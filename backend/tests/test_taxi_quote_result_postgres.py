"""Actual PostgreSQL MVCC barrier; a missing row must NOT promise no future commit."""
import threading
from concurrent.futures import ThreadPoolExecutor

import pytest
from sqlalchemy import event
from sqlmodel import Session, select

from app import instant_service as isv, taxi_quote_consent as consent
from app.db import engine
from app.models import InstantOrder
from test_taxi_quote_consent import advance_quote_ttl, consent_market as consent_market
from test_taxi_quote_consent import (  # noqa: F401
    quote, confirm, live_driver, promo_user, set_base,
)
from test_taxi_quote_result import UNKNOWN, lookup, saved_state

pytestmark = pytest.mark.skipif(engine.dialect.name != "postgresql", reason="real PostgreSQL MVCC sessions required")


def test_lookup_during_uncommitted_confirmation_then_commit_expiry_terminal_replay(
    client, user_factory, consent_market, monkeypatch,
):
    driver = live_driver(client, user_factory)
    set_base(600)
    user = promo_user(client, user_factory)
    q = quote(client, user)
    assert q["payable_kop"] == 45000
    reached, release = threading.Event(), threading.Event()
    paused, creator_pids, reader_pids, claims, dispatches = [], set(), set(), [], []
    original_claim, original_dispatch = consent.claim_promo, isv.dispatch_confirmed_order
    def claim(*args, **kwargs):
        claims.append(True)
        return original_claim(*args, **kwargs)
    def dispatch(session, order_id, *args, **kwargs):
        dispatches.append(order_id)
        return original_dispatch(session, order_id, *args, **kwargs)
    monkeypatch.setattr(consent, "claim_promo", claim)
    monkeypatch.setattr(isv, "dispatch_confirmed_order", dispatch)
    def before_commit(session):
        if not paused and any(isinstance(o, InstantOrder) and o.consent_request_key == q["request_key"] for o in session.identity_map.values()):
            paused.append(True)
            creator_pids.add(session.connection().connection.driver_connection.get_backend_pid())
            reached.set()
            assert release.wait(15), "test barrier never released"
    def before_sql(connection, cursor, statement, parameters, context, many):
        if reached.is_set() and not release.is_set() and "instantorder.consent_snapshot_json" in statement:
            reader_pids.add(connection.connection.driver_connection.get_backend_pid())
    event.listen(Session, "before_commit", before_commit)
    event.listen(engine, "before_cursor_execute", before_sql)
    try:
        with ThreadPoolExecutor(max_workers=2) as pool:
            sent = pool.submit(confirm, client, user, q)
            try:
                assert reached.wait(10), "real confirm did not reach precommit barrier"
                observed = pool.submit(lookup, client, user, q["request_key"]).result(5)
                assert observed.status_code == 200 and observed.json() == UNKNOWN
                assert creator_pids and reader_pids and creator_pids.isdisjoint(reader_pids)
                with Session(engine) as observer:
                    assert observer.exec(select(InstantOrder).where(
                        InstantOrder.passenger_id == user["id"])).first() is None
            finally:
                release.set()
            result = sent.result(20)
    finally:
        release.set()
        event.remove(Session, "before_commit", before_commit)
        event.remove(engine, "before_cursor_execute", before_sql)
    assert result.status_code == 200, result.text
    oid = result.json()["order"]["id"]
    assert lookup(client, user, q["request_key"]).json()["order_id"] == oid
    for action in ("accept", "arrived", "onboard", "done"):
        r = client.post(f"/instant/orders/{oid}/{action}", headers=driver["auth"])
        assert r.status_code == 200, r.text
    advance_quote_ttl(consent_market.redis, 121)
    consent_market.unavailable = True
    before = saved_state(user["id"])
    for _ in range(2):
        r = lookup(client, user, q["request_key"])
        assert r.status_code == 200 and r.json()["initial_payable_kop"] == 45000
    replay = confirm(client, user, q)
    assert replay.status_code == 200 and replay.json()["order"]["id"] == oid
    assert saved_state(user["id"]) == before
    assert claims == [True] and dispatches == [oid]
