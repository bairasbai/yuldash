"""Read-only passenger-authorized waypoint edit estimate. No confirmation is registered."""
import json
import math
from collections import Counter
from collections.abc import Callable
from datetime import timedelta
from types import SimpleNamespace

from sqlalchemy import update
from sqlmodel import Session, select

from . import instant_service as isv, promo_ride
from .compensation import COMPENSATION_FIELDS
from .errors import herr
from .models import InstantOrder, InstantOrderStatus as S
from .timeutil import utcnow

_ALLOWED = (S.accepted, S.arriving, S.onboard)
# Snapshot persisted inputs used by eligibility, repricing and the shown passenger
# estimate. Include lifecycle/assignment and route edit markers so a quote from an
# earlier phase or a pending driver request cannot be returned after an interleave.
_STATE_FIELDS = (
    "id", "passenger_id", "driver_id", "status", "created_at", "accepted_at",
    "arriving_at", "onboard_at", "reassigns", "search_round", "searching_at", "offered_at",
    "current_offer_driver_id", "offer_expires_at", "fallback_categories", "from_lat", "from_lng", "from_text",
    "to_lat", "to_lng", "to_text", "waypoints_json", "destination_changed_at",
    "destination_changes", "pending_to_lat", "pending_to_lng", "pending_to_text",
    "pending_asked_at", "pending_price", "pending_reason", "round_trip", "return_wait_min",
    "category", "tariff_id", "pricing_k", "surge_k", "driven_km", "distance_km", "eta_min",
    "ride_price", "ride_base_price", "price_estimate", "price_final", "pickup_pending",
    "promo_discount_kop", "waiting_started_at", "waiting_fee_kop", "stop_started_at",
    "paid", "payment_method", "payment_changed_at", *COMPENSATION_FIELDS,
)
_STATE_COLUMNS = tuple(getattr(InstantOrder, field) for field in _STATE_FIELDS)


def _unavailable():
    return herr(409, "Сейчас нельзя изменить остановки.", "Хәҙер туҡталыштарҙы үҙгәртеп булмай.",
                code="taxi_waypoints_unavailable")


def _driver_consent():
    return herr(409, "Для такого изменения нужно согласие водителя.",
                "Бындай үҙгәреш өсөн водитель ризалығы кәрәк.",
                code="taxi_waypoints_driver_consent_required")


def _conflict():
    return herr(409, "Заказ изменился. Проверь новую оценку.",
                "Заказ үҙгәрҙе. Яңы иҫәпте тикшер.",
                code="taxi_waypoints_quote_conflict")


def _read_state(session, owner_id, order_id):
    # Both reads are scalar SELECTs, not ORM identity-map hits. The normal
    # request session uses PostgreSQL READ COMMITTED or SQLite's legacy
    # transaction mode (no request writes on this path).
    result = session.exec(select(*_STATE_COLUMNS).where(
        InstantOrder.id == order_id, InstantOrder.passenger_id == owner_id,
    )).one_or_none()
    return tuple(result) if result is not None else None


def _eligible(order, now):
    # A one-way route is the only route handled by the existing repricer. An
    # address change waiting on the driver is not interchangeable with stops.
    if (order.status not in _ALLOWED or order.driver_id is None
            or order.round_trip or order.return_wait_min
            or order.pending_to_lat is not None or order.pending_to_lng is not None
            or order.pending_asked_at is not None or order.price_final is not None
            or order.paid):
        raise _unavailable()
    blocked = isv.can_change_destination(order, now=now)
    if blocked == "too_often":
        raise herr(429, "Подожди перед следующей правкой.", "Киләһе үҙгәреш алдынан көт.",
                   code="taxi_waypoints_unavailable")
    if blocked:
        raise _unavailable()


def _points(raw, requested):
    # Do not silently drop a corrupted persisted stop: the legacy parser
    # tolerates each malformed item for display, not for a protected rewrite.
    try:
        stored = json.loads(raw) if raw else []
        current = isv.parse_waypoints(raw)
        if (not isinstance(stored, list) or len(stored) != len(current)
                or any(not isinstance(p, dict) or type(p.get("done", False)) is not bool
                       for p in stored)):
            raise ValueError
        if any(not math.isfinite(p["lat"]) or not math.isfinite(p["lng"])
               or not -90 <= p["lat"] <= 90 or not -180 <= p["lng"] <= 180
               for p in current):
            raise ValueError
    except (TypeError, ValueError, AttributeError, OverflowError):
        raise _unavailable() from None

    remaining = [p for p in current if not p["done"]]
    requested = [{**p, "done": False} for p in requested]
    current_coords = [(p["lat"], p["lng"]) for p in remaining]
    requested_coords = [(p["lat"], p["lng"]) for p in requested]
    shared = Counter(current_coords) & Counter(requested_coords)

    def existing_order(coords):
        counts = shared.copy()
        result = []
        for point in coords:
            if counts[point]:
                result.append(point)
                counts[point] -= 1
        return result

    if existing_order(current_coords) != existing_order(requested_coords) or remaining == requested:
        raise _unavailable()
    return [p for p in current if p["done"]] + requested


def _checked_quote(order, result):
    if not result.get("ok"):
        raise herr(503, "Не удалось посчитать цену. Попробуй ещё раз.",
                   "Хаҡты иҫәпләп булманы. Яңынан һынап ҡара.")
    if result["needs_driver_ok"]:
        # No existing storage/workflow for a pending changed set of stops.
        # Never redirect to the unrelated pending-destination acceptance.
        raise _driver_consent()
    if any(type(result.get(k)) is not int or result[k] < 0 for k in ("price", "base_price", "old_price")):
        raise _unavailable()
    if not order.ride_price and isv.order_compensation_rub(order):
        # A legacy amount with compensation cannot be called a ride amount.
        raise _unavailable()
    return result


def _project(order, quote):
    # The pricing helpers compute on a transient value. The ORM order remains
    # unmodified, even when the estimated promo discount changes.
    p = SimpleNamespace(price_final=None, promo_discount_kop=order.promo_discount_kop,
                        **{name: getattr(order, name) for name in COMPENSATION_FIELDS})
    # Same price/compensation and promo functions as the existing edit + order payload.
    # Re-clamp the preview without touching SQL or the current discount.
    isv.set_ride_price(p, quote["price"], quote["base_price"])
    discountable = promo_ride.discountable_rub(p)
    p.promo_discount_kop = promo_ride.cap_for_price(p.promo_discount_kop, discountable)
    return p


def capture_route_write(order: InstantOrder) -> tuple:
    """The exact persisted source which the caller is about to price."""
    return tuple(getattr(order, name) for name in _STATE_FIELDS)


def _legacy_conflict():
    return herr(409, "Маршрут заказа изменился. Открой заказ заново.",
                "Заказ маршруты үҙгәрҙе. Заказды яңынан ас.",
                code="taxi_route_changed")


def _next_write_time(previous):
    now = utcnow()
    if previous is not None and now <= previous:
        # Advance even when two observations tick at the same clock sample.
        # Never wrap a terminal timestamp to an older route state.
        try:
            return previous + timedelta(microseconds=1)
        except OverflowError:
            raise _legacy_conflict() from None
    return now


def _write_route(session: Session, order: InstantOrder, captured: tuple, changes: dict):
    # SQL compares after route I/O. PostgreSQL READ COMMITTED rechecks the
    # predicate after a conflicting row lock; SQLite's writer does it atomically.
    # No intermediate committed fare/discount or ORM writes on the loser.
    statement = update(InstantOrder).where(*(
        column == old for column, old in zip(_STATE_COLUMNS, captured, strict=True)
    )).values(**changes).execution_options(synchronize_session=False)
    changed = session.execute(statement)
    if changed.rowcount != 1:
        raise _legacy_conflict()
    session.commit()
    session.refresh(order)
    return order


def _legacy_price_values(source, quote):
    projected = SimpleNamespace(**vars(source))
    isv.set_ride_price(projected, int(quote["price"]), quote.get("base_price"))
    changes = {
        "ride_price": projected.ride_price, "price_estimate": projected.price_estimate,
        "distance_km": float(quote["distance_km"]), "eta_min": float(quote["eta_min"]),
    }
    if quote.get("base_price") is not None:
        changes["ride_base_price"] = projected.ride_base_price
    discount = int(source.promo_discount_kop or 0)
    if discount > 0:
        capped = promo_ride.cap_for_price(discount, promo_ride.discountable_rub(projected))
        if capped < discount:
            changes["promo_discount_kop"] = capped
    return changes


def apply_guarded_route(session: Session, order: InstantOrder, captured: tuple, quote: dict, *,
                        points: list[dict] | None = None, destination: tuple | None = None,
                        text: str = "", driver_ack: bool = False):
    """Commit price, changed route and applicable discount together.

    This is NOT the protected post-price confirmation. The existing public
    callers retain their ordinary inputs and existing tariff/routing policy.
    """
    if (points is None) == (destination is None) or driver_ack and destination is None:
        raise ValueError("Expected exactly one route edit and a destination for driver acceptance")
    source = SimpleNamespace(**dict(zip(_STATE_FIELDS, captured, strict=True)))
    at = _next_write_time(source.destination_changed_at)
    changes = _legacy_price_values(source, quote)
    changes.update(destination_changed_at=at, destination_ack_at=at if driver_ack else None)
    if points is not None:
        changes["waypoints_json"] = isv.dump_waypoints(points)
    else:
        changes.update(to_lat=float(destination[0]), to_lng=float(destination[1]),
                       destination_changes=int(source.destination_changes or 0) + 1,
                       pending_to_lat=None, pending_to_lng=None, pending_to_text="",
                       pending_price=None, pending_asked_at=None, pending_reason="")
        if text:
            changes["to_text"] = text[:200]
        if quote.get("tariff_id"):
            changes["tariff_id"] = int(quote["tariff_id"])
    return _write_route(session, order, captured, changes)


def propose_guarded_destination(session: Session, order: InstantOrder, captured: tuple,
                                destination: tuple, text: str, quote: dict):
    """Do not make a driver consider an offer based on a superseded route."""
    source = dict(zip(_STATE_FIELDS, captured, strict=True))
    return _write_route(session, order, captured, {
        "pending_to_lat": float(destination[0]), "pending_to_lng": float(destination[1]),
        "pending_to_text": (text or "")[:200], "pending_price": int(quote["price"]),
        "pending_asked_at": _next_write_time(source["pending_asked_at"]),
        "pending_reason": quote.get("ask_reason") or "price",
    })


def preview(session: Session, owner_id: int, order_id: int, requested: list[dict],
            *, guard_budget: Callable[[int], None]) -> dict:
    captured = _read_state(session, owner_id, order_id)
    if captured is None:
        raise herr(404, "Заказ не найден", "Заказ табылманы")
    order = SimpleNamespace(**dict(zip(_STATE_FIELDS, captured, strict=True)))
    now = utcnow()
    _eligible(order, now)
    points = _points(order.waypoints_json, requested)
    # Use the same per-user estimate budget as /instant/estimate, after ownership
    # and local validation so unrelated requests do not spend an estimate slot.
    guard_budget(owner_id)
    quote = isv.destination_quote(session, order, (order.to_lat, order.to_lng),
                                  now=now, waypoints_override=points)
    # Driver movement has its own live-position guard, separate from SQL fields.
    _eligible(order, utcnow())
    # Do the fresh scalar re-read after external route and live-position work.
    if _read_state(session, owner_id, order_id) != captured:
        raise _conflict()
    quote = _checked_quote(order, quote)
    priced = _project(order, quote)
    return {
        "schema": "taxi-waypoints-preview-v1",
        "confirmation_available": False,
        "ride_price_kop": quote["price"] * 100,
        "previous_ride_price_kop": quote["old_price"] * 100,
        # Estimate for the current ride plus its retained compensation, less
        # applicable promo; not a final fare including accrued/future waiting.
        "payable_estimate_kop": promo_ride.payable_kop(priced),
        "previous_payable_estimate_kop": promo_ride.payable_kop(order),
    }
