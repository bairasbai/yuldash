"""Пауза не бросает людей на полдороге — ни у водителя, ни у курьера.

Продолжение волны 63, теперь со стороны тех, кто везёт. Наказание может прийти в любой момент,
в том числе когда пассажир уже в машине, а посылка — в багажнике. Правило «новых дел не
начинаем» не должно превращаться в «брось всё и высади человека на трассе».

Проверено запросами (аудит 2026-08-14, волна 64) — все три роли доводят начатое до конца.
Дыр не нашлось; этот файл закрепляет найденное, потому что незакреплённое правильное поведение
живёт ровно до следующей волны, где кто-нибудь (в том числе я) увидит «тут нет гейта»
и добавит его из лучших побуждений.

Что каждый обязан уметь на паузе:
  • водитель попутки — «подъезжаю», чат, SOS, завершение поездки, оценка, жалоба по этой поездке;
  • таксист — чат по идущему заказу, завершение, жалоба;
  • курьер — чат по посылке в пути и вручение по коду.
"""
from __future__ import annotations

from datetime import timedelta

import pytest
from sqlmodel import Session, select

from app.config import settings
from app.db import engine
from app.models import (Booking, BookingStatus, CourierApplication, InstantOrder,
                        InstantOrderStatus as S, ParcelDelivery, Ride, RideStatus,
                        SafetyProfile, UserRole)
from app.timeutil import utcnow
from conftest import upload_doc


@pytest.fixture
def services_on(monkeypatch):
    monkeypatch.setattr(settings, "taxi_enabled", True, raising=False)
    monkeypatch.setattr(settings, "courier_enabled", True, raising=False)
    yield


def _suspend(user_id: int) -> None:
    with Session(engine) as s:
        s.add(SafetyProfile(user_id=user_id, suspended_until=utcnow() + timedelta(days=7)))
        s.commit()


def _live_trip(session: Session, pax_id: int, drv_id: int) -> int:
    """Поездка идёт: бронь подтверждена, время выезда прошло."""
    ride = Ride(driver_id=drv_id, from_city="Сибай", to_city="Уфа",
                depart_at=utcnow() - timedelta(minutes=30), seats_total=3, seats_left=2,
                price=500, status=RideStatus.active)
    session.add(ride)
    session.commit()
    session.refresh(ride)
    b = Booking(ride_id=ride.id, passenger_id=pax_id, seats=1, price=500,
                status=BookingStatus.confirmed, boarding_code="123456")
    session.add(b)
    session.commit()
    session.refresh(b)
    return b.id


def test_водитель_на_паузе_довозит_и_отчитывается(client, user_factory):
    drv = user_factory("FinishDrv", role=UserRole.driver)
    pax = user_factory("FinishPax", role=UserRole.passenger)
    with Session(engine) as s:
        bid = _live_trip(s, pax["id"], drv["id"])
    _suspend(drv["id"])
    a = drv["auth"]

    assert client.post(f"/bookings/{bid}/driver-status", headers=a,
                       json={"status": "arriving"}).status_code == 200
    assert client.post(f"/bookings/{bid}/messages", headers=a,
                       json={"text": "жду у подъезда"}).status_code == 200
    assert client.post("/sos", headers=a, json={
        "category": "other", "note": "нужна помощь", "booking_id": bid}).status_code == 200
    assert client.post(f"/bookings/{bid}/driver-status", headers=a,
                       json={"status": "done"}).status_code == 200
    assert client.post(f"/bookings/{bid}/rate", headers=a, json={"stars": 1}).status_code == 200


def test_водитель_на_паузе_может_пожаловаться_на_пассажира(client, user_factory):
    """Волна 63 открыла это пассажиру — у водителя ровно та же нужда."""
    drv = user_factory("FinishComplainDrv", role=UserRole.driver)
    pax = user_factory("FinishComplainPax", role=UserRole.passenger)
    with Session(engine) as s:
        bid = _live_trip(s, pax["id"], drv["id"])
    _suspend(drv["id"])

    r = client.post("/incidents", headers=drv["auth"], json={
        "respondent_id": pax["id"], "booking_id": bid,
        "type": "rude", "description": "пассажир хамил всю дорогу",
    })
    assert r.status_code == 200


def test_таксист_на_паузе_довозит_заказ(client, user_factory, services_on):
    drv = user_factory("FinishTaxiDrv", role=UserRole.driver)
    pax = user_factory("FinishTaxiPax", role=UserRole.passenger)
    with Session(engine) as s:
        o = InstantOrder(passenger_id=pax["id"], driver_id=drv["id"], status=S.onboard,
                         from_lat=54.7, from_lng=55.9, to_lat=54.8, to_lng=56.0,
                         from_text="Сибай", to_text="Уфа", price_estimate=300,
                         created_at=utcnow() - timedelta(minutes=40))
        s.add(o)
        s.commit()
        s.refresh(o)
        oid = o.id
    _suspend(drv["id"])
    a = drv["auth"]

    assert client.post(f"/instant/orders/{oid}/messages", headers=a,
                       json={"text": "подъезжаю"}).status_code == 200
    assert client.post(f"/instant/orders/{oid}/done", headers=a, json={}).status_code == 200
    assert client.post("/incidents", headers=a, json={
        "respondent_id": pax["id"], "order_id": oid,
        "type": "rude", "description": "хамил"}).status_code == 200


def test_курьер_на_паузе_довозит_посылку(client, user_factory, services_on):
    sender = user_factory("FinishSender", role=UserRole.passenger)
    cour = user_factory("FinishCour", role=UserRole.passenger)
    selfie = upload_doc(client, cour["auth"])
    client.post("/courier/apply", headers=cour["auth"], json={
        "transport": "car", "full_name": "Курьер Курьеров", "car_plate": "А111АА102",
        "selfie_url": selfie, "rules_accepted": True,
    })
    with Session(engine) as s:
        row = s.exec(select(CourierApplication).where(
            CourierApplication.user_id == cour["id"])).first()
        if row is not None:
            row.status = "approved"
            s.add(row)
        p = ParcelDelivery(sender_id=sender["id"], courier_id=cour["id"], from_city="Баймак",
                           to_city="Сибай", size="small", description="лекарство",
                           receiver_name="Гөлнара", receiver_phone="+79995550001",
                           status="in_transit", delivery_type="courier", rules_accepted=True,
                           confirm_code="654321")
        s.add(p)
        s.commit()
        s.refresh(p)
        pid = p.id
    _suspend(cour["id"])
    a = cour["auth"]

    assert client.post(f"/parcels/{pid}/messages", headers=a,
                       json={"text": "подъезжаю"}).status_code == 200
    assert client.post(f"/parcels/{pid}/status", headers=a,
                       json={"status": "delivered", "code": "654321"}).status_code == 200


def test_но_новую_работу_на_паузе_не_берут(client, user_factory, services_on):
    """Контроль обратной стороны: доводить начатое можно, начинать новое — нет."""
    drv = user_factory("FinishNoNewDrv", role=UserRole.driver)
    pax = user_factory("FinishNoNewPax", role=UserRole.passenger)
    req = client.post("/requests", headers=pax["auth"], json={
        "from_city": "FinishA", "to_city": "FinishB", "seats": 1, "comment": "еду"})
    assert req.status_code == 200
    _suspend(drv["id"])

    depart = (utcnow() + timedelta(hours=settings.local_tz_offset_hours, days=1)).replace(
        microsecond=0).isoformat()
    # Опубликовать новую поездку нельзя…
    assert client.post("/rides", headers=drv["auth"], json={
        "from_city": "FinishA", "to_city": "FinishB", "seats_total": 3, "price": 500,
        "depart_at": depart}).status_code == 403
    # …и взять чужую заявку — тоже: это новые обязательства перед новым человеком.
    assert client.post(f"/requests/{req.json()['id']}/respond", headers=drv["auth"],
                       json={"price": 500}).status_code == 403
