"""Accept requests must not cross offer, route or price boundaries."""
from concurrent.futures import ThreadPoolExecutor
from threading import Event

import pytest
from sqlmodel import Session

from app import instant_service as isv
from app.db import engine
from app.models import InstantOrder, InstantOrderStatus as S
import test_instant
from test_instant import ORIG, _driver_online, _heartbeat, _offered_order
from test_taxi_route_write_races import POINTS, _destination, _price, _route

fake_redis = test_instant.fake_redis


def _state(order_id):
    with Session(engine) as session:
        order = session.get(InstantOrder, order_id)
        return (order.status, order.driver_id, order.current_offer_driver_id,
                order.to_lat, order.to_lng, order.price_estimate, order.ride_price,
                order.promo_discount_kop, order.destination_changes)


def _offer(client, user_factory, fake_redis, case):
    driver, passenger, order = _offered_order(
        client, user_factory, fake_redis, f"OfferedDrv{case}", f"OfferedPax{case}",
    )
    return driver, passenger, order["id"]


@pytest.mark.parametrize("pause_at", ("before_order_lock", "after_order_lock"))
def test_driver_request_that_observed_old_offer_cannot_accept_replacement_for_other_driver(
        client, user_factory, fake_redis, monkeypatch, pause_at):
    old_driver, passenger, oid = _offer(client, user_factory, fake_redis, "StaleAccept")
    new_driver = _driver_online(client, user_factory, "OfferedDrvStaleAcceptNext")
    _heartbeat(client, new_driver, ORIG)
    entered, proceed = Event(), Event()
    if pause_at == "after_order_lock" and engine.dialect.name != "sqlite":
        pytest.skip("PostgreSQL intentionally serializes behind the Order lock")
    target = "driver_pause_until" if pause_at == "before_order_lock" else "_settle_pickup_fee"
    real = getattr(isv, target)

    def pause_after_router_load(session, *args, **kwargs):
        driver_id = args[0] if pause_at == "before_order_lock" else args[1]
        if driver_id == old_driver["id"]:
            entered.set()
            assert proceed.wait(10)
        return real(session, *args, **kwargs)

    calls = []
    real_notify, real_cleanup = isv._notify_transition, isv._cleanup_tried

    def record_transition(session, order, status):
        calls.append((order.id, status, order.driver_id))
        return real_notify(session, order, status)

    def record_cleanup(order_id):
        calls.append((order_id, "cleanup"))
        return real_cleanup(order_id)

    monkeypatch.setattr(isv, "_notify_transition", record_transition)
    monkeypatch.setattr(isv, "_cleanup_tried", record_cleanup)
    monkeypatch.setattr(isv, target, pause_after_router_load)
    # Keep real pricing and its captured tariff; only make route metrics local.
    monkeypatch.setattr(isv.pricing, "route_metrics", isv.pricing.fallback_route)
    with ThreadPoolExecutor(max_workers=1) as pool:
        old_accept = pool.submit(client.post, _route(oid, "accept"), headers=old_driver["auth"])
        try:
            assert entered.wait(10)
            edit = client.post(_route(oid, "destination"), headers=passenger["auth"],
                               json=_destination())
            assert edit.status_code == 200, edit.text
            committed = _state(oid)
            assert committed[:3] == (S.offered, None, new_driver["id"])
        finally:
            proceed.set()
        result = old_accept.result(timeout=10)
    assert result.status_code == 409, result.text
    assert result.json()["detail"]["code"] == "taxi_offer_changed"
    assert _state(oid) == committed
    assert calls == []
    next_accept = client.post(_route(oid, "accept"), headers=new_driver["auth"])
    assert next_accept.status_code == 200, next_accept.text
    assert _state(oid)[:3] == (S.accepted, new_driver["id"], None)
    assert _state(oid)[3:] == committed[3:]
    assert calls == [(oid, "cleanup"), (oid, S.accepted, new_driver["id"])]


def test_live_waypoint_reprice_fences_accept_already_in_flight_to_same_driver(
        client, user_factory, fake_redis, monkeypatch):
    driver, passenger, oid = _offer(client, user_factory, fake_redis, "SameDriver")
    entered, proceed = Event(), Event()
    real_pause = isv.driver_pause_until

    def pause_before_order_lock(session, driver_id):
        entered.set()
        assert proceed.wait(10)
        return real_pause(session, driver_id)

    monkeypatch.setattr(isv, "driver_pause_until", pause_before_order_lock)
    monkeypatch.setattr(isv, "destination_quote",
                        lambda s, o, d, **kw: _price(130, o))
    with ThreadPoolExecutor(max_workers=1) as pool:
        stale = pool.submit(client.post, _route(oid, "accept"), headers=driver["auth"])
        try:
            assert entered.wait(10)
            edit = client.post(_route(oid, "waypoints"), headers=passenger["auth"],
                               json={"waypoints": POINTS})
            assert edit.status_code == 200, edit.text
            committed = _state(oid)
            assert committed[:3] == (S.offered, None, driver["id"])
            assert committed[5:7] == (130, 130)
        finally:
            proceed.set()
        result = stale.result(timeout=10)
    assert result.status_code == 409, result.text
    assert result.json()["detail"]["code"] == "taxi_offer_changed"
    assert _state(oid) == committed
    # The wire API is unchanged: a genuinely new request can accept this offer.
    accepted = client.post(_route(oid, "accept"), headers=driver["auth"])
    assert accepted.status_code == 200, accepted.text
