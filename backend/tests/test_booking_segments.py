"""Человек выбирает весь участок; идемпотентный повтор не переписывает договорённость."""
from datetime import timedelta

import pytest
from sqlmodel import Session, select

from app.db import engine
from app.models import Booking, BookingStatus, Ride, RideRequest, UserRole
from app.timeutil import utcnow


def seed_route(driver, passenger, *, frm="Ургаза", to="Бурибай", seats=3, **ride_extra):
    with Session(engine) as session:
        ride = Ride(driver_id=driver["id"], from_city="Сибай", to_city="Акъяр",
                    waypoints="Ургаза | Бурибай", depart_at=utcnow() + timedelta(days=4),
                    seats_total=seats, seats_left=seats, price=700, **ride_extra)
        session.add(ride)
        session.flush()
        req = RideRequest(passenger_id=passenger["id"], from_city=frm, to_city=to,
                          desired_at=ride.depart_at, seats=1)
        session.add(req)
        session.commit()
        return ride.id, req.id


@pytest.fixture
def route(client, user_factory):
    driver = user_factory("Segment driver", role=UserRole.driver, gender="female")
    passenger = user_factory("Segment passenger", gender="female")
    rid, reqid = seed_route(driver, passenger)
    return driver, passenger, rid, reqid


def book(client, passenger, rid, **fields):
    return client.post("/bookings", headers=passenger["auth"], json={"ride_id": rid, **fields})


def state(rid):
    with Session(engine) as session:
        ride = session.get(Ride, rid)
        bookings = session.exec(select(Booking).where(Booking.ride_id == rid)).all()
        return ride.seats_left, [(b.id, b.pickup_stop, b.dropoff_stop) for b in bookings]


@pytest.mark.parametrize("pickup,dropoff,expected", [
    ("Сибай", "Акъяр", ("", "")),
    ("Ургаза", "Бурибай", ("Ургаза", "Бурибай")),
    ("Урғаҙа", "Аҡъяр", ("Ургаза", "")),
    ("Сибай", "Бурибай", ("", "Бурибай")),
])
def test_valid_segment_is_saved_and_visible_to_both_parties(client, route, pickup, dropoff, expected):
    driver, passenger, rid, _ = route
    result = book(client, passenger, rid, pickup_stop=pickup, dropoff_stop=dropoff)
    assert result.status_code == 200, result.text
    bid = result.json()["id"]
    assert (result.json()["pickup_stop"], result.json()["dropoff_stop"]) == expected
    assert state(rid) == (2, [(bid, *expected)])
    for person in (driver, passenger):
        details = client.get(f"/bookings/{bid}/details", headers=person["auth"])
        assert details.status_code == 200, details.text
        assert (details.json()["pickup_stop"], details.json()["dropoff_stop"]) == expected
    mine = client.get("/bookings/mine", headers=passenger["auth"]).json()
    assert next(b for b in mine if b["id"] == bid)["dropoff_stop"] == expected[1]
    driver_rows = client.get("/driver/bookings", headers=driver["auth"]).json()
    driver_row = next(b for b in driver_rows if b["booking_id"] == bid)
    assert driver_row["dropoff_stop"] == expected[1]
    assert (driver_row["from_city"], driver_row["to_city"]) == ("Сибай", "Акъяр")


@pytest.mark.parametrize("pickup,dropoff", [
    ("Ургаза", "Сибай"), ("Бурибай", "Ургаза"), ("Ургаза", "Ургаза"),
    (None, "Бурибай"), ("Сибай", "Нет такого села"),
])
def test_invalid_segment_does_not_reserve_seats(client, route, pickup, dropoff):
    _, passenger, rid, _ = route
    result = book(client, passenger, rid, pickup_stop=pickup, dropoff_stop=dropoff)
    assert result.status_code == 400, result.text
    assert result.json()["detail"]["ru"] and result.json()["detail"]["ba"]
    assert state(rid) == (3, [])


def test_old_client_keeps_unknown_dropoff_and_cannot_silently_change_it(client, route):
    _, passenger, rid, _ = route
    first = book(client, passenger, rid, pickup_stop="Ургаза")
    assert first.status_code == 200 and first.json()["dropoff_stop"] is None
    bid = first.json()["id"]
    for fields in ({}, {"pickup_stop": None, "dropoff_stop": None}):
        again = book(client, passenger, rid, **fields)
        assert again.status_code == 200 and again.json()["id"] == bid
        assert again.json()["dropoff_stop"] is None
    changed = book(client, passenger, rid, dropoff_stop="Бурибай")
    assert changed.status_code == 409 and changed.json()["detail"]["code"] == "dropoff_stop_conflict"
    assert state(rid) == (2, [(bid, "Ургаза", None)])


def test_same_dropoff_repeat_without_pickup_uses_the_saved_pickup(client, route):
    _, passenger, rid, _ = route
    first = book(client, passenger, rid, pickup_stop="Ургаза", dropoff_stop="Бурибай")
    again = book(client, passenger, rid, dropoff_stop="Бурибай")
    assert first.status_code == again.status_code == 200
    assert first.json()["id"] == again.json()["id"]
    changed = book(client, passenger, rid, dropoff_stop="Акъяр")
    assert changed.status_code == 409
    assert state(rid)[0] == 2


@pytest.mark.parametrize("status", [BookingStatus.cancelled, BookingStatus.done])
def test_driver_loses_both_stops_when_private_window_closes(client, route, status):
    driver, passenger, rid, _ = route
    result = book(client, passenger, rid, pickup_stop="Ургаза", dropoff_stop="Бурибай")
    bid = result.json()["id"]
    with Session(engine) as session:
        booking = session.get(Booking, bid)
        booking.status = status
        booking.done_at = utcnow() - timedelta(days=30)
        ride = session.get(Ride, rid)
        ride.depart_at = utcnow() - timedelta(days=30)
        session.add(booking)
        session.add(ride)
        session.commit()
    details = client.get(f"/bookings/{bid}/details", headers=driver["auth"]).json()
    assert details["pickup_stop"] is None and details["dropoff_stop"] is None
    own = client.get(f"/bookings/{bid}/details", headers=passenger["auth"]).json()
    assert own["dropoff_stop"] == "Бурибай"
    rows = client.get("/driver/bookings", headers=driver["auth"]).json()
    assert next(b for b in rows if b["booking_id"] == bid)["dropoff_stop"] is None


def test_public_payload_and_stranger_do_not_expose_booking_segment(client, route, user_factory):
    _, passenger, rid, _ = route
    result = book(client, passenger, rid, pickup_stop="Ургаза", dropoff_stop="Бурибай")
    stranger = user_factory("Not a participant")
    denied = client.get(f"/bookings/{result.json()['id']}/details", headers=stranger["auth"])
    assert denied.status_code == 403
    public = client.get(f"/rides/{rid}").json()
    assert "dropoff_stop" not in public and "pickup_stop" not in public


def test_no_stops_only_accepts_start_to_end_and_old_null_is_preserved(client, route):
    _, passenger, rid, _ = route
    with Session(engine) as session:
        ride = session.get(Ride, rid)
        ride.waypoints = ""
        session.add(ride)
        session.commit()
    refused = book(client, passenger, rid, pickup_stop="Сибай", dropoff_stop="Ургаза")
    assert refused.status_code == 400 and state(rid) == (3, [])
    first = book(client, passenger, rid)
    assert first.status_code == 200 and first.json()["dropoff_stop"] is None
    assert book(client, passenger, rid, dropoff_stop="").status_code == 409
