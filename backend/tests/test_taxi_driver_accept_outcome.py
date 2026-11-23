"""Private driver evidence, only through the caller's real transaction."""
import sqlite3
from datetime import datetime, timedelta
from uuid import uuid4

import pytest
from sqlalchemy import delete, select, update
from sqlalchemy.exc import OperationalError
from sqlmodel import Session, SQLModel, create_engine

from app import taxi_driver_accept_outcome as receipt
from app.models import (
    InstantOrder,
    InstantOrderStatus,
    Tariff,
    TaxiDriverAcceptOutcome,
    User,
)
from app.taxi_sealed_offer_card import SealedOfferCardProof

_S = InstantOrderStatus
_O = InstantOrder.__table__
_D = TaxiDriverAcceptOutcome.__table__
_U = User.__table__
_KEY = b"synthetic-driver-outcome-testing-key"
_PROOF = SealedOfferCardProof(b"q" * 32)


@pytest.fixture
def record(tmp_path):
    db = create_engine(f"sqlite:///{tmp_path / 'driver-outcome.sqlite'}", hide_parameters=True)
    SQLModel.metadata.create_all(db, tables=[_U, Tariff.__table__, _O, _D], checkfirst=True)
    with Session(db) as s:
        users = [User(phone=f"+7999000630{i}") for i in range(3)]
        s.add_all(users)
        s.flush()
        owner, driver, successor = (u.id for u in users)
        first = InstantOrder(passenger_id=owner, status=_S.offered, current_offer_driver_id=driver)
        another = InstantOrder(passenger_id=owner)
        s.add_all([first, another])
        s.flush()
        a, b = first.id, another.id
        s.commit()
    try:
        yield db, {"driver_id": driver, "order_id": a, "attempt_id": str(uuid4()), "proof": _PROOF}, b, successor
    finally:
        db.dispose()


def _clock(conn):
    return datetime.fromisoformat(conn.exec_driver_sql("SELECT strftime('%Y-%m-%d %H:%M:%f000','now')").scalar_one())


def _assign(conn, identity):
    now = _clock(conn)
    oid, driver = identity["order_id"], identity["driver_id"]
    result = conn.execute(update(_O).where(_O.c.id == oid).values(
        status=_S.accepted, driver_id=driver, current_offer_driver_id=None, accepted_at=now,
    ))
    assert result.rowcount == 1
    return {"accepted_at": now, "expires_at": now + timedelta(minutes=2)}


def _lookup(db, identity, **changes):
    return receipt.committed_acceptance(db, _KEY, **(identity | changes))


def _stage(conn, identity, timing):
    return receipt.stage_committed_acceptance(conn, _KEY, **identity, **timing)


def _record(db, identity):
    with db.begin() as conn:
        conn.exec_driver_sql("BEGIN IMMEDIATE")
        timing = _assign(conn, identity)
        assert _stage(conn, identity, timing) is receipt.StageResult.STAGED


def test_real_driver_outcome_is_exact_and_survives_same_row_reassignment(record):
    db, identity, different_order, successor = record
    oid = identity["order_id"]
    with db.begin() as conn:
        conn.exec_driver_sql("BEGIN IMMEDIATE")
        timing = _assign(conn, identity)
        assert _stage(conn, identity, timing) is receipt.StageResult.STAGED
        assert _lookup(db, identity) is receipt.DriverAcceptEvidence.UNKNOWN
        assert _stage(conn, identity, timing | {"expires_at": timing["expires_at"] + timedelta(minutes=4)}) is (
            receipt.StageResult.UNKNOWN
        )
    assert _lookup(db, identity) is receipt.DriverAcceptEvidence.COMMITTED
    with db.begin() as conn:
        stored = conn.execute(select(_D)).mappings().one()
        assert stored["expires_at"] == timing["expires_at"]
        assert stored["binding"] != _PROOF._digest and len(stored["binding"]) == 32
        # The old driver's exact evidence is historical, even when the same
        # order has been cancelled then assigned to a different driver.
        conn.execute(update(_O).where(_O.c.id == oid).values(status=_S.cancelled))
    assert _lookup(db, identity) is receipt.DriverAcceptEvidence.COMMITTED
    with db.begin() as conn:
        conn.execute(update(_O).where(_O.c.id == oid).values(status=_S.accepted, driver_id=successor))
    assert _lookup(db, identity) is receipt.DriverAcceptEvidence.COMMITTED
    for bad in ({"driver_id": successor}, {"order_id": different_order}, {"attempt_id": str(uuid4())},
                {"proof": SealedOfferCardProof(b"w" * 32)}, {"proof": _PROOF._digest}, {"driver_id": True}):
        assert _lookup(db, identity, **bad) is receipt.DriverAcceptEvidence.UNKNOWN
    assert TaxiDriverAcceptOutcome(**stored).model_dump() == {}
    assert identity["attempt_id"] not in repr(TaxiDriverAcceptOutcome(**stored))


@pytest.mark.parametrize("lost_ack", (False, True))
def test_independent_result_does_not_confuse_rollback_and_real_lost_ack(record, monkeypatch, lost_ack):
    db, identity, *_ = record
    original_commit = db.dialect.do_commit

    def interrupt(raw):
        if lost_ack:
            original_commit(raw)
        raise sqlite3.OperationalError("synthetic commit interruption")

    with monkeypatch.context() as m:
        m.setattr(db.dialect, "do_commit", interrupt)
        with pytest.raises(OperationalError, match="synthetic commit interruption"):
            _record(db, identity)
    assert _lookup(db, identity) is (
        receipt.DriverAcceptEvidence.COMMITTED if lost_ack else receipt.DriverAcceptEvidence.UNKNOWN
    )
    with db.connect() as conn:
        expected = _S.accepted if lost_ack else _S.offered
        assert conn.scalar(select(_O.c.status).where(_O.c.id == identity["order_id"])) is expected


@pytest.mark.parametrize("delete_parent", ("driver", "order", "expiry"))
def test_expiry_and_each_parent_cascade_hide_only_driver_attempt(record, delete_parent):
    db, identity, other_order, successor = record
    _record(db, identity)
    oid = identity["order_id"]
    with db.begin() as conn:
        assert conn.exec_driver_sql("PRAGMA foreign_keys").scalar_one() == 1
        if delete_parent == "driver":
            # A former driver can be deleted while the order remains.
            conn.execute(update(_O).where(_O.c.id == oid).values(driver_id=successor))
            conn.execute(delete(_U).where(_U.c.id == identity["driver_id"]))
        elif delete_parent == "order":
            conn.execute(delete(_O).where(_O.c.id == oid))
        else:
            conn.execute(update(_D).values(expires_at=_clock(conn)-timedelta(seconds=1)))
        assert conn.scalar(select(_O.c.id).where(_O.c.id == other_order)) == other_order
        assert len(conn.execute(select(_D)).all()) == (1 if delete_parent == "expiry" else 0)
    assert _lookup(db, identity) is receipt.DriverAcceptEvidence.UNKNOWN


def test_ineligible_rows_unsafe_transactions_and_malformed_expiry_do_not_classify(record):
    db, identity, _, successor = record
    with db.connect() as conn:
        t = datetime(2001, 1, 1)
        timing = {"accepted_at": t, "expires_at": t + timedelta(minutes=1)}
        assert _stage(conn, identity, timing) is receipt.StageResult.UNKNOWN
    with db.connect().execution_options(isolation_level="AUTOCOMMIT") as conn, conn.begin():
        assert _stage(conn, identity, timing) is receipt.StageResult.UNKNOWN
    with db.begin() as conn:
        assert not conn.connection.driver_connection.in_transaction
        assert _stage(conn, identity, timing) is receipt.StageResult.UNKNOWN
    with db.connect() as conn:
        conn.exec_driver_sql("PRAGMA foreign_keys=OFF")
        conn.exec_driver_sql("BEGIN IMMEDIATE")
        measured = _assign(conn, identity)
        assert _stage(conn, identity, measured) is receipt.StageResult.UNKNOWN
        conn.rollback()
        conn.exec_driver_sql("PRAGMA foreign_keys=ON")
    with db.begin() as conn:
        conn.exec_driver_sql("BEGIN IMMEDIATE")
        now = _assign(conn, identity)
        with conn.begin_nested():
            assert _stage(conn, identity, now) is receipt.StageResult.UNKNOWN
        assert _stage(conn, identity | {"driver_id": successor}, now) is receipt.StageResult.UNKNOWN
        assert _stage(conn, identity, now | {"accepted_at": t}) is receipt.StageResult.UNKNOWN
        assert _stage(conn, identity, now | {"expires_at": now["accepted_at"]}) is receipt.StageResult.UNKNOWN
        conn.execute(update(_O).where(_O.c.id == identity["order_id"]).values(route_resume_claim_id=""))
        assert _stage(conn, identity, now) is receipt.StageResult.UNKNOWN
        conn.execute(update(_O).where(_O.c.id == identity["order_id"]).values(route_resume_claim_id=None))
        assert _stage(conn, identity, now) is receipt.StageResult.STAGED
    with db.begin() as conn:
        conn.exec_driver_sql("UPDATE taxidriveracceptoutcome SET expires_at = 'not-a-datetime'")
    assert _lookup(db, identity) is receipt.DriverAcceptEvidence.UNKNOWN
