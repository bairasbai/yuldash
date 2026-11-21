from datetime import timedelta
from types import SimpleNamespace

import pytest
from sqlalchemy import func
from sqlmodel import Session, select

from app.db import engine
from app.models import Booking, MedicalPartner, Notification, Ride, RideCategory, RideStatus, UserRole
from app.ride_seats import validate_stop_prices
from app.schemas import RideIn
from app.timeutil import utcnow


def add_ride(owner, **overrides):
    attributes = {
        "driver_id": owner["id"], "from_city": "Сибай", "to_city": "Акъяр",
        "depart_at": utcnow() + timedelta(days=1), "seats_total": 3, "seats_left": 1,
        "price": 500, "waypoints": "Ургаза | Баймак", "stop_prices_json": "[200, 410]",
    }
    with Session(engine) as session:
        ride = Ride(**(attributes | overrides))
        session.add(ride)
        session.commit()
        return ride.id


def template(client, ride_id, user):
    return client.get(f"/rides/{ride_id}/return-template", headers=user["auth"])


def test_template_keeps_direct_link_visibility_and_requires_owner(client, user_factory):
    owner = user_factory(role=UserRole.driver)
    passenger = user_factory()
    public = add_ride(owner)
    private = add_ride(owner, only_trusted=True)
    archived = add_ride(owner, status=RideStatus.done)

    assert client.get(f"/rides/{public}/return-template").status_code == 401
    missing = template(client, 0, passenger)
    assert missing.status_code == 404
    for ride_id in (private, archived):
        response = template(client, ride_id, passenger)
        assert response.status_code == 404
        assert response.json() == missing.json()
        assert template(client, ride_id, owner).status_code == 200
    response = template(client, public, passenger)
    assert response.status_code == 403
    assert response.json()["detail"]["code"] == "not_owner"


def test_archived_template_reverses_route_prices_and_excludes_private_data(client, user_factory):
    owner = user_factory(role=UserRole.driver)
    with Session(engine) as session:
        partner = MedicalPartner(name="Синтетическая клиника", city="Акъяр")
        session.add(partner)
        session.commit()
        partner_id = partner.id
    ride_id = add_ride(
        owner, status=RideStatus.done, category=RideCategory.hospital, partner_id=partner_id,
        pickup="Точное место встречи", pickup_lat=51.5, pickup_lng=58.7, comment="Личный комментарий",
        receiver_name="Имя получателя", parcel_size="Коробка", recurrence="weekly",
        pets_allowed=True, child_seat=True, women_only=True, smoking=True, baggage=True,
        air_conditioner=True, only_trusted=True, quiet=True, no_minors=True, back_max_two=True,
        front_seat_available=False, seats_total=2, seats_left=0,
    )
    with Session(engine) as session:
        stored = session.get(Ride, ride_id).model_dump()
        counts_before = [session.exec(select(func.count()).select_from(model)).one()
                         for model in (Ride, Booking, Notification)]
    response = template(client, ride_id, owner)
    assert response.status_code == 200, response.text
    payload = response.json()
    assert payload == {
        "from_city": "Акъяр", "to_city": "Сибай", "waypoints": "Баймак | Ургаза",
        "seats_total": 2, "price": 500, "stop_prices": [90, 300], "category": "regular",
        "pets_allowed": True, "child_seat": True, "women_only": True, "smoking": True,
        "baggage": True, "air_conditioner": True, "only_trusted": True, "quiet": True,
        "no_minors": True, "back_max_two": True, "front_seat_available": False,
        "dropped": ["depart_at", "pickup", "comment", "recurrence", "partner_id", "parcel_details", "category"],
        "suggested_date": None,
    }
    draft = RideIn(**payload, depart_at=utcnow() + timedelta(days=1))
    assert validate_stop_prices(SimpleNamespace(**draft.model_dump()), draft.stop_prices) == [90, 300]
    with Session(engine) as session:
        assert session.get(Ride, ride_id).model_dump() == stored
        assert [session.exec(select(func.count()).select_from(model)).one()
                for model in (Ride, Booking, Notification)] == counts_before
    copied = draft.model_dump(exclude={"stop_prices", "pickup_point_id"})
    reverse_id = add_ride(owner, **copied, stop_prices_json="[90, 300]")
    reverse = template(client, reverse_id, owner).json()
    assert (reverse["from_city"], reverse["to_city"], reverse["waypoints"], reverse["stop_prices"]) == (
        "Сибай", "Акъяр", "Ургаза | Баймак", [200, 410],
    )


@pytest.mark.parametrize("category,expected", [
    (RideCategory.regular, "regular"), (RideCategory.hospital, "regular"),
    (RideCategory.parcel, "regular"), (RideCategory.cargo, "regular"), (RideCategory.urgent, "urgent"),
])
def test_category_and_negotiated_price(client, user_factory, category, expected):
    owner = user_factory(role=UserRole.driver)
    ride_id = add_ride(owner, category=category, price=0, waypoints="", stop_prices_json="")
    response = template(client, ride_id, owner)
    assert response.status_code == 200, response.text
    body = response.json()
    assert (body["category"], body["price"], body["stop_prices"], body["waypoints"]) == (expected, 0, [], "")
    assert ("category" in body["dropped"]) == (category.value != expected)
    assert "stop_prices" not in body["dropped"]


@pytest.mark.parametrize("waypoints,prices,expected,stops_dropped", [
    ("Ургаза | Баймак", "broken", "Баймак | Ургаза", False),
    ("Ургаза | Баймак", "null", "Баймак | Ургаза", False),
    ("Ургаза | Баймак", "[true, 410]", "Баймак | Ургаза", False),
    ("Ургаза | Баймак", "[200]", "Баймак | Ургаза", False),
    ("Ургаза | Баймак", "[410, 200]", "Баймак | Ургаза", False),
    ("Ургаза | Баймак", "[0, 500]", "Баймак | Ургаза", False),
    ("Ургаза | Баймак | дом 10", "[100, 200, 300]", "Баймак | Ургаза", True),
    ("Ургаза | Ургаза | Сибай", "[100, 200, 300]", "Ургаза", True),
    ("Ургаза | Баймак | Уфа | Кага | Абзаково", "[100, 200, 300, 350, 400]",
     "Абзаково | Кага | Уфа | Баймак", True),
])
def test_old_stops_and_prices_use_safe_fallback(client, user_factory, waypoints, prices, expected, stops_dropped):
    owner = user_factory(role=UserRole.driver)
    ride_id = add_ride(owner, waypoints=waypoints, stop_prices_json=prices)
    response = template(client, ride_id, owner)
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["waypoints"] == expected
    assert body["stop_prices"] == []
    assert "stop_prices" in body["dropped"]
    assert ("waypoints" in body["dropped"]) == stops_dropped
