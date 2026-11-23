"""Unwired comparison of already quoted route terms. No quote/receipt authority.

Only a future authenticated, durable store and same-transaction write fence
can authorize, consume or replay a confirmation. An absent agreement or a no
match is never a statement about payment, route mutation, or an earlier attempt.
"""
from dataclasses import dataclass, fields
from datetime import datetime
import hashlib
import hmac
import json
import math
import sys
from types import SimpleNamespace

from fastapi import HTTPException

from . import instant_service as isv, promo_ride, taxi_waypoint_consent as waypoint
from .models import InstantOrderStatus
from .taxi_waypoint_consent import _STATE_FIELDS

_PURPOSE = b"taxi-route-agreement-v1\x00"
_MAX_KOP = (1 << 63) - 1


@dataclass(frozen=True, slots=True, repr=False)
class QuotedTerms:
    ride_price_kop: int
    previous_ride_price_kop: int
    payable_estimate_kop: int
    previous_payable_estimate_kop: int


@dataclass(frozen=True, slots=True, repr=False)
class RouteAgreement:
    issued_at: datetime
    valid_until: datetime
    binding: bytes


def _integer(value) -> bool:
    return type(value) is int and 0 <= value <= _MAX_KOP


def _stamp(value: datetime) -> str:
    if type(value) is not datetime or value.tzinfo is not None:
        raise ValueError("Agreement clock must be naive UTC")
    return value.isoformat(timespec="microseconds") + "Z"


def _source_value(value):
    if value is None or type(value) is bool or type(value) is int or isinstance(value, str):
        return value
    if type(value) is float and math.isfinite(value):
        return value
    if type(value) is datetime:
        return {"utc": _stamp(value)}
    raise ValueError("Unsupported route source value")


def _point(value, *, stop: bool):
    if type(value) is not tuple or len(value) != (4 if stop else 3):
        raise ValueError("Malformed route point")
    lat, lng, label = value[:3]
    if (type(lat) not in (int, float) or not -90 <= lat <= 90
            or type(lng) not in (int, float) or not -180 <= lng <= 180
            or type(label) is not str or len(label) > 200
            or stop and type(value[3]) is not bool):
        raise ValueError("Invalid route point fields")
    # Point JSON coordinates have one normalized numerical representation.
    coordinates = (float(lat) or 0.0, float(lng) or 0.0, label)
    return (*coordinates, value[3]) if stop else coordinates


def _binding(key: bytes, *, owner_id: int, order_id: int, source: tuple, route: tuple,
             terms: QuotedTerms, issued_at: datetime, valid_until: datetime) -> bytes:
    if type(key) is not bytes or len(key) < 16:
        raise ValueError("Agreement requires an existing service key")
    if not _integer(owner_id) or not _integer(order_id) or not owner_id or not order_id:
        raise ValueError("Invalid route agreement identity")
    # The exact existing TF04 repricing snapshot schema; never trust values from
    # the client. Both the SQL order/passenger and the authenticated IDs must agree.
    if (type(source) is not tuple or len(source) != len(_STATE_FIELDS)
            or type(source[0]) is not int or type(source[1]) is not int
            or source[:2] != (order_id, owner_id)):
        raise ValueError("Route source identity or shape mismatch")
    if type(route) is not tuple or len(route) != 3 or type(route[1]) is not tuple:
        raise ValueError("Expected ordered origin, stops and destination")
    if type(terms) is not QuotedTerms:
        raise ValueError("Expected quoted server terms")
    amounts = (terms.ride_price_kop, terms.previous_ride_price_kop,
               terms.payable_estimate_kop, terms.previous_payable_estimate_kop)
    if not all(map(_integer, amounts)):
        raise ValueError("Quote amounts must be nonnegative integer kopecks")
    issued, expires = _stamp(issued_at), _stamp(valid_until)
    if issued_at >= valid_until:
        raise ValueError("Invalid quote validity interval")
    candidate = (_point(route[0], stop=False),
                 tuple(_point(w, stop=True) for w in route[1]),
                 _point(route[2], stop=False))
    encoded = json.dumps(
        (owner_id, order_id, tuple(zip(_STATE_FIELDS, map(_source_value, source), strict=True)),
         candidate, amounts, issued, expires),
        ensure_ascii=True, allow_nan=False, separators=(",", ":"),
    ).encode("ascii")
    return hmac.digest(key, _PURPOSE + encoded, hashlib.sha256)


def bind_quoted_terms(key: bytes, *, owner_id: int, order_id: int, source: tuple, route: tuple,
                      terms: QuotedTerms, issued_at: datetime, valid_until: datetime) -> RouteAgreement:
    """Represent a trusted quote; no persistence or authority to apply it."""
    binding = _binding(key, owner_id=owner_id, order_id=order_id, source=source,
                       route=route, terms=terms, issued_at=issued_at, valid_until=valid_until)
    return RouteAgreement(issued_at, valid_until, binding)


def matches_current_terms(key: bytes, agreement: RouteAgreement | None, *, owner_id: int, order_id: int,
                          source: tuple, route: tuple, terms: QuotedTerms, now: datetime) -> bool:
    """Compare only; does not consume, reissue, extend, or classify a receipt."""
    if (type(agreement) is not RouteAgreement or type(agreement.binding) is not bytes
            or len(agreement.binding) != hashlib.sha256().digest_size):
        return False
    try:
        _stamp(now)
        _stamp(agreement.issued_at)
        _stamp(agreement.valid_until)
        if not agreement.issued_at <= now < agreement.valid_until:
            return False
        expected = _binding(key, owner_id=owner_id, order_id=order_id, source=source, route=route,
                            terms=terms, issued_at=agreement.issued_at, valid_until=agreement.valid_until)
    except (ValueError, TypeError):
        return False
    return hmac.compare_digest(agreement.binding, expected)


# Internal-only write agreement, not a new public preview/confirmation version.
# Wrapping the private v1 digest preserves its identity, route, terms and times.
_WAYPOINT_WRITE_PURPOSE = b"taxi-waypoint-write-intent-v2\x00"
_SQL_INTEGER_MAX = (1 << 31) - 1


@dataclass(frozen=True, slots=True, repr=False)
class WaypointWriteAgreement:
    issued_at: datetime
    valid_until: datetime
    binding: bytes


@dataclass(frozen=True, slots=True, repr=False)
class WaypointWriteProjection:
    """Exact current waypoint UPDATE values except its fresh route-write stamp."""

    waypoints_json: str
    ride_price: int
    ride_base_price: int
    price_estimate: int
    promo_discount_kop: int
    distance_km: float
    eta_min: float
    destination_ack_at: None


def _sql_metric(value) -> float:
    if type(value) not in (int, float) or not 0 <= value <= sys.float_info.max:
        raise ValueError("Invalid waypoint route metric")
    return float(value) or 0.0


def _waypoint_write(key, owner_id, order_id, source, requested, quote, issued_at, valid_until, now):
    row = SimpleNamespace(**dict(zip(_STATE_FIELDS, source, strict=True)))
    # Onboard uses live driver position and time. Do not extend this inert
    # pre-boarding proof to onboard, offered or pending driver changes.
    if row.status not in (InstantOrderStatus.accepted, InstantOrderStatus.arriving):
        raise ValueError("Unsupported waypoint phase")
    waypoint._eligible(row, now)
    if type(requested) is not list or len(requested) > 3:
        raise ValueError("Invalid remaining waypoints")
    clean = []
    for point in requested:
        if type(point) is not dict or set(point) != {"lat", "lng", "text"}:
            raise ValueError("Invalid remaining waypoint")
        lat, lng, label = _point((point["lat"], point["lng"], point["text"]), stop=False)
        clean.append({"lat": lat, "lng": lng, "text": label})
    points = waypoint._points(row.waypoints_json, clean)
    route = ((row.from_lat, row.from_lng, row.from_text),
             tuple((p["lat"], p["lng"], p["text"], p["done"]) for p in points),
             (row.to_lat, row.to_lng, row.to_text))
    if (type(quote) is not dict or quote.get("ok") is not True
            or quote.get("needs_driver_ok") is not False):
        raise ValueError("Unsupported server waypoint quote")
    quote = waypoint._checked_quote(row, quote)
    if quote["old_price"] != isv.order_ride_price(row):
        raise ValueError("Waypoint quote has incorrect starting fare")
    if not _integer(row.promo_discount_kop):
        raise ValueError("Invalid current waypoint promo")
    priced = waypoint._project(row, quote)
    if not all(type(n) is int and 0 <= n <= _SQL_INTEGER_MAX for n in
               (priced.ride_price, priced.ride_base_price, priced.price_estimate)):
        raise ValueError("Waypoint fare exceeds route column bounds")
    if not _integer(priced.promo_discount_kop):
        raise ValueError("Waypoint promo exceeds route column bounds")
    terms = QuotedTerms(quote["price"] * 100, quote["old_price"] * 100,
                        promo_ride.payable_kop(priced), promo_ride.payable_kop(row))
    projection = WaypointWriteProjection(
        isv.dump_waypoints(points), priced.ride_price, priced.ride_base_price,
        priced.price_estimate, priced.promo_discount_kop,
        _sql_metric(quote.get("distance_km")), _sql_metric(quote.get("eta_min")), None,
    )
    # No arbitrary change dict is accepted. Existing waypoint UPDATE keeps the
    # destination/tariff and merely resets ack. Its monotonically fresh
    # destination_changed_at must be generated and fenced by the future writer.
    # For SQL FLOAT values bind exact normalized binary precision, not a rounded
    # display string. This does not make the stored duration a live ETA.
    values = ((f.name, getattr(projection, f.name)) for f in fields(projection))
    payload = json.dumps([(name, value.hex() if type(value) is float else value) for name, value in values],
                         ensure_ascii=True, allow_nan=False, separators=(",", ":")).encode("ascii")
    public_binding = _binding(key, owner_id=owner_id, order_id=order_id, source=source,
                              route=route, terms=terms, issued_at=issued_at, valid_until=valid_until)
    return projection, hmac.digest(key, _WAYPOINT_WRITE_PURPOSE + public_binding + payload, hashlib.sha256)


def bind_waypoint_write(key: bytes, *, owner_id: int, order_id: int, source: tuple,
                        requested: list[dict], quote: dict, issued_at: datetime,
                        valid_until: datetime) -> WaypointWriteAgreement:
    """Bind vetted server inputs. No client authority, command admission, or writes."""
    _, binding = _waypoint_write(key, owner_id, order_id, source, requested, quote,
                                 issued_at, valid_until, issued_at)
    return WaypointWriteAgreement(issued_at, valid_until, binding)


def match_waypoint_write(key: bytes, agreement: WaypointWriteAgreement | None, *,
                         owner_id: int, order_id: int, source: tuple, requested: list[dict],
                         quote: dict, now: datetime) -> WaypointWriteProjection | None:
    """Compare and return the exact immutable SQL projection, not authorization."""
    if (type(agreement) is not WaypointWriteAgreement or type(agreement.binding) is not bytes
            or len(agreement.binding) != hashlib.sha256().digest_size):
        return None
    try:
        _stamp(now)
        _stamp(agreement.issued_at)
        _stamp(agreement.valid_until)
        if not agreement.issued_at <= now < agreement.valid_until:
            return None
        projection, binding = _waypoint_write(key, owner_id, order_id, source, requested, quote,
                                              agreement.issued_at, agreement.valid_until, now)
    except (HTTPException, KeyError, AttributeError, TypeError, ValueError, OverflowError):
        return None
    return projection if hmac.compare_digest(agreement.binding, binding) else None
