"""Opaque exact binding atop a real canonical preview, never mutate or reroute."""
from dataclasses import FrozenInstanceError, asdict, replace
from datetime import timedelta, timezone

import pytest
from sqlalchemy import event

from app import instant_service as isv, pricing, taxi_offered_waypoint_preview as offered
from app.models import InstantOrderStatus as S
from app.taxi_offered_waypoint_agreement import (
    OfferedWaypointAgreement, bind_offered_waypoints, matches_offered_waypoints,
)
from app.taxi_route_agreement import WaypointWriteAgreement, bind_waypoint_write, match_waypoint_write
from app.taxi_waypoint_consent import _STATE_FIELDS
from test_taxi_offered_waypoint_preview import (
    _preview, _row, _STOP, _unexpected, route, testdb,  # noqa: F401 - direct fixture import
)

_KEY = b"\x26" * 32


@pytest.fixture
def prepared(testdb, route):  # noqa: F811 - pytest fixture injection
    db, owner, oid, *_ = testdb
    p = _preview(testdb)
    assert p is not None and len(route) == 2
    return db, {"key": _KEY, "owner_id": owner, "order_id": oid, "preview": p}


def _match(args, agreement, **changes):
    p = args["preview"]
    info = args | {"current": p._source, "now": p.observed_at} | changes
    return matches_offered_waypoints(agreement=agreement, **info)


def test_exact_and_private_no_database_or_provider_effect(prepared, monkeypatch):
    db, args = prepared
    p = args["preview"]
    fresh = offered._read(db, args["owner_id"], args["order_id"])
    assert fresh == p._source
    before = _row(db, args["order_id"])
    for mod, name in ((pricing, "route_metrics"), (isv, "destination_quote"),
                      (isv, "livepos_get"), (offered, "_read")):
        monkeypatch.setattr(mod, name, _unexpected)
    event.listen(db, "before_cursor_execute", _unexpected)
    try:
        a = bind_offered_waypoints(**args)
        assert _match(args, a, current=fresh)
        assert _match(args, a, current=fresh)  # not consumed, no admission
        assert set(asdict(a)) == {"observed_at", "valid_until", "binding"}
        assert type(a.binding) is bytes and len(a.binding) == 32
        assert "SYNTHETIC" not in repr(a) and a.binding.hex() not in repr(a)
        with pytest.raises(FrozenInstanceError):
            a.binding = b"\x1a" * 32
    finally:
        event.remove(db, "before_cursor_execute", _unexpected)
    assert _row(db, args["order_id"]) == before


@pytest.mark.parametrize("name, changed", [
    ("id", 999), ("passenger_id", 999), ("current_offer_driver_id", 999),
    ("search_round", 2), ("status", S.searching), ("driver_id", 999),
    ("to_lat", 54.9), ("to_text", "SYNTHETIC OTHER DESTINATION"),
    ("waypoints_json", "[]"), ("price_estimate", 299), ("promo_discount_kop", 1),
    ("paid", True), ("options", "pet"), ("route_resume_id", "other"),
    ("tariff.per_km", 20), ("tariff.category", "comfort"), ("tariff.active", False),
])
def test_each_current_offer_route_fare_or_tariff_change_rejects(prepared, name, changed):
    _, args = prepared
    p = args["preview"]
    # Use named columns with separate tariff namespace, like the exact binder.
    labels = (*offered._FIELDS, *(f"tariff.{n}" for n in isv._DESTINATION_QUOTE_TARIFF_FIELDS))
    cur = dict(zip(labels, p._source, strict=True))
    assert cur[name] != changed
    cur[name] = changed
    a = bind_offered_waypoints(**args)
    assert not _match(args, a, current=tuple(cur.values()))


@pytest.mark.parametrize("field", ("ride_base_price", "distance_km", "eta_min", "waypoints_json"))
def test_equal_shown_kopecks_do_not_authorize_changed_hidden_projection(prepared, field):
    _, args = prepared
    p = args["preview"]
    changes = {"ride_base_price": p.ride_base_price + 1, "distance_km": p.distance_km + .5,
               "eta_min": p.eta_min + .5,
               "waypoints_json": isv.dump_waypoints([_STOP | {"text": "different", "done": False}])}
    changed = replace(p, **{("_waypoints_json" if field == "waypoints_json" else field): changes[field]})
    original = bind_offered_waypoints(**args)
    assert changed.terms == p.terms
    assert not _match(args, original, preview=changed)
    assert bind_offered_waypoints(**(args | {"preview": changed})).binding != original.binding


def test_inconsistent_hidden_amount_fails_closed(prepared):
    _, args = prepared
    p = args["preview"]
    for bad in (replace(p, _price_estimate=p._price_estimate + 1),
                replace(p, promo_discount_kop=p.promo_discount_kop + 1),
                replace(p, terms=replace(p.terms, ride_price_kop=p.terms.ride_price_kop + 1)),
                replace(p, terms=replace(p.terms, payable_estimate_kop=True)),
                replace(p, distance_km=float("inf")),
                replace(p, _waypoints_json='[{"lat":"malformed"}]')):
        valid = bind_offered_waypoints(**args)
        assert not _match(args, valid, preview=bad)
        with pytest.raises((ValueError, offered.HTTPException)):
            bind_offered_waypoints(**(args | {"preview": bad}))


def test_lease_caps_times_malformed_and_absent_inputs(prepared):
    _, args = prepared
    p = args["preview"]
    a = bind_offered_waypoints(**args)
    assert a.valid_until == p._source[offered._FIELDS.index("offer_expires_at")]
    assert _match(args, a, now=a.valid_until - timedelta(microseconds=1))
    for now in (a.valid_until, a.valid_until + timedelta(seconds=120),
                a.observed_at - timedelta(microseconds=1), a.observed_at.replace(tzinfo=timezone.utc)):
        assert not _match(args, a, now=now)
    assert not _match(args, None)
    assert not _match(args, a, preview=None)
    assert not _match(args, a, current=None)
    assert not _match(args, a, owner_id=True)
    assert not _match(args, a, key=b"\x12" * 32)
    assert not _match(args, replace(a, binding=b"too short"))
    extend = p.valid_until + timedelta(microseconds=1)
    assert not _match(args, replace(a, valid_until=extend), preview=replace(p, valid_until=extend))
    with pytest.raises(ValueError):
        bind_offered_waypoints(**(args | {"preview": replace(p, valid_until=extend)}))
    with pytest.raises(ValueError):
        bind_offered_waypoints(**(args | {"preview": None}))


def test_assigned_v2_and_offered_opaque_bindings_do_not_cross_domains(prepared):
    _, args = prepared
    p = args["preview"]
    a = bind_offered_waypoints(**args)
    assigned = dict(zip(_STATE_FIELDS, p._source[:len(_STATE_FIELDS)], strict=True))
    assigned.update(status=S.accepted, driver_id=p._source[offered._FIELDS.index("current_offer_driver_id")])
    kwargs = dict(key=_KEY, owner_id=args["owner_id"], order_id=args["order_id"],
                  source=tuple(assigned.values()), requested=[_STOP],
                  quote=dict(ok=True, needs_driver_ok=False, price=p.terms.ride_price_kop // 100,
                             old_price=240, base_price=p.ride_base_price, distance_km=p.distance_km,
                             eta_min=p.eta_min))
    v2 = bind_waypoint_write(**kwargs, issued_at=p.observed_at, valid_until=p.valid_until)
    assert match_waypoint_write(agreement=v2, now=p.observed_at, **kwargs) is not None
    assert not _match(args, v2)
    assert not _match(args, OfferedWaypointAgreement(a.observed_at, a.valid_until, v2.binding))
    assert match_waypoint_write(
        agreement=WaypointWriteAgreement(a.observed_at, a.valid_until, a.binding),
        now=p.observed_at, **kwargs,
    ) is None
