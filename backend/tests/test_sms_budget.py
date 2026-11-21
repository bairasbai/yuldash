"""Exercise SMS admission, compensation and cutoff through the FastAPI entry points."""

from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta
from threading import Barrier

import pytest
from fastapi.testclient import TestClient
from sqlmodel import Session, select

from app import cleanup, login_methods, otp_budget, security
from app.config import settings
from app.db import engine
from app.main import app
from app.models import OtpBudgetAlert, OtpBudgetBucket, OtpCode, OtpSendAttempt
from app.otp_budget import SmsDelivery
from app.routers import auth
from app.timeutil import utcnow


@pytest.fixture
def sms_clock(monkeypatch):
    # Tests that exhaust quotas move to distinct UTC days in the shared database.
    moment = [utcnow().replace(hour=12, minute=0, second=0, microsecond=0)]
    monkeypatch.setattr(otp_budget, "utcnow", lambda: moment[0])
    monkeypatch.setattr(auth, "utcnow", lambda: moment[0])
    monkeypatch.setattr(security, "utcnow", lambda: moment[0])
    return moment


def _send(client, number, *, device="", **fields):
    return client.post("/auth/request-code", json={"phone": f"+7999830{number:04d}", **fields},
                       headers={"X-Device-Id": device} if device else {})


def _day(when):
    return int((when - datetime(1970, 1, 1)).total_seconds()) // 86400 * 86400


def _used(session, when):
    row = session.get(OtpBudgetBucket, ("service_day", "global", _day(when)))
    return row.used if row is not None else 0


def test_historical_verifier_is_hashed_and_jwt_rotation_keeps_sms_state(client, monkeypatch, sms_clock):
    monkeypatch.setattr(settings, "sms_budget_hmac_key", "synthetic-sms-key-for-budget-test")
    monkeypatch.setattr(auth, "gen_otp", lambda: "604029")
    calls = []

    def provider(_phone, _code, *, lang, provider_key):
        calls.append((lang, provider_key))
        return SmsDelivery.SENT

    monkeypatch.setattr(auth, "send_sms", provider)
    assert _send(client, 0, lang="ba").status_code == 200
    monkeypatch.setattr(settings, "jwt_secret", "rotated-synthetic-jwt-key-with-at-least-32-bytes")
    assert _send(client, 0, lang="ba").status_code == 429
    assert len(calls) == 1 and calls[0][0] == "ba"
    with Session(engine) as session:
        code = session.exec(select(OtpCode).where(OtpCode.phone == "+79998300000")).one()
        attempt = session.get(OtpSendAttempt, code.attempt_id)
        assert attempt.provider_idempotency_key == calls[0][1] and attempt.state == "sent"
        assert code.code != "604029" and len(code.code) == len(code.ip_hash) == len(code.device_hash) == 64
    assert client.post("/auth/verify", json={"phone": "+79998300000", "code": "604029"}).status_code == 200


def test_phone_hour_and_day_quotas_across_window_boundaries(client, sms_clock):
    sms_clock[0] -= timedelta(days=20)
    for hour in range(2):
        for minute in range(5):
            assert _send(client, 1, device=f"qa-device-{hour}-{minute}").status_code == 200
            sms_clock[0] += timedelta(minutes=1)
        refusal = _send(client, 1, device=f"qa-device-{hour}-overflow")
        retry_after = (55 + 60 * 10 * hour) * 60
        assert refusal.status_code == 429 and refusal.headers["Retry-After"] == str(retry_after)
        sms_clock[0] += timedelta(minutes=55)
    refusal = _send(client, 1, device="qa-device-next-hour")
    assert refusal.status_code == 429 and refusal.headers["Retry-After"] == str(10 * 3600)
    sms_clock[0] += timedelta(hours=10)
    assert _send(client, 1, device="qa-device-next-day").status_code == 200


def test_device_quota_persists_across_phones_and_missing_id_uses_peer(client, sms_clock):
    sms_clock[0] -= timedelta(days=19)
    with TestClient(app, client=("198.51.100.1", 49152)) as first, \
            TestClient(app, client=("198.51.100.2", 49153)) as second:
        for i in range(5):
            assert _send(first, 10 + i, device="qa-device-budget").status_code == 200
        assert _send(second, 15, device="qa-device-budget").status_code == 429
        assert _send(second, 15, device="qa-device-other").status_code == 200
        for i in range(5):
            assert _send(first, 20 + i).status_code == 200
        assert _send(first, 25).status_code == 429
        assert _send(second, 25).status_code == 200


def test_ip_quota_uses_asgi_peer_not_raw_forwarded_headers(client, sms_clock):
    sms_clock[0] -= timedelta(days=18)
    with TestClient(app, client=("198.51.100.19", 49152)) as first, \
            TestClient(app, client=("::ffff:198.51.100.19", 49153)) as same_ip, \
            TestClient(app, client=("198.51.100.20", 49154)) as other_ip:
        for i in range(40):
            assert _send(first, 50 + i, device=f"ip-qa-device-{i}").status_code == 200
        blocked = same_ip.post("/auth/request-code", json={"phone": "+79998300090"},
                               headers={"X-Device-Id": "free-device", "X-Forwarded-For": "198.51.100.20"})
        assert blocked.status_code == 429
        assert blocked.headers["Retry-After"] == "3600"
        assert _send(other_ip, 90, device="free-device").status_code == 200


def test_failure_refunds_once_for_original_utc_day_and_keeps_personal_limits(client, monkeypatch, sms_clock):
    sms_clock[0] = (sms_clock[0] - timedelta(days=16)).replace(hour=23, minute=59, second=59)
    previous_day = sms_clock[0]

    def reject(*args, **kwargs):
        sms_clock[0] += timedelta(seconds=1)
        return SmsDelivery.FAILED

    monkeypatch.setattr(auth, "gen_otp", lambda: "609044")
    monkeypatch.setattr(auth, "send_sms", reject)
    response = _send(client, 95, device="qa-failed-device")
    assert response.status_code == 502 and response.json()["detail"]["code"] == "sms_delivery_failed"
    assert _send(client, 95, device="qa-failed-device").headers["Retry-After"] == "59"
    assert client.post("/auth/verify", json={"phone": "+79998300095", "code": "609044"}).status_code == 400
    with Session(engine) as session:
        proof = session.exec(select(OtpCode).where(OtpCode.phone == "+79998300095")).one()
        assert proof.code == ""
        attempt = session.get(OtpSendAttempt, proof.attempt_id)
        assert attempt.state == "failed"
        assert _used(session, previous_day) == _used(session, sms_clock[0]) == 0
        day_row = session.get(OtpBudgetBucket, ("service_day", "global", _day(previous_day)))
        assert day_row.version == 2
        for scope, subject, window in attempt.bucket_keys:
            if scope != "service_day":
                assert session.get(OtpBudgetBucket, (scope, subject, window)).used == 1
        assert not otp_budget.finish_sms(session, proof.attempt_id, SmsDelivery.FAILED)
        session.commit()
        assert day_row.version == 2 and day_row.used == 0


def test_timeout_keeps_quota_without_repeat_and_can_verify_delivered_code(client, monkeypatch, sms_clock, caplog):
    sms_clock[0] -= timedelta(days=14)
    calls = []

    def timed_out(_phone, code, *, lang, provider_key):
        calls.append(provider_key)
        raise TimeoutError(f"synthetic response contains {code}")

    monkeypatch.setattr(auth, "gen_otp", lambda: "609055")
    monkeypatch.setattr(auth, "send_sms", timed_out)
    answer = _send(client, 96)
    assert answer.status_code == 200 and "dev_code" not in answer.json()
    assert _send(client, 96).status_code == 429
    assert len(calls) == 1 and "609055" not in caplog.text
    with Session(engine) as session:
        proof = session.exec(select(OtpCode).where(OtpCode.phone == "+79998300096")).one()
        assert session.get(OtpSendAttempt, proof.attempt_id).state == "unknown"
        assert _used(session, sms_clock[0]) == 1
    assert client.post("/auth/verify", json={"phone": "+79998300096", "code": "609055"}).status_code == 200


def test_sms_retention_uses_end_of_window_and_keeps_attempts_for_reconciliation(client):
    now = datetime(2001, 1, 4)
    active_cutoff = now - timedelta(hours=48)
    day = _day(active_cutoff)
    stale_keys = [("service_day", "global", day - 2 * 86400),
                  ("ip_hour", "synthetic-peer", day - 2 * 3600),
                  ("phone_cooldown", "expired-cooldown", 0)]
    live_keys = [("service_day", "global", day - 86400),
                 ("ip_hour", "synthetic-peer", day - 3600),
                 ("phone_cooldown", "active-cooldown", 0)]
    with Session(engine) as session:
        for scope, subject, start in stale_keys + live_keys:
            updated = active_cutoff if subject == "active-cooldown" else active_cutoff - timedelta(seconds=1)
            session.add(OtpBudgetBucket(scope=scope, subject=subject, window_start=start,
                                        used=1, cap=1, version=1, updated_at=updated))
        for key in (day - 2 * 86400, day - 86400):
            session.add(OtpBudgetAlert(service_day=key, kind="sms_daily_cap", created_at=active_cutoff))
        for key, when in (("stale-retention", now - timedelta(days=31)),
                          ("live-retention", now - timedelta(days=29))):
            session.add(OtpSendAttempt(id=key, provider_idempotency_key=key, state="unknown",
                                       bucket_keys=[], created_at=when, updated_at=when))
        session.commit()

    for _, table, predicate, params in cleanup._rules(now):
        if table in {"otpbudgetbucket", "otpbudgetalert", "otpsendattempt"}:
            cleanup._delete_batched(table, predicate, params)
    with Session(engine) as session:
        assert all(session.get(OtpBudgetBucket, key) is None for key in stale_keys)
        assert all(session.get(OtpBudgetBucket, key) is not None for key in live_keys)
        assert session.get(OtpBudgetAlert, (day - 2 * 86400, "sms_daily_cap")) is None
        assert session.get(OtpBudgetAlert, (day - 86400, "sms_daily_cap")) is not None
        assert session.get(OtpSendAttempt, "stale-retention") is None
        assert session.get(OtpSendAttempt, "live-retention").state == "unknown"


def test_late_proven_rejection_is_recorded_after_original_bucket_expires(client, monkeypatch, sms_clock):
    sms_clock[0] -= timedelta(days=12)
    monkeypatch.setattr(auth, "send_sms", lambda *args, **kwargs: SmsDelivery.UNKNOWN)
    assert _send(client, 97).status_code == 200
    original = sms_clock[0]
    sms_clock[0] += timedelta(days=4)
    monkeypatch.setattr(auth, "send_sms", lambda *args, **kwargs: SmsDelivery.SENT)
    assert _send(client, 98).status_code == 200
    for _, table, predicate, params in cleanup._rules(sms_clock[0]):
        if table == "otpbudgetbucket":
            cleanup._delete_batched(table, predicate, params)
    with Session(engine) as session:
        proof = session.exec(select(OtpCode).where(OtpCode.phone == "+79998300097")).one()
        assert session.get(OtpBudgetBucket, ("service_day", "global", _day(original))) is None
        assert otp_budget.finish_sms(session, proof.attempt_id, SmsDelivery.FAILED)
        session.commit()
        assert proof.code == "" and session.get(OtpSendAttempt, proof.attempt_id).state == "failed"
        assert _used(session, sms_clock[0]) == 1


def test_parallel_phones_compete_for_one_remaining_service_slot(client, monkeypatch, sms_clock):
    sms_clock[0] -= timedelta(days=13)
    monkeypatch.setattr(settings, "sms_daily_cap", 1)
    ready = Barrier(4)
    delivered, alerts = [], []
    original = login_methods.login_settings

    def synchronize(session, *, lock=None):
        if lock == "share":
            ready.wait(timeout=10)
        return original(session, lock=lock)

    def provider(phone, _code, *, lang, provider_key):
        delivered.append(phone)
        return SmsDelivery.SENT

    def request(i):
        requester = TestClient(app, client=(f"198.51.100.{i}", 49152))
        try:
            return _send(requester, i, device=f"quota-race-{i}")
        finally:
            requester.close()

    monkeypatch.setattr(login_methods, "login_settings", synchronize)
    monkeypatch.setattr(auth, "send_sms", provider)
    monkeypatch.setattr(auth, "notify_service_limit", lambda session: alerts.append("sms_daily_cap"))
    with ThreadPoolExecutor(max_workers=4) as pool:
        answers = list(pool.map(request, range(100, 104)))
    errors = [answer for answer in answers if answer.status_code != 200]
    assert len(errors) == 3 and len(delivered) == 1
    assert [answer.status_code for answer in errors] == [503] * 3
    assert [answer.json()["detail"]["code"] for answer in errors] == ["sms_daily_cap"] * 3
    assert all(answer.headers["Retry-After"] == "43200" for answer in errors)
    assert alerts == ["sms_daily_cap"]
    with Session(engine) as session:
        assert _used(session, sms_clock[0]) == 1
        assert session.get(OtpBudgetAlert, (_day(sms_clock[0]), "sms_daily_cap")) is not None
        rows = session.exec(select(OtpCode).where(OtpCode.phone.in_(
            [f"+7999830{i:04d}" for i in range(100, 104)],
        ))).all()
        assert [row.phone for row in rows] == delivered
        attempt = session.get(OtpSendAttempt, rows[0].attempt_id)
        buckets = session.exec(select(OtpBudgetBucket).where(OtpBudgetBucket.updated_at == sms_clock[0])).all()
        assert {(row.scope, row.subject, row.window_start) for row in buckets} == set(map(tuple, attempt.bucket_keys))


@pytest.mark.parametrize("body", [
    {"phone": "+12025550123"},
    {"phone": "+74951234567"},
    {"phone": "+79998300110", "lang": "en"},
    {"phone": "+79998300110", "channel": "vk"},
])
def test_invalid_phone_language_or_channel_never_dispatches(client, monkeypatch, body):
    sent = []
    monkeypatch.setattr(auth, "send_sms", lambda *args, **kwargs: sent.append(args))
    assert client.post("/auth/request-code", json=body).status_code == 422
    assert sent == []
