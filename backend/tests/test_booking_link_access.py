"""The role endpoint is an authenticated ownership gate for restored private links."""
import pytest
from sqlmodel import Session

from app.db import engine
from app.models import Booking, BookingStatus
from test_location_privacy import _confirmed_booking


@pytest.mark.parametrize("status", [BookingStatus.confirmed, BookingStatus.done])
@pytest.mark.parametrize("participant,role", [(0, "driver"), (1, "passenger")])
def test_participant_can_validate_private_booking_link(client, user_factory, status, participant, role):
    driver, passenger, bid = _confirmed_booking(client, user_factory, "LinkRole")
    with Session(engine) as session:
        booking = session.get(Booking, bid)
        booking.status = status
        session.add(booking)
        session.commit()
    response = client.get(f"/bookings/{bid}/role", headers=(driver, passenger)[participant]["auth"])
    assert response.status_code == 200, response.text
    assert response.json()["role"] == role
    assert response.json()["status"] == status.value
    assert set(response.json()) == {"role", "status", "driver_phase", "alone_with_driver", "arrival_verified"}


def test_other_account_cannot_validate_private_booking_link(client, user_factory):
    _, _, bid = _confirmed_booking(client, user_factory, "ForeignLinkRole")
    outsider = user_factory("OtherAccountForLink")
    response = client.get(f"/bookings/{bid}/role", headers=outsider["auth"])
    assert response.status_code == 403, response.text
    assert "role" not in response.json() and "status" not in response.json()


def test_missing_booking_cannot_validate_link(client, user_factory):
    user = user_factory("MissingLinkRole")
    response = client.get("/bookings/999999999/role", headers=user["auth"])
    assert response.status_code == 404, response.text


def test_revoked_token_cannot_validate_previously_owned_link(client, user_factory):
    _, passenger, bid = _confirmed_booking(client, user_factory, "RevokedLinkRole")
    assert client.get(f"/bookings/{bid}/role", headers=passenger["auth"]).status_code == 200
    assert client.post("/auth/logout", headers=passenger["auth"]).status_code == 200
    response = client.get(f"/bookings/{bid}/role", headers=passenger["auth"])
    assert response.status_code == 401, response.text


def test_anonymous_user_cannot_validate_link(client, user_factory):
    _, _, bid = _confirmed_booking(client, user_factory, "AnonymousLinkRole")
    response = client.get(f"/bookings/{bid}/role")
    assert response.status_code == 401, response.text
