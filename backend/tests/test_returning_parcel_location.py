"""Return GPS remains private until the explicit policy decision recorded in decisions.md."""
import pytest

from app import ws_guard
from app.routers import location
from app.services import manager
from test_order_parcel_location_terminal_close import _channel, _close


@pytest.mark.parametrize("participant", [1, 2], ids=["sender", "courier"])
def test_returning_location_is_closed_to_both_participants(client, user_factory, participant):
    data = _channel(user_factory, "parcel", "returning")
    assert _close(client, data[0], data[participant]["token"]) == {
        "type": "websocket.close", "code": 1008, "reason": "Delivery not active",
    }


def test_return_start_closes_open_channels_and_removes_location_subscribers(client, user_factory, monkeypatch):
    path, owner, courier = _channel(user_factory, "parcel", "in_transit")
    parcel_id = int(path.split("/")[3])
    monkeypatch.setattr(ws_guard, "RECHECK_SEC", 0.05)
    with client.websocket_connect(path) as receive:
        receive.send_json({"type": "auth", "token": owner["token"]})
        with client.websocket_connect(path) as send:
            send.send_json({"type": "auth", "token": courier["token"]})
            send.send_json({"type": "loc", "lat": 54.0, "lng": 58.0, "ts": 1})
            assert receive.receive_json()["ts"] == 1

            response = client.post(f"/parcels/{parcel_id}/return-start", headers=courier["auth"])
            assert response.status_code == 200, response.text
            assert response.json()["status"] == "returning"
            # Passive sockets send no new coordinates: the real periodic guard removes both.
            assert receive.receive() == {"type": "websocket.close", "code": 1008, "reason": "Access ended"}
            assert send.receive() == {"type": "websocket.close", "code": 1008, "reason": "Access ended"}
            for offset in (0, 1):
                key = -(location.PARCEL_LOC_BASE + parcel_id * 2 + offset)
                assert not manager.active_connections.get(key), "Closed user must leave the location broadcast list"

    response = client.post(f"/parcels/{parcel_id}/return-done", headers=courier["auth"])
    assert response.status_code == 200, response.text
    assert response.json()["status"] == "returned"
    for user in (owner, courier):
        assert _close(client, path, user["token"]) == {
            "type": "websocket.close", "code": 1008, "reason": "Parcel ended",
        }


def test_returning_channel_keeps_outsiders_out(client, user_factory):
    path, _, _ = _channel(user_factory, "parcel", "returning")
    outsider = user_factory("ReturningOutsider")
    assert _close(client, path, outsider["token"]) == {
        "type": "websocket.close", "code": 1008, "reason": "Forbidden",
    }
