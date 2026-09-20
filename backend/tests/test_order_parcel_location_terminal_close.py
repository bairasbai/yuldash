"""Runtime WS close contract for taxi and parcel location, using isolated rows."""
import pytest
from sqlmodel import Session

from app.db import engine
from app.models import InstantOrder, InstantOrderStatus, ParcelDelivery, UserRole


def _channel(user_factory, kind, status):
    owner = user_factory("LocationOwner")
    driver = user_factory("LocationDriver", role=UserRole.driver)
    if kind == "instant":
        row = InstantOrder(passenger_id=owner["id"], driver_id=driver["id"],
                           status=InstantOrderStatus(status))
    else:
        row = ParcelDelivery(sender_id=owner["id"], courier_id=driver["id"],
                             status=status, from_city="Уфа", to_city="Сибай")
    with Session(engine) as session:
        session.add(row)
        session.commit()
        session.refresh(row)
        path = f"/ws/{kind}/{row.id}/location"
    return path, owner, driver


def _close(client, path, token):
    with client.websocket_connect(path) as ws:
        ws.send_json({"type": "auth", "token": token})
        return ws.receive()


TERMINALS = [
    ("instant", status, "Order ended") for status in ("done", "cancelled", "expired")
] + [
    ("parcel", status, "Parcel ended") for status in ("delivered", "canceled", "returned")
]


@pytest.mark.parametrize("kind,status,reason", TERMINALS)
@pytest.mark.parametrize("participant", [1, 2], ids=["owner", "driver"])
def test_finished_location_channel_is_terminal(client, user_factory, kind, status, reason, participant):
    data = _channel(user_factory, kind, status)
    assert _close(client, data[0], data[participant]["token"]) == {
        "type": "websocket.close", "code": 1008, "reason": reason,
    }


@pytest.mark.parametrize("kind,status,reason", [
    ("instant", status, "Order not active")
    for status in ("scheduled", "created", "searching", "offered")
] + [("parcel", "created", "Delivery not active"), ("parcel", "returning", "Delivery not active")])
def test_nonfinal_location_channel_keeps_waiting_contract(client, user_factory, kind, status, reason):
    path, owner, _ = _channel(user_factory, kind, status)
    assert _close(client, path, owner["token"]) == {
        "type": "websocket.close", "code": 1008, "reason": reason,
    }


@pytest.mark.parametrize("kind,status,reason", TERMINALS)
def test_terminal_status_is_private(client, user_factory, kind, status, reason):
    path, _, _ = _channel(user_factory, kind, status)
    outsider = user_factory("OutsideLocation")
    assert _close(client, path, outsider["token"]) == {
        "type": "websocket.close", "code": 1008, "reason": "Forbidden",
    }
    assert _close(client, path, "invalid-local-test-token") == {
        "type": "websocket.close", "code": 1008, "reason": "Invalid token",
    }


@pytest.mark.parametrize("kind,status", [
    ("instant", status) for status in ("accepted", "arriving", "onboard")
] + [("parcel", status) for status in ("accepted", "in_transit")])
def test_active_channel_still_relays_location(client, user_factory, kind, status):
    path, owner, driver = _channel(user_factory, kind, status)
    with client.websocket_connect(path) as receiver:
        receiver.send_json({"type": "auth", "token": owner["token"]})
        with client.websocket_connect(path) as sender:
            sender.send_json({"type": "auth", "token": driver["token"]})
            sender.send_json({"type": "loc", "lat": 54.0, "lng": 58.0, "ts": 7})
            frame = receiver.receive_json()
            assert frame["type"] == "loc"
            assert frame["lat"] == 54.0
            assert frame["lng"] == 58.0
            assert frame["role"] == ("driver" if kind == "instant" else "courier")
