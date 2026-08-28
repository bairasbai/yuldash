"""Чужой объект по прямой ссылке: сквозная проверка всех адресов с номером внутри.

Экран показывает человеку только его собственное. Но адрес вида `/bookings/17/details`
угадывается за секунду: поменял число — и запрос ушёл. Проверять это должен сервер, каждый
раз, на каждой ручке. Таких ручек в проекте больше сотни, и «забыли одну» здесь означает
чужую переписку, чужой телефон или чужую отмену поездки.

Тест не ведёт список ручек. Он сам обходит маршруты, подставляет номер объекта, созданного
ПЕРВЫМ человеком, и зовёт от имени ВТОРОГО — постороннего, который к этому объекту отношения
не имеет. Успешный ответ (2xx) считается находкой.

Отдельно ведётся список того, что проверить не удалось (нет объекта такого типа) — он
печатается и ограничен сверху, чтобы «не проверили» не выглядело как «проверили».
"""
from __future__ import annotations

import inspect
import re

import pytest

from app.config import settings
from app.main import app
from app.models import UserRole
from app.db import engine
from app.models import InstantOrder, InstantOrderStatus
from sqlmodel import Session

from test_api import _ride

# Адреса, где чужой номер — норма, с причиной.
_PUBLIC_BY_DESIGN = {
    "/rides/{ride_id}",                 # витрина поездки: её и должно быть видно всем
    "/r/{ride_id}", "/r/{ride_id}/preview",   # страница «поделиться поездкой»
    "/drivers/{driver_id}/public", "/drivers/{driver_id}/schedule",
    "/users/{user_id}/trust",           # витрина доверия: рейтинг и число поездок, без личного
    "/coupons/{coupon_id}",             # купон бизнеса — публичное предложение
    "/medical-partners/{partner_id}",   # справочник клиник
    "/t/{token}", "/t/{token}/state.json",    # капабилити-ссылка (проверяется отдельно)
    "/secure/docs/{name}", "/secure/evidence/{name}",  # проверяются отдельно, по владению файлом
    # Действия, открытые ЛЮБОМУ по замыслу — в этом весь смысл ленты:
    "/requests/{request_id}/respond",   # откликнуться на чужую заявку и должен уметь любой водитель
    "/parcels/{parcel_id}/accept",      # взять чужую посылку — работа курьера
}

# Методы, которые тест не дёргает: они меняют состояние необратимо и проверяются точечно.
_SAFE_METHODS = ("GET", "POST", "DELETE", "PATCH")


def _walk(routes, prefix: str = ""):
    for r in routes:
        path = getattr(r, "path", None)
        if path:
            yield prefix + path, r
        original = getattr(r, "original_router", None)
        if original is not None:
            ctx = getattr(r, "include_context", None)
            yield from _walk(original.routes, prefix + (getattr(ctx, "prefix", "") or ""))


def _id_routes() -> list[tuple[str, str]]:
    """(метод, адрес) для всех НЕ админских ручек с номером в пути и с авторизацией."""
    seen: set[tuple[str, str]] = set()
    out: list[tuple[str, str]] = []
    for path, route in _walk(app.routes):
        bare = path[len("/api/v1"):] if path.startswith("/api/v1") else path
        if bare.startswith("/admin") or "{" not in bare:
            continue
        endpoint = getattr(route, "endpoint", None)
        if endpoint is None:
            continue
        try:
            src = inspect.getsource(endpoint)
        except (OSError, TypeError):
            continue
        if "current_user" not in src:        # открытая ручка — не про доступ к чужому
            continue
        for method in sorted(getattr(route, "methods", None) or []):
            if method not in _SAFE_METHODS:
                continue
            key = (method, bare)
            if key in seen:
                continue
            seen.add(key)
            out.append(key)
    return out


ID_ROUTES = _id_routes()


@pytest.fixture(scope="module")
def owner_objects(client, user_factory_module):
    """Первый человек и по одному объекту каждого вида — чужой для всех остальных."""
    monkey_courier = settings.courier_enabled
    settings.courier_enabled = True
    try:
        driver = user_factory_module("SweepOwnerDrv", role=UserRole.driver)
        pax = user_factory_module("SweepOwnerPax")
        ids: dict[str, int] = {}

        ride_id = _ride(client, driver, seats=3)
        ids["ride_id"] = ride_id

        booking = client.post("/bookings", headers=pax["auth"],
                              json={"ride_id": ride_id, "seats": 1})
        assert booking.status_code == 200, f"бронь не создалась: {booking.text[:200]}"
        ids["booking_id"] = booking.json()["id"]
        assert client.post(f"/bookings/{ids['booking_id']}/confirm",
                           headers=driver["auth"]).status_code == 200

        req = client.post("/requests", headers=pax["auth"], json={
            "from_city": "Сибай", "to_city": "Уфа", "seats": 1,
        })
        assert req.status_code == 200, f"заявка не создалась: {req.text[:200]}"
        ids["request_id"] = req.json()["id"]

        resp = client.post(f"/requests/{ids['request_id']}/respond",
                           headers=driver["auth"], json={"price": 500})
        assert resp.status_code == 200, f"отклик не создался: {resp.text[:200]}"
        ids["response_id"] = resp.json()["id"]

        parcel = client.post("/parcels", headers=pax["auth"], json={
            "from_city": "Баймак", "to_city": "Сибай", "size": "small",
            "description": "гостинцы", "receiver_name": "Гөлнара",
            "receiver_phone": "+79990006001", "rules_accepted": True,
        })
        assert parcel.status_code == 200, f"посылка не создалась: {parcel.text[:200]}"
        ids["parcel_id"] = parcel.json()["id"]

        # Такси-заказ. Без него сторож не проверял НИ ОДНОГО адреса вида
        # /instant/orders/{order_id}/... — а их полтора десятка, и все про живую поездку
        # с чужими координатами и телефонами. Создаём прямо в базе: обычный путь требует
        # включённого такси, одобренной заявки и свободного водителя рядом, а сторожу нужен
        # только объект с владельцем.
        with Session(engine) as s_ord:
            order = InstantOrder(
                passenger_id=pax["id"], driver_id=driver["id"], status=InstantOrderStatus.onboard,
                from_lat=54.7351, from_lng=55.9587, to_lat=54.7450, to_lng=55.9700,
                distance_km=3.0, eta_min=8.0, price_estimate=200, category="standard",
            )
            s_ord.add(order)
            s_ord.commit()
            s_ord.refresh(order)
            ids["order_id"] = order.id

        ids["user_id"] = pax["id"]
        ids["driver_id"] = driver["id"]
        return {"driver": driver, "pax": pax, "ids": ids}
    finally:
        settings.courier_enabled = monkey_courier


@pytest.fixture(scope="module")
def user_factory_module(client):
    """Тот же завод пользователей, но живёт на весь модуль — объекты-«чужие» создаём один раз."""
    from sqlmodel import Session

    from app.db import engine
    from app.models import TaxiApplication, TaxiApplicationStatus, User
    from app.security import make_token

    counter = {"n": 0}

    def make(name="User", role=UserRole.passenger):
        counter["n"] += 1
        i = counter["n"]
        with Session(engine) as s:
            u = User(phone=f"sweep-{i}", name=name, telegram_id=f"sweep{i}",
                     verified=True, role=role)
            s.add(u)
            s.commit()
            s.refresh(u)
            if role == UserRole.driver:
                s.add(TaxiApplication(user_id=u.id, status=TaxiApplicationStatus.approved))
                s.commit()
            token = make_token(u.id)
            return {"id": u.id, "token": token, "auth": {"Authorization": f"Bearer {token}"}}
    return make


def test_адреса_с_номером_вообще_нашлись():
    """Страховка от «зелено на пустом множестве»."""
    assert len(ID_ROUTES) >= 50, f"Нашли всего {len(ID_ROUTES)} адресов с номером — разбор сломался"


def test_посторонний_не_попадает_в_чужие_объекты(client, owner_objects, user_factory_module):
    """Главная проверка: чужой номер в адресе не должен давать успешный ответ."""
    stranger = user_factory_module("SweepStranger")
    ids = owner_objects["ids"]

    opened: list[str] = []
    unchecked: list[str] = []
    for method, path in ID_ROUTES:
        if path in _PUBLIC_BY_DESIGN:
            continue
        params = re.findall(r"\{([a-z_]+)\}", path)
        if not params or any(p not in ids for p in params):
            unchecked.append(f"{method} {path}")
            continue
        url = path
        for p in params:
            url = url.replace("{" + p + "}", str(ids[p]))
        r = client.request(method, url, headers=stranger["auth"], json={})
        if 200 <= r.status_code < 300:
            opened.append(f"{method} {path} → {r.status_code} {r.text[:120]}")

    checked = sum(1 for m, pth in ID_ROUTES
                  if pth not in _PUBLIC_BY_DESIGN
                  and re.findall(r"\{([a-z_]+)\}", pth)
                  and all(x in ids for x in re.findall(r"\{([a-z_]+)\}", pth)))
    assert checked >= 30, (
        f"Реально проверено всего {checked} адресов из {len(ID_ROUTES)} — этого мало, "
        "чтобы называть сквозной проверкой. Заведи объекты для непроверенных видов."
    )
    assert not opened, (
        "Посторонний получил доступ к чужим объектам по прямой ссылке:\n  "
        + "\n  ".join(opened)
    )
    # Не молчим о том, что проверить не удалось: иначе «не проверили» выглядит как «проверили».
    assert len(unchecked) <= 60, (
        f"Слишком много непроверенных адресов ({len(unchecked)}) — заведи для них объекты:\n  "
        + "\n  ".join(sorted(unchecked)[:30])
    )


def test_контроль_владелец_в_свои_объекты_попадает(client, owner_objects):
    """Без этого контроля проверка выше зеленела бы и в случае «сервер закрыт для всех».
    Владелец обязан открывать своё."""
    ids = owner_objects["ids"]
    pax = owner_objects["pax"]
    ok = client.get(f"/bookings/{ids['booking_id']}/details", headers=pax["auth"])
    assert ok.status_code == 200, (
        f"владелец не открыл свою бронь: {ok.status_code} {ok.text[:200]} — "
        "значит проверка «посторонний не открыл» ничего не доказывает"
    )
