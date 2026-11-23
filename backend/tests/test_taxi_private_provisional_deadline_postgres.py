"""Characterize private visibility, not a production assignment protocol."""
import os
from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace
from datetime import timedelta
from threading import Event
from uuid import uuid4

import psycopg2
import pytest
from sqlalchemy import Column, Integer, MetaData, String, Table, create_engine, event, select, text, update
from test_taxi_offered_waypoint_preview import _unexpected
from test_taxi_offered_waypoint_retire import _CHILD, _O, _children
from test_taxi_receipted_accept_postgres import (
    _order, _receipt, _successor, _USERS,
    retired_pg, synthetic_route,  # noqa: F401 - imported pytest fixtures
)
from test_taxi_route_resume_deadline_postgres import _clock, _until

from app import instant_service as isv
from app import taxi_retirement_agreement_seal as seal
from app import taxi_route_resume as resume
from app.db import engine
from app.models import InstantOrderStatus as S
from app.models import LedgerEntry, Payment
from app.taxi_offered_waypoint_agreement import bind_offered_waypoints
from app.taxi_offered_waypoint_preview import _FIELDS as _OFFERED_SOURCE_FIELDS

pytestmark = pytest.mark.skipif(
    engine.dialect.name != "postgresql" or os.environ.get("AUDIT_PG_CI_SERVICE") != "1",
    reason="isolated PostgreSQL CI service required",
)


def _history(data):
    """Synthetic history: exercise the true 120s cutoff with a short remainder."""
    writer, reader, args, current, driver = data
    owner, oid = args["owner_id"], args["order_id"]
    preview = args["preview"]
    n = len(_OFFERED_SOURCE_FIELDS)
    source = dict(zip(_OFFERED_SOURCE_FIELDS, preview._source[:n], strict=True))
    marker = resume._state(current)["route_resume_id"]
    with reader.begin() as conn:
        retired = _clock(conn) - timedelta(seconds=105)
        deadline = retired + timedelta(seconds=120)
        prior = {"created_at": retired - timedelta(seconds=3), "searching_at": retired - timedelta(seconds=2),
                 "offered_at": retired - timedelta(seconds=1)}
        source |= prior | {"offer_expires_at": retired + timedelta(seconds=60)}
        old = tuple(source[name] for name in _OFFERED_SOURCE_FIELDS) + preview._source[n:]
        historical = replace(preview, _source=old, observed_at=retired - timedelta(milliseconds=10),
                             valid_until=retired + timedelta(seconds=50))
        agreement = bind_offered_waypoints(args["key"], owner_id=owner, order_id=oid, preview=historical)
        saved = seal.bind_retirement_agreement(
            args["key"], owner_id=owner, order_id=oid, old_source=old, preview=historical, agreement=agreement,
            marker_id=marker, retired_at=retired, deadline_at=deadline,
        )
        assert saved is not None
        assert conn.execute(update(_O).where(_O.c.id == oid).values(
            **prior, destination_changed_at=retired, route_resume_retired_at=retired,
            route_resume_ready_at=retired, route_resume_deadline_at=deadline, **{resume._SEAL: saved._digest},
        )).rowcount == 1
        assert conn.execute(update(_CHILD).where(
            _CHILD.c.owner_id == owner, _CHILD.c.order_id == oid, _CHILD.c.attempt_id == args["attempt_id"],
        ).values(binding=agreement.binding, expires_at=retired + timedelta(minutes=3))).rowcount == 1
    marked = resume.observe(reader, oid)
    assert marked is not None
    return writer, reader, args, marked, driver


def _private_visibility(conn, table, oid, marker, proposal, deadline, *, acknowledged):
    # One fresh RC read witnesses current row and private record together.
    # A later DB-clock sample is an upper bound on when this observer saw it
    # under the checked clock; neither sample authorizes public assignment.
    observed = conn.execute(select(table.c.proposal).select_from(table.join(
        _O, (_O.c.id == table.c.order_id) & (_O.c.route_resume_id == table.c.generation),
    )).where(
        table.c.order_id == oid, table.c.generation == marker, table.c.proposal == proposal,
        _O.c.status == S.offered, _O.c.driver_id.is_(None), _O.c.route_resume_deadline_at == deadline,
    )).scalar_one_or_none()
    at = _clock(conn)
    return at if acknowledged and observed == proposal and at < deadline else None


def _side_effect_rows(reader):
    with reader.connect() as conn:
        return tuple(
            conn.execute(select(table).order_by(table.c.id)).all()
            for table in (_USERS, Payment.__table__, LedgerEntry.__table__)
        )


def _commit_with_lost_ack(conn):
    conn.commit()
    raise psycopg2.OperationalError("synthetic interruption after physical private commit")


@pytest.mark.parametrize("finish", ("early", "late", "lost-ack", "rollback"))
def test_pg_private_proposal_visibility_never_publishes_assignment(request, monkeypatch, finish):
    original_retirement = request.getfixturevalue("retired_pg")
    data = _history(original_retirement) if finish in ("late", "rollback") else original_retirement
    writer, reader, args, _, _ = data
    _identity, _card, offer = _successor(data, offer_seconds=120)
    oid = args["order_id"]
    before = _order(reader, oid)
    marker, deadline = before["route_resume_id"], before["route_resume_deadline_at"]
    assert before["status"] is S.offered and before["driver_id"] is None
    assert before["offer_expires_at"] == deadline
    assert deadline - before["route_resume_retired_at"] == timedelta(seconds=120)
    passenger_receipt = _children(reader, oid)
    effects_before = _side_effect_rows(reader)
    assert len(passenger_receipt) == 1 and _receipt(reader, oid) == []
    for effect in ("_settle_pickup_fee", "_notify_pickup_fee", "_notify_transition", "_cleanup_tried"):
        monkeypatch.setattr(isv, effect, _unexpected)

    with reader.connect() as conn:
        schema = conn.exec_driver_sql("SELECT current_schema()").scalar_one()
    prefix = "pgacceptreceipt_"
    assert schema.startswith(prefix) and len(schema) == len(prefix) + 32
    assert all(ch in "0123456789abcdef" for ch in schema.removeprefix(prefix))
    private = Table(
        "synthetic_provisional", MetaData(), Column("order_id", Integer, primary_key=True),
        Column("generation", String(32), nullable=False), Column("proposal", String(32), nullable=False),
        schema=schema,
    )
    # The imported guarded fixture drops the own nonce schema even on failure.
    with reader.begin() as conn:
        private.create(conn)
    proposal = uuid4().hex
    timeout_engine = create_engine(
        reader.url, hide_parameters=True, connect_args={
            "connect_timeout": 4,
            "options": f"-csearch_path={schema} -clock_timeout=6000 -cstatement_timeout=8000",
        },
    )
    waiting, entered = [], Event()
    timeout_locked, allow_timeout_write = Event(), Event()

    def mark_timeout_lock(conn, cursor, statement, parameters, context, many):
        sql = " ".join(statement.lower().split())
        if sql.startswith("select instantorder.id from instantorder") and sql.endswith("for update"):
            waiting.append(conn.connection.driver_connection.get_backend_pid())
            entered.set()

    def hold_timeout_after_lock(conn, cursor, statement, parameters, context, many):
        sql = " ".join(statement.lower().split())
        if sql.startswith("select instantorder.id from instantorder") and sql.endswith("for update"):
            timeout_locked.set()
            assert allow_timeout_write.wait(8), "observer did not release timeout after row lock"

    try:
        with timeout_engine.connect() as conn:
            assert conn.exec_driver_sql("SELECT current_schema()").scalar_one() == schema
            assert conn.exec_driver_sql("SHOW transaction_isolation").scalar_one() == "read committed"
        event.listen(timeout_engine, "before_cursor_execute", mark_timeout_lock)
        event.listen(timeout_engine, "after_cursor_execute", hold_timeout_after_lock)
        with ThreadPoolExecutor(max_workers=1) as pool, writer.connect() as holder, reader.connect() as other:
            holder.exec_driver_sql("SET LOCAL statement_timeout = '6s'")
            hold_pid = holder.exec_driver_sql("SELECT pg_backend_pid()").scalar_one()
            other_pid = other.exec_driver_sql("SELECT pg_backend_pid()").scalar_one()
            assert hold_pid != other_pid
            assert other.exec_driver_sql("SHOW transaction_isolation").scalar_one() == "read committed"
            assert not other.exec_driver_sql("SELECT pg_is_in_recovery()").scalar_one()
            assert not holder.exec_driver_sql("SELECT pg_is_in_recovery()").scalar_one()
            assert holder.execute(select(_O.c.id).where(_O.c.id == oid).with_for_update()).scalar_one() == oid
            assert _clock(holder) < deadline
            assert holder.execute(private.insert().values(
                order_id=oid, generation=marker, proposal=proposal,
            ).returning(private.c.proposal)).scalar_one() == proposal
            assert other.scalar(select(private.c.proposal)) is None
            assert _order(reader, oid) == before and _receipt(reader, oid) == []
            assert _clock(other) < deadline

            timeout = None
            if finish in ("late", "rollback"):
                _until(lambda: _clock(other) >= deadline, "outside database clock never crossed cap", max_seconds=18)
                timeout = pool.submit(resume.store_timeout, timeout_engine, offer)
                assert entered.wait(5), "existing timeout did not reach the Order lock"
                assert len(waiting) == 1 and waiting[0] not in (hold_pid, other_pid)

                def blocked():
                    return other.execute(text("SELECT :a = ANY(pg_blocking_pids(:b))"),
                                         {"a": hold_pid, "b": waiting[0]}).scalar_one()

                _until(blocked, "timeout was not blocked by this exact writer", max_seconds=4)
                assert not timeout.done()
                assert other.scalar(select(private.c.proposal)) is None
                assert _order(reader, oid) == before and _receipt(reader, oid) == []
                assert _clock(other) >= deadline
            try:
                acknowledged = False
                if finish == "rollback":
                    holder.rollback()
                elif finish == "lost-ack":
                    with pytest.raises(psycopg2.OperationalError, match="synthetic interruption"):
                        _commit_with_lost_ack(holder)
                else:
                    holder.commit()
                    acknowledged = True

                if timeout is not None:
                    assert timeout_locked.wait(5), "timeout did not acquire the row after private transaction ended"
                    assert _order(reader, oid) == before
                    assert _clock(other) >= deadline
                assert other.scalar(select(private.c.proposal)) == (None if finish == "rollback" else proposal)
                witnessed = _private_visibility(other, private, oid, marker, proposal, deadline,
                                                 acknowledged=acknowledged)
                if finish == "early":
                    assert witnessed is not None and witnessed < deadline
                else:
                    assert witnessed is None
                if finish == "lost-ack":
                    # The physical row and timely visibility do not repair the
                    # failed acknowledgement for the conservative prototype.
                    assert _clock(other) < deadline
                    assert _private_visibility(other, private, oid, marker, proposal, deadline,
                                               acknowledged=True) is not None
            finally:
                allow_timeout_write.set()
            if timeout is not None:
                closed = timeout.result(timeout=9)
                assert closed is not None and resume._state(closed)["status"] is S.cancelled
                after = _order(reader, oid)
                assert after["cancelled_at"] >= deadline
                assert after == before | {
                    "status": S.cancelled, "cancelled_at": after["cancelled_at"], "cancel_by": "system",
                    "cancel_reason": "route_resume_timeout", "current_offer_driver_id": None,
                    "offer_expires_at": None, **dict.fromkeys(resume._MARKERS),
                }
                # Even a retained late private row cannot stand for this
                # generation after exact terminal closure; timeout cannot win twice.
                assert _private_visibility(other, private, oid, marker, proposal, deadline,
                                           acknowledged=True) is None
                assert other.scalar(select(private.c.proposal)) == (proposal if finish == "late" else None)
                assert resume.store_timeout(reader, offer) is None
            else:
                assert _order(reader, oid) == before
    finally:
        timeout_engine.dispose()
    assert _side_effect_rows(reader) == effects_before
    assert _receipt(reader, oid) == [] and _children(reader, oid) == passenger_receipt
