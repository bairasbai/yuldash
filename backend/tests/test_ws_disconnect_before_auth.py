"""A peer closing before its auth frame must not trigger another server close."""
import pytest
from starlette.websockets import WebSocket, WebSocketDisconnect, WebSocketState

PATHS = ["/ws/bookings/1", "/ws/instant/1/chat", "/ws/parcel/1/chat",
         "/ws/map", "/ws/trip/1/location", "/ws/instant/1/location",
         "/ws/parcel/1/location"]


@pytest.mark.parametrize("path", PATHS)
def test_client_disconnect_before_auth_does_not_close_again(client, monkeypatch, path):
    late_closes = []
    original = WebSocket.close

    async def record_close(socket, *args, **kwargs):
        if socket.client_state == WebSocketState.DISCONNECTED:
            late_closes.append(True)
        return await original(socket, *args, **kwargs)

    monkeypatch.setattr(WebSocket, "close", record_close)
    with client.websocket_connect(path) as websocket:
        websocket.close()
    assert not late_closes, "Server tried to close an already disconnected client"


@pytest.mark.parametrize("path", PATHS)
def test_malformed_auth_frame_still_rejected(client, path):
    with client.websocket_connect(path) as websocket:
        websocket.send_json({"type": "auth", "token": "synthetic-invalid-token"})
        with pytest.raises(WebSocketDisconnect) as closed:
            websocket.receive_json()
        assert closed.value.code == 1008
