"""Owner-scoped positive result only: real HTTP/auth/SQL, synthetic requests."""
import json
from uuid import uuid4

import pytest
from sqlalchemy import event
from sqlalchemy.exc import OperationalError
from sqlmodel import Session, select

from app import instant_service as isv, taxi_quote_consent as consent
from app.db import engine
from app.models import InstantOrder, InstantOrderStatus as S, LedgerEntry, PromoRedemption, User
from test_taxi_quote_consent import orders, promo_user, set_base

from test_taxi_quote_consent import consent_market, quote, confirm  # noqa: F401

RESULT = "/instant/orders/result/v1"


def lookup(client, user, key):
    return client.post(RESULT, headers=user["auth"], json={"request_key": key})


def test_result_recovers_committed_without_request_body(client, user_factory, consent_market):
    user = user_factory("SyntheticResult")
    q = quote(client, user)
    created = confirm(client, user, q)
    assert created.status_code == 200, created.text
    response = lookup(client, user, q["request_key"])
    assert response.status_code == 200, response.text
    assert response.json() == {
        "schema": "taxi-consent-result-v1", "state": "committed",
        "order_id": created.json()["order"]["id"], "initial_payable_kop": 45000, "next": "fetch_order",
    }
    assert response.headers["cache-control"] == "no-store"

UNKNOWN = {"schema": "taxi-consent-result-v1", "state": "unknown"}


def saved_state(owner):
    with Session(engine) as s:
        return {
            "orders": [o.model_dump() for o in s.exec(select(InstantOrder).where(InstantOrder.passenger_id == owner)).all()],
            "promo": [r.model_dump() for r in s.exec(select(PromoRedemption).where(PromoRedemption.user_id == owner)).all()],
            "ledger": [x.model_dump() for x in s.exec(select(LedgerEntry)).all()],
        }


@pytest.mark.parametrize("discount", [0, 150])
def test_expiry_terminal_redis_loss_read_never_dispatches_or_mutates(client, user_factory, consent_market, monkeypatch, discount):
    set_base(600)
    user = promo_user(client, user_factory) if discount else user_factory("SyntheticResult600")
    q = quote(client, user)
    first = confirm(client, user, q)
    assert first.status_code == 200, first.text
    oid = first.json()["order"]["id"]
    with Session(engine) as s:
        row = s.get(InstantOrder, oid)
        row.status = S.cancelled
        row.promo_discount_kop = 0  # Historical initial amount must not impersonate terminal current payable.
        s.add(row)
        s.commit()
    before = saved_state(user["id"])
    consent_market.clock.offset = 121
    consent_market.unavailable = True
    def forbidden(*a, **k):
        raise AssertionError("result lookup invoked a mutation/serializer")
    monkeypatch.setattr(isv, "start_matching", forbidden)
    monkeypatch.setattr(isv, "order_payload", forbidden)
    monkeypatch.setattr(consent, "claim_promo", forbidden)
    monkeypatch.setattr(consent, "issue", forbidden)
    for _ in range(2):
        r = lookup(client, user, q["request_key"])
        assert r.status_code == 200, r.text
        assert r.json() == {"schema": "taxi-consent-result-v1", "state": "committed", "order_id": oid,
                            "initial_payable_kop": 60000-discount*100, "next": "fetch_order"}
    assert saved_state(user["id"]) == before


def test_foreign_missing_unused_deleted_are_same_unknown_and_never_release(client, user_factory, consent_market):
    owner = user_factory("SyntheticLookupOwner")
    other = user_factory("SyntheticLookupOther")
    q = quote(client, owner)
    unused = lookup(client, owner, q["request_key"])
    assert unused.json() == UNKNOWN
    first = confirm(client, owner, q)  # An earlier unknown did NOT prevent a later real admission.
    assert first.status_code == 200, first.text
    foreign = lookup(client, other, q["request_key"])
    missing = lookup(client, other, str(uuid4()))
    with Session(engine) as s:
        row = s.get(InstantOrder, first.json()["order"]["id"])
        assert row.status == S.expired
        s.delete(row)
        s.commit()
    deleted = lookup(client, owner, q["request_key"])
    for r in (unused, foreign, missing, deleted):
        assert r.status_code == 200 and r.json() == UNKNOWN
        assert r.headers["cache-control"] == "no-store"
    assert confirm(client, owner, q).status_code == 409
    assert not orders(owner["id"])


def test_auth_revocation_and_other_participant_do_not_grant_lookup(client, user_factory, consent_market):
    user = user_factory("SyntheticResultRevoked")
    q = quote(client, user)
    assert confirm(client, user, q).status_code == 200
    assert client.post(RESULT, json={"request_key": q["request_key"]}).status_code in (401, 403)
    with Session(engine) as s:
        row = s.get(User, user["id"])
        row.auth_epoch += 1
        s.add(row)
        s.commit()
    response = lookup(client, user, q["request_key"])
    assert response.status_code == 401
    assert q["request_key"] not in response.text


def test_validation_and_access_logs_never_echo_key_or_body(client, user_factory, consent_market, caplog):
    user = user_factory("SyntheticResultPrivacy")
    q = quote(client, user)
    caplog.clear()
    assert lookup(client, user, q["request_key"]).json() == UNKNOWN
    for payload in ({}, {"request_key": "not-a-uuid"}, {"request_key": q["request_key"], "comment": "Synthetic private text"}):
        r = client.post(RESULT, headers=user["auth"], json=payload)
        assert r.status_code == 422
        assert q["request_key"] not in r.text and "Synthetic private text" not in r.text
    assert q["request_key"] not in caplog.text and "Synthetic private text" not in caplog.text
    assert client.get(RESULT, headers=user["auth"]).status_code in (404, 405)


@pytest.mark.parametrize("corrupt", ["json", "amount", "identity", "discount"])
def test_corrupt_snapshot_is_unavailable_never_unknown(client, user_factory, consent_market, corrupt, caplog):
    user = user_factory("SyntheticResultCorrupt")
    q = quote(client, user)
    r = confirm(client, user, q)
    assert r.status_code == 200
    with Session(engine) as s:
        row = s.get(InstantOrder, r.json()["order"]["id"])
        data = json.loads(row.consent_snapshot_json)
        if corrupt == "json":
            row.consent_snapshot_json = "invalid"
        else:
            if corrupt == "amount":
                data["accepted"]["payable_kop"] = True
            elif corrupt == "identity":
                data["accepted"]["request_key"] = str(uuid4())
            else:
                data["accepted"]["promo_discount_kop"] = 1
                data["accepted"]["payable_kop"] -= 1
            row.consent_snapshot_json = json.dumps(data)
        s.add(row)
        s.commit()
    caplog.clear()
    r = lookup(client, user, q["request_key"])
    assert r.status_code == 503 and r.json()["detail"]["code"] == "taxi_consent_result_unavailable"
    assert r.headers["cache-control"] == "no-store"
    assert q["request_key"] not in r.text + caplog.text


def test_database_error_does_not_log_bound_correlator(client, user_factory, consent_market, caplog):
    user = user_factory("SyntheticResultDbError")
    key = quote(client, user)["request_key"]
    def fail(connection, cursor, statement, parameters, context, many):
        if "instantorder.consent_snapshot_json" in statement and "SELECT" in statement:
            raise OperationalError(statement, parameters, Exception(key))
    caplog.clear()
    event.listen(engine, "before_cursor_execute", fail)
    try:
        response = lookup(client, user, key)
    finally:
        event.remove(engine, "before_cursor_execute", fail)
    assert response.status_code == 503
    assert key not in response.text + caplog.text
