"""QA-B01-018: using an OTP must not erase its SMS issuance throttle history."""
from datetime import datetime, timedelta, timezone
import uuid

import pytest
from sqlmodel import Session, select

from app import timeutil
from app.db import engine
from app.models import OtpCode, RefreshToken
from app.routers import auth
from app.timeutil import utcnow


def _rows(phone):
    with Session(engine) as session:
        return [row.model_dump() for row in session.exec(select(OtpCode).where(
            OtpCode.phone == phone).order_by(OtpCode.id)).all()]


def _webhook_cleanup(client):
    # Real webhook route, a synthetic unknown request; no external bot call.
    result = client.post("/telegram/webhook", json={"message": {
        "text": "/start qa-history-unknown", "from": {"id": 910018}, "chat": {"id": 910018}}})
    assert result.status_code == 200


@pytest.mark.parametrize("cleanup", [False, True], ids=["verify-only", "with-webhook-cleanup"])
def test_successful_login_cannot_reset_sms_issuance_throttle(client, monkeypatch, cleanup):
    clock = [datetime.now(timezone.utc)]

    class ClockDatetime(datetime):
        @classmethod
        def now(cls, tz=None):
            return clock[0].astimezone(tz) if tz is not None else clock[0].replace(tzinfo=None)

    # All imported utcnow functions and OtpCode.default_factory share this clock.
    monkeypatch.setattr(timeutil, "datetime", ClockDatetime)
    phone = "+7996" + str(int(uuid.uuid4().hex[:8], 16) % 10_000_000).zfill(7)
    sent = []
    codes = iter(("112233", "223344", "334455", "445566"))
    monkeypatch.setattr(auth, "send_sms", lambda *a, **kw: sent.append("synthetic SMS"))
    monkeypatch.setattr(auth, "gen_otp", lambda: next(codes))
    user_id = None
    for _ in range(3):
        issued = client.post("/auth/request-code", json={"phone": phone})
        assert issued.status_code == 200
        code = issued.json()["dev_code"]
        issued_row = _rows(phone)[-1]
        verified = client.post("/auth/verify", json={"phone": phone, "code": code})
        assert verified.status_code == 200
        current_user = verified.json()["user"]["id"]
        if user_id is None:
            user_id = current_user
        assert current_user == user_id
        retained_rows = [row for row in _rows(phone) if row["id"] == issued_row["id"]]
        assert len(retained_rows) == 1, "issuance history must survive successful verification"
        retained = retained_rows[0]
        assert retained["created_at"] == issued_row["created_at"]
        assert retained["code"] == "" and retained["expires_at"] <= utcnow()
        assert client.post("/auth/verify", json={"phone": phone, "code": code}).status_code == 400
        assert client.post("/auth/verify", json={"phone": phone, "code": ""}).status_code == 400
        if cleanup:
            clock[0] += timedelta(microseconds=1)
            _webhook_cleanup(client)
    before_rows = _rows(phone)
    fourth = client.post("/auth/request-code", json={"phone": phone})
    assert fourth.status_code == 429, f"HTTP={fourth.status_code}; SMS={len(sent)}; retained={len(before_rows)}"
    assert len(sent) == 3
    assert _rows(phone) == before_rows
    assert len(before_rows) == 3
    assert all(row["code"] == "" and row["expires_at"] <= utcnow() for row in before_rows)
    with Session(engine) as session:
        assert len(session.exec(select(RefreshToken).where(RefreshToken.user_id == user_id)).all()) == 3
    # The retained history must release the same existing 60-second window.
    clock[0] += timedelta(seconds=61)
    after_window = client.post("/auth/request-code", json={"phone": phone})
    assert after_window.status_code == 200
    assert len(sent) == 4
    latest = _rows(phone)[-1]
    assert latest["created_at"] == utcnow()
    assert latest["created_at"] >= before_rows[-1]["created_at"] + timedelta(seconds=60)
    after_login = client.post("/auth/verify", json={"phone": phone, "code": after_window.json()["dev_code"]})
    assert after_login.status_code == 200 and after_login.json()["user"]["id"] == user_id
    with Session(engine) as session:
        assert len(session.exec(select(RefreshToken).where(RefreshToken.user_id == user_id)).all()) == 4
    if cleanup:
        clock[0] += timedelta(microseconds=1)
        _webhook_cleanup(client)
        assert len(_rows(phone)) == 1, "cleanup should remove old used rows after the throttle window"
        assert _rows(phone)[0]["id"] == latest["id"], "the new issuance owns a fresh throttle window"
