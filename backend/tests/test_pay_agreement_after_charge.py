"""RS-01: locked booking payments and settlement of synthetic legacy invoices.

No public request creates a cashless invoice. Legacy payment rows are seeded explicitly;
provider status and the private cashless helper use local fakes, never outbound HTTP.
"""
import pytest
from sqlmodel import Session, select

from app.db import engine
from app.config import settings
from app.models import Booking, LedgerEntry, Payment, User
from app.routers import payments as payments_router
from app.routers import wallet
from test_booking_payment_agreement_amount import _create, _agree, _complete, _rows, _receipt


def _completed(client, user_factory):
    driver, passenger, bid = _create(client, user_factory)
    _agree(client, driver, passenger, bid, 400)
    _complete(client, driver, bid)
    return driver, passenger, bid


@pytest.mark.parametrize("method", ["card", "sbp"])
@pytest.mark.parametrize("status", ["pending", "succeeded", "canceled", "refund_due"])
def test_locked_public_payment_preserves_historical_invoice(client, user_factory, monkeypatch, method, status):
    driver, passenger, bid = _completed(client, user_factory)
    with Session(engine) as session:
        booking = session.get(Booking, bid)
        booking.paid = status == "succeeded"
        session.add(booking)
        session.add(Payment(user_id=passenger["id"], purpose="booking", booking_id=bid,
                            amount_kop=40000, method="yookassa", status=status,
                            provider_id=f"synthetic-legacy-{bid}"))
        session.commit()
    calls = []
    monkeypatch.setattr(wallet, "_start_yookassa", lambda *args: calls.append("create"))
    monkeypatch.setattr(payments_router, "fetch_payment", lambda *args: calls.append("fetch"))
    before = _rows(bid)
    for _ in range(2):
        result = client.post(f"/bookings/{bid}/pay", headers=passenger["auth"], json={"method": method})
        assert result.status_code == 503, result.text
        assert result.json()["detail"]["code"] == "booking_online_pay_off"
    assert _rows(bid) == before and calls == []
    _receipt(client, driver, bid, 400, status == "succeeded")
    _receipt(client, passenger, bid, 400, status == "succeeded")


def test_private_legacy_helper_settles_agreed_amount_and_status_repeat_once(client, user_factory, monkeypatch):
    driver, passenger, bid = _completed(client, user_factory)
    monkeypatch.setattr(settings, "payments_provider", "yookassa")
    calls = []
    def fake_provider(session, payment, description, phone):
        calls.append(payment.amount_kop)
        return {"status": "pending", "provider_id": f"synthetic-helper-{payment.id}", "confirmation_url": ""}
    monkeypatch.setattr(wallet, "_start_yookassa", fake_provider)
    with Session(engine) as session:
        payer = session.get(User, passenger["id"])
        result = wallet._pay_cashless(session, payer, purpose="booking", amount_kop=40000,
                                     method="card", description="synthetic legacy booking", booking_id=bid)
    assert result["status"] == "pending" and calls == [40000]
    monkeypatch.setattr(payments_router, "fetch_payment", lambda *args: {
        "status": "succeeded", "metadata": {"payment_id": str(result["payment_id"])},
    })
    for _ in range(2):
        polled = client.get(f"/payments/{result['payment_id']}/status", headers=passenger["auth"])
        assert polled.status_code == 200 and polled.json()["status"] == "succeeded", polled.text
    rows = _rows(bid)
    assert rows["paid"] and len(rows["payments"]) == len(rows["ledger"]) == 1
    assert rows["payments"][0]["amount_kop"] == rows["ledger"][0]["amount_kop"] == 40000
    assert rows["ledger"][0]["driver"] == driver["id"]
    blocked = client.post(f"/bookings/{bid}/pay", headers=passenger["auth"], json={"method": "card"})
    assert blocked.status_code == 503 and _rows(bid) == rows


@pytest.mark.parametrize("status", ["pending", "succeeded"])
def test_historical_invoice_still_freezes_changed_agreement(client, user_factory, status):
    driver, passenger, bid = _completed(client, user_factory)
    with Session(engine) as session:
        booking = session.get(Booking, bid)
        booking.paid = status == "succeeded"
        session.add(booking)
        session.add(Payment(user_id=passenger["id"], purpose="booking", booking_id=bid,
                            amount_kop=40000, method="yookassa", status=status,
                            provider_id=f"synthetic-freeze-{bid}"))
        session.commit()
    before = _rows(bid)
    response = client.post(f"/bookings/{bid}/pay-agreement", headers=driver["auth"],
                           json={"pay_amount": 1200, "pay_method": "cash"})
    assert response.status_code == 409, response.text
    assert _rows(bid) == before
    _receipt(client, passenger, bid, 400, status == "succeeded")


def test_cash_paid_amount_is_frozen_without_online_charge(client, user_factory):
    driver, passenger, bid = _completed(client, user_factory)
    paid = client.post(f"/bookings/{bid}/pay", headers=passenger["auth"], json={"method": "cash"})
    assert paid.status_code == 200 and paid.json()["status"] == "paid", paid.text
    before = _rows(bid)
    assert before == {"paid": True, "payments": [], "ledger": []}
    changed = client.post(f"/bookings/{bid}/pay-agreement", headers=driver["auth"], json={"pay_amount": 1200})
    assert changed.status_code == 409, changed.text
    same = client.post(f"/bookings/{bid}/pay-agreement", headers=passenger["auth"], json={"pay_amount": 400})
    assert same.status_code == 200, same.text
    assert _rows(bid) == before
    _receipt(client, passenger, bid, 400, True)
    with Session(engine) as session:
        assert session.exec(select(LedgerEntry).where(LedgerEntry.booking_id == bid)).all() == []
