"""Actual InstantOrder storage contract. No matcher/endpoint invokes this module."""
from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta
from threading import Event
from time import sleep
from uuid import uuid4

import pytest
from sqlalchemy import event
from sqlmodel import Session

from app import instant_service as isv, taxi_route_resume as resume
from app.db import engine
from app.models import InstantOrder, InstantOrderStatus as S, UserRole
from app.timeutil import utcnow


def _row(oid):
    with Session(engine) as s:
        return s.get(InstantOrder, oid)


def _seed(user_factory, *, format="marked"):
    uid = user_factory("ResumePassenger")["id"]
    old = user_factory("ResumeOldDriver", role=UserRole.driver)["id"]
    new = user_factory("ResumeNewDriver", role=UserRole.driver)["id"]
    tick = utcnow()
    o = InstantOrder(passenger_id=uid, status=S.searching, created_at=tick - timedelta(minutes=1),
                     searching_at=tick - timedelta(seconds=30), offered_at=tick - timedelta(seconds=10),
                     search_round=2, reassigns=1, from_lat=54.72, from_lng=55.92, to_lat=54.8101,
                     to_lng=55.9753, to_text="synthetic new destination", waypoints_json="[]",
                     ride_price=260, price_estimate=320, promo_discount_kop=3200)
    if format != "legacy":
        o.route_resume_id = uuid4().hex
        o.route_resume_previous_driver_id = old
        o.route_resume_retired_at = tick
        o.route_resume_deadline_at = tick + timedelta(seconds=120)
        o.route_resume_ready_at = tick - timedelta(seconds=1)
    if format == "scheduled":
        o.scheduled_at = tick - timedelta(days=1)
    elif format == "waiting":
        o.wait_until = tick + timedelta(minutes=15)
    elif format == "malformed":
        o.route_resume_previous_driver_id = None
    with Session(engine) as s:
        s.add(o)
        s.commit()
        s.refresh(o)
        return o.id, old, new


def test_marked_claim_and_offer_once_without_changing_agreed_route_fare_clock(user_factory):
    oid, prior, new = _seed(user_factory)
    initial = _row(oid)
    expected = resume.observe(engine, oid)
    valid = resume.claim(engine, expected, lease_seconds=30)
    assert valid and resume.claim(engine, expected, lease_seconds=30) is None
    assert resume.claim(engine, valid, lease_seconds=30) is None  # still leased
    assert resume.store_offer(engine, valid, driver_id=prior, offer_seconds=20) is None
    assert resume.store_offer(engine, valid, driver_id=new, offer_seconds=120)
    after = _row(oid)
    assert (after.status, after.current_offer_driver_id, after.search_round) == (S.offered, new, 3)
    assert after.offered_at < after.offer_expires_at == initial.route_resume_deadline_at
    assert after.route_resume_id == initial.route_resume_id
    assert after.route_resume_retired_at == initial.route_resume_retired_at
    assert after.route_resume_deadline_at == initial.route_resume_deadline_at
    assert after.route_resume_claim_id is None and after.route_resume_ready_at is None
    assert (after.searching_at, after.reassigns, after.to_text, after.waypoints_json,
            after.price_estimate, after.ride_price, after.promo_discount_kop) == (
                initial.searching_at, initial.reassigns, initial.to_text, initial.waypoints_json,
                initial.price_estimate, initial.ride_price, initial.promo_discount_kop,
            )
    # Modeled process exit after durable offer but before Redis or notification.
    assert resume.claim(engine, resume.observe(engine, oid), lease_seconds=30) is None
    assert resume.store_offer(engine, valid, driver_id=new, offer_seconds=20) is None
    assert after.route_resume_id not in repr(after) and after.route_resume_id not in repr(valid)
    assert not any(key.startswith("route_resume") for key in after.model_dump())


@pytest.mark.parametrize("format", ("legacy", "scheduled", "waiting", "malformed"))
def test_unmarked_and_out_of_scope_or_partial_rows_do_not_become_claims(user_factory, format):
    oid, _, _ = _seed(user_factory, format=format)
    obs = resume.observe(engine, oid)
    if format == "legacy":
        assert obs is None
    else:
        assert obs and resume.claim(engine, obs, lease_seconds=30) is None
        assert resume.store_timeout(engine, obs) is None
    assert _row(oid).route_resume_claim_id is None


@pytest.mark.parametrize("changed", ("route", "cancel", "newer", "fare"))
def test_losing_observation_cannot_apply_after_an_independent_writer(user_factory, changed):
    oid, _, new = _seed(user_factory)
    old = resume.claim(engine, resume.observe(engine, oid), lease_seconds=25)
    assert old
    with Session(engine) as s:
        o = s.get(InstantOrder, oid)
        if changed == "route":
            o.to_lat += 0.001
        elif changed == "fare":
            o.promo_discount_kop -= 1
        elif changed == "cancel":
            o.status, o.cancelled_at = S.cancelled, utcnow()
        else:
            o.route_resume_id, o.route_resume_claim_id = uuid4().hex, None
        s.commit()
    assert resume.store_offer(engine, old, driver_id=new, offer_seconds=20) is None
    assert resume.store_timeout(engine, old) is None
    assert _row(oid).current_offer_driver_id is None


def test_clock_expiry_reclaim_and_absolute_timeout(user_factory):
    oid, _, new = _seed(user_factory)
    with Session(engine) as s:
        o = s.get(InstantOrder, oid)
        o.route_resume_deadline_at = utcnow() + timedelta(seconds=3)
        deadline = o.route_resume_deadline_at
        s.commit()
    first = resume.claim(engine, resume.observe(engine, oid), lease_seconds=1)
    assert first
    claimed = _row(oid)
    sleep(max(0, (claimed.route_resume_ready_at - utcnow()).total_seconds()) + 0.04)
    assert resume.store_offer(engine, first, driver_id=new, offer_seconds=30) is None
    second = resume.claim(engine, first, lease_seconds=120)
    assert second and second != first and _row(oid).route_resume_ready_at == deadline
    assert _row(oid).route_resume_retired_at == claimed.route_resume_retired_at
    assert _row(oid).route_resume_deadline_at == claimed.route_resume_deadline_at == deadline
    assert resume.store_offer(engine, first, driver_id=new, offer_seconds=20) is None
    assert resume.store_timeout(engine, second) is None
    sleep(max(0, (deadline - utcnow()).total_seconds()) + 0.04)
    assert resume.store_offer(engine, second, driver_id=new, offer_seconds=20) is None
    assert resume.claim(engine, second, lease_seconds=1) is None
    assert resume.store_timeout(engine, second)
    stopped = _row(oid)
    assert (stopped.status, stopped.cancel_by, stopped.cancel_reason) == (S.cancelled, "system", "route_resume_timeout")
    assert stopped.cancelled_at >= deadline
    assert stopped.status not in isv._MATCHABLE and stopped.wait_until is None
    assert resume.observe(engine, oid) is None and resume.store_timeout(engine, second) is None
    assert (stopped.price_estimate, stopped.promo_discount_kop) == (320, 3200)


def test_capped_offer_timeout_never_revives_or_records_an_assignment(user_factory):
    oid, _, new = _seed(user_factory)
    with Session(engine) as s:
        o = s.get(InstantOrder, oid)
        o.route_resume_deadline_at = utcnow() + timedelta(seconds=2)
        deadline = o.route_resume_deadline_at
        s.commit()
    claimed = resume.claim(engine, resume.observe(engine, oid), lease_seconds=30)
    outcome = resume.store_offer(engine, claimed, driver_id=new, offer_seconds=20)
    assert outcome and _row(oid).offer_expires_at == deadline
    assert resume.store_timeout(engine, outcome) is None
    sleep(max(0, (deadline - utcnow()).total_seconds()) + 0.04)
    assert resume.store_timeout(engine, outcome)
    stopped = _row(oid)
    assert (stopped.status, stopped.driver_id, stopped.current_offer_driver_id,
            stopped.offer_expires_at, stopped.route_resume_id) == (S.cancelled, None, None, None, None)


def test_two_workers_and_one_short_committed_claim(user_factory):
    oid, _, _ = _seed(user_factory)
    expected = resume.observe(engine, oid)
    with ThreadPoolExecutor(max_workers=2) as pool:
        claims = list(pool.map(lambda _: resume.claim(engine, expected, lease_seconds=30), range(2)))
    assert sum(item is not None for item in claims) == 1
    assert _row(oid).search_round == 2


def test_offer_uses_sql_time_after_waiting_for_physical_sqlite_writer(user_factory):
    if engine.dialect.name != "sqlite":
        pytest.skip("SQLite writer wait probe; PostgreSQL needs an actual lock+PID case")
    oid, _, new = _seed(user_factory)
    with Session(engine) as s:
        o = s.get(InstantOrder, oid)
        o.route_resume_deadline_at = utcnow() + timedelta(seconds=2)
        deadline = o.route_resume_deadline_at
        s.commit()
    claim = resume.claim(engine, resume.observe(engine, oid), lease_seconds=30)
    entered = Event()

    def watch(conn, cursor, statement, parameters, context, executemany):
        if statement == "BEGIN IMMEDIATE":
            entered.set()

    with engine.connect() as blocker:
        blocker.exec_driver_sql("BEGIN IMMEDIATE")
        event.listen(engine, "before_cursor_execute", watch)
        try:
            with ThreadPoolExecutor(max_workers=1) as pool:
                late = pool.submit(resume.store_offer, engine, claim, driver_id=new, offer_seconds=20)
                try:
                    assert entered.wait(3) and not late.done()
                    sleep(max(0, (deadline - utcnow()).total_seconds()) + 0.04)
                finally:
                    blocker.rollback()
                assert late.result(timeout=5) is None
        finally:
            event.remove(engine, "before_cursor_execute", watch)
    assert _row(oid).status == S.searching


def test_rollback_after_offer_sql_before_commit_leaves_retryable_claim(user_factory):
    oid, _, new = _seed(user_factory)
    claim = resume.claim(engine, resume.observe(engine, oid), lease_seconds=30)

    def stop(conn, cursor, statement, parameters, context, executemany):
        if statement.startswith("UPDATE instantorder SET"):
            raise RuntimeError("synthetic failure after SQL and before commit")

    event.listen(engine, "after_cursor_execute", stop)
    try:
        with pytest.raises(RuntimeError, match="before commit"):
            resume.store_offer(engine, claim, driver_id=new, offer_seconds=20)
    finally:
        event.remove(engine, "after_cursor_execute", stop)
    assert _row(oid).status == S.searching
    assert resume.observe(engine, oid) == claim
    assert resume.store_offer(engine, claim, driver_id=new, offer_seconds=20)


@pytest.mark.parametrize("invalid", (True, 0, 121, -1, 1.5))
def test_lease_duration_outside_bounded_primitive_does_not_mutate(user_factory, invalid):
    oid, _, _ = _seed(user_factory)
    expected = resume.observe(engine, oid)
    with pytest.raises(ValueError, match="integer 1..120"):
        resume.claim(engine, expected, lease_seconds=invalid)
    assert resume.observe(engine, oid) == expected


@pytest.mark.parametrize("malformation", ("too_long", "zero", "future", "no_origin"))
def test_invalid_persisted_window_cannot_claim_or_store_even_with_claim_nonce(user_factory, malformation):
    oid, _, candidate = _seed(user_factory)
    with Session(engine) as s:
        row = s.get(InstantOrder, oid)
        if malformation == "too_long":
            row.route_resume_deadline_at = row.route_resume_retired_at + timedelta(seconds=120, microseconds=1)
        elif malformation == "zero":
            row.route_resume_deadline_at = row.route_resume_retired_at
        elif malformation == "future":
            row.route_resume_retired_at = utcnow() + timedelta(seconds=10)
            row.route_resume_deadline_at = row.route_resume_retired_at + timedelta(seconds=30)
        else:
            row.route_resume_retired_at = None
        # First test claim with an otherwise due marker; then prove a corrupt
        # pre-existing claim cannot confer authority either.
        s.commit()
    bad = resume.observe(engine, oid)
    assert resume.claim(engine, bad, lease_seconds=30) is None
    assert resume.observe(engine, oid) == bad
    with Session(engine) as s:
        row = s.get(InstantOrder, oid)
        row.route_resume_claim_id = "c" * 32
        row.route_resume_ready_at = utcnow() + timedelta(seconds=3)
        s.commit()
    bad_lease = resume.observe(engine, oid)
    assert resume.store_offer(engine, bad_lease, driver_id=candidate, offer_seconds=20) is None
    assert resume.store_timeout(engine, bad_lease) is None
    assert resume.observe(engine, oid) == bad_lease
    assert _row(oid).current_offer_driver_id is None
