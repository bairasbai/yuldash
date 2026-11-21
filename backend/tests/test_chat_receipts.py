from datetime import timedelta, timezone

import pytest
from sqlalchemy import event
from sqlmodel import Session, select

from app.chat_receipt_models import ChatMessageReceipt
from app.config import settings
from app.db import engine
from app.models import Message
from app.routers import chat
from app.timeutil import utcnow
from test_chat_closes_after_trip import _finish
from test_location_privacy import _confirmed_booking
from test_rest_chat_local_delivery import connected, receive, send


@pytest.fixture
def enabled(monkeypatch):
    since = utcnow() - timedelta(hours=1)
    monkeypatch.setattr(settings, "chat_receipts_since", since.replace(tzinfo=timezone.utc))
    return since


def _receipts(bid):
    return f"/bookings/{bid}/receipts"


def _state(client, bid, actor, **query):
    response = client.get(_receipts(bid), headers=actor["auth"], params=query)
    assert response.status_code == 200, response.text
    return response.json()


def _post(client, bid, actor, **body):
    response = client.post(_receipts(bid), headers=actor["auth"], json=body)
    assert response.status_code == 200, response.text
    return response.json()


def _unread(client, bid, actor):
    rows = client.get("/conversations", headers=actor["auth"]).json()
    return next(row["unread"] for row in rows if row["booking_id"] == bid)


def test_receipts_follow_explicit_ids_and_never_go_backwards(client, user_factory, enabled, monkeypatch):
    driver, passenger, bid = _confirmed_booking(client, user_factory, "ReceiptGap")
    first = send(client, bid, driver, "Пропущено до восстановления истории")["id"]
    second = send(client, bid, driver, "Доставлено после восстановления")["id"]
    own = send(client, bid, passenger, "Отвечаю")["id"]
    assert _unread(client, bid, passenger) == 2
    frames = []

    def inspect_publication(booking_id, frame):
        assert booking_id == bid
        with Session(engine) as session:
            for item in frame["items"]:
                assert session.get(ChatMessageReceipt, (item["message_id"], item["user_id"])) is not None
        frames.append(frame)

    monkeypatch.setattr(chat, "notify_chat_message", inspect_publication)
    applied = _post(client, bid, passenger, delivered_ids=[second, own, second + 10000])
    assert [item["message_id"] for item in applied["items"]] == [second]
    assert applied["items"][0]["my_delivered"]
    assert applied["unread"] == 2
    assert _post(client, bid, passenger, delivered_ids=[second])["items"][0]["my_delivered"]
    assert len(frames) == 1

    assert _post(client, bid, passenger, read_ids=[second])["unread"] == 1
    assert _post(client, bid, passenger, delivered_ids=[second])["items"][0]["my_read"]
    assert _unread(client, bid, passenger) == 1
    assert len(frames) == 2
    assert frames[-1] == {"type": "receipt", "items": [
        {"message_id": second, "user_id": passenger["id"], "delivered": True, "read": True},
    ]}
    before = _state(client, bid, driver, limit=1)
    assert before["next_after_message_id"] == first
    assert not before["items"][0]["peer_delivered"]
    after = _state(client, bid, driver, after_message_id=first)
    assert after["items"][0]["message_id"] == second
    assert after["items"][0]["peer_read"]
    assert set(after["items"][0]) == {"message_id", "peer_delivered", "peer_read", "my_delivered", "my_read"}
    with Session(engine) as session:
        assert session.get(ChatMessageReceipt, (first, passenger["id"])) is None
        assert session.get(ChatMessageReceipt, (own, passenger["id"])) is None


def test_disabled_protocol_keeps_old_history_without_receipt_queries(client, user_factory, monkeypatch):
    monkeypatch.setattr(settings, "chat_receipts_since", None)
    driver, passenger, bid = _confirmed_booking(client, user_factory, "ReceiptDisabled")
    message = send(client, bid, driver, "История доступна")["id"]
    receipt_queries = []

    def record(_conn, _cursor, statement, _params, _ctx, _many):
        if "chatmessagereceipt" in statement.lower():
            receipt_queries.append(statement)

    event.listen(engine, "before_cursor_execute", record)
    try:
        assert not _state(client, bid, passenger)["open"]
        assert not _post(client, bid, passenger, read_ids=[message])["open"]
        assert _unread(client, bid, passenger) is None
        page = client.get(f"/bookings/{bid}/message-page", headers=passenger["auth"]).json()
        assert [row["id"] for row in page["messages"]] == [message]
        assert not receipt_queries
    finally:
        event.remove(engine, "before_cursor_execute", record)


def test_hidden_deleted_and_older_messages_do_not_count_or_receive_receipts(client, user_factory, enabled):
    driver, passenger, bid = _confirmed_booking(client, user_factory, "ReceiptVisibility")
    old = send(client, bid, driver, "До начала протокола")["id"]
    hidden = send(client, bid, driver, "Скрыто у получателя")["id"]
    deleted = send(client, bid, driver, "Удалено у всех")["id"]
    visible = send(client, bid, driver, "Текущее сообщение")["id"]
    with Session(engine) as session:
        session.get(Message, old).created_at = enabled - timedelta(seconds=1)
        session.commit()
    assert client.delete(f"/bookings/{bid}/messages/{hidden}", headers=passenger["auth"],
                         params={"scope": "me"}).status_code == 200
    assert client.delete(f"/bookings/{bid}/messages/{deleted}", headers=driver["auth"],
                         params={"scope": "all"}).status_code == 200
    current = _state(client, bid, passenger)
    assert current["unread"] == 1
    assert [row["message_id"] for row in current["items"]] == [visible]
    assert _post(client, bid, passenger, read_ids=[old, hidden, deleted])["items"] == []
    history = client.get(f"/bookings/{bid}/message-page", headers=passenger["auth"],
                         params={"after_id": old, "limit": 1}).json()
    assert [m["id"] for m in history["messages"]] == [deleted]
    assert history["messages"][0]["deleted"] and history["has_more"]
    older = client.get(f"/bookings/{bid}/message-page", headers=passenger["auth"],
                       params={"before_id": deleted}).json()
    assert [m["id"] for m in older["messages"]] == [old]


def test_block_and_closed_window_hide_receipts_while_preserving_history(client, user_factory, enabled):
    driver, passenger, bid = _confirmed_booking(client, user_factory, "ReceiptPrivacy")
    stranger = user_factory("ReceiptStranger")
    message = send(client, bid, driver, "Участникам видна история")["id"]
    assert client.get(_receipts(bid), headers=stranger["auth"]).status_code == 403
    assert client.post(_receipts(bid), headers=stranger["auth"],
                       json={"read_ids": [message]}).status_code == 403
    assert client.post("/blocks", headers=driver["auth"], json={"blocked_user_id": passenger["id"]}).status_code == 200
    assert not _state(client, bid, passenger)["open"]
    assert not _post(client, bid, passenger, read_ids=[message])["open"]
    assert client.delete(f"/blocks/{passenger['id']}", headers=driver["auth"]).status_code == 200
    assert _state(client, bid, passenger)["open"]
    _finish(bid, days_ago=3)
    assert not _post(client, bid, passenger, read_ids=[message])["open"]
    assert _unread(client, bid, passenger) == 0
    page = client.get(f"/bookings/{bid}/message-page", headers=passenger["auth"]).json()
    assert [row["id"] for row in page["messages"]] == [message]
    with Session(engine) as session:
        assert not session.exec(select(ChatMessageReceipt).where(ChatMessageReceipt.message_id == message)).all()


@pytest.mark.parametrize("ids", [[1, 1], [0], ["1"], [True], list(range(1, 102))])
def test_reject_invalid_receipt_ids(client, user_factory, enabled, ids):
    _, passenger, bid = _confirmed_booking(client, user_factory, "ReceiptInput")
    result = client.post(_receipts(bid), headers=passenger["auth"], json={"read_ids": ids})
    assert result.status_code == 422



def test_socket_batches_receipt_ids_without_redis(client, user_factory, enabled, monkeypatch):
    from app import services

    monkeypatch.setattr(services, "_redis_pub", None)
    driver, passenger, bid = _confirmed_booking(client, user_factory, "ReceiptSocket")
    first = send(client, bid, driver, "Сообщение раньше")["id"]
    second = send(client, bid, driver, "Сообщение позже")["id"]

    with connected(client, bid, driver) as peer, connected(client, bid, passenger) as recipient:
        recipient.send_json({"type": "receipt", "delivered_ids": [second]})
        later = receive(peer)
        assert later == {"type": "receipt", "items": [
            {"message_id": second, "user_id": passenger["id"], "delivered": True, "read": False},
        ]}
        assert receive(recipient) == later
        assert receive(recipient)["type"] == "receipt_ack"
        assert _unread(client, bid, passenger) == 2

        recipient.send_json({"type": "receipt", "delivered_ids": [first]})
        recipient.send_json({"type": "receipt", "read_ids": [second]})
        combined = receive(peer, 3)
        assert combined["type"] == "receipt"
        assert {item["message_id"]: item["read"] for item in combined["items"]} == {first: False, second: True}
        assert _unread(client, bid, passenger) == 1


def test_closed_socket_does_not_drop_the_pending_receipt(client, user_factory, enabled, monkeypatch):
    from app import services

    monkeypatch.setattr(services, "_redis_pub", None)
    driver, passenger, bid = _confirmed_booking(client, user_factory, "ReceiptSocketClose")
    first = send(client, bid, driver, "Первое сообщение")["id"]
    second = send(client, bid, driver, "Второе сообщение")["id"]

    with connected(client, bid, driver) as peer:
        with connected(client, bid, passenger) as recipient:
            recipient.send_json({"type": "receipt", "delivered_ids": [first]})
            assert receive(peer)["items"][0]["message_id"] == first
            assert receive(recipient)["type"] == "receipt"
            assert receive(recipient)["type"] == "receipt_ack"
            recipient.send_json({"type": "receipt", "read_ids": [second]})
            recipient.close()
            closed = receive(peer, 3)
            assert closed is not None
            assert closed["items"][0]["message_id"] == second
            assert closed["items"][0]["read"]
    assert _unread(client, bid, passenger) == 1


def test_socket_omits_receipts_for_messages_hidden_by_the_listener(client, user_factory, enabled, monkeypatch):
    from app import services

    monkeypatch.setattr(services, "_redis_pub", None)
    driver, passenger, bid = _confirmed_booking(client, user_factory, "ReceiptHiddenListener")
    hidden = send(client, bid, driver, "Скрою это у себя")["id"]
    visible = send(client, bid, driver, "Это останется в истории")["id"]
    assert client.delete(f"/bookings/{bid}/messages/{hidden}", headers=driver["auth"],
                         params={"scope": "me"}).status_code == 200
    with connected(client, bid, driver) as peer, connected(client, bid, passenger) as recipient:
        recipient.send_json({"type": "receipt", "read_ids": [hidden, visible]})
        frame = receive(peer)
        assert [item["message_id"] for item in frame["items"]] == [visible]
        own = receive(recipient)
        assert {item["message_id"] for item in own["items"]} == {hidden, visible}
        assert receive(recipient)["type"] == "receipt_ack"
    assert [item["message_id"] for item in _state(client, bid, driver)["items"]] == [visible]


def test_overflow_receipt_survives_a_disconnected_ack(client, user_factory, enabled, monkeypatch):
    from starlette.websockets import WebSocket, WebSocketDisconnect
    from app import services

    monkeypatch.setattr(services, "_redis_pub", None)
    driver, passenger, bid = _confirmed_booking(client, user_factory, "ReceiptOverflow")
    first = send(client, bid, driver, "Проверка первой пачки")["id"]
    second = send(client, bid, driver, "Проверка заполненной пачки")["id"]
    third = send(client, bid, driver, "Проверка переполнения")["id"]
    regular_send = WebSocket.send_json
    acks = 0

    async def disconnect_during_ack(self, data, mode="text"):
        nonlocal acks
        if data.get("type") == "receipt_ack":
            acks += 1
            if acks == 2:
                raise WebSocketDisconnect()
        await regular_send(self, data, mode=mode)

    monkeypatch.setattr(WebSocket, "send_json", disconnect_during_ack)
    with connected(client, bid, driver) as peer, connected(client, bid, passenger) as recipient:
        recipient.send_json({"type": "receipt", "delivered_ids": [first]})
        assert receive(peer)["items"][0]["message_id"] == first
        assert receive(recipient)["type"] == "receipt"
        assert receive(recipient)["type"] == "receipt_ack"
        recipient.send_json({"type": "receipt", "delivered_ids": [second, *range(10000, 10099)]})
        recipient.send_json({"type": "receipt", "delivered_ids": [third]})
        assert receive(peer, 3)["items"][0]["message_id"] == second
        surviving = receive(peer, 3)
        assert surviving is not None and surviving["items"][0]["message_id"] == third

def test_push_hint_is_sent_only_with_the_enabled_receipt_protocol(client, user_factory, enabled, monkeypatch):
    driver, passenger, bid = _confirmed_booking(client, user_factory, "ReceiptPushHint")
    payloads = []
    monkeypatch.setattr(chat, "push_notification", lambda *args, **kwargs: payloads.append(kwargs["data"]))
    first = send(client, bid, driver, "Текущее сообщение")["id"]
    assert payloads[-1] == {"type": "chat", "id": bid, "mid": first}
    monkeypatch.setattr(settings, "chat_receipts_since", None)
    send(client, bid, passenger, "Старый режим")
    assert payloads[-1] == {"type": "chat", "id": bid}
