"""Exact hidden write projection is not public preview or route-edit authority."""
from dataclasses import FrozenInstanceError, asdict, replace
from datetime import timedelta
from types import SimpleNamespace

import pytest
from fastapi import HTTPException
from sqlalchemy import delete, select
from sqlmodel import Session

from app import instant_service as isv, promo_ride, taxi_waypoint_consent as wp
from app.db import engine
from app.models import InstantOrder, InstantOrderStatus as S
from app.taxi_route_agreement import (
    QuotedTerms, WaypointWriteAgreement, bind_quoted_terms, bind_waypoint_write,
    match_waypoint_write, matches_current_terms,
)
from app.timeutil import utcnow


KEY = b"\x4f" * 32
A = (54.71, 55.81, "SYNTHETIC A")
B = (54.73, 55.83, "SYNTHETIC B")
STOP = {"lat": 54.72, "lng": 55.82, "text": "SYNTHETIC STOP"}
REQUESTED = [STOP]
ROUTE = (A, ((STOP["lat"], STOP["lng"], STOP["text"], False),), B)
QUOTE = {"ok": True, "needs_driver_ok": False, "price": 160, "old_price": 240,
         "base_price": 150, "distance_km": 7.5, "eta_min": 11.5, "tariff_id": 999}


@pytest.fixture
def order_id(user_factory):
    owner = user_factory("ProjectionOwner")["id"]
    driver = user_factory("ProjectionDriver")["id"]
    with Session(engine) as s:
        order = InstantOrder(passenger_id=owner, driver_id=driver, status=S.accepted,
                             from_lat=A[0], from_lng=A[1], from_text=A[2],
                             to_lat=B[0], to_lng=B[1], to_text=B[2],
                             price_estimate=280, ride_price=240, ride_base_price=215,
                             pickup_fee_kop=4000, promo_discount_kop=8000,
                             distance_km=8.0, eta_min=12.0)
        s.add(order)
        s.commit()
        oid = order.id
    try:
        yield oid
    finally:
        with engine.begin() as conn:
            conn.execute(delete(InstantOrder).where(InstantOrder.id == oid))


def _args(order_id):
    with Session(engine) as s:
        row = s.get(InstantOrder, order_id)
        return {"key": KEY, "order_id": order_id, "owner_id": row.passenger_id,
                "source": wp.capture_route_write(row), "requested": REQUESTED, "quote": QUOTE}


def _old_args(args, quote):
    view = SimpleNamespace(**dict(zip(wp._STATE_FIELDS, args["source"], strict=True)))
    paid = wp._project(view, quote)
    return {"key": KEY, "order_id": args["order_id"], "owner_id": args["owner_id"],
            "source": args["source"], "route": ROUTE,
            "terms": QuotedTerms(quote["price"] * 100, quote["old_price"] * 100,
                                 promo_ride.payable_kop(paid), promo_ride.payable_kop(view))}


@pytest.mark.parametrize("key, value", [("base_price", 131), ("distance_km", 7.6), ("eta_min", 11.6)])
def test_same_public_terms_are_not_same_persisted_write(order_id, key, value):
    args = _args(order_id)
    now = utcnow()
    ends = now + timedelta(seconds=43)
    divergent = QUOTE | {key: value}
    old = bind_quoted_terms(**_old_args(args, QUOTE), issued_at=now, valid_until=ends)
    assert _old_args(args, divergent)["terms"] == _old_args(args, QUOTE)["terms"]
    assert matches_current_terms(agreement=old, **_old_args(args, divergent), now=now)

    exact = bind_waypoint_write(**args, issued_at=now, valid_until=ends)
    assert match_waypoint_write(agreement=exact, **args, now=now) is not None
    assert match_waypoint_write(agreement=exact, **(args | {"quote": divergent}), now=now) is None

    # Real existing guarded route writer in disposable test SQLite, neither
    # an endpoint nor linked to the v1 agreement. Show that the unbound
    # field actually reaches SQL, rather than being a spare quote key.
    with Session(engine) as s:
        row = s.get(InstantOrder, order_id)
        wp.apply_guarded_route(s, row, args["source"], divergent,
                               points=[STOP | {"done": False}])
    fields = {"base_price": "ride_base_price", "distance_km": "distance_km", "eta_min": "eta_min"}
    with engine.connect() as conn:
        assert conn.scalar(select(getattr(InstantOrder, fields[key])).where(InstantOrder.id == order_id)) == value


def test_exact_projection_matches_real_guarded_update_and_excludes_lifecycle(order_id):
    args = _args(order_id)
    now = utcnow()
    a = bind_waypoint_write(**args, issued_at=now, valid_until=now + timedelta(seconds=33))
    p = match_waypoint_write(agreement=a, **args, now=now)
    assert p is not None
    assert "SYNTHETIC" not in repr(p) + repr(a)
    assert a.binding.hex() not in repr(a)
    with pytest.raises(FrozenInstanceError):
        p.ride_base_price = 7
    assert match_waypoint_write(agreement=a, **args, now=a.valid_until) is None
    assert _args(order_id)["source"] == args["source"]  # both bind and match are inert

    w = asdict(p)
    assert set(w) == {"waypoints_json", "ride_price", "ride_base_price", "price_estimate",
                     "promo_discount_kop", "distance_km", "eta_min", "destination_ack_at"}
    assert w["ride_price"] == 160 and w["price_estimate"] == 200
    assert w["promo_discount_kop"] == promo_ride.cap_for_price(8000, 160)
    with Session(engine) as s:
        row = s.get(InstantOrder, order_id)
        wp.apply_guarded_route(s, row, args["source"], QUOTE, points=[STOP | {"done": False}])
    with Session(engine) as s:
        row = s.get(InstantOrder, order_id)
        assert {name: getattr(row, name) for name in w} == w
        assert row.tariff_id is None and row.destination_changed_at is not None
        assert row.status == S.accepted and row.paid is False and row.price_final is None


def test_legacy_version_and_rewrapped_legacy_digest_never_match(order_id):
    args = _args(order_id)
    now = utcnow()
    ends = now + timedelta(seconds=42)
    v1 = bind_quoted_terms(**_old_args(args, QUOTE), issued_at=now, valid_until=ends)
    assert match_waypoint_write(agreement=v1, **args, now=now) is None
    assert match_waypoint_write(agreement=WaypointWriteAgreement(now, ends, v1.binding), **args, now=now) is None


@pytest.mark.parametrize("field, value", [
    ("base_price", True), ("base_price", 1 << 31), ("price", 1 << 31),
    ("old_price", 999), ("distance_km", float("inf")), ("distance_km", "7.5"),
    ("eta_min", float("nan")), ("eta_min", -1),
    ("needs_driver_ok", True), ("ok", False),
])
def test_invalid_or_ineligible_server_quote_cannot_be_bound(order_id, field, value):
    args = _args(order_id)
    now = utcnow()
    ends = now + timedelta(seconds=44)
    old = bind_waypoint_write(**args, issued_at=now, valid_until=ends)
    bad = args | {"quote": QUOTE | {field: value}}
    assert match_waypoint_write(agreement=old, **bad, now=now) is None
    with pytest.raises((ValueError, HTTPException)):
        bind_waypoint_write(**bad, issued_at=now, valid_until=ends)


@pytest.mark.parametrize("changes", [
    {"status": S.onboard}, {"status": S.offered}, {"driver_id": None},
    {"pending_to_lat": 54.8}, {"price_final": 280}, {"paid": True}, {"round_trip": True},
    {"from_text": "changed"},
])
def test_current_authority_and_preboarding_only(order_id, changes):
    args = _args(order_id)
    now = utcnow()
    a = bind_waypoint_write(**args, issued_at=now, valid_until=now + timedelta(seconds=39))
    src = dict(zip(wp._STATE_FIELDS, args["source"], strict=True)) | changes
    assert match_waypoint_write(agreement=a, **(args | {"source": tuple(src.values())}), now=now) is None
    assert match_waypoint_write(agreement=a, **(args | {"requested": [STOP | {"text": "different"}]}), now=now) is None
    assert match_waypoint_write(agreement=a, **(args | {"owner_id": args["owner_id"] + 1}), now=now) is None
    assert match_waypoint_write(agreement=replace(a, valid_until=now + timedelta(seconds=41)),
                                **args, now=now) is None


def test_passed_stops_remain_exact_and_noop_is_not_a_projection(order_id):
    args = _args(order_id)
    now = utcnow()
    ends = now + timedelta(seconds=31)
    model = dict(zip(wp._STATE_FIELDS, args["source"], strict=True))
    done = STOP | {"text": "previous", "done": True}
    model.update(waypoints_json=isv.dump_waypoints([done]))
    args["source"] = tuple(model.values())
    a = bind_waypoint_write(**args, issued_at=now, valid_until=ends)
    p = match_waypoint_write(agreement=a, **args, now=now)
    assert isv.parse_waypoints(p.waypoints_json) == [done, STOP | {"done": False}]
    assert match_waypoint_write(agreement=a, **(args | {"requested": []}), now=now) is None
    with pytest.raises(HTTPException):
        bind_waypoint_write(**(args | {"requested": []}), issued_at=now, valid_until=ends)


def test_arriving_preparation_never_calls_routing_or_live_position(order_id, monkeypatch):
    def unexpected(*args, **kwargs):
        pytest.fail("prepared quote matching crossed the external I/O boundary")

    monkeypatch.setattr(isv, "destination_quote", unexpected)
    monkeypatch.setattr(isv, "livepos_get", unexpected)
    args = _args(order_id)
    model = dict(zip(wp._STATE_FIELDS, args["source"], strict=True)) | {"status": S.arriving}
    args["source"] = tuple(model.values())
    now = utcnow()
    a = bind_waypoint_write(**args, issued_at=now, valid_until=now + timedelta(seconds=31))
    assert match_waypoint_write(agreement=a, **args, now=now) is not None
