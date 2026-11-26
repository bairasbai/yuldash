"""Синтетические сценарии сверки водительской отмены без повторного действия."""
from datetime import timedelta

import pytest
from sqlmodel import Session

from app import instant_service as isv
from app.db import engine
from app.models import TaxiDriverCancelReceipt
from app.timeutil import utcnow
from test_dropped_order_keeps_looking import _accepted, _события
from test_money_rules import _db_order, _driver_online, _heartbeat, _shift, fake_redis  # noqa: F401

COMMAND = "a" * 32


def _detail(client, rider, order_id):
    return client.get(f"/instant/orders/{order_id}", headers=rider["auth"]).json()


def _body(client, driver, order_id, reason="driver_cancel"):
    return {
        "command_id": COMMAND,
        "assignment_revision": _detail(client, driver, order_id)["driver_assignment_revision"],
        "reason": reason,
    }


def _submit(client, driver, order_id, body):
    return client.post(f"/instant/orders/{order_id}/driver-cancel-command",
                       headers=driver["auth"], json=body)


def _read(client, user, order_id):
    res = client.get(f"/instant/orders/{order_id}/cancel-receipts/{COMMAND}", headers=user["auth"])
    assert res.status_code == 200, res.text
    assert res.headers["cache-control"] == "no-store"
    return res.json()


def test_lost_response_can_be_recovered_after_commit_without_resending(
    client, user_factory, fake_redis, monkeypatch,
):
    driver, pax, oid = _accepted(client, user_factory, "ReceiptDriver", "ReceiptPassenger")
    body = _body(client, driver, oid)

    def disconnected_after_commit(*_):
        raise ConnectionError("Fixture: inline continuation lost after commit")

    with monkeypatch.context() as m:
        m.setattr(isv, "_continue_after_driver_cancel", disconnected_after_commit)
        with pytest.raises(ConnectionError):
            _submit(client, driver, oid, body)
    found = _read(client, driver, oid)
    assert found == {
        "state": "applied", "order_id": oid, "command_id": COMMAND,
        "assignment_revision": body["assignment_revision"], "outcome": "released",
        "cancel_fee_kop": 0,
    }
    assert _read(client, pax, oid) == {"state": "unconfirmed"}
    assert _submit(client, driver, oid, body).json() == found
    conflict = _submit(client, driver, oid, {**body, "reason": "no_show"})
    assert conflict.status_code == 409
    assert conflict.json()["detail"]["code"] == "taxi_driver_cancel_command_conflict"
    assert len(_события(driver["id"])) == 1
    assert _db_order(oid).status == isv.S.searching


def test_read_does_not_mutate_expired_replacement_offer(client, user_factory, fake_redis, monkeypatch):
    first, pax, oid = _accepted(client, user_factory, "FirstReceiptDriver", "ReturningPassenger")
    second = _driver_online(client, user_factory, "SecondReceiptDriver")
    _heartbeat(client, second)
    first_body = _body(client, first, oid)
    assert _submit(client, first, oid, first_body).status_code == 200
    assert client.post(f"/instant/orders/{oid}/accept", headers=second["auth"]).status_code == 200
    assert _submit(client, second, oid, _body(client, second, oid)).status_code == 200
    _shift(oid, offer_expires_at=utcnow() - timedelta(seconds=2))
    before = _db_order(oid)
    assert before.status == isv.S.offered and before.current_offer_driver_id == first["id"]

    def no_mutation(*_):
        pytest.fail("Receipt GET must not run the matcher")
    with monkeypatch.context() as m:
        m.setattr(isv, "reconcile_offer", no_mutation)
        assert _read(client, first, oid)["assignment_revision"] == first_body["assignment_revision"]
        assert _read(client, pax, oid) == {"state": "unconfirmed"}
    after = _db_order(oid)
    assert (after.status, after.current_offer_driver_id, after.offer_expires_at, after.search_round) == (
        before.status, before.current_offer_driver_id, before.offer_expires_at, before.search_round,
    )


def test_pending_read_never_claims_passenger_cancel_as_driver_action(client, user_factory, fake_redis):
    driver, pax, oid = _accepted(client, user_factory, "NoSendDriver", "CancellingPassenger")
    assert _read(client, driver, oid) == {"state": "unconfirmed"}
    body = _body(client, driver, oid)
    assert client.post(f"/instant/orders/{oid}/cancel", headers=pax["auth"], json={}).status_code == 200
    assert _read(client, driver, oid) == {"state": "unconfirmed"}
    assert _submit(client, driver, oid, body).status_code == 409
    assert not _события(driver["id"])


def test_no_show_receipt_keeps_original_fee_and_survives_repeated_reads(client, user_factory, fake_redis):
    driver, pax, oid = _accepted(client, user_factory, "NoShowReceiptDriver", "NoShowReceiptPassenger")
    assert client.post(f"/instant/orders/{oid}/arrived", headers=driver["auth"]).status_code == 200
    _shift(oid, waiting_started_at=utcnow() - timedelta(minutes=30),
           arriving_at=utcnow() - timedelta(minutes=30))
    body = _body(client, driver, oid, "no_show")
    result = _submit(client, driver, oid, body)
    assert result.status_code == 200, result.text
    expected = result.json()
    assert expected["outcome"] == "no_show" and expected["cancel_fee_kop"] > 0
    assert expected["cancel_fee_kop"] == _db_order(oid).cancel_fee_kop
    assert _read(client, driver, oid) == expected
    assert _submit(client, driver, oid, body).json() == expected
    assert _read(client, pax, oid) == {"state": "unconfirmed"}
    assert len(_события(driver["id"])) == 1


def test_cancel_receipt_and_strike_rollback_with_order(client, user_factory, fake_redis, monkeypatch):
    driver, _, oid = _accepted(client, user_factory, "AtomicReceiptDriver", "AtomicReceiptPassenger")
    body = _body(client, driver, oid)
    original = Session.add

    def fail_receipt(self, item, *args, **kwargs):
        if isinstance(item, TaxiDriverCancelReceipt):
            raise RuntimeError("Fixture: receipt insert cannot run")
        return original(self, item, *args, **kwargs)

    with monkeypatch.context() as m:
        m.setattr(Session, "add", fail_receipt)
        with pytest.raises(RuntimeError, match="receipt insert"):
            _submit(client, driver, oid, body)
    assert _read(client, driver, oid) == {"state": "unconfirmed"}
    assert _db_order(oid).status == isv.S.accepted
    assert not _события(driver["id"])


@pytest.mark.parametrize("deleted", ["driver", "passenger"])
def test_cancel_receipt_deleted_with_either_party(client, user_factory, fake_redis, deleted):
    from app.account import delete_user_account
    from app.models import User

    driver, pax, oid = _accepted(client, user_factory, "DeleteReceiptDriver", "DeleteReceiptPassenger")
    body = _body(client, driver, oid)
    assert _submit(client, driver, oid, body).status_code == 200
    with Session(engine) as session:
        key = (oid, driver["id"], COMMAND)
        assert session.get(TaxiDriverCancelReceipt, key) is not None
        account = driver if deleted == "driver" else pax
        delete_user_account(session, session.get(User, account["id"]))
    with Session(engine) as session:
        assert session.get(TaxiDriverCancelReceipt, key) is None


def test_cancel_receipt_migration_round_trip(tmp_path, monkeypatch):
    from alembic import command
    from sqlalchemy import inspect
    from sqlmodel import create_engine
    from test_migrations_match_the_models import _config

    url = f"sqlite:///{(tmp_path / 'driver-cancel-receipt.sqlite3').as_posix()}"
    monkeypatch.setenv("DATABASE_URL", url)
    db = create_engine(url)
    cfg = _config(url)
    previous = "db_chat_message_receipt"
    current = "dc_taxi_driver_cancel_receipt"
    table = "taxidrivercancelreceipt"
    try:
        command.upgrade(cfg, current)
        command.downgrade(cfg, previous)
        assert not inspect(db).has_table(table)
        command.upgrade(cfg, current)
        schema = inspect(db)
        assert schema.get_pk_constraint(table)["constrained_columns"] == ["order_id", "driver_id", "command_id"]
        assert {key["referred_table"]: key["options"]["ondelete"] for key in schema.get_foreign_keys(table)} == {
            "instantorder": "CASCADE", "user": "CASCADE",
        }
        assert {c["name"] for c in schema.get_check_constraints(table)} == {"ck_taxi_driver_cancel_receipt_outcome"}
    finally:
        db.dispose()


@pytest.mark.skipif(engine.dialect.name != "postgresql", reason="Требуются блокировки PostgreSQL")
@pytest.mark.timeout(60)
def test_pg_two_posts_for_same_command_get_one_effect(client, user_factory, fake_redis):
    from concurrent.futures import ThreadPoolExecutor
    from threading import Barrier

    driver, _, oid = _accepted(client, user_factory, "ConcurrentReceiptDriver", "ConcurrentReceiptPassenger")
    assert client.post(f"/instant/orders/{oid}/arrived", headers=driver["auth"]).status_code == 200
    _shift(oid, waiting_started_at=utcnow() - timedelta(minutes=30),
           arriving_at=utcnow() - timedelta(minutes=30))
    body = _body(client, driver, oid, "no_show")
    start = Barrier(2)

    def submit():
        start.wait(timeout=10)
        return _submit(client, driver, oid, body)

    with ThreadPoolExecutor(max_workers=2) as workers:
        pending = [workers.submit(submit), workers.submit(submit)]
        replies = [reply.result(timeout=25) for reply in pending]
    assert all(reply.status_code == 200 for reply in replies), [reply.text for reply in replies]
    assert replies[0].json() == replies[1].json() == _read(client, driver, oid)
    assert len(_события(driver["id"])) == 1
