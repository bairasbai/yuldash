"""Administrator sessions require credentials proved by the Telegram login flow."""

from datetime import timedelta

import pytest
from fastapi.security import HTTPAuthorizationCredentials
from fastapi.testclient import TestClient
from jose import JWTError, jwt
from sqlmodel import Session, select

from app.config import settings
from app.db import engine
from app.main import app
from app.models import AuthSession, OtpCode, RefreshToken, TgAuth, User, UserRole
from app.routers import auth
from app.security import _decode, _hash_refresh, authenticate_ws, current_user_optional, issue_tokens
from app.timeutil import utcnow

pytestmark = pytest.mark.usefixtures("telegram_login_ready")


def _existing_user(phone, telegram_id, role=UserRole.passenger):
    with Session(engine) as session:
        user = User(phone=phone, telegram_id=str(telegram_id), role=role, name="QA")
        session.add(user)
        session.commit()
        return user.id


def _start(client, telegram_id):
    challenge = client.post("/auth/tg/start").json()["request_id"]
    response = client.post("/telegram/webhook", json={"message": {
        "text": "/start " + challenge, "from": {"id": telegram_id},
        "chat": {"id": telegram_id, "type": "private"},
    }})
    assert response.status_code == 200
    with Session(engine) as session:
        code = session.exec(select(TgAuth).where(TgAuth.request_id == challenge)).one().code
    return {"request_id": challenge, "code": code}


def _contact(client, telegram_id, phone, *, contact_owner=None, chat_type="private"):
    return client.post("/telegram/webhook", json={"message": {
        "from": {"id": telegram_id}, "chat": {"id": telegram_id, "type": chat_type},
        "contact": {"user_id": contact_owner if contact_owner is not None else telegram_id,
                    "phone_number": phone},
    }})


def _headers(token):
    return {"Authorization": f"Bearer {token}"}


def test_listed_phone_promotes_only_after_own_contact_from_this_login(client, monkeypatch):
    phone, telegram_id = "+79990101021", 70001021
    user_id = _existing_user(phone, telegram_id)
    monkeypatch.setattr(settings, "admin_telegram_chat_id", "")
    monkeypatch.setattr(settings, "admin_phones", "8 (999) 010-10-21")
    challenge = _start(client, telegram_id)
    assert _contact(client, telegram_id, phone, contact_owner=telegram_id + 1).status_code == 200
    assert _contact(client, telegram_id, phone, chat_type="group").status_code == 200

    passenger = client.post("/auth/tg/verify", json=challenge)
    assert passenger.status_code == 200 and passenger.json()["user"]["role"] == "passenger"
    assert _contact(client, telegram_id, "+79990101028").status_code == 200
    with Session(engine) as session:
        assert session.get(User, user_id).phone == phone
        assert session.exec(select(TgAuth).where(
            TgAuth.request_id == challenge["request_id"],
        )).one().shared_phone is None

    new_challenge = _start(client, telegram_id)
    assert _contact(client, telegram_id, phone).status_code == 200
    promoted = client.post("/auth/tg/verify", json=new_challenge)
    assert promoted.status_code == 200 and promoted.json()["user"]["role"] == "admin"
    assert promoted.json()["user"]["id"] == user_id
    assert client.get("/admin/drivers/pending", headers=_headers(promoted.json()["access_token"])).status_code == 200
    assert client.get("/me", headers=_headers(passenger.json()["access_token"])).status_code == 401

    fresh = _start(client, telegram_id)
    retry = client.post("/auth/tg/verify", json=fresh)
    assert retry.status_code == 403 and retry.json()["detail"]["code"] == "phone_required"
    assert _contact(client, telegram_id, phone).status_code == 200
    verified = client.post("/auth/tg/verify", json=fresh)
    assert verified.status_code == 200 and verified.json()["user"]["role"] == "admin"


def test_contact_committed_between_challenge_read_and_consume_is_used(client, monkeypatch):
    phone, telegram_id = "+79990101022", 70001022
    _existing_user(phone, telegram_id)
    monkeypatch.setattr(settings, "admin_phones", phone)
    challenge = _start(client, telegram_id)
    compare_digest = auth.hmac.compare_digest

    def compare_and_deliver_contact(submitted, expected):
        matches = compare_digest(submitted, expected)
        if submitted == challenge["code"] and matches:
            with TestClient(app, headers=client.headers) as bot:
                response = _contact(bot, telegram_id, phone)
                assert response.status_code == 200 and "remove_keyboard" in response.json()["reply_markup"]
        return matches

    monkeypatch.setattr(auth.hmac, "compare_digest", compare_and_deliver_contact)
    verified = client.post("/auth/tg/verify", json=challenge)
    assert verified.status_code == 200 and verified.json()["user"]["role"] == "admin"


def test_verified_sms_of_existing_admin_consumes_code_without_session(client):
    phone, telegram_id = "+79990101023", 70001023
    user_id = _existing_user(phone, telegram_id, UserRole.admin)
    with Session(engine) as session:
        user = session.get(User, user_id)
        user.last_seen_at = utcnow() - timedelta(days=400)
        user.last_device_id = "old-qa-device"
        session.add(user)
        session.add(OtpCode(phone=phone, code="123456", expires_at=utcnow() + timedelta(minutes=5)))
        session.commit()
    body = {"phone": phone, "code": "123456"}
    headers = {"X-Device-Id": "new-qa-device"}
    assert client.post("/auth/verify", json={**body, "code": "654321"}, headers=headers).status_code == 400
    refused = client.post("/auth/verify", json=body, headers=headers)
    assert refused.status_code == 403 and refused.json()["detail"]["code"] == "telegram_login_required"
    assert client.post("/auth/verify", json=body, headers=headers).status_code == 400
    with Session(engine) as session:
        account = session.get(User, user_id)
        assert (account.phone, account.role, account.last_device_id) == (phone, UserRole.admin, "old-qa-device")
        assert not session.exec(select(RefreshToken).where(RefreshToken.user_id == user_id)).all()


def test_legacy_role_is_rechecked_on_verified_telegram_login(client, monkeypatch):
    phone, telegram_id = "+79990101024", 70001024
    user_id = _existing_user(phone, telegram_id, UserRole.admin)
    monkeypatch.setattr(settings, "admin_telegram_chat_id", str(telegram_id + 1))
    monkeypatch.setattr(settings, "admin_phones", "")
    with Session(engine) as session:
        old = issue_tokens(session, user_id, auth_method="telegram", auth_time=utcnow())
    response = client.post("/auth/tg/verify", json=_start(client, telegram_id))
    assert response.status_code == 200 and response.json()["user"]["role"] == "passenger"
    assert client.get("/me", headers=_headers(old["access_token"])).status_code == 401
    assert client.post("/auth/refresh", json={"refresh_token": old["refresh_token"]}).status_code == 401


@pytest.mark.parametrize("change", [
    {"amr": None}, {"amr": "sms"}, {"sid": None}, {"auth_time": None}, {"auth_time": True},
    {"auth_time": -1}, {"auth_time": 4_102_444_800}, {"review_session": True},
])
def test_weak_admin_access_refused_by_required_optional_and_websocket_auth(client, user_factory, change):
    admin = user_factory(role=UserRole.admin)
    claims = _decode(admin["token"])
    for key, value in change.items():
        if value is None:
            claims.pop(key)
        else:
            claims[key] = value
    token = jwt.encode(claims, settings.jwt_secret, algorithm="HS256")
    assert client.get("/me", headers=_headers(token)).status_code in (401, 403)
    credentials = HTTPAuthorizationCredentials(scheme="Bearer", credentials=token)
    with Session(engine) as session:
        assert current_user_optional(credentials, session) is None
        with pytest.raises(JWTError):
            authenticate_ws(token, session)


@pytest.mark.parametrize("method", ["legacy", "sms"])
def test_weak_refresh_of_admin_does_not_revoke_active_telegram_login(client, method):
    user_id = _existing_user("+79990101025" if method == "legacy" else "+79990101026",
                             "70001025" if method == "legacy" else "70001026", UserRole.admin)
    with Session(engine) as session:
        verified = issue_tokens(session, user_id, auth_method="telegram", auth_time=utcnow())
        old = "synthetic-weak-refresh-" + method
        session.add(RefreshToken(
            user_id=user_id, token_hash=_hash_refresh(old), auth_session_id=verified["session_id"],
            auth_method=method, auth_time=utcnow() if method == "sms" else None,
            expires_at=utcnow() + timedelta(minutes=5), revoked=True,
            rotated_at=utcnow() - timedelta(minutes=10), rotation_id_hash=_hash_refresh("b" * 64),
        ))
        session.commit()
    assert client.post("/auth/refresh", json={"refresh_token": old}).status_code == 401
    with Session(engine) as session:
        assert session.get(AuthSession, verified["session_id"]).revoked_at is None
    assert client.get("/admin/drivers/pending", headers=_headers(verified["access_token"])).status_code == 200
