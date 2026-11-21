"""The booking row serializes changes to the agreed amount and payment."""

from sqlalchemy import and_, or_, update
from sqlmodel import Session, select

from .errors import herr
from .models import Booking
from .rideshare_bank_models import BANK_ACTIVE_STATES, RideshareBankCheckout, RideshareBankOperation


def booking_amount_kop(booking: Booking) -> int:
    amount_rub = booking.pay_amount if booking.pay_amount is not None else booking.price
    amount_kop = int(amount_rub) * 100
    if amount_kop <= 0:
        raise herr(409, "У брони нет суммы к оплате", "Брондә түләргә сумма юҡ")
    return amount_kop


def lock_booking_payment(session: Session, booking_id: int) -> Booking | None:
    if session.get_bind().dialect.name == "sqlite":
        session.execute(update(Booking).where(Booking.id == booking_id).values(paid=Booking.paid))
    return session.get(Booking, booking_id, with_for_update=True, populate_existing=True)


def booking_has_bank_obligation(session: Session, booking_id: int) -> bool:
    # A queued deal is harmless. Money can move only after a payment gets a dispatch stamp.
    obligation = session.exec(
        select(RideshareBankOperation.id)
        .join(RideshareBankCheckout, RideshareBankCheckout.id == RideshareBankOperation.checkout_id)
        .where(RideshareBankCheckout.booking_id == booking_id,
               RideshareBankOperation.resource == "payments",
               or_(RideshareBankOperation.state == "succeeded",
                   and_(RideshareBankOperation.state.in_(BANK_ACTIVE_STATES),
                        RideshareBankOperation.dispatched_at.is_not(None))))
        .limit(1),
    ).first()
    return obligation is not None


def check_direct_payment_allowed(session: Session, booking_id: int) -> None:
    if booking_has_bank_obligation(session, booking_id):
        raise herr(409, "Банк ещё проверяет оплату. Обнови её статус.",
                   "Банк түләүҙе әле тикшерә. Торошон яңырт.", code="booking_bank_in_progress")
