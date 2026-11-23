"""Real PostgreSQL proof for inert sealed retirement, claim and successor storage."""
import os
from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta
from threading import Event
from uuid import uuid4

import pytest
from sqlalchemy import create_engine, event, select, text
from sqlalchemy.schema import CreateSchema, DropSchema
from sqlmodel import Session, SQLModel
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
from app import taxi_sealed_resume_claim as claims
from app import taxi_sealed_resume_offer as offers
from app.db import engine
from app.models import InstantOrder, Tariff, User
from app.models import InstantOrderStatus as S
from app.taxi_offered_waypoint_retire import OfferedRetirement, retire_offered_waypoints

pytestmark = pytest.mark.skipif(
    engine.dialect.name != "postgresql" or os.environ.get("AUDIT_PG_CI_SERVICE") != "1",
    reason="isolated PostgreSQL CI service required",
)
_USERS = User.__table__


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
    schema = "pgseal_" + uid.hex
    category = "pgseal_" + uid.hex[:24]
    # Global maintenance must run only in this test's own schema; never
    # purge receipts seeded by another suite in the shared PostgreSQL database.
    with engine.begin() as conn:
        conn.execute(CreateSchema(schema))
    options = {"options": f"-csearch_path={schema}"}
    writer = create_engine(url, hide_parameters=True, connect_args=options)
    reader = create_engine(url, hide_parameters=True, connect_args=options)
    try:
        for isolated in (reader, writer):
            with isolated.connect() as conn:
                assert conn.scalar(text("SELECT current_schema()")) == schema
        SQLModel.metadata.create_all(reader, tables=[_USERS, _T, _O, _CHILD])
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
        args["receipt_expires_at"] = args["preview"].observed_at + timedelta(seconds=5)
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


def _offer_args(claim_args, claim, driver):
    return {**{k: claim_args[k] for k in ("key", "owner_id", "order_id", "marker_id")},
            "claim": claim, "driver_id": driver, "offer_seconds": 120}


def test_pg_sealed_flow_keeps_original_terms_after_independent_receipt_purge(retired_pg):
    writer, reader, args, retired, successor = retired_pg
    oid = args["order_id"]
    original = resume._state(retired)
    expires = args["receipt_expires_at"]
    deadline = original["route_resume_deadline_at"]
    assert deadline - original["route_resume_retired_at"] == timedelta(seconds=120)
    assert (original["ride_price"], original["ride_base_price"], original["price_estimate"]) == (170, 170, 210)
    assert original["promo_discount_kop"] == args["preview"].promo_discount_kop
    evidence = {"owner_id": args["owner_id"], "order_id": oid, "attempt_id": args["attempt_id"],
                "binding": args["agreement"].binding}
    assert outcome.committed_evidence(reader, **evidence, now=expires - timedelta(microseconds=1)) is (
        outcome.CommittedEvidence.COMMITTED
    )
    assert outcome.committed_evidence(reader, **evidence, now=expires) is outcome.CommittedEvidence.UNKNOWN
    # Deterministic as-of maintenance cutoff, no wall-clock sleep. The real
    # receipt delete must not touch the exact stored seal or reset the order.
    with reader.begin() as conn:
        assert outcome.purge_expired(conn, cutoff=expires, limit=1) == 1
    assert _children(reader, oid) == [] and resume.observe(reader, oid) == retired
    assert outcome.committed_evidence(reader, **evidence, now=expires) is outcome.CommittedEvidence.UNKNOWN
    claim_args = _claim_args(args, retired, 120)
    claim = claims.claim_sealed_retirement(writer, **claim_args)
    assert isinstance(claim, claims.SealedResumeClaim) and resume.observe(reader, oid) == claim._observation
    claimed = resume._state(claim._observation)
    assert claimed == original | {"route_resume_claim_id": claimed["route_resume_claim_id"],
                                   "route_resume_ready_at": deadline}
    params = _offer_args(claim_args, claim, successor)
    with ThreadPoolExecutor(max_workers=2) as pool:
        results = [pool.submit(offers.store_sealed_successor_offer, writer, **params) for _ in range(2)]
        winners = [value for x in results if (value := x.result(timeout=8)) is not None]
    assert len(winners) == 1
    final = resume._state(winners[0])
    assert resume.observe(reader, oid) == winners[0] and not _children(reader, oid)
    assert final == original | {"status": S.offered, "current_offer_driver_id": successor,
                                "offered_at": final["offered_at"], "offer_expires_at": deadline,
                                "search_round": original["search_round"] + 1,
                                "route_resume_ready_at": None, "route_resume_claim_id": None}
    assert original["route_resume_retired_at"] <= final["offered_at"] < deadline


def test_pg_sealed_successor_loses_expired_claim_while_physically_blocked(retired_pg):
    writer, reader, args, retired, successor = retired_pg
    oid = args["order_id"]
    claim_args = _claim_args(args, retired, 6)
    claim = claims.claim_sealed_retirement(writer, **claim_args)
    assert isinstance(claim, claims.SealedResumeClaim)
    claimed = resume._state(claim._observation)
    lease, deadline = claimed["route_resume_ready_at"], claimed["route_resume_deadline_at"]
    assert lease < deadline
    attempting = Event()
    waiter = {}

    def lock_attempt(conn, cursor, statement, parameters, context, executemany):
        if "FROM instantorder" in statement and "FOR UPDATE" in statement:
            waiter["pid"] = conn.connection.driver_connection.get_backend_pid()
            attempting.set()

    with ThreadPoolExecutor(max_workers=1) as pool, reader.connect() as blocker:
        blocker.execute(select(_O.c.id).where(_O.c.id == oid).with_for_update()).one()
        blocker_pid = blocker.exec_driver_sql("SELECT pg_backend_pid()").scalar_one()
        event.listen(writer, "before_cursor_execute", lock_attempt)
        try:
            pending = pool.submit(offers.store_sealed_successor_offer, writer, **_offer_args(claim_args, claim, successor))
            try:
                assert attempting.wait(5)
                with reader.connect() as observer:
                    observer_pid = observer.exec_driver_sql("SELECT pg_backend_pid()").scalar_one()
                    assert len({blocker_pid, waiter["pid"], observer_pid}) == 3

                    def blocked():
                        assert not pending.done()
                        return observer.execute(text("SELECT :holder = ANY(pg_blocking_pids(:waiter))"),
                                                {"holder": blocker_pid, "waiter": waiter["pid"]}).scalar_one()

                    _until(blocked, "PostgreSQL did not report real order lock contention")
                    assert _clock(observer) < lease
                    _until(lambda: _clock(observer) >= lease, "database clock did not reach the claim lease")
                    assert blocked() and _clock(observer) < deadline
            finally:
                blocker.rollback()  # unlock unchanged row; only time has passed
            assert pending.result(timeout=8) is None
        finally:
            event.remove(writer, "before_cursor_execute", lock_attempt)
    assert resume.observe(reader, oid) == claim._observation
