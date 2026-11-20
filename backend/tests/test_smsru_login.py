"""Run the HTTP protocol and normal login endpoints against synthetic SMS.ru replies."""

import logging
from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta
from threading import Event
from urllib.parse import parse_qs

from fastapi.testclient import TestClient
import httpx
import pytest
from sqlalchemy import delete, func, update
from sqlmodel import Session, select

from app import otp_budget, services, sms_reconcile, smsru
from app.config import settings
from app.db import engine
from app.main import app
from app.models import AppSetting, AppSettingChange, OtpBudgetBucket, OtpCode, OtpSendAttempt, UserRole
from app.routers import auth
from app.timeutil import utcnow


CODE = "627491"
KEY = "synthetic-smsru-api-id"
RECEIPT = "000527-70102050"


@pytest.fixture
def smsru_settings(client, monkeypatch, telegram_login_ready):
    monkeypatch.setattr(settings, "sms_provider", "smsru")
    monkeypatch.setattr(settings, "sms_ru_api_id", KEY)
    monkeypatch.setattr(settings, "sms_from", "SyntheticSender")
    monkeypatch.setattr(settings, "sms_budget_hmac_key", "synthetic-budget-key")
    monkeypatch.setattr(settings, "sms_notify_enabled", False)
    monkeypatch.setattr(settings, "sms_ru_login_ready", False)
    monkeypatch.setattr(auth, "gen_otp", lambda: CODE)
    with Session(engine) as session:
        row = session.get(AppSetting, "login.sms")
        previous = (row.value, row.updated_at, row.updated_by)
        last_change = session.exec(select(func.max(AppSettingChange.id))).one() or 0
        session.execute(update(AppSetting).where(AppSetting.key == "login.sms").values(value=None))
        session.commit()
    yield
    with Session(engine) as session:
        phone_filter = OtpCode.phone.startswith("+79998705")
        attempt_ids = select(OtpCode.attempt_id).where(phone_filter)
        session.execute(delete(OtpSendAttempt).where(OtpSendAttempt.id.in_(attempt_ids)))
        session.execute(delete(OtpCode).where(phone_filter))
        session.execute(delete(AppSettingChange).where(
            AppSettingChange.key == "login.sms", AppSettingChange.id > last_change,
        ))
        session.execute(update(AppSetting).where(AppSetting.key == "login.sms")
                        .values(value=previous[0], updated_at=previous[1], updated_by=previous[2]))
        session.commit()


@pytest.fixture
def enable_login(client, user_factory, monkeypatch, smsru_settings):
    def enable():
        monkeypatch.setattr(settings, "sms_ru_login_ready", True)
        owner = user_factory(role=UserRole.admin)
        response = client.post("/admin/switches/login.sms", headers=owner["auth"], json={"value": True})
        assert response.status_code == 200 and response.json()["effective"] is True
        return owner

    return enable


def _transport(monkeypatch, respond):
    calls = []

    def handle(request):
        assert request.method == "POST" and request.url.host == "sms.ru"
        assert request.url.scheme == "https" and not request.url.query
        form = {k: v[0] for k, v in parse_qs(request.content.decode()).items()}
        assert form["api_id"] == KEY and form["json"] == "1"
        calls.append((request.url.path, form))
        return respond(request, form)

    monkeypatch.setattr(smsru, "_client", lambda: httpx.Client(
        transport=httpx.MockTransport(handle), trust_env=False, follow_redirects=False,
    ))
    return calls


def _reply(recipient, item):
    return httpx.Response(200, json={"status": "OK", "status_code": 100, "sms": {recipient: item}})


def _accepted(recipient, message_id=RECEIPT):
    return _reply(recipient, {"status": "OK", "status_code": 100, "sms_id": message_id})


def _attempt(session, phone):
    code = session.exec(select(OtpCode).where(OtpCode.phone == phone)).one()
    return session.get(OtpSendAttempt, code.attempt_id), code


def _service_used(session, attempt):
    scope, subject, start = next(key for key in attempt.bucket_keys if key[0] == "service_day")
    return session.get(OtpBudgetBucket, (scope, subject, start)).used


def test_credentials_and_operator_readiness_do_not_activate_paid_login_or_notifications(client, monkeypatch,
                                                                                       smsru_settings):
    def forbidden(*args, **kwargs):
        pytest.fail("a disabled channel attempted network transport")

    monkeypatch.setattr(smsru, "_client", forbidden)
    monkeypatch.setattr(httpx, "get", forbidden)
    phone = "+79998705001"
    for ready in (False, True):
        monkeypatch.setattr(settings, "sms_ru_login_ready", ready)
        response = client.post("/auth/request-code", json={"phone": phone})
        assert response.status_code == 403
        assert services.sms_will_reach([phone]) == 0
        assert services.send_text(phone, "Синтетическое уведомление") is False
    with Session(engine) as session:
        assert not session.exec(select(OtpCode.id).where(OtpCode.phone == phone)).all()


@pytest.mark.parametrize("lang, title, ordinal", [
    ("ru", "Код входа в Юлдаш", 2), ("ba", "Юлдашҡа инеү коды", 3),
])
def test_request_and_verify_use_smsru_protocol_and_never_expose_an_otp(client, monkeypatch, caplog, enable_login,
                                                                     lang, title, ordinal):
    enable_login()
    phone = f"+7999870500{ordinal}"
    receipt = f"000527-7010205{ordinal}"
    calls = _transport(monkeypatch, lambda request, form: _accepted(form["to"], receipt))
    with caplog.at_level(logging.INFO, logger="httpx"):
        response = client.post("/auth/request-code", json={"phone": phone, "lang": lang, "channel": "sms"})
    assert response.status_code == 200, response.text
    assert response.json() == {"sent": True, "resend_after_sec": 60, "expires_in_sec": settings.otp_ttl_sec}
    (path, form), = calls
    assert path == "/sms/send" and form["to"] == phone[1:] and form["from"] == "SyntheticSender"
    assert form["ttl"] == "5" and "test" not in form
    assert form["msg"].startswith(title) and form["msg"].endswith(f"\n@app.yulbash.ru #{CODE}")
    assert len(form["msg"]) <= 70
    assert all(secret not in caplog.text for secret in (phone[1:], KEY, CODE))
    with Session(engine) as session:
        attempt, proof = _attempt(session, phone)
        assert (attempt.state, attempt.sms_ru_message_id, attempt.sms_ru_status_code) == ("sent", receipt, 100)
        assert proof.code != CODE and phone not in str(attempt.bucket_keys)
    assert client.post("/auth/verify", json={"phone": phone, "code": CODE}).status_code == 200
    assert client.post("/auth/verify", json={"phone": phone, "code": CODE}).status_code == 400


@pytest.mark.parametrize("case, status, charge", [
    ("balance", 502, 0), ("recipient", 502, 0), ("timeout", 200, 1), ("gateway", 200, 1),
    ("unavailable", 200, 1), ("no-id", 200, 1), ("malformed-id", 200, 1), ("wrong-row", 200, 1),
    ("contradictory-rejection", 200, 1), ("redirect", 200, 1),
])
def test_only_a_proven_pre_acceptance_refusal_refunds(client, monkeypatch, enable_login, case, status, charge):
    enable_login()
    cases = ("balance", "recipient", "timeout", "gateway", "unavailable", "no-id", "malformed-id", "wrong-row",
             "contradictory-rejection", "redirect")
    ordinal = cases.index(case)
    phone = f"+799987051{ordinal:02d}"
    before_time = utcnow()

    def respond(request, form):
        recipient = form["to"]
        if case == "timeout":
            raise httpx.ReadTimeout(f"synthetic secret {CODE}", request=request)
        if case == "gateway":
            return httpx.Response(502, text="gateway error")
        if case == "redirect":
            return httpx.Response(307, headers={"Location": "https://example.invalid"})
        if case == "balance":
            return httpx.Response(200, json={"status": "ERROR", "status_code": 201})
        if case == "unavailable":
            return httpx.Response(200, json={"status": "ERROR", "status_code": 220})
        if case == "recipient":
            return _reply(recipient, {"status": "ERROR", "status_code": 207})
        if case == "wrong-row":
            return _accepted("79998705999")
        if case == "contradictory-rejection":
            return _reply(recipient, {"status": "ERROR", "status_code": 207, "sms_id": RECEIPT})
        return _accepted(recipient, CODE if case == "malformed-id" else "")

    with Session(engine) as session:
        service_day = otp_budget._window(before_time, otp_budget.DAY_SECONDS)
        bucket = session.get(OtpBudgetBucket, ("service_day", "global", service_day))
        previous = bucket.used if bucket else 0
    calls = _transport(monkeypatch, respond)
    response = client.post("/auth/request-code", json={"phone": phone})
    assert response.status_code == status and "dev_code" not in response.json()
    assert client.post("/auth/request-code", json={"phone": phone}).status_code == 429
    assert len(calls) == 1
    with Session(engine) as session:
        attempt, proof = _attempt(session, phone)
        assert attempt.state == ("unknown" if charge else "failed")
        assert _service_used(session, attempt) == previous + charge
        assert bool(proof.code) == bool(charge)
    verified = client.post("/auth/verify", json={"phone": phone, "code": CODE})
    assert verified.status_code == (200 if charge else 400)


def test_crash_after_dispatch_keeps_attempt_and_allows_a_delivered_code_after_wait(client, monkeypatch,
                                                                                   enable_login):
    enable_login()
    phone = "+79998705200"
    moment = [utcnow() - timedelta(seconds=otp_budget.DISPATCH_RECOVERY_SECONDS)]
    monkeypatch.setattr(otp_budget, "utcnow", lambda: moment[0])
    monkeypatch.setattr(auth, "utcnow", lambda: moment[0])
    calls = _transport(monkeypatch, lambda request, form: _accepted(form["to"], "000527-70102060"))

    def lose_receipt(*args, **kwargs):
        raise RuntimeError("synthetic process lost after provider accepted")

    monkeypatch.setattr(auth, "finish_sms", lose_receipt)
    request = TestClient(app, raise_server_exceptions=False)
    try:
        result = request.post("/auth/request-code", json={"phone": phone})
    finally:
        request.close()
    assert result.status_code == 500
    with Session(engine) as session:
        attempt, _ = _attempt(session, phone)
        original_used = _service_used(session, attempt)
        assert attempt.state == "reserved" and attempt.sms_ru_message_id is None
    verify = {"phone": phone, "code": CODE}
    assert client.post("/auth/verify", json=verify).status_code == 400
    moment[0] += timedelta(seconds=otp_budget.DISPATCH_RECOVERY_SECONDS)
    assert client.post("/auth/verify", json=verify).status_code == 200
    assert client.post("/auth/verify", json=verify).status_code == 400
    assert client.post("/auth/request-code", json={"phone": phone}).status_code == 429
    with Session(engine) as session:
        attempt, _ = _attempt(session, phone)
        assert attempt.state == "unknown" and _service_used(session, attempt) == original_used
    assert [path for path, _ in calls] == ["/sms/send"]


@pytest.mark.parametrize("delivery_status", [103, 104, -1])
def test_status_reconciliation_keeps_accepted_charge_when_login_is_disabled(client, monkeypatch,
                                                                           enable_login, delivery_status):
    owner = enable_login()
    phone = f"+79998705{300 + delivery_status:03d}"
    receipt = f"000527-{70102200 + delivery_status}"

    def respond(request, form):
        if request.url.path == "/sms/send":
            return _accepted(form["to"], receipt)
        assert request.url.path == "/sms/status" and form["sms_id"] == receipt
        return _reply(receipt, {"status": "ERROR" if delivery_status < 0 else "OK",
                                "status_code": delivery_status, "status_text": "ignored"})

    moment = [utcnow()]
    monkeypatch.setattr(otp_budget, "utcnow", lambda: moment[0])
    monkeypatch.setattr(sms_reconcile, "utcnow", lambda: moment[0])
    calls = _transport(monkeypatch, respond)
    assert client.post("/auth/request-code", json={"phone": phone}).status_code == 200
    with Session(engine) as session:
        attempt, _ = _attempt(session, phone)
        old_used = _service_used(session, attempt)
    result = client.post("/admin/switches/login.sms", headers=owner["auth"], json={"value": False, "confirm": True})
    assert result.status_code == 200 and result.json()["effective"] is False
    monkeypatch.setattr(settings, "sms_ru_login_ready", False)
    moment[0] += timedelta(minutes=1)
    with Session(engine) as session:
        assert sms_reconcile.reconcile_receipts(session) == (int(delivery_status > 0))
        attempt, _ = _attempt(session, phone)
        expected = 100 if delivery_status < 0 else delivery_status
        assert attempt.state == "sent" and attempt.sms_ru_status_code == expected
        assert _service_used(session, attempt) == old_used
        assert sms_reconcile.reconcile_receipts(session) == 0
    assert [path for path, _ in calls] == ["/sms/send", "/sms/status"]


def test_parallel_reconciliation_only_queries_the_receipt_once_per_minute(client, monkeypatch, enable_login):
    enable_login()
    phone = "+79998705522"
    receipt = "000527-70102062"
    entered, release = Event(), Event()

    def respond(request, form):
        if request.url.path == "/sms/send":
            return _accepted(form["to"], receipt)
        assert form["sms_id"] == receipt
        entered.set()
        assert release.wait(8)
        return _reply(receipt, {"status": "OK", "status_code": 103})

    moment = [utcnow()]
    monkeypatch.setattr(otp_budget, "utcnow", lambda: moment[0])
    monkeypatch.setattr(sms_reconcile, "utcnow", lambda: moment[0])
    calls = _transport(monkeypatch, respond)
    assert client.post("/auth/request-code", json={"phone": phone}).status_code == 200
    moment[0] += timedelta(minutes=1)

    def reconcile():
        with Session(engine) as session:
            return sms_reconcile.reconcile_receipts(session)

    with ThreadPoolExecutor(max_workers=2) as pool:
        first = pool.submit(reconcile)
        try:
            assert entered.wait(8)
            assert pool.submit(reconcile).result(timeout=8) == 0
        finally:
            release.set()
        assert first.result(timeout=8) == 1
    assert [path for path, _ in calls] == ["/sms/send", "/sms/status"]
