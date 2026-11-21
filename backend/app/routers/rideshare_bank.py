"""Read the same confirmed bank receipt for Android and PWA."""

from datetime import timezone
from enum import Enum
from typing import Annotated, Literal

from fastapi import APIRouter, Depends, Query, Response
from fastapi.security import HTTPAuthorizationCredentials
from pydantic import AwareDatetime, BaseModel, Field
from sqlmodel import Session, select

from ..booking_payment import booking_has_bank_obligation
from ..config import settings
from ..db import get_session
from ..driver_pay_settings import passenger_ride
from ..errors import herr
from ..idempotency import Applied, KeyedBody, Scope, execute, require_wire_command
from ..models import Booking, BookingStatus, Ride, User
from ..rideshare_bank_booking import (
    BankCheckoutUnavailable, BankRuntime, BankSwitchRevision, bank_switches, check_bank_charge, start_bank_checkout,
)
from ..rideshare_bank_confirmation import open_bank_confirmation
from ..rideshare_bank_models import (
    RideshareBankCheckout, RideshareBankOperation, RideshareBankPosting, RideshareBankRecipient,
)
from ..rideshare_bank_provider import BankResource
from ..security import bearer, current_user


router = APIRouter(tags=["rideshare-bank"])


class ReceiptStatus(str, Enum):
    unavailable = "unavailable"
    not_started = "not_started"
    queued = "queued"
    waiting_bank = "waiting_bank"
    reconciling = "reconciling"
    succeeded = "succeeded"
    canceled = "canceled"


_OPERATION_STATUS = {
    "queued": ReceiptStatus.queued,
    "dispatched": ReceiptStatus.reconciling,
    "needs_reconciliation": ReceiptStatus.reconciling,
    "pending": ReceiptStatus.waiting_bank,
    "succeeded": ReceiptStatus.succeeded,
    "canceled": ReceiptStatus.canceled,
    "rejected": ReceiptStatus.canceled,
}


class CardPaymentReceipt(BaseModel):
    currency: Literal["RUB"] = "RUB"
    amount_kop: int | None = None
    payment_status: ReceiptStatus = ReceiptStatus.unavailable
    paid_kop: int = 0
    refund_status: ReceiptStatus = ReceiptStatus.not_started
    refunded_kop: int = 0
    refund_coverage: Literal["none", "partial", "full"] = "none"
    payout_status: ReceiptStatus | None = None
    paid_out_kop: int | None = None
    payout_remaining_kop: int | None = None
    payment_attempt_id: str | None = None


class CardPaymentConfirmation(BaseModel):
    amount_kop: Annotated[int, Field(strict=True, gt=0)]
    paid_revision: Annotated[int, Field(strict=True, ge=0)]
    card_revision: Annotated[int, Field(strict=True, ge=0)]
    recipient_version: Annotated[str, Field(pattern=r"^[a-f0-9]{32}$")]
    driver_consent_at: AwareDatetime
    retry_after_payment_id: Annotated[str, Field(pattern=r"^[a-f0-9]{32}$")] | None = None


class CardPaymentTerms(BaseModel):
    confirmation: CardPaymentConfirmation | None = None
    blocked_reason: str | None = None
    action: Literal["start", "retry", "resume"] | None = None


class CardCheckoutLink(BaseModel):
    checkout_url: str | None = None


def receipt_bank_runtime() -> BankRuntime | None:
    """Provide the trusted runtime when the application composes the bank integration."""
    return None


def _load_card_booking(session: Session, booking_id: int, user_id: int, *,
                       passenger_only: bool = False) -> tuple[Booking, Ride]:
    booking = session.get(Booking, booking_id)
    ride = session.get(Ride, booking.ride_id) if booking is not None else None
    if (booking is None or ride is None or not passenger_ride(ride)
            or user_id not in {booking.passenger_id, ride.driver_id}
            or (passenger_only and user_id != booking.passenger_id)):
        raise herr(404, "Бронь не найдена.", "Бронь табылманы.", code="booking_not_found")
    return booking, ride


@router.get("/bookings/{booking_id}/card-payment", response_model=CardPaymentReceipt)
def card_payment_receipt(booking_id: int, response: Response, user: User = Depends(current_user),
                         session: Session = Depends(get_session),
                         runtime: BankRuntime | None = Depends(receipt_bank_runtime)) -> CardPaymentReceipt:
    booking, ride = _load_card_booking(session, booking_id, user.id)
    response.headers["Cache-Control"] = "no-store"
    test_mode = runtime is not None and runtime.test_mode and not settings.is_prod

    latest_checkout = (
        select(RideshareBankCheckout.id)
        .join(Booking, Booking.id == RideshareBankCheckout.booking_id)
        .join(Ride, Ride.id == Booking.ride_id)
        .where(Booking.id == booking_id, Booking.passenger_id == booking.passenger_id,
               Ride.driver_id == ride.driver_id, Ride.category == ride.category,
               RideshareBankCheckout.test_mode == test_mode)
        .order_by(RideshareBankCheckout.created_at.desc(), RideshareBankCheckout.id.desc())
        .limit(1).correlate(None).scalar_subquery()
    )
    rows = session.exec(
        select(RideshareBankCheckout.amount_kop, RideshareBankOperation.id, RideshareBankOperation.resource,
               RideshareBankOperation.state,
               RideshareBankPosting.amount_kop.label("posted_kop"), RideshareBankPosting.settlement_kop)
        .select_from(RideshareBankCheckout)
        .outerjoin(RideshareBankOperation, RideshareBankOperation.checkout_id == RideshareBankCheckout.id)
        .outerjoin(RideshareBankPosting, RideshareBankPosting.operation_id == RideshareBankOperation.id)
        .where(RideshareBankCheckout.id == latest_checkout)
        .order_by(RideshareBankOperation.created_at.desc(), RideshareBankOperation.id.desc()),
    ).all()
    if not rows:
        return CardPaymentReceipt()

    statuses: dict[str, ReceiptStatus] = {}
    totals: dict[str, tuple[int, int]] = {}
    payment_attempt_id = None
    for _, operation_id, resource, state, posted, settlement in rows:
        if resource is None:
            continue
        if resource == BankResource.payment.value and payment_attempt_id is None:
            payment_attempt_id = operation_id
        statuses.setdefault(resource, _OPERATION_STATUS[state])
        if state == "succeeded" and posted is not None:
            total, share = totals.get(resource, (0, 0))
            totals[resource] = (total + posted, share + settlement)

    paid, driver_share = totals.get(BankResource.payment.value, (0, 0))
    refunded, refund_share = totals.get(BankResource.refund.value, (0, 0))
    payout, payout_share = totals.get(BankResource.payout.value, (0, 0))
    payment_status = statuses.get(BankResource.payment.value)
    if payment_status is None:
        deal_status = statuses.get(BankResource.deal.value, ReceiptStatus.queued)
        payment_status = ReceiptStatus.queued if deal_status == ReceiptStatus.succeeded else deal_status
    is_driver = ride.driver_id == user.id
    return CardPaymentReceipt(
        amount_kop=rows[0].amount_kop, payment_status=payment_status, paid_kop=paid,
        refund_status=statuses.get(BankResource.refund.value, ReceiptStatus.not_started), refunded_kop=refunded,
        refund_coverage="none" if not refunded else ("full" if refunded == paid else "partial"),
        payout_status=statuses.get(BankResource.payout.value, ReceiptStatus.not_started) if is_driver else None,
        paid_out_kop=payout if is_driver else None,
        payout_remaining_kop=driver_share - refund_share - payout_share if is_driver else None,
        payment_attempt_id=payment_attempt_id if booking.passenger_id == user.id else None,
    )


@router.get("/bookings/{booking_id}/card-payment/checkout-link", response_model=CardCheckoutLink)
def card_checkout_link(booking_id: int, response: Response, user: User = Depends(current_user),
                       session: Session = Depends(get_session),
                       runtime: BankRuntime | None = Depends(receipt_bank_runtime),
                       expected_payment_id: Annotated[str | None, Query(pattern=r"^[a-f0-9]{32}$")] = None) -> CardCheckoutLink:
    _, ride = _load_card_booking(session, booking_id, user.id, passenger_only=True)
    response.headers["Cache-Control"] = "no-store"
    if runtime is None or (runtime.test_mode and settings.is_prod):
        return CardCheckoutLink()
    selection = (
        select(RideshareBankCheckout.id.label("checkout_id"), RideshareBankOperation.id.label("operation_id"),
               RideshareBankOperation.confirmation_ciphertext)
        .select_from(RideshareBankCheckout)
        .join(RideshareBankOperation, RideshareBankOperation.checkout_id == RideshareBankCheckout.id)
        .join(Booking, Booking.id == RideshareBankCheckout.booking_id)
        .join(Ride, Ride.id == Booking.ride_id)
        .where(Booking.id == booking_id, Booking.passenger_id == user.id, Booking.status == BookingStatus.done,
               Booking.paid.is_(False),
               Ride.driver_id == ride.driver_id, Ride.category == ride.category,
               RideshareBankCheckout.closed_at.is_(None), RideshareBankCheckout.test_mode == runtime.test_mode,
               RideshareBankOperation.resource == BankResource.payment.value, RideshareBankOperation.state == "pending",
               RideshareBankOperation.dispatched_at.is_not(None), RideshareBankOperation.provider_id.is_not(None),
               RideshareBankOperation.confirmation_ciphertext.is_not(None))
    )
    if expected_payment_id is not None:
        selection = selection.where(RideshareBankOperation.id == expected_payment_id)
    row = session.exec(selection).one_or_none()
    if row is None:
        return CardCheckoutLink()
    return CardCheckoutLink(checkout_url=open_bank_confirmation(
        runtime.cipher, row.checkout_id, row.operation_id, runtime.test_mode, row.confirmation_ciphertext,
    ))


@router.get("/bookings/{booking_id}/card-payment/terms", response_model=CardPaymentTerms)
def card_payment_terms(booking_id: int, response: Response, user: User = Depends(current_user),
                       session: Session = Depends(get_session),
                       runtime: BankRuntime | None = Depends(receipt_bank_runtime)) -> CardPaymentTerms:
    booking, ride = _load_card_booking(session, booking_id, user.id, passenger_only=True)
    response.headers["Cache-Control"] = "no-store"
    if runtime is None:
        return CardPaymentTerms(blocked_reason="bank_project_unaccepted")
    try:
        runtime.check_environment()
        revisions = bank_switches(session)
        driver = session.get(User, ride.driver_id)
        recipient = session.get(RideshareBankRecipient, ride.driver_id)
        if driver is None or recipient is None:
            raise BankCheckoutUnavailable("bank_driver_not_ready")
        terms = check_bank_charge(session, booking, driver, recipient.version, runtime)
    except BankCheckoutUnavailable as unavailable:
        return CardPaymentTerms(blocked_reason=unavailable.code)

    checkout = session.exec(select(RideshareBankCheckout).where(
        RideshareBankCheckout.booking_id == booking_id, RideshareBankCheckout.closed_at.is_(None),
    )).one_or_none()
    if checkout is not None and checkout.test_mode != runtime.test_mode:
        return CardPaymentTerms(blocked_reason="bank_checkout_changed")
    offer = CardPaymentConfirmation(
        amount_kop=terms.amount_kop, paid_revision=revisions.paid, card_revision=revisions.card,
        recipient_version=recipient.version, driver_consent_at=terms.driver_consent_at.replace(tzinfo=timezone.utc),
    )
    if checkout is None:
        if booking_has_bank_obligation(session, booking_id):
            return CardPaymentTerms(blocked_reason="bank_booking_unavailable")
        return CardPaymentTerms(confirmation=offer, action="start")
    return _queued_card_terms(session, checkout, offer)


def _queued_card_terms(session: Session, checkout: RideshareBankCheckout,
                       offer: CardPaymentConfirmation) -> CardPaymentTerms:
    operation = session.exec(select(
        RideshareBankOperation.id, RideshareBankOperation.state,
        RideshareBankOperation.provider_id, RideshareBankOperation.dispatched_at,
    ).where(
        RideshareBankOperation.checkout_id == checkout.id, RideshareBankOperation.resource == BankResource.payment.value,
    ).order_by(RideshareBankOperation.created_at.desc(), RideshareBankOperation.id.desc()).limit(1)).first()
    if operation is not None and operation.state in {"canceled", "rejected"} and operation.provider_id:
        return CardPaymentTerms(confirmation=offer.model_copy(update={"retry_after_payment_id": operation.id}), action="retry")
    if operation is None:
        deal_state = session.exec(select(RideshareBankOperation.state).where(
            RideshareBankOperation.checkout_id == checkout.id, RideshareBankOperation.resource == BankResource.deal.value,
        )).one_or_none()
        unsent = deal_state in {"queued", "succeeded"}
    else:
        unsent = operation.state == "queued" and operation.dispatched_at is None
    changed = (checkout.paid_revision, checkout.card_revision, checkout.amount_kop, checkout.recipient_version,
               checkout.driver_consent_at) != (
        offer.paid_revision, offer.card_revision, offer.amount_kop, offer.recipient_version,
        offer.driver_consent_at.replace(tzinfo=None),
    )
    return CardPaymentTerms(confirmation=offer, action="resume" if unsent and changed else None)


@router.post("/bookings/{booking_id}/card-payment/confirm")
@router.post("/bookings/{booking_id}/commands/v1/card-confirm", dependencies=[Depends(require_wire_command)])
def confirm_card_payment(booking_id: int, body: CardPaymentConfirmation, command: KeyedBody,
                         user: User = Depends(current_user),
                         credentials: HTTPAuthorizationCredentials = Depends(bearer),
                         session: Session = Depends(get_session),
                         runtime: BankRuntime | None = Depends(receipt_bank_runtime)) -> dict[str, bool]:
    _load_card_booking(session, booking_id, user.id, passenger_only=True)
    user_id = user.id
    accepted = {"accepted": True}

    def apply() -> Applied:
        if runtime is None:
            raise BankCheckoutUnavailable("bank_project_unaccepted")
        start_bank_checkout(
            session, booking_id, user_id, runtime=runtime, credentials=credentials, recipient_version=body.recipient_version,
            expected_switches=BankSwitchRevision(body.paid_revision, body.card_revision), expected_amount_kop=body.amount_kop,
            expected_driver_consent_at=body.driver_consent_at.astimezone(timezone.utc).replace(tzinfo=None),
            retry_after_payment_id=body.retry_after_payment_id,
        )
        booking = session.get_one(Booking, booking_id)
        return Applied(booking_id, booking.created_at, accepted)

    try:
        if command is not None:
            return execute(session, user_id, command.command(Scope.BOOKING_CARD_CONFIRM, booking_id, body), apply)
        apply()
        session.commit()
        return accepted
    except BankCheckoutUnavailable as unavailable:
        raise herr(409, "Оплата картой пока недоступна. Обнови условия.",
                   "Карта менән түләү әлегә мөмкин түгел. Шарттарҙы яңырт.", code=unavailable.code) from None
