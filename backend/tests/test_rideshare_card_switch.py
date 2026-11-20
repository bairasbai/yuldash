"""The card scaffold exposes readiness while the existing money boundary stays closed."""

from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta
from threading import Event

import pytest
from alembic import command
from fastapi.testclient import TestClient
from sqlalchemy import delete, inspect, text
from sqlmodel import Session, select

from app.card_payments import BANK_PROJECT_UNACCEPTED, CARD_PAYMENTS_SWITCH
from app.config import settings
from app.db import engine, init_db
from app.main import app
from app.models import AppSetting, AppSettingChange, Booking, BookingStatus, DriverPaySettings, Payment, User, UserRole
from app.owner_switches import PAID_SWITCH
from app.routers import admin_switches, wallet
from app.security import issue_tokens
from app.timeutil import utcnow
from test_last_seat_race import _ride
from test_migration_owner_switches import _isolated_database


SWITCH_PATH = f"/admin/switches/{CARD_PAYMENTS_SWITCH}"
BLOCKER = BANK_PROJECT_UNACCEPTED


@pytest.fixture
def card_owner(user_factory, set_paid_owner, telegram_login_ready):
    owner = user_factory("Synthetic bank operator", role=UserRole.admin)
    set_paid_owner(owner)
    def reset(session):
        session.execute(delete(AppSettingChange).where(AppSettingChange.key == CARD_PAYMENTS_SWITCH))
        card = session.get(AppSetting, CARD_PAYMENTS_SWITCH)
        card.value = card.updated_at = card.updated_by = None

    with Session(engine) as session:
        previously_paid = session.get(AppSetting, PAID_SWITCH).value
        reset(session)
        session.commit()
    yield owner
    with Session(engine) as session:
        reset(session)
        session.get(AppSetting, PAID_SWITCH).value = previously_paid
        session.commit()


def _card_view(client, owner):
    reply = client.get("/admin/switches", headers=owner["auth"])
    assert reply.status_code == 200, reply.text
    return reply.json()["switches"][CARD_PAYMENTS_SWITCH]


def _write(client, user, value, revision=0):
    return client.post(SWITCH_PATH, headers=user["auth"], json={"value": value, "expected_revision": revision})


def test_only_current_telegram_owner_can_see_or_clear_the_card_switch(client, card_owner, user_factory):
    admin = user_factory("Other synthetic operator", role=UserRole.admin)
    passenger = user_factory("Synthetic passenger")
    assert CARD_PAYMENTS_SWITCH not in client.get("/admin/switches", headers=admin["auth"]).json()["switches"]
    assert _write(client, admin, False).status_code == 403
    assert _write(client, passenger, False).status_code == 403
    view = _card_view(client, card_owner)
    assert view["stored"] is None and view["effective"] is False
    assert view["revision"] == 0 and not view["history"]
    assert len(view["blockers"]) == 1
    blocker, = view["blockers"]
    assert blocker["code"] == BLOCKER and blocker["ru"] and blocker["ba"]
    denied = _write(client, card_owner, True)
    assert denied.status_code == 409 and denied.json()["detail"]["code"] == BLOCKER
    assert _card_view(client, card_owner) == view


@pytest.mark.parametrize("method", ["legacy", "sms"])
def test_pre_promotion_sessions_cannot_read_or_write_the_card_switch(client, card_owner, method):
    with Session(engine) as session:
        owner = session.get(User, card_owner["id"])
        owner.role = UserRole.passenger
        session.commit()
        weak = issue_tokens(session, owner.id, auth_method=method, auth_time=utcnow() if method == "sms" else None)
        owner.role = UserRole.admin
        session.commit()
    earlier = _card_view(client, card_owner)
    weak_owner = {"auth": {"Authorization": f"Bearer {weak['access_token']}"}}
    assert client.get("/admin/switches", headers=weak_owner["auth"]).status_code in (401, 403)
    assert _write(client, weak_owner, False).status_code in (401, 403)
    assert client.post("/auth/refresh", json={"refresh_token": weak["refresh_token"]}).status_code == 401
    assert _card_view(client, card_owner) == earlier


def test_stored_true_is_ineffective_and_cleared_only_at_current_revision(client, card_owner):
    with Session(engine) as session:
        session.get(AppSetting, CARD_PAYMENTS_SWITCH).value = True
        previous_change = AppSettingChange(key=CARD_PAYMENTS_SWITCH, new_value=True, admin_id=card_owner["id"])
        session.add(previous_change)
        session.commit()
        revision = previous_change.id
    current = _card_view(client, card_owner)
    assert current["stored"] and not current["effective"] and current["revision"] == revision
    denied = _write(client, card_owner, True, revision)
    assert denied.status_code == 409 and denied.json()["detail"]["code"] == BLOCKER
    stale = _write(client, card_owner, False, revision - 1)
    assert stale.status_code == 409 and stale.json()["detail"]["code"] == "switch_revision_conflict"
    assert _card_view(client, card_owner) == current
    changed = _write(client, card_owner, False, revision)
    assert changed.status_code == 200
    cleared = changed.json()
    assert cleared["stored"] is cleared["effective"] is False
    assert cleared["revision"] > revision
    assert [entry["new_value"] for entry in cleared["history"]] == [False, True]
    assert _write(client, card_owner, False, revision).json() == cleared


def test_card_switch_and_its_history_roll_back_together(client, card_owner, monkeypatch):
    with Session(engine) as session:
        session.get(AppSetting, CARD_PAYMENTS_SWITCH).value = True
        session.commit()
    previous = _card_view(client, card_owner)

    def fail_after_flush(session, row, **_kwargs):
        assert row.key == CARD_PAYMENTS_SWITCH and row.value is False
        change = session.exec(select(AppSettingChange).where(AppSettingChange.key == row.key)).one()
        assert change.new_value is False
        raise RuntimeError("synthetic failure before commit")

    with monkeypatch.context() as patch, TestClient(app, raise_server_exceptions=False) as requester:
        patch.setattr(admin_switches, "_switch_view", fail_after_flush)
        assert _write(requester, card_owner, False).status_code == 500

    assert _card_view(client, card_owner) == previous


def test_repeated_db_initialization_preserves_card_switch_and_its_history(client, card_owner):
    with Session(engine) as session:
        session.get(AppSetting, CARD_PAYMENTS_SWITCH).value = True
        session.commit()
    changed = _write(client, card_owner, False)
    assert changed.status_code == 200
    before = changed.json()
    assert before["stored"] is before["effective"] is False
    assert before["revision"] > 0 and len(before["history"]) == 1

    init_db()
    init_db()

    assert _card_view(client, card_owner) == before


def test_driver_can_clear_stored_card_consent_with_unchanged_methods(client, user_factory):
    driver = user_factory("Synthetic driver", role=UserRole.driver)
    with Session(engine) as session:
        session.add(DriverPaySettings(user_id=driver["id"], methods="cash", accepts_card=True, accepts_card_at=utcnow()))
        session.commit()
    url = "/driver/pay-settings"
    initial = client.get(url, headers=driver["auth"]).json()
    assert initial["methods"] == ["cash"] and initial["accepts_card"] is initial["card_switch_on"] is False
    assert initial["card_blockers"][0]["code"] == BLOCKER
    same = {"methods": ["cash"], "expected_updated_at": initial["updated_at"]}
    enable = client.put(url, headers=driver["auth"], json={**same, "accepts_card": True})
    assert enable.status_code == 409 and enable.json()["detail"]["code"] == BLOCKER
    stale = client.put(url, headers=driver["auth"], json={**same, "expected_updated_at": None, "accepts_card": False})
    assert stale.status_code == 409 and stale.json()["detail"]["code"] == "pay_settings_changed"
    cleared = client.put(url, headers=driver["auth"], json={**same, "accepts_card": False})
    assert cleared.status_code == 200
    assert cleared.json()["methods"] == ["cash"] and cleared.json()["updated_at"] != initial["updated_at"]
    with Session(engine) as session:
        stored = session.exec(select(DriverPaySettings).where(DriverPaySettings.user_id == driver["id"])).one()
        assert stored.accepts_card is False and stored.accepts_card_at is None


def test_card_and_bank_sbp_always_refuse_even_for_paid_replay_and_during_off(client, user_factory, card_owner, monkeypatch):
    driver = user_factory("Synthetic ride driver", role=UserRole.driver)
    passenger = user_factory("Synthetic rider")
    stranger = user_factory("Synthetic outsider")
    ride_id = _ride(client, driver)
    booking = client.post("/bookings", headers=passenger["auth"], json={"ride_id": ride_id}).json()
    booking_id = booking["id"]
    with Session(engine) as session:
        session.get(AppSetting, CARD_PAYMENTS_SWITCH).value = True
        session.get(AppSetting, PAID_SWITCH).value = True
        session.add(DriverPaySettings(user_id=driver["id"], methods="cash", accepts_card=True))
        row = session.get(Booking, booking_id)
        row.status = BookingStatus.done
        row.paid = True
        row.payment_method = "yookassa"
        session.commit()
    monkeypatch.setattr(settings, "payments_provider", "yookassa")
    monkeypatch.setattr(settings, "yookassa_shop_id", "synthetic-shop")
    monkeypatch.setattr(settings, "yookassa_secret_key", "synthetic-key")
    monkeypatch.setattr(settings, "payouts_enabled", True)

    def forbid(*_args, **_kw):
        pytest.fail("The card scaffold reached the cashless write or provider path")

    monkeypatch.setattr(wallet, "_pay_cashless", forbid)
    monkeypatch.setattr(wallet, "_start_yookassa", forbid)
    pay_url = f"/bookings/{booking_id}/pay"

    def check_closed():
        for method in ("card", "sbp"):
            reply = client.post(pay_url, headers=passenger["auth"], json={"method": method})
            assert reply.status_code == 503 and reply.json()["detail"]["code"] == "booking_online_pay_off"

    assert client.post(pay_url, headers=stranger["auth"], json={"method": "card"}).status_code == 403
    for url in (f"/rides/{ride_id}", f"/bookings/{booking_id}/details", f"/trips/{booking_id}/receipt"):
        reply = client.get(url, headers=passenger["auth"])
        assert reply.status_code == 200, reply.text
        assert reply.json()["card_payment"] is False
    check_closed()
    prepared, release = Event(), Event()
    original_view = admin_switches._switch_view

    def hold_before_commit(session, row, **kwargs):
        assert row.key == CARD_PAYMENTS_SWITCH and row.value is False
        prepared.set()
        assert release.wait(12)
        return original_view(session, row, **kwargs)

    monkeypatch.setattr(admin_switches, "_switch_view", hold_before_commit)
    with TestClient(app) as stopping, ThreadPoolExecutor(max_workers=1) as executor:
        attempt = executor.submit(_write, stopping, card_owner, False)
        try:
            assert prepared.wait(10)
            check_closed()
        finally:
            release.set()
        reply = attempt.result(timeout=10)
    assert reply.status_code == 200 and reply.json()["stored"] is False
    assert [entry["new_value"] for entry in reply.json()["history"]] == [False]
    check_closed()
    with Session(engine) as session:
        assert session.exec(select(Payment).where(Payment.booking_id == booking_id)).all() == []


def test_card_migration_retains_manual_settings_and_audit_history(tmp_path, monkeypatch):
    db, config = _isolated_database(tmp_path, monkeypatch, "YULDASH_CARD_SWITCH_MIGRATION_DB", "card-switch.sqlite3")
    previous, target = "cr_driver_pay_settings", "cs_card_payment_switch"
    try:
        command.upgrade(config, previous)
        # The initial migration can build fresh metadata; restore the two old columns'
        # absence so the test exercises the actual update from the previous revision.
        names = {column["name"] for column in inspect(db).get_columns("driverpaysettings")}
        with db.begin() as connection:
            for name in ("accepts_card_at", "accepts_card"):
                if name in names:
                    connection.execute(text(f"ALTER TABLE driverpaysettings DROP COLUMN {name}"))
            connection.execute(text("DELETE FROM appsetting WHERE key=:key"), {"key": CARD_PAYMENTS_SWITCH})
        with Session(db) as session:
            user = User(phone="+79990001072", role=UserRole.driver)
            session.add(user)
            session.flush()
            user_id = user.id
            session.execute(text("INSERT INTO driverpaysettings (user_id, methods, updated_at) VALUES (:user, 'sbp', :updated)"),
                            {"user": user_id, "updated": utcnow() - timedelta(days=1)})
            session.commit()
        command.upgrade(config, target)
        with Session(db) as session:
            row = session.exec(select(DriverPaySettings).where(DriverPaySettings.user_id == user_id)).one()
            assert row.methods == "sbp" and row.accepts_card is False and row.accepts_card_at is None
            setting = session.get(AppSetting, CARD_PAYMENTS_SWITCH)
            assert setting.value is False
            setting.value = True
            change = AppSettingChange(key=CARD_PAYMENTS_SWITCH, new_value=True)
            session.add(change)
            session.commit()
            old_revision = change.id
        command.downgrade(config, previous)
        assert "accepts_card" not in {column["name"] for column in inspect(db).get_columns("driverpaysettings")}
        with Session(db) as session:
            dormant = session.get(AppSetting, CARD_PAYMENTS_SWITCH)
            assert dormant.value is False and dormant.updated_by is None and dormant.updated_at is None
        command.upgrade(config, target)
        with Session(db) as session:
            row = session.exec(select(DriverPaySettings).where(DriverPaySettings.user_id == user_id)).one()
            assert row.methods == "sbp" and not row.accepts_card
            assert session.get(AppSetting, CARD_PAYMENTS_SWITCH).value is False
            history = session.exec(select(AppSettingChange).where(AppSettingChange.key == CARD_PAYMENTS_SWITCH)
                                   .order_by(AppSettingChange.id)).all()
            assert [change.new_value for change in history] == [True, False]
            assert history[-1].id > old_revision
    finally:
        db.dispose()
