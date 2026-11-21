"""Process a bounded batch using the bank dispatcher and its durable authorization."""

from dataclasses import dataclass
from datetime import datetime, timedelta

from sqlalchemy import and_, case, or_, update
from sqlalchemy.engine import Engine
from sqlmodel import Session, select

from .models import Booking, BookingStatus
from .rideshare_bank_accounting import BankSettlementUnavailable
from .rideshare_bank_booking import BankCheckoutUnavailable, BankRuntime
from .rideshare_bank_dispatch import (
    PROVIDER_KEY_LIFETIME,
    _check_provider,
    dispatch_bank_operation,
    inspect_bank_operation,
)
from .rideshare_bank_models import BANK_ACTIVE_STATES, RideshareBankCheckout, RideshareBankOperation
from .rideshare_bank_provider import BankOutcomeUnknown, BankResource, YooKassaSafeDeal
from .timeutil import utcnow


_CHECK_INTERVAL = timedelta(minutes=1)
_EXPIRED_CHECK_INTERVAL = timedelta(minutes=15)
_MAX_BATCH = 20


@dataclass(frozen=True)
class BankQueueResult:
    checkout_id: int
    state: str | None


def claim_due_bank_checkout(engine: Engine, *, test_mode: bool, ready_before: datetime,
                            checkout_id: int | None = None, provider_account_id: str | None = None) -> int | None:
    """Commit the next poll time before any authorization locks or provider calls."""
    if not test_mode and provider_account_id is None:
        return None
    checkout, operation = RideshareBankCheckout, RideshareBankOperation
    scope = and_(checkout.test_mode == test_mode, checkout.provider_account_id == provider_account_id)
    may_charge = select(Booking.id).where(
        Booking.id == checkout.booking_id, Booking.status == BookingStatus.done, Booking.paid.is_(False),
    ).correlate(checkout).exists()
    active = select(operation.id).where(
        operation.checkout_id == checkout.id, operation.state.in_(BANK_ACTIVE_STATES),
        or_(operation.dispatched_at.is_not(None), and_(
            checkout.closed_at.is_(None),
            or_(operation.resource == BankResource.refund.value,
                and_(checkout.booking_id.is_not(None), operation.resource == BankResource.payout.value),
                may_charge),
        )),
    ).correlate(checkout).exists()
    opened_deal = select(operation.id).where(
        operation.checkout_id == checkout.id, operation.resource == BankResource.deal.value,
        operation.state == "succeeded",
    ).correlate(checkout).exists()
    prior_payment = select(operation.id).where(
        operation.checkout_id == checkout.id, operation.resource == BankResource.payment.value,
    ).correlate(checkout).exists()
    due = or_(checkout.next_bank_check_at.is_(None), checkout.next_bank_check_at <= ready_before)
    candidates = select(checkout.id).where(
        scope, due,
        or_(active, and_(checkout.closed_at.is_(None), may_charge, opened_deal, ~prior_payment)),
    ).order_by(checkout.next_bank_check_at.asc().nulls_first(), checkout.id).limit(1)
    if checkout_id is not None:
        candidates = candidates.where(checkout.id == checkout_id)
    if engine.dialect.name == "postgresql":
        candidates = candidates.with_for_update(skip_locked=True, of=checkout)
    now = utcnow()
    expired_unknown = select(operation.id).where(
        operation.checkout_id == checkout.id, operation.state.in_(BANK_ACTIVE_STATES),
        operation.provider_id.is_(None), operation.dispatched_at <= now - PROVIDER_KEY_LIFETIME,
    ).correlate(checkout).exists()
    claim = update(checkout).where(
        checkout.id == candidates.correlate(None).scalar_subquery(), scope, due,
    ).values(next_bank_check_at=case(
        (expired_unknown, now + _EXPIRED_CHECK_INTERVAL), else_=now + _CHECK_INTERVAL,
    )).returning(checkout.id)
    with Session(engine) as session:
        checkout_id = session.execute(claim).scalar_one_or_none()
        session.commit()
    return checkout_id


def _process_checkout(engine: Engine, checkout_id: int, *, runtime: BankRuntime,
                      provider: YooKassaSafeDeal) -> str | None:
    with Session(engine) as session:
        active = session.exec(select(RideshareBankOperation).where(
            RideshareBankOperation.checkout_id == checkout_id,
            RideshareBankOperation.state.in_(BANK_ACTIVE_STATES),
        )).one_or_none()
    if active is None:
        return dispatch_bank_operation(engine, checkout_id, runtime=runtime, provider=provider)
    if active.dispatched_at is not None:
        result = inspect_bank_operation(engine, checkout_id, active.id, runtime=runtime, provider=provider,
                                        search_unknown=True)
        if not result.missing_after_complete_lookup:
            return result.state
    settlement = active.resource in (BankResource.refund.value, BankResource.payout.value)
    return dispatch_bank_operation(
        engine, checkout_id, runtime=runtime, provider=provider,
        settlement_operation_id=active.id if settlement else None,
        expected_operation_id=None if settlement else active.id,
    )


def run_bank_queue(engine: Engine, *, runtime: BankRuntime, provider: YooKassaSafeDeal,
                   limit: int = _MAX_BATCH, checkout_id: int | None = None) -> list[BankQueueResult]:
    """Give each due checkout one step. A paused or uncertain item does not block the batch."""
    if type(limit) is not int or not 1 <= limit <= _MAX_BATCH:
        raise ValueError("Invalid bank queue batch size")
    runtime.require_writes()
    _check_provider(runtime, provider)
    ready_before = utcnow()
    results = []
    for _ in range(limit):
        claimed = claim_due_bank_checkout(engine, test_mode=runtime.test_mode,
                                          ready_before=ready_before, checkout_id=checkout_id,
                                          provider_account_id=runtime.provider_account_id)
        if claimed is None:
            break
        try:
            state = _process_checkout(engine, claimed, runtime=runtime, provider=provider)
        except BankCheckoutUnavailable:
            state = "blocked"
        except (BankOutcomeUnknown, BankSettlementUnavailable):
            state = "unconfirmed"
        results.append(BankQueueResult(claimed, state))
    return results
