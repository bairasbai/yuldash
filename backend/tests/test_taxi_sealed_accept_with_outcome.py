"""Real SQLite assignment and driver evidence, without routes or providers."""
import sqlite3
from datetime import timedelta
from uuid import uuid4

import pytest
from sqlalchemy import event, insert, select, update
from test_taxi_offered_waypoint_preview import _unexpected
from test_taxi_offered_waypoint_retire import _wait_db, healthy, route, testdb  # noqa: F401 - pytest fixtures
from test_taxi_sealed_offer_card import _bind, offered  # noqa: F401
from test_taxi_sealed_resume_claim import near_deadline, retired  # noqa: F401
from test_taxi_sealed_resume_offer import _claim, _store

from app import instant_service as isv
from app import taxi_driver_accept_outcome as outcome
from app import taxi_route_resume as resume
from app import taxi_sealed_offer_accept as accept
from app.models import InstantOrder, LedgerEntry, Payment, TaxiDriverAcceptOutcome
from app.models import InstantOrderStatus as S
from app.taxi_sealed_offer_card import SealedOfferCardProof
from app.timeutil import utcnow

_R = TaxiDriverAcceptOutcome.__table__
_O = InstantOrder.__table__


def _params(data):
    _, owner, oid, driver, _, _ = data
    return {"owner_id": owner, "order_id": oid, "driver_id": driver,
            "attempt_id": str(uuid4()), "expires_at": utcnow() + timedelta(minutes=3)}


def _apply(data, proof, params):
    return accept.accept_sealed_offer_with_outcome(data[0], data[4]["key"], proof, **params)


def _known(data, proof, params):
    attributes = {k: params[k] for k in ("driver_id", "order_id", "attempt_id")}
    return outcome.committed_acceptance(data[0], data[4]["key"], proof=proof, **attributes)


def _state(data):
    db, _, oid, *_ = data
    with db.connect() as conn:
        order = conn.execute(select(_O).where(_O.c.id == oid)).mappings().one()
        evidence = conn.execute(select(_R).where(_R.c.order_id == oid)).mappings().all()
    return order, evidence


@pytest.mark.parametrize("lost_ack", (False, True), ids=("acknowledged", "committed-ack-lost"))
def test_assignment_and_receipt_publish_together_even_after_terminal_reassignment(request, monkeypatch, lost_ack):
    data = request.getfixturevalue("offered")
    db, owner, oid, driver, _, _ = data
    proof = _bind(data)
    assert proof
    params = _params(data)
    before = _state(data)
    assert not before[1]
    for side_effect in ("_settle_pickup_fee", "_notify_pickup_fee", "_notify_transition", "_cleanup_tried"):
        monkeypatch.setattr(isv, side_effect, _unexpected)
    original_commit = db.dialect.do_commit
    seen = []

    def witness(conn, cursor, statement, parameters, context, executemany):
        if statement.startswith("INSERT INTO taxidriveracceptoutcome"):
            assert conn.scalar(select(_O.c.driver_id).where(_O.c.id == oid)) == driver
            assert _state(data) == before
            assert _known(data, proof, params) is outcome.DriverAcceptEvidence.UNKNOWN
            seen.append(True)

    def lose_ack(raw):
        original_commit(raw)
        raise sqlite3.OperationalError("synthetic lost database acknowledgement")

    event.listen(db, "before_cursor_execute", witness)
    try:
        with monkeypatch.context() as m:
            if lost_ack:
                m.setattr(db.dialect, "do_commit", lose_ack)
            result = _apply(data, proof, params)
    finally:
        event.remove(db, "before_cursor_execute", witness)
    assert result is (accept.SealedAcceptance.UNKNOWN if lost_ack else accept.SealedAcceptance.ACCEPTED)
    assert seen == [True]
    saved, receipts = _state(data)
    assert dict(saved) == dict(before[0]) | {
        "status": S.accepted, "driver_id": driver, "accepted_at": saved["accepted_at"],
        "current_offer_driver_id": None, "offer_expires_at": None, **dict.fromkeys(resume._MARKERS),
    }
    assert before[0]["offered_at"] <= saved["accepted_at"] < before[0]["offer_expires_at"]
    assert len(receipts) == 1 and receipts[0]["expires_at"] == params["expires_at"]
    assert receipts[0]["binding"] != proof._digest
    with db.begin() as conn:
        assert conn.scalar(select(Payment.id).where(Payment.order_id == oid)) is None
        assert conn.scalar(select(LedgerEntry.id).where(LedgerEntry.order_id == oid)) is None
        conn.execute(update(_O).where(_O.c.id == oid).values(status=S.cancelled))
    assert _known(data, proof, params) is outcome.DriverAcceptEvidence.COMMITTED
    # The same order row may then acquire a different assignment: historical
    # evidence refers only to this driver's attempt, not current route access.
    other_driver = before[0]["route_resume_previous_driver_id"]
    assert other_driver not in (driver, owner)
    with db.begin() as conn:
        conn.execute(update(_O).where(_O.c.id == oid).values(status=S.accepted, driver_id=other_driver))
    assert _known(data, proof, params) is outcome.DriverAcceptEvidence.COMMITTED
    for wrong in ({"driver_id": owner}, {"driver_id": other_driver}, {"order_id": oid + 1},
                  {"attempt_id": str(uuid4())}):
        assert _known(data, proof, params | wrong) is outcome.DriverAcceptEvidence.UNKNOWN
    assert _known(data, SealedOfferCardProof(b"x"*32), params) is outcome.DriverAcceptEvidence.UNKNOWN


@pytest.mark.parametrize("failure", ("insert", "receipt_deleted", "receipt_binding", "order_after_update",
                                     "order_after_receipt", "duplicate", "no_commit"))
def test_receipt_or_assignment_discrepancy_rolls_back_whole_acceptance(request, monkeypatch, failure):
    data = request.getfixturevalue("offered")
    db, _, oid, driver, args, _ = data
    proof = _bind(data)
    assert proof
    params = _params(data)
    triggers = {
        "insert": "BEFORE INSERT ON taxidriveracceptoutcome BEGIN SELECT RAISE(ABORT, 'synthetic receipt refusal'); END",
        "receipt_deleted": ("AFTER INSERT ON taxidriveracceptoutcome BEGIN DELETE FROM taxidriveracceptoutcome "
                            "WHERE attempt_id=NEW.attempt_id; END"),
        "receipt_binding": ("AFTER INSERT ON taxidriveracceptoutcome BEGIN UPDATE taxidriveracceptoutcome "
                            "SET binding=zeroblob(32) WHERE attempt_id=NEW.attempt_id; END"),
        "order_after_update": ("AFTER UPDATE OF accepted_at ON instantorder BEGIN UPDATE instantorder "
                               "SET price_estimate=price_estimate+10 WHERE id=NEW.id; END"),
        "order_after_receipt": ("AFTER INSERT ON taxidriveracceptoutcome BEGIN UPDATE instantorder "
                                "SET price_estimate=price_estimate+10 WHERE id=NEW.order_id; END"),
    }
    prior_proof = SealedOfferCardProof(b"p"*32)
    with db.begin() as conn:
        if failure in triggers:
            conn.exec_driver_sql(f"CREATE TRIGGER synthetic_receipted_failure {triggers[failure]}")
        elif failure == "duplicate":
            conn.execute(insert(_R).values(
                driver_id=driver, order_id=oid, attempt_id=params["attempt_id"],
                binding=outcome._binding(args["key"], driver, oid, params["attempt_id"], prior_proof),
                expires_at=params["expires_at"],
            ))
    before = _state(data)
    seen = []

    def witness(conn, cursor, statement, parameters, context, executemany):
        if statement.startswith("INSERT INTO taxidriveracceptoutcome"):
            seen.append(True)

    def refuse_commit(raw):
        raise sqlite3.OperationalError("synthetic refusal before commit")

    event.listen(db, "before_cursor_execute", witness)
    try:
        with monkeypatch.context() as m:
            if failure == "no_commit":
                m.setattr(db.dialect, "do_commit", refuse_commit)
            assert _apply(data, proof, params) is accept.SealedAcceptance.UNKNOWN
    finally:
        event.remove(db, "before_cursor_execute", witness)
    assert seen == ([] if failure == "order_after_update" else [True])
    assert _state(data) == before
    assert _known(data, proof, params) is outcome.DriverAcceptEvidence.UNKNOWN
    if failure == "duplicate":
        assert _known(data, prior_proof, params) is outcome.DriverAcceptEvidence.COMMITTED


def test_original_deadline_crossing_during_receipt_insert_rolls_back_both(request):
    data = (*request.getfixturevalue("retired")[:-1], request.getfixturevalue("near_deadline"))
    db = data[0]
    assert _store(data, _claim(data, lease_seconds=120), offer_seconds=120)
    proof = _bind(data)
    assert proof
    before = _state(data)
    deadline = before[0]["route_resume_deadline_at"]
    assert deadline == before[0]["offer_expires_at"]
    assert deadline - before[0]["route_resume_retired_at"] == timedelta(seconds=120)
    params = _params(data)
    entered = []

    def cross(conn, cursor, statement, parameters, context, executemany):
        if statement.startswith("INSERT INTO taxidriveracceptoutcome"):
            assert conn.scalar(select(_O.c.status).where(_O.c.id == params["order_id"])) is S.accepted
            assert conn.scalar(select(_R.c.attempt_id)) == params["attempt_id"]
            entered.append(True)
            _wait_db(db, deadline)

    event.listen(db, "after_cursor_execute", cross)
    try:
        assert _apply(data, proof, params) is accept.SealedAcceptance.UNKNOWN
    finally:
        event.remove(db, "after_cursor_execute", cross)
    assert entered == [True] and _state(data) == before
    assert _known(data, proof, params) is outcome.DriverAcceptEvidence.UNKNOWN
