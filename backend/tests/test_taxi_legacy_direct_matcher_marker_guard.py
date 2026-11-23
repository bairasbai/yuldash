"""Actual private file SQLite tests of unmarked legacy matcher ownership."""
from datetime import timedelta

import pytest
from sqlalchemy import event
from sqlmodel import Session

from app import instant_service as isv, safety_logic, services, taxi, taxi_route_resume as resume
from app.models import InstantOrder, InstantOrderStatus as S
from app.timeutil import utcnow
from test_taxi_offered_waypoint_preview import _mutate, _row, testdb  # noqa: F401


@pytest.fixture
def searching(request):
    db, owner, oid, previous, candidate, tariff, at = request.getfixturevalue("testdb")
    _mutate(db, oid, status=S.searching, search_round=0, current_offer_driver_id=None,
            offer_expires_at=None, offered_at=None)
    return db, owner, oid, previous, candidate, tariff, at


def _run_match(searching):
    db, _, oid, *_ = searching
    with Session(db) as session:
        return isv.try_offer_next(session, session.get(InstantOrder, oid)).status


def _watch(monkeypatch, candidate, *, paused=False):
    effects = []

    def mark(label, answer=None):
        def tracked(*args, **kwargs):
            effects.append(label)
            return answer
        return tracked

    monkeypatch.setattr(safety_logic, "account_paused", mark("standing", paused))
    monkeypatch.setattr(isv, "_redis", mark("redis-read", object()))
    monkeypatch.setattr(isv, "_tried_set", mark("tried-read", set()))
    monkeypatch.setattr(isv, "candidates", mark("lookup", [candidate] if candidate else []))
    monkeypatch.setattr(isv, "_remember_offered_driver", mark("tried-write"))
    monkeypatch.setattr(isv, "_push_offer", mark("offer-notice"))
    monkeypatch.setattr(isv, "_cleanup_tried", mark("cleanup"))
    monkeypatch.setattr(isv, "push_bilingual", mark("expiry-notice"))
    monkeypatch.setattr(isv, "_notify_cancel", mark("cancel-notice"))
    monkeypatch.setattr(isv.promo_ride, "release", mark("promo-release"))
    monkeypatch.setattr(services, "push_notification", mark("pause-notice"))
    return effects


@pytest.mark.parametrize("marker", (*resume._MARKERS, "all"))
def test_visible_marker_never_reaches_any_legacy_matcher_step(searching, monkeypatch, marker):
    db, _, oid, prev, candidate, _, at = searching
    values = dict(route_resume_id="a"*32, route_resume_claim_id="", route_resume_previous_driver_id=prev,
                  route_resume_retired_at=at, route_resume_ready_at=at, route_resume_deadline_at=at,
                  route_resume_agreement_seal=b"x"*32)
    _mutate(db, oid, **(values if marker == "all" else {marker: values[marker]}))
    before = _row(db, oid)
    effects = _watch(monkeypatch, candidate)
    assert _run_match(searching) is S.searching
    assert effects == [] and _row(db, oid) == before


@pytest.mark.parametrize("outcome", (S.offered, S.expired, S.cancelled))
@pytest.mark.parametrize("lose", (False, True))
def test_direct_match_existing_winner_or_rival_at_real_final_dml(searching, monkeypatch, outcome, lose):
    db, _, oid, _, candidate, *_ = searching
    effects = _watch(monkeypatch, candidate if outcome is S.offered else None, paused=outcome is S.cancelled)
    expected, rowcounts = [], []
    changed = {resume._SEAL: b"x"*32}
    before = _row(db, oid)

    def own(statement, parameters):
        return statement.startswith("UPDATE instantorder SET") and outcome.value in parameters

    def before_sql(conn, cursor, statement, parameters, context, many):
        if lose and own(statement, parameters) and not expected:
            with db.begin() as rival:
                assert rival.connection.driver_connection is not conn.connection.driver_connection
                rival.execute(resume._ORDER.update().where(resume._COL.id == oid).values(**changed))
            expected.append(_row(db, oid))

    def after_sql(conn, cursor, statement, parameters, context, many):
        if own(statement, parameters):
            rowcounts.append(cursor.rowcount)

    event.listen(db, "before_cursor_execute", before_sql)
    event.listen(db, "after_cursor_execute", after_sql)
    try:
        status = _run_match(searching)
    finally:
        event.remove(db, "before_cursor_execute", before_sql)
        event.remove(db, "after_cursor_execute", after_sql)
    assert rowcounts == [0 if lose else 1]
    final = _row(db, oid)
    lookup = ["standing"] if outcome is S.cancelled else ["standing", "redis-read", "tried-read", "lookup"]
    if lose:
        assert status is S.searching and final == expected[0]
        assert dict(final._mapping) == dict(before._mapping) | changed
        assert effects == lookup
    else:
        assert status is final.status is outcome
        assert effects == lookup + {
            S.offered: ["tried-write", "offer-notice"],
            S.expired: ["cleanup", "expiry-notice"],
            S.cancelled: ["cleanup", "promo-release", "cancel-notice", "pause-notice"],
        }[outcome]
        assert final.current_offer_driver_id == (candidate if outcome is S.offered else None)


def _seed_confirmed(searching, lag=timedelta()):
    db, _, oid, *_ = searching
    _mutate(db, oid, status=S.created, searching_at=None, created_at=utcnow()-lag,
            consent_request_key="synthetic-first-dispatch")


@pytest.mark.parametrize("late", (False, True))
def test_confirmed_marked_row_not_claimed_even_if_mark_arrives_at_actual_claim(searching, monkeypatch, late):
    db, _, oid, _, candidate, *_ = searching
    _seed_confirmed(searching, timedelta(days=1))
    changes = {resume._SEAL: b"x"*32}
    if not late:
        _mutate(db, oid, **changes)
    before = _row(db, oid)
    effects = _watch(monkeypatch, candidate)
    hit = []

    def before_claim(conn, cursor, statement, parameters, context, many):
        if late and statement.startswith("UPDATE instantorder SET") and "coalesce(" in statement and not hit:
            with db.begin() as rival:
                assert rival.connection.driver_connection is not conn.connection.driver_connection
                rival.execute(resume._ORDER.update().where(resume._COL.id == oid).values(**changes))
            hit.append(True)

    event.listen(db, "before_cursor_execute", before_claim)
    try:
        with Session(db) as session:
            assert isv.dispatch_confirmed_order(session, oid) is False
            assert not session.in_transaction()
    finally:
        event.remove(db, "before_cursor_execute", before_claim)
    assert hit == ([True] if late else []) and effects == []
    assert dict(_row(db, oid)._mapping) == dict(before._mapping) | changes


@pytest.mark.parametrize("mode", ("live", "expired", "deadline_during_lookup"))
def test_first_confirmed_deadline_and_existing_effects(searching, monkeypatch, mode):
    db, _, oid, _, candidate, *_ = searching
    _seed_confirmed(searching, timedelta(days=1) if mode == "expired" else timedelta())
    before = _row(db, oid)
    budget = timedelta(seconds=isv.settings.instant_max_offers * isv.settings.instant_offer_ttl_sec)
    deadline = before.created_at + budget
    effects = _watch(monkeypatch, candidate)
    monkeypatch.setattr(taxi, "availability", lambda *a, **k: {"enabled": True})
    if mode == "deadline_during_lookup":
        original = isv.candidates

        def delayed(*a, **k):
            monkeypatch.setattr(isv, "utcnow", lambda: deadline)
            return original(*a, **k)

        monkeypatch.setattr(isv, "candidates", delayed)

    with Session(db) as session:
        assert isv.dispatch_confirmed_order(session, oid) is True
        assert isv.dispatch_confirmed_order(session, oid) is False
    final = _row(db, oid)
    assert final.status is (S.offered if mode == "live" else S.expired)
    assert final.consent_request_key == before.consent_request_key
    assert final.price_estimate == before.price_estimate
    assert effects == {
        "live": ["standing", "redis-read", "lookup", "tried-write", "offer-notice"],
        "expired": ["cleanup", "expiry-notice"],
        "deadline_during_lookup": ["standing", "redis-read", "lookup", "cleanup", "expiry-notice"],
    }[mode]
