"""Квитанция хранит личный участок пассажира; водителю действует прежнее окно приватности."""
from datetime import datetime, timedelta

import pytest
from sqlmodel import Session

from app import timeutil
from app.config import settings
from app.db import engine
from app.models import Booking, BookingStatus, PayMethod, Rating, Ride, UserRole


NOW = datetime(2031, 2, 3, 12, 0, 0)


@pytest.fixture
def receipt(client, user_factory, monkeypatch):
    driver = user_factory("Receipt segment driver", role=UserRole.driver)
    passenger = user_factory("Receipt segment passenger")
    # Меняем только источник «сейчас», который функция окна получает при каждом вызове.
    # Системные часы, datetime и время токенов не подменяем.
    monkeypatch.setattr(timeutil, "utcnow", lambda: NOW)

    def make(*, pickup="Ургаза", dropoff="Бурибай", depart=None, status=BookingStatus.done,
             lost_item_until=None, amount=1250):
        with Session(engine) as session:
            ride = Ride(driver_id=driver["id"], from_city="Сибай", to_city="Акъяр",
                        waypoints="Ургаза | Бурибай", depart_at=depart or NOW - timedelta(hours=1),
                        price=700, seats_total=3, seats_left=2)
            session.add(ride)
            session.flush()
            booking = Booking(ride_id=ride.id, passenger_id=passenger["id"], seats=2, price=1400,
                              pay_amount=amount, pay_method=PayMethod.sbp, paid=True, status=status,
                              pickup_stop=pickup, dropoff_stop=dropoff, lost_item_until=lost_item_until)
            session.add(booking)
            session.commit()
            return booking.id, ride.id, ride.depart_at.isoformat()

    return driver, passenger, make


def get_receipt(client, person, bid):
    return client.get(f"/trips/{bid}/receipt", headers=person["auth"])


@pytest.mark.parametrize("role", ["driver", "passenger"])
def test_recent_done_receipt_contains_the_personal_section_without_changing_other_fields(client, receipt, role):
    driver, passenger, make = receipt
    bid, rid, depart = make()
    person = driver if role == "driver" else passenger
    result = get_receipt(client, person, bid)
    assert result.status_code == 200, result.text
    body = result.json()
    assert body["pickup_stop"] == "Ургаза" and body["dropoff_stop"] == "Бурибай"
    assert (body["from_city"], body["to_city"], body["depart_at"]) == ("Сибай", "Акъяр", depart)
    assert (body["booking_id"], body["ride_id"], body["role"], body["seats"]) == (bid, rid, role, 2)
    assert (body["amount"], body["pay_method"], body["paid"]) == (1250, "sbp", True)
    assert body["counterparty_id"] == (passenger if role == "driver" else driver)["id"]
    assert body["my_stars"] == 0 and body["my_rating_tags"] == ""
    assert "driver_phone" not in body and "passenger_phone" not in body


@pytest.mark.parametrize("pickup,dropoff", [
    (None, None), (None, "Бурибай"), ("Ургаза", None),
    ("", ""), ("", "Бурибай"), ("Ургаза", ""),
    ("Старая сохранённая посадка", "Старая сохранённая высадка"),
])
@pytest.mark.parametrize("role", ["driver", "passenger"])
def test_receipt_preserves_raw_unknown_partial_endpoints_and_historic_places(client, receipt, pickup, dropoff, role):
    driver, passenger, make = receipt
    bid, _, _ = make(pickup=pickup, dropoff=dropoff)
    person = driver if role == "driver" else passenger
    result = get_receipt(client, person, bid)
    assert result.status_code == 200, result.text
    # Обе колонки обязаны присутствовать: legacy NULL и явный endpoint "" имеют разный смысл.
    assert result.json()["pickup_stop"] == pickup
    assert result.json()["dropoff_stop"] == dropoff


def test_expired_driver_receipt_hides_both_places_but_passenger_keeps_own_raw_data(client, receipt):
    driver, passenger, make = receipt
    bid, _, _ = make(depart=NOW - timedelta(hours=settings.chat_after_trip_hours + 1))
    hidden = get_receipt(client, driver, bid)
    assert hidden.status_code == 200, hidden.text
    assert hidden.json()["pickup_stop"] is None and hidden.json()["dropoff_stop"] is None
    own = get_receipt(client, passenger, bid)
    assert own.status_code == 200
    assert own.json()["pickup_stop"] == "Ургаза" and own.json()["dropoff_stop"] == "Бурибай"
    assert (hidden.json()["amount"], hidden.json()["role"], hidden.json()["paid"]) == (1250, "driver", True)


@pytest.mark.parametrize("after_us,visible", [(-1, True), (0, True), (1, False)])
def test_driver_section_obeys_the_exact_existing_contact_window_boundary(client, receipt, after_us, visible):
    driver, _, make = receipt
    depart = NOW - timedelta(hours=settings.chat_after_trip_hours, microseconds=after_us)
    bid, _, _ = make(depart=depart)
    result = get_receipt(client, driver, bid)
    assert result.status_code == 200, result.text
    expected = ("Ургаза", "Бурибай") if visible else (None, None)
    assert (result.json()["pickup_stop"], result.json()["dropoff_stop"]) == expected


@pytest.mark.parametrize("remaining_us,visible", [(1, True), (0, True), (-1, False)])
def test_lost_item_extension_uses_the_existing_boundary_without_renewing_it(client, receipt, remaining_us, visible):
    driver, _, make = receipt
    until = NOW + timedelta(microseconds=remaining_us)
    bid, _, _ = make(depart=NOW - timedelta(days=30), lost_item_until=until)
    result = get_receipt(client, driver, bid)
    assert result.status_code == 200, result.text
    expected = ("Ургаза", "Бурибай") if visible else (None, None)
    assert (result.json()["pickup_stop"], result.json()["dropoff_stop"]) == expected
    with Session(engine) as session:
        assert session.get(Booking, bid).lost_item_until == until


def test_each_party_keeps_their_own_rating_and_amount_fallback(client, receipt):
    driver, passenger, make = receipt
    bid, _, _ = make(amount=None)
    with Session(engine) as session:
        session.add(Rating(booking_id=bid, rater_id=driver["id"], ratee_id=passenger["id"], stars=3, tags="ontime"))
        session.add(Rating(booking_id=bid, rater_id=passenger["id"], ratee_id=driver["id"], stars=5, tags="safe"))
        session.commit()
    for person, role, stars, tags in ((driver, "driver", 3, "ontime"), (passenger, "passenger", 5, "safe")):
        result = get_receipt(client, person, bid)
        assert result.status_code == 200, result.text
        body = result.json()
        assert (body["my_stars"], body["my_rating_tags"], body["amount"], body["role"]) == (stars, tags, 1400, role)
        assert (body["pickup_stop"], body["dropoff_stop"]) == ("Ургаза", "Бурибай")


def test_personal_section_never_opens_receipt_to_strangers_or_missing_ids(client, receipt, user_factory):
    _, passenger, make = receipt
    bid, _, _ = make()
    stranger = user_factory("Receipt section stranger")
    assert get_receipt(client, stranger, bid).status_code == 403
    assert get_receipt(client, passenger, -100001).status_code == 404
    assert client.get(f"/trips/{bid}/receipt").status_code == 401


@pytest.mark.parametrize("status", [BookingStatus.pending, BookingStatus.confirmed, BookingStatus.cancelled])
@pytest.mark.parametrize("role", ["driver", "passenger"])
def test_unfinished_or_cancelled_booking_still_has_no_receipt(client, receipt, status, role):
    driver, passenger, make = receipt
    bid, _, _ = make(status=status)
    person = driver if role == "driver" else passenger
    result = get_receipt(client, person, bid)
    assert result.status_code == 409
    assert "pickup_stop" not in result.json() and "dropoff_stop" not in result.json()
