"""QA-B01-014: fixed store-review credentials must not open privileged accounts."""
from datetime import timedelta
from concurrent.futures import ThreadPoolExecutor
import threading
import uuid

import pytest
from fastapi.testclient import TestClient
from sqlmodel import Session, select

from app.config import settings
from app.db import engine
from app.main import app
from app.models import (
    Consent, DeviceToken, DriverProfile, RefreshToken, TgAuth, TrustedContact, User, UserRole,
)
from app.security import _hash_refresh, make_token
from app.timeutil import utcnow
from app.routers import auth


REVIEW_CODE = "424242"  # Synthetic configuration; no production credential is read.


def _account(role):
    ident = uuid.uuid4().hex
    phone = "+7999" + str(int(ident[:8], 16) % 10_000_000).zfill(7)
    with Session(engine) as session:
        user = User(phone=phone, name="QA existing account", role=role, verified=True,
                    telegram_id="qa-review-" + ident, language="ba", city="QA town",
                    last_device_id="qa-existing-device", last_seen_at=utcnow() - timedelta(hours=1),
                    tokens_valid_from=utcnow() - timedelta(days=1), referral_credits=7)
        session.add(user)
        session.commit()
        session.refresh(user)
        user_id = user.id
        session.add(RefreshToken(user_id=user_id, token_hash=_hash_refresh("qa-prior-" + ident),
                                 expires_at=utcnow() + timedelta(days=1)))
        session.add(Consent(user_id=user_id, kind="privacy", granted_at=utcnow() - timedelta(days=1)))
        session.add(DeviceToken(user_id=user_id, token="qa-fcm-" + ident, device_id="qa-existing-device"))
        session.add(TrustedContact(user_id=user_id, name="QA friend", phone="qa-contact-" + ident))
        session.add(DriverProfile(user_id=user_id, online=True, work_city="QA town"))
        session.commit()
    return user_id, phone, make_token(user_id)


def _snapshot(user_id):
    with Session(engine) as session:
        result = {"User": session.get(User, user_id).model_dump()}
        for model in (RefreshToken, Consent, DeviceToken, TrustedContact, DriverProfile):
            rows = session.exec(select(model).where(model.user_id == user_id).order_by(model.id)).all()
            result[model.__name__] = [row.model_dump() for row in rows]
        return result


@pytest.mark.parametrize("role", [UserRole.admin, UserRole.driver])
def test_fixed_review_login_rejects_existing_privileged_account_without_mutating_it(client, monkeypatch, role):
    user_id, phone, prior_access = _account(role)
    monkeypatch.setattr(settings, "review_phone", phone)
    monkeypatch.setattr(settings, "review_code", REVIEW_CODE)
    # Even a correctly configured admin remains unavailable to store-review credentials.
    monkeypatch.setattr(settings, "admin_phones", phone if role == UserRole.admin else "")
    monkeypatch.setattr(settings, "admin_telegram_chat_id", "")
    emitted = []
    monkeypatch.setattr("app.services.push_notification", lambda *a, **kw: emitted.append("push"))
    monkeypatch.setattr("app.services.send_text", lambda *a, **kw: emitted.append("sms"))
    before = _snapshot(user_id)
    old_admin_status = client.get("/admin/bans", headers={"Authorization": "Bearer " + prior_access}).status_code
    assert old_admin_status == (200 if role == UserRole.admin else 403)

    response = client.post("/auth/verify", json={"phone": phone, "code": REVIEW_CODE},
                           headers={"X-Device-Id": "qa-store-review-device"})
    new_admin_status = None
    if response.status_code == 200:
        # A real protected request with the actually issued token proves the breach.
        new_admin_status = client.get("/admin/bans", headers={
            "Authorization": "Bearer " + response.json()["access_token"],
        }).status_code
    after = _snapshot(user_id)
    changed = [name for name in before if after[name] != before[name]]
    assert response.status_code == 400, (
        f"fixed review code opened {role.value}: HTTP={response.status_code}, "
        f"admin endpoint={new_admin_status}, changed={changed}, signals={emitted}"
    )
    assert changed == [], "blocked store login changed existing account data"
    assert emitted == []
    assert client.get("/me", headers={"Authorization": "Bearer " + prior_access}).status_code == 200
    assert client.get("/admin/bans", headers={"Authorization": "Bearer " + prior_access}).status_code == old_admin_status


@pytest.mark.parametrize("kind", ["sms", "telegram"])
@pytest.mark.parametrize("role", [UserRole.admin, UserRole.driver])
def test_normal_verified_login_preserves_existing_privileged_role(client, monkeypatch, role, kind):
    user_id, phone, _ = _account(role)
    monkeypatch.setattr(settings, "review_phone", "")
    monkeypatch.setattr(settings, "review_code", "")
    monkeypatch.setattr(settings, "admin_phones", phone if role == UserRole.admin else "")
    monkeypatch.setattr(settings, "admin_telegram_chat_id", "")
    before = _snapshot(user_id)
    if kind == "sms":
        requested = client.post("/auth/request-code", json={"phone": phone})
        assert requested.status_code == 200
        path, body = "/auth/verify", {"phone": phone, "code": requested.json()["dev_code"]}
    else:
        ident = uuid.uuid4().hex
        with Session(engine) as session:
            session.add(TgAuth(request_id=ident, telegram_id=before["User"]["telegram_id"],
                               status="sent", code="246810", expires_at=utcnow() + timedelta(minutes=5)))
            session.commit()
        path, body = "/auth/tg/verify", {"request_id": ident, "code": "246810"}
    result = client.post(path, json=body, headers={"X-Device-Id": "qa-existing-device"})
    assert result.status_code == 200, result.text
    assert result.json()["user"]["id"] == user_id
    assert result.json()["user"]["role"] == role.value
    assert result.json()["user"]["is_reviewer"] is False
    assert client.get("/admin/bans", headers={"Authorization": "Bearer " + result.json()["access_token"]}).status_code == (
        200 if role == UserRole.admin else 403
    )
    after = _snapshot(user_id)
    assert after["User"]["role"] == role
    assert after["User"]["name"] == before["User"]["name"]
    assert after["DriverProfile"] == before["DriverProfile"]
    assert after["DeviceToken"] == before["DeviceToken"]
    assert after["TrustedContact"] == before["TrustedContact"]
    assert len(after["RefreshToken"]) == len(before["RefreshToken"]) + 1


def test_normal_passenger_store_review_stays_passenger(client, monkeypatch):
    user_id, phone, _ = _account(UserRole.passenger)
    monkeypatch.setattr(settings, "review_phone", phone)
    monkeypatch.setattr(settings, "review_code", REVIEW_CODE)
    monkeypatch.setattr(settings, "admin_phones", phone)
    before = _snapshot(user_id)
    result = client.post("/auth/verify", json={"phone": phone, "code": REVIEW_CODE},
                         headers={"X-Device-Id": "qa-existing-device"})
    assert result.status_code == 200
    assert result.json()["user"]["id"] == user_id
    assert result.json()["user"]["role"] == "passenger"
    assert result.json()["user"]["is_reviewer"] is True
    assert client.get("/admin/bans", headers={"Authorization": "Bearer " + result.json()["access_token"]}).status_code == 403
    after = _snapshot(user_id)
    assert after["User"]["role"] == UserRole.passenger
    assert after["DriverProfile"] == before["DriverProfile"]
    assert len(after["RefreshToken"]) == len(before["RefreshToken"]) + 1


def test_review_role_promotion_race_rechecks_locked_user_before_issuing_token(client, monkeypatch):
    user_id, phone, _ = _account(UserRole.passenger)
    monkeypatch.setattr(settings, "review_phone", phone)
    monkeypatch.setattr(settings, "review_code", REVIEW_CODE)
    looked_up, promoted = threading.Event(), threading.Event()
    original_find = auth.find_user_by_phone

    def find_then_allow_concurrent_promotion(session, searched_phone, **kwargs):
        user = original_find(session, searched_phone, **kwargs)
        if searched_phone == phone:
            looked_up.set()
            assert promoted.wait(timeout=10)
        return user

    monkeypatch.setattr(auth, "find_user_by_phone", find_then_allow_concurrent_promotion)
    emitted = []
    monkeypatch.setattr("app.services.push_notification", lambda *a, **kw: emitted.append("push"))
    monkeypatch.setattr("app.services.send_text", lambda *a, **kw: emitted.append("sms"))

    def post_review_login():
        requester = TestClient(app, raise_server_exceptions=False)
        try:
            return requester.post("/auth/verify", json={"phone": phone, "code": REVIEW_CODE},
                                  headers={"X-Device-Id": "qa-store-review-device"})
        finally:
            requester.close()

    with ThreadPoolExecutor(max_workers=1) as workers:
        pending = workers.submit(post_review_login)
        assert looked_up.wait(timeout=10)
        with Session(engine) as session:
            user = session.get(User, user_id)
            user.role = UserRole.admin
            session.add(user)
            session.commit()
        expected = _snapshot(user_id)  # Only the other actor's role change is permitted.
        promoted.set()
        response = pending.result(timeout=20)
    assert response.status_code == 400, response.text
    assert _snapshot(user_id) == expected
    assert emitted == []
