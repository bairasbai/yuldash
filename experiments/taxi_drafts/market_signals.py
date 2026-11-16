"""Offline signal-quality sketch; never authorizes a surcharge or a trip."""
from dataclasses import dataclass
from datetime import datetime
from enum import Enum
from fractions import Fraction

from .contracts import Category, InvalidDraft, identifier, instant, integer


@dataclass(frozen=True)
class SignalContext:
    region_version: str
    category: Category
    provider: str
    version: str
    window_start: datetime
    window_end: datetime

    def __post_init__(self):
        for value in (self.region_version, self.provider, self.version):
            identifier(value)
        if type(self.category) is not Category:
            raise InvalidDraft("invalid_category")
        instant(self.window_start)
        instant(self.window_end)
        if self.window_start >= self.window_end:
            raise InvalidDraft("invalid_window")


class Quality(str, Enum):
    COMPLETE = "complete"
    PARTIAL = "partial"
    FAILED = "failed"


@dataclass(frozen=True)
class CountSignal:
    """sample_size counts validated observations, NOT current demand/supply.

    Counts must eventually come from deduplicated server demand and independently
    eligible reachable supply. These fixtures do not implement or prove that.
    """
    context: SignalContext
    received_at: datetime
    count: int | None
    sample_size: int
    quality: Quality

    def __post_init__(self):
        if type(self.context) is not SignalContext or type(self.quality) is not Quality:
            raise InvalidDraft("invalid_signal")
        instant(self.received_at)
        if self.received_at < self.context.window_end:
            raise InvalidDraft("received_before_window_end")
        if self.count is not None:
            integer(self.count)
        integer(self.sample_size)


class SafetyState(str, Enum):
    CLEAR = "clear"
    UNKNOWN = "unknown"
    RESTRICTED = "restricted"


@dataclass(frozen=True)
class SafetySignal:
    region_version: str
    version: str
    observed_at: datetime
    expires_at: datetime
    state: SafetyState

    def __post_init__(self):
        identifier(self.region_version)
        identifier(self.version)
        instant(self.observed_at)
        instant(self.expires_at)
        if self.expires_at <= self.observed_at or type(self.state) is not SafetyState:
            raise InvalidDraft("invalid_safety_signal")


@dataclass(frozen=True)
class QualityPolicy:
    max_age_sec: int
    max_window_sec: int
    min_observations: int
    safety_version: str

    def __post_init__(self):
        for value in (self.max_age_sec, self.max_window_sec, self.min_observations):
            integer(value, minimum=1)
        identifier(self.safety_version)


class SignalDecision(str, Enum):
    RESTRICTION_REPORTED = "restriction_reported"
    SAFETY_REVIEW_REQUIRED = "safety_review_required"
    DEGRADED = "degraded"
    NO_REACHABLE_SUPPLY = "no_reachable_supply"
    NO_DEMAND = "no_demand"
    COMPARABLE_OBSERVATION = "comparable_observation"


@dataclass(frozen=True)
class SignalAssessment:
    decision: SignalDecision
    reasons: tuple[str, ...]
    demand_supply_observation: Fraction | None
    limitation: str = "NOT_IMPLEMENTED_pricing_authorization_and_service_safety"


def _quality_reasons(signal: CountSignal | None, name: str, expected: SignalContext,
                     policy: QualityPolicy, now: datetime) -> list[str]:
    if signal is None:
        return [name+"_missing"]
    if type(signal) is not CountSignal:
        raise InvalidDraft("invalid_signal")
    reasons = []
    if signal.context != expected:
        reasons.append(name+"_context_mismatch")
    if signal.received_at > now or signal.context.window_end > now:
        reasons.append(name+"_future")
    if (now-signal.context.window_end).total_seconds() >= policy.max_age_sec:
        reasons.append(name+"_stale")
    if (signal.context.window_end-signal.context.window_start).total_seconds() > policy.max_window_sec:
        reasons.append(name+"_window_too_wide")
    if signal.quality is not Quality.COMPLETE:
        reasons.append(name+"_"+signal.quality.value)
    if signal.count is None:
        reasons.append(name+"_unknown_count")
    if signal.sample_size < policy.min_observations:
        reasons.append(name+"_insufficient_observations")
    return reasons


def assess_market_signals(demand: CountSignal | None, supply: CountSignal | None,
                          safety: SafetySignal | None, *, expected: SignalContext,
                          policy: QualityPolicy, now: datetime) -> SignalAssessment:
    """A dimensionless ratio is an observation, NEVER a fare multiplier.

    There is no weather surcharge input. Rain alone cannot generate scarcity here.
    Active trips, support and real availability are untouched. Even CLEAR is only
    a supplied fixture, not a guarantee that a real road or trip is safe.
    """
    instant(now)
    if type(expected) is not SignalContext or type(policy) is not QualityPolicy:
        raise InvalidDraft("invalid_context_or_policy")
    reasons = _quality_reasons(demand, "demand", expected, policy, now)
    reasons += _quality_reasons(supply, "supply", expected, policy, now)
    safety_reasons = []
    restriction = False
    if safety is None:
        safety_reasons.append("safety_missing")
    else:
        if type(safety) is not SafetySignal:
            raise InvalidDraft("invalid_safety")
        matches = (safety.region_version == expected.region_version
                   and safety.version == policy.safety_version)
        if not matches:
            safety_reasons.append("safety_context_mismatch")
        if safety.observed_at > now or now >= safety.expires_at:
            safety_reasons.append("safety_not_current")
        if safety.state is SafetyState.UNKNOWN:
            safety_reasons.append("safety_unknown")
        # Conservative: a matching restriction still suppresses the ratio when
        # stale. Its freshness issue is retained; not a verified live incident.
        restriction = matches and safety.state is SafetyState.RESTRICTED
    all_reasons = tuple(safety_reasons+reasons)
    if restriction:
        return SignalAssessment(SignalDecision.RESTRICTION_REPORTED, ("restriction_reported",)+all_reasons, None)
    if safety_reasons:
        return SignalAssessment(SignalDecision.SAFETY_REVIEW_REQUIRED, all_reasons, None)
    if reasons:
        return SignalAssessment(SignalDecision.DEGRADED, all_reasons, None)
    if supply.count == 0:
        return SignalAssessment(SignalDecision.NO_REACHABLE_SUPPLY, ("zero_supply_not_infinite_scarcity",), None)
    if demand.count == 0:
        return SignalAssessment(SignalDecision.NO_DEMAND, (), Fraction(0))
    return SignalAssessment(SignalDecision.COMPARABLE_OBSERVATION, (), Fraction(demand.count, supply.count))
