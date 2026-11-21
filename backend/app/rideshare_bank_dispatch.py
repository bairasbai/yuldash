"""Commit an authorized bank request before sending it, then reconcile its result."""

from dataclasses import dataclass, field
from datetime import datetime, timedelta
from uuid import uuid4

from cryptography.fernet import InvalidToken
from sqlalchemy import and_, or_
from sqlalchemy.engine import Engine
from sqlmodel import Session, select

from .booking_payment import lock_booking_payment
from .rideshare_bank_accounting import _lock_checkout, _successful_provider_id, record_bank_observation
from .rideshare_bank_booking import (
    CARD_RECEIPT_METHOD,
    BankCheckoutUnavailable,
    BankRuntime,
    BankSwitchRevision,
    bank_switches,
    check_bank_charge,
    checked_bank_booking,
    lock_booking_participants,
)
from .rideshare_bank_models import BANK_ACTIVE_STATES, RideshareBankApproval, RideshareBankCheckout, RideshareBankOperation
from .rideshare_bank_provider import (
    BankObservation,
    BankOutcomeUnknown,
    BankRequest,
    BankRequestRejected,
    BankResource,
    YooKassaSafeDeal,
    bank_notification,
    prepare_deal,
    prepare_payment,
    prepare_payout,
    prepare_refund,
)
from .rideshare_bank_recipient import BankRecipientUnavailable, bank_recipient_token
from .rideshare_bank_settlement import lock_bank_settlement
from .timeutil import utcnow


# Keep a full send lease in reserve before the provider's idempotency deadline.
_LEASE = timedelta(seconds=60)
_PROVIDER_KEY_LIFETIME = timedelta(hours=24)
_BANK_CLOCK_MARGIN = timedelta(minutes=5)


@dataclass(frozen=True)
class BankDispatch:
    checkout_id: int
    operation_id: str
    lease_token: str
    send_before: datetime
    request: BankRequest = field(repr=False)


def _next_operation(session: Session, checkout: RideshareBankCheckout) -> RideshareBankOperation | None:
    operation = session.exec(select(RideshareBankOperation).where(
        RideshareBankOperation.checkout_id == checkout.id, RideshareBankOperation.state.in_(BANK_ACTIVE_STATES),
    ).with_for_update().execution_options(populate_existing=True)).one_or_none()
    if operation is not None:
        return operation
    deal_id = _successful_provider_id(session, checkout.id, BankResource.deal)
    prior_payment = session.exec(select(RideshareBankOperation.id).where(
        RideshareBankOperation.checkout_id == checkout.id, RideshareBankOperation.resource == BankResource.payment.value,
    ).limit(1)).first()
    if deal_id is None or prior_payment is not None:
        return None
    operation = RideshareBankOperation(checkout_id=checkout.id, resource=BankResource.payment.value,
                                      amount_kop=checkout.amount_kop, settlement_kop=checkout.payout_kop)
    session.add(operation)
    session.flush()
    return operation


def _payout_token(session: Session, driver_id: int, checkout: RideshareBankCheckout, runtime: BankRuntime) -> str:
    try:
        return bank_recipient_token(session, driver_id, checkout.recipient_version, cipher=runtime.cipher)
    except BankRecipientUnavailable:
        raise BankCheckoutUnavailable("bank_recipient_changed") from None


def _prepare_request(session: Session, runtime: BankRuntime, checkout: RideshareBankCheckout,
                     operation: RideshareBankOperation, payout_token: str | None) -> BankRequest:
    resource = BankResource(operation.resource)
    prefix = f"{operation.id}\0{resource.value}\0".encode("ascii")
    if operation.request_ciphertext is not None:
        try:
            envelope = runtime.cipher.decrypt(operation.request_ciphertext)
        except InvalidToken:
            raise BankCheckoutUnavailable("bank_request_unavailable") from None
        if not envelope.startswith(prefix):
            raise BankCheckoutUnavailable("bank_request_unavailable")
        return BankRequest(resource, operation.id, envelope[len(prefix):])
    if operation.dispatched_at is not None:
        raise BankCheckoutUnavailable("bank_request_unavailable")

    if resource == BankResource.deal:
        request = prepare_deal(operation.id, operation.id)
    else:
        deal_id = _successful_provider_id(session, checkout.id, BankResource.deal)
        if deal_id is None:
            raise BankCheckoutUnavailable("bank_deal_unavailable")
        if resource == BankResource.payment:
            request = prepare_payment(operation.id, operation.id, deal_id=deal_id, amount_kop=operation.amount_kop,
                                      payout_kop=operation.settlement_kop, return_url=runtime.return_url(checkout.booking_id))
        elif resource == BankResource.refund:
            payment_id = _successful_provider_id(session, checkout.id, BankResource.payment)
            if payment_id is None:
                raise BankCheckoutUnavailable("bank_payment_unavailable")
            reduction = None if operation.amount_kop == checkout.amount_kop else operation.settlement_kop
            request = prepare_refund(operation.id, operation.id, payment_id=payment_id,
                                     amount_kop=operation.amount_kop, payout_reduction_kop=reduction)
        else:
            request = prepare_payout(operation.id, operation.id, deal_id=deal_id, amount_kop=operation.amount_kop,
                                     payout_token=payout_token)
    operation.request_ciphertext = runtime.cipher.encrypt(prefix + request.body)
    return request


def _send_window(operation: RideshareBankOperation) -> tuple[datetime, datetime] | None:
    if operation.provider_id is not None or operation.state not in BANK_ACTIVE_STATES:
        return None
    now = utcnow()
    if operation.lease_until is not None and operation.lease_until > now:
        return None
    cutoff = (operation.dispatched_at or now) + _PROVIDER_KEY_LIFETIME - _LEASE
    if now >= cutoff:
        operation.state = "needs_reconciliation"
        return None
    return now, min(now + _LEASE, cutoff)


def _admit_request(session: Session, runtime: BankRuntime, checkout: RideshareBankCheckout,
                   operation: RideshareBankOperation, window: tuple[datetime, datetime],
                   payout_token: str | None = None) -> BankDispatch:
    now, send_before = window
    request = _prepare_request(session, runtime, checkout, operation, payout_token)
    operation.dispatched_at = operation.dispatched_at or now
    operation.state = "dispatched"
    operation.lease_token = uuid4().hex
    operation.lease_until = now + _LEASE
    session.flush()
    return BankDispatch(checkout.id, operation.id, operation.lease_token, send_before, request)


def claim_bank_dispatch(session: Session, checkout_id: int, *, runtime: BankRuntime) -> BankDispatch | None:
    """Admit at most one POST. The lease is usable only after the caller commits."""
    runtime.check_environment()
    before = session.get(RideshareBankCheckout, checkout_id, populate_existing=True)
    if before is None or before.booking_id is None:
        raise BankCheckoutUnavailable("bank_booking_unavailable")
    booking_id = before.booking_id
    passenger, driver = lock_booking_participants(session, booking_id)
    revisions = bank_switches(session, lock=True)
    booking = checked_bank_booking(session, booking_id, passenger, driver)
    checkout = _lock_checkout(session, checkout_id)
    if checkout.booking_id != booking_id or checkout.closed_at is not None or checkout.test_mode != runtime.test_mode:
        raise BankCheckoutUnavailable("bank_checkout_changed")
    if revisions != BankSwitchRevision(checkout.paid_revision, checkout.card_revision):
        raise BankCheckoutUnavailable("bank_confirmation_required")
    operation = _next_operation(session, checkout)
    if operation is None or operation.resource not in (BankResource.deal.value, BankResource.payment.value):
        return None
    window = _send_window(operation)
    if window is None:
        return None
    terms = check_bank_charge(session, booking, driver, checkout.recipient_version, runtime)
    if terms.amount_kop != checkout.amount_kop or terms.driver_consent_at != checkout.driver_consent_at:
        raise BankCheckoutUnavailable("bank_checkout_changed")
    return _admit_request(session, runtime, checkout, operation, window)


def _claim_bank_settlement(session: Session, checkout_id: int, operation_id: str, *,
                           runtime: BankRuntime) -> BankDispatch | None:
    operation = session.get(RideshareBankOperation, operation_id)
    approval = session.get(RideshareBankApproval, operation_id)
    if (operation is None or operation.checkout_id != checkout_id or
            operation.resource not in (BankResource.refund.value, BankResource.payout.value) or
            approval is None or approval.approved_by is None):
        return None
    owner_id, resource = approval.approved_by, BankResource(operation.resource)
    context = lock_bank_settlement(session, checkout_id, resource, owner_id, runtime=runtime)
    operation = session.get(RideshareBankOperation, operation_id, with_for_update=True, populate_existing=True)
    approval = session.get(RideshareBankApproval, operation_id, with_for_update=True, populate_existing=True)
    if (operation is None or operation.checkout_id != checkout_id or operation.resource != resource.value
            or approval is None or approval.approved_by != owner_id):
        return None
    window = _send_window(operation)
    if window is None:
        return None
    payout_token = None
    if resource == BankResource.payout:
        if context.payout_revisions() != BankSwitchRevision(approval.paid_revision, approval.card_revision):
            raise BankCheckoutUnavailable("bank_confirmation_required")
        if operation.dispatched_at is None:
            payout_token = _payout_token(session, context.driver.id, context.checkout, runtime)
    return _admit_request(session, runtime, context.checkout, operation, window, payout_token)



def _apply_bank_result(session: Session, checkout_id: int, operation_id: str, runtime: BankRuntime,
                       observation: BankObservation | None, *, lease_token: str | None = None) -> str | None:
    # A concurrently deleted booking is detached by FK. Its bank obligation remains.
    snapshot = session.get(RideshareBankCheckout, checkout_id)
    if snapshot is None:
        return None
    booking_id = snapshot.booking_id
    booking = lock_booking_payment(session, booking_id) if booking_id is not None else None
    checkout = _lock_checkout(session, checkout_id)
    if checkout.booking_id != (booking.id if booking is not None else None) or checkout.test_mode != runtime.test_mode:
        raise BankCheckoutUnavailable("bank_checkout_changed")
    operation = session.get(RideshareBankOperation, operation_id, with_for_update=True, populate_existing=True)
    if (operation is None or operation.checkout_id != checkout.id or
            (lease_token is not None and operation.lease_token != lease_token)):
        return None
    if observation is None:
        if operation.state == "dispatched":
            operation.state = "needs_reconciliation"
    else:
        operation = record_bank_observation(session, checkout_id, operation_id, observation)
        if operation.resource == BankResource.payment.value:
            if operation.state == "succeeded" and booking is not None:
                if booking.paid and booking.payment_method != CARD_RECEIPT_METHOD:
                    checkout.receipt_conflict = True
                else:
                    booking.paid = True
                    booking.payment_method = CARD_RECEIPT_METHOD
            if operation.state == "pending" and observation.checkout_url is not None:
                operation.confirmation_ciphertext = runtime.cipher.encrypt(observation.checkout_url.encode("utf-8"))
            elif operation.state in {"succeeded", "canceled", "rejected"}:
                operation.confirmation_ciphertext = None
    operation.lease_token = operation.lease_until = None
    return operation.state


def _check_provider(runtime: BankRuntime, provider: YooKassaSafeDeal) -> None:
    runtime.check_environment()
    if runtime.test_mode != provider.test_mode:
        raise BankCheckoutUnavailable("bank_provider_environment")


def dispatch_bank_operation(engine: Engine, checkout_id: int, *, runtime: BankRuntime,
                            provider: YooKassaSafeDeal, settlement_operation_id: str | None = None) -> str | None:
    _check_provider(runtime, provider)
    with Session(engine) as session:
        if settlement_operation_id is None:
            claim = claim_bank_dispatch(session, checkout_id, runtime=runtime)
        else:
            claim = _claim_bank_settlement(session, checkout_id, settlement_operation_id, runtime=runtime)
        session.commit()
    if claim is None:
        return None
    # The commit granted admission. Fence stale workers and check the deadline just before HTTP.
    with Session(engine) as session:
        owner = session.get(RideshareBankOperation, claim.operation_id)
        if owner is None or owner.lease_token != claim.lease_token or owner.provider_id is not None:
            return None
    if utcnow() >= claim.send_before:
        return None
    try:
        observation = provider.submit(claim.request)
    except (BankOutcomeUnknown, BankRequestRejected):
        observation = None
    with Session(engine) as session:
        result = _apply_bank_result(session, checkout_id, claim.operation_id, runtime, observation,
                                    lease_token=claim.lease_token)
        session.commit()
        return result


def reconcile_bank_operation(engine: Engine, checkout_id: int, operation_id: str, *,
                             runtime: BankRuntime, provider: YooKassaSafeDeal,
                             search_unknown: bool = False) -> str | None:
    """Read an already admitted object, including while the switches are paused."""
    _check_provider(runtime, provider)
    with Session(engine) as session:
        checkout = session.get(RideshareBankCheckout, checkout_id)
        operation = session.get(RideshareBankOperation, operation_id)
        if (checkout is None or checkout.test_mode != runtime.test_mode or
                operation is None or operation.checkout_id != checkout_id or operation.dispatched_at is None):
            return None
        resource, provider_id = BankResource(operation.resource), operation.provider_id
        if provider_id is None:
            if (not search_unknown or operation.state not in BANK_ACTIVE_STATES or
                    operation.request_ciphertext is None):
                return None
            first_dispatch = operation.dispatched_at
            payment_id = None
            if resource == BankResource.refund:
                payment_id = _successful_provider_id(session, checkout_id, BankResource.payment)
                if payment_id is None:
                    raise BankCheckoutUnavailable("bank_payment_unavailable")
    if provider_id is None:
        provider_id = provider.find_operation(
            resource, operation_id, since=first_dispatch - _BANK_CLOCK_MARGIN,
            until=min(utcnow(), first_dispatch + _PROVIDER_KEY_LIFETIME) + _BANK_CLOCK_MARGIN, payment_id=payment_id,
        )
        if provider_id is None:
            return None
    return _fetch_bank_result(engine, checkout_id, operation_id, resource, provider_id,
                              runtime=runtime, provider=provider)


def reconcile_bank_notification(engine: Engine, payload: object, *, runtime: BankRuntime,
                                provider: YooKassaSafeDeal) -> str | None:
    """Verify a notified object after the ingress has admitted the notification."""
    _check_provider(runtime, provider)
    hint = bank_notification(payload)
    if hint is None:
        return None
    with Session(engine) as session:
        candidate = RideshareBankOperation.provider_id == hint.provider_id
        if hint.operation_id is not None:
            candidate = or_(candidate, and_(
                RideshareBankOperation.id == hint.operation_id,
                RideshareBankOperation.provider_id.is_(None),
                RideshareBankOperation.state.in_(BANK_ACTIVE_STATES),
                RideshareBankOperation.request_ciphertext.is_not(None),
            ))
        matches = session.exec(
            select(RideshareBankOperation.id, RideshareBankOperation.checkout_id)
            .join(RideshareBankCheckout, RideshareBankCheckout.id == RideshareBankOperation.checkout_id)
            .where(RideshareBankCheckout.test_mode == runtime.test_mode,
                   RideshareBankOperation.resource == hint.resource.value,
                   RideshareBankOperation.dispatched_at.is_not(None),
                   RideshareBankOperation.state != "queued", candidate)
            .limit(2),
        ).all()
        if len(matches) != 1:
            return None
        operation_id, checkout_id = matches[0]
        if hint.operation_id is not None and operation_id != hint.operation_id:
            return None
    return _fetch_bank_result(engine, checkout_id, operation_id, hint.resource, hint.provider_id,
                              runtime=runtime, provider=provider)


def _fetch_bank_result(engine: Engine, checkout_id: int, operation_id: str,
                       resource: BankResource, provider_id: str, *,
                       runtime: BankRuntime, provider: YooKassaSafeDeal) -> str | None:
    observation = provider.fetch(resource, provider_id)
    if resource == BankResource.deal and observation.status == "closed":
        raise BankOutcomeUnknown("Closed bank deal requires reconciliation")
    with Session(engine) as session:
        state = _apply_bank_result(session, checkout_id, operation_id, runtime, observation)
        session.commit()
        return state
