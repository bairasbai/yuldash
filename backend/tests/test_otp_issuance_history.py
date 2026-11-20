"""QA-B01-018: using an OTP must not erase its SMS issuance throttle history."""
from datetime import datetime, timedelta, timezone
import uuid

import pytest
from sqlmodel import Session, select

from app import timeutil
from app.db import engine
from app.models import OtpCode, RefreshToken
from app.otp_budget import SmsDelivery
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
def test_successful_login_cannot_reset_sms_issuance_throttle(client, monkeypatch, cleanup, telegram_login_ready):
    clock = [datetime.now(timezone.utc)]

    class ClockDatetime(datetime):
        @classmethod
        def now(cls, tz=None):
            return clock[0].astimezone(tz) if tz is not None else clock[0].replace(tzinfo=None)

    # All imported utcnow functions and OtpCode.default_factory share this clock.
    monkeypatch.setattr(timeutil, "datetime", ClockDatetime)
    phone = "+7996" + str(int(uuid.uuid4().hex[:8], 16) % 10_000_000).zfill(7)
    sent = []
    def synthetic_send(_phone, _code, *, lang, provider_key):
        sent.append(provider_key)
        return SmsDelivery.SENT

    monkeypatch.setattr(auth, "send_sms", synthetic_send)
    issued = client.post("/auth/request-code", json={"phone": phone})
    assert issued.status_code == 200
    code = issued.json()["dev_code"]
    issued_row, = _rows(phone)
    verified = client.post("/auth/verify", json={"phone": phone, "code": code})
    assert verified.status_code == 200
    user_id = verified.json()["user"]["id"]
    retained, = _rows(phone)
    assert retained["id"] == issued_row["id"] and retained["created_at"] == issued_row["created_at"]
    assert retained["code"] == "" and retained["expires_at"] <= utcnow()
    assert client.post("/auth/verify", json={"phone": phone, "code": code}).status_code == 400
    assert client.post("/auth/verify", json={"phone": phone, "code": ""}).status_code == 400
    if cleanup:
        clock[0] += timedelta(microseconds=1)
        _webhook_cleanup(client)
    before_rows = _rows(phone)
    duplicate = client.post("/auth/request-code", json={"phone": phone})
    assert duplicate.status_code == 429 and duplicate.headers["Retry-After"] == "60"
    assert len(sent) == 1 and _rows(phone) == before_rows == [retained]
    with Session(engine) as session:
        assert len(session.exec(select(RefreshToken).where(RefreshToken.user_id == user_id)).all()) == 1
    clock[0] += timedelta(seconds=60)
    after_window = client.post("/auth/request-code", json={"phone": phone})
    assert after_window.status_code == 200
    assert len(sent) == 2 and len(set(sent)) == 2
    latest = _rows(phone)[-1]
    assert latest["created_at"] == utcnow()
    assert latest["created_at"] >= before_rows[-1]["created_at"] + timedelta(seconds=60)
    after_login = client.post("/auth/verify", json={"phone": phone, "code": after_window.json()["dev_code"]})
    assert after_login.status_code == 200 and after_login.json()["user"]["id"] == user_id
    with Session(engine) as session:
        assert len(session.exec(select(RefreshToken).where(RefreshToken.user_id == user_id)).all()) == 2
    if cleanup:
        clock[0] += timedelta(microseconds=1)
        _webhook_cleanup(client)
        assert [row["id"] for row in _rows(phone)] == [retained["id"], latest["id"]]
