"""The phone must distinguish waiting for confirmation from a finished trip."""
import pytest
from sqlmodel import Session

from app.db import engine
from app.models import Booking, BookingStatus
from test_location_privacy import _confirmed_booking


def _set_status(bid, status):
    with Session(engine) as session:
        booking = session.get(Booking, bid)
        booking.status = status
        session.add(booking)
        session.commit()


def _close_frame(client, bid, token):
    with client.websocket_connect(f"/ws/trip/{bid}/location") as ws:
        ws.send_json({"type": "auth", "token": token})
        return ws.receive()


@pytest.mark.parametrize("status", [BookingStatus.done, BookingStatus.cancelled])
@pytest.mark.parametrize("participant", [0, 1], ids=["driver", "passenger"])
def test_finished_trip_reports_terminal_close(client, user_factory, status, participant):
    driver, passenger, bid = _confirmed_booking(client, user_factory, "Terminal")
    _set_status(bid, status)
    assert _close_frame(client, bid, (driver, passenger)[participant]["token"]) == {
        "type": "websocket.close", "code": 1008, "reason": "Trip ended",
    }


def test_pending_trip_remains_retryable(client, user_factory):
    _, passenger, bid = _confirmed_booking(client, user_factory, "PendingClose")
    _set_status(bid, BookingStatus.pending)
    assert _close_frame(client, bid, passenger["token"]) == {
        "type": "websocket.close", "code": 1008, "reason": "Trip not active",
    }


@pytest.mark.parametrize("status", [BookingStatus.done, BookingStatus.cancelled])
def test_outsider_cannot_discover_terminal_status(client, user_factory, status):
    _, _, bid = _confirmed_booking(client, user_factory, "PrivateClose")
    _set_status(bid, status)
    outsider = user_factory("OutsideClose")
    assert _close_frame(client, bid, outsider["token"]) == {
        "type": "websocket.close", "code": 1008, "reason": "Forbidden",
    }


def test_invalid_token_does_not_discover_terminal_status(client, user_factory):
    _, _, bid = _confirmed_booking(client, user_factory, "InvalidClose")
    _set_status(bid, BookingStatus.done)
    assert _close_frame(client, bid, "invalid-local-test-token") == {
        "type": "websocket.close", "code": 1008, "reason": "Invalid token",
    }
