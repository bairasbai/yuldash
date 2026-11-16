"""Offline readiness evidence binding, NOT driver admission or assignment logic."""
from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum

from .contracts import InvalidDraft, identifier, instant, integer


def _ref(value: str) -> None:
    identifier(value)
    if not value.strip():
        raise InvalidDraft("blank_reference")


class CheckPhase(str, Enum):
    OFFER = "offer"
    ACCEPT = "accept"
    ONBOARD = "onboard"


@dataclass(frozen=True)
class ReadinessScope:
    order_ref: str = field(repr=False)
    driver_ref: str = field(repr=False)
    vehicle_ref: str = field(repr=False)
    batch_ref: str = field(repr=False)
    order_revision: int
    vehicle_revision: int
    assignment_generation: int
    policy_version: str
    phase: CheckPhase
    request_started_at: datetime

    def __post_init__(self):
        for value in (self.order_ref, self.driver_ref, self.vehicle_ref, self.batch_ref, self.policy_version):
            _ref(value)
        for value in (self.order_revision, self.vehicle_revision, self.assignment_generation):
            integer(value)
        if type(self.phase) is not CheckPhase:
            raise InvalidDraft("invalid_phase")
        instant(self.request_started_at)


class CheckKind(str, Enum):
    ACCOUNT_ADMISSION = "account_admission"
    DOCUMENTS = "documents"
    VEHICLE = "vehicle"
    PRETRIP = "pretrip"
    REST = "rest"
    CLASS = "class"
    OPTIONS = "options"
    ZONE = "zone"
    AVAILABILITY = "availability"
    PRESENCE = "presence"
    PARTICIPANT_COMPATIBILITY = "participant_compatibility"


class CheckOutcome(str, Enum):
    PASS = "pass"
    FAIL = "fail"
    UNKNOWN = "unknown"


@dataclass(frozen=True)
class ReadinessEvidence:
    """Synthetic attestations only; never accept PASS/evidence_ref from a client."""
    scope: ReadinessScope
    kind: CheckKind
    outcome: CheckOutcome
    observed_at: datetime
    valid_until: datetime
    evidence_ref: str = field(repr=False)

    def __post_init__(self):
        if (type(self.scope) is not ReadinessScope or type(self.kind) is not CheckKind
                or type(self.outcome) is not CheckOutcome):
            raise InvalidDraft("invalid_readiness_evidence")
        _ref(self.evidence_ref)
        instant(self.observed_at)
        instant(self.valid_until)
        if self.valid_until <= self.observed_at:
            raise InvalidDraft("invalid_validity_interval")


class ReadinessDecision(str, Enum):
    RECHECK_REQUIRED = "recheck_required"
    NOT_A_CANDIDATE = "not_a_candidate"
    CANDIDATE_ONLY = "candidate_only"
    ACTIVE_TRIP_POLICY_REQUIRED = "active_trip_policy_required"


@dataclass(frozen=True)
class ReadinessReview:
    decision: ReadinessDecision
    reasons: tuple[str, ...]
    limitation: str = "Synthetic binding only. No assignment, permission or active-trip action."


def review_readiness(evidence: tuple[ReadinessEvidence, ...], *, expected: ReadinessScope,
                     now: datetime) -> ReadinessReview:
    """Validate the full sketch checklist without reimplementing eligible().

    The caller's request boundary is trusted fixture data, not proof of issuance.
    A fresh batch name cannot refresh old observations. Onboard always requires
    a separate safety policy; this function never ends trips or hides help.
    """
    instant(now)
    if type(evidence) is not tuple or type(expected) is not ReadinessScope:
        raise InvalidDraft("invalid_readiness_input")
    reasons, failures, kinds = set(), set(), set()
    if expected.request_started_at > now:
        reasons.add("request_from_future")
    for item in evidence:
        if type(item) is not ReadinessEvidence:
            raise InvalidDraft("invalid_readiness_evidence")
        name = item.kind.value
        if item.kind in kinds:
            reasons.add(name+"_duplicate")
        kinds.add(item.kind)
        valid = True
        if item.scope != expected:
            reasons.add(name+"_scope_mismatch")
            valid = False
        if item.observed_at < expected.request_started_at:
            reasons.add(name+"_predates_request")
            valid = False
        if item.observed_at > now or now >= item.valid_until:
            reasons.add(name+"_not_current")
            valid = False
        if item.outcome is CheckOutcome.UNKNOWN:
            reasons.add(name+"_unknown")
        elif valid and item.outcome is CheckOutcome.FAIL:
            failures.add(name+"_failed")
    for kind in set(CheckKind)-kinds:
        reasons.add(kind.value+"_missing")
    all_reasons = tuple(sorted(reasons | failures))
    if expected.phase is CheckPhase.ONBOARD:
        return ReadinessReview(ReadinessDecision.ACTIVE_TRIP_POLICY_REQUIRED, all_reasons)
    if reasons:
        return ReadinessReview(ReadinessDecision.RECHECK_REQUIRED, all_reasons)
    if failures:
        return ReadinessReview(ReadinessDecision.NOT_A_CANDIDATE, all_reasons)
    return ReadinessReview(ReadinessDecision.CANDIDATE_ONLY, ())
