# -*- coding: utf-8 -*-
"""Время подачи и «застрял на трассе» для такси (аудит 2026-07-26).

- На вопрос «когда приедет машина?» ответа не было вообще: оценка отдавала только длительность
  самой поездки А→Б, и клиент честно писал «N мин в пути» — но это про другое.
- Зимний протокол «застрял на трассе» работал ТОЛЬКО для попуток, хотя именно в такси зимой
  четыре часа трассы Сибай–Уфа. Застрявшему в такси идти было некуда, кроме красной кнопки SOS.
"""
import pytest
from sqlmodel import Session, select

from app import instant_service as isv
from app import models as M
from app import pricing
from app.db import engine
from app.models import InstantOrderStatus as S, UserRole
from app.timeutil import utcnow


@pytest.fixture(autouse=True)
def _quiet(monkeypatch):
    monkeypatch.setattr("app.routers.safety.notify_admin_telegram", lambda *a, **k: None)
    monkeypatch.setattr("app.routers.safety._send_sos_sms", lambda *a, **k: None)


def _order(passenger_id: int, driver_id: int, status=S.onboard) -> int:
    with Session(engine) as s:
        o = M.InstantOrder(
            passenger_id=passenger_id, driver_id=driver_id, status=status,
            from_lat=52.59, from_lng=58.31, to_lat=54.73, to_lng=55.97,
            from_text="Сибай", to_text="Уфа", price_estimate=2500,
        )
        s.add(o); s.commit(); s.refresh(o)
        return o.id


# ============================== время подачи ==============================

def test_estimate_reports_pickup_eta_separately(client, user_factory, monkeypatch):
    """«Через сколько приедет» и «сколько ехать» — разные числа, и оба честные."""
    monkeypatch.setattr(isv, "nearby_drivers", lambda lat, lng, limit=8, **kw: [{"lat": lat, "lng": lng, "eta_min": 4}])
    monkeypatch.setattr(
        pricing,
        "route_metrics",
        lambda frm, to: pricing.RouteMetrics(
            distance_km=7.5,
            duration_min=13.7,
            source="test",
        ),
    )
    u = user_factory("Юлаусы")
    r = client.post("/instant/estimate", headers=u["auth"], json={
        "from_lat": 52.59, "from_lng": 58.31, "to_lat": 52.70, "to_lng": 58.40,
    })
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["pickup_eta_min"] == 4
    assert body["eta_min"] == 13.7


def test_estimate_pickup_eta_is_null_when_no_cars(client, user_factory, monkeypatch):
    """Рядом никого → честное «не знаю», а не выдуманное число."""
    monkeypatch.setattr(isv, "nearby_drivers", lambda lat, lng, limit=8, **kw: [])
    u = user_factory("Юлаусы2")
    r = client.post("/instant/estimate", headers=u["auth"], json={
        "from_lat": 52.59, "from_lng": 58.31, "to_lat": 52.70, "to_lng": 58.40,
    })
    assert r.status_code == 200, r.text
    assert r.json()["pickup_eta_min"] is None


# ============================== «застрял на трассе» ==============================

def test_passenger_can_call_roadside_help_from_taxi_order(client, user_factory):
    pax = user_factory("Юлаусы3")
    drv = user_factory("Водитель", role=UserRole.driver)
    oid = _order(pax["id"], drv["id"])
    r = client.post(f"/instant/orders/{oid}/stuck", headers=pax["auth"], json={
        "lat": 53.1, "lng": 57.2, "note": "Замело, стоим",
    })
    assert r.status_code == 200, r.text
    assert r.json()["category"] == "breakdown"
    assert r.json()["order_id"] == oid          # сигнал привязан к заказу, а не висит в воздухе


def test_driver_can_call_roadside_help_too(client, user_factory):
    pax = user_factory("Юлаусы4")
    drv = user_factory("Водитель2", role=UserRole.driver)
    oid = _order(pax["id"], drv["id"])
    r = client.post(f"/instant/orders/{oid}/stuck", headers=drv["auth"], json={"lat": 53.1, "lng": 57.2})
    assert r.status_code == 200, r.text


def test_stranger_cannot_call_roadside_help(client, user_factory):
    pax = user_factory("Юлаусы5")
    drv = user_factory("Водитель3", role=UserRole.driver)
    stranger = user_factory("Посторонний")
    oid = _order(pax["id"], drv["id"])
    r = client.post(f"/instant/orders/{oid}/stuck", headers=stranger["auth"], json={"lat": 53.1, "lng": 57.2})
    assert r.status_code == 403


def test_roadside_event_lands_in_admin_sos_feed(client, user_factory):
    """Смысл события — чтобы его УВИДЕЛИ: сигнал должен появиться в ленте админа."""
    pax = user_factory("Юлаусы6")
    drv = user_factory("Водитель4", role=UserRole.driver)
    oid = _order(pax["id"], drv["id"])
    client.post(f"/instant/orders/{oid}/stuck", headers=pax["auth"], json={"lat": 53.1, "lng": 57.2})
    admin = user_factory("Админ", role=UserRole.admin)
    feed = client.get("/admin/sos?status=open", headers=admin["auth"]).json()
    assert any(e["order_id"] == oid and e["category"] == "breakdown" for e in feed)


def test_booking_roadside_still_works(client, user_factory):
    """Попутка не должна пострадать от появления такси-варианта — общая механика одна."""
    with Session(engine) as s:
        drv = M.User(phone="tg-road-drv", name="Водитель", verified=True, role=UserRole.driver)
        s.add(drv); s.commit(); s.refresh(drv)
        ride = M.Ride(driver_id=drv.id, from_city="Сибай", to_city="Уфа",
                      depart_at=utcnow(), seats_total=3, seats_left=2, price=500)
        s.add(ride); s.commit(); s.refresh(ride)
        ride_id = ride.id
    pax = user_factory("Юлаусы7")
    with Session(engine) as s:
        b = M.Booking(ride_id=ride_id, passenger_id=pax["id"], seats=1)
        s.add(b); s.commit(); s.refresh(b)
        bid = b.id
    r = client.post(f"/bookings/{bid}/stuck", headers=pax["auth"], json={"lat": 53.1, "lng": 57.2})
    assert r.status_code == 200, r.text
    with Session(engine) as s:
        ev = s.exec(select(M.SosEvent).where(M.SosEvent.booking_id == bid)).first()
    assert ev is not None and ev.order_id is None
