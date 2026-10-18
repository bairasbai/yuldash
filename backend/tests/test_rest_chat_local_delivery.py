"""REST messages must reach an already open, authorized chat with Redis disabled.

Real ASGI HTTP + WebSocket handlers and SQLite; only the unavailable Redis boundary
is replaced. Bounded receives distinguish a missed event from a hanging test.
"""
import json
from contextlib import contextmanager
from unittest.mock import AsyncMock

import anyio
import pytest

from app import services
from test_location_privacy import _confirmed_booking


@pytest.fixture
def local_chat(monkeypatch):
    monkeypatch.setattr(services, "_cache_client", lambda: None)


@contextmanager
def connected(client, booking_id, actor):
    before = len(services.manager.active_connections.get(booking_id, []))
    with client.websocket_connect(f"/ws/bookings/{booking_id}") as ws:
        ws.send_json({"type": "auth", "token": actor["token"]})

        async def registered():
            with anyio.fail_after(2):
                while len(services.manager.active_connections.get(booking_id, [])) <= before:
                    await anyio.sleep(0.01)

        client.portal.call(registered)
        yield ws


def receive(ws, timeout=1):
    async def bounded():
        with anyio.move_on_after(timeout):
            event = await ws._send_rx.receive()
            if event["type"] == "websocket.close":
                return {"closed": event["code"]}
            return json.loads(event.get("text") or event["bytes"])
        return None

    return ws.portal.call(bounded)


def send(client, booking_id, actor, text="Local REST message", key=None):
    headers = dict(actor["auth"])
    if key is not None:
        headers["Idempotency-Key"] = key
    response = client.post(f"/bookings/{booking_id}/messages", headers=headers,
                           json={"text": text})
    assert response.status_code == 200, response.text
    return response.json()


@pytest.mark.parametrize("sender_role", ["driver", "passenger"])
@pytest.mark.parametrize("redis_state", ["off", "publish_failed"])
def test_rest_reaches_already_open_chat_without_history_reload(
    client, user_factory, monkeypatch, local_chat, sender_role, redis_state,
):
    if redis_state == "publish_failed":
        class BrokenRedis:
            def publish(self, *_args):
                raise ConnectionError("local test Redis unavailable")

        monkeypatch.setattr(services, "_cache_client", lambda: BrokenRedis())
    driver, passenger, bid = _confirmed_booking(client, user_factory, "RestLive")
    sender, receiver = ((driver, passenger) if sender_role == "driver"
                        else (passenger, driver))
    with connected(client, bid, receiver) as ws:
        saved = send(client, bid, sender)
        event = receive(ws)
        assert event is not None, "saved REST message was not delivered to the open socket"
        assert event["type"] == "message"
        assert event["id"] == saved["id"]
        assert event["text"] == saved["text"]
        assert event["sender_id"] == sender["id"]
        assert event["timestamp"] == saved["created_at"]
        assert receive(ws, 0.1) is None, "one REST post produced duplicate socket messages"


def test_local_delivery_stays_with_the_authorized_booking(client, user_factory, local_chat):
    driver, passenger, bid = _confirmed_booking(client, user_factory, "LocalTarget")
    _, other_passenger, other_bid = _confirmed_booking(client, user_factory, "OtherTarget")
    with connected(client, bid, passenger) as target:
        with connected(client, other_bid, other_passenger) as unrelated:
            saved = send(client, bid, driver)
            assert receive(target)["id"] == saved["id"]
            assert receive(unrelated, 0.15) is None, "another booking received private chat text"


def test_local_delivery_rejects_outsider_socket(client, user_factory, local_chat):
    driver, passenger, bid = _confirmed_booking(client, user_factory, "LocalPrivate")
    outsider = user_factory("LocalOutsider")
    with client.websocket_connect(f"/ws/bookings/{bid}") as stranger:
        stranger.send_json({"type": "auth", "token": outsider["token"]})
        with connected(client, bid, passenger) as target:
            saved = send(client, bid, driver)
            assert receive(target)["id"] == saved["id"]
            assert receive(stranger) == {"closed": 1008}


def test_rejected_rest_sender_does_not_emit_a_live_message(client, user_factory, local_chat):
    driver, passenger, bid = _confirmed_booking(client, user_factory, "LocalRejected")
    outsider = user_factory("LocalRestOutsider")
    with connected(client, bid, passenger) as ws:
        denied = client.post(f"/bookings/{bid}/messages", headers=outsider["auth"],
                             json={"text": "must not appear"})
        assert denied.status_code == 403
        assert receive(ws, 0.1) is None
        saved = send(client, bid, driver)
        assert receive(ws)["id"] == saved["id"]


def test_idempotent_rest_retry_emits_only_the_first_message(client, user_factory, local_chat):
    driver, passenger, bid = _confirmed_booking(client, user_factory, "LocalRetry")
    with connected(client, bid, passenger) as ws:
        first = send(client, bid, driver, key="local-retry-key")
        assert receive(ws)["id"] == first["id"]
        retry = send(client, bid, driver, key="local-retry-key")
        assert retry["id"] == first["id"]
        assert receive(ws, 0.15) is None


def test_both_open_participants_receive_one_copy(client, user_factory, local_chat):
    driver, passenger, bid = _confirmed_booking(client, user_factory, "LocalBoth")
    with connected(client, bid, passenger) as recipient:
        with connected(client, bid, driver) as sender:
            saved = send(client, bid, driver)
            assert receive(recipient)["id"] == saved["id"]
            assert receive(sender)["id"] == saved["id"]
            assert receive(recipient, 0.1) is None
            assert receive(sender, 0.1) is None


def test_failed_live_delivery_does_not_undo_committed_message(
    client, user_factory, monkeypatch, local_chat,
):
    driver, passenger, bid = _confirmed_booking(client, user_factory, "LocalBestEffort")
    monkeypatch.setattr(services.manager, "local_broadcast",
                        AsyncMock(side_effect=RuntimeError("local test delivery failure")))
    saved = send(client, bid, driver)
    history = client.get(f"/bookings/{bid}/messages", headers=passenger["auth"])
    assert history.status_code == 200
    assert [message["id"] for message in history.json()] == [saved["id"]]


def test_successful_redis_publish_does_not_also_deliver_locally(monkeypatch):
    published = []

    class WorkingRedis:
        def publish(self, channel, payload):
            published.append((channel, json.loads(payload)))
            return 1

    delivery = AsyncMock()
    monkeypatch.setattr(services, "_cache_client", lambda: WorkingRedis())
    monkeypatch.setattr(services.manager, "local_broadcast", delivery)
    data = {"type": "message", "id": 123, "text": "test"}

    async def post_commit_worker():
        await anyio.to_thread.run_sync(services.notify_chat_message, 42, data)

    anyio.run(post_commit_worker)
    assert published == [(services._CHAT_CHANNEL, {"booking_id": 42, "data": data})]
    delivery.assert_not_called()


def test_call_outside_rest_worker_remains_best_effort(local_chat):
    # Maintenance callers without an ASGI loop must not turn a committed send into 500.
    services.notify_chat_message(999999, {"type": "message", "id": 1})
