"""Actual SQLite sealed successor storage; no driver selection or delivery."""
from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace
import sqlite3
from threading import Event

import pytest
from sqlalchemy import event

from app import taxi_marked_offer_card as cards, taxi_route_resume as resume
from app import taxi_sealed_resume_claim as claims, taxi_sealed_resume_offer as offers
from app.config import settings
from app.models import InstantOrderStatus as S
from test_taxi_offered_waypoint_preview import _mutate
from test_taxi_offered_waypoint_retire import _clock, _wait_db, healthy, route, testdb  # noqa: F401
from test_taxi_sealed_resume_claim import _params, near_deadline, retired  # noqa: F401


def _store(data, won, **changes):
    db, owner, oid, other, kw, initial = data
    options = dict(key=kw["key"], owner_id=owner, order_id=oid, claim=won, driver_id=other,
                   marker_id=resume._state(initial)["route_resume_id"], offer_seconds=25)
    return offers.store_sealed_successor_offer(db, **(options | changes))


def _claim(data, **changes):
    won = claims.claim_sealed_retirement(data[0], **_params(data, **changes))
    assert type(won) is claims.SealedResumeClaim
    return won


def test_real_retire_claim_offer_preserves_seal_price_terms_and_caps_lease(request):
    data = request.getfixturevalue("retired")
    db, owner, oid, other, kw, initial = data
    won = _claim(data)
    result = _store(data, won, offer_seconds=120)
    assert result is not None and resume.observe(db, oid) == result
    recorded = resume._state(result)
    before = resume._state(initial)
    assert recorded == before | {
        "status": S.offered, "current_offer_driver_id": other,
        "offered_at": recorded["offered_at"], "offer_expires_at": before["route_resume_deadline_at"],
        "search_round": before["search_round"] + 1,
        "route_resume_ready_at": None, "route_resume_claim_id": None,
    }
    assert recorded["driver_id"] is None
    with db.connect() as conn:
        assert cards.bind_current_card(conn, kw["key"], owner_id=owner, order_id=oid, driver_id=other) is None
    assert _store(data, won) is None and resume.observe(db, oid) == result


def test_wrong_recipient_owner_marker_raw_or_forged_claim_never_writes(request, monkeypatch):
    data = request.getfixturevalue("retired")
    db, owner, oid, other, _, initial = data
    won = _claim(data)
    state = resume._state(initial)
    for variations in (
        {"driver_id": state["route_resume_previous_driver_id"]}, {"driver_id": owner}, {"driver_id": 999999},
        {"driver_id": True}, {"driver_id": 1 << 31}, {"owner_id": other}, {"order_id": oid+1}, {"marker_id": "e"*32},
        {"key": b"x"*32}, {"claim": won._observation}, {"claim": replace(won, _ack=b"x"*32)},
        {"claim": replace(won, _observation=initial)},
    ):
        assert _store(data, won, **variations) is None
    with monkeypatch.context() as m:
        m.setattr(settings, "instant_max_offers", state["search_round"])
        assert _store(data, won) is None
    assert resume.observe(db, oid) == won._observation


@pytest.mark.parametrize("kind", ("route", "fare", "marker", "nonce", "offer", "pending"))
def test_claim_cannot_store_against_drift_or_other_work(request, kind):
    data = request.getfixturevalue("retired")
    db, _, oid, _, _, _ = data
    won = _claim(data)
    fresh = resume._state(won._observation)
    changed = {
        "route": {"to_text": "SYNTHETIC DIFFERENT DESTINATION"},
        "fare": {"ride_price": fresh["ride_price"] + 10, "price_estimate": fresh["price_estimate"] + 10},
        "marker": {"route_resume_id": "f"*32},
        "nonce": {"route_resume_claim_id": "d"*32},
        "offer": {"status": S.offered, "offer_expires_at": fresh["route_resume_ready_at"]},
        "pending": {"pending_price": 100},
    }[kind]
    _mutate(db, oid, **changed)
    before = resume.observe(db, oid)
    assert _store(data, won) is None
    assert resume.observe(db, oid) == before


def test_lost_claim_ack_cannot_be_rehydrated_but_due_takeover_is_fresh(request, monkeypatch):
    data = request.getfixturevalue("retired")
    db, _, oid, _, _, _ = data
    original_commit = db.dialect.do_commit

    def lost(raw):
        original_commit(raw)
        raise sqlite3.OperationalError("synthetic lost claim acknowledgement")

    with monkeypatch.context() as m:
        m.setattr(db.dialect, "do_commit", lost)
        assert claims.claim_sealed_retirement(db, **_params(data, lease_seconds=1)) is None
    observed = resume.observe(db, oid)
    assert resume._token(resume._state(observed)["route_resume_claim_id"])
    assert _store(data, observed) is None
    assert _store(data, claims.SealedResumeClaim(observed, b"x"*32)) is None
    _wait_db(db, resume._state(observed)["route_resume_ready_at"])
    takeover = _claim(data, expected=observed)
    assert takeover._observation != observed
    assert _store(data, takeover) is not None


def test_old_genuine_ack_never_uses_renewed_claim(request):
    data = request.getfixturevalue("retired")
    old = _claim(data, lease_seconds=1)
    _wait_db(data[0], resume._state(old._observation)["route_resume_ready_at"])
    renewed = _claim(data, expected=old._observation)
    assert _store(data, old) is None
    assert _store(data, replace(old, _observation=renewed._observation)) is None
    assert _store(data, renewed) is not None


def test_two_real_writers_serialize_one_successor_and_one_round(request):
    data = request.getfixturevalue("retired")
    db, _, oid, _, _, initial = data
    won = _claim(data)
    first_in_dml, second_at_lock, release = Event(), Event(), Event()
    conns = []

    def witness(conn, cursor, statement, parameters, context, executemany):
        if statement == "BEGIN IMMEDIATE":
            conns.append(conn.connection.driver_connection)
            if len(conns) == 2:
                second_at_lock.set()
        if statement.startswith("UPDATE instantorder SET"):
            first_in_dml.set()
            assert release.wait(4)

    event.listen(db, "before_cursor_execute", witness)
    try:
        with ThreadPoolExecutor(max_workers=2) as workers:
            first = workers.submit(_store, data, won)
            try:
                assert first_in_dml.wait(3)
                second = workers.submit(_store, data, won)
                assert second_at_lock.wait(3)
                assert conns[0] is not conns[1] and not second.done()
            finally:
                release.set()
            stored = first.result(timeout=5)
            assert stored is not None and second.result(timeout=5) is None
    finally:
        event.remove(db, "before_cursor_execute", witness)
    assert resume.observe(db, oid) == stored
    assert resume._state(stored)["search_round"] == resume._state(initial)["search_round"] + 1


@pytest.mark.parametrize("boundary", ("absolute_after_lock", "lease_after_verified_read"))
def test_actual_clock_at_lock_and_final_store_does_not_allow_expired_offer(request, boundary):
    data = request.getfixturevalue("retired")
    db, _, oid, _, _, _ = data
    if boundary == "absolute_after_lock":
        near = request.getfixturevalue("near_deadline")
        data = (*data[:-1], near)
        won = _claim(data, lease_seconds=120)
    else:
        won = _claim(data, lease_seconds=1)
    snapshot = resume._state(won._observation)
    expires = snapshot["route_resume_ready_at"]
    if boundary == "absolute_after_lock":
        assert expires == snapshot["route_resume_deadline_at"]
    else:
        assert expires < snapshot["route_resume_deadline_at"]
    entry = Event()
    entered_connections = []

    def witness(conn, cursor, statement, parameters, context, executemany):
        if boundary == "absolute_after_lock" and statement == "BEGIN IMMEDIATE":
            entered_connections.append(conn.connection.driver_connection)
            entry.set()
        elif boundary == "lease_after_verified_read" and statement.startswith("UPDATE instantorder SET"):
            entry.set()
            _wait_db(db, expires)

    if boundary == "absolute_after_lock":
        with ThreadPoolExecutor(max_workers=1) as pool:
            with db.begin() as rival:
                rival.exec_driver_sql("BEGIN IMMEDIATE")
                assert _clock(rival) < expires
                event.listen(db, "before_cursor_execute", witness)
                try:
                    pending = pool.submit(_store, data, won)
                    assert entry.wait(3) and entered_connections[0] is not rival.connection.driver_connection
                    assert not pending.done()
                    _wait_db(db, expires)
                finally:
                    event.remove(db, "before_cursor_execute", witness)
            assert pending.result(timeout=5) is None
    else:
        event.listen(db, "before_cursor_execute", witness)
        try:
            assert _store(data, won) is None
        finally:
            event.remove(db, "before_cursor_execute", witness)
        assert entry.is_set()
    assert resume.observe(db, oid) == won._observation


@pytest.mark.parametrize("failure", ("after_trigger", "commit_refusal", "commit_lost"))
def test_rollbacks_and_lost_commit_are_never_reported_as_a_new_ack(request, monkeypatch, failure):
    data = request.getfixturevalue("retired")
    db, _, oid, _, _, initial = data
    won = _claim(data)
    if failure == "after_trigger":
        with db.begin() as conn:
            conn.exec_driver_sql(
                "CREATE TRIGGER drift_after_offer AFTER UPDATE OF current_offer_driver_id ON instantorder "
                "BEGIN UPDATE instantorder SET ride_base_price=ride_base_price+1 WHERE id=NEW.id; END",
            )
        assert _store(data, won) is None
    else:
        original_commit = db.dialect.do_commit

        def fail(raw):
            if failure == "commit_lost":
                original_commit(raw)
            raise sqlite3.OperationalError("synthetic commit interruption")

        with monkeypatch.context() as m:
            m.setattr(db.dialect, "do_commit", fail)
            assert _store(data, won) is None
    current = resume.observe(db, oid)
    if failure == "commit_lost":
        state = resume._state(current)
        assert state["status"] is S.offered and state["search_round"] == resume._state(initial)["search_round"] + 1
        assert _store(data, won) is None and resume.observe(db, oid) == current
    else:
        assert current == won._observation
