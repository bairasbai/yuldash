"""Pure synthetic policy tests: no database claim or recovery guarantee."""

from dataclasses import FrozenInstanceError, fields, replace
from datetime import datetime, timedelta, timezone

import pytest

from app.taxi_resume_policy_lab import (
    INT_MAX,
    ClaimDecision,
    OwnerSnapshot,
    Pending,
    RetryPolicy,
    evaluate_resume_claim,
)

NOW = datetime(2026, 1, 1)
POLICY = RetryPolicy(2, 10)


def owner(**changes):
    value = OwnerSnapshot(
        1,
        "searching",
        20,
        None,
        NOW - timedelta(seconds=10),
        2,
        None,
        None,
        "a" * 32,
        NOW - timedelta(seconds=5),
        None,
        Pending(20, NOW, 0),
    )
    return replace(value, **changes)


def decision(value=None, **kwargs):
    value = owner() if value is None else value
    return evaluate_resume_claim(
        kwargs.pop("captured", value),
        value,
        kwargs.pop("now", NOW),
        kwargs.pop("policy", POLICY),
    )


def test_due_is_only_frozen_deterministic_proposal():
    value = owner()
    result = decision(value)
    assert result == decision(value)
    assert result == ClaimDecision(
        "due", "proposal_only", value, Pending(20, NOW + timedelta(seconds=2), 1)
    )
    assert value.pending == Pending(20, NOW, 0)
    for obj in (value, value.pending, POLICY, result, result.replacement_pending):
        with pytest.raises(FrozenInstanceError):
            setattr(obj, fields(obj)[0].name, None)
    assert (
        decision(
            replace(value, pending=result.replacement_pending), captured=value
        ).outcome
        == "obsolete"
    )


@pytest.mark.parametrize(
    "attempt,delay,new_attempt",
    [
        (0, 2, 1),
        (1, 4, 2),
        (2, 8, 3),
        (3, 10, 4),
        (4, 10, 5),
        (INT_MAX - 1, 10, INT_MAX),
        (INT_MAX, 10, INT_MAX),
    ],
)
def test_backoff_and_saturation(attempt, delay, new_attempt):
    result = decision(owner(pending=Pending(20, NOW, attempt)))
    assert result.replacement_pending == Pending(
        20, NOW + timedelta(seconds=delay), new_attempt
    )


@pytest.mark.parametrize("offset,outcome", [(-1, "due"), (0, "due"), (1, "not_due")])
def test_clock_boundary(offset, outcome):
    assert (
        decision(
            owner(pending=Pending(20, NOW + timedelta(microseconds=offset), 0))
        ).outcome
        == outcome
    )


def test_absent_and_partial_tuple():
    assert decision(owner(pending=Pending(None, None, None))).reason == "absent"
    for value in (
        Pending(None, NOW, 0),
        Pending(20, None, 0),
        Pending(20, NOW, None),
        Pending(None, None, 0),
        Pending(20, None, None),
        Pending(None, NOW, None),
    ):
        assert decision(owner(pending=value)).outcome == "invalid"


@pytest.mark.parametrize(
    "status",
    [
        "scheduled",
        "created",
        "accepted",
        "arriving",
        "onboard",
        "done",
        "cancelled",
        "expired",
    ],
)
def test_ineligible_status_never_permits_clear(status):
    result = decision(owner(status=status))
    assert result == ClaimDecision("not_due", "status_ineligible")


@pytest.mark.parametrize(
    "deadline", [None, NOW - timedelta(days=1), NOW + timedelta(days=1)]
)
@pytest.mark.parametrize("token", [None, "b" * 32])
def test_offered_retained_never_executes(deadline, token):
    result = decision(
        owner(
            status="offered",
            current_offer_driver_id=2,
            offer_token=token,
            offer_expires_at=deadline,
        )
    )
    assert result == ClaimDecision("not_due", "offered_retained")


@pytest.mark.parametrize(
    "field,value",
    [
        ("id", 2),
        ("status", "created"),
        ("search_revision", 21),
        ("tried_lifecycle", "b" * 32),
        ("searching_at", NOW - timedelta(seconds=11)),
        ("search_round", 3),
        ("driver_id", 2),
        ("current_offer_driver_id", 3),
        ("offer_token", "c" * 32),
        ("offered_at", NOW - timedelta(seconds=6)),
        ("offer_expires_at", NOW + timedelta(seconds=9)),
        ("pending", Pending(21, NOW, 0)),
        ("pending", Pending(20, NOW + timedelta(seconds=1), 0)),
        ("pending", Pending(20, NOW, 1)),
    ],
)
def test_each_valid_changed_field_is_obsolete(field, value):
    # Non-searching shape allows independent assignment-field mutation. This
    # tests exact tuple fencing, not permission to execute this status.
    before = owner(status="done")
    after = replace(before, **{field: value})
    assert decision(after, captured=before) == ClaimDecision(
        "obsolete", "snapshot_changed"
    )


@pytest.mark.parametrize(
    "field,value",
    [
        ("id", True),
        ("id", 0),
        ("id", INT_MAX + 1),
        ("search_revision", True),
        ("search_revision", -1),
        ("search_revision", 1.0),
        ("search_round", True),
        ("search_round", -1),
        ("search_round", INT_MAX + 1),
        ("driver_id", False),
        ("driver_id", 0),
        ("current_offer_driver_id", True),
        ("tried_lifecycle", "A" * 32),
        ("tried_lifecycle", ""),
        ("offer_token", "x" * 32),
        ("status", "unknown"),
        ("searching_at", NOW + timedelta(microseconds=1)),
        ("offered_at", NOW + timedelta(microseconds=1)),
        ("searching_at", None),
        ("offer_expires_at", NOW),
        ("driver_id", 2),
        ("current_offer_driver_id", 2),
    ],
)
def test_invalid_current_precedes_changed_snapshot(field, value):
    assert decision(owner(**{field: value}), captured=owner()).reason == "current"


@pytest.mark.parametrize("field", ["searching_at", "offered_at", "offer_expires_at"])
def test_aware_owner_times_rejected(field):
    assert (
        decision(owner(**{field: NOW.replace(tzinfo=timezone.utc)})).outcome
        == "invalid"
    )


@pytest.mark.parametrize(
    "pending",
    [
        Pending(True, NOW, 0),
        Pending(-1, NOW, 0),
        Pending(20, NOW, True),
        Pending(20, NOW, -1),
        Pending(20, NOW, INT_MAX + 1),
        Pending(20, NOW, 0.0),
        Pending(20, NOW.replace(tzinfo=timezone.utc), 0),
    ],
)
def test_invalid_pending(pending):
    assert decision(owner(pending=pending)).outcome == "invalid"


@pytest.mark.parametrize(
    "policy",
    [
        RetryPolicy(True, 10),
        RetryPolicy(1, True),
        RetryPolicy(0, 10),
        RetryPolicy(2, 1),
        RetryPolicy(1, 1.5),
        None,
    ],
)
def test_invalid_policy(policy):
    assert decision(policy=policy).reason == "retry_policy"


def test_legacy_none_token_lifecycle_and_exact_huge_revision():
    huge = 10**100 + 1
    result = decision(
        owner(search_revision=huge, offer_token=None, pending=Pending(huge, NOW, 0))
    )
    assert result.outcome == "due" and result.replacement_pending.revision == huge
    assert (
        decision(owner(search_revision=None, pending=Pending(None, None, None))).reason
        == "absent"
    )
    assert decision(owner(search_revision=None)).reason == "pending_owner_mismatch"


def test_malformed_captured_and_clock():
    assert decision(captured=owner(id=False)).reason == "captured"
    assert decision(now=NOW.replace(tzinfo=timezone.utc)).reason == "clock"
    assert decision(now="2026-01-01").reason == "clock"


def test_datetime_overflow_no_clamp_or_proposal():
    result = decision(owner(pending=Pending(20, datetime.max, 0)), now=datetime.max)
    assert result == ClaimDecision("invalid", "deadline_overflow")


def test_unity_cap_and_non_power_of_two_cap():
    assert decision(
        policy=RetryPolicy(3, 3)
    ).replacement_pending.next_at == NOW + timedelta(seconds=3)
    assert decision(
        owner(pending=Pending(20, NOW, INT_MAX)), policy=RetryPolicy(3, 7)
    ).replacement_pending.next_at == NOW + timedelta(seconds=7)


@pytest.mark.parametrize(
    "policy",
    [
        RetryPolicy(1, INT_MAX + 1),
        RetryPolicy(INT_MAX + 1, INT_MAX + 1),
        RetryPolicy(1, 1 << 100000),
        RetryPolicy(1 << 100000, 1 << 100000),
    ],
)
def test_policy_seconds_resource_budget_rejected_before_arithmetic(policy):
    assert decision(policy=policy) == ClaimDecision("invalid", "retry_policy")


def test_policy_seconds_budget_boundary():
    result = decision(policy=RetryPolicy(INT_MAX, INT_MAX))
    assert result.replacement_pending.next_at == NOW + timedelta(seconds=INT_MAX)
