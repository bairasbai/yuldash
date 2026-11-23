"""Inert sealed assignment transaction; synthetic SQLite only."""
from concurrent.futures import ThreadPoolExecutor
import sqlite3
from threading import Event

from fastapi import HTTPException
import pytest
from sqlalchemy import event, insert, select
from sqlmodel import Session

from app import instant_service as isv, taxi_route_resume as resume
from app import taxi_sealed_offer_accept as apply
from app.models import InstantOrder, InstantOrderStatus as S, LedgerEntry, LedgerKind, Payment
from app.timeutil import utcnow
from test_taxi_offered_waypoint_preview import _mutate, _row, _unexpected
from test_taxi_offered_waypoint_retire import _clock, _wait_db, healthy, route, testdb  # noqa: F401
from test_taxi_sealed_offer_card import _bind, offered  # noqa: F401
from test_taxi_sealed_resume_claim import near_deadline, retired  # noqa: F401
from test_taxi_sealed_resume_offer import _claim, _store

_OK = apply.SealedAcceptance.ACCEPTED
_UNKNOWN = apply.SealedAcceptance.UNKNOWN


def _run(data, proof, **overrides):
    db, owner, oid, driver, args, _ = data
    p = dict(owner_id=owner, order_id=oid, driver_id=driver)
    return apply.accept_sealed_offer(db, args["key"], proof, **(p | overrides))


def _as_dict(data):
    db, _, oid, *_ = data
    return dict(_row(db, oid)._mapping)


def _active(db, driver):
    o = InstantOrder.__table__.c
    with db.connect() as conn:
        return set(conn.scalars(select(o.id).where(
            o.driver_id == driver, o.status.in_((S.accepted, S.arriving, S.onboard)),
        )))


def _ordinary_order(data):
    db, _, oid, _, _, initial = data
    other_passenger = resume._state(initial)["route_resume_previous_driver_id"]
    with Session(db) as s:
        source = s.get(InstantOrder, oid)
        captured = {n: getattr(source, n) for n in isv._ACCEPT_OFFER_FIELDS if n not in ("id", "passenger_id")}
        instant = InstantOrder(**captured, passenger_id=other_passenger, created_at=source.created_at)
        s.add(instant)
        s.flush()
        result = instant.id
        s.commit()
    return result


def test_actual_inert_accept_changes_only_assignment_and_marker(request, monkeypatch):
    data = request.getfixturevalue("offered")
    db, _, oid, driver, _, _ = data
    proof = _bind(data)
    assert proof
    before = _as_dict(data)
    for name in ("_settle_pickup_fee", "_notify_pickup_fee", "_notify_transition", "_cleanup_tried", "_push_offer"):
        monkeypatch.setattr(isv, name, _unexpected)
    assert _run(data, proof) is _OK
    after = _as_dict(data)
    assert after == before | dict(
        status=S.accepted, driver_id=driver, accepted_at=after["accepted_at"],
        current_offer_driver_id=None, offer_expires_at=None, **dict.fromkeys(resume._MARKERS),
    )
    assert before["offered_at"] <= after["accepted_at"] < before["offer_expires_at"]
    assert after["accepted_at"] < before["route_resume_deadline_at"]
    assert _active(db, driver) == {oid}
    assert _run(data, proof) is _UNKNOWN
    assert _as_dict(data) == after


@pytest.mark.parametrize("change", ("route", "fare", "nonce", "wrong_actor", "former_actor", "legacy_card",
                                     "paid", "payment", "ledger", "cancel"))
def test_evidence_and_actual_state_changes_refuse_without_assignment(request, change):
    data = request.getfixturevalue("offered")
    db, owner, oid, driver, _, _ = data
    proof = _bind(data)
    assert proof
    before = _as_dict(data)
    override = {}
    if change == "wrong_actor":
        override = {"driver_id": owner}
    elif change == "former_actor":
        override = {"driver_id": before["route_resume_previous_driver_id"]}
    elif change == "legacy_card":
        proof = isv.offer_card_precondition(tuple(before[n] for n in isv._ACCEPT_OFFER_FIELDS),
                                            None, driver, utcnow())
        assert proof
    elif change == "payment":
        with db.begin() as c:
            c.execute(insert(Payment).values(user_id=owner, order_id=oid, purpose="ride", status="pending"))
    elif change == "ledger":
        with db.begin() as c:
            c.execute(insert(LedgerEntry).values(driver_id=driver, order_id=oid, kind=LedgerKind.earn))
    else:
        edits = {
            "route": {"from_text": "SYNTHETIC DIFFERENT ORIGIN"},
            "fare": {"ride_price": before["ride_price"]+10, "price_estimate": before["price_estimate"]+10},
            "nonce": {"route_resume_claim_id": "e"*32}, "paid": {"paid": True},
            "cancel": {"status": S.cancelled, "cancelled_at": utcnow()},
        }[change]
        _mutate(db, oid, **edits)
    changed = _as_dict(data)
    assert _run(data, proof, **override) is _UNKNOWN and _as_dict(data) == changed
    assert _active(db, driver) == set()


@pytest.mark.parametrize("winner", ("sealed", "ordinary"))
def test_separate_real_sqlite_writers_compete_with_actual_ordinary_transition(request, monkeypatch, winner):
    ordinary_pickup = isv._settle_pickup_fee
    data = request.getfixturevalue("offered")
    db, _, oid, driver, _, _ = data
    ordinary = _ordinary_order(data)
    before_ordinary = _row(db, ordinary)
    assert before_ordinary.pickup_pending is False
    monkeypatch.setattr(isv, "_settle_pickup_fee", ordinary_pickup)
    proof = _bind(data)
    assert proof
    for name in ("_notify_pickup_fee", "_notify_transition", "_cleanup_tried"):
        monkeypatch.setattr(isv, name, lambda *_, **__: None)
    seen = Event()
    release = Event()
    ordinary_entered = Event()
    conns = []

    def actual_ordinary():
        with Session(db) as s:
            row = s.get(InstantOrder, ordinary, populate_existing=True)
            expected = isv.capture_offer_for_accept(row)
            try:
                return isv.transition(s, ordinary, isv.Actor.driver, S.accepted, driver,
                                      idempotent=False, expected_offer=expected).status
            except HTTPException as err:
                return err.status_code

    def witness(conn, cursor, statement, parameters, context, executemany):
        if statement.startswith("UPDATE instantorder SET"):
            if "route_resume_agreement_seal=" in statement:
                conns.append(conn.connection.driver_connection)
                seen.set()
                assert release.wait(5)
            else:
                conns.append(conn.connection.driver_connection)
                ordinary_entered.set()

    if winner == "ordinary":
        assert actual_ordinary() is S.accepted
        assert _run(data, proof) is _UNKNOWN
        assert _active(db, driver) == {ordinary} and _as_dict(data)["status"] is S.offered
        return
    event.listen(db, "before_cursor_execute", witness)
    try:
        with ThreadPoolExecutor(max_workers=2) as workers:
            protected = workers.submit(_run, data, proof)
            try:
                assert seen.wait(4)
                old = workers.submit(actual_ordinary)
                assert ordinary_entered.wait(4)
                assert conns[0] is not conns[1] and not old.done()
            finally:
                release.set()
            assert protected.result(timeout=7) is _OK and old.result(timeout=7) == 409
    finally:
        event.remove(db, "before_cursor_execute", witness)
    assert _active(db, driver) == {oid} and _row(db, ordinary) == before_ordinary


@pytest.mark.parametrize("boundary", ("offer_final", "absolute_lock"))
def test_both_real_sqlite_cutoffs_and_existing_timeout_ownership(request, boundary):
    data = request.getfixturevalue("retired")
    db, _, oid, driver, _, _ = data
    if boundary == "absolute_lock":
        data = (*data[:-1], request.getfixturevalue("near_deadline"))
    assert _store(data, _claim(data, lease_seconds=120), offer_seconds=120 if boundary == "absolute_lock" else 1)
    before = resume.observe(db, oid)
    state = resume._state(before)
    expiry = state["offer_expires_at"]
    proof = _bind(data)
    assert proof
    entered = Event()
    acceptance_conn = []

    def witness(conn, cursor, statement, parameters, context, executemany):
        if boundary == "absolute_lock" and statement == "BEGIN IMMEDIATE":
            acceptance_conn.append(conn.connection.driver_connection)
            entered.set()
        elif boundary == "offer_final" and statement.startswith("UPDATE instantorder SET"):
            entered.set()
            _wait_db(db, expiry)

    if boundary == "absolute_lock":
        assert expiry == state["route_resume_deadline_at"]
        with ThreadPoolExecutor(max_workers=1) as workers:
            with db.begin() as rival:
                rival.exec_driver_sql("BEGIN IMMEDIATE")
                event.listen(db, "before_cursor_execute", witness)
                try:
                    accepting = workers.submit(_run, data, proof)
                    assert entered.wait(3) and acceptance_conn[0] is not rival.connection.driver_connection
                    assert not accepting.done()
                    _wait_db(db, expiry)
                finally:
                    event.remove(db, "before_cursor_execute", witness)
            closed = resume.store_timeout(db, before)
            assert closed is not None and accepting.result(timeout=6) is _UNKNOWN
        assert resume.observe(db, oid) is None
        assert _as_dict(data)["status"] is S.cancelled
    else:
        assert expiry < state["route_resume_deadline_at"]
        event.listen(db, "before_cursor_execute", witness)
        try:
            assert _run(data, proof) is _UNKNOWN
        finally:
            event.remove(db, "before_cursor_execute", witness)
        assert entered.is_set()
        assert resume.observe(db, oid) == before
    assert _active(db, driver) == set()


@pytest.mark.parametrize("failure", ("late_payment", "late_ledger", "after_trigger", "commit_refusal", "commit_lost"))
def test_atomic_predicates_and_unknown_commit_never_reapply(request, monkeypatch, failure):
    data = request.getfixturevalue("offered")
    db, owner, oid, driver, _, _ = data
    proof = _bind(data)
    assert proof
    before = _as_dict(data)
    if failure in ("late_payment", "late_ledger"):
        seen = []

        def witness(conn, cursor, statement, parameters, context, executemany):
            if statement.startswith("UPDATE instantorder SET"):
                seen.append(True)
                assert conn.connection.driver_connection.in_transaction
                if failure == "late_payment":
                    conn.execute(insert(Payment).values(user_id=owner, order_id=oid, purpose="ride"))
                else:
                    conn.execute(insert(LedgerEntry).values(driver_id=driver, order_id=oid, kind=LedgerKind.earn))

        event.listen(db, "before_cursor_execute", witness)
        try:
            assert _run(data, proof) is _UNKNOWN
        finally:
            event.remove(db, "before_cursor_execute", witness)
        assert seen == [True]
        with db.connect() as conn:
            assert conn.scalar(select(Payment.id).where(Payment.order_id == oid)) is None
            assert conn.scalar(select(LedgerEntry.id).where(LedgerEntry.order_id == oid)) is None
    elif failure == "after_trigger":
        with db.begin() as conn:
            conn.exec_driver_sql(
                "CREATE TRIGGER drift_after_sealed_accept AFTER UPDATE OF accepted_at ON instantorder "
                "BEGIN UPDATE instantorder SET price_estimate=price_estimate+1 WHERE id=NEW.id; END",
            )
        assert _run(data, proof) is _UNKNOWN
    else:
        original = db.dialect.do_commit

        def fail(raw):
            if failure == "commit_lost":
                original(raw)
            raise sqlite3.OperationalError("synthetic commit interruption")

        with monkeypatch.context() as m:
            m.setattr(db.dialect, "do_commit", fail)
            assert _run(data, proof) is _UNKNOWN
    after = _as_dict(data)
    if failure == "commit_lost":
        assert after["status"] is S.accepted and _active(db, driver) == {oid}
        assert _run(data, proof) is _UNKNOWN and _as_dict(data) == after
    else:
        assert after == before and _active(db, driver) == set()
