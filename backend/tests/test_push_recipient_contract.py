"""Real FCM serialization; transport intercepted, no Firebase request or credentials."""
import json
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
