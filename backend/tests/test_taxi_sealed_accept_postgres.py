"""Real PostgreSQL proof for dormant sealed assignment and actual row locks."""
import os
from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta
from threading import Event
from uuid import uuid4

import pytest
from sqlalchemy import create_engine, event, select, text
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
from app import taxi_route_edit_outcome as outcome
from app import taxi_route_resume as resume
from app import taxi_sealed_offer_accept as assignment
from app import taxi_sealed_offer_card as cards
from app import taxi_sealed_resume_claim as claims
from app import taxi_sealed_resume_offer as offers
from app.db import engine
from app.models import InstantOrder, LedgerEntry, Payment, Tariff, User
from app.models import InstantOrderStatus as S
from app.taxi_offered_waypoint_retire import OfferedRetirement, retire_offered_waypoints

pytestmark = pytest.mark.skipif(
    engine.dialect.name != "postgresql" or os.environ.get("AUDIT_PG_CI_SERVICE") != "1",
    reason="isolated PostgreSQL CI service required",
)
_USERS = User.__table__


def _assignment_tables():
    # Include just the assignment's models, their FK dependencies, and the
    # receipt. No lifespan or references to the CI suite's public schema.
    pending = [_USERS, _T, _O, _CHILD, Payment.__table__, LedgerEntry.__table__]
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
    schema = "pgsealedaccept_" + uid.hex
    category = "pgsealedaccept_" + uid.hex[:24]
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


def test_pg_sealed_accept_commits_only_assignment_once(retired_pg, monkeypatch):
    writer, reader, args, _, driver = retired_pg
    identity, proof, _ = _successor(retired_pg, offer_seconds=120)
    oid = args["order_id"]
    before = _order(reader, oid)
    receipts = _children(reader, oid)
    assert len(receipts) == 1
    assert before["offer_expires_at"] == before["route_resume_deadline_at"]
    assert (before["ride_price"], before["ride_base_price"], before["price_estimate"]) == (170, 170, 210)
    assert before["promo_discount_kop"] == args["preview"].promo_discount_kop
    for side_effect in ("_settle_pickup_fee", "_notify_pickup_fee", "_notify_transition", "_cleanup_tried"):
        monkeypatch.setattr(isv, side_effect, _unexpected)
    with reader.connect() as conn:
        lower = _clock(conn)
    assert assignment.accept_sealed_offer(writer, **identity, proof=proof) is assignment.SealedAcceptance.ACCEPTED
    with reader.connect() as conn:
        upper = _clock(conn)
        assert conn.scalar(select(Payment.id).where(Payment.order_id == oid)) is None
        assert conn.scalar(select(LedgerEntry.id).where(LedgerEntry.order_id == oid)) is None
    after = _order(reader, oid)
    assert lower <= after["accepted_at"] <= upper < before["route_resume_deadline_at"]
    assert after == before | {
        "status": S.accepted, "driver_id": driver, "accepted_at": after["accepted_at"],
        "current_offer_driver_id": None, "offer_expires_at": None, **dict.fromkeys(resume._MARKERS),
    }
    evidence = {"owner_id": args["owner_id"], "order_id": oid, "attempt_id": args["attempt_id"],
                "binding": args["agreement"].binding}
    assert _children(reader, oid) == receipts
    assert outcome.committed_evidence(reader, **evidence, now=upper) is outcome.CommittedEvidence.COMMITTED
    assert assignment.accept_sealed_offer(writer, **identity, proof=proof) is assignment.SealedAcceptance.UNKNOWN
    assert _order(reader, oid) == after and _children(reader, oid) == receipts


@pytest.mark.parametrize("lock", ("order", "driver"))
def test_pg_sealed_accept_expires_while_waiting_on_real_row_lock(retired_pg, lock):
    writer, reader, args, _, driver = retired_pg
    identity, proof, observed = _successor(retired_pg, offer_seconds=7)
    oid = args["order_id"]
    before = _order(reader, oid)
    receipts = _children(reader, oid)
    lease, deadline = before["offer_expires_at"], before["route_resume_deadline_at"]
    assert lease < deadline
    target, target_id, table_name, clause = (
        (_O, oid, "instantorder", "FOR UPDATE") if lock == "order"
        else (_USERS, driver, '"user"', "FOR NO KEY UPDATE")
    )
    attempted = Event()
    waiter = {}

    def lock_query(conn, cursor, statement, parameters, context, executemany):
        if f"FROM {table_name}" in statement and clause in statement:
            waiter["pid"] = conn.connection.driver_connection.get_backend_pid()
            attempted.set()

    with ThreadPoolExecutor(max_workers=1) as pool, reader.connect() as blocker:
        blocker.execute(select(target.c.id).where(target.c.id == target_id).with_for_update()).one()
        blocker_pid = blocker.exec_driver_sql("SELECT pg_backend_pid()").scalar_one()
        event.listen(writer, "before_cursor_execute", lock_query)
        try:
            pending = pool.submit(assignment.accept_sealed_offer, writer, **identity, proof=proof)
            try:
                assert attempted.wait(5), f"accept never attempted the {lock} lock"
                with reader.connect() as observer:
                    observer_pid = observer.exec_driver_sql("SELECT pg_backend_pid()").scalar_one()
                    assert len({blocker_pid, waiter["pid"], observer_pid}) == 3

                    def blocked():
                        assert not pending.done(), "accept returned before the actual lock was released"
                        return observer.execute(text("SELECT :holder = ANY(pg_blocking_pids(:waiter))"),
                                                {"holder": blocker_pid, "waiter": waiter["pid"]}).scalar_one()

                    _until(blocked, "PostgreSQL did not report a real blocking edge")
                    assert _clock(observer) < lease
                    _until(lambda: _clock(observer) >= lease, "database clock did not reach the offer lease")
                    assert blocked() and _clock(observer) < deadline
            finally:
                blocker.rollback()  # unchanged row; the lease (not a stale mutation) rejects accept
            assert pending.result(timeout=8) is assignment.SealedAcceptance.UNKNOWN
        finally:
            event.remove(writer, "before_cursor_execute", lock_query)
    assert resume.observe(reader, oid) == observed
    assert _order(reader, oid) == before and _children(reader, oid) == receipts
