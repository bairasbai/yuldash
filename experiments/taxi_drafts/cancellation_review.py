"""Offline cancellation/no-show review sketches. No fees, strikes or mutations."""
from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum

from .contracts import InvalidDraft, identifier, instant, integer
from .recovery import OrderSnapshot


def _ref(value: str) -> None:
    identifier(value)
    if not value.strip():
        raise InvalidDraft("blank_reference")


@dataclass(frozen=True)
class CancellationScope:
    order_ref: str = field(repr=False)
    actor_ref: str = field(repr=False)
    revision: int
    assignment_generation: int

    def __post_init__(self):
        _ref(self.order_ref)
        _ref(self.actor_ref)
        integer(self.revision)
        integer(self.assignment_generation)


@dataclass(frozen=True)
class CancelTerms:
    decision_ref: str = field(repr=False)
    scope: CancellationScope
    policy_version: str
    reason_code: str
    fee_kop: int
    currency: str
    created_at: datetime
    expires_at: datetime

    def __post_init__(self):
        _ref(self.decision_ref)
        _ref(self.policy_version)
        _ref(self.reason_code)
        if type(self.scope) is not CancellationScope or self.currency != "RUB":
            raise InvalidDraft("invalid_cancel_scope_or_currency")
        integer(self.fee_kop)
        instant(self.created_at)
        instant(self.expires_at)
        if self.expires_at <= self.created_at:
            raise InvalidDraft("invalid_cancel_interval")


class ConsentReview(str, Enum):
    RECONFIRM = "reconfirm"
    TIME_INVALID = "time_invalid"
    ALIGNED_FIXTURE = "aligned_fixture"


def review_cancel_terms(confirmed: CancelTerms | None, execution_terms: CancelTerms,
                        *, now: datetime) -> ConsentReview:
    """Strict reconfirm-on-change sketch, NOT the selected live cancellation policy.

    Execution terms must come from the future authoritative server adapter.
    Equality is not proof of consent, actor authorization or transaction safety.
    """
    instant(now)
    if type(execution_terms) is not CancelTerms or (confirmed is not None and type(confirmed) is not CancelTerms):
        raise InvalidDraft("invalid_cancel_terms")
    if confirmed is None or confirmed != execution_terms:
        return ConsentReview.RECONFIRM
    if now < confirmed.created_at or now >= confirmed.expires_at:
        return ConsentReview.TIME_INVALID
    return ConsentReview.ALIGNED_FIXTURE


class PickupEvidence(str, Enum):
    CONFIRMED = "confirmed"
    UNKNOWN = "unknown"
    INVALID = "invalid"


class ContactEvidence(str, Enum):
    RECORDED = "recorded"
    UNKNOWN = "unknown"
    MISSING = "missing"


@dataclass(frozen=True)
class NoShowFacts:
    snapshot: OrderSnapshot
    driver_ref: str = field(repr=False)
    policy_version: str
    assessed_at: datetime
    expires_at: datetime
    arrived_at: datetime | None
    waiting_started_at: datetime | None
    eligible_at: datetime | None
    scheduled_not_before: datetime | None
    pickup: PickupEvidence
    contact: ContactEvidence
    passenger_present: bool | None

    def __post_init__(self):
        if type(self.snapshot) is not OrderSnapshot:
            raise InvalidDraft("invalid_snapshot")
        _ref(self.driver_ref)
        _ref(self.policy_version)
        instant(self.assessed_at)
        instant(self.expires_at)
        if self.assessed_at >= self.expires_at:
            raise InvalidDraft("invalid_review_interval")
        for value in (self.arrived_at, self.waiting_started_at, self.eligible_at, self.scheduled_not_before):
            if value is not None:
                instant(value)
        if type(self.pickup) is not PickupEvidence or type(self.contact) is not ContactEvidence:
            raise InvalidDraft("invalid_review_evidence")
        if self.passenger_present is not None and type(self.passenger_present) is not bool:
            raise InvalidDraft("invalid_presence")


class NoShowReview(str, Enum):
    REVIEW_REQUIRED = "review_required"
    BLOCKED = "blocked"
    WAIT = "wait"
    CANDIDATE_ONLY = "candidate_only"


def review_no_show(facts: NoShowFacts, *, current_scope: CancellationScope,
                   current_snapshot: OrderSnapshot, policy_version: str,
                   now: datetime) -> NoShowReview:
    """No action permission. Unknown location/contact is not passenger fault.

    current_* and policy_version are the current order's applicable frozen rules,
    not latest global rates. All provenance, contact semantics and authorization
    are NOT_IMPLEMENTED. Candidate alone cannot mark no-show, charge or punish.
    """
    instant(now)
    _ref(policy_version)
    if (type(facts) is not NoShowFacts or type(current_scope) is not CancellationScope
            or type(current_snapshot) is not OrderSnapshot):
        raise InvalidDraft("invalid_no_show_input")
    expected = CancellationScope(current_snapshot.order_ref, facts.driver_ref,
                                 current_snapshot.revision, current_snapshot.assignment_generation)
    if (current_scope != expected or facts.snapshot != current_snapshot
            or facts.policy_version != policy_version):
        return NoShowReview.REVIEW_REQUIRED
    if current_snapshot.state != "arriving" or facts.passenger_present is True:
        return NoShowReview.BLOCKED
    if now < facts.assessed_at or now >= facts.expires_at:
        return NoShowReview.REVIEW_REQUIRED
    if (facts.pickup is not PickupEvidence.CONFIRMED or facts.contact is not ContactEvidence.RECORDED
            or facts.passenger_present is None):
        return NoShowReview.REVIEW_REQUIRED
    arrival, waiting, eligible = facts.arrived_at, facts.waiting_started_at, facts.eligible_at
    if arrival is None or waiting is None or eligible is None:
        return NoShowReview.REVIEW_REQUIRED
    if not arrival <= waiting <= facts.assessed_at or eligible < waiting:
        return NoShowReview.REVIEW_REQUIRED
    if facts.scheduled_not_before is not None and waiting < facts.scheduled_not_before:
        return NoShowReview.REVIEW_REQUIRED
    if now < eligible:
        return NoShowReview.WAIT
    return NoShowReview.CANDIDATE_ONLY
