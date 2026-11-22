"""Real independent PostgreSQL transactions: close/confirm/GC lock ordering."""
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta

import pytest
from sqlalchemy import event, text
from sqlmodel import Session, select

from app import instant_service as isv, taxi_quote_consent as consent
from app.db import engine
from app.models import InstantOrder, TaxiConsentAttempt
from app.timeutil import utcnow
from test_taxi_quote_consent import quote, confirm, promo_user, set_base
from test_taxi_quote_consent import consent_market as consent_market
from test_taxi_quote_close import old_waits as old_waits
from test_taxi_quote_close import close, attempt, collect_expired
from test_taxi_quote_result import saved_state, lookup, UNKNOWN

pytestmark = pytest.mark.skipif(engine.dialect.name != "postgresql", reason="independent PostgreSQL sessions required")


def blocked(pid):
    for _ in range(100):
        with engine.connect() as conn:
            wait = conn.execute(text("SELECT wait_event_type FROM pg_stat_activity WHERE pid=:pid"), {"pid": pid}).scalar()
        if wait == "Lock":
            return
        time.sleep(.02)
    raise AssertionError("the competing PostgreSQL transaction did not wait on a real lock")


@pytest.mark.parametrize("winner", ["close", "confirm"])
def test_two_lock_orders_have_one_outcome_and_no_losing_side_effects(
    client, user_factory, consent_market, monkeypatch, winner, old_waits,
):
    set_base(600)
    user = promo_user(client, user_factory)
    with Session(engine) as session:
        old = InstantOrder(passenger_id=user["id"], status="expired", wait_until=utcnow()+timedelta(minutes=30),
                           from_lat=52.59, from_lng=58.31, to_lat=52.71, to_lng=58.66, price_estimate=600)
        session.add(old)
        session.commit()
        old_id = old.id
        old_waits.append(old_id)
    q = quote(client, user)
    assert q["payable_kop"] == 45000
    before = saved_state(user["id"])
    reached, release, waiting = threading.Event(), threading.Event(), threading.Event()
    winner_pids, waiter_pids, dispatches, claims = set(), set(), [], []
    original_dispatch, original_claim = isv.start_matching, consent.claim_promo
    def dispatch(session, order):
        dispatches.append(order.id)
        return original_dispatch(session, order)
    def claim(*args):
        claims.append(True)
        return original_claim(*args)
    monkeypatch.setattr(isv, "start_matching", dispatch)
    monkeypatch.setattr(consent, "claim_promo", claim)
    def hold(session):
        if not reached.is_set() and any(isinstance(o, TaxiConsentAttempt) and o.owner_id == user["id"]
                and o.request_key == q["request_key"] and o.state == ("closed" if winner == "close" else "committed")
                for o in session.identity_map.values()):
            winner_pids.add(session.connection().connection.driver_connection.get_backend_pid())
            reached.set()
            assert release.wait(20), "winner precommit barrier not released"
    def observe(connection, cursor, statement, parameters, context, many):
        if reached.is_set() and not release.is_set() and "FOR UPDATE" in statement and '"user"' in statement:
            pid = connection.connection.driver_connection.get_backend_pid()
            if pid not in winner_pids:
                waiter_pids.add(pid)
                waiting.set()
    event.listen(Session, "before_commit", hold)
    event.listen(engine, "before_cursor_execute", observe)
    try:
        with ThreadPoolExecutor(max_workers=2) as pool:
            first = pool.submit(close, client, user, q["request_key"]) if winner == "close" else pool.submit(confirm, client, user, q)
            try:
                assert reached.wait(10)
                second = pool.submit(confirm, client, user, q) if winner == "close" else pool.submit(close, client, user, q["request_key"])
                assert waiting.wait(10)
                blocked(next(iter(waiter_pids)))
                assert not second.done() and winner_pids.isdisjoint(waiter_pids)
                assert lookup(client, user, q["request_key"]).json() == UNKNOWN
                # Another actual SQL session observes no new order or consumed discount yet.
                assert saved_state(user["id"]) == before
            finally:
                release.set()
            a, b = first.result(20), second.result(20)
    finally:
        release.set()
        event.remove(Session, "before_commit", hold)
        event.remove(engine, "before_cursor_execute", observe)
    assert a.status_code == 200
    if winner == "close":
        assert a.json()["history"] == "not_created" and b.status_code == 409
        assert saved_state(user["id"]) == before and not claims and not dispatches
        assert attempt(user, q["request_key"])["state"] == "closed"
    else:
        oid = a.json()["order"]["id"]
        assert b.status_code == 200 and b.json()["order_id"] == oid
        assert claims == [True] and dispatches == [oid]
        with Session(engine) as observer:
            rows = observer.exec(select(InstantOrder).where(InstantOrder.passenger_id == user["id"])).all()
            assert len(rows) == 2
            created = observer.get(InstantOrder, oid)
            assert created.price_estimate == 600 and created.promo_discount_kop == 15000
            previous = observer.get(InstantOrder, old_id)
            assert previous.status == "cancelled" and previous.wait_until is None
        assert confirm(client, user, q).json()["order"]["id"] == oid
        assert dispatches == [oid] and claims == [True]


@pytest.mark.parametrize("action", ["close", "confirm"])
def test_gc_waits_for_fenced_transaction_and_never_reopens_admission(
    client, user_factory, consent_market, action,
):
    user = user_factory("SyntheticGCRace")
    q = quote(client, user)
    expiry = attempt(user, q["request_key"])["expires_at"]
    reached, release, deleting = threading.Event(), threading.Event(), threading.Event()
    holders, collectors = set(), set()
    def hold(session):
        if not reached.is_set() and any(isinstance(o, TaxiConsentAttempt) and o.owner_id == user["id"]
                and o.state == ("closed" if action == "close" else "committed") for o in session.identity_map.values()):
            holders.add(session.connection().connection.driver_connection.get_backend_pid())
            reached.set()
            assert release.wait(20)
    def observe(connection, cursor, statement, parameters, context, many):
        if reached.is_set() and not release.is_set() and statement.startswith("DELETE FROM taxiconsentattempt"):
            collectors.add(connection.connection.driver_connection.get_backend_pid())
            deleting.set()
    event.listen(Session, "before_commit", hold)
    event.listen(engine, "before_cursor_execute", observe)
    try:
        with ThreadPoolExecutor(max_workers=2) as pool:
            first = pool.submit(close, client, user, q["request_key"]) if action == "close" else pool.submit(confirm, client, user, q)
            try:
                assert reached.wait(10)
                gc = pool.submit(collect_expired, expiry+timedelta(seconds=1))
                assert deleting.wait(10)
                blocked(next(iter(collectors)))
                assert not gc.done() and holders.isdisjoint(collectors)
            finally:
                release.set()
            created, deleted = first.result(20), gc.result(20)
    finally:
        release.set()
        event.remove(Session, "before_commit", hold)
        event.remove(engine, "before_cursor_execute", observe)
    assert created.status_code == 200 and deleted >= 1
    assert attempt(user, q["request_key"]) is None
    if action == "close":
        assert confirm(client, user, q).status_code == 409
        assert not saved_state(user["id"])["orders"]
    else:
        oid = created.json()["order"]["id"]
        assert close(client, user, q["request_key"]).json()["order_id"] == oid
        assert confirm(client, user, q).json()["order"]["id"] == oid
