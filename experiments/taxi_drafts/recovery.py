"""Offline recovery classifiers. No reads, persistence, retries or authorization.

Evidence must eventually come from authenticated consistent server reads. These
sketches cannot prove provenance and must NOT authorize API actions on their own.
"""
from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum

from .contracts import InvalidDraft, identifier, instant, integer


@dataclass(frozen=True)
class ReadScope:
    account_ref: str = field(repr=False)
    session_ref: str = field(repr=False)
    request_ref: str = field(repr=False)

    def __post_init__(self):
        for value in (self.account_ref, self.session_ref, self.request_ref):
            identifier(value)


@dataclass(frozen=True)
class OrderSnapshot:
    order_ref: str = field(repr=False)
    revision: int
    assignment_generation: int
    state: str

    def __post_init__(self):
        identifier(self.order_ref)
        integer(self.revision)
        integer(self.assignment_generation)
        if self.state not in ("scheduled", "created", "searching", "offered", "accepted",
                              "arriving", "onboard", "done", "cancelled", "expired"):
            raise InvalidDraft("unknown_order_state")


class ReadKind(str, Enum):
    CURRENT = "current"
    ABSENT = "absent"  # Explicit authoritative active-order query result, NOT 404.
    ERROR = "error"
    AUTH_REQUIRED = "auth_required"
    ACCESS_REVOKED = "access_revoked"


@dataclass(frozen=True)
class ReadEvidence:
    scope: ReadScope
    kind: ReadKind
    observed_at: datetime | None
    snapshot: OrderSnapshot | None = field(default=None, repr=False)

    def __post_init__(self):
        if type(self.scope) is not ReadScope or type(self.kind) is not ReadKind:
            raise InvalidDraft("invalid_read_evidence")
        if self.kind in (ReadKind.CURRENT, ReadKind.ABSENT):
            instant(self.observed_at)
        elif self.observed_at is not None:
            raise InvalidDraft("error_is_not_a_confirmed_read")
        if self.kind is ReadKind.CURRENT:
            if type(self.snapshot) is not OrderSnapshot:
                raise InvalidDraft("missing_snapshot")
        elif self.snapshot is not None:
            raise InvalidDraft("unexpected_snapshot")


class ReadDecision(str, Enum):
    CURRENT_ORDER = "current_order"
    CONFIRMED_ABSENT = "confirmed_absent"
    KEEP_LAST_UNCONFIRMED = "keep_last_unconfirmed"
    HIDE_AND_REAUTHENTICATE = "hide_and_reauthenticate"
    HIDE_AND_RECONCILE_ACCESS = "hide_and_reconcile_access"
    REJECTED_SCOPE = "rejected_scope"
    REJECTED_TIME = "rejected_time"
    RECONCILE_ORDER = "reconcile_order"
    RECONCILE_GENERATION = "reconcile_generation"
    REJECTED_REVISION = "rejected_revision"


def assess_read(evidence: ReadEvidence | None, *, expected_scope: ReadScope,
                now: datetime, max_age_sec: int,
                anchor: OrderSnapshot | None = None) -> ReadDecision:
    """Classify a latest-request fixture without clearing or replacing any state.

    ABSENT is not a command receipt. Terminal/expired/cancelled states do not
    trigger clearing here: existing queue/reassignment may resume searching.
    max_age_sec is an explicit fixture policy, not an approved production TTL.
    """
    instant(now)
    integer(max_age_sec, minimum=1)
    if type(expected_scope) is not ReadScope:
        raise InvalidDraft("invalid_expected_scope")
    if anchor is not None and type(anchor) is not OrderSnapshot:
        raise InvalidDraft("invalid_anchor")
    if evidence is None:
        return ReadDecision.KEEP_LAST_UNCONFIRMED
    if type(evidence) is not ReadEvidence:
        raise InvalidDraft("invalid_read_evidence")
    if evidence.scope != expected_scope:
        return ReadDecision.REJECTED_SCOPE
    if evidence.kind is ReadKind.ERROR:
        return ReadDecision.KEEP_LAST_UNCONFIRMED
    if evidence.kind is ReadKind.AUTH_REQUIRED:
        return ReadDecision.HIDE_AND_REAUTHENTICATE
    if evidence.kind is ReadKind.ACCESS_REVOKED:
        return ReadDecision.HIDE_AND_RECONCILE_ACCESS
    age = (now - evidence.observed_at).total_seconds()
    if age < 0 or age >= max_age_sec:
        return ReadDecision.REJECTED_TIME
    if evidence.kind is ReadKind.ABSENT:
        return ReadDecision.CONFIRMED_ABSENT
    snapshot = evidence.snapshot
    if anchor is not None:
        if snapshot.order_ref != anchor.order_ref:
            return ReadDecision.RECONCILE_ORDER
        if snapshot.assignment_generation != anchor.assignment_generation:
            return ReadDecision.RECONCILE_GENERATION
        if snapshot.revision < anchor.revision or (
            snapshot.revision == anchor.revision and snapshot != anchor
        ):
            return ReadDecision.REJECTED_REVISION
    return ReadDecision.CURRENT_ORDER


@dataclass(frozen=True)
class CommandIdentity:
    account_ref: str = field(repr=False)
    action: str
    idempotency_key: str = field(repr=False)
    body: bytes = field(repr=False)

    def __post_init__(self):
        for value in (self.account_ref, self.action, self.idempotency_key):
            identifier(value)
        if type(self.body) is not bytes or not self.body:
            raise InvalidDraft("missing_command_body")


class ReceiptKind(str, Enum):
    APPLIED = "applied"
    REJECTED = "rejected"
    ABSENT = "absent"


@dataclass(frozen=True)
class CommandEvidence:
    """A command-specific receipt, never derived from absence of active orders."""
    command: CommandIdentity = field(repr=False)
    kind: ReceiptKind
    observed_at: datetime
    replay_guarantee_until: datetime | None = None

    def __post_init__(self):
        if type(self.command) is not CommandIdentity or type(self.kind) is not ReceiptKind:
            raise InvalidDraft("invalid_command_evidence")
        instant(self.observed_at)
        if self.replay_guarantee_until is not None:
            instant(self.replay_guarantee_until)
            if self.replay_guarantee_until <= self.observed_at:
                raise InvalidDraft("invalid_guarantee_interval")


class CommandDecision(str, Enum):
    RECONCILE = "reconcile"
    IDENTITY_CONFLICT = "identity_conflict"
    READ_EXISTING_RECEIPT = "read_existing_receipt"
    SAME_KEY_REPLAY_CANDIDATE = "same_key_replay_candidate"


def assess_command(original: CommandIdentity | None, proposed: CommandIdentity,
                   evidence: CommandEvidence | None, *, now: datetime,
                   max_evidence_age_sec: int) -> CommandDecision:
    """Do not send anything. CANDIDATE is NOT authorization to execute/retry.

    Only a future authenticated adapter may supply a command-scoped idempotency
    guarantee, renew authorization and verify deadlines atomically at execution.
    No new key is generated; loss of the saved body/key requires reconciliation.
    Bodies are exact serialized bytes; serialization/canonicalization is absent.
    """
    instant(now)
    integer(max_evidence_age_sec, minimum=1)
    if type(proposed) is not CommandIdentity:
        raise InvalidDraft("invalid_command")
    if original is None:
        return CommandDecision.RECONCILE
    if type(original) is not CommandIdentity:
        raise InvalidDraft("invalid_original")
    if original != proposed:
        return CommandDecision.IDENTITY_CONFLICT
    if evidence is None:
        return CommandDecision.RECONCILE
    if type(evidence) is not CommandEvidence:
        raise InvalidDraft("invalid_command_evidence")
    if evidence.command != original:
        return CommandDecision.IDENTITY_CONFLICT
    age = (now - evidence.observed_at).total_seconds()
    if age < 0 or age >= max_evidence_age_sec:
        return CommandDecision.RECONCILE
    if evidence.kind in (ReceiptKind.APPLIED, ReceiptKind.REJECTED):
        return CommandDecision.READ_EXISTING_RECEIPT
    if evidence.replay_guarantee_until is None or now >= evidence.replay_guarantee_until:
        return CommandDecision.RECONCILE
    return CommandDecision.SAME_KEY_REPLAY_CANDIDATE
