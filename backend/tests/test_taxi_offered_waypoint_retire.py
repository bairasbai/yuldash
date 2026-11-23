"""Real SQLite route retirement transaction, no API/provider/dispatch."""
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta
import sqlite3
from threading import Event
from time import monotonic
from uuid import uuid4

import pytest
from sqlalchemy import event, select, update

from app import instant_service as isv, pricing, taxi_route_edit_outcome as out
from app import taxi_route_resume as resume
from app.config import settings
from app.models import InstantOrder, InstantOrderStatus as S, Tariff, TaxiRouteEditOutcome
from app.taxi_offered_waypoint_agreement import bind_offered_waypoints
from app.taxi_offered_waypoint_retire import OfferedRetirement, retire_offered_waypoints
from app.timeutil import utcnow
from test_taxi_offered_waypoint_preview import (
    _preview, _row, _STOP, _unexpected, route, testdb,  # noqa: F401 - pytest fixtures
)

_KEY = b"\x27" * 32
_O = InstantOrder.__table__
_T = Tariff.__table__
_CHILD = TaxiRouteEditOutcome.__table__


@pytest.fixture
def healthy(request):
    data = request.getfixturevalue("testdb")
    db = data[0]
    def enable_fk(raw, *_):
        raw.execute("PRAGMA foreign_keys=ON").close()
    event.listen(db, "checkout", enable_fk)
    try:
        yield data
    finally:
        event.remove(db, "checkout", enable_fk)


def _args(data, duration=None):
    _, owner, oid, *_ = data
    p = _preview(data, until=utcnow() + timedelta(seconds=duration) if duration else None)
    assert p is not None
    args = dict(key=_KEY, owner_id=owner, order_id=oid, preview=p)
    return args | dict(
        agreement=bind_offered_waypoints(**args), attempt_id=str(uuid4()),
        receipt_expires_at=p.observed_at + timedelta(minutes=3),
    )


def _clock(conn):
    return datetime.fromisoformat(conn.exec_driver_sql(
        "SELECT strftime('%Y-%m-%d %H:%M:%f000','now')",
    ).scalar_one())


def _wait_db(db, boundary):
    until = monotonic() + 7
    with db.connect() as c:
        while _clock(c) < boundary:
            assert monotonic() < until, "Did not observe database clock crossing"
            Event().wait(0.01)


def _children(db, oid):
    with db.connect() as c:
        return c.execute(select(_CHILD).where(_CHILD.c.order_id == oid)).mappings().all()


def _forbid_external(monkeypatch):
    monkeypatch.setattr(pricing, "route_metrics", _unexpected)
    monkeypatch.setattr(isv, "destination_quote", _unexpected)
    monkeypatch.setattr(isv, "_push_offer", _unexpected)


def test_atomic_offer_retirement_and_exact_marker_existing_reader(healthy, monkeypatch, request):
    request.getfixturevalue("route")
    db, owner, oid, recipient, other, _, _ = healthy
    args = _args(healthy)
    _forbid_external(monkeypatch)
    prior = _row(db, oid)
    seen = []
    def at_update(conn, cursor, statement, parameters, context, executemany):
        if statement.startswith("UPDATE instantorder SET"):
            assert conn.scalar(select(_CHILD.c.attempt_id)) == args["attempt_id"]
            seen.append((_row(db, oid), _children(db, oid)))
    event.listen(db, "before_cursor_execute", at_update)
    try:
        assert retire_offered_waypoints(db, **args) is OfferedRetirement.COMMITTED
    finally:
        event.remove(db, "before_cursor_execute", at_update)
    assert seen == [(prior, [])]

    row = _row(db, oid)
    assert row.status is S.searching and row.driver_id is None
    assert row.current_offer_driver_id is None and row.offer_expires_at is None
    assert (row.searching_at, row.offered_at, row.search_round, row.tariff_id) == (
        prior.searching_at, prior.offered_at, prior.search_round, prior.tariff_id,
    )
    assert row.route_resume_previous_driver_id == recipient
    assert row.route_resume_claim_id is None and len(row.route_resume_id) == 32
    assert row.route_resume_retired_at == row.route_resume_ready_at == row.destination_changed_at
    assert row.route_resume_deadline_at - row.route_resume_retired_at == timedelta(seconds=120)
    assert row.route_resume_deadline_at > prior.offer_expires_at  # separate business horizons
    assert row.route_resume_retired_at < args["agreement"].valid_until
    assert row.ride_price == 170 and row.ride_base_price == 170 and row.price_estimate == 210
    assert row.promo_discount_kop == args["preview"].promo_discount_kop
    assert row.pickup_fee_kop == prior.pickup_fee_kop and row.price_final is None and not row.paid
    assert (row.from_lat, row.from_lng, row.to_lat, row.to_lng) == (
        prior.from_lat, prior.from_lng, prior.to_lat, prior.to_lng,
    )
    assert isv.parse_waypoints(row.waypoints_json) == [_STOP | {"done": False}]
    children = _children(db, oid)
    assert len(children) == 1
    assert (children[0]["owner_id"], children[0]["attempt_id"],
            children[0]["binding"], children[0]["expires_at"]) == (
        owner, args["attempt_id"], args["agreement"].binding, args["receipt_expires_at"],
    )
    assert retire_offered_waypoints(db, **args) is OfferedRetirement.UNKNOWN
    assert retire_offered_waypoints(db, **(args | {"attempt_id": str(uuid4())})) is OfferedRetirement.UNKNOWN
    assert _row(db, oid) == row and _children(db, oid) == children

    # Existing inactive primitives independently recognize the real created
    # state, but retirement itself did not call even these SQL operations.
    observed = resume.observe(db, oid)
    assert resume._valid(resume._state(observed))
    claimed = resume.claim(db, observed, lease_seconds=30)
    assert claimed and resume.store_offer(db, claimed, driver_id=recipient, offer_seconds=20) is None
    assert resume.store_offer(db, claimed, driver_id=other, offer_seconds=20) is not None
    final = _row(db, oid)
    assert final.current_offer_driver_id == other and final.search_round == prior.search_round + 1
    assert final.offer_expires_at <= row.route_resume_deadline_at


@pytest.mark.parametrize("kind", ("accepted", "cancelled", "newer", "route", "tariff", "deadline"))
def test_actual_other_writer_than_phase_and_quote_wins_under_lock(healthy, request, kind):
    request.getfixturevalue("route")
    db, _, oid, recipient, other, tariff, _ = healthy
    args = _args(healthy, duration=2 if kind == "deadline" else None)
    initial = _row(db, oid)
    entered, raw_ids = Event(), []

    def witness(conn, cursor, statement, parameters, context, executemany):
        if statement == "BEGIN IMMEDIATE":
            raw_ids.append(conn.connection.driver_connection)
            entered.set()

    with ThreadPoolExecutor(max_workers=1) as pool:
        with db.begin() as other_tx:
            other_tx.exec_driver_sql("BEGIN IMMEDIATE")
            assert other_tx.connection.driver_connection.in_transaction
            event.listen(db, "before_cursor_execute", witness)
            future = pool.submit(retire_offered_waypoints, db, **args)
            assert entered.wait(timeout=5)
            assert raw_ids[0] is not other_tx.connection.driver_connection and not future.done()
            if kind == "deadline":
                _wait_db(db, args["agreement"].valid_until)
            elif kind == "tariff":
                other_tx.execute(update(_T).where(_T.c.id == tariff).values(per_min=64))
            else:
                vals = {
                    "accepted": {"status": S.accepted, "driver_id": recipient, "accepted_at": initial.offered_at},
                    "cancelled": {"status": S.cancelled, "cancelled_at": initial.offered_at},
                    "newer": {"current_offer_driver_id": other, "search_round": 2},
                    "route": {"to_text": "SYNTHETIC NEW DESTINATION"},
                }[kind]
                other_tx.execute(update(_O).where(_O.c.id == oid).values(**vals))
        assert future.result(timeout=10) is OfferedRetirement.UNKNOWN
    event.remove(db, "before_cursor_execute", witness)
    assert _children(db, oid) == []
    saved = _row(db, oid)
    assert saved.route_resume_id is None and saved.ride_price == initial.ride_price
    if kind not in ("deadline", "tariff"):
        assert all(getattr(saved, name) == value for name, value in vals.items())


@pytest.mark.parametrize("kind", ("final_expiry", "order_cas", "tariff_cas"))
def test_real_stage_then_zero_update_has_no_receipt_or_retirement(healthy, request, kind):
    request.getfixturevalue("route")
    db, _, oid, _, _, tariff, _ = healthy
    args = _args(healthy, duration=2 if kind == "final_expiry" else None)
    prior = _row(db, oid)
    observed = []

    def witness(conn, cursor, statement, parameters, context, executemany):
        if not statement.startswith("UPDATE instantorder SET"):
            return
        assert conn.scalar(select(_CHILD.c.attempt_id)) == args["attempt_id"]
        observed.append(True)
        if kind == "final_expiry":
            _wait_db(db, args["agreement"].valid_until)
        elif kind == "order_cas":
            assert conn.scalar(select(_O.c.ride_base_price)) == 131
        else:
            assert conn.scalar(select(_T.c.per_km)) == 66

    if kind != "final_expiry":
        mutation = ("UPDATE instantorder SET ride_base_price=131 WHERE id=NEW.order_id"
                    if kind == "order_cas" else "UPDATE tariff SET per_km=66")
        with db.begin() as c:
            c.exec_driver_sql(f"CREATE TRIGGER synthetic_diverge AFTER INSERT ON taxirouteeditoutcome "
                              f"BEGIN {mutation}; END")
    event.listen(db, "before_cursor_execute", witness)
    try:
        assert retire_offered_waypoints(db, **args) is OfferedRetirement.UNKNOWN
    finally:
        event.remove(db, "before_cursor_execute", witness)
    assert observed == [True] and _row(db, oid) == prior and _children(db, oid) == []
    with db.connect() as c:
        assert c.scalar(select(_T.c.per_km).where(_T.c.id == tariff)) == 10


@pytest.mark.parametrize("lost_ack", (False, True))
def test_supported_commit_ack_or_unknown_but_never_reapply(healthy, request, monkeypatch, lost_ack):
    request.getfixturevalue("route")
    db, owner, oid, *_ = healthy
    args = _args(healthy)
    before = _row(db, oid)
    original = db.dialect.do_commit

    def failed(raw):
        if lost_ack:
            original(raw)
        raise sqlite3.OperationalError("synthetic commit interruption")

    with monkeypatch.context() as m:
        m.setattr(db.dialect, "do_commit", failed)
        assert retire_offered_waypoints(db, **args) is OfferedRetirement.UNKNOWN
    evidence = out.committed_evidence(
        db, owner_id=owner, order_id=oid, attempt_id=args["attempt_id"],
        binding=args["agreement"].binding, now=utcnow(),
    )
    if lost_ack:
        assert evidence is out.CommittedEvidence.COMMITTED
        row = _row(db, oid)
        assert row.route_resume_retired_at and len(_children(db, oid)) == 1
        assert retire_offered_waypoints(db, **args) is OfferedRetirement.UNKNOWN
        assert _row(db, oid) == row
    else:
        assert evidence is out.CommittedEvidence.UNKNOWN
        assert _row(db, oid) == before and _children(db, oid) == []


def test_wrong_actor_exhaustion_fallback_and_matching_receipt_never_retire(healthy, request, monkeypatch):
    request.getfixturevalue("route")
    db, owner, oid, _, other, _, _ = healthy
    args = _args(healthy)
    orig = _row(db, oid)
    assert retire_offered_waypoints(db, **(args | {"owner_id": other})) is OfferedRetirement.UNKNOWN
    with monkeypatch.context() as m:
        m.setattr(settings, "instant_max_offers", orig.search_round)
        assert retire_offered_waypoints(db, **args) is OfferedRetirement.UNKNOWN
    with db.begin() as c:
        c.execute(update(_O).values(fallback_categories="comfort").where(_O.c.id == oid))
    assert retire_offered_waypoints(db, **args) is OfferedRetirement.UNKNOWN
    with db.begin() as c:
        c.execute(update(_O).values(fallback_categories="").where(_O.c.id == oid))
    assert _row(db, oid) == orig and _children(db, oid) == []
    with db.begin() as c:
        c.exec_driver_sql("BEGIN IMMEDIATE")
        assert out.stage_committed(
            c, owner_id=owner, order_id=oid, attempt_id=args["attempt_id"],
            binding=args["agreement"].binding, expires_at=args["receipt_expires_at"], now=_clock(c),
        ) is out.StageResult.STAGED
    recorded = _children(db, oid)
    assert retire_offered_waypoints(db, **(args | {
        "receipt_expires_at": args["receipt_expires_at"] + timedelta(days=1),
    })) is OfferedRetirement.UNKNOWN
    assert _row(db, oid) == orig and _children(db, oid) == recorded
