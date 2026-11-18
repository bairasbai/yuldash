"""Unconnected pure cooldown proposal, NOT a claim, lease or durable recovery.

A caller would need a fresh locked database snapshot/clock and an exact CAS of
all owner and pending fields. Nothing here clears an obligation or authorizes
matching. Nonnegative revisions are a pre-cutover lab restriction only.
"""

import re
from dataclasses import dataclass
from datetime import datetime, timedelta

INT_MAX = 2**31 - 1
STATUSES = frozenset(
    {
        "scheduled",
        "created",
        "searching",
        "offered",
        "accepted",
        "arriving",
        "onboard",
        "done",
        "cancelled",
        "expired",
    }
)


@dataclass(frozen=True, slots=True)
class Pending:
    revision: int | None
    next_at: datetime | None
    attempts: int | None


@dataclass(frozen=True, slots=True)
class OwnerSnapshot:
    id: int
    status: str
    search_revision: int | None
    tried_lifecycle: str | None
    searching_at: datetime | None
    search_round: int
    driver_id: int | None
    current_offer_driver_id: int | None
    offer_token: str | None
    offered_at: datetime | None
    offer_expires_at: datetime | None
    pending: Pending


@dataclass(frozen=True, slots=True)
class RetryPolicy:
    base_seconds: int
    cap_seconds: int


@dataclass(frozen=True, slots=True)
class ClaimDecision:
    outcome: str
    reason: str
    expected: OwnerSnapshot | None = None
    replacement_pending: Pending | None = None


def _integer(value, minimum=0, maximum=None):
    return (
        type(value) is int
        and value >= minimum
        and (maximum is None or value <= maximum)
    )


def _time(value):
    return type(value) is datetime and value.tzinfo is None


def _hex_or_none(value):
    return value is None or (
        type(value) is str and re.fullmatch(r"[0-9a-f]{32}", value) is not None
    )


def _valid_snapshot(owner, now):
    if type(owner) is not OwnerSnapshot or type(owner.pending) is not Pending:
        return False
    if (
        not _integer(owner.id, 1, INT_MAX)
        or type(owner.status) is not str
        or owner.status not in STATUSES
    ):
        return False
    if owner.search_revision is not None and not _integer(owner.search_revision):
        return False
    if not _integer(owner.search_round, 0, INT_MAX):
        return False
    if any(
        value is not None and not _integer(value, 1, INT_MAX)
        for value in (owner.driver_id, owner.current_offer_driver_id)
    ):
        return False
    if not all(
        _hex_or_none(value) for value in (owner.tried_lifecycle, owner.offer_token)
    ):
        return False
    # Historical event times cannot be future; deadlines may be past or future.
    for value in (owner.searching_at, owner.offered_at):
        if value is not None and (not _time(value) or value > now):
            return False
    if owner.offer_expires_at is not None and not _time(owner.offer_expires_at):
        return False
    if owner.status == "searching" and (
        owner.searching_at is None
        or owner.driver_id is not None
        or owner.current_offer_driver_id is not None
        or owner.offer_expires_at is not None
    ):
        return False
    if owner.status == "offered" and (
        owner.driver_id is not None or owner.current_offer_driver_id is None
    ):
        return False
    pending = owner.pending
    if pending == Pending(None, None, None):
        return True
    return (
        _integer(pending.revision)
        and _time(pending.next_at)
        and _integer(pending.attempts, 0, INT_MAX)
    )


def evaluate_resume_claim(captured, current, now, retry_policy):
    """Return a deterministic proposal; never mutate, acknowledge or do I/O.

    All-null pending is absent; partial tuples are invalid. Offered retains its
    obligation without execution even after expiry. Other statuses never grant
    clear permission. Equality is due. First attempt uses base and returns 1.
    """
    if not _time(now):
        return ClaimDecision("invalid", "clock")
    # Lab resource budget, not a production retry setting: bounded before
    # shifts/timedelta, independent of the caller's integer magnitude.
    if (
        type(retry_policy) is not RetryPolicy
        or not _integer(retry_policy.base_seconds, 1, INT_MAX)
        or not _integer(retry_policy.cap_seconds, retry_policy.base_seconds, INT_MAX)
    ):
        return ClaimDecision("invalid", "retry_policy")
    if not _valid_snapshot(current, now):
        return ClaimDecision("invalid", "current")
    if not _valid_snapshot(captured, now):
        return ClaimDecision("invalid", "captured")
    if captured != current:
        return ClaimDecision("obsolete", "snapshot_changed")
    pending = current.pending
    if pending == Pending(None, None, None):
        return ClaimDecision("not_due", "absent")
    if pending.revision != current.search_revision:
        return ClaimDecision("obsolete", "pending_owner_mismatch")
    if current.status == "offered":
        return ClaimDecision("not_due", "offered_retained")
    if current.status != "searching":
        return ClaimDecision("not_due", "status_ineligible")
    if pending.next_at > now:
        return ClaimDecision("not_due", "cooldown")
    # Bound the shift BEFORE constructing an integer: MAX attempts is cheap.
    threshold = (retry_policy.cap_seconds // retry_policy.base_seconds).bit_length()
    delay = (
        retry_policy.cap_seconds
        if pending.attempts >= threshold
        else min(
            retry_policy.cap_seconds, retry_policy.base_seconds << pending.attempts
        )
    )
    try:
        next_at = now + timedelta(seconds=delay)
    except OverflowError:
        return ClaimDecision("invalid", "deadline_overflow")
    replacement = Pending(pending.revision, next_at, min(pending.attempts + 1, INT_MAX))
    return ClaimDecision("due", "proposal_only", current, replacement)
