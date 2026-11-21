"""Read the same confirmed bank receipt for Android and PWA."""

from enum import Enum
from typing import Literal

from fastapi import APIRouter, Depends, Response
from pydantic import BaseModel
from sqlmodel import Session, select

from ..config import settings
from ..db import get_session
from ..driver_pay_settings import passenger_ride
from ..errors import herr
from ..models import Booking, Ride, User
from ..rideshare_bank_booking import BankRuntime
from ..rideshare_bank_models import RideshareBankCheckout, RideshareBankOperation, RideshareBankPosting
from ..rideshare_bank_provider import BankResource
from ..security import current_user


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


def receipt_bank_runtime() -> BankRuntime | None:
    """Provide the trusted runtime when the application composes the bank integration."""
    return None


@router.get("/bookings/{booking_id}/card-payment", response_model=CardPaymentReceipt)
def card_payment_receipt(booking_id: int, response: Response, user: User = Depends(current_user),
                         session: Session = Depends(get_session),
                         runtime: BankRuntime | None = Depends(receipt_bank_runtime)) -> CardPaymentReceipt:
    booking = session.get(Booking, booking_id)
    ride = session.get(Ride, booking.ride_id) if booking is not None else None
    if ride is None or not passenger_ride(ride) or user.id not in {booking.passenger_id, ride.driver_id}:
        raise herr(404, "Бронь не найдена.", "Бронь табылманы.", code="booking_not_found")
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
