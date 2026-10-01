"""QA-B01-017: phone-wide attempt/issuance budgets survive concurrent requests."""
from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta
import threading
import uuid

import pytest
from fastapi.testclient import TestClient
from sqlmodel import Session, select

from app.db import engine
from app.main import app
from app.models import OtpCode, RefreshToken, User
from app.routers import auth
from app.timeutil import utcnow


CODE, WRONG = "246810", "135790"


def _phone_variant(phone, index):
    return (phone, "8" + phone[2:], phone[2:], "+7 " + phone[2:])[index % 4]


def _phone():
    return "+7997" + str(int(uuid.uuid4().hex[:8], 16) % 10_000_000).zfill(7)


def _budget(old_attempts):
    phone = _phone()
    with Session(engine) as session:
        user = User(phone=phone, name="QA phone-wide budget")
        session.add(user)
        session.commit()
        session.refresh(user)
        user_id = user.id
        before_user = user.model_dump()
        attempts_history = [*old_attempts, 0]
        ages = [180, 130, 70, 1][-len(attempts_history):]
        now = utcnow()
        for attempts, age in zip(attempts_history, ages, strict=True):
            created_at = now - timedelta(seconds=age)
            session.add(OtpCode(phone=phone, code=CODE, attempts=attempts,
                                created_at=created_at,
                                expires_at=created_at + timedelta(minutes=5)))
        session.commit()
    return phone, user_id, before_user


def _rows(phone):
    with Session(engine) as session:
        return [row.model_dump() for row in session.exec(select(OtpCode).where(
            OtpCode.phone == phone).order_by(OtpCode.id)).all()]


def _no_login_effects(user_id, before):
    with Session(engine) as session:
        assert session.get(User, user_id).model_dump() == before
        assert not session.exec(select(RefreshToken).where(RefreshToken.user_id == user_id)).all()


def _post(path, body):
    request = TestClient(app, raise_server_exceptions=False)
    try:
        return request.post(path, json=body)
    finally:
        request.close()


@pytest.mark.parametrize("code", [WRONG, CODE], ids=["wrong", "correct"])
def test_new_code_does_not_reset_exhausted_phone_budget(client, code):
    phone, user_id, before_user = _budget([5, 5, 5])
    before = _rows(phone)
    result = client.post("/auth/verify", json={"phone": phone, "code": code})
    assert result.status_code == 429
    assert _rows(phone) == before, "denied verification changed OTP rows"
    _no_login_effects(user_id, before_user)


@pytest.mark.parametrize("requests", [2, 5])
def test_parallel_wrong_codes_cannot_cross_phone_wide_budget(client, monkeypatch, requests):
    phone, user_id, before_user = _budget([5, 5, 4])
    ready = threading.Barrier(requests)
    original = auth._lock_otp_phone

    def lock_after_all_requests_arrive(session, normalized_phone):
        assert normalized_phone == phone
        ready.wait(timeout=10)
        return original(session, normalized_phone)

    # Both callers cannot read the budget at once after real transaction locking.
    # Synchronize before that boundary; the spy executes the real database lock.
    monkeypatch.setattr(auth, "_lock_otp_phone", lock_after_all_requests_arrive)
    with ThreadPoolExecutor(max_workers=requests) as pool:
        results = list(pool.map(lambda i: _post("/auth/verify", {
            "phone": _phone_variant(phone, i), "code": WRONG}), range(requests)))
    statuses = [r.status_code for r in results]
    rows = _rows(phone)
    total = sum(row["attempts"] for row in rows)
    assert total == 15, f"phone-wide budget was exceeded: attempts={total}, HTTP={statuses}"
    assert statuses.count(400) == 1 and statuses.count(429) == requests - 1
    assert rows[-1]["attempts"] == 1
    _no_login_effects(user_id, before_user)
    with monkeypatch.context() as patch:
        patch.setattr(auth, "_lock_otp_phone", original)
        assert client.post("/auth/verify", json={"phone": phone, "code": CODE}).status_code == 429


@pytest.mark.parametrize("requests", [4, 8])
def test_parallel_code_issuance_sends_at_most_three_sms_per_minute(client, monkeypatch, requests):
    phone = _phone()
    ready = threading.Barrier(requests)
    original = auth._lock_otp_phone
    sent = []

    def lock_after_all_requests_arrive(session, normalized_phone):
        assert normalized_phone == phone
        ready.wait(timeout=10)
        return original(session, normalized_phone)

    monkeypatch.setattr(auth, "_lock_otp_phone", lock_after_all_requests_arrive)
    monkeypatch.setattr(auth, "send_sms", lambda *a, **kw: sent.append("synthetic SMS"))
    with ThreadPoolExecutor(max_workers=requests) as pool:
        results = list(pool.map(lambda i: _post("/auth/request-code", {
            "phone": _phone_variant(phone, i)}), range(requests)))
    statuses = [r.status_code for r in results]
    rows = _rows(phone)
    assert len(rows) == 3, f"phone throttle was exceeded: rows={len(rows)}, HTTP={statuses}, SMS={len(sent)}"
    assert len(sent) == 3
    assert statuses.count(200) == 3 and statuses.count(429) == requests - 3
    with Session(engine) as session:
        assert session.exec(select(User).where(User.phone == phone)).first() is None


@pytest.mark.parametrize("path", ["/auth/request-code", "/auth/verify"])
def test_database_lock_failure_has_no_otp_sms_or_login_side_effects(client, monkeypatch, path):
    phone, user_id, before_user = _budget([])
    before_rows = _rows(phone)
    real_execute = Session.execute
    sent, failed = [], []

    def fail_only_real_phone_lock(session, statement, *args, **kwargs):
        sql = str(statement)
        is_pg_lock = "pg_advisory_xact_lock" in sql
        is_sqlite_lock = sql.startswith("UPDATE otpcode SET attempts=otpcode.attempts")
        if is_pg_lock or is_sqlite_lock:
            failed.append("synthetic DB lock failure")
            raise RuntimeError("synthetic DB lock failure")
        return real_execute(session, statement, *args, **kwargs)

    monkeypatch.setattr(Session, "execute", fail_only_real_phone_lock)
    monkeypatch.setattr(auth, "send_sms", lambda *a, **kw: sent.append("synthetic SMS"))
    result = _post(path, {"phone": phone, "code": CODE})
    assert result.status_code == 500
    assert len(failed) == 1, "the fault must reach the real SQL locking operation"
    assert _rows(phone) == before_rows
    assert sent == []
    _no_login_effects(user_id, before_user)
    with monkeypatch.context() as patch:
        patch.setattr(Session, "execute", real_execute)
        assert client.post(path, json={"phone": phone, "code": CODE}).status_code == 200
