"""Ordinary handover fixture checks. NOT authorization or assignment mutation."""
from dataclasses import dataclass, field
from enum import Enum

from .contracts import InvalidDraft, flag, identifier, integer


class AssignmentState(str, Enum):
    ACTIVE = "active"
    READY = "ready"
    ENDED = "ended"
    BLOCKED = "blocked"


@dataclass(frozen=True)
class Assignment:
    assignment_ref: str
    fleet_ref: str
    vehicle_ref: str
    driver_ref: str = field(repr=False)
    revision: int
    state: AssignmentState

    def __post_init__(self):
        for value in (self.assignment_ref, self.fleet_ref, self.vehicle_ref, self.driver_ref):
            identifier(value)
        integer(self.revision)
        if type(self.state) is not AssignmentState:
            raise InvalidDraft("invalid_assignment_state")


@dataclass(frozen=True)
class HandoverEvidence:
    """Server adapter obligations, NOT trusted user checkboxes.

    Signatures, role scopes, expiry, freshness and atomic DB checks remain absent.
    Do NOT construct this directly from an API request or use as authorization.
    """
    old_revision: int
    new_revision: int
    record_revision: int
    old_ack_revision: int
    new_ack_revision: int
    fleet_authorized: bool
    driver_eligible: bool
    vehicle_eligible: bool
    vehicle_hold: bool
    old_driver_has_live_order: bool
    new_driver_has_live_order: bool
    vehicle_has_live_order: bool
    new_driver_has_other_vehicle: bool

    def __post_init__(self):
        for name in ("old_revision", "new_revision", "record_revision",
                     "old_ack_revision", "new_ack_revision"):
            integer(getattr(self, name))
        for name in ("fleet_authorized", "driver_eligible", "vehicle_eligible",
                     "vehicle_hold", "old_driver_has_live_order", "new_driver_has_live_order",
                     "vehicle_has_live_order", "new_driver_has_other_vehicle"):
            flag(getattr(self, name))


def check_ordinary_handover(old: Assignment, new: Assignment,
                           evidence: HandoverEvidence) -> tuple[str, ...]:
    """Zero issues only means fixture consistency, NOT permission or success.

    Live order means any reserved/offered/accepted/arriving/onboard obligation.
    Exceptional custody recovery, return without successor, rent and debt transfers
    are deliberately unsupported. Never replace a driver inside a live order.
    """
    issues = []
    if old.assignment_ref == new.assignment_ref or old.driver_ref == new.driver_ref:
        issues.append("not_a_driver_handover")
    if (old.fleet_ref, old.vehicle_ref) != (new.fleet_ref, new.vehicle_ref):
        issues.append("vehicle_or_fleet_mismatch")
    if old.state is not AssignmentState.ACTIVE or new.state is not AssignmentState.READY:
        issues.append("invalid_handover_state")
    if (old.revision, new.revision) != (evidence.old_revision, evidence.new_revision):
        issues.append("stale_assignment")
    if not evidence.old_ack_revision == evidence.new_ack_revision == evidence.record_revision:
        issues.append("record_not_confirmed")
    if not evidence.fleet_authorized:
        issues.append("fleet_permission_missing")
    if not evidence.driver_eligible or not evidence.vehicle_eligible or evidence.vehicle_hold:
        issues.append("eligibility_blocked")
    if (evidence.old_driver_has_live_order or evidence.new_driver_has_live_order
            or evidence.vehicle_has_live_order or evidence.new_driver_has_other_vehicle):
        issues.append("occupied")
    return tuple(issues)
