"""Actual file SQLite legacy generic start ownership; no capped worker."""
from datetime import timedelta

import pytest
from sqlalchemy import event
from sqlmodel import Session

from app import instant_service as isv, taxi_route_resume as resume
from app.models import InstantOrder, InstantOrderStatus as S
from app.timeutil import utcnow
from test_taxi_offered_waypoint_preview import _mutate, _row, testdb  # noqa: F401


@pytest.fixture
def searching(request):
    db, owner, oid, old, new, tariff, at = request.getfixturevalue("testdb")
    _mutate(db, oid, status=S.searching, searching_at=at - timedelta(minutes=1), search_round=3,
            current_offer_driver_id=None, offer_expires_at=None)
    return db, owner, oid, old, new, tariff, at


def _start(data):
    db, _, oid, *_ = data
    with Session(db) as s:
        order = s.get(InstantOrder, oid)
        result = isv.start_matching(s, order, notify=False)
        return result.status


def _spy(monkeypatch, *, candidates=None):
    effects = []

    def redis():
        effects.append("redis")
        return object()

    monkeypatch.setattr(isv, "_redis", redis)
    monkeypatch.setattr(isv, "_tried_set", lambda *a: set())
    monkeypatch.setattr(isv, "candidates", candidates or (lambda *a, **k: effects.append("geo") or []))
    monkeypatch.setattr(isv, "_remember_offered_driver", lambda *a: effects.append("remember"))
    monkeypatch.setattr(isv, "_cleanup_tried", lambda *a: effects.append("cleanup"))
    monkeypatch.setattr(isv, "_push_offer", lambda *a: effects.append("offer"))
    return effects


@pytest.mark.parametrize("mark", (*resume._MARKERS, "complete"))
def test_already_marked_never_runs_legacy_write_or_lookup(searching, monkeypatch, mark):
    db, _, oid, old, *_ = searching
    at = _row(db, oid).searching_at
    fields = {"route_resume_id": "a"*32, "route_resume_claim_id": "b"*32,
              "route_resume_previous_driver_id": old, resume._SEAL: b"x"*32,
              "route_resume_retired_at": at, "route_resume_ready_at": at, "route_resume_deadline_at": at}
    _mutate(db, oid, **(fields if mark == "complete" else {mark: fields[mark]}))
    before = _row(db, oid)
    effects, writes = _spy(monkeypatch), []

    def witness(conn, cursor, statement, parameters, context, executemany):
        if statement.startswith("UPDATE instantorder SET"):
            writes.append(True)

    event.listen(db, "before_cursor_execute", witness)
    try:
        assert _start(searching) is S.searching
    finally:
        event.remove(db, "before_cursor_execute", witness)

    assert effects == writes == [] and _row(db, oid) == before


@pytest.mark.parametrize("rival", ("mark", "other_phase", "route", "same_phase_new_episode"))
def test_independent_rival_at_actual_start_update_owns_state_and_no_lookup(searching, monkeypatch, rival):
    db, _, oid, *_ = searching
    before = _row(db, oid)
    effects, observed = _spy(monkeypatch), []
    change = {
        "mark": {resume._SEAL: b"x"*32},
        "other_phase": {"status": S.expired, "expired_at": utcnow()},
        "route": {"to_text": "SYNTHETIC RIVAL DESTINATION"},
        "same_phase_new_episode": {"searching_at": utcnow(), "search_round": 2},
    }[rival]

    def inject(conn, cursor, statement, parameters, context, executemany):
        if statement.startswith("UPDATE instantorder SET") and not observed:
            observed.append(True)
            with db.begin() as writer:
                assert writer.connection.driver_connection is not conn.connection.driver_connection
                writer.execute(resume._ORDER.update().where(resume._COL.id == oid).values(**change))

    event.listen(db, "before_cursor_execute", inject)
    try:
        _start(searching)
    finally:
        event.remove(db, "before_cursor_execute", inject)
    final = dict(_row(db, oid)._mapping)
    assert observed == [True] and effects == []
    assert final == dict(before._mapping) | change


def test_mark_after_own_start_commit_but_before_continuation_blocks_lookup(searching, monkeypatch):
    db, _, oid, *_ = searching
    effects, hit = _spy(monkeypatch), []
    original_commit = db.dialect.do_commit
    original_engine_id = []

    def record(conn, cursor, statement, parameters, context, executemany):
        if statement.startswith("UPDATE instantorder SET"):
            original_engine_id.append(conn.connection.driver_connection)

    def winner(raw):
        original_commit(raw)
        if original_engine_id and getattr(raw, "driver_connection", raw) is original_engine_id[0] and not hit:
            hit.append(True)
            just_committed = _row(db, oid)
            assert just_committed.status is S.searching and just_committed.search_round == 0
            _mutate(db, oid, **{resume._SEAL: b"x"*32})

    event.listen(db, "before_cursor_execute", record)
    try:
        monkeypatch.setattr(db.dialect, "do_commit", winner)
        _start(searching)
    finally:
        event.remove(db, "before_cursor_execute", record)
    assert hit == [True] and effects == []
    assert _row(db, oid).route_resume_agreement_seal == b"x"*32


@pytest.mark.parametrize("has_driver", (True, False))
def test_in_flight_geo_after_successful_start_cannot_commit_old_matcher_effects(searching, monkeypatch, has_driver):
    db, _, oid, _, next_driver, *_ = searching
    looked = []

    def intercepted(redis, session, order, exclude):
        current = _row(db, oid)
        assert current.status is S.searching and current.search_round == 0
        looked.append(current)
        _mutate(db, oid, **{resume._SEAL: b"x"*32})
        return [next_driver] if has_driver else []

    effects = _spy(monkeypatch, candidates=intercepted)
    assert _start(searching) is S.searching
    assert len(looked) == 1 and effects == ["redis"]
    assert dict(_row(db, oid)._mapping) == dict(looked[0]._mapping) | {resume._SEAL: b"x"*32}


@pytest.mark.parametrize("driver_available", (True, False))
def test_ordinary_acknowledged_start_keeps_prior_route_budget_reset_and_matcher(searching, monkeypatch, driver_available):
    db, _, oid, _, next_driver, *_ = searching
    before = _row(db, oid)
    seen = []

    def candidates(*a, **kw):
        current = _row(db, oid)
        assert current.status is S.searching and current.search_round == 0 and current.searching_at > before.searching_at
        seen.append(current)
        return [next_driver] if driver_available else []

    effects = _spy(monkeypatch, candidates=candidates)
    status = _start(searching)
    expected = S.offered if driver_available else S.expired
    assert status is expected and len(seen) == 1
    final = _row(db, oid)
    assert final.status is expected and final.current_offer_driver_id == (next_driver if driver_available else None)
    assert final.search_round == int(driver_available)
    assert (final.to_text, final.waypoints_json, final.price_estimate, final.promo_discount_kop) == (
        before.to_text, before.waypoints_json, before.price_estimate, before.promo_discount_kop,
    )
    assert effects == (["redis", "remember", "offer"] if driver_available else ["redis", "cleanup"])


@pytest.mark.parametrize("clock_offset_us", (0, -1, 1))
def test_start_only_at_forward_wall_clock_preserves_coherent_current_card(searching, monkeypatch, clock_offset_us):
    db, _, oid, _, next_driver, *_ = searching
    first = _row(db, oid)
    now = first.searching_at + timedelta(microseconds=clock_offset_us)
    monkeypatch.setattr(isv, "utcnow", lambda: now)
    choices, writes = [], []

    def available(*args, **kwargs):
        choices.append(next_driver)
        return [next_driver]

    def witness(conn, cursor, statement, parameters, context, executemany):
        if statement.startswith("UPDATE instantorder SET"):
            writes.append(True)

    effects = _spy(monkeypatch, candidates=available)
    event.listen(db, "before_cursor_execute", witness)
    try:
        status = _start(searching)
    finally:
        event.remove(db, "before_cursor_execute", witness)

    if clock_offset_us <= 0:
        assert status is S.searching and _row(db, oid) == first
        assert choices == writes == effects == []
        return

    assert status is S.offered and choices == [next_driver] and len(writes) == 2
    assert effects == ["redis", "remember", "offer"]
    with Session(db) as session:
        order = session.get(InstantOrder, oid)
        assert order.searching_at == order.offered_at == now
        assert isv.offer_card_precondition(isv.capture_offer_for_accept(order), order.price_final, next_driver, now)


def test_ordinary_wait_renewal_during_lookup_remains_fallback(searching, monkeypatch):
    db, _, oid, *_ = searching
    renewed = utcnow() + timedelta(minutes=5)

    def no_candidates(*args, **kwargs):
        _mutate(db, oid, wait_until=renewed)
        return []

    effects = _spy(monkeypatch, candidates=no_candidates)
    assert _start(searching) is S.expired
    final = _row(db, oid)
    assert final.wait_until == renewed
    assert effects == ["redis", "cleanup"]


def test_caller_local_dirty_order_is_not_flushed_as_side_effect_of_start(searching, monkeypatch):
    db, _, oid, *_ = searching
    before = _row(db, oid)
    effects = _spy(monkeypatch)
    with Session(db) as session:
        order = session.get(InstantOrder, oid)
        order.to_text = "SYNTHETIC UNCOMMITTED CHANGE"
        assert isv.start_matching(session, order, notify=False) is order
        assert order.to_text != before.to_text
        assert _row(db, oid) == before
        session.rollback()
    assert effects == [] and _row(db, oid) == before
