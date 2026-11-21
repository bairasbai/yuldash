"""Authorize a rideshare refund or payout using the owner's current Telegram session."""

from dataclasses import dataclass
from uuid import UUID

from fastapi.security import HTTPAuthorizationCredentials
from sqlmodel import Session, select

from .models import Booking, User, UserRole
from .owner_switches import paid_owner
from .rideshare_bank_accounting import _lock_checkout, reserve_settlement
from .rideshare_bank_booking import (
    CARD_RECEIPT_METHOD,
    BankCheckoutUnavailable,
    BankRuntime,
    BankSwitchRevision,
    bank_switches,
    booking_participant_ids,
    checked_bank_booking,
)
from .rideshare_bank_models import BANK_ACTIVE_STATES, RideshareBankApproval, RideshareBankCheckout, RideshareBankOperation
from .rideshare_bank_provider import BankResource
from .security import current_user, lock_authenticated_user, lock_refresh_user
from .timeutil import utcnow


@dataclass(frozen=True)
class BankSettlement:
    checkout: RideshareBankCheckout
    booking: Booking | None
    driver: User | None
    revisions: BankSwitchRevision | None

    def payout_revisions(self) -> BankSwitchRevision:
        if self.revisions is None:
            raise BankCheckoutUnavailable("bank_paused")
        if (self.booking is None or self.driver is None or self.checkout.receipt_conflict
                or not self.booking.paid or self.booking.payment_method != CARD_RECEIPT_METHOD):
            raise BankCheckoutUnavailable("bank_payment_unavailable")
        return self.revisions


def lock_bank_settlement(session: Session, checkout_id: int, resource: BankResource, owner_id: int, *,
                         runtime: BankRuntime, credentials: HTTPAuthorizationCredentials | None = None) -> BankSettlement:
    runtime.check_environment()
    if credentials is not None and current_user(credentials, session).id != owner_id:
        raise BankCheckoutUnavailable("bank_owner_required")
    before = session.get(RideshareBankCheckout, checkout_id, populate_existing=True)
    if before is None or before.test_mode != runtime.test_mode:
        raise BankCheckoutUnavailable("bank_checkout_changed")
    booking_id = before.booking_id
    participants = booking_participant_ids(session, booking_id) if booking_id is not None else ()
    users = {user_id: lock_refresh_user(session, user_id) for user_id in sorted({owner_id, *participants})}
    owner = users[owner_id]
    if (owner is None or owner.role != UserRole.admin or not paid_owner(owner)):
        raise BankCheckoutUnavailable("bank_owner_required")
    if credentials is not None:
        actor, _ = lock_authenticated_user(credentials, session)
        if actor.id != owner_id:
            raise BankCheckoutUnavailable("bank_owner_required")

    revisions = None
    if resource == BankResource.payout:
        try:
            revisions = bank_switches(session, lock=True)
        except BankCheckoutUnavailable:
            pass  # The owner may still retrieve an already reserved operation on pause.
    booking = driver = None
    if booking_id is not None:
        passenger, driver = (users[user_id] for user_id in participants)
        if passenger is None or driver is None:
            raise BankCheckoutUnavailable("bank_booking_unavailable")
        booking = checked_bank_booking(session, booking_id, passenger, driver)
    checkout = _lock_checkout(session, checkout_id)
    if checkout.booking_id != booking_id or checkout.closed_at is not None or checkout.test_mode != runtime.test_mode:
        raise BankCheckoutUnavailable("bank_checkout_changed")
    return BankSettlement(checkout, booking, driver, revisions)


def authorize_bank_settlement(session: Session, checkout_id: int, resource: BankResource, *,
                              owner_id: int, credentials: HTTPAuthorizationCredentials, command_id: UUID,
                              amount_kop: int, runtime: BankRuntime,
                              expected_switches: BankSwitchRevision | None = None) -> RideshareBankOperation:
    if credentials is None or not isinstance(command_id, UUID):
        raise BankCheckoutUnavailable("bank_owner_required")
    if not isinstance(resource, BankResource) or resource not in (BankResource.refund, BankResource.payout):
        raise BankCheckoutUnavailable("bank_settlement_unavailable")
    if type(amount_kop) is not int or amount_kop <= 0:
        raise BankCheckoutUnavailable("bank_amount_changed")
    context = lock_bank_settlement(session, checkout_id, resource, owner_id, runtime=runtime,
                                   credentials=credentials)
    previous = session.exec(select(RideshareBankOperation).join(
        RideshareBankApproval, RideshareBankApproval.operation_id == RideshareBankOperation.id,
    ).where(RideshareBankApproval.command_id == command_id.hex)).one_or_none()
    if previous is not None:
        if (previous.checkout_id != checkout_id or previous.resource != resource.value
                or previous.amount_kop != amount_kop or previous.settlement_kop != amount_kop):
            raise BankCheckoutUnavailable("bank_owner_command_conflict")
        return previous
    revisions = None
    if resource == BankResource.payout:
        revisions = context.payout_revisions()
        if revisions != expected_switches:
            raise BankCheckoutUnavailable("bank_switch_revision_conflict")
    operation = reserve_settlement(session, checkout_id, resource, amount_kop=amount_kop, settlement_kop=amount_kop)
    approval = RideshareBankApproval(operation_id=operation.id, command_id=command_id.hex, approved_by=owner_id)
    if revisions is not None:
        approval.paid_revision, approval.card_revision = revisions.paid, revisions.card
    session.add(approval)
    session.flush()
    return operation


def reauthorize_bank_payout(session: Session, checkout_id: int, operation_id: str, *,
                            owner_id: int, credentials: HTTPAuthorizationCredentials, runtime: BankRuntime,
                            expected_switches: BankSwitchRevision) -> RideshareBankOperation:
    if credentials is None:
        raise BankCheckoutUnavailable("bank_owner_required")
    context = lock_bank_settlement(session, checkout_id, BankResource.payout, owner_id, runtime=runtime,
                                   credentials=credentials)
    revisions = context.payout_revisions()
    if revisions != expected_switches:
        raise BankCheckoutUnavailable("bank_switch_revision_conflict")
    operation = session.get(RideshareBankOperation, operation_id, with_for_update=True, populate_existing=True)
    approval = session.get(RideshareBankApproval, operation_id, with_for_update=True, populate_existing=True)
    if (operation is None or operation.checkout_id != checkout_id or operation.resource != BankResource.payout.value
            or operation.state not in BANK_ACTIVE_STATES or operation.provider_id is not None or approval is None):
        raise BankCheckoutUnavailable("bank_settlement_unavailable")
    approval.paid_revision, approval.card_revision = revisions.paid, revisions.card
    approval.approved_by, approval.approved_at = owner_id, utcnow()
    session.flush()
    return operation


def cancel_unsent_bank_payout(session: Session, checkout_id: int, operation_id: str, *,
                              owner_id: int, credentials: HTTPAuthorizationCredentials,
                              runtime: BankRuntime) -> RideshareBankOperation:
    """Release a payout only when the locked row proves that no request was admitted."""
    if credentials is None:
        raise BankCheckoutUnavailable("bank_owner_required")
    lock_bank_settlement(session, checkout_id, BankResource.payout, owner_id, runtime=runtime,
                         credentials=credentials)
    operation = session.get(RideshareBankOperation, operation_id, with_for_update=True, populate_existing=True)
    approval = session.get(RideshareBankApproval, operation_id)
    if (operation is None or operation.checkout_id != checkout_id or operation.resource != BankResource.payout.value
            or approval is None or operation.state not in {"queued", "canceled"} or operation.dispatched_at is not None
            or operation.provider_id is not None or operation.request_ciphertext is not None):
        raise BankCheckoutUnavailable("bank_settlement_unavailable")
    operation.state = "canceled"
    session.flush()
    return operation
