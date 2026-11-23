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


_RoutePoints = tuple[tuple[float, float], ...]


@dataclass(frozen=True, repr=False)
class _RoutePairRequest:
    before: _RoutePoints
    after: _RoutePoints
    departure_at: datetime


@dataclass(frozen=True, repr=False)
class _TrafficBasis:
    # From the trusted router's actual data revision, not from our request ID,
    # cache hit time, wall-clock bucket, or the provider's coarse traffic_type.
    revision: str
    departure_at: datetime
    mode: str
    avoid_tolls: bool


@dataclass(frozen=True, repr=False)
class _TrafficRoute:
    points: _RoutePoints
    basis: _TrafficBasis
    metrics: RouteMetrics


@dataclass(frozen=True, repr=False)
class _RoutePair:
    before: _TrafficRoute
    after: _TrafficRoute


_RemainingRoutePair = Callable[[_RoutePairRequest], _RoutePair | None]


def _valid_basis(basis, departure_at):
    return (type(basis) is _TrafficBasis
            and type(basis.revision) is str and 0 < len(basis.revision) <= 128
            and basis.revision.isascii() and basis.revision.isprintable()
            and basis.revision.strip() == basis.revision
            and _utc_stamp(basis.departure_at) and basis.departure_at == departure_at
            and type(basis.mode) is str and basis.mode == "driving"
            and type(basis.avoid_tolls) is bool)


def _same_points(actual, expected):
    # Reject bool masquerading as 0/1 and malformed route echoes without
    # treating rounding or unordered equality as proof about this geometry.
    return (type(actual) is tuple and len(actual) == len(expected)
            and all(type(point) is tuple and len(point) == 2
                    and all(type(value) in (float, int) for value in point)
                    and point == wanted for point, wanted in zip(actual, expected, strict=True)))


def _pair_delta(pair, request):
    if type(pair) is not _RoutePair:
        return None
    old, new = pair.before, pair.after
    if (type(old) is not _TrafficRoute or type(new) is not _TrafficRoute
            or not _same_points(old.points, request.before) or not _same_points(new.points, request.after)
            or not _valid_basis(old.basis, request.departure_at) or not _valid_basis(new.basis, request.departure_at)
            or old.basis != new.basis):
        return None
    before, after = _minutes(old.metrics), _minutes(new.metrics)
    if (before is None or after is None
            or type(old.metrics.traffic_type) is not str or type(new.metrics.traffic_type) is not str
            or old.metrics.traffic_type not in ("realtime", "forecast")
            or old.metrics.traffic_type != new.metrics.traffic_type):
        return None
    delta = after - before
    return delta if math.isfinite(delta) else None


def _minutes(metrics: RouteMetrics | None) -> float | None:
    # Existing routing contract: only the provider's route is authoritative.
    # Fallback or a mixed provider/fallback route is not proof of a delta.
    if type(metrics) is not RouteMetrics or type(metrics.source) is not str or metrics.source != "yandex":
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
    route_pair: _RemainingRoutePair,
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

    request = _RoutePairRequest((start, *legs(old_remaining), destination),
                                (start, *legs(new_remaining), destination), now)
    # An inert contract only: existing route_metrics / route_through cannot
    # supply a common traffic revision. Never upgrade their results to proof.
    return OnboardWaypointEta(_pair_delta(route_pair(request), request))
