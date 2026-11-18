"""Regression for a cheaper fallback clearing an offer without continuing matching."""
import pytest
from sqlmodel import Session

from app import instant_service as isv
from app.db import engine
from app.models import InstantOrder, InstantOrderStatus as S
from app.config import settings
from test_instant import _offered_order
from test_taxi_offer_token import fake_redis  # noqa: F401


@pytest.mark.usefixtures("fake_redis")
def test_cheaper_fallback_does_not_leave_orphan_offered_order(client, user_factory, monkeypatch):
    monkeypatch.setattr(settings, "car_class_min_drivers", 0)
    driver, passenger, payload = _offered_order(client, user_factory, isv._redis(), "FallbackDriver", "FallbackPax")
    oid = payload["id"]
    with Session(engine) as session:
        order = session.get(InstantOrder, oid)
        order.category = "comfort"
        order.distance_km = 5
        order.eta_min = 10
        order.pricing_k = 1
        order.price_estimate = 500
        order.ride_price = 500
        order.ride_base_price = 500
        session.add(order)
        session.commit()
    response = client.post(f"/instant/orders/{oid}/alternatives", headers=passenger["auth"], json={"category": "standard"})
    assert response.status_code == 200
    assert response.json()["price"] < 500, "test must actually lower the displayed price"
    with Session(engine) as session:
        order = session.get(InstantOrder, oid)
        # Normal progress may yield a fresh valid offer or an explicit no-driver outcome.
        assert order.status != S.offered or (
            order.current_offer_driver_id is not None and order.offer_expires_at is not None
        ), "offered order lost both recipient and expiry; worker/lazy timeout cannot advance it"


@pytest.mark.parametrize("has_candidate", [True, False])
@pytest.mark.usefixtures("fake_redis")
def test_fallback_continues_round_with_new_token_or_expiry(client, user_factory, monkeypatch, has_candidate):
    monkeypatch.setattr(settings, "car_class_min_drivers", 0)
    driver, passenger, payload = _offered_order(client, user_factory, isv._redis(), "NextFallbackDriver", "NextFallbackPax")
    oid = payload["id"]
    monkeypatch.setattr(isv, "candidates", lambda *args, **kwargs: [driver["id"]] if has_candidate else [])
    with Session(engine) as session:
        order = session.get(InstantOrder, oid)
        old_token = order.offer_token
        order.category = "comfort"
        order.distance_km, order.eta_min, order.pricing_k = 5, 10, 1
        isv.set_ride_price(order, 500, 500)
        order.search_round = 3
        session.add(order)
        session.commit()
    response = client.post(f"/instant/orders/{oid}/alternatives", headers=passenger["auth"], json={"category": "standard"})
    assert response.status_code == 200
    with Session(engine) as session:
        order = session.get(InstantOrder, oid)
        assert order.status == (S.offered if has_candidate else S.expired)
        assert order.search_round == (4 if has_candidate else 3)
        if has_candidate:
            new_token = order.offer_token
            assert new_token != old_token
            assert order.current_offer_driver_id == driver["id"]
    if has_candidate:
        stale = client.post(f"/instant/orders/{oid}/decline", headers=driver["auth"], json={"expected_offer_token": old_token})
        assert stale.status_code == 200
        assert stale.json()["status"] == "offered"
        assert stale.json()["offer_token"] == new_token


@pytest.mark.usefixtures("fake_redis")
def test_fallback_without_price_drop_keeps_current_offer(client, user_factory, monkeypatch):
    monkeypatch.setattr(settings, "car_class_min_drivers", 0)
    _, passenger, payload = _offered_order(client, user_factory, isv._redis(), "SamePriceDriver", "SamePricePax")
    oid = payload["id"]
    with Session(engine) as session:
        order = session.get(InstantOrder, oid)
        order.category = "comfort"
        isv.set_ride_price(order, 1, 1)
        before = (order.offer_token, order.current_offer_driver_id, order.offer_expires_at, order.search_round)
        session.add(order)
        session.commit()
    monkeypatch.setattr(isv, "try_offer_next", lambda *args: pytest.fail("unchanged price restarted offer"))
    assert client.post(f"/instant/orders/{oid}/alternatives", headers=passenger["auth"], json={"category": "standard"}).status_code == 200
    with Session(engine) as session:
        order = session.get(InstantOrder, oid)
        assert order.status == S.offered
        assert (order.offer_token, order.current_offer_driver_id, order.offer_expires_at, order.search_round) == before


@pytest.mark.parametrize("transition", ["accept", "cancel"])
@pytest.mark.usefixtures("fake_redis")
def test_stale_fallback_rechecks_state_before_money_changes(client, user_factory, monkeypatch, transition):
    from fastapi import HTTPException
    monkeypatch.setattr(settings, "car_class_min_drivers", 0)
    driver, passenger, payload = _offered_order(client, user_factory, isv._redis(), "StaleFallbackDriver", "StaleFallbackPax")
    oid = payload["id"]
    with Session(engine) as stale_session:
        stale = stale_session.get(InstantOrder, oid)
        with Session(engine) as other:
            if transition == "accept":
                isv.transition(other, oid, isv.Actor.driver, S.accepted, driver["id"])
            else:
                isv.cancel_order(other, oid, isv.Actor.passenger, passenger["id"], "changed_mind")
        with Session(engine) as observer:
            saved = observer.get(InstantOrder, oid)
            before = (saved.status, saved.price_estimate, saved.ride_price, saved.fallback_categories)
        with pytest.raises(HTTPException) as error:
            isv.add_fallback_category(stale_session, stale, "standard")
        assert error.value.status_code == 409
    with Session(engine) as observer:
        saved = observer.get(InstantOrder, oid)
        assert (saved.status, saved.price_estimate, saved.ride_price, saved.fallback_categories) == before


@pytest.mark.usefixtures("fake_redis")
def test_previous_driver_cannot_accept_repriced_offer_sent_elsewhere(client, user_factory, monkeypatch):
    from test_instant import _driver_online
    monkeypatch.setattr(settings, "car_class_min_drivers", 0)
    old_driver, passenger, payload = _offered_order(client, user_factory, isv._redis(), "PreviousFallbackDriver", "RepriceFallbackPax")
    new_driver = _driver_online(client, user_factory, "NewFallbackDriver")
    oid = payload["id"]
    monkeypatch.setattr(isv, "candidates", lambda *args, **kwargs: [new_driver["id"]])
    with Session(engine) as session:
        order = session.get(InstantOrder, oid)
        order.category = "comfort"
        order.distance_km, order.eta_min, order.pricing_k = 5, 10, 1
        isv.set_ride_price(order, 500, 500)
        session.add(order)
        session.commit()
    assert client.post(f"/instant/orders/{oid}/alternatives", headers=passenger["auth"], json={"category": "standard"}).status_code == 200
    denied = client.post(f"/instant/orders/{oid}/accept", headers=old_driver["auth"])
    assert denied.status_code == 403
    assert denied.json()["detail"]["ru"] == "Заказ предложен другому водителю"
    with Session(engine) as session:
        order = session.get(InstantOrder, oid)
        assert order.status == S.offered
        assert order.current_offer_driver_id == new_driver["id"]
        assert order.driver_id is None
