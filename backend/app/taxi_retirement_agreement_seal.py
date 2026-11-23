"""Inactive seal of a verified retirement route and pricing projection.

This is separate from a driver's transient card. Only the *old*, verified
offered preview plus its accepted write projection can mint a seal; never
mint/backfill one from a searching or successor row. Future callers must store
it inside the same trusted admission/retirement transaction and recheck it
against the owned current SQL row at the final mutation, not just at read time.
"""
from collections.abc import Mapping
from dataclasses import dataclass
from datetime import datetime, timedelta
import hmac
import json

from . import taxi_offered_waypoint_preview as offered, taxi_route_resume as resume
from .compensation import COMPENSATION_FIELDS
from .taxi_offered_waypoint_agreement import OfferedWaypointAgreement, _matched_projection
from .taxi_route_agreement import _source_value

_PURPOSE = b"taxi-offered-retirement-passenger-projection-v1\x00"
_LINEAGE = (
    "id", "passenger_id", "route_resume_id", "route_resume_previous_driver_id",
    "route_resume_retired_at", "route_resume_deadline_at", "destination_changed_at",
)
_RETAINED = (
    "from_lat", "from_lng", "from_text", "to_lat", "to_lng", "to_text",
    "category", "options", "women_only", "fallback_categories",
    "tariff_id", "pricing_k", "surge_k",
    "pickup_pending", "pickup_enroute", "pickup_km", *COMPENSATION_FIELDS, "weather_kind",
)
_PROJECTED = (
    "waypoints_json", "ride_price", "ride_base_price", "price_estimate",
    "promo_discount_kop", "distance_km", "eta_min",
)
_FIELDS = (*_LINEAGE, *_RETAINED, *_PROJECTED)


@dataclass(frozen=True, slots=True, repr=False)
class RetirementAgreementSeal:
    """Opaque server value only; not a token, receipt, or authorization."""
    _digest: bytes


def _well_formed(key: bytes, marker: str, retired: datetime, deadline: datetime) -> bool:
    # The approved absolute horizon belongs to the atomic retirement, not to
    # the old quote/offer, a new claim, or a successor's offer clock.
    return (type(key) is bytes and len(key) >= 16 and resume._token(marker)
            and offered._utc(retired) and offered._utc(deadline)
            and deadline - retired == timedelta(seconds=resume._MAX_SECONDS))


def _digest(key: bytes, state: Mapping) -> bytes:
    # A tagged/versioned exact allowlist: no mutable lease, status, round,
    # current recipient, held claim, or rate-table read. The old signed offer
    # already included the verified rate inputs; persist the locked pricing
    # context and exact SQL projection, not future live rates or an estimate.
    payload = tuple((field, _source_value(state[field])) for field in _FIELDS)
    encoded = json.dumps(payload, ensure_ascii=True, allow_nan=False, separators=(",", ":")).encode("ascii")
    return hmac.digest(key, _PURPOSE + encoded, "sha256")


def bind_retirement_agreement(
    key: bytes, *, owner_id: int, order_id: int,
    old_source: tuple, preview: offered.OfferedWaypointPreview, agreement: OfferedWaypointAgreement,
    marker_id: str, retired_at: datetime, deadline_at: datetime,
) -> RetirementAgreementSeal | None:
    """Build only from the unchanged signed old offer and vetted projection.

    No DB access. A future writer must own the observed row/rates and obtain
    the exact marker and timestamps from its actual atomic retirement. This
    helper does not establish that ownership or any passenger admission.
    """
    if not _well_formed(key, marker_id, retired_at, deadline_at):
        return None
    projected = _matched_projection(
        key, agreement, owner_id=owner_id, order_id=order_id, preview=preview,
        current=old_source, now=retired_at,
    )
    if projected is None:
        return None
    old = dict(zip(offered._FIELDS, old_source[:len(offered._FIELDS)], strict=True))
    source = {field: old[field] for field in _RETAINED}
    source.update((field, getattr(projected, field)) for field in _PROJECTED)
    source.update(
        id=order_id, passenger_id=owner_id, route_resume_id=marker_id,
        route_resume_previous_driver_id=old["current_offer_driver_id"],
        route_resume_retired_at=retired_at, route_resume_deadline_at=deadline_at,
        destination_changed_at=retired_at,
    )
    return RetirementAgreementSeal(_digest(key, source))


def matches_retirement_agreement(
    key: bytes, seal: RetirementAgreementSeal | None, *, owner_id: int, order_id: int,
    current: Mapping, now: datetime,
) -> bool:
    """Equality to the originally approved write projection, not permission."""
    if (type(seal) is not RetirementAgreementSeal or type(seal._digest) is not bytes
            or len(seal._digest) != 32 or not offered._utc(now)
            or not offered._id(owner_id) or not offered._id(order_id)):
        return False
    try:
        retired = current["route_resume_retired_at"]
        deadline = current["route_resume_deadline_at"]
        if (not _well_formed(key, current["route_resume_id"], retired, deadline)
                or not retired <= now < deadline or current["destination_changed_at"] != retired
                or type(current["id"]) is not int or current["id"] != order_id
                or type(current["passenger_id"]) is not int or current["passenger_id"] != owner_id
                or not offered._id(current["route_resume_previous_driver_id"])):
            return False
        expected = _digest(key, current)
    except (KeyError, TypeError, ValueError, OverflowError):
        return False
    return hmac.compare_digest(expected, seal._digest)
