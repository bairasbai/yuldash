"""Real PostgreSQL outcome-aware sealed acceptance in isolated CI schemas."""
import os
from datetime import timedelta
from uuid import uuid4

import psycopg2
import pytest
from sqlalchemy import create_engine, event, select, text, update
from sqlalchemy.schema import CreateSchema, DropSchema
from sqlmodel import Session, SQLModel
from test_taxi_offered_waypoint_preview import _unexpected
from test_taxi_offered_waypoint_preview import route as synthetic_route  # noqa: F401 - pytest fixture
from test_taxi_offered_waypoint_retire import (
    _CHILD,
    _O,
    _T,
    _args,
    _children,
    _forbid_external,
)
from test_taxi_route_resume_deadline_postgres import _clock, _until

from app import instant_service as isv
from app import taxi_driver_accept_outcome as evidence
from app import taxi_route_resume as resume
from app import taxi_sealed_offer_accept as assignment
from app import taxi_sealed_offer_card as cards
from app import taxi_sealed_resume_claim as claims
from app import taxi_sealed_resume_offer as offers
from app.db import engine
from app.models import (
    InstantOrder,
    LedgerEntry,
    Payment,
    Tariff,
    TaxiDriverAcceptOutcome,
    User,
)
from app.models import InstantOrderStatus as S
from app.taxi_offered_waypoint_retire import OfferedRetirement, retire_offered_waypoints

pytestmark = pytest.mark.skipif(
    engine.dialect.name != "postgresql" or os.environ.get("AUDIT_PG_CI_SERVICE") != "1",
    reason="isolated PostgreSQL CI service required",
)
_USERS = User.__table__
_RECEIPT = TaxiDriverAcceptOutcome.__table__


def _assignment_tables():
    # Include just the assignment's models, their FK dependencies, and the
    # receipt. No lifespan or references to the CI suite's public schema.
    pending = [_USERS, _T, _O, _CHILD, _RECEIPT, Payment.__table__, LedgerEntry.__table__]
    result = set()
    while pending:
        table = pending.pop()
        if table not in result:
            result.add(table)
            pending.extend(fk.column.table for fk in table.foreign_keys)
    return result


@pytest.fixture
def retired_pg(request, monkeypatch):
    url = engine.url
    assert (url.drivername == "postgresql+psycopg2" and url.username == "yuldash"
            and url.host in ("localhost", "127.0.0.1", "::1") and url.port == 5432
            and url.database == "yuldash_run" and not url.query), "expected the disposable CI PostgreSQL DSN"
    with engine.connect() as conn:
        assert tuple(conn.execute(text("SELECT current_database(), current_user")).one()) == ("yuldash_run", "yuldash")
    routes = request.getfixturevalue("synthetic_route")
    uid = uuid4()
    schema = "pgacceptreceipt_" + uid.hex
    category = "pgacceptreceipt_" + uid.hex[:24]
    # Every write targets a unique test schema, never shared CI rows.
    with engine.begin() as conn:
        conn.execute(CreateSchema(schema))
    options = {"options": f"-csearch_path={schema}"}
    writer = create_engine(url, hide_parameters=True, connect_args=options)
    reader = create_engine(url, hide_parameters=True, connect_args=options)
    try:
        for isolated in (reader, writer):
            with isolated.connect() as conn:
                assert conn.scalar(text("SELECT current_schema()")) == schema
        SQLModel.metadata.create_all(reader, tables=_assignment_tables())
        seed = uid.int % (10**7 - 3)
        with Session(reader) as s:
            at = _clock(s.connection())
            actors = tuple(User(phone=f"+7999{seed + i:07d}") for i in range(3))
            tariff = Tariff(zone="city", category=category, active=True, base=80, per_km=10, per_min=4)
            s.add_all((*actors, tariff))
            s.flush()
            owner, previous, _next_driver = actors
            user_ids = tuple(actor.id for actor in actors)
            tariff_id = tariff.id
            row = InstantOrder(
                passenger_id=owner.id, current_offer_driver_id=previous.id, tariff_id=tariff_id, category=category,
                status=S.offered, search_round=1, created_at=at - timedelta(seconds=3),
                searching_at=at - timedelta(seconds=2), offered_at=at - timedelta(seconds=1),
                offer_expires_at=at + timedelta(seconds=60),
                from_lat=54.7, from_lng=55.8, from_text="SYNTHETIC A",
                to_lat=54.71, to_lng=55.81, to_text="SYNTHETIC B",
                ride_price=240, ride_base_price=210, price_estimate=280,
                pickup_fee_kop=4000, promo_discount_kop=10000, distance_km=8, eta_min=11,
            )
            s.add(row)
            s.flush()
            oid = row.id
            s.commit()
        with Session(reader) as s:
            chosen = isv.active_tariff(s, "city", category)
            assert chosen is not None and chosen.id == tariff_id and chosen.category == category
        args = _args((reader, user_ids[0], oid, user_ids[1], user_ids[2], tariff_id, at))
        assert len(routes) == 2
        args["receipt_expires_at"] = args["preview"].observed_at + timedelta(minutes=3)
        _forbid_external(monkeypatch)
        with reader.connect() as conn:
            lower = _clock(conn)
        assert retire_offered_waypoints(writer, **args) is OfferedRetirement.COMMITTED
        saved = resume.observe(reader, oid)
        assert saved is not None
        with reader.connect() as conn:
            upper = _clock(conn)
        state = resume._state(saved)
        assert lower <= state["route_resume_retired_at"] <= upper < args["receipt_expires_at"]
        assert type(state[resume._SEAL]) is bytes and len(state[resume._SEAL]) == 32
        yield writer, reader, args, saved, user_ids[2]
    finally:
        reader.dispose()
        writer.dispose()
        with engine.begin() as conn:
            conn.execute(DropSchema(schema, cascade=True))


def _claim_args(args, expected, duration):
    state = resume._state(expected)
    return {"key": args["key"], "owner_id": args["owner_id"], "order_id": args["order_id"],
            "expected": expected, "marker_id": state["route_resume_id"], "lease_seconds": duration}


def _successor(data, *, offer_seconds):
    writer, reader, args, retired, driver = data
    claim_args = _claim_args(args, retired, 120)
    claim = claims.claim_sealed_retirement(writer, **claim_args)
    assert isinstance(claim, claims.SealedResumeClaim)
    identities = {k: claim_args[k] for k in ("key", "owner_id", "order_id")}
    identity = identities | {"driver_id": driver}
    saved = offers.store_sealed_successor_offer(
        writer, **identity, marker_id=claim_args["marker_id"], claim=claim, offer_seconds=offer_seconds,
    )
    assert saved is not None and resume.observe(reader, args["order_id"]) == saved
    with reader.connect() as conn:
        proof = cards.observe_sealed_offer_card(conn, **identity)
    assert isinstance(proof, cards.SealedOfferCardProof)
    return identity, proof, saved


def _order(reader, oid):
    with reader.connect() as conn:
        return dict(conn.execute(select(_O).where(_O.c.id == oid)).mappings().one())


def _attempt(reader, identity):
    with reader.connect() as conn:
        expires = _clock(conn) + timedelta(minutes=3)
    return identity | {"attempt_id": str(uuid4()), "expires_at": expires}


def _known(reader, proof, attempt):
    identity = {k: attempt[k] for k in ("key", "driver_id", "order_id", "attempt_id")}
    return evidence.committed_acceptance(reader, **identity, proof=proof)


def _receipt(reader, oid):
    with reader.connect() as conn:
        return conn.execute(select(_RECEIPT).where(_RECEIPT.c.order_id == oid)).mappings().all()


@pytest.mark.parametrize("lost_ack", (False, True), ids=("acknowledged", "committed-ack-lost"))
def test_pg_assignment_and_exact_receipt_commit_together(retired_pg, monkeypatch, lost_ack):
    writer, reader, args, _, driver = retired_pg
    identity, proof, _ = _successor(retired_pg, offer_seconds=120)
    attempt = _attempt(reader, identity)
    oid = args["order_id"]
    before = _order(reader, oid)
    passenger_receipt = _children(reader, oid)
    assert len(passenger_receipt) == 1 and _receipt(reader, oid) == []
    for side_effect in ("_settle_pickup_fee", "_notify_pickup_fee", "_notify_transition", "_cleanup_tried"):
        monkeypatch.setattr(isv, side_effect, _unexpected)
    seen = []
    committed = []

    def witness(conn, cursor, statement, parameters, context, executemany):
        if statement.startswith("INSERT INTO taxidriveracceptoutcome"):
            assert conn.scalar(select(_O.c.driver_id).where(_O.c.id == oid)) == driver
            with reader.connect() as other:
                assert other.exec_driver_sql("SELECT pg_backend_pid()").scalar_one() != (
                    conn.connection.driver_connection.get_backend_pid()
                )
            assert _order(reader, oid) == before
            assert _known(reader, proof, attempt) is evidence.DriverAcceptEvidence.UNKNOWN
            seen.append(True)

    original = writer.dialect.do_commit

    def lost(raw):
        original(raw)  # actual PostgreSQL commit, then simulate the caller losing its response
        committed.append(True)
        raise psycopg2.OperationalError("synthetic acknowledgement interruption")

    with reader.connect() as conn:
        lower = _clock(conn)
    event.listen(writer, "before_cursor_execute", witness)
    try:
        with monkeypatch.context() as scoped:
            if lost_ack:
                scoped.setattr(writer.dialect, "do_commit", lost)
            result = assignment.accept_sealed_offer_with_outcome(writer, **attempt, proof=proof)
    finally:
        event.remove(writer, "before_cursor_execute", witness)
    with reader.connect() as conn:
        upper = _clock(conn)
        assert conn.scalar(select(Payment.id).where(Payment.order_id == oid)) is None
        assert conn.scalar(select(LedgerEntry.id).where(LedgerEntry.order_id == oid)) is None
    assert seen == [True] and committed == ([True] if lost_ack else [])
    assert result is (assignment.SealedAcceptance.UNKNOWN if lost_ack else assignment.SealedAcceptance.ACCEPTED)
    after = _order(reader, oid)
    assert lower <= after["accepted_at"] <= upper < before["route_resume_deadline_at"]
    assert after == before | {
        "status": S.accepted, "driver_id": driver, "accepted_at": after["accepted_at"],
        "current_offer_driver_id": None, "offer_expires_at": None, **dict.fromkeys(resume._MARKERS),
    }
    rows = _receipt(reader, oid)
    assert len(rows) == 1 and rows[0]["expires_at"] == attempt["expires_at"]
    assert bytes(rows[0]["binding"]) != proof._digest
    assert _children(reader, oid) == passenger_receipt
    assert _known(reader, proof, attempt) is evidence.DriverAcceptEvidence.COMMITTED
    next_driver = before["route_resume_previous_driver_id"]
    with reader.begin() as conn:
        conn.execute(update(_O).where(_O.c.id == oid).values(status=S.cancelled, driver_id=None, accepted_at=None))
    assert _known(reader, proof, attempt) is evidence.DriverAcceptEvidence.COMMITTED
    with reader.begin() as conn:
        conn.execute(update(_O).where(_O.c.id == oid).values(status=S.accepted, driver_id=next_driver, accepted_at=upper))
    assert _known(reader, proof, attempt) is evidence.DriverAcceptEvidence.COMMITTED
    assert _known(reader, proof, attempt | {"driver_id": next_driver}) is evidence.DriverAcceptEvidence.UNKNOWN
    assert _known(reader, proof, attempt | {"attempt_id": str(uuid4())}) is evidence.DriverAcceptEvidence.UNKNOWN
    assert _receipt(reader, oid) == rows


@pytest.mark.parametrize("failure", ("insert-error", "trigger-drift", "delete-staged-receipt"))
def test_pg_actual_db_failure_rolls_back_both_assignment_and_new_receipt(retired_pg, failure):
    writer, reader, args, _, _ = retired_pg
    identity, proof, _ = _successor(retired_pg, offer_seconds=120)
    attempt = _attempt(reader, identity)
    oid = args["order_id"]
    before = _order(reader, oid)
    passenger_receipt = _children(reader, oid)
    assert _receipt(reader, oid) == []
    with reader.begin() as conn:
        if failure == "insert-error":
            conn.exec_driver_sql("ALTER TABLE taxidriveracceptoutcome ADD CHECK (false)")
        else:
            sql = {
                "trigger-drift": "UPDATE instantorder SET price_estimate=price_estimate+10 WHERE id=NEW.order_id;",
                "delete-staged-receipt": "DELETE FROM taxidriveracceptoutcome WHERE attempt_id=NEW.attempt_id;",
            }[failure]
            conn.exec_driver_sql(f"""
                CREATE FUNCTION synthetic_receipt_failure() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN {sql} RETURN NEW; END;
                $$
            """)
            conn.exec_driver_sql("""
                CREATE TRIGGER synthetic_receipt_failure AFTER INSERT ON taxidriveracceptoutcome
                FOR EACH ROW EXECUTE FUNCTION synthetic_receipt_failure()
            """)
    seen = []

    def observe(conn, cursor, statement, parameters, context, executemany):
        if statement.startswith("INSERT INTO taxidriveracceptoutcome"):
            assert conn.scalar(select(_O.c.status).where(_O.c.id == oid)) is S.accepted
            seen.append(True)

    event.listen(writer, "before_cursor_execute", observe)
    try:
        assert assignment.accept_sealed_offer_with_outcome(writer, **attempt, proof=proof) is (
            assignment.SealedAcceptance.UNKNOWN
        )
    finally:
        event.remove(writer, "before_cursor_execute", observe)
    assert seen == [True]
    assert _order(reader, oid) == before and _receipt(reader, oid) == []
    assert _children(reader, oid) == passenger_receipt
    assert _known(reader, proof, attempt) is evidence.DriverAcceptEvidence.UNKNOWN


def test_pg_cutoff_after_actual_receipt_insert_is_not_a_success(retired_pg):
    writer, reader, args, _, _ = retired_pg
    identity, proof, _ = _successor(retired_pg, offer_seconds=6)
    attempt = _attempt(reader, identity)
    oid = args["order_id"]
    before = _order(reader, oid)
    deadline = before["offer_expires_at"]
    assert deadline < before["route_resume_deadline_at"]
    seen = []

    def cross(conn, cursor, statement, parameters, context, executemany):
        if not statement.startswith("INSERT INTO taxidriveracceptoutcome"):
            return
        assert conn.scalar(select(_RECEIPT.c.attempt_id)) == attempt["attempt_id"]
        assert _order(reader, oid) == before and _known(reader, proof, attempt) is evidence.DriverAcceptEvidence.UNKNOWN
        with reader.connect() as clock:
            assert _clock(clock) < deadline
            _until(lambda: _clock(clock) >= deadline, "server never reached original offer cutoff")
            assert _clock(clock) < before["route_resume_deadline_at"]
        seen.append(True)

    event.listen(writer, "after_cursor_execute", cross)
    try:
        assert assignment.accept_sealed_offer_with_outcome(writer, **attempt, proof=proof) is (
            assignment.SealedAcceptance.UNKNOWN
        )
    finally:
        event.remove(writer, "after_cursor_execute", cross)
    assert seen == [True] and _order(reader, oid) == before and _receipt(reader, oid) == []
