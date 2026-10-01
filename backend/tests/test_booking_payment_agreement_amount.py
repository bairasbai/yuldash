"""Payment must match the participant-visible agreement/receipt, not another price.

Actual publication -> booking -> agreement -> confirmation/completion -> receipt -> pay.
Only the outbound _start_yookassa boundary is substituted: the endpoint, persisted
Payment, activation, booking settlement and driver ledger remain real. No real money
or provider request. Initial evidence uses the existing per-process SQLite fixture;
these sequential cases do not establish PostgreSQL locking/concurrency correctness.
"""
from datetime import timedelta, timezone
import json

import pytest
from sqlmodel import Session, select

from app.config import settings
from app.db import engine
from app.models import Booking, BookingStatus, LedgerEntry, LedgerKind, Payment, UserRole
from app.routers import wallet
from app.timeutil import utcnow


@pytest.fixture
def captured_provider(monkeypatch):
    # A launcher must disable external integrations before importing the application.
    # Assert the actual settings, without printing credentials or changing product logic.
    assert settings.env == "dev"
    assert settings.payments_provider == "mock"
    assert settings.sms_provider == "mock"
    assert not settings.redis_url
    assert not settings.firebase_credentials
    assert not settings.telegram_bot_token
    assert not settings.yandex_geocoder_key
    assert not settings.vapid_private_key
    assert settings.ride_service_fee_percent == 0
    calls = []

    def capture(session, payment, description, phone):
        # No amount is calculated here: capture exactly the persisted outbound amount.
        calls.append({
            "payment_id": payment.id, "booking_id": payment.booking_id,
            "user_id": payment.user_id, "purpose": payment.purpose,
            "amount_kop": payment.amount_kop, "stored_method": payment.method,
        })
        # Preserve the real boundary's method tagging; real activation/ledger do the rest.
        payment.method = "yookassa"
        session.add(payment)
        session.commit()
        return {"status": "succeeded", "provider_id": f"qa-agreement-{payment.id}",
                "confirmation_url": ""}

    monkeypatch.setattr(wallet, "_start_yookassa", capture)
    return calls


def _create(client, user_factory):
    driver = user_factory("QA_AGREEMENT_DRIVER", role=UserRole.driver)
    passenger = user_factory("QA_AGREEMENT_PASSENGER")
    # Explicit UTC avoids a local-time reinterpretation. Ten minutes is inside the
    # existing early-completion allowance; no clock or status endpoint is replaced.
    depart = (utcnow() + timedelta(minutes=10)).replace(tzinfo=timezone.utc).isoformat()
    published = client.post("/rides", headers=driver["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай", "depart_at": depart,
        "seats_total": 3, "price": 1000,
    })
    assert published.status_code == 200, published.text
    created = client.post("/bookings", headers=passenger["auth"], json={
        "ride_id": published.json()["id"], "seats": 1,
    })
    assert created.status_code == 200, created.text
    booking = created.json()
    assert booking["price"] == 1000
    assert booking["pay_amount"] == 1000
    assert booking["status"] == "pending"
    return driver, passenger, booking["id"]


def _agree(client, driver, passenger, booking_id, amount):
    agreement = client.post(f"/bookings/{booking_id}/pay-agreement",
                            headers=driver["auth"], json={"pay_method": "sbp", "pay_amount": amount})
    assert agreement.status_code == 200, agreement.text
    assert agreement.json()["pay_amount"] == amount
    for participant in (driver, passenger):
        details = client.get(f"/bookings/{booking_id}/details", headers=participant["auth"])
        assert details.status_code == 200, details.text
        assert details.json()["price"] == 1000
        assert details.json()["pay_amount"] == amount
        assert details.json()["pay_method"] == "sbp"


def _complete(client, driver, booking_id):
    confirmed = client.post(f"/bookings/{booking_id}/confirm", headers=driver["auth"])
    assert confirmed.status_code == 200, confirmed.text
    assert confirmed.json()["status"] == "confirmed"
    completed = client.post(f"/bookings/{booking_id}/driver-status", headers=driver["auth"],
                            json={"status": "done"})
    assert completed.status_code == 200, completed.text
    assert completed.json()["status"] == "done"


def _receipt(client, participant, booking_id, amount, paid):
    response = client.get(f"/trips/{booking_id}/receipt", headers=participant["auth"])
    assert response.status_code == 200, response.text
    assert response.json()["amount"] == amount
    assert response.json()["paid"] is paid
    return response.json()


def _rows(booking_id):
    with Session(engine) as session:
        booking = session.get(Booking, booking_id)
        payments = session.exec(select(Payment).where(Payment.booking_id == booking_id)
                                .order_by(Payment.id)).all()
        ledger = session.exec(select(LedgerEntry).where(LedgerEntry.booking_id == booking_id)
                              .order_by(LedgerEntry.id)).all()
        return {
            "paid": bool(booking and booking.paid),
            "payments": [{"id": p.id, "payer": p.user_id, "purpose": p.purpose,
                          "amount_kop": p.amount_kop, "status": p.status,
                          "method": p.method} for p in payments],
            "ledger": [{"driver": row.driver_id, "kind": row.kind.value,
                        "amount_kop": row.amount_kop} for row in ledger],
        }


def _pay_and_check(client, driver, passenger, booking_id, amount, method, captured_provider):
    _receipt(client, passenger, booking_id, amount, False)
    _receipt(client, driver, booking_id, amount, False)
    assert _rows(booking_id) == {"paid": False, "payments": [], "ledger": []}
    payment = client.post(f"/bookings/{booking_id}/pay", headers=passenger["auth"], json={"method": method})
    assert payment.status_code == 200, payment.text
    assert payment.json()["status"] == "succeeded"
    payment_id = payment.json()["payment_id"]
    actual = _rows(booking_id)
    balance = client.get("/wallet/balance", headers=driver["auth"])
    assert balance.status_code == 200, balance.text
    passenger_receipt = _receipt(client, passenger, booking_id, amount, True)
    driver_receipt = _receipt(client, driver, booking_id, amount, True)
    repeated = client.post(f"/bookings/{booking_id}/pay", headers=passenger["auth"], json={"method": method})
    assert repeated.status_code == 200, repeated.text
    assert repeated.json()["status"] == "already_paid"
    assert _rows(booking_id) == actual, "repeat must not append payments or earnings"
    observed = {
        "expected_amount_kop": amount * 100, "provider": captured_provider,
        "financial_rows": actual, "driver_balance_kop": balance.json()["balance_kop"],
        "passenger_receipt_rub": passenger_receipt["amount"],
        "driver_receipt_rub": driver_receipt["amount"], "repeat": repeated.json()["status"],
    }
    print("QA_BOOKING_AGREEMENT " + json.dumps(observed, ensure_ascii=False, sort_keys=True))
    # These independent observations are retained before any amount assertion fails.
    # Both actual card/sbp requests must reach this boundary once. Booking invoices
    # now persist yookassa before the first commit to make that gap retryable too.
    assert captured_provider == [{
        "payment_id": payment_id, "booking_id": booking_id, "user_id": passenger["id"],
        "purpose": "booking", "amount_kop": amount * 100, "stored_method": "yookassa",
    }], "outbound charge must match the agreed amount shown to both participants"
    assert actual == {
        "paid": True,
        "payments": [{"id": payment_id, "payer": passenger["id"], "purpose": "booking",
                      "amount_kop": amount * 100, "status": "succeeded", "method": "yookassa"}],
        "ledger": [{"driver": driver["id"], "kind": LedgerKind.earn.value, "amount_kop": amount * 100}],
    }
    assert balance.json()["balance_kop"] == amount * 100


@pytest.mark.parametrize("method", ["card", "sbp"])
@pytest.mark.parametrize("agreed_rub", [400, 1200])
def test_actual_agreement_receipt_charge_and_earnings_use_one_amount(
        client, user_factory, captured_provider, method, agreed_rub):
    """Both a lower/higher agreement must match the CTA/receipt and actual debit."""
    driver, passenger, booking_id = _create(client, user_factory)
    _agree(client, driver, passenger, booking_id, agreed_rub)
    _complete(client, driver, booking_id)
    _pay_and_check(client, driver, passenger, booking_id, agreed_rub, method, captured_provider)


@pytest.mark.parametrize("method", ["card", "sbp"])
def test_legacy_null_agreement_uses_original_price(client, user_factory, captured_provider, method):
    driver, passenger, booking_id = _create(client, user_factory)
    # The API now defaults pay_amount to price; None represents a stored legacy row.
    # Only that historical input is seeded. Completion, payment and settlement are real.
    with Session(engine) as session:
        booking = session.get(Booking, booking_id)
        booking.pay_amount = None
        session.add(booking)
        session.commit()
    _complete(client, driver, booking_id)
    _pay_and_check(client, driver, passenger, booking_id, 1000, method, captured_provider)


@pytest.mark.parametrize("state", ["pending", "confirmed", "onboard", "cancelled"])
def test_unfinished_or_cancelled_booking_never_creates_payment(
        client, user_factory, captured_provider, state):
    driver, passenger, booking_id = _create(client, user_factory)
    _agree(client, driver, passenger, booking_id, 400)
    if state == "confirmed":
        assert client.post(f"/bookings/{booking_id}/confirm", headers=driver["auth"]).status_code == 200
    elif state == "cancelled":
        response = client.post(f"/bookings/{booking_id}/cancel", headers=passenger["auth"],
                               json={"reason": "changed_mind"})
        assert response.status_code == 200, response.text
    elif state == "onboard":
        # Model-state guard control only: no current public booking endpoint was found
        # assigning onboard. Do not claim this seeded variant is a reachable UI journey.
        with Session(engine) as session:
            booking = session.get(Booking, booking_id)
            booking.status = BookingStatus.onboard
            session.add(booking)
            session.commit()
    before = _rows(booking_id)
    response = client.post(f"/bookings/{booking_id}/pay", headers=passenger["auth"], json={"method": "card"})
    assert response.status_code == 409, response.text
    assert captured_provider == []
    assert _rows(booking_id) == before == {"paid": False, "payments": [], "ledger": []}


@pytest.mark.parametrize("payer", ["driver", "outsider"])
def test_only_actual_passenger_can_pay_agreed_booking(client, user_factory, captured_provider, payer):
    driver, passenger, booking_id = _create(client, user_factory)
    _agree(client, driver, passenger, booking_id, 400)
    _complete(client, driver, booking_id)
    attempted_payer = driver if payer == "driver" else user_factory("QA_AGREEMENT_OUTSIDER")
    before = _rows(booking_id)
    response = client.post(f"/bookings/{booking_id}/pay", headers=attempted_payer["auth"], json={"method": "card"})
    assert response.status_code == 403, response.text
    assert captured_provider == []
    assert _rows(booking_id) == before == {"paid": False, "payments": [], "ledger": []}


def test_explicit_zero_agreement_cannot_charge_the_original_positive_price(client, user_factory, captured_provider):
    driver, passenger, booking_id = _create(client, user_factory)
    _agree(client, driver, passenger, booking_id, 0)
    _complete(client, driver, booking_id)
    _receipt(client, passenger, booking_id, 0, False)
    response = client.post(f"/bookings/{booking_id}/pay", headers=passenger["auth"], json={"method": "card"})
    print("QA_BOOKING_ZERO " + json.dumps({"http": response.status_code, "provider": captured_provider,
                                         "financial_rows": _rows(booking_id)}, sort_keys=True))
    assert response.status_code == 409, "a zero-price agreement must not create a positive provider charge"
    assert captured_provider == []
    assert _rows(booking_id) == {"paid": False, "payments": [], "ledger": []}


def test_missing_booking_creates_no_payment(client, user_factory, captured_provider):
    passenger = user_factory("QA_AGREEMENT_MISSING")
    booking_id = 1_000_000_000
    response = client.post(f"/bookings/{booking_id}/pay", headers=passenger["auth"], json={"method": "card"})
    assert response.status_code == 404, response.text
    assert captured_provider == []
    assert _rows(booking_id) == {"paid": False, "payments": [], "ledger": []}
