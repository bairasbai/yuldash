"""B01: concurrent wrong-code accounting and final-state rejection via real routes."""
from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta
import threading
import uuid

import pytest
from fastapi.testclient import TestClient
from sqlmodel import Session, select

from app.db import engine
from app.main import app
from app.models import OtpCode, RefreshToken, TgAuth, User
from app.routers import auth
from app.timeutil import utcnow


CODE = "246810"
WRONG = "135790"


def _login(kind):
    ident = uuid.uuid4().hex
    phone = "+7999" + str(int(ident[:8], 16) % 10_000_000).zfill(7)
    with Session(engine) as session:
        user = User(phone=phone, name="QA attempt budget",
                    telegram_id="qa-attempt-" + ident if kind == "telegram" else None)
        session.add(user)
        session.commit()
        session.refresh(user)
        if kind == "sms":
            row = OtpCode(phone=phone, code=CODE, expires_at=utcnow() + timedelta(minutes=5))
            path, body = "/auth/verify", {"phone": phone, "code": WRONG}
        else:
            row = TgAuth(request_id=ident, telegram_id=user.telegram_id, status="sent",
                         code=CODE, expires_at=utcnow() + timedelta(minutes=5))
            path, body = "/auth/tg/verify", {"request_id": ident, "code": WRONG}
        user_id = user.id
        session.add(row)
        session.commit()
        session.refresh(row)
        return user_id, type(row), row.id, path, body


def _post(path, body):
    requester = TestClient(app, raise_server_exceptions=False)
    try:
        return requester.post(path, json=body)
    finally:
        requester.close()


@pytest.mark.parametrize("kind", ["sms", "telegram"])
@pytest.mark.parametrize("requests", [5, 8])
def test_parallel_wrong_attempts_are_counted_and_budget_is_enforced(client, monkeypatch, kind, requests):
    user_id, model, row_id, path, body = _login(kind)
    all_read_budget = threading.Barrier(requests)
    original = auth.hmac.compare_digest

    def compare_after_every_request_read(left, right):
        if {left, right} == {CODE, WRONG}:
            all_read_budget.wait(timeout=10)
        return original(left, right)

    with monkeypatch.context() as patch:
        if kind == "sms":
            original_lock = auth._lock_otp_phone

            def lock_after_every_request_arrives(session, phone):
                all_read_budget.wait(timeout=10)
                return original_lock(session, phone)

            patch.setattr(auth, "_lock_otp_phone", lock_after_every_request_arrives)
        else:
            patch.setattr(auth.hmac, "compare_digest", compare_after_every_request_read)
        with ThreadPoolExecutor(max_workers=requests) as workers:
            replies = list(workers.map(lambda _: _post(path, body), range(requests)))
    statuses = [reply.status_code for reply in replies]
    with Session(engine) as session:
        attempts = session.get(model, row_id).attempts
        assert attempts == 5, f"{requests} concurrent requests lost increments: attempts={attempts}, HTTP={statuses}"
        assert not session.exec(select(RefreshToken).where(RefreshToken.user_id == user_id)).all()
    assert statuses.count(400) == 5 and statuses.count(429) == requests - 5
    assert client.post(path, json=body).status_code == 429
    assert client.post(path, json={**body, "code": CODE}).status_code == 429
    with Session(engine) as session:
        assert session.get(model, row_id).attempts == 5
        assert not session.exec(select(RefreshToken).where(RefreshToken.user_id == user_id)).all()


@pytest.mark.parametrize("kind", ["sms", "telegram"])
@pytest.mark.parametrize("final", ["used", "expired"])
def test_late_wrong_attempt_cannot_mutate_final_code(client, monkeypatch, kind, final):
    _, model, row_id, path, body = _login(kind)
    compared, changed = threading.Event(), threading.Event()
    original = auth.hmac.compare_digest

    def pause_before_wrong_attempt_write(left, right):
        if {left, right} == {CODE, WRONG}:
            compared.set()
            assert changed.wait(timeout=10)
        return original(left, right)

    sqlite_sms = kind == "sms" and engine.dialect.name == "sqlite"
    natural_expiry = sqlite_sms and final == "expired"
    if sqlite_sms and final == "used":
        original_lock = auth._lock_otp_phone

        def pause_before_phone_lock(session, phone):
            compared.set()
            assert changed.wait(timeout=10)
            return original_lock(session, phone)

        # SQLite's writer lock makes an after-lock DELETE in a second session
        # impossible. This variant deletes before acquisition; PG below retains
        # the original after-read mutation of final state.
        monkeypatch.setattr(auth, "_lock_otp_phone", pause_before_phone_lock)
    else:
        monkeypatch.setattr(auth.hmac, "compare_digest", pause_before_wrong_attempt_write)
    with ThreadPoolExecutor(max_workers=1) as workers:
        pending = workers.submit(_post, path, body)
        assert compared.wait(timeout=10)
        if natural_expiry:
            # Time can pass while the SQLite writer lock is held; another writer
            # cannot update expiry until it is released. No product delay needed.
            expired_now = utcnow() + timedelta(minutes=10)
            monkeypatch.setattr(auth, "utcnow", lambda: expired_now)
        else:
            with Session(engine) as session:
                row = session.get(model, row_id)
                if final == "expired":
                    row.expires_at = utcnow() - timedelta(seconds=1)
                    session.add(row)
                elif kind == "sms":
                    session.delete(row)
                else:
                    row.status = "used"
                    session.add(row)
                session.commit()
        changed.set()
        response = pending.result(timeout=20)
    expected = 400 if kind == "sms" else (410 if final == "expired" else 409)
    assert response.status_code == expected, response.text
    with Session(engine) as session:
        row = session.get(model, row_id)
        if kind == "sms" and final == "used":
            assert row is None
        else:
            assert row.attempts == 0, "late request modified final code accounting"
            if final == "expired":
                assert row.expires_at < (auth.utcnow() if natural_expiry else utcnow())
            else:
                assert row.status == "used"


@pytest.mark.parametrize("kind", ["sms", "telegram"])
def test_non_ascii_wrong_code_is_a_counted_client_error(client, kind):
    _, model, row_id, path, body = _login(kind)
    result = _post(path, {**body, "code": "Башҡорт"})
    assert result.status_code == 400, result.text
    with Session(engine) as session:
        assert session.get(model, row_id).attempts == 1


def test_non_ascii_review_code_is_rejected_without_server_error(client, monkeypatch):
    user_id, model, row_id, path, body = _login("sms")
    monkeypatch.setattr(auth.settings, "review_phone", body["phone"])
    monkeypatch.setattr(auth.settings, "review_code", CODE)
    result = _post(path, {**body, "code": "Башҡорт"})
    assert result.status_code == 400, result.text
    with Session(engine) as session:
        assert session.get(model, row_id).attempts == 0  # Режим проверки стора игнорирует обычные OTP.
        assert not session.exec(select(RefreshToken).where(RefreshToken.user_id == user_id)).all()


def test_non_ascii_configured_review_code_does_not_raise_500(client, monkeypatch):
    user_id, model, row_id, path, body = _login("sms")
    monkeypatch.setattr(auth.settings, "review_phone", body["phone"])
    monkeypatch.setattr(auth.settings, "review_code", "Башҡорт")
    result = _post(path, {**body, "code": CODE})
    assert result.status_code == 400, result.text
    with Session(engine) as session:
        assert session.get(model, row_id).attempts == 0
        assert not session.exec(select(RefreshToken).where(RefreshToken.user_id == user_id)).all()
