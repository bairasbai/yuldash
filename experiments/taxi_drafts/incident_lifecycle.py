"""Synthetic incident journal projection, never an emergency service or dispatch."""
from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum

from .contracts import InvalidDraft, identifier, instant, integer


def _ref(value: str) -> None:
    identifier(value)
    if not value.strip():
        raise InvalidDraft("blank_reference")


@dataclass(frozen=True)
class IncidentScope:
    incident_ref: str = field(repr=False)
    order_ref: str = field(repr=False)
    reporter_ref: str = field(repr=False)
    responsible_channel_ref: str = field(repr=False)

    def __post_init__(self):
        for value in (self.incident_ref, self.order_ref, self.reporter_ref, self.responsible_channel_ref):
            _ref(value)


class IncidentKind(str, Enum):
    ACCEPTED = "accepted"
    DELIVERY_CONFIRMED = "delivery_confirmed"
    DELIVERY_FAILED = "delivery_failed"
    TAKEN_IN_WORK = "taken_in_work"
    CLOSED = "closed"
    REOPENED = "reopened"


class IncidentReason(str, Enum):
    RESPONDER_REPORT_COMPLETE = "responder_report_complete"
    REQUEST_WITHDRAWN = "request_withdrawn"
    CONTACT_UNAVAILABLE = "contact_unavailable"
    DUPLICATE = "duplicate"
    FOLLOWUP_REQUESTED = "followup_requested"


@dataclass(frozen=True)
class IncidentEvent:
    event_ref: str = field(repr=False)
    scope: IncidentScope
    revision: int
    kind: IncidentKind
    occurred_at: datetime
    evidence_ref: str = field(repr=False)
    actor_ref: str | None = field(default=None, repr=False)
    channel_ref: str | None = field(default=None, repr=False)
    reason: IncidentReason | None = None

    def __post_init__(self):
        _ref(self.event_ref)
        _ref(self.evidence_ref)
        integer(self.revision, minimum=1)
        instant(self.occurred_at)
        if type(self.scope) is not IncidentScope or type(self.kind) is not IncidentKind:
            raise InvalidDraft("invalid_incident_event")
        if self.kind in (IncidentKind.TAKEN_IN_WORK, IncidentKind.CLOSED, IncidentKind.REOPENED):
            _ref(self.actor_ref)
        elif self.actor_ref is not None:
            raise InvalidDraft("unexpected_actor")
        if self.kind in (IncidentKind.DELIVERY_CONFIRMED, IncidentKind.DELIVERY_FAILED):
            _ref(self.channel_ref)
        elif self.channel_ref is not None:
            raise InvalidDraft("unexpected_channel")
        if self.kind in (IncidentKind.CLOSED, IncidentKind.REOPENED):
            if type(self.reason) is not IncidentReason:
                raise InvalidDraft("audit_reason_required")
            if (self.kind is IncidentKind.REOPENED) != (self.reason is IncidentReason.FOLLOWUP_REQUESTED):
                raise InvalidDraft("invalid_audit_reason")
        elif self.reason is not None:
            raise InvalidDraft("unexpected_reason")


class IncidentState(str, Enum):
    NOT_CONFIRMED = "not_confirmed"
    ACCEPTED = "accepted"
    DELIVERED = "delivered"
    IN_WORK = "in_work"
    CLOSED = "closed"


@dataclass(frozen=True)
class IncidentProjection:
    state: IncidentState | None
    issues: tuple[str, ...]
    delivery_failure_count: int | None
    owner_ref: str | None = field(repr=False)
    closure_reason: IncidentReason | None
    limitation: str = "Synthetic only. Neither delivery nor closure proves assistance."


def project_incident(events: tuple[IncidentEvent, ...], *, expected_scope: IncidentScope,
                     as_of: datetime) -> IncidentProjection:
    """Complete revisioned fixtures only; no network, timers, calls or side effects.

    Authentication, evidence provenance, ownership permissions, real delivery,
    staffing and closure verification are NOT_IMPLEMENTED. Existing backend
    handled is not automatically CLOSED. No claim of help being sent is emitted.
    """
    instant(as_of)
    if type(events) is not tuple or type(expected_scope) is not IncidentScope:
        raise InvalidDraft("invalid_incident_input")
    by_id, revisions, issues = {}, {}, set()
    for event in events:
        if type(event) is not IncidentEvent:
            raise InvalidDraft("invalid_incident_event")
        if event.scope != expected_scope:
            issues.add("scope_mismatch")
        if event.occurred_at > as_of:
            issues.add("future_event")
        if event.channel_ref is not None and event.channel_ref != expected_scope.responsible_channel_ref:
            issues.add("wrong_responsible_channel")
        if event.event_ref in by_id and by_id[event.event_ref] != event:
            issues.add("event_identity_conflict")
        if event.revision in revisions and revisions[event.revision] != event:
            issues.add("revision_conflict")
        by_id[event.event_ref] = event
        revisions[event.revision] = event
    if sorted(revisions) != list(range(1, len(revisions)+1)):
        issues.add("incomplete_revision_chain")
    if issues:
        return IncidentProjection(None, tuple(sorted(issues)), None, None, None)
    state, owner, closure, failures, previous_time = IncidentState.NOT_CONFIRMED, None, None, 0, None
    for revision in sorted(revisions):
        event = revisions[revision]
        if previous_time is not None and event.occurred_at < previous_time:
            issues.add("non_monotonic_time")
        previous_time = event.occurred_at
        if event.kind is IncidentKind.ACCEPTED and state is IncidentState.NOT_CONFIRMED:
            state = IncidentState.ACCEPTED
        elif event.kind in (IncidentKind.DELIVERY_CONFIRMED, IncidentKind.DELIVERY_FAILED) and state is not IncidentState.NOT_CONFIRMED:
            if event.kind is IncidentKind.DELIVERY_FAILED:
                failures += 1  # Attempt observation, not proof that every channel failed.
            elif state is IncidentState.ACCEPTED:
                state = IncidentState.DELIVERED
        elif event.kind is IncidentKind.TAKEN_IN_WORK and state is IncidentState.DELIVERED:
            state, owner = IncidentState.IN_WORK, event.actor_ref
        elif event.kind is IncidentKind.CLOSED and state is IncidentState.IN_WORK and event.actor_ref == owner:
            state, closure = IncidentState.CLOSED, event.reason
        elif event.kind is IncidentKind.REOPENED and state is IncidentState.CLOSED and event.actor_ref == owner:
            state, closure = IncidentState.IN_WORK, None
        else:
            issues.add("unsupported_transition_or_owner")
    if issues:
        return IncidentProjection(None, tuple(sorted(issues)), None, None, None)
    return IncidentProjection(state, (), failures, owner, closure)
