"""Regression tests for map and trip location WebSocket edge cases."""

import json

import pytest
from starlette.websockets import WebSocketDisconnect

from app.models import UserRole

from test_api import _ride


def test_map_websocket_rejects_invalid_token(client):
    with pytest.raises((WebSocketDisconnect, Exception)):
        with client.websocket_connect("/ws/map") as ws:
            ws.send_text(json.dumps({"type": "auth", "token": "bad-token"}))
            ws.receive_text()


def test_map_websocket_accepts_valid_token_and_disconnects_cleanly(client, user_factory):
    user = user_factory("MapWsUser")
    with client.websocket_connect("/ws/map") as ws:
        ws.send_text(json.dumps({"type": "auth", "token": user["token"]}))
        ws.send_text(json.dumps({"type": "ping"}))


def test_trip_location_rejects_non_participant(client, user_factory):
    driver = user_factory("LocForbiddenDriver", role=UserRole.driver)
    ride_id = _ride(client, driver, seats=1)
    passenger = user_factory("LocForbiddenPassenger")
    booking_id = client.post("/bookings", headers=passenger["auth"], json={"ride_id": ride_id, "seats": 1}).json()["id"]
    assert client.post(f"/bookings/{booking_id}/confirm", headers=driver["auth"]).status_code == 200
    outsider = user_factory("LocForbiddenOutsider")

    with pytest.raises((WebSocketDisconnect, Exception)):
        with client.websocket_connect(f"/ws/trip/{booking_id}/location") as ws:
            ws.send_text(json.dumps({"type": "auth", "token": outsider["token"]}))
            ws.receive_text()


def test_trip_location_ignores_bad_frames_before_valid_location(client, user_factory):
    driver = user_factory("LocBadFrameDriver", role=UserRole.driver)
    ride_id = _ride(client, driver, seats=1)
    passenger = user_factory("LocBadFramePassenger")
    booking_id = client.post("/bookings", headers=passenger["auth"], json={"ride_id": ride_id, "seats": 1}).json()["id"]
    assert client.post(f"/bookings/{booking_id}/confirm", headers=driver["auth"]).status_code == 200

    with client.websocket_connect(f"/ws/trip/{booking_id}/location") as driver_ws:
        driver_ws.send_text(json.dumps({"type": "auth", "token": driver["token"]}))
        with client.websocket_connect(f"/ws/trip/{booking_id}/location") as passenger_ws:
            passenger_ws.send_text(json.dumps({"type": "auth", "token": passenger["token"]}))
            driver_ws.send_text("{bad-json")
            driver_ws.send_text(json.dumps({"type": "loc", "lat": "bad", "lng": 58.02}))
            driver_ws.send_text(json.dumps({"type": "loc", "lat": 54.01, "lng": 58.02, "ts": 123}))
            message = json.loads(passenger_ws.receive_text())
            assert message["type"] == "loc"
            assert message["role"] == "driver"
            assert message["lat"] == 54.01
            assert message["lng"] == 58.02
            assert message["ts"] == 123
