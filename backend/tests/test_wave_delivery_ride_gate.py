"""Выключенные посылки нельзя обойти категорией обычной поездки; старые брони сохраняются."""
import pytest

from app.config import settings
from app.models import UserRole
from test_api import _ride
from test_wave_launch_switches import _soon


@pytest.mark.parametrize("category", ["parcel", "cargo"])
def test_delivery_ride_creation_is_closed_when_parcels_are_off(client, user_factory, monkeypatch, category):
    monkeypatch.setattr(settings, "parcels_enabled", False)
    driver = user_factory("DeliveryCreator-" + category, role=UserRole.driver)
    reply = client.post("/rides", headers=driver["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай", "depart_at": "2030-01-01T10:00:00",
        "seats_total": 1, "price": 0, "category": category,
    })
    _soon(reply)
    assert client.get("/driver/rides", headers=driver["auth"]).json() == []


@pytest.mark.parametrize("category", ["parcel", "cargo"])
def test_new_delivery_ride_booking_is_closed_when_parcels_are_off(client, user_factory, monkeypatch, category):
    monkeypatch.setattr(settings, "parcels_enabled", True)
    driver = user_factory("DeliveryDriver-" + category, role=UserRole.driver)
    passenger = user_factory("DeliveryPassenger-" + category)
    ride_id = _ride(client, driver, seats=1, price=0, category=category)
    monkeypatch.setattr(settings, "parcels_enabled", False)
    _soon(client.post("/bookings", headers=passenger["auth"], json={"ride_id": ride_id, "seats": 1}))
    assert client.get("/bookings/mine", headers=passenger["auth"]).json() == []
    assert client.get(f"/rides/{ride_id}").json()["seats_left"] == 1


@pytest.mark.parametrize("category", ["parcel", "cargo"])
def test_enabled_delivery_ride_create_and_booking_still_work(client, user_factory, monkeypatch, category):
    monkeypatch.setattr(settings, "parcels_enabled", True)
    driver = user_factory("EnabledDriver-" + category, role=UserRole.driver)
    passenger = user_factory("EnabledPassenger-" + category)
    ride_id = _ride(client, driver, seats=1, price=0, category=category)
    reply = client.post("/bookings", headers=passenger["auth"], json={"ride_id": ride_id, "seats": 1})
    assert reply.status_code == 200, reply.text
    assert reply.json()["ride_id"] == ride_id


@pytest.mark.parametrize("category", ["regular", "urgent", "hospital"])
def test_passenger_rides_remain_open_when_parcels_are_off(client, user_factory, monkeypatch, category):
    monkeypatch.setattr(settings, "parcels_enabled", False)
    driver = user_factory("PassengerRideDriver-" + category, role=UserRole.driver)
    passenger = user_factory("PassengerRideUser-" + category)
    ride_id = _ride(client, driver, seats=1, price=0, category=category)
    reply = client.post("/bookings", headers=passenger["auth"], json={"ride_id": ride_id, "seats": 1})
    assert reply.status_code == 200, reply.text


@pytest.mark.parametrize("category", ["parcel", "cargo"])
def test_existing_delivery_booking_survives_flag_and_idempotent_retry(client, user_factory, monkeypatch, category):
    monkeypatch.setattr(settings, "parcels_enabled", True)
    driver = user_factory("OldDeliveryDriver-" + category, role=UserRole.driver)
    passenger = user_factory("OldDeliveryPassenger-" + category)
    ride_id = _ride(client, driver, seats=1, price=0, category=category)
    body = {"ride_id": ride_id, "seats": 1}
    first = client.post("/bookings", headers=passenger["auth"], json=body)
    assert first.status_code == 200, first.text
    booking_id = first.json()["id"]
    monkeypatch.setattr(settings, "parcels_enabled", False)
    repeated = client.post("/bookings", headers=passenger["auth"], json=body)
    assert repeated.status_code == 200 and repeated.json()["id"] == booking_id, repeated.text
    confirmed = client.post(f"/bookings/{booking_id}/confirm", headers=driver["auth"])
    assert confirmed.status_code == 200, confirmed.text
    cancelled = client.post(f"/bookings/{booking_id}/cancel", headers=passenger["auth"], json={})
    assert cancelled.status_code == 200, cancelled.text
