"""B01: failed issuance must preserve OTP; concurrent verification consumes it once.

Synthetic account/code fixtures enter the real auth routes. External providers are
disabled by the runner; no real SMS or Telegram webhook is used here.
"""
from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta
import threading
import uuid

import pytest
from fastapi.testclient import TestClient
from sqlmodel import Session, select

from app import security
from app.db import engine
from app.main import app
from app.models import Consent, OtpCode, RefreshToken, TgAuth, User
from app.routers import auth
from app.timeutil import utcnow


CODE = "246810"


def _pending_login(kind, *, existing=True):
    """Persist synthetic existing user and one live code, bypassing only delivery."""
    ident = uuid.uuid4().hex
    with Session(engine) as session:
        phone = "+7999" + str(int(ident[:8], 16) % 10_000_000).zfill(7)
        telegram_id = "qa-tg-" + ident if kind == "telegram" else None
        user_id = None
        if existing:
            user = User(phone=phone, name="QA auth atomicity", telegram_id=telegram_id)
            session.add(user)
            session.commit()
            session.refresh(user)
            user_id = user.id
        if kind == "sms":
            row = OtpCode(phone=phone, code=CODE,
                          expires_at=utcnow() + timedelta(minutes=5))
            body = {"phone": phone, "code": CODE}
            path = "/auth/verify"
        else:
            row = TgAuth(request_id=ident, telegram_id=telegram_id,
                         status="sent", code=CODE, shared_phone=phone,
                         expires_at=utcnow() + timedelta(minutes=5))
            body = {"request_id": ident, "code": CODE}
            path = "/auth/tg/verify"
        session.add(row)
        session.commit()
    return user_id, path, body


def _refresh_count(user_id):
    with Session(engine) as session:
        return len(session.exec(select(RefreshToken).where(
            RefreshToken.user_id == user_id)).all())


@pytest.mark.parametrize("kind", ["sms", "telegram"])
@pytest.mark.parametrize("stage", ["sign", "commit"])
def test_issuance_failure_keeps_code_retryable(client, monkeypatch, kind, stage):
    user_id, path, body = _pending_login(kind)
    if kind == "sms":
        with Session(engine) as session:
            before_otp = session.exec(select(OtpCode).where(OtpCode.phone == body["phone"])).one().model_dump()

    def unavailable_signer(*args, **kwargs):
        raise RuntimeError("synthetic issuance failure before token commit")

    original_commit = Session.commit

    def unavailable_issuance_commit(session):
        if any(isinstance(row, RefreshToken) for row in session.new):
            raise RuntimeError("synthetic issuance commit failure")
        return original_commit(session)

    # Non-context client reuses the session fixture's DB; it does not start a
    # second lifespan or dispose the common engine.
    failing_client = TestClient(app, raise_server_exceptions=False)
    try:
        with monkeypatch.context() as patch:
            if stage == "sign":
                patch.setattr(security, "make_token", unavailable_signer)
            else:
                patch.setattr(Session, "commit", unavailable_issuance_commit)
            failed = failing_client.post(path, json=body)
        assert failed.status_code == 500
        assert _refresh_count(user_id) == 0, "failed signature must not commit a token"
        if kind == "sms":
            with Session(engine) as session:
                assert session.exec(select(OtpCode).where(OtpCode.phone == body["phone"])).one().model_dump() == before_otp
        repeated = failing_client.post(path, json=body)
    finally:
        failing_client.close()

    assert repeated.status_code == 200, (
        f"{kind}/{stage}: issuance failed before commit, but the same live code cannot "
        f"recover: HTTP {repeated.status_code}, {repeated.text}"
    )
    assert repeated.json()["user"]["id"] == user_id
    assert _refresh_count(user_id) == 1
    assert client.post(path, json=body).status_code in (400, 409)


@pytest.mark.parametrize("kind", ["sms", "telegram"])
@pytest.mark.parametrize("existing", [True, False], ids=["existing", "registration"])
def test_concurrent_verification_issues_only_one_token_pair(client, monkeypatch, kind, existing):
    user_id, path, body = _pending_login(kind, existing=existing)
    if existing:
        with Session(engine) as session:
            user = session.get(User, user_id)
            user.last_device_id = "qa-race-original"
            session.add(user)
            session.commit()
    sent = []
    monkeypatch.setattr("app.services.push_notification", lambda *a, **kw: sent.append("push"))
    monkeypatch.setattr("app.services.send_text", lambda *a, **kw: sent.append("sms"))
    both_read_live_code = threading.Barrier(2)
    original_compare = auth.hmac.compare_digest

    def compare_after_both_requests_read(left, right):
        # Synchronize exactly the real check/use boundary. No fake database,
        # artificial sleep, or replacement of authorization/issuance is used.
        if left == CODE and right == CODE:
            both_read_live_code.wait(timeout=10)
        return original_compare(left, right)

    def verify_once(device_id):
        requester = TestClient(app, raise_server_exceptions=False)
        try:
            return requester.post(path, json=body, headers={"X-Device-Id": device_id})
        finally:
            requester.close()

    if kind == "sms":
        original_lock = auth._lock_otp_phone

        def lock_after_both_arrive(session, phone):
            both_read_live_code.wait(timeout=10)
            return original_lock(session, phone)

        # Phone locking now excludes two simultaneous SMS budget/code reads.
        # Keep the race at its entrance and still execute the real SQL lock.
        monkeypatch.setattr(auth, "_lock_otp_phone", lock_after_both_arrive)
    else:
        monkeypatch.setattr(auth.hmac, "compare_digest", compare_after_both_requests_read)
    # Early claim now serializes registration itself. The previous secondary
    # name/INSERT barrier would require the correctly rejected caller to reach
    # INSERT; keep the real code check/use barrier common to all four cases.
    with ThreadPoolExecutor(max_workers=2) as workers:
        first = workers.submit(verify_once, "qa-race-device-a")
        second = workers.submit(verify_once, "qa-race-device-b")
        replies = [first.result(timeout=20), second.result(timeout=20)]

    statuses = [reply.status_code for reply in replies]
    if user_id is None:
        winners = [reply.json()["user"]["id"] for reply in replies if reply.status_code == 200]
        assert winners, f"registration produced no successful response: {statuses}"
        user_id = winners[0]
    count = _refresh_count(user_id)
    assert statuses.count(200) == 1, (
        f"{kind}: one live code accepted by {statuses.count(200)} requests; "
        f"HTTP {statuses}; persisted refresh tokens={count}"
    )
    assert sorted(statuses) in ([200, 400], [200, 409])
    assert count == 1, "one successful consumption must create one refresh token"
    winner = next(reply for reply in replies if reply.status_code == 200)
    with Session(engine) as session:
        assert session.get(User, user_id).last_device_id == winner.request.headers["X-Device-Id"]
        assert len(session.exec(select(Consent).where(Consent.user_id == user_id)).all()) == 3
    assert sent == (["push", "sms"] if existing else [])


@pytest.mark.parametrize("kind", ["sms", "telegram"])
def test_failed_login_rolls_back_device_consents_and_safety_sends(client, monkeypatch, kind):
    user_id, path, body = _pending_login(kind)
    with Session(engine) as session:
        user = session.get(User, user_id)
        user.last_device_id = "qa-original-device"
        session.add(user)
        session.commit()
    sent = []
    monkeypatch.setattr("app.services.push_notification", lambda *a, **kw: sent.append("push"))
    monkeypatch.setattr("app.services.send_text", lambda *a, **kw: sent.append("sms"))

    def fail(*args, **kwargs):
        raise RuntimeError("synthetic login signature failure")

    requester = TestClient(app, raise_server_exceptions=False)
    try:
        with monkeypatch.context() as patch:
            patch.setattr(security, "make_token", fail)
            response = requester.post(path, json=body, headers={"X-Device-Id": "qa-new-device"})
        assert response.status_code == 500
        with Session(engine) as session:
            assert session.get(User, user_id).last_device_id == "qa-original-device"
            assert not session.exec(select(Consent).where(Consent.user_id == user_id)).all()
        assert not sent, "failed issuance sent a misleading safety event"
        recovered = requester.post(path, json=body, headers={"X-Device-Id": "qa-new-device"})
        assert recovered.status_code == 200
        with Session(engine) as session:
            assert session.get(User, user_id).last_device_id == "qa-new-device"
            assert len(session.exec(select(Consent).where(Consent.user_id == user_id)).all()) == 3
        assert sent == ["push", "sms"]
    finally:
        requester.close()


@pytest.mark.parametrize("kind", ["sms", "telegram"])
def test_failed_login_rolls_back_phone_release_or_telegram_link(client, monkeypatch, kind):
    from app.antifraud import PHONE_RECYCLE_DAYS
    user_id, path, body = _pending_login(kind)
    with Session(engine) as session:
        user = session.get(User, user_id)
        original_phone = user.phone
        user.last_device_id = "qa-old-phone"
        if kind == "sms":
            user.last_seen_at = utcnow() - timedelta(days=PHONE_RECYCLE_DAYS + 1)
        else:
            user.telegram_id = None  # existing SMS account about to be linked
        session.add(user)
        session.commit()
    sent = []
    monkeypatch.setattr("app.services.push_notification", lambda *a, **kw: sent.append("push"))
    monkeypatch.setattr("app.services.send_text", lambda *a, **kw: sent.append("sms"))

    def fail(*args, **kwargs):
        raise RuntimeError("synthetic login signature failure")

    monkeypatch.setattr(security, "make_token", fail)
    requester = TestClient(app, raise_server_exceptions=False)
    try:
        response = requester.post(path, json=body, headers={"X-Device-Id": "qa-new-phone"})
        assert response.status_code == 500
        with Session(engine) as session:
            owner = session.get(User, user_id)
            assert owner.phone == original_phone, "failed login detached the previous account"
            assert owner.telegram_id is None, "failed login linked a Telegram identity"
            assert owner.last_device_id == "qa-old-phone"
            assert len(session.exec(select(User).where(User.phone == original_phone)).all()) == 1
        assert not sent
    finally:
        requester.close()


def test_telegram_start_cannot_transfer_another_users_shared_phone(client):
    phone = "+7999" + str(int(uuid.uuid4().hex[:8], 16) % 10_000_000).zfill(7)
    first_tid = 90_000_000_000 + int(uuid.uuid4().hex[:12], 16)
    second_tid = first_tid + 1
    with Session(engine) as session:
        victim = User(phone=phone, name="QA phone owner")
        session.add(victim)
        session.commit()
        session.refresh(victim)
        victim_id = victim.id
    request_id = client.post("/auth/tg/start").json()["request_id"]

    def start(tid):
        return client.post("/telegram/webhook", json={"message": {
            "text": "/start " + request_id, "from": {"id": tid, "first_name": "QA"},
            "chat": {"id": tid},
        }})

    assert start(first_tid).status_code == 200
    assert client.post("/telegram/webhook", json={"message": {
        "from": {"id": first_tid}, "chat": {"id": first_tid},
        "contact": {"user_id": first_tid, "phone_number": phone},
    }}).status_code == 200
    second = start(second_tid)
    assert second.status_code == 200
    with Session(engine) as session:
        row = session.exec(select(TgAuth).where(TgAuth.request_id == request_id)).one()
        assert row.telegram_id == str(first_tid), "shared-phone proof transferred to another Telegram"
        assert row.shared_phone == phone
        code = row.code
    verified = client.post("/auth/tg/verify", json={"request_id": request_id, "code": code})
    assert verified.status_code == 200
    assert verified.json()["user"]["id"] == victim_id
    assert verified.json()["user"]["telegram_id"] == str(first_tid)


@pytest.mark.parametrize("kind", ["sms", "telegram"])
def test_post_commit_safety_failure_keeps_issued_session_successful(client, monkeypatch, kind):
    user_id, path, body = _pending_login(kind)
    with Session(engine) as session:
        user = session.get(User, user_id)
        user.last_device_id = "qa-prior-device"
        session.add(user)
        session.commit()
    monkeypatch.setattr("app.services.push_notification", lambda *a, **kw: None)

    def fail(*args, **kwargs):
        raise RuntimeError("synthetic safety channel outage")

    monkeypatch.setattr("app.services.send_text", fail)
    result = client.post(path, json=body, headers={"X-Device-Id": "qa-current-device"})
    assert result.status_code == 200
    assert result.json()["user"]["id"] == user_id
    assert client.get("/me", headers={"Authorization": "Bearer " + result.json()["access_token"]}).status_code == 200
    assert _refresh_count(user_id) == 1
    assert client.post(path, json=body).status_code in (400, 409)


@pytest.mark.parametrize("kind", ["sms", "telegram"])
@pytest.mark.parametrize("failure", ["runtime", "database"])
def test_best_effort_consent_failure_does_not_undo_claim(client, monkeypatch, kind, failure):
    from app import trust_service
    user_id, path, body = _pending_login(kind)
    original = trust_service.record_consent

    def fail_privacy(session, user_id, consent_kind, **kwargs):
        if consent_kind == "privacy":
            if failure == "runtime":
                raise RuntimeError("synthetic consent insert outage")
            # Real foreign-key failure inside the real helper SAVEPOINT. The
            # failed SQL transaction must not roll back the outer OTP claim.
            session.add(Consent(user_id=-1, kind="privacy"))
            session.flush()
        return original(session, user_id, consent_kind, **kwargs)

    monkeypatch.setattr(trust_service, "record_consent", fail_privacy)
    result = client.post(path, json=body)
    assert result.status_code == 200
    assert _refresh_count(user_id) == 1
    assert client.post(path, json=body).status_code in (400, 409)
    with Session(engine) as session:
        assert {r.kind for r in session.exec(select(Consent).where(Consent.user_id == user_id)).all()} == {"offer", "age18"}


def test_phone_required_rolls_back_claim_then_contact_allows_same_code(client):
    telegram_id = "989" + str(int(uuid.uuid4().hex[:8], 16)).zfill(10)
    request_id = client.post("/auth/tg/start").json()["request_id"]
    assert client.post("/telegram/webhook", json={"message": {
        "text": "/start " + request_id, "from": {"id": telegram_id}, "chat": {"id": telegram_id},
    }}).status_code == 200
    with Session(engine) as session:
        row = session.exec(select(TgAuth).where(TgAuth.request_id == request_id)).one()
        code = row.code
    assert client.post("/auth/tg/verify", json={"request_id": request_id, "code": code}).status_code == 403
    with Session(engine) as session:
        assert session.exec(select(TgAuth).where(TgAuth.request_id == request_id)).one().status == "sent"
        assert not session.exec(select(User).where(User.telegram_id == telegram_id)).all()
    phone = "+7999" + str(int(uuid.uuid4().hex[:8], 16) % 10_000_000).zfill(7)
    assert client.post("/telegram/webhook", json={"message": {
        "from": {"id": telegram_id}, "chat": {"id": telegram_id},
        "contact": {"user_id": telegram_id, "phone_number": phone},
    }}).status_code == 200
    result = client.post("/auth/tg/verify", json={"request_id": request_id, "code": code})
    assert result.status_code == 200
    assert result.json()["user"]["phone"] == phone
    assert client.post("/auth/tg/verify", json={"request_id": request_id, "code": code}).status_code == 409


@pytest.mark.parametrize("kind", ["sms", "telegram"])
def test_admin_event_is_written_only_for_committed_role_change(client, monkeypatch, kind):
    from app.config import settings
    user_id, path, body = _pending_login(kind)
    with Session(engine) as session:
        phone = session.get(User, user_id).phone
    monkeypatch.setattr(settings, "admin_phones", phone)
    events = []
    monkeypatch.setattr(auth, "admin_action", lambda uid, action, **kw: events.append((uid, action)))

    def fail(*args, **kwargs):
        raise RuntimeError("synthetic admin signing outage")

    requester = TestClient(app, raise_server_exceptions=False)
    try:
        with monkeypatch.context() as patch:
            patch.setattr(security, "make_token", fail)
            assert requester.post(path, json=body).status_code == 500
        assert events == []
        with Session(engine) as session:
            assert session.get(User, user_id).role.value == "passenger"
        result = requester.post(path, json=body)
        assert result.status_code == 200
        assert result.json()["user"]["role"] == "admin"
        assert events == [(user_id, "role.promote")]
    finally:
        requester.close()


def test_stale_start_cannot_reopen_used_telegram_code(client, monkeypatch):
    user_id, path, body = _pending_login("telegram")
    with Session(engine) as session:
        telegram_id = session.get(User, user_id).telegram_id

    def finish_verification_before_webhook_write():
        # A separate client has its own portal. Calling the fixture's client
        # from its active async webhook loop would be a harness RuntimeError.
        requester = TestClient(app)
        try:
            assert requester.post(path, json=body).status_code == 200
        finally:
            requester.close()
        return "975310"

    # The real webhook has read sent; the real verify commits used before its
    # UPDATE. CAS must reject that stale UPDATE, without reopening the session.
    monkeypatch.setattr(auth, "gen_otp", finish_verification_before_webhook_write)
    response = client.post("/telegram/webhook", json={"message": {
        "text": "/start " + body["request_id"], "from": {"id": telegram_id}, "chat": {"id": telegram_id},
    }})
    assert response.status_code == 200
    with Session(engine) as session:
        row = session.exec(select(TgAuth).where(TgAuth.request_id == body["request_id"])).one()
        assert row.status == "used"
    assert _refresh_count(user_id) == 1
    assert client.post(path, json={**body, "code": "975310"}).status_code == 409
