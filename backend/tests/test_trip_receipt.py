"""Квитанция поездки (GET /trips/{booking_id}/receipt): участник видит, посторонний → 403,
незавершённая → 409, сумма берётся из договорённости (pay_amount)."""
from sqlmodel import Session

from app.db import engine
from app.models import Booking, BookingStatus, PayMethod, Ride, UserRole
from app.timeutil import utcnow


def _make_booking(driver_id: int, passenger_id: int, status: BookingStatus,
                  price: int = 300, pay_amount=None, pay_method=PayMethod.sbp) -> int:
    with Session(engine) as s:
        ride = Ride(driver_id=driver_id, from_city="Уфа", to_city="Стерлитамак", depart_at=utcnow())
        s.add(ride)
        s.commit()
        s.refresh(ride)
        b = Booking(ride_id=ride.id, passenger_id=passenger_id, seats=1, price=price,
                    status=status, pay_method=pay_method, pay_amount=pay_amount)
        s.add(b)
        s.commit()
        s.refresh(b)
        return b.id


def test_participant_sees_receipt(client, user_factory):
    driver = user_factory("ReceiptDriver", role=UserRole.driver)
    pax = user_factory("ReceiptPassenger")
    bid = _make_booking(driver["id"], pax["id"], BookingStatus.done, price=300, pay_amount=350)

    # пассажир
    r = client.get(f"/trips/{bid}/receipt", headers=pax["auth"])
    assert r.status_code == 200
    body = r.json()
    assert body["from_city"] == "Уфа" and body["to_city"] == "Стерлитамак"
    assert body["amount"] == 350            # договорённость (pay_amount) важнее цены брони
    assert body["pay_method"] == "sbp"
    assert body["role"] == "passenger"
    assert body["counterparty_name"] == "ReceiptDriver"
    assert body["my_stars"] == 0 and body["my_rating_tags"] == ""
    assert "driver_phone" not in body       # телефон в квитанции не отдаём

    rated = client.post(
        f"/bookings/{bid}/rate",
        headers=pax["auth"],
        json={"stars": 5, "tags": "polite,safe"},
    )
    assert rated.status_code == 200, rated.text
    passenger_body = client.get(f"/trips/{bid}/receipt", headers=pax["auth"]).json()
    assert passenger_body["my_stars"] == 5
    assert passenger_body["my_rating_tags"] == "polite,safe"

    # водитель тоже участник
    r = client.get(f"/trips/{bid}/receipt", headers=driver["auth"])
    assert r.status_code == 200 and r.json()["role"] == "driver"
    assert r.json()["counterparty_name"] == "ReceiptPassenger"
    assert r.json()["my_stars"] == 0, "водителю показали оценку пассажира как свою"


def test_amount_falls_back_to_price(client, user_factory):
    driver = user_factory(role=UserRole.driver)
    pax = user_factory()
    bid = _make_booking(driver["id"], pax["id"], BookingStatus.done, price=420, pay_amount=None)
    assert client.get(f"/trips/{bid}/receipt", headers=pax["auth"]).json()["amount"] == 420


def test_outsider_forbidden(client, user_factory):
    driver = user_factory(role=UserRole.driver)
    pax = user_factory()
    outsider = user_factory()
    bid = _make_booking(driver["id"], pax["id"], BookingStatus.done)
    assert client.get(f"/trips/{bid}/receipt", headers=outsider["auth"]).status_code == 403


def test_unfinished_trip_409(client, user_factory):
    driver = user_factory(role=UserRole.driver)
    pax = user_factory()
    bid = _make_booking(driver["id"], pax["id"], BookingStatus.confirmed)
    assert client.get(f"/trips/{bid}/receipt", headers=pax["auth"]).status_code == 409
