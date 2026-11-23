"""Unwired comparison of already quoted route terms. No quote/receipt authority.

Only a future authenticated, durable store and same-transaction write fence
can authorize, consume or replay a confirmation. An absent agreement or a no
match is never a statement about payment, route mutation, or an earlier attempt.
"""
from dataclasses import dataclass
from datetime import datetime
import hashlib
import hmac
import json
import math

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
