"""Что видит человек БЕЗ входа в приложение — то есть кто угодно из интернета.

Большая часть сервера закрыта авторизацией, но часть ручек открыта намеренно: лендинг тянет
отзывы и статистику, страница трекинга работает без приложения, справочник населённых пунктов
открыт по смыслу. Эти адреса знает поисковик и любой, кто откроет вкладку.

Опасность не в том, что ручка открыта, а в том, что через неё утекает лишнее: телефон,
точный адрес, фамилия, координаты дома. Один забытый ключ в ответе — и «между своими»
превращается в справочник по деревне.

Тест сам находит ВСЕ открытые адреса (без авторизации), зовёт каждый анонимно и смотрит,
что вернулось. Новая публичная ручка попадает под проверку автоматически: список в тесте
не ведётся.
"""
from __future__ import annotations

import re

import pytest
from sqlmodel import Session

from app.db import engine
from app.main import app
from app.models import User, UserRole

from test_api import _ride

# Ключи, которых в публичном ответе быть не должно ни при каких обстоятельствах.
_FORBIDDEN_KEYS = {
    "phone", "user_phone", "driver_phone", "passenger_phone", "sender_phone",
    "receiver_phone", "admin_phone", "from_address", "confirm_code",
    "telegram_id", "license_url", "criminal_record_url", "osago_url", "selfie_url",
}

# Поля, которые на конкретном адресе публичны ОСОЗНАННО, с причиной. Каждая строка здесь —
# исключение, которое кто-то должен был обдумать; пустой список лучше длинного.
_ALLOWED = {
    # Телефон БИЗНЕСА в «Скидках по пути»: кафе публикует свой номер, чтобы по нему звонили.
    # Это не номер пользователя — те проверяются отдельным тестом по самому значению.
    ("/coupons", "phone"),
    ("/coupons/{coupon_id}", "phone"),
}

# Открытые по смыслу и проверяемые отдельно (нужен токен/особый разбор).
_SKIP_PATHS = {
    "/t/{token}", "/t/{token}/state.json",     # капабилити-ссылка: проверяется в test_location_privacy
    "/payments/yookassa/webhook",              # вход платёжного провайдера, не витрина
    "/health", "/healthz", "/version/min",
    # Схема API и интерактивные доки. В ТЕСТАХ они включены (ENV=dev) и, конечно, содержат
    # имена всех полей, включая criminal_record_url и confirm_code — это описание, а не данные.
    # На проде они выключены; это проверяется отдельным тестом ниже, а не пропуском.
    "/openapi.json", "/docs", "/redoc", "/docs/oauth2-redirect",
}


def _walk(routes, prefix: str = ""):
    for r in routes:
        path = getattr(r, "path", None)
        if path:
            yield prefix + path, r
        original = getattr(r, "original_router", None)
        if original is not None:
            ctx = getattr(r, "include_context", None)
            yield from _walk(original.routes, prefix + (getattr(ctx, "prefix", "") or ""))


def _public_get_routes() -> list[str]:
    """GET-адреса, которым НЕ нужна авторизация. Определяем по зависимостям маршрута:
    у закрытых в цепочке стоит current_user."""
    seen: set[str] = set()
    out: list[str] = []
    for path, route in _walk(app.routes):
        bare = path[len("/api/v1"):] if path.startswith("/api/v1") else path
        if bare in seen or "GET" not in (getattr(route, "methods", None) or set()):
            continue
        seen.add(bare)
        endpoint = getattr(route, "endpoint", None)
        if endpoint is None:
            continue
        import inspect
        try:
            src = inspect.getsource(endpoint)
        except (OSError, TypeError):
            continue
        # current_user → закрыто; current_user_optional → открыто, но с бонусом для вошедших.
        if "current_user)" in src or "Depends(current_user)" in src:
            continue
        if bare.startswith("/admin"):
            continue
        out.append(bare)
    return sorted(out)


PUBLIC = _public_get_routes()


def test_публичные_адреса_вообще_нашлись():
    """Страховка от «зелено на пустом множестве»: если разбор маршрутов сломается,
    проверка ниже пройдёт ни по чему."""
    assert len(PUBLIC) >= 5, f"Нашли всего {len(PUBLIC)} открытых адресов — разбор сломался"


def _give_phone(user_id: int) -> str:
    """Уникальный запоминающийся номер, выведенный ИЗ id пользователя.

    Не счётчик: свой счётчик уже завёл `test_antifraud` с тем же префиксом, и в полном прогоне
    номера столкнулись на уникальном индексе (найдено 2026-08-06). id уникален по построению,
    поэтому столкнуться не с чем."""
    phone = f"+7{900_000_000 + user_id:09d}"
    with Session(engine) as s:
        u = s.get(User, user_id)
        u.phone = phone
        s.add(u)
        s.commit()
    return phone


def _seed_real_data(client, user_factory):
    """Настоящие люди и поездка — иначе публичные ленты вернут пустоту и проверять будет нечего.
    Возвращает телефоны, которых в публичных ответах быть не должно."""
    driver = user_factory("PubDrv", role=UserRole.driver)
    drv_phone = _give_phone(driver["id"])
    _ride(client, driver, seats=3)
    pax = user_factory("PubPax")
    pax_phone = _give_phone(pax["id"])
    client.post("/requests", headers=pax["auth"], json={
        "from_city": "Сибай", "to_city": "Уфа", "seats": 1,
    })
    client.post("/reviews", headers=pax["auth"], json={"stars": 5, "text": "хорошо"})
    return drv_phone, pax_phone


def test_ни_один_открытый_адрес_не_отдаёт_телефон(client, user_factory):
    """Главная проверка: анонимный запрос по каждому открытому адресу — и в ответе не должно
    быть ни одного телефона живого человека."""
    phones = _seed_real_data(client, user_factory)
    leaked: list[str] = []
    for path in PUBLIC:
        if path in _SKIP_PATHS:
            continue
        url = re.sub(r"\{[^}]*\}", "1", path)
        r = client.get(url)          # БЕЗ заголовка авторизации — как из браузера
        if r.status_code != 200:
            continue
        body = r.text
        for phone in phones:
            if phone in body:
                leaked.append(f"{path} → {phone}")
    assert not leaked, "Открытые адреса отдают телефоны живых людей:\n  " + "\n  ".join(leaked)


def test_ни_один_открытый_адрес_не_отдаёт_запретных_полей(client, user_factory):
    """Не только телефон: документы водителя, адрес отправления, код вручения, telegram_id.
    Ищем по именам ключей — так ловится и поле, которого ещё не было, когда тест писали."""
    _seed_real_data(client, user_factory)
    leaked: list[str] = []
    for path in PUBLIC:
        if path in _SKIP_PATHS:
            continue
        url = re.sub(r"\{[^}]*\}", "1", path)
        r = client.get(url)
        if r.status_code != 200:
            continue
        for key in _FORBIDDEN_KEYS:
            if (path, key) in _ALLOWED:
                continue
            if f'"{key}"' in r.text:
                leaked.append(f"{path} → поле {key}")
    assert not leaked, (
        "Открытые адреса отдают чувствительные поля:\n  " + "\n  ".join(sorted(set(leaked)))
    )


def test_закрытые_адреса_анонима_не_пускают(client, user_factory):
    """Контроль к тестам выше. Без него они зеленели бы и в случае «сервер вообще ничего
    никому не отдаёт»: надо убедиться, что закрытые адреса действительно закрыты,
    а открытые действительно отвечают."""
    closed = client.get("/bookings/mine")
    assert closed.status_code in (401, 403), (
        f"личный список броней открыт анониму: {closed.status_code} {closed.text[:150]}"
    )
    opened = client.get("/reviews/public")
    assert opened.status_code == 200, (
        f"публичные отзывы не отвечают анониму: {opened.status_code} — тогда проверки утечки "
        "ничего не доказывают"
    )


@pytest.mark.parametrize("path", [p for p in PUBLIC if p not in _SKIP_PATHS][:40],
                         ids=lambda p: p)
def test_открытый_адрес_не_падает_на_аноним(client, path):
    """Открытая ручка не должна отвечать 500 анониму: это и утечка стектрейса, и просто
    сломанная страница у того, кто пришёл из поиска."""
    url = re.sub(r"\{[^}]*\}", "1", path)
    r = client.get(url)
    assert r.status_code < 500, f"{path} упал на анонимном запросе: {r.status_code} {r.text[:200]}"


def test_на_проде_схема_api_закрыта(monkeypatch):
    """Интерактивная схема (/docs, /openapi.json) — это карта ВСЕХ ручек, включая админские
    и платёжные, с именами полей вроде criminal_record_url. В dev она удобна, на проде это
    подарок тому, кто ищет, куда постучаться.

    Проверяем не «сейчас выключено», а что выключение привязано к признаку прода: иначе
    достаточно одной правки в create_app, чтобы схема тихо открылась наружу."""
    from app.config import settings
    from app.main import create_app

    monkeypatch.setattr(settings, "env", "production", raising=False)
    assert settings.is_prod, "признак прода не поднялся — проверка ничего не докажет"

    prod_app = create_app()
    assert prod_app.openapi_url is None, "на проде /openapi.json открыт — это карта всех ручек"
    assert prod_app.docs_url is None, "на проде /docs открыт"
    assert prod_app.redoc_url is None, "на проде /redoc открыт"


def test_контроль_в_dev_схема_открыта():
    """Обратная сторона: если бы схема была выключена всегда, тест выше проходил бы
    по неправильной причине."""
    from app.main import app as dev_app
    assert dev_app.openapi_url is not None, (
        "схема выключена и в dev — тогда проверка «на проде выключена» ничего не доказывает"
    )


# ---------- Точное место встречи ----------
# Правило: город и маршрут видны всем, а «где именно встречаемся» — только участнику
# подтверждённой брони. Иначе по объявлению «Сибай → Уфа, 8:00, у школы №3, второй подъезд»
# посторонний знает, где и когда будет стоять конкретная женщина.
#
# Скрывает это `services.public_ride_payload`, но применяется он ПОШТУЧНО на каждой выдаче —
# ровно тот случай, где легко забыть одно место. Поэтому проверяем не функцию, а все выдачи.

_PICKUP = "у школы №3, второй подъезд"


def _ride_with_pickup(client, driver) -> int:
    from datetime import timedelta

    from app.config import settings as _s
    from app.timeutil import utcnow as _now
    depart = (_now() + timedelta(hours=_s.local_tz_offset_hours, days=1)).replace(
        microsecond=0).isoformat()
    r = client.post("/rides", headers=driver["auth"], json={
        "from_city": "Сибай", "to_city": "Уфа", "seats": 3, "price": 500,
        "depart_at": depart, "pickup": _PICKUP, "pickup_lat": 52.9128, "pickup_lng": 58.6689,
    })
    assert r.status_code == 200, f"поездка не создалась: {r.status_code} {r.text[:200]}"
    return r.json()["id"]


def test_точное_место_встречи_не_видно_анониму(client, user_factory):
    """Главная проверка: ни одна открытая выдача не должна показывать точку сбора."""
    driver = user_factory("PickupDrv", role=UserRole.driver)
    ride_id = _ride_with_pickup(client, driver)

    leaked = []
    for url in ("/rides", f"/rides/{ride_id}", "/rides/near?from_city=Сибай&to_city=Уфа",
                "/feed", f"/r/{ride_id}", f"/r/{ride_id}/preview"):
        r = client.get(url)
        if r.status_code == 200 and _PICKUP in r.text:
            leaked.append(url)
    assert not leaked, (
        "Точка сбора видна без входа в приложение — посторонний знает, где и когда будет "
        f"стоять человек: {leaked}"
    )


def test_контроль_поездка_в_открытой_выдаче_есть(client, user_factory):
    """Без этого контроля проверка выше зеленела бы просто потому, что поездки нет в ленте."""
    driver = user_factory("PickupCtlDrv", role=UserRole.driver)
    ride_id = _ride_with_pickup(client, driver)
    r = client.get(f"/rides/{ride_id}")
    assert r.status_code == 200, f"карточка поездки не отвечает анониму: {r.status_code}"
    assert "Сибай" in r.text, "в карточке нет даже города — выдача пустая, проверка бессмысленна"


def test_участник_подтверждённой_брони_место_встречи_видит(client, user_factory):
    """Обратная сторона: тому, кто реально едет, точка сбора нужна — иначе он не найдёт машину."""
    driver = user_factory("PickupOkDrv", role=UserRole.driver)
    ride_id = _ride_with_pickup(client, driver)
    pax = user_factory("PickupOkPax")
    bid = client.post("/bookings", headers=pax["auth"],
                      json={"ride_id": ride_id, "seats": 1}).json()["id"]
    assert client.post(f"/bookings/{bid}/confirm", headers=driver["auth"]).status_code == 200

    details = client.get(f"/bookings/{bid}/details", headers=pax["auth"])
    assert details.status_code == 200, details.text
    assert _PICKUP in details.text, (
        "пассажир подтверждённой брони не видит точку сбора — он не найдёт машину"
    )


def test_до_подтверждения_места_встречи_ещё_нет(client, user_factory):
    """Бронь создана, но не подтверждена — договорённости ещё нет, адрес рано."""
    driver = user_factory("PickupPendDrv", role=UserRole.driver)
    ride_id = _ride_with_pickup(client, driver)
    pax = user_factory("PickupPendPax")
    bid = client.post("/bookings", headers=pax["auth"],
                      json={"ride_id": ride_id, "seats": 1}).json()["id"]
    details = client.get(f"/bookings/{bid}/details", headers=pax["auth"])
    assert details.status_code == 200, details.text
    assert _PICKUP not in details.text, (
        "точка сбора отдана до подтверждения брони — водитель ещё никого не звал"
    )


def test_разрешённые_исключения_не_протухли():
    """Список осознанных исключений не должен разрастаться и не должен ссылаться на адреса,
    которых уже нет: и то и другое означает, что его перестали читать."""
    assert len(_ALLOWED) <= 4, (
        f"Исключений стало {len(_ALLOWED)}. Каждое — публичное поле, которое кто-то однажды "
        "обдумал; длинный список означает, что их перестали обдумывать."
    )
    unknown = sorted({path for path, _ in _ALLOWED} - set(PUBLIC))
    assert not unknown, (
        f"Исключение выписано на адрес, которого среди открытых больше нет: {unknown}. "
        "Убери строку — иначе она молча прикрывает что-то другое."
    )
