"""Inactive driver-card equality proof for one currently observed capped offer.

This is not admission, assignment, or passenger agreement provenance. The
marker neither stores nor links the accepted route/fare digest; edits that
happen BEFORE first observation can be signed as the new observation. Do not
wire this into an API or accept path without proof of that provenance and
the corresponding transaction/legacy/mixed-version fences.
"""
from dataclasses import dataclass
from datetime import datetime
import hmac
import json
from types import SimpleNamespace

from sqlalchemy import DateTime, select, type_coerce
from sqlalchemy.engine import Connection
from sqlalchemy.exc import SQLAlchemyError

from . import instant_service as isv, taxi_offered_waypoint_preview as preview, taxi_route_resume as resume
from .models import InstantOrder, InstantOrderStatus, LedgerEntry, Payment
from .taxi_route_agreement import _point, _source_value, _sql_metric

_PURPOSE = b"taxi-marked-successor-driver-card-v1\x00"
_ORDER = InstantOrder.__table__
_COL = _ORDER.c
_FIELDS = tuple(dict.fromkeys((*resume._FIELDS, *preview._FIELDS, "payment_ack_at")))
_PAYMENT = Payment.__table__
_LEDGER = LedgerEntry.__table__
_HAS_PAYMENT = select(_PAYMENT.c.id).where(_PAYMENT.c.order_id == _COL.id).exists()
_HAS_LEDGER = select(_LEDGER.c.id).where(_LEDGER.c.order_id == _COL.id).exists()


@dataclass(frozen=True, slots=True, repr=False)
class MarkedOfferCardProof:
    """Private typed digest; no raw route, amount, marker, or public token."""
    _digest: bytes


def _valid_route(state: dict) -> bool:
    for prefix in ("from", "to"):
        _point(tuple(state[f"{prefix}_{k}"] for k in ("lat", "lng", "text")), stop=False)
    raw = state["waypoints_json"]
    if type(raw) is not str or len(raw) > 8192:
        return False
    stops = json.loads(raw) if raw else []
    if type(stops) is not list or len(stops) > 3:
        return False
    for stop in stops:
        if (type(stop) is not dict or set(stop) != {"lat", "lng", "text", "done"}
                or stop["done"] is not False):
            return False
        _point(tuple(stop[k] for k in ("lat", "lng", "text", "done")), stop=True)
    return isv.dump_waypoints(stops) == raw


def _valid_current(state: dict, now: datetime) -> bool:
    retired = state["route_resume_retired_at"]
    # Offered-state marker shape: four lineage fields set, claim and ready
    # cleared by store_offer. Keep both deadlines; never refresh either.
    if not (resume._valid(state) and resume._successor_offer(state)
            and state["route_resume_previous_driver_id"] not in (
                state["current_offer_driver_id"], state["passenger_id"],
            )
            and state["current_offer_driver_id"] != state["passenger_id"]
            and state["search_round"] >= 2
            and preview._utc(state["created_at"]) and preview._utc(now)
            and state["created_at"] <= state["searching_at"] <= retired <= state["offered_at"] <= now
            < state["offer_expires_at"] <= state["route_resume_deadline_at"]
            and state["destination_changed_at"] == retired
            and preview._id(state["tariff_id"])
            and all(state[f] is None for f in (
                "arriving_at", "onboard_at", "expired_at", "done_at", "pending_to_lat",
                "pending_to_lng", "pending_asked_at", "pending_price", "waiting_started_at",
                "stop_started_at", "destination_ack_at", "payment_changed_at", "payment_ack_at",
            ))
            and all(state[f] is False for f in preview._FALSE)
            and all(type(state[f]) is int and state[f] == 0 for f in preview._ZERO)
            and state["pending_to_text"] == state["pending_reason"] == state["fallback_categories"] == ""
            and state["payment_method"] in ("", "cash") and state["driven_km"] == 0.0):
        return False

    # Check existing row coherence, not another price or a fee inference.
    if (not preview._valid_fare(SimpleNamespace(**state))
            or state["promo_discount_kop"] > state["ride_price"] * 100):
        return False
    for name in ("distance_km", "eta_min", "pickup_km"):
        _sql_metric(state[name])
    return _valid_route(state)


def bind_current_card(
    conn: Connection, key: bytes, *, owner_id: int, order_id: int, driver_id: int,
) -> MarkedOfferCardProof | None:
    """Observe one exact private card by SQL, not eligibility or authority.

    Owner ID is a trusted internal order identity, not a driver-provided field.
    Existing settlement records are excluded in the same statement. The read
    is not a reservation; a later writer needs its own trusted admission and
    exact compare under ownership with a fresh DB clock in the final write.
    """
    if (conn.dialect.name not in ("postgresql", "sqlite")
            or type(key) is not bytes or len(key) < 16
            or not all(map(preview._id, (owner_id, order_id, driver_id)))
            or owner_id == driver_id):
        return None
    try:
        now_value = type_coerce(resume._clock(conn), DateTime())
        result = conn.execute(select(*(_COL[field] for field in _FIELDS), now_value).where(
            _COL.id == order_id, _COL.passenger_id == owner_id,
            _COL.current_offer_driver_id == driver_id,
            _COL.status == InstantOrderStatus.offered,
            _COL.route_resume_id.is_not(None),
            ~_HAS_PAYMENT, ~_HAS_LEDGER,
        )).one_or_none()
        if result is None:
            return None
        now = result[-1]
        state = dict(zip(_FIELDS, result[:-1], strict=True))
        if not _valid_current(state, now):
            return None
        # Keep exact stored scalar bytes/values in this digest; no mutable ORM,
        # repr, repricing, expiry issuance, or driver-visible PII.
        encoded = json.dumps(tuple(zip(_FIELDS, map(_source_value, result[:-1]), strict=True)),
                             ensure_ascii=True, allow_nan=False, separators=(",", ":")).encode("ascii")
    except (SQLAlchemyError, TypeError, ValueError, OverflowError, RecursionError):
        return None
    return MarkedOfferCardProof(hmac.digest(key, _PURPOSE + encoded, "sha256"))


def matches_current_card(
    conn: Connection, key: bytes, proof: MarkedOfferCardProof | None, *,
    owner_id: int, order_id: int, driver_id: int,
) -> bool:
    """Compare a freshly observed card. False does not imply any prior outcome."""
    if (type(proof) is not MarkedOfferCardProof
            or type(proof._digest) is not bytes or len(proof._digest) != 32):
        return False
    current = bind_current_card(conn, key, owner_id=owner_id, order_id=order_id, driver_id=driver_id)
    return current is not None and hmac.compare_digest(proof._digest, current._digest)
