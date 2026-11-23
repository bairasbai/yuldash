"""Opaque agreement for an OFFERED waypoint observation only, not an admission or receipt."""
from dataclasses import dataclass
from datetime import datetime
import hashlib
import hmac
import json
from types import SimpleNamespace

from fastapi import HTTPException

from . import instant_service as isv, promo_ride, taxi_offered_waypoint_preview as offered
from .taxi_route_agreement import QuotedTerms, _integer, _source_value, _sql_metric, _stamp

_PURPOSE = b"taxi-offered-waypoint-preview-agreement-v1\x00"
_COLUMNS = (*offered._FIELDS, *(f"tariff.{name}" for name in isv._DESTINATION_QUOTE_TARIFF_FIELDS))


@dataclass(frozen=True, slots=True, repr=False)
class OfferedWaypointAgreement:
    observed_at: datetime
    valid_until: datetime
    binding: bytes


def _material(key: bytes, owner_id: int, order_id: int, source: tuple,
              preview: offered.OfferedWaypointPreview, now: datetime) -> bytes:
    if (type(key) is not bytes or len(key) < 16 or not offered._id(owner_id) or not offered._id(order_id)
            or type(preview) is not offered.OfferedWaypointPreview
            or type(source) is not tuple or len(source) != len(_COLUMNS)
            or type(preview._source) is not tuple or len(preview._source) != len(_COLUMNS)
            or type(source[0]) is not int or type(source[1]) is not int
            or source[:2] != (order_id, owner_id) or not all(offered._utc(v) for v in (
                preview.observed_at, preview.valid_until, now,
            ))):
        raise ValueError("Invalid offered waypoint agreement")

    # Form a canonical strictly typed scalar snapshot for comparison. The
    # duplicate id/category/zone names in tariff are domain-prefixed.
    observed = tuple(map(_source_value, preview._source))
    current = tuple(map(_source_value, source))
    # JSON prevents Python integer/bool equality from aliasing signed input.
    enc = dict(ensure_ascii=True, allow_nan=False, separators=(",", ":"))
    if json.dumps(observed, **enc) != json.dumps(current, **enc):
        raise ValueError("Offered waypoint source changed")
    row = SimpleNamespace(**dict(zip(offered._FIELDS, source[:len(offered._FIELDS)], strict=True)))
    if (not offered._valid_offer(row, preview.observed_at) or not offered._valid_fare(row)
            or not preview.observed_at <= now < preview.valid_until <= row.offer_expires_at):
        raise ValueError("Offered waypoint agreement window or scope changed")
    tariff_id, active, category, zone, *_ = source[len(offered._FIELDS):]
    if (tariff_id != row.tariff_id or active is not True or category != row.category
            or zone not in ("city", "intercity")):
        raise ValueError("Offered waypoint tariff changed")

    materialized = isv.parse_waypoints(preview._waypoints_json)
    requested = [{k: p[k] for k in ("lat", "lng", "text")} for p in materialized]
    if isv.dump_waypoints(offered._candidate(row, requested)) != preview._waypoints_json:
        raise ValueError("Invalid offered waypoint write projection")

    t = preview.terms
    if (type(t) is not QuotedTerms or not all(map(_integer, (
            t.ride_price_kop, t.previous_ride_price_kop, t.payable_estimate_kop, t.previous_payable_estimate_kop,
        ))) or t.ride_price_kop % 100
            or not _integer(preview.promo_discount_kop)):
        raise ValueError("Invalid offered waypoint amounts")
    ride_rub = t.ride_price_kop // 100
    gross = preview._price_estimate
    promo_kop = preview.promo_discount_kop
    if (not all(type(v) is int and 0 <= v <= offered._INT_MAX for v in (
            ride_rub, gross, preview.ride_base_price,
        ))
            or t.previous_ride_price_kop != row.ride_price * 100
            or t.previous_payable_estimate_kop != promo_ride.payable_kop(row)
            or gross != ride_rub + isv.order_compensation_rub(row)
            or promo_kop > min(row.promo_discount_kop, t.ride_price_kop)
            or t.payable_estimate_kop != gross * 100 - promo_kop):
        raise ValueError("Inconsistent offered waypoint amounts")

    # No route-metric or tariff computation. Bind the values already
    # projected by the canonical quote, including all hidden write fields.
    # ACK resets on a future changed route; no timestamp/status/marker here.
    projection = (
        ("waypoints_json", preview._waypoints_json), ("ride_price", ride_rub),
        ("ride_base_price", preview.ride_base_price), ("price_estimate", gross),
        ("promo_discount_kop", promo_kop), ("distance_km", _sql_metric(preview.distance_km).hex()),
        ("eta_min", _sql_metric(preview.eta_min).hex()), ("destination_ack_at", None),
    )
    payload = json.dumps(
        (owner_id, order_id, tuple(zip(_COLUMNS, current, strict=True)), projection,
         (t.ride_price_kop, t.previous_ride_price_kop, t.payable_estimate_kop, t.previous_payable_estimate_kop),
         _stamp(preview.observed_at), _stamp(preview.valid_until)),
        **enc,
    ).encode("ascii")
    return hmac.digest(key, _PURPOSE + payload, hashlib.sha256)


def bind_offered_waypoints(
    key: bytes, *, owner_id: int, order_id: int, preview: offered.OfferedWaypointPreview,
) -> OfferedWaypointAgreement:
    """Bind an existing vetted server preview; do not issue an attempt or extend its clock."""
    if type(preview) is not offered.OfferedWaypointPreview:
        raise ValueError("Expected an offered waypoint preview")
    return OfferedWaypointAgreement(
        preview.observed_at, preview.valid_until,
        _material(key, owner_id, order_id, preview._source, preview, preview.observed_at),
    )


def matches_offered_waypoints(
    key: bytes, agreement: OfferedWaypointAgreement | None, *, owner_id: int, order_id: int,
    preview: offered.OfferedWaypointPreview, current: tuple, now: datetime,
) -> bool:
    """Compare independent current observation against the original private preview only."""
    if (type(agreement) is not OfferedWaypointAgreement or type(preview) is not offered.OfferedWaypointPreview
            or type(agreement.binding) is not bytes or len(agreement.binding) != hashlib.sha256().digest_size
            or agreement.observed_at != preview.observed_at or agreement.valid_until != preview.valid_until):
        return False
    try:
        expected = _material(key, owner_id, order_id, current, preview, now)
    except (AttributeError, HTTPException, TypeError, ValueError, OverflowError):
        return False
    return hmac.compare_digest(agreement.binding, expected)
