"""Explicit close: actual auth/SQL/Redis, synthetic immediate taxi only."""
import json
from datetime import timedelta
from uuid import uuid4

import pytest
from sqlalchemy import event, text
from sqlmodel import Session

from app import instant_service as isv, taxi_quote_consent as consent
from app.db import engine
from app.models import InstantOrder
from app.timeutil import utcnow
from test_taxi_quote_consent import (  # noqa: F401
    quote, confirm, orders, promo_user, set_base,
)
from test_taxi_quote_consent import consent_market as consent_market
from test_taxi_quote_result import lookup, saved_state, UNKNOWN

CLOSE = "/instant/orders/close-attempt/v1"


def close(client, user, key):
    return client.post(CLOSE, headers=user["auth"], json={"request_key": key})


def attempt(user, key):
    from app.models import TaxiConsentAttempt
    with Session(engine) as session:
        row = session.get(TaxiConsentAttempt, (user["id"], key))
        return row.model_dump() if row else None


def test_close_known_attempt_prevents_delayed_confirm(client, user_factory, consent_market):
    user = user_factory("SyntheticClose")
    q = quote(client, user)
    closed = close(client, user, q["request_key"])
    assert closed.status_code == 200, "Explicit close must exist for an owned issued attempt"
    assert closed.json() == {"schema": "taxi-consent-close-v1", "state": "closed",
                             "history": "not_created", "admission": "blocked"}
    assert closed.headers["cache-control"] == "no-store"
    before = saved_state(user["id"])
    assert confirm(client, user, q).status_code == 409
    assert saved_state(user["id"]) == before
    assert attempt(user, q["request_key"])["state"] == "closed"
    assert close(client, user, q["request_key"]).json() == closed.json()
    assert lookup(client, user, q["request_key"]).json() == UNKNOWN


def collect_expired(at):
    from app import cleanup
    _, table, where, params = next(r for r in cleanup._rules(at) if r[1] == "taxiconsentattempt")
    return cleanup._delete_batched(table, where, params)


@pytest.fixture
def old_waits():
    """Own fixture waits must not steal drivers from later worker tests."""
    ids = []
    try:
        yield ids
    finally:
        with Session(engine) as session:
            for oid in ids:
                row = session.get(InstantOrder, oid)
                if row is not None:
                    row.wait_until = None
                    session.add(row)
            session.commit()


def test_close_keeps_promo_old_wait_and_dispatch_untouched_without_redis(
    client, user_factory, consent_market, monkeypatch, old_waits,
):
    set_base(600)
    user = promo_user(client, user_factory)
    wait = utcnow() + timedelta(minutes=20)
    with Session(engine) as session:
        old = InstantOrder(passenger_id=user["id"], status="expired", wait_until=wait,
                           from_lat=52.59, from_lng=58.31, to_lat=52.71, to_lng=58.66, price_estimate=600)
        session.add(old)
        session.commit()
        old_waits.append(old.id)
    q = quote(client, user)
    assert q["payable_kop"] == 45000
    before = saved_state(user["id"])
    def forbidden(*args, **kwargs):
        raise AssertionError("closed attempt reached pricing/promo/dispatch")
    monkeypatch.setattr(consent, "claim_promo", forbidden)
    monkeypatch.setattr(isv, "start_matching", forbidden)
    monkeypatch.setattr(isv, "estimate", forbidden)
    consent_market.unavailable = True
    assert close(client, user, q["request_key"]).json()["state"] == "closed"
    assert confirm(client, user, q).status_code == 409
    assert saved_state(user["id"]) == before


def test_gc_and_restored_cache_cannot_reopen_closed_attempt(client, user_factory, consent_market):
    user = user_factory("SyntheticGC")
    q = quote(client, user)
    key = "taxi:initial-quote:v1:" + str(user["id"]) + ":" + q["quote_id"]
    raw = consent_market.redis.get(key)
    expiry = attempt(user, q["request_key"])["expires_at"]
    assert close(client, user, q["request_key"]).json()["history"] == "not_created"
    assert attempt(user, q["request_key"])["expires_at"] == expiry
    assert collect_expired(expiry + timedelta(seconds=1)) >= 1
    assert attempt(user, q["request_key"]) is None
    # Deliberately retain/restore an unexpired cache in this synthetic clock interleaving.
    consent_market.redis.set(key, raw, ex=600)
    assert confirm(client, user, q).status_code == 409
    closed = close(client, user, q["request_key"]).json()
    assert closed == {"schema": "taxi-consent-close-v1", "state": "unknown",
                      "history": "unverified", "admission": "blocked"}
    assert not orders(user["id"])


def test_committed_replay_precedes_closed_and_gc_even_after_terminal(
    client, user_factory, consent_market, monkeypatch,
):
    user = user_factory("SyntheticCommitted")
    q = quote(client, user)
    dispatches = []
    original = isv.start_matching
    def dispatch(session, order):
        dispatches.append(order.id)
        return original(session, order)
    monkeypatch.setattr(isv, "start_matching", dispatch)
    created = confirm(client, user, q)
    assert created.status_code == 200
    oid = created.json()["order"]["id"]
    assert attempt(user, q["request_key"])["state"] == "committed"
    positive = close(client, user, q["request_key"])
    assert positive.status_code == 200 and positive.json()["state"] == "committed"
    assert positive.json()["order_id"] == oid
    assert collect_expired(attempt(user, q["request_key"])["expires_at"] + timedelta(seconds=1)) >= 1
    consent_market.unavailable = True
    before = saved_state(user["id"])
    for _ in range(2):
        assert close(client, user, q["request_key"]).json() == positive.json()
        replay = confirm(client, user, q)
        assert replay.status_code == 200 and replay.json()["order"]["id"] == oid
    conflict = confirm(client, user, q, comment="different operational request")
    assert conflict.status_code == 409 and conflict.json()["detail"]["code"] == "taxi_consent_key_conflict"
    assert saved_state(user["id"]) == before and dispatches == [oid]


def test_physical_delete_preserves_only_available_history_then_unknown_after_gc(
    client, user_factory, consent_market,
):
    user = user_factory("SyntheticDeleted")
    q = quote(client, user)
    result = confirm(client, user, q)
    assert result.status_code == 200
    expiry = attempt(user, q["request_key"])["expires_at"]
    with Session(engine) as session:
        order = session.get(InstantOrder, result.json()["order"]["id"])
        assert order.status == "expired"
        session.delete(order)
        session.commit()
    retired = close(client, user, q["request_key"])
    assert retired.json() == {"schema": "taxi-consent-close-v1", "state": "retired",
                              "history": "created_unavailable", "admission": "blocked"}
    assert confirm(client, user, q).status_code == 409
    assert lookup(client, user, q["request_key"]).json() == UNKNOWN
    collect_expired(expiry + timedelta(seconds=1))
    assert close(client, user, q["request_key"]).json()["history"] == "unverified"
    assert confirm(client, user, q).status_code == 409 and not orders(user["id"])


def test_foreign_and_unissued_are_indistinguishable_without_closing_foreign_attempt(
    client, user_factory, consent_market,
):
    owner, outsider = user_factory("SyntheticOwner"), user_factory("SyntheticOther")
    q = quote(client, owner)
    before = attempt(owner, q["request_key"])
    foreign = close(client, outsider, q["request_key"])
    missing = close(client, outsider, str(uuid4()))
    assert foreign.status_code == missing.status_code == 200 and foreign.json() == missing.json()
    assert foreign.json()["history"] == "unverified"
    assert attempt(owner, q["request_key"]) == before
    assert confirm(client, owner, q).status_code == 200
    assert not orders(outsider["id"])


def test_unused_pre_cutover_quote_is_not_backfilled(client, user_factory, consent_market):
    from app.models import TaxiConsentAttempt
    user = user_factory("SyntheticOldQuote")
    q = quote(client, user)
    with Session(engine) as session:
        session.delete(session.get(TaxiConsentAttempt, (user["id"], q["request_key"])))
        session.commit()
    assert close(client, user, q["request_key"]).json()["history"] == "unverified"
    assert confirm(client, user, q).status_code == 409
    assert attempt(user, q["request_key"]) is None
    assert not orders(user["id"])


def test_close_rollback_does_not_claim_success_or_block_a_valid_confirm(
    client, user_factory, consent_market,
):
    from app.models import TaxiConsentAttempt
    from sqlalchemy.exc import OperationalError
    user = user_factory("SyntheticCloseRollback")
    q = quote(client, user)
    def fail(session):
        if any(isinstance(o, TaxiConsentAttempt) and o.state == "closed" for o in session.dirty):
            raise OperationalError("synthetic", {"request_key": q["request_key"]}, Exception("synthetic"))
    event.listen(Session, "before_commit", fail)
    try:
        denied = close(client, user, q["request_key"])
    finally:
        event.remove(Session, "before_commit", fail)
    assert denied.status_code == 503 and q["request_key"] not in denied.text
    assert attempt(user, q["request_key"])["state"] == "issued"
    assert confirm(client, user, q).status_code == 200


def test_confirm_rollback_keeps_fence_and_all_financial_side_effects_rollback(
    client, user_factory, consent_market, monkeypatch,
):
    set_base(600)
    user = promo_user(client, user_factory)
    q = quote(client, user)
    before = saved_state(user["id"])
    def fail(*args, **kwargs):
        raise consent.conflict()
    with monkeypatch.context() as patch:
        patch.setattr(consent, "claim_promo", fail)
        assert confirm(client, user, q).status_code == 409
    assert saved_state(user["id"]) == before and attempt(user, q["request_key"])["state"] == "issued"
    assert close(client, user, q["request_key"]).json()["history"] == "not_created"
    assert confirm(client, user, q).status_code == 409


def test_orphan_cache_after_issue_db_rollback_is_not_admissible(client, user_factory, consent_market):
    from sqlalchemy.exc import OperationalError
    user = user_factory("SyntheticIssueRollback")
    def fail(session):
        if session.connection().execute(text("SELECT count(*) FROM taxiconsentattempt WHERE owner_id=:owner"), {"owner": user["id"]}).scalar():
            raise OperationalError("synthetic", {}, Exception("synthetic"))
    event.listen(Session, "before_commit", fail)
    try:
        from test_money_rules import _order_body
        response = client.post("/instant/quotes/v1", headers=user["auth"], json=_order_body())
    finally:
        event.remove(Session, "before_commit", fail)
    assert response.status_code == 503
    raw = [consent_market.redis.get(k) for k in consent_market.redis.keys("taxi:initial-quote:v1:" + str(user["id"]) + ":*")]
    assert len(raw) == 1
    data = json.loads(raw[0])
    q = consent.public_snapshot(data)
    assert attempt(user, q["request_key"]) is None
    assert confirm(client, user, q).status_code == 409 and not orders(user["id"])


@pytest.mark.parametrize("payload", [{}, {"request_key": "bad"}, {"request_key": str(uuid4()), "extra": "synthetic"}])
def test_close_validation_and_auth_do_not_echo_inputs(client, user_factory, payload):
    user = user_factory("SyntheticCloseValidation")
    assert client.post(CLOSE, json={"request_key": str(uuid4())}).status_code == 401
    response = client.post(CLOSE, headers=user["auth"], json=payload)
    assert response.status_code == 422
    assert payload.get("request_key", "not present") not in response.text


def test_expired_known_state_is_not_confused_with_cache_absence(client, user_factory, consent_market, monkeypatch):
    user = user_factory("SyntheticExpiredKnown")
    q = quote(client, user)
    original = attempt(user, q["request_key"])
    consent_market.redis.flushall()
    monkeypatch.setattr(consent, "utcnow", lambda: original["expires_at"] + timedelta(seconds=1))
    assert close(client, user, q["request_key"]).json()["history"] == "not_created"
    assert attempt(user, q["request_key"])["expires_at"] == original["expires_at"]
    assert confirm(client, user, q).status_code == 409


def test_lookup_never_closes_or_renews_new_admission_record(client, user_factory, consent_market):
    user = user_factory("SyntheticReadOnly")
    q = quote(client, user)
    before = attempt(user, q["request_key"])
    assert lookup(client, user, q["request_key"]).json() == UNKNOWN
    assert attempt(user, q["request_key"]) == before
    assert confirm(client, user, q).status_code == 200


def test_account_removal_cascades_ephemeral_attempt(client, user_factory, consent_market):
    from app.account import delete_user_account
    from app.models import User
    user = user_factory("SyntheticDeleteOwner")
    q = quote(client, user)
    assert attempt(user, q["request_key"])
    with Session(engine) as session:
        delete_user_account(session, session.get(User, user["id"]))
    assert attempt(user, q["request_key"]) is None
