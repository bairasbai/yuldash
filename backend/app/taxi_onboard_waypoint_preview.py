"""Inert TF04 waypoint ETA proof; no WS intake, price calculation or HTTP caller."""

import math
from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime

from .instant_service import DESTINATION_LOCK_KM, parse_waypoints
from .livepos import LIVEPOS_TTL_SEC
from .pricing import RouteMetrics
from .services import haversine_km
from .taxi_waypoint_consent import _points, _unavailable


def _utc_stamp(value: datetime) -> bool:
    return type(value) is datetime and value.tzinfo is None


def _id(value: int) -> bool:
    return type(value) is int and 0 < value <= (1 << 31) - 1


@dataclass(frozen=True, repr=False)
class OnboardAssignment:
    order_id: int
    passenger_id: int
    driver_id: int
    accepted_at: datetime
    onboard_at: datetime
    reassigns: int

    def __post_init__(self):
        if (not all(map(_id, (self.order_id, self.passenger_id, self.driver_id)))
                or self.passenger_id == self.driver_id
                or not _utc_stamp(self.accepted_at) or not _utc_stamp(self.onboard_at)
                or self.accepted_at > self.onboard_at or type(self.reassigns) is not int
                or not 0 <= self.reassigns <= (1 << 31) - 1):
            raise ValueError("Invalid onboard assignment")


@dataclass(frozen=True, repr=False)
class _Coordinate:
    lat: float
    lng: float

    def __post_init__(self):
        if (type(self.lat) not in (int, float) or not -90 <= self.lat <= 90
                or type(self.lng) not in (int, float) or not -180 <= self.lng <= 180):
            raise ValueError("Invalid route point")

    def pair(self) -> tuple[float, float]:
        return self.lat, self.lng


@dataclass(frozen=True, repr=False)
class OnboardPosition:
    """Structurally valid sample only. Construction proves no authentication."""

    assignment: OnboardAssignment
    observed_at: datetime
    lat: float
    lng: float

    def __post_init__(self):
        if (type(self.assignment) is not OnboardAssignment or not _utc_stamp(self.observed_at)
                or self.observed_at < self.assignment.onboard_at):
            raise ValueError("Invalid onboard position age")
        _Coordinate(self.lat, self.lng)


@dataclass(frozen=True, repr=False)
class ProposedStop:
    lat: float
    lng: float
    text: str = ""

    def __post_init__(self):
        _Coordinate(self.lat, self.lng)
        if type(self.text) is not str or len(self.text) > 200:
            raise ValueError("Invalid proposed stop text")


@dataclass(frozen=True)
class OnboardWaypointEta:
    # None means no trustworthy comparison, not zero minutes of detour.
    delta_min: float | None


_RemainingRoute = Callable[
    [tuple[float, float], tuple[tuple[float, float], ...], tuple[float, float]],
    RouteMetrics | None,
]


def _minutes(metrics: RouteMetrics | None) -> float | None:
    # Existing routing contract: only the provider's route is authoritative.
    # Fallback or a mixed provider/fallback route is not proof of a delta.
    if type(metrics) is not RouteMetrics or metrics.source != "yandex":
        return None
    raw = (metrics.distance_km, metrics.duration_min)
    if not all(type(value) in (int, float) for value in raw):
        return None
    try:
        distance, duration = map(float, raw)
    except OverflowError:
        return None
    if not all(math.isfinite(value) and value > 0 for value in (distance, duration)):
        return None
    return duration


def compare_remaining(
    *,
    assignment: OnboardAssignment,
    position: OnboardPosition | None,
    now: datetime,
    to_lat: float,
    to_lng: float,
    stored_waypoints: str,
    requested: tuple[ProposedStop, ...],
    standing: bool,
    route: _RemainingRoute,
) -> OnboardWaypointEta:
    """Read-only proof over caller-authorized inputs; no source/cache or I/O defaults."""
    if (type(position) is not OnboardPosition or type(assignment) is not OnboardAssignment
            or position.assignment != assignment or not _utc_stamp(now)):
        raise _unavailable()
    age = (now - position.observed_at).total_seconds()
    if not 0 <= age < LIVEPOS_TTL_SEC:
        raise _unavailable()
    try:
        destination = _Coordinate(to_lat, to_lng).pair()
    except ValueError:
        raise _unavailable() from None
    start = (position.lat, position.lng)
    if haversine_km(*start, *destination) <= DESTINATION_LOCK_KM:
        raise _unavailable()
    if (type(stored_waypoints) is not str or type(requested) is not tuple or len(requested) > 3
            or not all(type(stop) is ProposedStop for stop in requested) or type(standing) is not bool):
        raise _unavailable()
    proposed = _points(stored_waypoints, [
        {"lat": stop.lat, "lng": stop.lng, "text": stop.text} for stop in requested
    ])
    old_remaining = [stop for stop in parse_waypoints(stored_waypoints) if not stop["done"]]
    new_remaining = [stop for stop in proposed if not stop["done"]]
    # Waiting is recorded on the order, not on an individual stop. Keep
    # the first pending point unchanged while a stop may be in progress.
    if standing and (not old_remaining or not new_remaining or old_remaining[0] != new_remaining[0]):
        raise _unavailable()

    def legs(stops):
        return tuple((stop["lat"], stop["lng"]) for stop in stops)

    before = _minutes(route(start, legs(old_remaining), destination))
    after = _minutes(route(start, legs(new_remaining), destination))
    return OnboardWaypointEta(after - before if before is not None and after is not None else None)
