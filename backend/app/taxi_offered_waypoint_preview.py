"""Uncalled estimate for a single live, unassigned ordinary offer. No authority to edit."""
from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime
import math
from types import SimpleNamespace

from fastapi import HTTPException
from sqlalchemy.engine import Engine
from sqlmodel import Session, select

from . import instant_service as isv, promo_ride, taxi_waypoint_consent as waypoint
from .compensation import COMPENSATION_FIELDS
from .models import InstantOrder, InstantOrderStatus as S, Tariff
from .taxi_route_agreement import QuotedTerms, _point, _sql_metric
from .timeutil import utcnow

_FIELDS = (*waypoint._STATE_FIELDS,
           "scheduled_at", "wait_until", "retry_count", "expired_at", "cancelled_at", "done_at",
           "options", "women_only", "pickup_km", "pickup_enroute", "weather_kind",
           "destination_ack_at", "no_show", "cancel_fee_kop",
           "route_resume_id", "route_resume_previous_driver_id", "route_resume_retired_at",
           "route_resume_deadline_at", "route_resume_ready_at", "route_resume_claim_id")
_COLUMNS = tuple(getattr(InstantOrder, name) for name in _FIELDS)
_TARIFF_COLUMNS = tuple(getattr(Tariff, name) for name in isv._DESTINATION_QUOTE_TARIFF_FIELDS)
_INT_MAX = (1 << 31) - 1
_NONE = (
    "driver_id", "accepted_at", "arriving_at", "onboard_at", "scheduled_at", "wait_until",
    "expired_at", "cancelled_at", "done_at", "pending_to_lat", "pending_to_lng",
    "pending_asked_at", "pending_price", "price_final", "waiting_started_at", "stop_started_at",
    "route_resume_id", "route_resume_previous_driver_id", "route_resume_retired_at",
    "route_resume_deadline_at", "route_resume_ready_at", "route_resume_claim_id",
)
_FALSE = ("round_trip", "pickup_pending", "paid", "no_show")
_ZERO = ("return_wait_min", "retry_count", "reassigns", "waiting_fee_kop", "cancel_fee_kop")


@dataclass(frozen=True, slots=True, repr=False)
class OfferedWaypointPreview:
    """Internal only. No client token, receipt, authorization or edit."""
    _source: tuple
    _waypoints_json: str
    terms: QuotedTerms
    ride_base_price: int
    promo_discount_kop: int
    distance_km: float
    eta_min: float
    observed_at: datetime
    valid_until: datetime


def _utc(value) -> bool:
    return type(value) is datetime and value.tzinfo is None


def _id(value) -> bool:
    return type(value) is int and 0 < value <= _INT_MAX


def _read(engine: Engine, owner_id: int, order_id: int):
    # Never invoke the user-facing order/offer getters: they can reconcile
    # expired offers and dispatch. Separate physical transactions per read.
    with Session(engine, autoflush=False) as session:
        row = session.exec(select(*_COLUMNS, *_TARIFF_COLUMNS).select_from(InstantOrder).outerjoin(
            Tariff, Tariff.id == InstantOrder.tariff_id,
        ).where(InstantOrder.id == order_id, InstantOrder.passenger_id == owner_id)).one_or_none()
        return tuple(row) if row is not None else None


def _valid_offer(order, now: datetime) -> bool:
    timestamps = order.created_at, order.searching_at, order.offered_at, order.offer_expires_at
    return (order.status == S.offered and _id(order.current_offer_driver_id)
            and order.current_offer_driver_id != order.passenger_id
            and _id(order.search_round) and _id(order.tariff_id)
            and all(map(_utc, timestamps))
            and order.created_at <= order.searching_at <= order.offered_at <= now < order.offer_expires_at
            and all(getattr(order, f) is None for f in _NONE)
            and all(getattr(order, f) is False for f in _FALSE)
            and all(type(getattr(order, f)) is int and getattr(order, f) == 0 for f in _ZERO)
            and order.pending_to_text == order.pending_reason == order.fallback_categories == ""
            and isv.can_change_destination(order, now=now) is None)


def _valid_fare(order) -> bool:
    return (_id(order.ride_price)
            and all(type(v) is int and 0 <= v <= _INT_MAX for v in
                    (order.ride_base_price, order.price_estimate))
            and all(type(getattr(order, f)) is int and 0 <= getattr(order, f) < (1 << 63)
                    for f in (*COMPENSATION_FIELDS, "promo_discount_kop"))
            and order.price_estimate == order.ride_price + isv.order_compensation_rub(order))


def _candidate(order, requested: list[dict]):
    _point((order.from_lat, order.from_lng, order.from_text), stop=False)
    _point((order.to_lat, order.to_lng, order.to_text), stop=False)
    if type(requested) is not list or len(requested) > 3:
        raise ValueError("Expected up to three intermediate stops")
    clean = []
    for point in requested:
        if type(point) is not dict or set(point) != {"lat", "lng", "text"}:
            raise ValueError("Expected only a waypoint")
        lat, lng, label = _point((point["lat"], point["lng"], point["text"]), stop=False)
        clean.append({"lat": lat, "lng": lng, "text": label})
    points = waypoint._points(order.waypoints_json, clean)
    before = isv.parse_waypoints(order.waypoints_json)
    if (any(stop["done"] for stop in before)
            or [(w["lat"], w["lng"]) for w in points] == [(w["lat"], w["lng"]) for w in before]):
        raise ValueError("Expected changed unpassed intermediate stops")
    return points


def preview_offered_waypoints(
    engine: Engine, *, owner_id: int, order_id: int, requested: list[dict],
    quote_valid_until: datetime, guard_budget: Callable[[int], None],
) -> OfferedWaypointPreview | None:
    """Calculate a private candidate using vetted server clock/quote policy.

    Caller must authenticate owner_id and supply the existing estimate budget
    plus its trusted quote validity ceiling; neither is established here.
    Missing/stale/unsupported returns no candidate. A result is not a promise
    of future price, assignment or mutation. No order/receipt/offer writes.
    """
    if not _id(owner_id) or not _id(order_id) or not _utc(quote_valid_until):
        return None
    captured = _read(engine, owner_id, order_id)
    if captured is None:
        return None
    order = SimpleNamespace(**dict(zip(_FIELDS, captured[:len(_FIELDS)], strict=True)))
    tariff = captured[len(_FIELDS):]
    tariff_id, active, category, zone, *rates = tariff
    now = utcnow()
    try:
        if (not _valid_offer(order, now) or not _valid_fare(order)
                or tariff_id != order.tariff_id or active is not True or category != order.category
                or zone not in ("city", "intercity")
                or not all(type(n) in (float, int) and math.isfinite(n) and n >= 0 for n in rates)):
            return None
        valid_until = min(quote_valid_until, order.offer_expires_at)
        if now >= valid_until:
            return None
        points = _candidate(order, requested)
    except (HTTPException, TypeError, ValueError, OverflowError):
        return None
    # Out-of-scope/other-owner requests do not consume routing budget.
    # Keep budget errors visible, and never route before this guard.
    guard_budget(owner_id)
    if _read(engine, owner_id, order_id) != captured:
        return None
    price_at = utcnow()
    if not now <= price_at < valid_until:
        return None

    try:
        with Session(engine, autoflush=False) as session:
            quote = isv.destination_quote(session, order, (order.to_lat, order.to_lng),
                                          now=price_at, waypoints_override=points, expected_tariff=tariff)
        # The canonical quote may pick a new zone tariff or a category fallback.
        # A successful quote in either case is not this narrow offer's price.
        if quote.get("tariff_id") != tariff_id or quote.get("zone") != zone:
            return None
        if _read(engine, owner_id, order_id) != captured:
            return None
        observed_at = utcnow()
        if not price_at <= observed_at < valid_until:
            return None
        quote = waypoint._checked_quote(order, quote)
        p = waypoint._project(order, quote)
        if not all(0 <= x <= _INT_MAX for x in (p.ride_price, p.ride_base_price, p.price_estimate)):
            return None
        terms = QuotedTerms(p.ride_price * 100, quote["old_price"] * 100,
                            promo_ride.payable_kop(p), promo_ride.payable_kop(order))
        return OfferedWaypointPreview(
            captured, isv.dump_waypoints(points), terms, p.ride_base_price, p.promo_discount_kop,
            _sql_metric(quote.get("distance_km")), _sql_metric(quote.get("eta_min")), observed_at, valid_until,
        )
    except (HTTPException, TypeError, ValueError, OverflowError):
        return None
