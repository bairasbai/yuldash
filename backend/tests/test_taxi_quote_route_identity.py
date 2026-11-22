"""Taxi-only quote identity regression on synthetic isolated requests."""
import hashlib
import json

import fakeredis
import pytest
from sqlmodel import Session

from app import instant_service as isv
from app import price_freeze as freeze
from app.config import settings
from app.db import engine
from app.models import InstantOrder
from test_instant import ORIG, DEST, _order_body
from test_price_freeze import _est


@pytest.fixture
def fake_redis(monkeypatch):
    redis = fakeredis.FakeStrictRedis(decode_responses=True)
    monkeypatch.setattr(isv, "_redis_override", redis)
    return redis


NEAR_ORIGIN = {"lat": 52.60, "lng": 58.40}
NEAR_DESTINATION = {"lat": 52.70, "lng": 58.60}
DETOUR = {"lat": 52.80, "lng": 58.20}


@pytest.mark.parametrize("shown_stops,ordered_stops", [
    pytest.param([], [DETOUR], id="add"),
    pytest.param([NEAR_ORIGIN], [DETOUR], id="move"),
    pytest.param([NEAR_ORIGIN], [], id="remove"),
    pytest.param([NEAR_ORIGIN, NEAR_DESTINATION], [NEAR_DESTINATION, NEAR_ORIGIN], id="reorder"),
])
def test_changed_route_does_not_inherit_an_incompatible_quote(
    client, user_factory, fake_redis, monkeypatch, shown_stops, ordered_stops,
):
    pax = user_factory("QuoteRouteIdentity")
    shown_body = _order_body(waypoints=shown_stops)
    ordered_body = _order_body(waypoints=ordered_stops)
    shown = client.post("/instant/estimate", headers=pax["auth"], json=shown_body)
    assert shown.status_code == 200
    assert shown.json()["price_locked_sec"] > 0
    monkeypatch.setattr(isv, "surge_k_for", lambda *a, **k: settings.surge_max_k)
    with Session(engine) as session:
        expected = isv.estimate(
            session, (ordered_body["from_lat"], ordered_body["from_lng"]),
            (ordered_body["to_lat"], ordered_body["to_lng"]),
            waypoints=ordered_stops,
        )
    assert expected["price"] > shown.json()["price"], "Fixture must distinguish routes"
    created = client.post("/instant/orders", headers=pax["auth"], json=ordered_body)
    assert created.status_code == 200
    assert created.json()["price_estimate"] == expected["price"]
    with Session(engine) as session:
        order = session.get(InstantOrder, created.json()["id"])
        assert order.distance_km == expected["distance_km"]
        assert order.price_estimate == expected["price"]


def test_route_signature_boundaries(fake_redis):
    a = {"lat": 52.80, "lng": 58.20, "text": "A"}
    b = {"lat": 52.81, "lng": 58.21, "text": "B"}
    args = (fake_redis, 1, ORIG, DEST, "standard", "", False)
    original = _est(450)
    original.update(distance_km=17.0, eta_min=30.0, pickup_fee=50, ride_price=400)
    assert freeze.remember(*args, original, waypoints=[a, b]) == freeze.lock_seconds()
    fresh = _est(600)
    same = freeze.apply(*args, fresh, waypoints=[dict(a, text="Renamed"), b])
    assert same["price_was_frozen"] is True
    for key in freeze.FROZEN_KEYS:
        if key in original:
            assert same[key] == original[key]
    for changed in ([], [a], [b, a], [a, dict(b, lat=52.810001)], [a, b, a]):
        assert freeze.apply(*args, fresh, waypoints=changed) == fresh
    cheaper = _est(400)
    assert freeze.apply(*args, cheaper, waypoints=[a, b]) == cheaper
    fake_redis.delete(*fake_redis.keys("pricefreeze:*"))
    assert freeze.apply(*args, fresh, waypoints=[a, b]) == fresh


def test_empty_route_compatibility_and_legacy_cache_is_ignored(fake_redis):
    args = (fake_redis, 1, ORIG, DEST, "standard", "", False)
    raw = f"{ORIG[0]:.3f},{ORIG[1]:.3f}->{DEST[0]:.3f},{DEST[1]:.3f}|standard||0"
    legacy = "pricefreeze:1:" + hashlib.sha1(raw.encode()).hexdigest()[:12]
    fake_redis.set(legacy, json.dumps(_est(100)))
    assert freeze.apply(*args, _est(600)) == _est(600)
    freeze.remember(*args, _est(450), waypoints=[])
    assert freeze.apply(*args, _est(600), waypoints=None)["price"] == 450
    key = freeze._key(1, ORIG, DEST, "standard", "", False)
    assert 0 < fake_redis.ttl(key) <= freeze.lock_seconds()


def test_same_waypoint_route_keeps_shown_price_when_demand_rises(
    client, user_factory, fake_redis, monkeypatch,
):
    pax = user_factory("QuoteSameRoute")
    body = _order_body(waypoints=[{"lat": 52.80, "lng": 58.20, "text": "Before"}])
    shown = client.post("/instant/estimate", headers=pax["auth"], json=body)
    assert shown.status_code == 200
    monkeypatch.setattr(isv, "surge_k_for", lambda *a, **k: settings.surge_max_k)
    body["waypoints"][0]["text"] = "After"
    created = client.post("/instant/orders", headers=pax["auth"], json=body)
    assert created.status_code == 200
    with Session(engine) as session:
        order = session.get(InstantOrder, created.json()["id"])
        assert order.price_estimate == shown.json()["price"]
        assert order.distance_km == shown.json()["distance_km"]
        assert order.eta_min == shown.json()["eta_min"]


def test_numerically_equivalent_waypoints_keep_the_quote(fake_redis):
    args = (fake_redis, 1, ORIG, DEST, "standard", "", False)
    freeze.remember(*args, _est(450), waypoints=[{"lat": 52, "lng": -0.0}])
    same = freeze.apply(*args, _est(600), waypoints=[{"lat": 52.0, "lng": 0}])
    assert same["price"] == 450
