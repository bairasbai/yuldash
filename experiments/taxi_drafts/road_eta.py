"""Road ETA fixture comparison only. NOT a dispatch/eligibility implementation."""
from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum

from .contracts import InvalidDraft, Point, identifier, instant, integer


class RouteStatus(str, Enum):
    ROAD = "road"
    UNREACHABLE = "unreachable"
    TIMEOUT = "timeout"
    MISSING = "missing"


@dataclass(frozen=True)
class Comparison:
    """One explicit provider/profile/traffic/departure context per batch."""
    provider: str
    map_version: str
    profile: str
    traffic_version: str
    departure_at: datetime
    pickup: Point = field(repr=False)

    def __post_init__(self):
        for value in (self.provider, self.map_version, self.profile, self.traffic_version):
            identifier(value)
        instant(self.departure_at)
        if type(self.pickup) is not Point:
            raise InvalidDraft("invalid_pickup")


@dataclass(frozen=True)
class Estimate:
    candidate_ref: str = field(repr=False)
    context: Comparison
    origin: Point = field(repr=False)
    observed_at: datetime
    position_at: datetime
    status: RouteStatus
    duration_sec: int | None

    def __post_init__(self):
        identifier(self.candidate_ref)
        if type(self.context) is not Comparison or type(self.origin) is not Point:
            raise InvalidDraft("invalid_route_context")
        instant(self.observed_at)
        instant(self.position_at)
        if self.position_at > self.observed_at:
            raise InvalidDraft("position_after_estimate")
        if type(self.status) is not RouteStatus:
            raise InvalidDraft("invalid_route_status")
        if self.status is RouteStatus.ROAD:
            integer(self.duration_sec)
        elif self.duration_sec is not None:
            raise InvalidDraft("duration_without_route")


@dataclass(frozen=True)
class Shortlist:
    candidates: tuple[str, ...] = field(repr=False)
    rejected: tuple[tuple[str, str], ...] = field(repr=False)


def preview_shortlist(estimates: tuple[Estimate, ...], *, context: Comparison,
                      origins: dict[str, Point], now: datetime,
                      max_estimate_age_sec: int, max_position_age_sec: int,
                      limit: int) -> Shortlist:
    """Compare already eligible synthetic candidates, no allocation or fallback.

    origins is the expected driver -> pickup direction; mismatches are rejected.
    The caller must bound its batch and separately verify authoritative eligibility.
    Limits are required fixture policy, not approved production defaults.
    Equal ETA uses stable ID solely for reproducible tests; production fairness,
    starvation protection and transactional reservation are NOT_IMPLEMENTED.
    """
    instant(now)
    integer(max_estimate_age_sec)
    integer(max_position_age_sec)
    integer(limit, minimum=1)
    if type(estimates) is not tuple or any(type(e) is not Estimate for e in estimates):
        raise InvalidDraft("invalid_estimates")
    ids = [e.candidate_ref for e in estimates]
    if len(set(ids)) != len(ids):
        raise InvalidDraft("duplicate_candidate")
    accepted, rejected = [], []
    for e in estimates:
        if e.context != context or origins.get(e.candidate_ref) != e.origin:
            reason = "incomparable_route"
        elif e.observed_at > now or e.position_at > now:
            reason = "future_observation"
        elif (now - e.observed_at).total_seconds() > max_estimate_age_sec:
            reason = "stale_estimate"
        elif (now - e.position_at).total_seconds() > max_position_age_sec:
            reason = "stale_position"
        elif e.status is not RouteStatus.ROAD:
            reason = e.status.value
        else:
            accepted.append((e.duration_sec, e.candidate_ref))
            continue
        rejected.append((e.candidate_ref, reason))
    return Shortlist(tuple(ref for _, ref in sorted(accepted)[:limit]), tuple(rejected))
