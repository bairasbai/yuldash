"""Real driver offer/accept transactions with protected and legacy wire controls."""
from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta
from threading import Event
from time import sleep

import pytest
from sqlmodel import Session

from app import instant_service as isv
from app.db import engine
from app.models import InstantOrder, InstantOrderStatus as S
from app.timeutil import utcnow
import test_instant
from test_instant import _create_order, _driver_online, _offered_order
from test_taxi_route_write_races import POINTS, _price, _route

fake_redis = test_instant.fake_redis
FIELD = "offer_card_precondition"


@pytest.fixture
def offer(client, user_factory, fake_redis):
    driver, passenger, order = _offered_order(client, user_factory, fake_redis, "ClickD", "ClickP")
    return driver, passenger, order["id"]


def _card(client, who):
    response = client.get("/instant/driver/offer", headers=who["auth"])
    assert response.status_code == 200, response.text
    assert response.headers["cache-control"] == "no-store"
    return response.json()["offer"]


def _protected(client, oid, user, token):
    return client.post(_route(oid, "accept/v2"), headers=user["auth"], json={FIELD: token})


def _rewrite(client, passenger, oid, monkeypatch):
    monkeypatch.setattr(isv, "destination_quote", lambda s, o, d, **kw: _price(130, o))
    r = client.post(_route(oid, "waypoints"), headers=passenger["auth"], json={"waypoints": POINTS})
    assert r.status_code == 200, r.text


def _unchanged(oid, driver):
    with Session(engine) as s:
        order = s.get(InstantOrder, oid)
        return (order.status, order.driver_id, order.current_offer_driver_id) == (S.offered, None, driver["id"])


def test_old_card_fails_after_edit_before_accept_and_fresh_card_explicitly_succeeds(
        client, offer, monkeypatch):
    driver, passenger, oid = offer
    old = _card(client, driver)
    _rewrite(client, passenger, oid, monkeypatch)
    new = _card(client, driver)
    assert old["offer_expires_at"] == new["offer_expires_at"]
    assert not old["stops"] and len(new["stops"]) == 1
    assert new["driver_gross_kop"] == 13000 != old["driver_gross_kop"]
    assert old[FIELD] != new[FIELD]
    effects = []
    cleanup = isv._cleanup_tried

    def clean(order_id):
        effects.append(order_id)
        return cleanup(order_id)

    monkeypatch.setattr(isv, "_cleanup_tried", clean)
    stale = _protected(client, oid, driver, old[FIELD])
    assert stale.status_code == 409 and stale.json()["detail"]["code"] == "taxi_offer_changed"
    assert _unchanged(oid, driver) and effects == []
    success = _protected(client, oid, driver, new[FIELD])
    assert success.status_code == 200, success.text
    assert success.json()["driver_gross_kop"] == 13000
    assert FIELD not in success.json()
    assert effects == [oid]
    assert _card(client, driver) is None


def test_tag_issuer_only_current_driver_no_cache_and_no_auth_bypass(client, user_factory, offer):
    driver, passenger, oid = offer
    stranger = _driver_online(client, user_factory, "ClickStranger")
    visible = _card(client, driver)[FIELD]
    assert len(visible) == len("v1.") + 64 and visible.startswith("v1.")
    assert _card(client, driver)[FIELD] == visible
    assert _card(client, passenger) is None and _card(client, stranger) is None
    for user in (driver, passenger):
        ordinary = client.get(_route(oid, ""), headers=user["auth"], follow_redirects=True)
        assert ordinary.status_code == 200, ordinary.text
        assert FIELD not in ordinary.json()
    assert _protected(client, oid, stranger, visible).status_code == 403
    assert _protected(client, oid, passenger, visible).status_code == 403
    assert client.post(_route(oid, "accept/v2"), json={FIELD: visible}).status_code == 401
    assert _unchanged(oid, driver)


@pytest.mark.parametrize("bad", (None, 19, True, "", "v2." + "a" * 64, "v1." + "x" * 64))
def test_v2_rejects_explicit_invalid_tag_without_returning_raw_input(client, offer, bad):
    driver, _, oid = offer
    response = _protected(client, oid, driver, bad)
    assert response.status_code == 422, response.text
    assert response.json()["detail"]["code"] == "taxi_offer_precondition_invalid"
    assert "input" not in response.json()["detail"]
    assert _unchanged(oid, driver)


@pytest.mark.parametrize("post_kwargs", ({}, {"json": {}}, {"content": "null", "headers": {"content-type": "application/json"}},
                                         {"json": {"unknown_legacy": "ignored"}}))
def test_old_endpoint_still_accepts_existing_shapes(client, offer, post_kwargs):
    driver, _, oid = offer
    kwargs = dict(post_kwargs)
    headers = {**driver["auth"], **kwargs.pop("headers", {})}
    assert client.post(_route(oid, "accept"), headers=headers, **kwargs).status_code == 200


@pytest.mark.parametrize("post_kwargs", ({}, {"json": {}}, {"content": "null", "headers": {"content-type": "application/json"}}))
def test_protected_endpoint_never_falls_back_without_a_tag(client, offer, post_kwargs):
    driver, _, oid = offer
    kwargs = dict(post_kwargs)
    headers = {**driver["auth"], **kwargs.pop("headers", {})}
    missing = client.post(_route(oid, "accept/v2"), headers=headers, **kwargs)
    assert missing.status_code == 422
    assert missing.json()["detail"]["code"] == "taxi_offer_precondition_invalid"
    assert _unchanged(oid, driver)


def test_protected_cas_rejects_change_after_first_server_read(client, offer, monkeypatch):
    driver, passenger, oid = offer
    proof = _card(client, driver)[FIELD]
    entered, proceed = Event(), Event()
    real = isv.driver_pause_until

    def stopped(session, driver_id):
        entered.set()
        assert proceed.wait(10)
        return real(session, driver_id)

    monkeypatch.setattr(isv, "driver_pause_until", stopped)
    with ThreadPoolExecutor(max_workers=1) as pool:
        accept = pool.submit(_protected, client, oid, driver, proof)
        try:
            assert entered.wait(10)
            _rewrite(client, passenger, oid, monkeypatch)
        finally:
            proceed.set()
        result = accept.result(timeout=10)
    assert result.status_code == 409 and result.json()["detail"]["code"] == "taxi_offer_changed"
    assert _unchanged(oid, driver)


@pytest.mark.parametrize(("field", "value"), (("offered_at", None), ("searching_at", None), ("price_final", 1)))
def test_unsupported_lineage_or_finalized_price_does_not_mint_or_accept(client, offer, field, value):
    driver, _, oid = offer
    previous = _card(client, driver)[FIELD]
    with Session(engine) as s:
        setattr(s.get(InstantOrder, oid), field, value)
        s.commit()
    assert FIELD not in _card(client, driver)
    result = _protected(client, oid, driver, previous)
    assert result.status_code == 409 and result.json()["detail"]["code"] == "taxi_offer_changed"
    assert _unchanged(oid, driver)


def test_valid_shape_is_not_authority_for_another_order_for_same_recipient(client, user_factory, offer):
    driver, _, oid = offer
    first_proof = _card(client, driver)[FIELD]
    # The real matcher does not assign a second offer to a currently offered
    # driver. Retire their first offer through the actual decline endpoint.
    retired = client.post(_route(oid, "decline"), headers=driver["auth"])
    assert retired.status_code == 200 and retired.json()["status"] == "expired"
    passenger_b = user_factory("ClickPassengerB")
    second = _create_order(client, passenger_b)
    assert second["id"] != oid and second["status"] == "offered"
    result = _protected(client, second["id"], driver, first_proof)
    assert result.status_code == 409 and result.json()["detail"]["code"] == "taxi_offer_changed"
    assert _unchanged(second["id"], driver)
    assert _protected(client, second["id"], driver, "v1." + "0" * 64).status_code == 409


@pytest.mark.parametrize("late_change", ("expires", "price_final"))
def test_protected_database_decision_after_initial_validation(client, offer, monkeypatch, late_change):
    if engine.dialect.name != "sqlite" and late_change == "price_final":
        pytest.skip("PostgreSQL holds the actual Order lock across this callback")
    driver, _, oid = offer
    expiry = None
    if late_change == "expires":
        expiry = utcnow() + timedelta(seconds=2.5)
        with Session(engine) as s:
            s.get(InstantOrder, oid).offer_expires_at = expiry
            s.commit()
    proof = _card(client, driver)[FIELD]
    entered, proceed = Event(), Event()
    real = isv._settle_pickup_fee

    def stop_after_guard(session, order, driver_id):
        entered.set()
        assert proceed.wait(7)
        return real(session, order, driver_id)

    monkeypatch.setattr(isv, "_settle_pickup_fee", stop_after_guard)
    with ThreadPoolExecutor(max_workers=1) as pool:
        accept = pool.submit(_protected, client, oid, driver, proof)
        try:
            assert entered.wait(5)
            if expiry is not None:
                sleep(max(0, (expiry - utcnow()).total_seconds()) + 0.05)
            else:
                with Session(engine) as s:
                    s.get(InstantOrder, oid).price_final = 1
                    s.commit()
        finally:
            proceed.set()
        result = accept.result(timeout=7)
    assert result.status_code == 409 and result.json()["detail"]["code"] == "taxi_offer_changed"
    assert _unchanged(oid, driver)
