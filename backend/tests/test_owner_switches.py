"""RS-04 owner operations, authoritative availability, and transaction boundaries."""

from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta
from threading import Event
from time import monotonic, sleep

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import delete, text, update
from sqlmodel import Session, select

from app import login_methods, security
from app.config import settings
from app.db import engine
from app.main import app
from app.models import AppSetting, AppSettingChange, OtpCode, User, UserRole
from app.otp_budget import SmsDelivery
from app.owner_switches import LOGIN_KEYS
from app.routers import admin_switches, auth
from app.timeutil import utcnow


SMS = "login.sms"
SMS_PATH = "/admin/switches/login.sms"
PG_ONLY = pytest.mark.skipif(engine.dialect.name != "postgresql", reason="requires real PostgreSQL row locks")


@pytest.fixture(autouse=True)
def synthetic_channels(telegram_login_ready, monkeypatch):
    monkeypatch.setattr(settings, "env", "dev")
    monkeypatch.setattr(settings, "sms_provider", "mock")
    monkeypatch.setattr(settings, "review_phone", "")
    with Session(engine) as session:
        session.execute(update(AppSetting).where(AppSetting.key.in_(LOGIN_KEYS))
                        .values(value=None, updated_by=None, updated_at=None))
        session.commit()
    yield
    with Session(engine) as session:
        session.execute(delete(AppSettingChange).where(AppSettingChange.key.in_(LOGIN_KEYS)))
        session.execute(update(AppSetting).where(AppSetting.key.in_(LOGIN_KEYS))
                        .values(value=None, updated_by=None, updated_at=None))
        session.commit()


def _state(client, operator, key=SMS):
    response = client.get("/admin/switches", headers=operator["auth"])
    assert response.status_code == 200
    return response.json()["switches"][key]


def _post(method, path, headers=None, body=None):
    requester = TestClient(app)
    try:
        return requester.request(method, path, headers=headers, json=body)
    finally:
        requester.close()


def _assert_blocked(pid, blocker):
    deadline = monotonic() + 8
    with engine.connect().execution_options(isolation_level="AUTOCOMMIT") as connection:
        while monotonic() < deadline:
            blockers = connection.execute(text("SELECT pg_blocking_pids(:pid)"), {"pid": pid}).scalar_one()
            if blocker in blockers:
                return
            sleep(0.02)
    pytest.fail(f"PostgreSQL session {pid} did not wait for {blocker}")


def _pid(session):
    return session.execute(text("SELECT pg_backend_pid()")).scalar_one()


def test_switches_show_state_and_require_telegram_operator(client, user_factory, monkeypatch):
    admin = user_factory("QA switch operator", role=UserRole.admin)
    passenger = user_factory()
    assert client.get("/admin/switches", headers=passenger["auth"]).status_code == 403
    assert client.post(SMS_PATH, headers=passenger["auth"], json={"value": False}).status_code == 403
    assert _state(client, admin) == {
        "stored": None, "effective": True, "blockers": [], "updated_at": None,
        "updated_by_name": None, "history": [], "in_flight": 0,
    }
    assert client.get("/config/login-methods").json()["methods"] == ["sms", "telegram"]
    denied = client.post("/admin/switches/login.telegram", headers=admin["auth"], json={"value": False})
    assert denied.status_code == 409 and denied.json()["detail"]["code"] == "switch_telegram_required"
    assert client.post("/admin/switches/unknown", headers=admin["auth"], json={"value": True}).status_code == 404
    for path in ("login.vk", "login.whatsapp"):
        denied = client.post(f"/admin/switches/{path}", headers=admin["auth"], json={"value": True})
        assert denied.status_code == 409 and denied.json()["detail"]["blockers"]
    for deployment in ("dev", "prod"):
        monkeypatch.setattr(settings, "env", deployment)
        monkeypatch.setattr(settings, "sms_provider", "smsru")
        monkeypatch.setattr(settings, "sms_ru_api_id", "synthetic-key")
        response = client.post(SMS_PATH, headers=admin["auth"], json={"value": True})
        assert response.status_code == 409
        assert response.json()["detail"]["blockers"] == ["sms_atomic_budget_pending"]
        assert client.get("/config/login-methods").json()["methods"] == ["telegram"]


def test_sms_can_be_disabled_with_confirmation_and_reenabled_without_restart(client, user_factory, caplog):
    admin = user_factory("QA switch author", role=UserRole.admin)
    phone = "+79990102031"
    with Session(engine) as session:
        session.add(User(phone=phone, name="SMS-only passenger"))
        session.commit()
    confirmed = {"value": False, "confirm": True, "note": "QA maintenance"}
    response = client.post(SMS_PATH, headers=admin["auth"], json={"value": False})
    assert response.status_code == 409 and response.json()["detail"]["code"] == "switch_needs_confirm"
    assert response.json()["detail"]["affected"] >= 1
    assert _state(client, admin)["history"] == []

    with caplog.at_level("INFO", logger="yuldash"):
        response = client.post(SMS_PATH, headers=admin["auth"], json=confirmed)
    assert response.status_code == 200 and "switch.update" in caplog.text
    expected = response.json()
    assert expected["stored"] is False and expected["effective"] is False
    assert expected["updated_by_name"] == "QA switch author"
    assert len(expected["history"]) == 1
    assert expected["history"][0]["note"] == "QA maintenance"
    assert client.post(SMS_PATH, headers=admin["auth"], json=confirmed).json() == expected
    assert client.get("/config/login-methods").json()["methods"] == ["telegram"]
    blocked = client.post("/auth/request-code", json={"phone": phone})
    assert blocked.status_code == 403 and blocked.json()["detail"]["code"] == "login_method_disabled"

    restored = client.post(SMS_PATH, headers=admin["auth"], json={"value": True})
    assert restored.status_code == 200 and restored.json()["effective"] is True
    assert [item["new_value"] for item in restored.json()["history"]] == [True, False]
    issued = client.post("/auth/request-code", json={"phone": phone})
    assert issued.status_code == 200
    assert client.post("/auth/verify", json={"phone": phone, "code": issued.json()["dev_code"]}).status_code == 200


def test_last_working_method_cannot_be_disabled(client, user_factory, monkeypatch):
    admin = user_factory(role=UserRole.admin)
    monkeypatch.setattr(settings, "telegram_bot_token", "")
    result = client.post(SMS_PATH, headers=admin["auth"], json={"value": False, "confirm": True})
    assert result.status_code == 409 and result.json()["detail"]["code"] == "switch_last_login_method"
    assert _state(client, admin)["effective"] is True


def test_repeat_stored_choice_keeps_history_when_channel_becomes_blocked(client, user_factory, monkeypatch):
    admin = user_factory(role=UserRole.admin)
    enabled = client.post(SMS_PATH, headers=admin["auth"], json={"value": True})
    assert enabled.status_code == 200 and enabled.json()["effective"] is True
    monkeypatch.setattr(settings, "sms_provider", "smsru")
    monkeypatch.setattr(settings, "sms_ru_api_id", "synthetic-key")
    repeated = client.post(SMS_PATH, headers=admin["auth"], json={"value": True})
    assert repeated.status_code == 200
    state = repeated.json()
    assert state["stored"] is True and state["effective"] is False
    assert state["blockers"] == ["sms_atomic_budget_pending"]
    assert state["history"] == enabled.json()["history"]


def test_setting_and_history_roll_back_together(client, user_factory, monkeypatch):
    admin = user_factory(role=UserRole.admin)
    original = admin_switches._switch_view

    def fail_after_flush(session, row):
        assert session.exec(select(AppSettingChange).where(AppSettingChange.key == SMS)).one().new_value is False
        raise RuntimeError("synthetic response assembly failure")

    with TestClient(app, raise_server_exceptions=False) as isolated:
        monkeypatch.setattr(admin_switches, "_switch_view", fail_after_flush)
        response = isolated.post(SMS_PATH, headers=admin["auth"], json={"value": False, "confirm": True})
        assert response.status_code == 500
    monkeypatch.setattr(admin_switches, "_switch_view", original)
    state = _state(client, admin)
    assert state["effective"] is True and state["stored"] is None and state["history"] == []


def test_disabled_sms_rejects_previously_issued_code_on_new_verify(client, user_factory):
    admin = user_factory(role=UserRole.admin)
    phone = "+79990102032"
    issued = client.post("/auth/request-code", json={"phone": phone})
    assert issued.status_code == 200
    assert client.post(SMS_PATH, headers=admin["auth"], json={"value": False, "confirm": True}).status_code == 200
    response = client.post("/auth/verify", json={"phone": phone, "code": issued.json()["dev_code"]})
    assert response.status_code == 403 and response.json()["detail"]["code"] == "login_method_disabled"


@PG_ONLY
@pytest.mark.parametrize("first", ["send", "off"])
def test_sms_issue_and_switch_off_share_row_locks(client, user_factory, monkeypatch, first):
    admin = user_factory(role=UserRole.admin)
    phone = "+79990102033" if first == "send" else "+79990102034"
    entered = {name: Event() for name in ("send", "off")}
    held, release = Event(), Event()
    active_pids, sent = {}, []
    raw_settings = login_methods.login_settings
    raw_view = admin_switches._switch_view
    reserve = auth.reserve_sms

    def track(name, session, lock):
        if lock:
            active_pids[name] = _pid(session)
            entered[name].set()
        return raw_settings(session, lock=lock)

    def synthetic_send(number, _code, *, lang, provider_key):
        assert number == phone
        sent.append(number)
        return SmsDelivery.SENT

    def hold_reservation(*args):
        admission = reserve(*args)
        if first == "send":
            held.set()
            assert release.wait(10)
        return admission

    def hold_off_commit(session, row):
        if first == "off":
            held.set()
            assert release.wait(10)
        return raw_view(session, row)

    monkeypatch.setattr(login_methods, "login_settings", lambda session, *, lock=None: track("send", session, lock))
    monkeypatch.setattr(admin_switches, "login_settings", lambda session, *, lock=None: track("off", session, lock))
    monkeypatch.setattr(auth, "send_sms", synthetic_send)
    monkeypatch.setattr(auth, "reserve_sms", hold_reservation)
    monkeypatch.setattr(admin_switches, "_switch_view", hold_off_commit)
    work = {
        "send": lambda: _post("POST", "/auth/request-code", body={"phone": phone}),
        "off": lambda: _post("POST", SMS_PATH, admin["auth"], {"value": False, "confirm": True}),
    }
    second = "off" if first == "send" else "send"
    with ThreadPoolExecutor(max_workers=2) as pool:
        responses = {first: pool.submit(work[first])}
        try:
            assert held.wait(8)
            responses[second] = pool.submit(work[second])
            assert entered[second].wait(8)
            _assert_blocked(active_pids[second], active_pids[first])
        finally:
            release.set()
        assert responses["off"].result(timeout=10).status_code == 200
        assert responses["send"].result(timeout=10).status_code == (200 if first == "send" else 403)
    assert sent == ([phone] if first == "send" else [])


@PG_ONLY
def test_sms_switch_off_completes_while_admitted_provider_is_in_flight(client, user_factory, monkeypatch):
    admin = user_factory(role=UserRole.admin)
    entered, release = Event(), Event()
    sent = []

    def synthetic_send(phone, _code, *, lang, provider_key):
        sent.append(phone)
        entered.set()
        assert release.wait(10)
        return SmsDelivery.SENT

    monkeypatch.setattr(auth, "send_sms", synthetic_send)
    phone = "+79990102039"
    with ThreadPoolExecutor(max_workers=2) as pool:
        issued = pool.submit(_post, "POST", "/auth/request-code", body={"phone": phone})
        try:
            assert entered.wait(8)
            switch = pool.submit(_post, "POST", SMS_PATH, admin["auth"], {"value": False, "confirm": True})
            disabled = switch.result(timeout=8)
            assert disabled.status_code == 200
            assert disabled.json()["effective"] is False and disabled.json()["in_flight"] == 1
            assert client.post("/auth/request-code", json={"phone": "+79990102040"}).status_code == 403
        finally:
            release.set()
        assert issued.result(timeout=8).status_code == 200
    assert sent == [phone] and _state(client, admin)["in_flight"] == 0


@PG_ONLY
def test_revoke_wins_before_operator_write_under_same_user_lock(client, user_factory, monkeypatch):
    admin = user_factory(role=UserRole.admin)
    tracking = Event()
    pids = {}
    original = admin_switches.lock_authenticated_user

    def observe(cred, session):
        pids["post"] = _pid(session)
        tracking.set()
        return original(cred, session)

    monkeypatch.setattr(admin_switches, "lock_authenticated_user", observe)
    with ThreadPoolExecutor(max_workers=1) as pool, Session(engine) as revoker:
        user = security.lock_refresh_user(revoker, admin["id"])
        user.tokens_valid_from = utcnow()
        security.revoke_all_refresh(revoker, user.id, commit=False)
        pending = pool.submit(_post, "POST", SMS_PATH, admin["auth"], {"value": False, "confirm": True})
        try:
            assert tracking.wait(8)
            _assert_blocked(pids["post"], _pid(revoker))
        finally:
            revoker.commit()
        assert pending.result(timeout=10).status_code == 401
    with Session(engine) as session:
        assert session.get(AppSetting, SMS).value is None
        assert not session.exec(select(AppSettingChange).where(AppSettingChange.key == SMS)).all()


@PG_ONLY
def test_author_deletion_waits_for_write_and_preserves_anonymous_history(client, user_factory, monkeypatch):
    admin = user_factory("QA switch author to delete", role=UserRole.admin)
    writing, deleting, release = Event(), Event(), Event()
    pids = {}
    original_view = admin_switches._switch_view
    original_auth = auth.lock_authenticated_user

    def hold_before_commit(session, row):
        pids["write"] = _pid(session)
        writing.set()
        assert release.wait(10)
        return original_view(session, row)

    def observe_delete(cred, session):
        pids["delete"] = _pid(session)
        deleting.set()
        return original_auth(cred, session)

    monkeypatch.setattr(admin_switches, "_switch_view", hold_before_commit)
    monkeypatch.setattr(auth, "lock_authenticated_user", observe_delete)
    with ThreadPoolExecutor(max_workers=2) as pool:
        write = pool.submit(_post, "POST", SMS_PATH, admin["auth"], {"value": False, "confirm": True})
        try:
            assert writing.wait(8)
            remove = pool.submit(_post, "POST", "/me/delete", admin["auth"])
            assert deleting.wait(8)
            _assert_blocked(pids["delete"], pids["write"])
        finally:
            release.set()
        assert write.result(timeout=10).status_code == 200
        assert remove.result(timeout=10).status_code == 200
    with Session(engine) as session:
        assert session.get(User, admin["id"]) is None
        assert session.get(AppSetting, SMS).updated_by is None
        change = session.exec(select(AppSettingChange).where(AppSettingChange.key == SMS)).one()
        assert change.new_value is False and change.admin_id is None


@PG_ONLY
def test_verify_admitted_before_off_can_complete_without_another_provider_call(client, user_factory, monkeypatch):
    admin = user_factory(role=UserRole.admin)
    phone = "+79990102036"
    with Session(engine) as session:
        session.add(OtpCode(phone=phone, code="450120", expires_at=utcnow() + timedelta(minutes=5)))
        session.commit()
    checked, release = Event(), Event()
    lock_phone = auth._lock_otp_phone

    def hold_after_availability(session, number):
        checked.set()
        assert release.wait(10)
        return lock_phone(session, number)

    monkeypatch.setattr(auth, "_lock_otp_phone", hold_after_availability)
    with ThreadPoolExecutor(max_workers=1) as pool:
        old = pool.submit(_post, "POST", "/auth/verify", body={"phone": phone, "code": "450120"})
        try:
            assert checked.wait(8)
            assert client.post(SMS_PATH, headers=admin["auth"], json={"value": False, "confirm": True}).status_code == 200
            response = client.post("/auth/verify", json={"phone": phone, "code": "450120"})
            assert response.status_code == 403 and response.json()["detail"]["code"] == "login_method_disabled"
        finally:
            release.set()
        assert old.result(timeout=10).status_code == 200
