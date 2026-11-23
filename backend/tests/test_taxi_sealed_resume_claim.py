"""Real private SQLite sealed claim. No worker, provider, driver selection or offer."""
from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace
from datetime import timedelta
import sqlite3
from threading import Event
from uuid import uuid4

import pytest
from sqlalchemy import event

from app import instant_service as isv, pricing, taxi_route_edit_outcome as out
from app import taxi_retirement_agreement_seal as seal, taxi_route_resume as resume, taxi_sealed_resume_claim as claim
from app.models import InstantOrderStatus as S
from app.taxi_marked_offer_card import MarkedOfferCardProof
from app.taxi_offered_waypoint_agreement import bind_offered_waypoints
from app.taxi_offered_waypoint_preview import _FIELDS
from app.taxi_offered_waypoint_retire import OfferedRetirement, retire_offered_waypoints
from app.timeutil import utcnow
from test_taxi_offered_waypoint_preview import _mutate, _unexpected
from test_taxi_offered_waypoint_retire import _args, _clock, _wait_db, healthy, route, testdb  # noqa: F401



@pytest.fixture
def retired(request, monkeypatch):
    request.getfixturevalue("route")
    data = request.getfixturevalue("healthy")
    db, owner, oid, _, other, *_ = data
    kwargs = _args(data)
    # Short client receipt independent of the owned marker's exact 120s.
    kwargs["receipt_expires_at"] = utcnow() + timedelta(seconds=3)
    assert retire_offered_waypoints(db, **kwargs) is OfferedRetirement.COMMITTED
    for name in ("destination_quote", "try_offer_next", "_push_offer", "_settle_pickup_fee"):
        monkeypatch.setattr(isv, name, _unexpected)
    monkeypatch.setattr(pricing, "route_metrics", _unexpected)
    expected = resume.observe(db, oid)
    assert resume._state(expected)[resume._SEAL]
    return db, owner, oid, other, kwargs, expected


def _params(data, **overrides):
    _, owner, oid, _, kw, expected = data
    defaults = dict(key=kw["key"], owner_id=owner, order_id=oid, expected=expected,
                    marker_id=resume._state(expected)["route_resume_id"], lease_seconds=25)
    return defaults | overrides


def test_actual_retire_purge_and_claim_without_changing_approved_terms(retired, monkeypatch):
    db, owner, oid, other, kwargs, expected = retired
    before = resume._state(expected)
    assert resume.claim(db, expected, lease_seconds=25) is None
    receipt = dict(owner_id=owner, order_id=oid, attempt_id=kwargs["attempt_id"], binding=kwargs["agreement"].binding)
    assert out.committed_evidence(db, **receipt, now=kwargs["receipt_expires_at"] - timedelta(microseconds=1)) is (
        out.CommittedEvidence.COMMITTED
    )
    _wait_db(db, kwargs["receipt_expires_at"])
    with db.begin() as conn:
        conn.exec_driver_sql("BEGIN IMMEDIATE")
        assert out.purge_expired(conn, cutoff=_clock(conn), limit=1) == 1
    assert out.committed_evidence(db, **receipt, now=utcnow()) is out.CommittedEvidence.UNKNOWN
    for name in ("stage_committed", "committed_evidence", "purge_expired"):
        monkeypatch.setattr(out, name, _unexpected)
    actual = claim.claim_sealed_retirement(db, **_params(retired, lease_seconds=120))
    assert actual and resume.observe(db, oid) == actual._observation
    after = resume._state(actual._observation)
    assert after == before | {
        "route_resume_claim_id": after["route_resume_claim_id"],
        "route_resume_ready_at": before["route_resume_deadline_at"],
    }
    assert resume._token(after["route_resume_claim_id"])
    assert after["status"] is S.searching and after["current_offer_driver_id"] is None
    assert after["route_resume_deadline_at"] - after["route_resume_retired_at"] == timedelta(seconds=120)
    assert claim.claim_sealed_retirement(db, **_params(retired)) is None
    assert resume.store_offer(db, actual._observation, driver_id=other, offer_seconds=20) is None


@pytest.mark.parametrize("changed", ("route", "fare", "generation", "partial", "pending", "assigned", "raw_text"))
def test_neither_stale_nor_fresh_unproven_marker_is_claimable(retired, changed):
    db, _, oid, other, _, expected = retired
    original = resume._state(expected)
    edit = {
        "route": {"from_text": "SYNTHETIC ALTERED ORIGIN"},
        "fare": {"ride_price": original["ride_price"] + 10, "price_estimate": original["price_estimate"] + 10},
        "generation": {"route_resume_id": uuid4().hex},
        "partial": {resume._SEAL: None},
        "pending": {"pending_price": 170},
        "assigned": {"driver_id": other},
    }
    if changed == "raw_text":
        with db.begin() as conn:
            conn.exec_driver_sql("UPDATE instantorder SET route_resume_agreement_seal=? WHERE id=?", ("x"*32, oid))
    else:
        _mutate(db, oid, **edit[changed])
    fresh = resume.observe(db, oid)
    assert claim.claim_sealed_retirement(db, **_params(retired)) is None
    assert claim.claim_sealed_retirement(db, **_params(
        retired, expected=fresh, marker_id=resume._state(fresh)["route_resume_id"],
    )) is None
    assert resume.observe(db, oid) == fresh


def test_wrong_owner_order_generation_key_or_card_is_not_a_claim(retired):
    db, owner, oid, other, _, expected = retired
    snapshot = resume._state(expected)
    for bad in (
        {"owner_id": other}, {"order_id": oid+1}, {"marker_id": uuid4().hex}, {"key": b"x"*32},
        {"expected": MarkedOfferCardProof(snapshot[resume._SEAL])}, {"owner_id": True},
    ):
        assert claim.claim_sealed_retirement(db, **_params(retired, **bad)) is None
    assert resume.observe(db, oid) == expected


def test_exact_ready_lease_takeover_requires_fresh_owned_observation(retired):
    db, _, oid, _, _, expected = retired
    first = claim.claim_sealed_retirement(db, **_params(retired, lease_seconds=1))
    assert first and not claim.claim_sealed_retirement(db, **_params(retired, expected=first._observation))
    in_flight = resume._state(first._observation)
    _wait_db(db, in_flight["route_resume_ready_at"])
    assert claim.claim_sealed_retirement(db, **_params(retired, expected=expected)) is None
    successor = claim.claim_sealed_retirement(db, **_params(retired, expected=first._observation))
    assert successor and resume.observe(db, oid) == successor._observation
    renewed = resume._state(successor._observation)
    assert renewed["route_resume_claim_id"] != in_flight["route_resume_claim_id"]
    assert renewed == in_flight | {
        "route_resume_ready_at": renewed["route_resume_ready_at"],
        "route_resume_claim_id": renewed["route_resume_claim_id"],
    }


@pytest.fixture
def near_deadline(retired):
    # Keep the real original 120-second proof and unchanged clock semantics.
    # Choose a synthetic signed retirement anchored 118 seconds in the past.
    # Only the fixture can create that observation. Real SQL clock controls
    # and the actual marker stay unmodified while waiting/crossing the cutoff.
    db, owner, oid, _, kwargs, current = retired
    initial_state = resume._state(current)
    # Recreate the OLD signed preview in isolated synthetic test state so the
    # exact new timestamps are authenticated, rather than coding a bypass.
    snapshot = kwargs["preview"]._source
    source = dict(zip(_FIELDS, snapshot[:len(_FIELDS)], strict=True))
    with db.connect() as conn:
        now = _clock(conn)
    at = now - timedelta(seconds=118)
    source |= {
        "created_at": at-timedelta(seconds=3), "searching_at": at-timedelta(seconds=2),
        "offered_at": at-timedelta(seconds=1), "offer_expires_at": at+timedelta(seconds=60),
    }
    old_tuple = tuple(source[name] for name in _FIELDS) + snapshot[len(_FIELDS):]
    p = replace(kwargs["preview"], _source=old_tuple, observed_at=at-timedelta(milliseconds=10),
                valid_until=at+timedelta(seconds=50))
    old_agreement = bind_offered_waypoints(kwargs["key"], owner_id=owner, order_id=oid, preview=p)
    fingerprint = seal.bind_retirement_agreement(
        kwargs["key"], owner_id=owner, order_id=oid, old_source=old_tuple, preview=p, agreement=old_agreement,
        marker_id=initial_state["route_resume_id"], retired_at=at, deadline_at=at+timedelta(seconds=120),
    )
    assert fingerprint
    _mutate(db, oid, created_at=source["created_at"], searching_at=source["searching_at"],
            offered_at=source["offered_at"], destination_changed_at=at,
            route_resume_retired_at=at, route_resume_ready_at=at,
            route_resume_deadline_at=at+timedelta(seconds=120), **{resume._SEAL: fingerprint._digest})
    return resume.observe(db, oid)


@pytest.mark.parametrize("boundary", ("lock_wait", "final_write"))
def test_real_deadline_crossing_in_lock_or_after_verified_read_never_claims(retired, near_deadline, boundary):
    db, _, oid, *_ = retired
    deadline = resume._state(near_deadline)["route_resume_deadline_at"]
    entry = Event()
    own_conn = []

    def witness(conn, cursor, statement, parameters, context, executemany):
        if boundary == "lock_wait" and statement == "BEGIN IMMEDIATE":
            own_conn.append(conn.connection.driver_connection)
            entry.set()
        if boundary == "final_write" and statement.startswith("UPDATE instantorder SET route_resume"):
            assert conn.connection.driver_connection.in_transaction
            entry.set()
            _wait_db(db, deadline)

    args = _params(retired, expected=near_deadline)
    if boundary == "lock_wait":
        with ThreadPoolExecutor(max_workers=1) as pool:
            with db.begin() as rival:
                rival.exec_driver_sql("BEGIN IMMEDIATE")
                assert _clock(rival) < deadline
                event.listen(db, "before_cursor_execute", witness)
                try:
                    pending = pool.submit(claim.claim_sealed_retirement, db, **args)
                    assert entry.wait(timeout=3) and not pending.done()
                    assert own_conn[0] is not rival.connection.driver_connection
                    _wait_db(db, deadline)
                finally:
                    event.remove(db, "before_cursor_execute", witness)
            assert pending.result(timeout=7) is None
    else:
        event.listen(db, "before_cursor_execute", witness)
        try:
            assert claim.claim_sealed_retirement(db, **args) is None
        finally:
            event.remove(db, "before_cursor_execute", witness)
        assert entry.is_set()
    assert resume.observe(db, oid) == near_deadline


def test_after_update_interference_does_not_commit_a_claim(retired):
    db, _, oid, *_ = retired
    with db.begin() as conn:
        conn.exec_driver_sql(
            "CREATE TRIGGER drift_after_claim AFTER UPDATE OF route_resume_claim_id ON instantorder "
            "BEGIN UPDATE instantorder SET ride_base_price = ride_base_price + 1 WHERE id = NEW.id; END",
        )
    before = resume.observe(db, oid)
    assert claim.claim_sealed_retirement(db, **_params(retired)) is None
    assert resume.observe(db, oid) == before


def test_lost_commit_ack_never_returns_usable_claim_or_retries(retired, monkeypatch):
    db, _, oid, *_ = retired
    real = db.dialect.do_commit

    def lost_ack(raw):
        real(raw)
        raise sqlite3.OperationalError("synthetic lost acknowledgement")

    with monkeypatch.context() as m:
        m.setattr(db.dialect, "do_commit", lost_ack)
        assert claim.claim_sealed_retirement(db, **_params(retired)) is None
    witnessed = resume.observe(db, oid)
    assert resume._token(resume._state(witnessed)["route_resume_claim_id"])
    assert claim.claim_sealed_retirement(db, **_params(retired)) is None
    assert claim.claim_sealed_retirement(db, **_params(retired, expected=witnessed)) is None
    assert resume.observe(db, oid) == witnessed
