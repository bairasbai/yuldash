"""RS-03 В: «Подъезжаю» сверяет машину с ТОЧНОЙ точкой встречи брони, когда она задана.

Матрица (план §7): 4 км от точки — проходит, без галочки и без отказа; 300 м — «подтверждено
по GPS»; 7 км — отказ `arriving_too_far`, записей нет; выключатель
`arrival_verify_meeting_enabled` — прежний путь (остановка/пин). Булавка на глаз: ложный
отказ дороже — молчаливое «прошло без галочки» между 800 м и 5 км, как у пина.
"""
from __future__ import annotations

from datetime import timedelta

import pytest
from sqlmodel import Session

import app.services as svc
from app import livepos
from app.config import settings
from app.db import engine
from app.geo import snapshot_cached
from app.models import Booking, Ride, UserRole
from app.ride_stops import stop_point
from app.timeutil import utcnow

STOPS = "Ургаза | Бурибай"
SIBAY = (52.716, 58.664)


def _when() -> str:
    # Выезд 5 минут назад: «Подъезжаю» уже открыто (за 30 минут до выезда), стадия не мешает.
    return (utcnow() - timedelta(minutes=5)).replace(microsecond=0).isoformat() + "+00:00"


@pytest.fixture
def redis_on():
    import fakeredis
    from app import instant_service as isv
    r = fakeredis.FakeStrictRedis(decode_responses=True)
    isv._redis_override = r
    yield r
    isv._redis_override = None


@pytest.fixture
def pushes(monkeypatch) -> list:
    got: list = []
    monkeypatch.setattr(svc, "send_push",
                        lambda session, uid, title, body, **k: got.append((uid, title, body)))
    return got


def _trip_with_point(client, user_factory, tag: str):
    """Рейс + бронь с ТОЧНОЙ точкой встречи у Ургазы. Возвращает (drv, pax, ride, bid, точка)."""
    drv = user_factory(f"MA {tag} Driver", role=UserRole.driver)
    pax = user_factory(f"MA {tag} Pax")
    body = {"from_city": "Сибай", "to_city": "Акъяр", "depart_at": _when(), "seats_total": 3,
            "price": 700, "comment": f"ma-{tag}", "waypoints": STOPS, "pickup": "У автовокзала",
            "pickup_lat": SIBAY[0], "pickup_lng": SIBAY[1]}
    ride = client.post("/rides", headers=drv["auth"], json=body).json()
    with Session(engine) as s:
        sp = stop_point(s.get(Ride, ride["id"]), "Ургаза", snapshot_cached())
        assert sp is not None
    meet = (sp.lat + 0.003, sp.lng + 0.003)   # ≈400 м от опоры остановки — точно в селе
    b = client.post("/bookings", headers=pax["auth"], json={
        "ride_id": ride["id"], "seats": 1, "pickup_stop": "Ургаза",
        "meeting": {"lat": meet[0], "lng": meet[1], "note": "у школы"}})
    assert b.status_code == 200, b.text
    bid = b.json()["id"]
    assert client.post(f"/bookings/{bid}/confirm", headers=drv["auth"]).status_code == 200
    return drv, pax, ride, bid, meet


def _arriving(client, drv, bid):
    return client.post(f"/bookings/{bid}/driver-status", headers=drv["auth"], json={"status": "arriving"})


def _driver_at(point, bid):
    livepos.livepos_set("booking", bid, point[0], point[1])


def _km(a, b) -> float:
    from app.services import haversine_km
    return haversine_km(a[0], a[1], b[0], b[1])


def _offset(meet, km_north: float) -> tuple:
    """Точка примерно в `km_north` км севернее цели (1° широты ≈ 111.32 км)."""
    return (meet[0] + km_north / 111.32, meet[1])


def test_300m_verified_4km_quiet_7km_refused(client, user_factory, redis_on):
    drv, pax, ride, bid, meet = _trip_with_point(client, user_factory, "matrix")
    # 7 км — отказ, ничего не записано.
    _driver_at(_offset(meet, 7.0), bid)
    far = _arriving(client, drv, bid)
    assert far.status_code == 409 and far.json()["detail"]["code"] == "arriving_too_far", far.text
    with Session(engine) as s:
        b = s.get(Booking, bid)
        assert b.arriving_at is None and b.arrival_verified is False
    # 4 км — проходит, но без галочки (булавка на глаз: ложный отказ дороже).
    _driver_at(_offset(meet, 4.0), bid)
    quiet = _arriving(client, drv, bid)
    assert quiet.status_code == 200, quiet.text
    with Session(engine) as s:
        b = s.get(Booking, bid)
        assert b.arrival_verified is False
    # 300 м — «подтверждено по GPS».
    _driver_at(_offset(meet, 0.3), bid)
    ok = _arriving(client, drv, bid)
    assert ok.status_code == 200, ok.text
    assert client.get(f"/bookings/{bid}/role", headers=pax["auth"]).json()["arrival_verified"] is True


def test_switch_off_falls_back_to_the_stop_path(client, user_factory, redis_on, monkeypatch):
    """Выключатель: точной цели нет — прежняя сверка с остановкой (5 км отказ / 2 км галочка)."""
    monkeypatch.setattr(settings, "arrival_verify_meeting_enabled", False)
    drv, pax, ride, bid, meet = _trip_with_point(client, user_factory, "off")
    # Машина у самой точки встречи — но цель выключена: считаем по остановке (≈0.4 км → галочка).
    _driver_at(meet, bid)
    ok = _arriving(client, drv, bid)
    assert ok.status_code == 200, ok.text
    assert client.get(f"/bookings/{bid}/role", headers=pax["auth"]).json()["arrival_verified"] is True


def test_without_meeting_point_nothing_changed(client, user_factory, redis_on):
    """Бронь без точной точки — прежний путь по остановке (RS-03 В не трогает старые брони)."""
    drv = user_factory("MA Plain Driver", role=UserRole.driver)
    pax = user_factory("MA Plain Pax")
    body = {"from_city": "Сибай", "to_city": "Акъяр", "depart_at": _when(), "seats_total": 3,
            "price": 700, "comment": "ma-plain", "waypoints": STOPS, "pickup": "У автовокзала",
            "pickup_lat": SIBAY[0], "pickup_lng": SIBAY[1]}
    ride = client.post("/rides", headers=drv["auth"], json=body).json()
    b = client.post("/bookings", headers=pax["auth"],
                    json={"ride_id": ride["id"], "seats": 1, "pickup_stop": "Ургаза"})
    bid = b.json()["id"]
    assert client.post(f"/bookings/{bid}/confirm", headers=drv["auth"]).status_code == 200
    with Session(engine) as s:
        sp = stop_point(s.get(Ride, ride["id"]), "Ургаза", snapshot_cached())
    _driver_at((sp.lat, sp.lng), bid)
    ok = _arriving(client, drv, bid)
    assert ok.status_code == 200, ok.text
    assert client.get(f"/bookings/{bid}/role", headers=pax["auth"]).json()["arrival_verified"] is True
