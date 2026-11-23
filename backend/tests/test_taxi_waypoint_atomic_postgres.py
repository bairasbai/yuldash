"""Real PostgreSQL evidence for the inert assigned/preboard transaction."""
import os
from contextlib import contextmanager
from datetime import timedelta
from uuid import uuid4

import psycopg2
import pytest
from sqlalchemy import create_engine, delete, event, select, text, update
from sqlmodel import Session
from test_taxi_waypoint_atomic import _KEY, _ORDER, _QUOTE, _RECEIPT, _STOP, _state

from app import instant_service as isv
from app import taxi_route_edit_outcome as outcome
from app.db import engine
from app.models import InstantOrder, User
from app.models import InstantOrderStatus as S
from app.taxi_route_agreement import bind_waypoint_write
from app.taxi_waypoint_atomic import WaypointCommit, commit_preboard_waypoints
from app.taxi_waypoint_consent import capture_route_write

pytestmark = pytest.mark.skipif(
    engine.dialect.name != "postgresql" or os.environ.get("AUDIT_PG_CI_SERVICE") != "1",
    reason="isolated PostgreSQL CI service required",
)
_CLOCK = text("SELECT timezone('UTC', clock_timestamp())")
_USER = User.__table__


@pytest.fixture
def preboard_pg(request):
    url = engine.url
    assert (url.drivername == "postgresql+psycopg2" and url.username == "yuldash"
            and url.host in ("localhost", "127.0.0.1", "::1") and url.port == 5432
            and url.database == "yuldash_run" and not url.query), "expected the disposable CI PostgreSQL DSN"
    with engine.connect() as conn:
        db_name, role = conn.execute(text("SELECT current_database(), current_user")).one()
        assert (db_name, role) == ("yuldash_run", "yuldash")
    # Only initialize app/test schema after verifying the known isolated target.
    request.getfixturevalue("client")
    writer = create_engine(url, hide_parameters=True)
    observer = create_engine(url, hide_parameters=True)
    try:
        seed = uuid4().int % 10**7
        with Session(observer) as s:
            passenger = User(phone=f"+7999{seed:07d}")
            driver = User(phone=f"+7999{(seed + 1) % 10**7:07d}")
            s.add_all([passenger, driver])
            s.flush()
            users = passenger.id, driver.id
            row = InstantOrder(
                passenger_id=users[0], driver_id=users[1], status=S.accepted,
                from_lat=54.71, from_lng=55.81, from_text="SYNTHETIC A",
                to_lat=54.73, to_lng=55.83, to_text="SYNTHETIC B",
                ride_price=240, price_estimate=280, ride_base_price=210,
                pickup_fee_kop=4000, promo_discount_kop=10000, distance_km=8.0, eta_min=12.0,
            )
            s.add(row)
            s.flush()
            order_id = row.id
            s.commit()
        try:
            with observer.connect() as conn, Session(bind=conn) as s:
                source = capture_route_write(s.get(InstantOrder, order_id))
                now = conn.scalar(_CLOCK)
            args = {"key": _KEY, "owner_id": users[0], "order_id": order_id, "requested": [_STOP], "quote": _QUOTE}
            agreement = bind_waypoint_write(**args, source=source, issued_at=now - timedelta(seconds=1),
                                            valid_until=now + timedelta(seconds=45))
            assert agreement is not None
            yield writer, observer, args | {
                "agreement": agreement, "attempt_id": str(uuid4()), "receipt_expires_at": now + timedelta(minutes=3),
            }
        finally:
            with observer.begin() as conn:
                conn.execute(delete(_RECEIPT).where(_RECEIPT.c.order_id == order_id))
                conn.execute(delete(_ORDER).where(_ORDER.c.id == order_id))
                conn.execute(delete(_USER).where(_USER.c.id.in_(users)))
    finally:
        observer.dispose()
        writer.dispose()


def _evidence(observer, args):
    with observer.connect() as conn:
        now = conn.scalar(_CLOCK)
    return outcome.committed_evidence(
        observer, owner_id=args["owner_id"], order_id=args["order_id"], attempt_id=args["attempt_id"],
        binding=args["agreement"].binding, now=now,
    )


def _assert_saved(observer, args):
    route, receipts = _state(observer, args["order_id"])
    assert (route["ride_price"], route["price_estimate"], route["ride_base_price"]) == (160, 200, 143)
    assert 0 < route["promo_discount_kop"] < 10000
    assert isv.parse_waypoints(route["waypoints_json"]) == [_STOP | {"done": False}]
    assert route["status"] == S.accepted and route["price_final"] is None and not route["paid"]
    assert len(receipts) == 1
    assert receipts[0]["owner_id"] == args["owner_id"] and receipts[0]["attempt_id"] == args["attempt_id"]
    assert bytes(receipts[0]["binding"]) == args["agreement"].binding
    assert receipts[0]["expires_at"] == args["receipt_expires_at"]
    assert _evidence(observer, args) is outcome.CommittedEvidence.COMMITTED
    return route, receipts


def test_postgres_route_and_receipt_become_visible_together(preboard_pg):
    writer, observer, args = preboard_pg
    oid = args["order_id"]
    before = _state(observer, oid)
    seen = []

    def witness(conn, cursor, statement, parameters, context, executemany):
        if statement.startswith("UPDATE instantorder SET"):
            # The writer has a locally staged receipt, yet another backend sees neither change.
            assert conn.scalar(select(_RECEIPT.c.attempt_id).where(_RECEIPT.c.order_id == oid)) == args["attempt_id"]
            with observer.connect() as reader:
                assert reader.exec_driver_sql("SELECT pg_backend_pid()").scalar_one() != (
                    conn.connection.driver_connection.get_backend_pid()
                )
            assert _state(observer, oid) == before and not before[1]
            seen.append(True)

    with event_scope(writer, "before_cursor_execute", witness):
        assert commit_preboard_waypoints(writer, **args) is WaypointCommit.COMMITTED
    assert seen == [True]
    saved = _assert_saved(observer, args)
    assert commit_preboard_waypoints(writer, **args) is WaypointCommit.UNKNOWN
    assert _state(observer, oid) == saved


@contextmanager
def event_scope(engine, name, hook):
    event.listen(engine, name, hook)
    try:
        yield
    finally:
        event.remove(engine, name, hook)


def test_postgres_staged_receipt_rolls_back_on_zero_row_cas(preboard_pg):
    writer, observer, args = preboard_pg
    oid = args["order_id"]
    before = _state(observer, oid)
    staged = []
    updated = []

    def interleave(conn, cursor, statement, parameters, context, executemany):
        if statement.startswith("INSERT INTO taxirouteeditoutcome"):
            assert conn.scalar(select(_RECEIPT.c.attempt_id).where(_RECEIPT.c.order_id == oid)) == args["attempt_id"]
            # Simulate a changed source after staging in this same transaction.
            # No trigger, no DDL and no writes outside this synthetic order.
            conn.execute(update(_ORDER).where(_ORDER.c.id == oid).values(ride_base_price=123))
            staged.append(True)
        elif statement.startswith("UPDATE instantorder SET") and "RETURNING" in statement:
            updated.append(cursor.rowcount)

    with event_scope(writer, "after_cursor_execute", interleave):
        assert commit_preboard_waypoints(writer, **args) is WaypointCommit.UNKNOWN
    assert staged == [True] and updated == [0]
    assert _state(observer, oid) == before and not before[1]
    assert _evidence(observer, args) is outcome.CommittedEvidence.UNKNOWN


@pytest.mark.parametrize("lost_ack", [False, True], ids=["before-commit", "committed-ack-lost"])
def test_postgres_commit_uncertainty_needs_independent_receipt(preboard_pg, monkeypatch, lost_ack):
    writer, observer, args = preboard_pg
    oid = args["order_id"]
    before = _state(observer, oid)
    physical_commit = writer.dialect.do_commit
    attempts = []

    def interrupted(raw):
        attempts.append(True)
        if lost_ack:
            physical_commit(raw)
        raise psycopg2.OperationalError("synthetic commit acknowledgement unavailable")

    with monkeypatch.context() as patch:
        patch.setattr(writer.dialect, "do_commit", interrupted)
        assert commit_preboard_waypoints(writer, **args) is WaypointCommit.UNKNOWN
    assert attempts == [True]
    if lost_ack:
        saved = _assert_saved(observer, args)
        assert commit_preboard_waypoints(writer, **args) is WaypointCommit.UNKNOWN
        assert _state(observer, oid) == saved
    else:
        assert _state(observer, oid) == before and not before[1]
        assert _evidence(observer, args) is outcome.CommittedEvidence.UNKNOWN
