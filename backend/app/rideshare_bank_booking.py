"""Authoritative consent to start or resume a rideshare bank checkout."""

from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime

from cryptography.fernet import Fernet
from fastapi.security import HTTPAuthorizationCredentials
from sqlalchemy import func
from sqlmodel import Session, select

from .booking_payment import booking_amount_kop, booking_has_bank_obligation, lock_booking_payment
from .card_payments import CARD_PAYMENTS_SWITCH
from .config import settings
from .driver_pay_settings import passenger_ride
from .models import AppSettingChange, Booking, BookingStatus, DriverPaySettings, Payment, Ride, User, UserRole
from .owner_switches import PAID_SWITCH, login_settings, stored_on
from .rideshare_bank_models import BANK_ACTIVE_STATES, RideshareBankCheckout, RideshareBankOperation
from .rideshare_bank_provider import BankResource
from .rideshare_bank_recipient import BankRecipientUnavailable, bank_recipient_token
from .security import current_user, lock_authenticated_user, lock_refresh_user


CARD_RECEIPT_METHOD = "rideshare_card"


class BankCheckoutUnavailable(ValueError):
    """The booking or current consent does not permit starting an operation."""

    def __init__(self, code: str):
        self.code = code
        super().__init__(code)


@dataclass(frozen=True)
class BankRuntime:
    """Configuration assembled by the application, never from a user's request."""

    test_mode: bool
    cipher: Fernet
    return_url: Callable[[int], str]

    def check_environment(self) -> None:
        if self.test_mode and settings.is_prod:
            raise BankCheckoutUnavailable("bank_test_in_production")


@dataclass(frozen=True)
class BankSwitchRevision:
    paid: int
    card: int


@dataclass(frozen=True)
class BankChargeTerms:
    amount_kop: int
    driver_consent_at: datetime


def bank_switches(session: Session, *, lock: bool = False) -> BankSwitchRevision:
    rows = login_settings(session, lock="update" if lock else None)
    if not stored_on(rows, PAID_SWITCH) or not stored_on(rows, CARD_PAYMENTS_SWITCH):
        raise BankCheckoutUnavailable("bank_paused")
    revisions = dict(session.exec(
        select(AppSettingChange.key, func.max(AppSettingChange.id))
        .where(AppSettingChange.key.in_((PAID_SWITCH, CARD_PAYMENTS_SWITCH)))
        .group_by(AppSettingChange.key),
    ).all())
    return BankSwitchRevision(revisions.get(PAID_SWITCH, 0), revisions.get(CARD_PAYMENTS_SWITCH, 0))


def booking_participant_ids(session: Session, booking_id: int) -> tuple[int, int]:
    row = session.exec(
        select(Booking.passenger_id, Ride.driver_id)
        .join(Ride, Ride.id == Booking.ride_id).where(Booking.id == booking_id),
    ).one_or_none()
    if row is None:
        raise BankCheckoutUnavailable("bank_booking_unavailable")
    return row[0], row[1]


def lock_booking_participants(session: Session, booking_id: int, *, passenger_id: int | None = None) -> tuple[User, User]:
    ids = booking_participant_ids(session, booking_id)
    if passenger_id is not None and ids[0] != passenger_id:
        raise BankCheckoutUnavailable("bank_booking_unavailable")
    users = {user_id: lock_refresh_user(session, user_id) for user_id in sorted(set(ids))}
    passenger, driver = users[ids[0]], users[ids[1]]
    if passenger is None or driver is None:
        raise BankCheckoutUnavailable("bank_booking_unavailable")
    return passenger, driver


def checked_bank_booking(session: Session, booking_id: int, passenger: User, driver: User) -> Booking:
    booking = lock_booking_payment(session, booking_id)
    ride = session.get(Ride, booking.ride_id, populate_existing=True) if booking is not None else None
    if (booking is None or ride is None or booking.passenger_id != passenger.id or
            ride.driver_id != driver.id or not passenger_ride(ride)):
        raise BankCheckoutUnavailable("bank_booking_unavailable")
    return booking


def check_bank_charge(session: Session, booking: Booking, driver: User, recipient_version: str,
                      runtime: BankRuntime) -> BankChargeTerms:
    if booking.status != BookingStatus.done or booking.paid:
        raise BankCheckoutUnavailable("bank_booking_unavailable")
    consent = session.exec(
        select(DriverPaySettings).where(DriverPaySettings.user_id == driver.id)
        .execution_options(populate_existing=True),
    ).one_or_none()
    if (driver.role != UserRole.driver or not driver.verified or consent is None or
            not consent.accepts_card or consent.accepts_card_at is None):
        raise BankCheckoutUnavailable("bank_driver_not_ready")
    try:
        bank_recipient_token(session, driver.id, recipient_version, cipher=runtime.cipher)
    except BankRecipientUnavailable:
        raise BankCheckoutUnavailable("bank_recipient_changed") from None
    legacy = session.exec(select(Payment.id).where(
        Payment.booking_id == booking.id, Payment.purpose == "booking",
        Payment.status.in_(("pending", "succeeded", "refund_due")),
    ).limit(1)).first()
    if legacy is not None:
        raise BankCheckoutUnavailable("bank_legacy_payment_exists")
    return BankChargeTerms(booking_amount_kop(booking), consent.accepts_card_at)


def start_bank_checkout(session: Session, booking_id: int, passenger_id: int, *,
                        expected_switches: BankSwitchRevision, recipient_version: str,
                        expected_amount_kop: int, expected_driver_consent_at: datetime, runtime: BankRuntime,
                        credentials: HTTPAuthorizationCredentials | None = None,
                        retry_after_payment_id: str | None = None) -> RideshareBankCheckout:
    """Confirm new work or resume that same attempt using a fresh read of the switches."""
    runtime.check_environment()
    if credentials is not None and current_user(credentials, session).id != passenger_id:
        raise BankCheckoutUnavailable("bank_booking_unavailable")
    passenger, driver = lock_booking_participants(session, booking_id, passenger_id=passenger_id)
    if credentials is not None:
        current_passenger, _ = lock_authenticated_user(credentials, session)
        if current_passenger.id != passenger.id:
            raise BankCheckoutUnavailable("bank_booking_unavailable")
    try:
        revisions = bank_switches(session, lock=True)
    except BankCheckoutUnavailable:
        revisions = None
    booking = checked_bank_booking(session, booking_id, passenger, driver)
    checkout = session.exec(select(RideshareBankCheckout).where(
        RideshareBankCheckout.booking_id == booking_id, RideshareBankCheckout.closed_at.is_(None),
    ).with_for_update().execution_options(populate_existing=True)).one_or_none()
    if checkout is not None and (checkout.test_mode != runtime.test_mode):
        raise BankCheckoutUnavailable("bank_checkout_changed")
    if (checkout is not None and retry_after_payment_id is None and
            booking.paid and booking.payment_method == CARD_RECEIPT_METHOD):
        return checkout
    if revisions is None:
        raise BankCheckoutUnavailable("bank_paused")
    if revisions != expected_switches:
        raise BankCheckoutUnavailable("bank_switch_revision_conflict")
    terms = check_bank_charge(session, booking, driver, recipient_version, runtime)
    amount = terms.amount_kop
    if type(expected_amount_kop) is not int or expected_amount_kop != amount:
        raise BankCheckoutUnavailable("bank_amount_changed")
    if expected_driver_consent_at != terms.driver_consent_at:
        raise BankCheckoutUnavailable("bank_driver_consent_changed")
    if checkout is not None and (checkout.amount_kop != amount or checkout.recipient_version != recipient_version or
                                 checkout.driver_consent_at != terms.driver_consent_at):
        if booking_has_bank_obligation(session, booking_id):
            raise BankCheckoutUnavailable("bank_checkout_changed")
        # A deal can already exist even when no debit has begun. Keep that operation and its key.
        queued_charge = session.exec(select(RideshareBankOperation).where(
            RideshareBankOperation.checkout_id == checkout.id,
            RideshareBankOperation.resource == BankResource.payment.value,
            RideshareBankOperation.state == "queued",
        )).one_or_none()
        if queued_charge is not None:
            if queued_charge.request_ciphertext is not None or queued_charge.dispatched_at is not None:
                raise BankCheckoutUnavailable("bank_checkout_changed")
            queued_charge.amount_kop = queued_charge.settlement_kop = amount
        checkout.amount_kop = checkout.payout_kop = amount
        checkout.recipient_version = recipient_version
        checkout.driver_consent_at = terms.driver_consent_at
    if checkout is not None:
        if retry_after_payment_id is not None:
            ended = session.get(RideshareBankOperation, retry_after_payment_id)
            if (ended is None or ended.checkout_id != checkout.id or ended.resource != BankResource.payment.value
                    or ended.state not in {"canceled", "rejected"} or ended.provider_id is None):
                raise BankCheckoutUnavailable("bank_retry_unavailable")
            # An old retry command is valid only against the last terminal charge.
            newer = session.exec(select(RideshareBankOperation.id).where(
                RideshareBankOperation.checkout_id == checkout.id,
                RideshareBankOperation.resource == BankResource.payment.value,
                RideshareBankOperation.created_at >= ended.created_at,
                RideshareBankOperation.id != ended.id,
            ).limit(1)).first()
            active = session.exec(select(RideshareBankOperation.id).where(
                RideshareBankOperation.checkout_id == checkout.id, RideshareBankOperation.state.in_(BANK_ACTIVE_STATES),
            ).limit(1)).first()
            if newer is None and active is None:
                session.add(RideshareBankOperation(checkout_id=checkout.id, resource=BankResource.payment.value,
                                                   amount_kop=amount, settlement_kop=checkout.payout_kop))
    else:
        if retry_after_payment_id is not None:
            raise BankCheckoutUnavailable("bank_retry_unavailable")
        if booking_has_bank_obligation(session, booking_id):
            raise BankCheckoutUnavailable("bank_booking_unavailable")
        checkout = RideshareBankCheckout(booking_id=booking_id, amount_kop=amount, payout_kop=amount,
                                        test_mode=runtime.test_mode, recipient_version=recipient_version,
                                        driver_consent_at=terms.driver_consent_at)
        session.add(checkout)
        session.flush()
        session.add(RideshareBankOperation(checkout_id=checkout.id, resource=BankResource.deal.value))
    checkout.paid_revision, checkout.card_revision = revisions.paid, revisions.card
    session.flush()
    return checkout
