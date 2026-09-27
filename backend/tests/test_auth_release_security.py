"""Release security regressions; local database and simulated provider messages only."""
from uuid import uuid4

import pytest
from sqlmodel import Session, select

from app.config import settings
from app.db import engine
from app.models import TgAuth, User, WebPushSubscription


def _telegram_message(client, **message):
    return client.post(
        "/telegram/webhook",
        headers={"x-telegram-bot-api-secret-token": "local-security-test"},
        json={"message": message},
    )


@pytest.mark.parametrize("same_telegram_user", [False, True])
def test_telegram_request_keeps_phone_bound_to_original_identity(
    client, monkeypatch, same_telegram_user,
):
    monkeypatch.setattr(settings, "telegram_webhook_secret", "local-security-test")
    codes = iter(["111111", "222222"])
    monkeypatch.setattr("app.routers.auth.gen_otp", lambda: next(codes))
    phone = "+79996110101" if same_telegram_user else "+79996110102"
    original_id = 96110101 if same_telegram_user else 96110102
    with Session(engine) as session:
        original = User(phone=phone, name="Original owner")
        session.add(original)
        session.commit()
        original_user_id = original.id

    request_id = client.post("/auth/tg/start").json()["request_id"]
    assert _telegram_message(
        client, text=f"/start {request_id}",
        **{"from": {"id": original_id}, "chat": {"id": original_id}},
    ).status_code == 200
    assert _telegram_message(
        client, contact={"user_id": original_id, "phone_number": phone},
        **{"from": {"id": original_id}, "chat": {"id": original_id}},
    ).status_code == 200

    retry_id = original_id if same_telegram_user else 96110999
    assert _telegram_message(
        client, text=f"/start {request_id}",
        **{"from": {"id": retry_id}, "chat": {"id": retry_id}},
    ).status_code == 200
    result = client.post("/auth/tg/verify", json={"request_id": request_id, "code": "222222"})
    if same_telegram_user:
        assert result.status_code == 200, result.text
        assert result.json()["user"]["id"] == original_user_id
    else:
        # The second Telegram identity must not get a code for the original's phone.
        assert result.status_code == 400, result.text
        with Session(engine) as session:
            row = session.exec(select(TgAuth).where(TgAuth.request_id == request_id)).one()
            assert row.telegram_id == str(original_id)
            assert row.shared_phone == phone
            assert session.get(User, original_user_id).telegram_id is None


@pytest.mark.parametrize("original_device,new_device,allowed", [
    ("", "", False),
    ("browser-owner-123", "", False),
    ("", "browser-other-456", False),
    ("browser-owner-123", "browser-other-456", False),
    ("browser-owner-123", "browser-owner-123", True),
])
def test_web_push_subscription_transfer_requires_same_device(
    client, user_factory, original_device, new_device, allowed,
):
    original, other = user_factory(), user_factory()
    endpoint = f"https://fcm.googleapis.com/fcm/send/{uuid4().hex}"
    body = {"endpoint": endpoint, "keys": {"p256dh": "local-public-key", "auth": "local-auth"}}
    initial = client.post("/push/web/subscribe", headers={
        **original["auth"], "X-Device-Id": original_device,
    }, json=body)
    assert initial.status_code == 200, initial.text
    result = client.post("/push/web/subscribe", headers={
        **other["auth"], "X-Device-Id": new_device,
    }, json={**body, "keys": {"p256dh": "replacement-key", "auth": "replacement-auth"}})
    assert result.status_code == (200 if allowed else 409), result.text
    with Session(engine) as session:
        row = session.exec(select(WebPushSubscription).where(
            WebPushSubscription.endpoint == endpoint,
        )).one()
        assert row.user_id == (other["id"] if allowed else original["id"])
        assert row.p256dh == ("replacement-key" if allowed else "local-public-key")


def test_web_push_owner_can_refresh_legacy_subscription(client, user_factory):
    owner = user_factory()
    body = {"endpoint": f"https://fcm.googleapis.com/fcm/send/{uuid4().hex}",
            "keys": {"p256dh": "local-public-key", "auth": "local-auth"}}
    for _ in range(2):
        result = client.post("/push/web/subscribe", headers=owner["auth"], json=body)
        assert result.status_code == 200, result.text


@pytest.mark.parametrize("endpoint", [
    "https://127.0.0.1/internal", "https://[::1]/internal", "https://169.254.169.254/metadata",
    "https://localhost/push", "https://attacker.example/push",
    "https://fcm.googleapis.com.attacker.example/push",
    "https://evilnotify.windows.com/push", "https://fcm.googleapis.com@127.0.0.1/push",
    "https://fcm.googleapis.com:8443/push", "https://fcm.googleapis.com/push#fragment",
    "https://fcm.googleapis.com\\@127.0.0.1/push", "https://fcm.googleapis.com/\npush",
])
def test_web_push_rejects_untrusted_destinations(client, user_factory, endpoint):
    owner = user_factory()
    result = client.post("/push/web/subscribe", headers=owner["auth"], json={
        "endpoint": endpoint, "keys": {"p256dh": "local-key", "auth": "local-auth"},
    })
    assert result.status_code == 400, result.text
    with Session(engine) as session:
        assert session.exec(select(WebPushSubscription).where(
            WebPushSubscription.user_id == owner["id"],
        )).first() is None


@pytest.mark.parametrize("host", [
    "fcm.googleapis.com", "updates.push.services.mozilla.com", "web.push.apple.com",
    "wns2-test.notify.windows.com",
])
def test_web_push_accepts_supported_provider_hosts(client, user_factory, host):
    result = client.post("/push/web/subscribe", headers=user_factory()["auth"], json={
        "endpoint": f"https://{host}/push/{uuid4().hex}",
        "keys": {"p256dh": "local-key", "auth": "local-auth"},
    })
    assert result.status_code == 200, result.text


def test_web_push_sender_rejects_legacy_untrusted_endpoint(client, user_factory, monkeypatch):
    from app.services import _send_web_push

    owner = user_factory()
    monkeypatch.setattr(settings, "vapid_private_key", "local-private-key")
    monkeypatch.setattr(settings, "vapid_public_key", "local-public-key")
    sent = []
    monkeypatch.setattr("pywebpush.webpush", lambda **kwargs: sent.append(kwargs))
    with Session(engine) as session:
        session.add(WebPushSubscription(user_id=owner["id"],
            endpoint=f"https://127.0.0.1/internal/{uuid4().hex}", p256dh="local-key", auth="local-auth"))
        session.commit()
        _send_web_push(session, owner["id"], "Title", "Body")
    assert sent == []


def test_web_push_transport_never_follows_redirects(monkeypatch):
    import requests
    from app.push_endpoint_policy import PushRequestsSession

    sent = []
    monkeypatch.setattr(requests.Session, "request", lambda self, method, url, **kwargs:
                        sent.append((method, url, kwargs)))
    with PushRequestsSession() as transport:
        transport.post("https://fcm.googleapis.com/local-push", allow_redirects=True)
        with pytest.raises(ValueError, match="Untrusted"):
            transport.post("https://127.0.0.1/internal")
    assert len(sent) == 1
    assert sent[0][2]["allow_redirects"] is False


@pytest.mark.parametrize("same_device", [False, True])
def test_web_push_insert_race_rechecks_subscription_owner(
    client, user_factory, monkeypatch, same_device,
):
    original, other = user_factory(), user_factory()
    endpoint = f"https://fcm.googleapis.com/fcm/send/{uuid4().hex}"
    original_commit = Session.commit
    inserted = False

    def racing_commit(session):
        nonlocal inserted
        if not inserted and any(isinstance(row, WebPushSubscription) and row.endpoint == endpoint
                                for row in session.new):
            inserted = True
            with Session(engine) as competing:
                competing.add(WebPushSubscription(user_id=original["id"], endpoint=endpoint,
                    p256dh="original-key", auth="original-auth", device_id="original-browser"))
                original_commit(competing)
        return original_commit(session)

    monkeypatch.setattr(Session, "commit", racing_commit)
    result = client.post("/push/web/subscribe", headers={
        **other["auth"], "X-Device-Id": "original-browser" if same_device else "other-browser",
    }, json={"endpoint": endpoint, "keys": {"p256dh": "new-key", "auth": "new-auth"}})
    assert inserted
    assert result.status_code == (200 if same_device else 409), result.text
    with Session(engine) as session:
        row = session.exec(select(WebPushSubscription).where(WebPushSubscription.endpoint == endpoint)).one()
        assert row.user_id == (other["id"] if same_device else original["id"])
        assert row.p256dh == ("new-key" if same_device else "original-key")
