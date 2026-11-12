"""Targeted logout keeps other logins usable and leaves legacy auth compatible."""

from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta
import json
from threading import Event
from time import monotonic, sleep
from types import SimpleNamespace

import pytest
from fastapi import HTTPException
from fastapi.security import HTTPAuthorizationCredentials
from jose import JWTError
from sqlalchemy import text
from sqlmodel import Session, select

from app import cleanup, security, services
from app.db import engine
from app.models import AuthSession, RefreshToken, User, WebPushSubscription
from app.routers import auth
from app.timeutil import utcnow


def _bearer(value):
    return {"Authorization": "Bearer " + value}


def _issue(user_id):
    with Session(engine) as session:
        return security.issue_tokens(session, user_id)


def _close(client, pair):
    return client.post("/auth/logout/session", headers=_bearer(pair["logout_token"]))


def test_delayed_logout_closes_only_the_old_login_and_its_push(client, user_factory):
    user = user_factory("ScopedLogout")
    old = _issue(user["id"])
    rotated = client.post("/auth/refresh", json={
        "refresh_token": old["refresh_token"], "rotation_id": "a" * 64,
    }).json()
    new = _issue(user["id"])
    assert old["session_id"] == rotated["session_id"] != new["session_id"]
    assert old["logout_token"] == rotated["logout_token"]

    with Session(engine) as session:
        for pair, label in [(old, "old"), (new, "new")]:
            session.add(WebPushSubscription(user_id=user["id"], auth_session_id=pair["session_id"],
                                            endpoint=f"https://push.invalid/scoped-logout-{user['id']}-{label}"))
        before = session.get(User, user["id"]).tokens_valid_from
        session.commit()

    assert _close(client, old).json() == {"ok": True}
    assert _close(client, old).json() == {"ok": True}
    assert client.get("/me", headers=_bearer(old["access_token"])).status_code == 401
    assert client.get("/me", headers=_bearer(rotated["access_token"])).status_code == 401
    assert client.get("/me", headers=_bearer(new["access_token"])).status_code == 200
    assert client.post("/auth/refresh", json={"refresh_token": old["refresh_token"],
                                             "rotation_id": "a" * 64}).status_code == 401
    assert client.post("/auth/refresh", json={"refresh_token": rotated["refresh_token"]}).status_code == 401
    assert client.post("/auth/refresh", json={"refresh_token": new["refresh_token"]}).status_code == 200

    with Session(engine) as session:
        assert session.get(User, user["id"]).tokens_valid_from == before
        assert session.get(AuthSession, old["session_id"]).revoked_at is not None
        assert session.get(AuthSession, new["session_id"]).revoked_at is None
        assert session.exec(select(WebPushSubscription.auth_session_id).where(
            WebPushSubscription.user_id == user["id"],
        )).all() == [new["session_id"]]
        creds = HTTPAuthorizationCredentials(scheme="Bearer", credentials=rotated["access_token"])
        assert security.current_user_optional(creds, session) is None
        with pytest.raises(JWTError):
            security.authenticate_ws(rotated["access_token"], session)
        assert security.authenticate_ws(new["access_token"], session).id == user["id"]


def test_logout_ticket_is_not_an_access_or_refresh_credential(client, user_factory):
    user = user_factory("ScopedTicket")
    pair = _issue(user["id"])
    ticket = pair["logout_token"]
    invalid = [pair["access_token"], pair["refresh_token"], pair["session_id"] + "." + "0" * 64]
    for value in invalid:
        assert client.post("/auth/logout/session", headers=_bearer(value)).status_code == 401
    assert client.get("/me", headers=_bearer(ticket)).status_code == 401
    assert client.post("/auth/refresh", json={"refresh_token": ticket}).status_code == 401
    assert client.get("/me", headers=_bearer(pair["access_token"])).status_code == 200


def test_old_logout_ticket_survives_jwt_key_rotation(client, user_factory, monkeypatch):
    user = user_factory("ScopedSigningRotation")
    first = _issue(user["id"])
    monkeypatch.setattr(security.settings, "jwt_secret", "fixture-signing-key-for-rotation-123456789")
    second = client.post("/auth/refresh", json={"refresh_token": first["refresh_token"]}).json()
    assert second["session_id"] == first["session_id"]
    assert second["logout_token"] != first["logout_token"]
    assert _close(client, first).status_code == 200
    assert client.get("/me", headers=_bearer(second["access_token"])).status_code == 401
    with Session(engine) as session:
        hashes = session.get(AuthSession, first["session_id"]).logout_hashes
        assert len(hashes) == 2 and all(len(value) == 64 for value in hashes)


def test_valid_legacy_refresh_replay_binds_both_rows_without_extending_expiry(client, user_factory):
    user = user_factory("ScopedLegacyReplay")
    raw, nonce = "fixture-legacy-parent", "a" * 64
    successor = security._recovery_token(raw, nonce)
    now = security.utcnow()
    expires = now + timedelta(days=1)
    with Session(engine) as session:
        session.add(RefreshToken(user_id=user["id"], token_hash=security._hash_refresh(raw),
                                 expires_at=expires, revoked=True, rotated_at=now,
                                 rotation_id_hash=security._hash_refresh(nonce)))
        session.add(RefreshToken(user_id=user["id"], token_hash=security._hash_refresh(successor),
                                 expires_at=expires))
        session.commit()
    legacy_access = security.make_token(user["id"])
    assert client.get("/me", headers=_bearer(legacy_access)).status_code == 200
    response = client.post("/auth/refresh", json={"refresh_token": raw, "rotation_id": nonce})
    assert response.status_code == 200
    pair = response.json()
    assert pair["refresh_token"] == successor
    with Session(engine) as session:
        rows = session.exec(select(RefreshToken).where(RefreshToken.user_id == user["id"])).all()
        assert len(rows) == 2
        assert {row.auth_session_id for row in rows} == {pair["session_id"]}
        assert {row.expires_at for row in rows} == {expires}
        assert next(row for row in rows if row.revoked).rotated_at == now
    assert _close(client, pair).status_code == 200
    assert client.get("/me", headers=_bearer(pair["access_token"])).status_code == 401


def test_legacy_global_logout_still_revokes_every_login(client, user_factory):
    user = user_factory("ScopedLegacyGlobal")
    first, second = _issue(user["id"]), _issue(user["id"])
    assert client.post("/auth/logout", headers=_bearer(first["access_token"])).json() == {"ok": True}
    for pair in (first, second):
        assert client.get("/me", headers=_bearer(pair["access_token"])).status_code == 401
        assert client.post("/auth/refresh", json={"refresh_token": pair["refresh_token"]}).status_code == 401
        assert _close(client, pair).status_code == 200


@pytest.mark.skipif(engine.dialect.name != "postgresql", reason="requires isolated PostgreSQL")
@pytest.mark.parametrize("first", ["close", "rotate"])
def test_close_and_rotate_share_the_user_lock(client, user_factory, monkeypatch, first):
    user = user_factory("ScopedPgLock")
    original_pair, separate_pair = _issue(user["id"]), _issue(user["id"])
    acquired, competing = Event(), Event()
    original_lock = security.lock_refresh_user

    def ordered_lock(session, owner_id):
        if session.info.get("first_lock_completed"):
            return original_lock(session, owner_id)
        if session.info["operation"] == first:
            locked = original_lock(session, owner_id)
            session.info["first_lock_completed"] = True
            acquired.set()
            assert competing.wait(5)
            return locked
        assert acquired.wait(5)
        competing.set()
        session.info["first_lock_completed"] = True
        return original_lock(session, owner_id)

    def invoke(operation):
        with Session(engine) as session:
            session.info["operation"] = operation
            session.execute(text("SET lock_timeout = '5s'"))
            pid = session.execute(text("SELECT pg_backend_pid()")).scalar_one()
            if operation == "close":
                return pid, security.revoke_auth_session(session, original_pair["logout_token"])
            try:
                return pid, security.rotate_refresh(session, original_pair["refresh_token"], "b" * 64)
            except HTTPException as error:
                return pid, error.status_code

    with monkeypatch.context() as patch:
        patch.setattr(security, "lock_refresh_user", ordered_lock)
        with ThreadPoolExecutor(max_workers=2) as executor:
            closed = executor.submit(invoke, "close")
            rotated = executor.submit(invoke, "rotate")
            close_pid, closed_result = closed.result(timeout=15)
            rotate_pid, rotate_result = rotated.result(timeout=15)
    assert close_pid != rotate_pid
    assert closed_result is True
    if first == "close":
        assert rotate_result == 401
    else:
        assert rotate_result["session_id"] == original_pair["session_id"]
        assert client.get("/me", headers=_bearer(rotate_result["access_token"])).status_code == 401
        assert client.post("/auth/refresh", json={"refresh_token": rotate_result["refresh_token"]}).status_code == 401
    assert client.get("/me", headers=_bearer(separate_pair["access_token"])).status_code == 200

def _subscribe(client, pair, endpoint, expected=None, *, scoped=True):
    return client.post("/push/web/subscribe", headers=_bearer(pair["access_token"]), json={
        "endpoint": endpoint, "keys": {"auth": "fixture-auth", "p256dh": "fixture-p256dh"},
        "session_bound": scoped, "expected_binding_id": expected,
    })


def test_late_push_actions_cannot_overwrite_or_remove_rebound_subscription(client, user_factory):
    a, b = user_factory("PushBindingA"), user_factory("PushBindingB")
    first, second = _issue(a["id"]), _issue(b["id"])
    endpoint = f"https://push.invalid/binding-{a['id']}"
    original = _subscribe(client, first, endpoint).json()["binding_id"]
    assert _subscribe(client, second, endpoint).status_code == 409
    rebound = _subscribe(client, second, endpoint, original).json()["binding_id"]
    assert original != rebound
    assert _subscribe(client, first, endpoint, original).status_code == 409
    assert _subscribe(client, first, endpoint, scoped=False).status_code == 409
    assert client.post("/push/web/unsubscribe", headers=_bearer(first["access_token"]), json={
        "endpoint": endpoint, "session_bound": True, "expected_binding_id": original,
    }).status_code == 200
    assert _close(client, first).status_code == 200

    with Session(engine) as session:
        row = session.exec(select(WebPushSubscription).where(WebPushSubscription.endpoint == endpoint)).one()
        assert (row.user_id, row.auth_session_id, row.binding_id) == (b["id"], second["session_id"], rebound)
    assert client.post("/push/web/unsubscribe", headers=_bearer(second["access_token"]), json={
        "endpoint": endpoint, "session_bound": True, "expected_binding_id": rebound,
    }).status_code == 200
    assert _subscribe(client, second, endpoint, rebound).status_code == 409


def test_legacy_worker_receives_no_details_or_deep_link(client, user_factory, monkeypatch):
    import pywebpush

    user = user_factory("LegacyPush")
    pair = _issue(user["id"])
    endpoint = f"https://push.invalid/legacy-{user['id']}"
    result = client.post("/push/web/subscribe", headers=_bearer(pair["access_token"]), json={
        "endpoint": endpoint, "keys": {"p256dh": "fixture-key", "auth": "fixture-secret"},
    })
    assert result.status_code == 200
    deliveries = []
    monkeypatch.setattr(services.settings, "vapid_public_key", "fixture-public")
    monkeypatch.setattr(services.settings, "vapid_private_key", "fixture-private")
    monkeypatch.setattr(pywebpush, "webpush", lambda **kwargs: deliveries.append(json.loads(kwargs["data"])))

    with Session(engine) as session:
        services._send_web_push(session, user["id"], "Private title", "Private body", {"type": "booking", "id": "99"})
    assert deliveries == [{"title": "Юлдаш", "tag": "yuldash", "url": "/"}]


def test_late_push_expiration_keeps_new_binding(client, user_factory, monkeypatch):
    import pywebpush

    user = user_factory("PushBindingLate410")
    first, second = _issue(user["id"]), _issue(user["id"])
    endpoint = f"https://push.invalid/late-410-{user['id']}"
    original = _subscribe(client, first, endpoint).json()["binding_id"]
    deliveries, rebound = [], []
    monkeypatch.setattr(services.settings, "vapid_public_key", "fixture-public")
    monkeypatch.setattr(services.settings, "vapid_private_key", "fixture-private")

    class Expired(Exception):
        response = SimpleNamespace(status_code=410)

    def send(**kwargs):
        deliveries.append(json.loads(kwargs["data"]))
        rebound.append(_subscribe(client, second, endpoint, original).json()["binding_id"])
        raise Expired()

    monkeypatch.setattr(pywebpush, "webpush", send)
    monkeypatch.setattr(pywebpush, "WebPushException", Expired)
    with Session(engine) as session:
        services._send_web_push(session, user["id"], "Fixture title", "Fixture body")

    assert len(deliveries) == 1
    assert deliveries[0]["session_id"] == first["session_id"]
    assert deliveries[0]["binding_id"] == original
    with Session(engine) as session:
        row = session.exec(select(WebPushSubscription).where(WebPushSubscription.endpoint == endpoint)).one()
        assert row.auth_session_id == second["session_id"]
        assert row.binding_id == rebound[0]

def test_expired_login_removes_only_its_own_browser_subscription(user_factory, monkeypatch):
    import pywebpush

    user = user_factory("RetiredLogin")
    now = utcnow()
    expired = now - timedelta(days=cleanup.TOKEN_DAYS + 1)
    sessions = {"dead": "d" * 64, "refresh": "e" * 64, "access": "f" * 64}
    endpoints = {kind: f"https://push.invalid/retention-{user['id']}-{kind}"
                 for kind in (*sessions, "legacy")}
    with Session(engine) as session:
        for kind, sid in sessions.items():
            session.add(AuthSession(id=sid, user_id=user["id"],
                                    access_expires_at=now + timedelta(minutes=10) if kind == "access" else expired))
        session.flush()
        session.add(RefreshToken(user_id=user["id"], token_hash="retention-fixture-live-refresh",
                                 auth_session_id=sessions["refresh"], expires_at=now + timedelta(days=1)))
        for kind, endpoint in endpoints.items():
            session.add(WebPushSubscription(user_id=user["id"], endpoint=endpoint, auth_session_id=sessions.get(kind),
                                            binding_id="e" * 32))
        session.commit()

    deliveries = []
    monkeypatch.setattr(services.settings, "vapid_public_key", "fixture-public")
    monkeypatch.setattr(services.settings, "vapid_private_key", "fixture-private")
    monkeypatch.setattr(pywebpush, "webpush", lambda **args: deliveries.append(args["subscription_info"]["endpoint"]))
    with Session(engine) as session:
        services._send_web_push(session, user["id"], "Fixture title", "Fixture body")
    expected = {endpoints[kind] for kind in ("refresh", "access", "legacy")}
    assert set(deliveries) == expected

    for _, table, where, params in cleanup._rules(now):
        if table in {"webpushsubscription", "authsession"}:
            cleanup._delete_batched(table, where, params)
    with Session(engine) as session:
        assert session.get(AuthSession, sessions["dead"]) is None
        assert session.get(AuthSession, sessions["access"]) is not None
        assert session.get(AuthSession, sessions["refresh"]) is not None
        assert set(session.exec(select(WebPushSubscription.endpoint).where(
            WebPushSubscription.user_id == user["id"],
        )).all()) == expected


@pytest.mark.skipif(engine.dialect.name != "postgresql", reason="requires isolated PostgreSQL")
def test_cleanup_waiting_for_browser_rebind_keeps_new_subscription(user_factory):
    user = user_factory("CleanupDuringPushRebind")
    now = utcnow()
    old_sid, new_sid = "7" * 64, "8" * 64
    with Session(engine) as session:
        session.add(AuthSession(id=old_sid, user_id=user["id"],
                                access_expires_at=now - timedelta(days=cleanup.TOKEN_DAYS + 1)))
        session.add(AuthSession(id=new_sid, user_id=user["id"], access_expires_at=now + timedelta(minutes=10)))
        session.flush()
        row = WebPushSubscription(user_id=user["id"], endpoint="https://push.invalid/retention-rebind",
                                  auth_session_id=old_sid, binding_id="7" * 32)
        session.add(row)
        session.flush()
        row_id = row.id
        session.commit()

    _, table, where, params = next(rule for rule in cleanup._rules(now) if rule[1] == "webpushsubscription")
    with ThreadPoolExecutor(max_workers=1) as worker, Session(engine) as rebind:
        locker = rebind.exec(text("SELECT pg_backend_pid()")).scalar_one()
        row = rebind.get(WebPushSubscription, row_id)
        row.auth_session_id, row.binding_id = new_sid, "8" * 32
        rebind.add(row)
        rebind.flush()
        deletion = worker.submit(cleanup._delete_batched, table, where, params)
        try:
            with Session(engine) as monitor:
                deadline = monotonic() + 5
                while monotonic() < deadline:
                    waiting = monitor.execute(text(
                        "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE :locker = ANY(pg_blocking_pids(pid)))"
                    ), {"locker": locker}).scalar_one()
                    if waiting:
                        break
                    sleep(0.02)
                assert waiting
        finally:
            rebind.commit()
        deletion.result(timeout=5)
    with Session(engine) as session:
        row = session.get(WebPushSubscription, row_id)
        assert row.auth_session_id == new_sid
        assert row.binding_id == "8" * 32


@pytest.mark.skipif(engine.dialect.name != "postgresql", reason="requires isolated PostgreSQL")
def test_waiting_global_logout_rechecks_closed_origin_session(client, user_factory, monkeypatch):
    user = user_factory("ScopedBeforeGlobal")
    old, new = _issue(user["id"]), _issue(user["id"])
    acquired, waiting = Event(), Event()
    original_lock = security.lock_refresh_user

    def ordered_lock(session, owner_id):
        if session.info["kind"] == "scoped":
            result = original_lock(session, owner_id)
            acquired.set()
            assert waiting.wait(5)
            return result
        assert acquired.wait(5)
        waiting.set()
        return original_lock(session, owner_id)

    def execute(kind):
        with Session(engine) as session:
            session.info["kind"] = kind
            session.execute(text("SET lock_timeout = '5s'"))
            if kind == "scoped":
                assert security.revoke_auth_session(session, old["logout_token"])
            else:
                cred = HTTPAuthorizationCredentials(scheme="Bearer", credentials=old["access_token"])
                with pytest.raises(HTTPException) as rejected:
                    auth.logout(cred=cred, session=session)
                assert rejected.value.status_code == 401

    with monkeypatch.context() as patch:
        patch.setattr(security, "lock_refresh_user", ordered_lock)
        with ThreadPoolExecutor(max_workers=2) as executor:
            scoped = executor.submit(execute, "scoped")
            global_logout = executor.submit(execute, "global")
            scoped.result(timeout=15)
            global_logout.result(timeout=15)
    assert client.get("/me", headers=_bearer(new["access_token"])).status_code == 200
