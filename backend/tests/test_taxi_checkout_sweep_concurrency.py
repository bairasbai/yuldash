"""Exercise the sweep's transaction boundaries on SQLite and real PostgreSQL."""
from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta
from threading import Barrier
from uuid import uuid4

import pytest
from sqlalchemy import event, text, update
from sqlmodel import Session, select

from app import taxi_checkout_sweep as sweep
from app.db import engine
from app.models import InstantOrder, LedgerEntry, LedgerKind, Payment, UserRole
from app.routers import payments
from app.timeutil import utcnow
from test_money_holes_audit import _order


def _fixture(user_factory):
    driver = user_factory("SweepConcurrencyDriver", role=UserRole.driver)
    passenger = user_factory("SweepConcurrencyPassenger")
    oid = _order(passenger["id"], driver["id"], price=600)
    # Far enough in the past that unrelated cases in the shared test database are not due.
    now = utcnow() - timedelta(days=90)
    with Session(engine) as session:
        attempt = Payment(user_id=passenger["id"], order_id=oid, purpose="ride", status="pending",
                          provider_id=str(uuid4()), method="yookassa", created_at=now - timedelta(days=1),
                          amount_kop=60000)
        session.add(attempt)
        session.commit()
        return oid, attempt.id, attempt.provider_id, now


def test_concurrent_claims_on_distinct_connections_only_grant_one_lease(user_factory):
    _oid, payment_id, _provider_id, now = _fixture(user_factory)
    selected = Barrier(2)
    connections = set()

    def align_two_selected_candidates(conn, cursor, statement, parameters, context, executemany):
        if statement.lstrip().upper().startswith("UPDATE PAYMENT SET TAXI_RECONCILE_AFTER"):
            connections.add(id(conn.connection.driver_connection))
            selected.wait(timeout=6)

    event.listen(engine, "before_cursor_execute", align_two_selected_candidates)
    try:
        with ThreadPoolExecutor(max_workers=2) as executor:
            requests = [executor.submit(sweep._claim, engine, now=now, known=True, shop="fixture")
                        for _ in range(2)]
            results = [request.result(timeout=15) for request in requests]
    finally:
        event.remove(engine, "before_cursor_execute", align_two_selected_candidates)
    assert len(connections) == 2
    assert results.count(payment_id) == results.count(None) == 1

    with Session(engine) as session:
        attempt = session.get(Payment, payment_id)
        assert attempt.taxi_reconcile_after == now + timedelta(minutes=5)
        attempt.status = "canceled"  # do not leave this synthetic attempt eligible in later tests
        session.commit()


def test_bank_read_does_not_lock_payment_and_old_provider_snapshot_cannot_settle(
    user_factory, monkeypatch,
):
    oid, payment_id, first_provider, _now = _fixture(user_factory)
    new_provider = str(uuid4())
    calls = []

    def replace_bank_id():
        # A separate transaction must be able to write while the worker is at the bank.
        with Session(engine) as s:
            if engine.dialect.name == "postgresql":
                s.execute(text("SET LOCAL lock_timeout = '2s'"))
            else:
                s.execute(text("PRAGMA busy_timeout = 2000"))
            changed = s.execute(update(Payment).where(Payment.id == payment_id)
                                .values(provider_id=new_provider))
            assert changed.rowcount == 1
            s.commit()

    def signed_read(provider_id, pid, amount, shop):
        assert (pid, amount, shop) == (payment_id, 60000, "fixture")
        calls.append(provider_id)
        if provider_id == first_provider:
            with ThreadPoolExecutor(max_workers=1) as executor:
                executor.submit(replace_bank_id).result(timeout=4)
        return {"status": "succeeded"}

    # Preserve a diagnostic failure if the fixture cannot race, even though production
    # deliberately absorbs transient provider read errors.
    monkeypatch.setattr(sweep, "fetch_taxi_payment_status", signed_read)
    sweep._poll_known(engine, payment_id, "fixture")
    with Session(engine) as s:
        p = s.get(Payment, payment_id)
        assert p.provider_id == new_provider and p.status == "pending"
        assert not s.get(InstantOrder, oid).paid
        assert s.exec(select(LedgerEntry.id).where(LedgerEntry.order_id == oid)).first() is None

    sweep._poll_known(engine, payment_id, "fixture")
    sweep._poll_known(engine, payment_id, "fixture")
    assert calls == [first_provider, new_provider]
    with Session(engine) as s:
        assert s.get(Payment, payment_id).status == "succeeded"
        assert s.get(InstantOrder, oid).paid
        rows = s.exec(select(LedgerEntry.amount_kop).where(
            LedgerEntry.order_id == oid, LedgerEntry.kind == LedgerKind.earn,
        )).all()
        assert rows == [60000]


@pytest.mark.skipif(engine.dialect.name != "postgresql", reason="requires real PostgreSQL row lock for this race")
def test_webhook_and_background_race_both_read_pending_but_credit_once(user_factory, monkeypatch):
    oid, payment_id, provider_id, _now = _fixture(user_factory)
    signed_reads = Barrier(2)

    def both_fetch(provider):
        assert provider == provider_id
        signed_reads.wait(timeout=5)
        return {"status": "succeeded"}

    monkeypatch.setattr(sweep, "fetch_taxi_payment_status", lambda provider, *_: both_fetch(provider))
    monkeypatch.setattr(payments, "fetch_payment", both_fetch)

    def webhook():
        with Session(engine) as s:
            s.execute(text("SET LOCAL lock_timeout = '4s'"))
            payments._process_yookassa_webhook(s, provider_id)

    with ThreadPoolExecutor(max_workers=2) as executor:
        pending = [executor.submit(webhook), executor.submit(sweep._poll_known, engine, payment_id, "fixture")]
        for result in pending:
            result.result(timeout=12)
    with Session(engine) as s:
        assert s.get(Payment, payment_id).status == "succeeded"
        assert s.get(InstantOrder, oid).paid
        rows = s.exec(select(LedgerEntry).where(LedgerEntry.order_id == oid)).all()
        assert [r.amount_kop for r in rows if r.kind == LedgerKind.earn] == [60000]
        assert len([r for r in rows if r.kind == LedgerKind.fee]) <= 1
