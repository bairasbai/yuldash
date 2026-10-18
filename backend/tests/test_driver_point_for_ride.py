"""Честное «Подъезжаю» видит машину по ЛЮБОЙ брони рейса (18.10.2026, PR0 «место посадки»).

Что было. Точку водителя кладёт в кэш (`livepos`) канал координат ОДНОЙ брони — той, чью
поездку водитель открыл («Едут с тобой» → бронь A). По брони B того же рейса кэш пуст. «Подъезжаю»
по B сверялось с кэшем B, не находило там ничего и пропускало слово водителя без проверки:
открыл бронь A, жмёшь «Подъезжаю» по B за 25 км — пассажирке B срочно уходит «Водитель
подъезжает», и она выходит к дороге за полчаса до машины (находка проверки безопасности, P1).

Что стало. Внутренний помощник `livepos.driver_point_for_ride` берёт самую свежую точку водителя
среди живых броней рейса (не старше двух минут). Его читает ТОЛЬКО проверка «Подъезжаю»: точка
никому не отдаётся — ни пассажиру B, ни его близким, ни ленте, ни карте, ни превью `/r/{id}`
(сторож внизу файла). Пассажиры точки друг друга не получают: в кэш броней пишет только водитель.
"""
from __future__ import annotations

import ast
import json
import pathlib
from datetime import timedelta

import fakeredis
import pytest
from sqlmodel import Session

from app import instant_service as isv
from app import livepos
from app.db import engine
from app.models import Booking, BookingStatus, Ride, UserRole
from app.timeutil import utcnow

# Сломанный канал должен краснеть за 30 с, а не держать CI.
pytestmark = pytest.mark.timeout(30)

BAYMAK = (52.5906, 58.3169)      # пин точки сбора
FAR = (52.7200, 58.6600)         # ≈ 25 км от пина (сторона Сибая)
NEAR = (52.5910, 58.3175)        # во дворе у пина


@pytest.fixture
def redis_on():
    r = fakeredis.FakeStrictRedis(decode_responses=True)
    isv._redis_override = r
    yield r
    isv._redis_override = None


def _trip_two_passengers(client, user_factory, tag: str, *, depart_in=timedelta(minutes=10)):
    """Рейс без остановок с пином, две подтверждённые брони (A и B). Выезд через 10 минут:
    точка водителя уже идёт (`trip_is_live`), «Подъезжаю» уже можно (за 30 минут до выезда)."""
    drv = user_factory(f"DPR Driver {tag}", role=UserRole.driver)
    pax_a = user_factory(f"DPR A {tag}")
    pax_b = user_factory(f"DPR B {tag}")
    r = client.post("/rides", headers=drv["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай", "comment": f"dpr-{tag}",
        "depart_at": (utcnow() + depart_in).isoformat() + "+00:00",
        "seats_total": 3, "price": 300, "pickup_lat": BAYMAK[0], "pickup_lng": BAYMAK[1],
    })
    assert r.status_code == 200, r.text
    ride = r.json()
    ids = []
    for pax in (pax_a, pax_b):
        b = client.post("/bookings", headers=pax["auth"], json={"ride_id": ride["id"], "seats": 1})
        assert b.status_code == 200, b.text
        assert client.post(f"/bookings/{b.json()['id']}/confirm", headers=drv["auth"]).status_code == 200
        ids.append(b.json()["id"])
    return drv, pax_a, pax_b, ride, ids[0], ids[1]


def _driver_sends_on(client, drv, pax, bid: int, point) -> None:
    """Водитель открыл поездку брони `bid` и прислал точку; пассажир этой брони её получил
    (барьер: кадр обработан сервером, а значит и записан в кэш)."""
    path = f"/ws/trip/{bid}/location"
    with client.websocket_connect(path) as ear:
        ear.send_text(json.dumps({"type": "auth", "token": pax["token"]}))
        with client.websocket_connect(path) as mouth:
            mouth.send_text(json.dumps({"type": "auth", "token": drv["token"]}))
            mouth.send_text(json.dumps({"type": "loc", "lat": point[0], "lng": point[1], "ts": 1}))
            got = json.loads(ear.receive_text())
            assert got["lat"] == pytest.approx(point[0]), got


def _arriving(client, drv, bid):
    return client.post(f"/bookings/{bid}/driver-status", headers=drv["auth"], json={"status": "arriving"})


def _booking(bid: int) -> Booking:
    with Session(engine) as s:
        return s.get(Booking, bid)


# ── Главный сценарий (P1) ──

def test_arriving_for_b_sees_the_point_sent_in_a(client, user_factory, redis_on):
    """Водитель шлёт точку только в канал брони A (за 25 км) и жмёт «Подъезжаю» по брони B →
    отказ «далеко»: проверка видит машину, хотя кэш B пуст. Пассажирке B ничего не ушло."""
    drv, pax_a, pax_b, _ride, a, b = _trip_two_passengers(client, user_factory, "p1")
    _driver_sends_on(client, drv, pax_a, a, FAR)
    assert livepos.livepos_get("booking", b) is None, "кэш B пуст — точка шла только в A"

    r = _arriving(client, drv, b)
    assert r.status_code == 409, r.text
    assert "км" in r.json()["detail"]["ru"] and "км" in r.json()["detail"]["ba"]
    got = _booking(b)
    assert got.driver_phase == "" and got.arriving_at is None, "отказ ничего не записал"
    # Помощник ничего не пишет в кэш B: пассажирка B и её близкие новых данных не получают.
    assert livepos.livepos_get("booking", b) is None


def test_arriving_for_b_near_the_pin_is_verified(client, user_factory, redis_on):
    """Та же схема, но водитель во дворе у пина: «Подъезжаю» по B проходит и подтверждено по GPS."""
    drv, pax_a, pax_b, _ride, a, b = _trip_two_passengers(client, user_factory, "near")
    _driver_sends_on(client, drv, pax_a, a, NEAR)
    r = _arriving(client, drv, b)
    assert r.status_code == 200, r.text
    role = client.get(f"/bookings/{b}/role", headers=pax_b["auth"]).json()
    assert role["driver_phase"] == "arriving" and role["arrival_verified"] is True


def test_no_point_anywhere_still_lets_the_driver_through(client, user_factory, redis_on):
    """Предохранитель прежний: точки нет ни в одной брони — не отказываем, но и не подтверждаем."""
    drv, _pax_a, pax_b, _ride, _a, b = _trip_two_passengers(client, user_factory, "none")
    r = _arriving(client, drv, b)
    assert r.status_code == 200, r.text
    assert client.get(f"/bookings/{b}/role", headers=pax_b["auth"]).json()["arrival_verified"] is False


# ── Помощник: что он берёт и чего не берёт ──

def _ride_with(statuses: list[BookingStatus], user_factory) -> tuple[int, list[int]]:
    drv = user_factory("DPR Unit Driver", role=UserRole.driver)
    with Session(engine) as s:
        ride = Ride(driver_id=drv["id"], from_city="Баймак", to_city="Сибай",
                    depart_at=utcnow() + timedelta(minutes=10), seats_total=4, seats_left=4)
        s.add(ride)
        s.commit()
        s.refresh(ride)
        ids = []
        for st in statuses:
            pax = user_factory("DPR Unit Pax")
            bk = Booking(ride_id=ride.id, passenger_id=pax["id"], status=st)
            s.add(bk)
            s.commit()
            s.refresh(bk)
            ids.append(bk.id)
        return ride.id, ids


def _point(ride_id: int):
    with Session(engine) as s:
        return livepos.driver_point_for_ride(s, s.get(Ride, ride_id))


def _put(redis, bid: int, point, age_sec: float | None) -> None:
    """Точка водителя в кэше брони `bid`, записанная `age_sec` секунд назад (None — без метки)."""
    payload = {"lat": point[0], "lng": point[1], "bearing": None,
               "ts": (utcnow() - timedelta(seconds=age_sec)).isoformat() if age_sec is not None else ""}
    redis.set(f"livepos:booking:{bid}", json.dumps(payload), ex=livepos.LIVEPOS_TTL_SEC)


def test_freshest_point_wins(user_factory, redis_on):
    ride, (a, b) = _ride_with([BookingStatus.confirmed, BookingStatus.onboard], user_factory)
    _put(redis_on, a, FAR, 60)
    _put(redis_on, b, NEAR, 5)
    got = _point(ride)
    assert (got["lat"], got["lng"]) == NEAR


def test_stale_point_is_ignored(user_factory, redis_on):
    """Точка старше двух минут (машина могла уехать) не годится — даже если кэш её ещё держит."""
    ride, (a,) = _ride_with([BookingStatus.confirmed], user_factory)
    _put(redis_on, a, FAR, livepos.LIVEPOS_TTL_SEC + 5)
    assert _point(ride) is None


def test_point_without_timestamp_ranks_below_a_fresh_one(user_factory, redis_on):
    ride, (a, b) = _ride_with([BookingStatus.confirmed, BookingStatus.confirmed], user_factory)
    _put(redis_on, a, FAR, None)
    assert _point(ride)["lat"] == FAR[0], "без метки — лучше, чем ничего"
    _put(redis_on, b, NEAR, 100)
    assert _point(ride)["lat"] == NEAR[0]


def test_cancelled_and_done_bookings_do_not_count(user_factory, redis_on):
    ride, (a, b, c) = _ride_with([BookingStatus.cancelled, BookingStatus.done, BookingStatus.pending], user_factory)
    _put(redis_on, a, FAR, 1)
    _put(redis_on, b, FAR, 1)
    assert _point(ride) is None
    _put(redis_on, c, NEAR, 1)
    assert _point(ride)["lat"] == NEAR[0], "ожидающая бронь — живая"


def test_other_rides_points_are_not_mixed_in(user_factory, redis_on):
    ride, (a,) = _ride_with([BookingStatus.confirmed], user_factory)
    _other, (x,) = _ride_with([BookingStatus.confirmed], user_factory)
    _put(redis_on, x, FAR, 1)
    assert _point(ride) is None


def test_without_redis_there_is_no_point(user_factory):
    isv._redis_override = None
    ride, (_a,) = _ride_with([BookingStatus.confirmed], user_factory)
    assert _point(ride) is None


# ── Сторож: помощник — только внутренний ──

APP = pathlib.Path(__file__).resolve().parents[1] / "app"
HELPER = "driver_point_for_ride"
# Где помощник МОЖНО упоминать: (файл, функция верхнего уровня). Каждая новая строка — сознательное
# решение «кому и когда отдаём точку водителя», с проверкой безопасности (§8, гео).
ALLOWED = {
    ("livepos.py", HELPER),                    # определение
    ("routers/bookings.py", "_verify_arrival"),  # честное «Подъезжаю»: отвечает водителю «далеко/рядом»
}
# Публичные выдачи, где точки водителя не будет НИКОГДА: лента, «Ближайшие», города, карточка
# поездки, сигнальный канал карты, витрина `/r/{id}` (P3 второй проверки безопасности).
PUBLIC = {
    "routers/rides.py": None,                  # весь файл: лента, near, cities, карточка
    "routers/location.py": {"map_feed"},
    "routers/share.py": {"ride_preview", "ride_share_page", "_preview_dict", "_shareable", "_render_html"},
}


def _mentions(path: pathlib.Path, base: pathlib.Path = APP) -> set[tuple[str, str]]:
    """Где в файле упомянут помощник: {(файл, функция верхнего уровня или «<module>»)}."""
    tree = ast.parse(path.read_text(encoding="utf-8"))
    rel = path.relative_to(base).as_posix()
    found: set[tuple[str, str]] = set()

    def hit(node) -> bool:
        return ((isinstance(node, ast.Name) and node.id == HELPER)
                or (isinstance(node, ast.Attribute) and node.attr == HELPER)
                or (isinstance(node, ast.alias) and (node.name == HELPER or node.asname == HELPER))
                or (isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)) and node.name == HELPER))

    for top in tree.body:
        owner = top.name if isinstance(top, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)) else "<module>"
        if any(hit(n) for n in ast.walk(top)):
            found.add((rel, owner))
    return found


def _all_mentions() -> set[tuple[str, str]]:
    out: set[tuple[str, str]] = set()
    for path in APP.rglob("*.py"):
        out |= _mentions(path)
    return out


def test_helper_is_used_only_where_allowed():
    extra = _all_mentions() - ALLOWED
    assert not extra, (
        f"точка водителя по рейсу ушла туда, где её не разрешали: {sorted(extra)}. Это гео (§8): "
        "сначала план и проверка безопасности, потом строка в ALLOWED")


def test_helper_never_reaches_public_feeds():
    for rel, funcs in PUBLIC.items():
        hits = {owner for f, owner in _mentions(APP / rel) if f == rel}
        bad = hits if funcs is None else hits & funcs
        assert not bad, f"{rel}: публичная выдача читает точку водителя ({sorted(bad)})"


def test_the_guard_sees_a_planted_mention(tmp_path):
    """Сторож не слепой: упоминание в публичной функции он находит (иначе тест выше зелёный зря)."""
    fake_app = tmp_path / "app"
    (fake_app / "routers").mkdir(parents=True)
    (fake_app / "routers" / "rides.py").write_text(
        "from ..livepos import driver_point_for_ride\n"
        "def rides_near(session, ride):\n    return driver_point_for_ride(session, ride)\n",
        encoding="utf-8")
    found = _mentions(fake_app / "routers" / "rides.py", fake_app)
    assert ("routers/rides.py", "rides_near") in found and ("routers/rides.py", "<module>") in found
