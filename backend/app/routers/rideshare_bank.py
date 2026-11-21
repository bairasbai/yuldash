"""Read the same confirmed bank receipt for Android and PWA."""

from datetime import timezone
from enum import Enum
from typing import Annotated, Literal

from fastapi import APIRouter, Depends, Response
from fastapi.security import HTTPAuthorizationCredentials
from pydantic import AwareDatetime, BaseModel, Field
from sqlmodel import Session, select

from ..config import settings
from ..db import get_session
from ..driver_pay_settings import passenger_ride
from ..errors import herr
from ..models import Booking, Ride, User
from ..rideshare_bank_booking import (
    BankCheckoutUnavailable, BankRuntime, BankSwitchRevision, bank_switches, check_bank_charge, start_bank_checkout,
)
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
        select(RideshareBankCheckout.amount_kop, RideshareBankOperation.resource, RideshareBankOperation.state,
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
    for _, resource, state, posted, settlement in rows:
        if resource is None:
            continue
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
    )


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

    latest = session.exec(
        select(RideshareBankOperation.id, RideshareBankOperation.state, RideshareBankOperation.provider_id)
        .join(RideshareBankCheckout, RideshareBankCheckout.id == RideshareBankOperation.checkout_id)
        .where(RideshareBankCheckout.booking_id == booking_id, RideshareBankCheckout.test_mode == runtime.test_mode,
               RideshareBankCheckout.closed_at.is_(None), RideshareBankOperation.resource == BankResource.payment.value)
        .order_by(RideshareBankOperation.created_at.desc(), RideshareBankOperation.id.desc())
        .limit(1),
    ).first()
    retry = latest.id if latest is not None and latest.state in {"canceled", "rejected"} and latest.provider_id else None
    return CardPaymentTerms(confirmation=CardPaymentConfirmation(
        amount_kop=terms.amount_kop, paid_revision=revisions.paid, card_revision=revisions.card,
        recipient_version=recipient.version, driver_consent_at=terms.driver_consent_at.replace(tzinfo=timezone.utc),
        retry_after_payment_id=retry,
    ))


@router.post("/bookings/{booking_id}/card-payment/confirm")
def confirm_card_payment(booking_id: int, body: CardPaymentConfirmation, user: User = Depends(current_user),
                         credentials: HTTPAuthorizationCredentials = Depends(bearer),
                         session: Session = Depends(get_session),
                         runtime: BankRuntime | None = Depends(receipt_bank_runtime)) -> dict[str, bool]:
    _load_card_booking(session, booking_id, user.id, passenger_only=True)
    try:
        if runtime is None:
            raise BankCheckoutUnavailable("bank_project_unaccepted")
        start_bank_checkout(
            session, booking_id, user.id, runtime=runtime, credentials=credentials, recipient_version=body.recipient_version,
            expected_switches=BankSwitchRevision(body.paid_revision, body.card_revision), expected_amount_kop=body.amount_kop,
            expected_driver_consent_at=body.driver_consent_at.astimezone(timezone.utc).replace(tzinfo=None),
            retry_after_payment_id=body.retry_after_payment_id,
        )
        session.commit()
    except BankCheckoutUnavailable as unavailable:
        raise herr(409, "Оплата картой пока недоступна. Обнови условия.",
                   "Карта менән түләү әлегә мөмкин түгел. Шарттарҙы яңырт.", code=unavailable.code) from None
    return {"accepted": True}
