"""QA-B01-016: store credentials cannot acquire rights from a later role change."""
from datetime import timedelta
import uuid

import pytest
from fastapi.security import HTTPAuthorizationCredentials
from jose import JWTError
from sqlmodel import Session, select

from app.config import settings
from app.db import engine
from app.models import RefreshToken, TgAuth, User, UserRole
from app.security import authenticate_ws, current_user_optional
from app.timeutil import utcnow


NONCE = "c3" * 32


def _store_session(client, monkeypatch):
    ident = uuid.uuid4().hex
    phone = "+7998" + str(int(ident[:8], 16) % 10_000_000).zfill(7)
    with Session(engine) as session:
        user = User(phone=phone, name="QA scoped store", telegram_id="qa-scope-" + ident)
        session.add(user)
        session.commit()
        session.refresh(user)
        user_id = user.id
    monkeypatch.setattr(settings, "review_phone", phone)
    monkeypatch.setattr(settings, "review_code", "424242")
    monkeypatch.setattr(settings, "admin_phones", "")
    monkeypatch.setattr(settings, "admin_telegram_chat_id", "")
    result = client.post("/auth/verify", json={"phone": phone, "code": "424242"})
    assert result.status_code == 200, result.text
    pair = result.json()
    assert pair["user"]["id"] == user_id
    assert pair["user"]["role"] == "passenger"
    assert client.get("/me", headers=_headers(pair)).status_code == 200
    return user_id, phone, pair


def _headers(pair):
    return {"Authorization": "Bearer " + pair["access_token"]}


def _snapshot(user_id):
    with Session(engine) as session:
        return {
            "user": session.get(User, user_id).model_dump(),
            "refresh": [row.model_dump() for row in session.exec(select(RefreshToken).where(
                RefreshToken.user_id == user_id).order_by(RefreshToken.id)).all()],
        }


def _verified_admin_login(client, monkeypatch, user_id, phone):
    monkeypatch.setattr(settings, "admin_phones", phone)
    request_id = uuid.uuid4().hex
    with Session(engine) as session:
        user = session.get(User, user_id)
        session.add(TgAuth(request_id=request_id, telegram_id=user.telegram_id,
                           status="sent", code="246810",
                           expires_at=utcnow() + timedelta(minutes=5)))
        session.commit()
    result = client.post("/auth/tg/verify", json={"request_id": request_id, "code": "246810"})
    assert result.status_code == 200, result.text
    assert result.json()["user"]["role"] == "admin"
    assert result.json()["user"]["id"] == user_id
    assert client.get("/admin/bans", headers=_headers(result.json())).status_code == 200
    return result.json()


@pytest.mark.parametrize("entry", ["access", "refresh", "recovered_refresh", "rotated_access", "rotated_refresh",
                                  "rotated_access_no_nonce", "rotated_refresh_no_nonce"])
def test_store_session_cannot_gain_admin_after_verified_telegram_login(client, monkeypatch, entry):
    user_id, phone, store = _store_session(client, monkeypatch)
    tested = store
    if entry == "recovered_refresh" or entry.startswith("rotated_"):
        rotation = {"refresh_token": store["refresh_token"]}
        if not entry.endswith("_no_nonce"):
            rotation["rotation_id"] = NONCE
        first = client.post("/auth/refresh", json=rotation)
        assert first.status_code == 200
        if entry != "recovered_refresh":
            tested = first.json()
    normal = _verified_admin_login(client, monkeypatch, user_id, phone)
    before = _snapshot(user_id)
    if entry == "access" or entry.startswith("rotated_access"):
        result = client.get("/admin/bans", headers=_headers(tested))
    else:
        body = {"refresh_token": tested["refresh_token"]}
        if entry == "recovered_refresh":
            body["rotation_id"] = NONCE
        result = client.post("/auth/refresh", json=body)
    assert result.status_code == 401, (
        f"store {entry} became privileged after normal login: HTTP {result.status_code}"
    )
    assert _snapshot(user_id) == before, "denied store token changed account/refresh records"
    assert client.get("/me", headers=_headers(normal)).json()["role"] == "admin"
    assert client.get("/admin/bans", headers=_headers(normal)).status_code == 200
    rotated = client.post("/auth/refresh", json={"refresh_token": normal["refresh_token"]})
    assert rotated.status_code == 200
    assert client.get("/admin/bans", headers=_headers(rotated.json())).status_code == 200


@pytest.mark.parametrize("role", [UserRole.driver, UserRole.admin])
@pytest.mark.parametrize("entry", ["optional", "websocket"])
def test_store_access_scope_is_checked_by_shared_authorization_gates(client, monkeypatch, role, entry):
    user_id, _, store = _store_session(client, monkeypatch)
    with Session(engine) as session:
        user = session.get(User, user_id)
        user.role = role  # A second actor changes the live role, without issuing another token.
        session.add(user)
        session.commit()
    before = _snapshot(user_id)
    with Session(engine) as session:
        if entry == "optional":
            cred = HTTPAuthorizationCredentials(scheme="Bearer", credentials=store["access_token"])
            assert current_user_optional(cred, session) is None, "optional gate returned privileged live User"
        else:
            with pytest.raises(JWTError):
                authenticate_ws(store["access_token"], session)
    assert _snapshot(user_id) == before


def test_passenger_store_rotation_and_same_attempt_recovery_remain_usable(client, monkeypatch):
    user_id, _, store = _store_session(client, monkeypatch)
    body = {"refresh_token": store["refresh_token"], "rotation_id": NONCE}
    first = client.post("/auth/refresh", json=body)
    assert first.status_code == 200
    before = _snapshot(user_id)
    retry = client.post("/auth/refresh", json=body)
    assert retry.status_code == 200
    assert retry.json()["refresh_token"] == first.json()["refresh_token"]
    assert _snapshot(user_id) == before, "same-attempt recovery must not add rows or extend expiry"
    for pair in (first.json(), retry.json()):
        assert client.get("/me", headers=_headers(pair)).json()["role"] == "passenger"
        assert client.get("/admin/bans", headers=_headers(pair)).status_code == 403
        with Session(engine) as session:
            assert authenticate_ws(pair["access_token"], session).id == user_id
    child = client.post("/auth/refresh", json={"refresh_token": first.json()["refresh_token"]})
    assert child.status_code == 200
    assert client.get("/me", headers=_headers(child.json())).json()["role"] == "passenger"


def test_changing_refresh_scope_marker_cannot_change_authorization_or_storage(client, monkeypatch):
    user_id, phone, store = _store_session(client, monkeypatch)
    request_id = uuid.uuid4().hex
    with Session(engine) as session:
        user = session.get(User, user_id)
        session.add(TgAuth(request_id=request_id, telegram_id=user.telegram_id,
                           status="sent", code="246810",
                           expires_at=utcnow() + timedelta(minutes=5)))
        session.commit()
    verified = client.post("/auth/tg/verify", json={"request_id": request_id, "code": "246810"})
    assert verified.status_code == 200
    assert verified.json()["user"]["role"] == "passenger"
    review_raw, normal_raw = store["refresh_token"], verified.json()["refresh_token"]
    assert review_raw.startswith("review.")
    before = _snapshot(user_id)
    for forged in (review_raw.removeprefix("review."), "review." + normal_raw):
        rejected = client.post("/auth/refresh", json={"refresh_token": forged, "rotation_id": NONCE})
        assert rejected.status_code == 401, "changing an opaque token must not find the original record"
        assert _snapshot(user_id) == before, "a forged marker caused a write"
    # Both original tokens still work for the same passenger; neither test is hidden by a role mismatch.
    for raw in (review_raw, normal_raw):
        result = client.post("/auth/refresh", json={"refresh_token": raw, "rotation_id": NONCE})
        assert result.status_code == 200
        assert client.get("/me", headers=_headers(result.json())).json()["role"] == "passenger"
