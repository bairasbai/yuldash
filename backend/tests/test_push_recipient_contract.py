"""Real FCM serialization; transport intercepted, no Firebase request or credentials."""
import json
from datetime import timedelta
from threading import Event, Thread
from types import SimpleNamespace

import pytest
from firebase_admin import messaging
from sqlmodel import Session

from app import services
from app.config import settings
from app.db import engine
from app.models import DeviceToken


@pytest.mark.parametrize("data_only", [False, True])
@pytest.mark.parametrize("data", [None, {"type": "instant", "order_id": 42},
                                  {"type": "chat", "id": 17, "recipient_user_id": "wrong-account"}])
def test_every_fcm_message_is_checked_by_the_receiving_account(monkeypatch, user_factory, data_only, data):
    owner = user_factory("RecipientOwner")
    other = user_factory("RecipientOther")
    own_tokens = {f"recipient-{owner['id']}-a", f"recipient-{owner['id']}-b"}
    with Session(engine) as session:
        for token in own_tokens:
            session.add(DeviceToken(user_id=owner["id"], token=token))
        session.add(DeviceToken(user_id=other["id"], token=f"recipient-{other['id']}"))
        session.commit()
    monkeypatch.setattr(settings, "firebase_credentials", "never-read.json")
    monkeypatch.setattr(services, "_fcm_app", object())
    web = []
    monkeypatch.setattr(services, "_send_web_push", lambda *args: web.append(args[1:]))
    captured = []

    def capture(messages):
        # SDK encoding validates the same JSON fields that would be sent to FCM.
        captured.extend(json.loads(messaging._MessagingService.JSON_ENCODER.encode(m)) for m in messages)
        return SimpleNamespace(responses=[SimpleNamespace(success=True, exception=None) for _ in messages])

    monkeypatch.setattr(messaging, "send_each", capture)
    original = dict(data) if data else data
    with Session(engine) as session:
        services.send_push(session, owner["id"], "Заголовок", "Текст", data=data, data_only=data_only)
    assert data == original
    assert web == [(owner["id"], "Заголовок", "Текст", original)]
    assert {m["token"] for m in captured} == own_tokens
    for message in captured:
        assert "notification" not in message, "Background auto-display bypasses account validation"
        assert message["android"]["priority"] == "high"
        assert message["data"]["recipient_user_id"] == str(owner["id"])
        assert message["data"]["title"] == "Заголовок"
        assert message["data"]["body"] == "Текст"
        for key, value in (original or {}).items():
            if key != "recipient_user_id":
                assert message["data"][key] == str(value)

@pytest.fixture
def offer_transports(monkeypatch, user_factory):
    owner = user_factory("OfferPushDriver")
    with Session(engine) as session:
        session.add(DeviceToken(user_id=owner["id"], token=f"driver-offer-{owner['id']}"))
        session.commit()
    monkeypatch.setattr(settings, "firebase_credentials", "never-read.json")
    monkeypatch.setattr(services, "_fcm_app", object())
    monkeypatch.setattr(settings, "vapid_public_key", "fixture-public")
    monkeypatch.setattr(settings, "vapid_private_key", "fixture-private")
    return owner["id"]


def test_taxi_fcm_is_not_held_behind_slow_web_and_sends_only_remaining_ttl(monkeypatch, offer_transports):
    now = services.utcnow()
    deadline = now + timedelta(seconds=20)
    monkeypatch.setattr(services, "utcnow", lambda: now + timedelta(seconds=18, milliseconds=500))
    web_started, web_release, web_done, android_started = Event(), Event(), Event(), Event()
    captured, web_sessions, request_sessions = [], [], []

    def slow_web(session, *_args, **_kwargs):
        web_sessions.append(session)
        web_started.set()
        try:
            web_release.wait(timeout=5)
        finally:
            web_done.set()

    def fcm(messages):
        assert web_started.wait(2), "FCM must not hold up the start of the browser delivery"
        captured.extend(json.loads(messaging._MessagingService.JSON_ENCODER.encode(m)) for m in messages)
        android_started.set()
        return SimpleNamespace(responses=[SimpleNamespace(success=True, exception=None) for _ in messages])

    monkeypatch.setattr(services, "_send_web_push", slow_web)
    monkeypatch.setattr(messaging, "send_each", fcm)

    def send():
        with Session(engine) as session:
            request_sessions.append(session)
            services.send_push(session, offer_transports, "Offer", "Fixture", data={
                "type": "instant_offer", "offer_expires_at": deadline.isoformat(), "ttl_sec": "20",
            })

    request = Thread(target=send, daemon=True)
    request.start()
    try:
        assert web_started.wait(3)
        assert android_started.wait(1), "Android must reach FCM before Web Push releases"
        assert request_sessions[0] is not web_sessions[0]
        assert len(captured) == 1 and captured[0]["android"]["ttl"] == "1s"
        assert captured[0]["data"]["ttl_sec"] == "20"
    finally:
        web_release.set()
        request.join(timeout=5)
        assert web_done.wait(2)


@pytest.mark.parametrize("raw_deadline", ["expired", "missing", "invalid"])
def test_expired_or_undated_taxi_push_does_not_reach_fcm(monkeypatch, offer_transports, raw_deadline):
    monkeypatch.setattr(settings, "vapid_private_key", "")
    monkeypatch.setattr(messaging, "send_each", lambda _messages: pytest.fail("stale offer reached FCM"))
    now = services.utcnow()
    deadline = {"expired": now.isoformat(), "invalid": "not-a-time"}.get(raw_deadline)
    monkeypatch.setattr(services, "utcnow", lambda: now)
    data = {"type": "instant_offer", "ttl_sec": "20"}
    if deadline is not None:
        data["offer_expires_at"] = deadline
    with Session(engine) as session:
        services.send_push(session, offer_transports, "Offer", "Fixture", data=data)


def test_queued_offer_expires_before_its_first_web_attempt(monkeypatch, offer_transports):
    now = services.utcnow()
    deadline = now + timedelta(seconds=2)
    monkeypatch.setattr(services, "utcnow", lambda: now)
    monkeypatch.setattr(settings, "firebase_credentials", "")
    monkeypatch.setattr(services, "_send_web_push", lambda *_a, **_kw: pytest.fail("expired queued offer dispatched"))
    jobs = []
    monkeypatch.setattr(services, "_offer_web_push_pool", SimpleNamespace(submit=jobs.append))
    with Session(engine) as session:
        services.send_push(session, offer_transports, "Offer", "Fixture", data={
            "type": "instant_offer", "offer_expires_at": deadline.isoformat(),
        })
    assert len(jobs) == 1
    now = deadline
    jobs[0]()
