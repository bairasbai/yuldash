"""QA-B07-003: an edited agreement must not misrepresent an existing charge.

Publish/book/agree/complete/pay/read are actual HTTP endpoints. Only outbound invoice
creation and provider-status lookup are replaced. Payment sync, activation, settlement,
receipts and append-only ledger remain real. This does not prescribe a rejection code
or a new consent/refund policy: observations retain whether the edit was accepted.
Existing per-process SQLite proves these sequential paths, not PostgreSQL concurrency.
"""
from datetime import timedelta, timezone
import json

import pytest
from sqlmodel import Session, select

from app.config import settings
from app.db import engine
from app.models import Booking, LedgerEntry, Payment, UserRole
from app.routers import payments as payments_router
from app.routers import wallet
from app.timeutil import utcnow


@pytest.fixture
def provider_boundary(monkeypatch):
    assert settings.env == "dev" and settings.payments_provider == "mock"
    assert settings.sms_provider == "mock" and settings.ride_service_fee_percent == 0
    assert not any((settings.redis_url, settings.firebase_credentials,
                    settings.telegram_bot_token, settings.yandex_geocoder_key,
                    settings.vapid_private_key))
    invoices, lookups = {}, []
    state = {"status": "pending"}

    def create(session, payment, description, phone):
        provider_id = f"qa-after-charge-{payment.id}"
        captured = {"payment_id": payment.id, "booking_id": payment.booking_id,
                    "payer": payment.user_id, "amount_kop": payment.amount_kop,
                    "confirmation_url": f"https://qa.invalid/invoice/{payment.id}"}
        # Retrying the same external invoice cannot change its already created amount.
        assert invoices.setdefault(provider_id, captured) == captured
        payment.method = "yookassa"
        session.add(payment)
        session.commit()
        return {"provider_id": provider_id, "status": state["status"],
                "confirmation_url": captured["confirmation_url"]}

    def fetch(provider_id):
        invoice = invoices[provider_id]
        lookups.append({"provider_id": provider_id, "status": state["status"]})
        return {"status": state["status"], "metadata": {"payment_id": str(invoice["payment_id"])},
                "confirmation_url": invoice["confirmation_url"]}

    monkeypatch.setattr(wallet, "_start_yookassa", create)
    monkeypatch.setattr(payments_router, "fetch_payment", fetch)
    return {"invoices": invoices, "lookups": lookups, "state": state}


def _create_completed_booking(client, user_factory):
    driver = user_factory("QA_AFTER_CHARGE_DRIVER", role=UserRole.driver)
    passenger = user_factory("QA_AFTER_CHARGE_PASSENGER")
    depart = (utcnow() + timedelta(minutes=10)).replace(tzinfo=timezone.utc).isoformat()
    published = client.post("/rides", headers=driver["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай", "depart_at": depart,
        "seats_total": 3, "price": 1000,
    })
    assert published.status_code == 200, published.text
    booked = client.post("/bookings", headers=passenger["auth"], json={
        "ride_id": published.json()["id"], "seats": 1,
    })
    assert booked.status_code == 200, booked.text
    booking_id = booked.json()["id"]
    agreed = client.post(f"/bookings/{booking_id}/pay-agreement", headers=driver["auth"],
                         json={"pay_method": "sbp", "pay_amount": 400})
    assert agreed.status_code == 200 and agreed.json()["pay_amount"] == 400, agreed.text
    confirmed = client.post(f"/bookings/{booking_id}/confirm", headers=driver["auth"])
    assert confirmed.status_code == 200, confirmed.text
    completed = client.post(f"/bookings/{booking_id}/driver-status", headers=driver["auth"],
                            json={"status": "done"})
    assert completed.status_code == 200 and completed.json()["status"] == "done", completed.text
    return driver, passenger, booking_id


def _financial_rows(booking_id):
    with Session(engine) as session:
        booking = session.get(Booking, booking_id)
        payments = session.exec(select(Payment).where(Payment.booking_id == booking_id)
                                .order_by(Payment.id)).all()
        ledger = session.exec(select(LedgerEntry).where(LedgerEntry.booking_id == booking_id)
                              .order_by(LedgerEntry.id)).all()
        return {"paid": bool(booking.paid), "agreement_rub": booking.pay_amount,
                "payments": [{"id": p.id, "amount_kop": p.amount_kop, "status": p.status,
                              "provider_id": p.provider_id, "method": p.method} for p in payments],
                "ledger": [{"id": e.id, "driver": e.driver_id, "kind": e.kind.value,
                            "amount_kop": e.amount_kop, "ext_id": e.ext_id,
                            "created_at": e.created_at.isoformat(), "note": e.note} for e in ledger]}


def _participant_views(client, driver, passenger, booking_id):
    views = {}
    for role, participant in (("driver", driver), ("passenger", passenger)):
        details = client.get(f"/bookings/{booking_id}/details", headers=participant["auth"])
        receipt = client.get(f"/trips/{booking_id}/receipt", headers=participant["auth"])
        assert details.status_code == receipt.status_code == 200
        views[role] = {"agreement_rub": details.json()["pay_amount"],
                       "receipt_rub": receipt.json()["amount"], "paid": receipt.json()["paid"]}
    return views


def _pay(client, passenger, booking_id):
    response = client.post(f"/bookings/{booking_id}/pay", headers=passenger["auth"],
                           json={"method": "card"})
    assert response.status_code == 200, response.text
    return response.json()


def _edit(client, participant, booking_id, amount):
    response = client.post(f"/bookings/{booking_id}/pay-agreement", headers=participant["auth"],
                           json={"pay_amount": amount})
    # The audit requires consistency, not an invented rule that edits must return 409.
    assert response.status_code < 500, response.text
    return {"http": response.status_code, "body": response.json()}


def _assert_settled(client, driver, passenger, booking_id, provider, paid_views, rows):
    assert len(provider["invoices"]) == len(rows["payments"]) == 1, "one logical payment, one invoice"
    invoice = next(iter(provider["invoices"].values()))
    assert invoice["amount_kop"] == 40000
    assert rows["paid"] and rows["payments"][0]["status"] == "succeeded"
    assert rows["payments"][0]["amount_kop"] == invoice["amount_kop"]
    assert len(rows["ledger"]) == 1
    assert rows["ledger"][0]["driver"] == driver["id"]
    assert rows["ledger"][0]["kind"] == "earn"
    assert rows["ledger"][0]["amount_kop"] == invoice["amount_kop"]
    balance = client.get("/wallet/balance", headers=driver["auth"])
    assert balance.status_code == 200 and balance.json()["balance_kop"] == invoice["amount_kop"]
    for view in paid_views.values():
        assert view["paid"] is True
        assert view["receipt_rub"] * 100 == invoice["amount_kop"], "paid receipt must match actual charge/earn"


def _pending_journey(client, user_factory, provider, editor, edit_amount):
    driver, passenger, booking_id = _create_completed_booking(client, user_factory)
    initial = _pay(client, passenger, booking_id)
    assert initial["status"] == "pending"
    initial_rows = _financial_rows(booking_id)
    initial_views = _participant_views(client, driver, passenger, booking_id)
    edit = _edit(client, driver if editor == "driver" else passenger, booking_id, edit_amount)
    after_edit = _participant_views(client, driver, passenger, booking_id)
    repeat_pending = _pay(client, passenger, booking_id)
    pending_rows = _financial_rows(booking_id)
    pending_views = _participant_views(client, driver, passenger, booking_id)
    provider["state"]["status"] = "succeeded"
    resolved = _pay(client, passenger, booking_id)  # real provider sync -> activation -> ledger
    settled_rows = _financial_rows(booking_id)
    paid_views = _participant_views(client, driver, passenger, booking_id)
    final_repeat = _pay(client, passenger, booking_id)
    final_rows = _financial_rows(booking_id)
    observed = {"journey": "pending_then_edit", "editor": editor, "edit_amount_rub": edit_amount,
                "initial": initial, "initial_rows": initial_rows, "initial_views": initial_views,
                "edit": edit, "after_edit": after_edit, "repeat_pending": repeat_pending,
                "pending_rows": pending_rows, "pending_views": pending_views, "resolved": resolved,
                "settled_rows": settled_rows, "paid_views": paid_views, "final_repeat": final_repeat,
                "final_rows": final_rows, "provider": provider}
    print("QA_AFTER_CHARGE " + json.dumps(observed, ensure_ascii=False, sort_keys=True))
    # Capture every stage before asserting the candidate inconsistency.
    assert initial_rows["ledger"] == pending_rows["ledger"] == []
    assert repeat_pending["status"] == "pending" and repeat_pending["payment_id"] == initial["payment_id"]
    assert repeat_pending["confirmation_url"] == initial["confirmation_url"]
    assert resolved["status"] == "succeeded" and final_repeat["status"] == "already_paid"
    assert final_rows == settled_rows, "payment retry must not alter financial history"
    assert pending_rows["payments"] == initial_rows["payments"], "an issued invoice amount is immutable"
    if edit["http"] != 200:
        assert after_edit == initial_views, "a rejected agreement must not silently mutate the view"
    _assert_settled(client, driver, passenger, booking_id, provider, paid_views, settled_rows)
    invoice = next(iter(provider["invoices"].values()))
    for view in pending_views.values():
        assert view["paid"] is False
        assert view["receipt_rub"] * 100 == invoice["amount_kop"], "visible pending amount must match returned invoice"


def _paid_journey(client, user_factory, provider, editor, edit_amount):
    provider["state"]["status"] = "succeeded"
    driver, passenger, booking_id = _create_completed_booking(client, user_factory)
    initial = _pay(client, passenger, booking_id)
    assert initial["status"] == "succeeded"
    initial_rows = _financial_rows(booking_id)
    initial_views = _participant_views(client, driver, passenger, booking_id)
    edit = _edit(client, driver if editor == "driver" else passenger, booking_id, edit_amount)
    after_edit = _participant_views(client, driver, passenger, booking_id)
    repeated = _pay(client, passenger, booking_id)
    final_rows = _financial_rows(booking_id)
    final_views = _participant_views(client, driver, passenger, booking_id)
    observed = {"journey": "paid_then_edit", "editor": editor, "edit_amount_rub": edit_amount,
                "initial": initial, "initial_rows": initial_rows, "initial_views": initial_views,
                "edit": edit, "after_edit": after_edit, "repeated": repeated,
                "final_rows": final_rows, "final_views": final_views, "provider": provider}
    print("QA_AFTER_CHARGE " + json.dumps(observed, ensure_ascii=False, sort_keys=True))
    assert repeated["status"] == "already_paid"
    assert final_rows["payments"] == initial_rows["payments"]
    assert final_rows["ledger"] == initial_rows["ledger"], "append-only history cannot rewrite a settled charge"
    if edit["http"] != 200:
        assert after_edit == initial_views, "a rejected agreement must not silently mutate the view"
    _assert_settled(client, driver, passenger, booking_id, provider, final_views, final_rows)


@pytest.mark.parametrize("editor", ["driver", "passenger"])
def test_pending_invoice_edit_keeps_receipt_charge_and_earnings_consistent(
        client, user_factory, provider_boundary, editor):
    _pending_journey(client, user_factory, provider_boundary, editor, 1200)


@pytest.mark.parametrize("editor", ["driver", "passenger"])
def test_paid_invoice_edit_cannot_rewrite_the_paid_receipt(
        client, user_factory, provider_boundary, editor):
    _paid_journey(client, user_factory, provider_boundary, editor, 1200)


@pytest.mark.parametrize("initial_status", ["pending", "succeeded"])
def test_unchanged_agreement_preserves_invoice_and_receipt_control(
        client, user_factory, provider_boundary, initial_status):
    journey = _pending_journey if initial_status == "pending" else _paid_journey
    journey(client, user_factory, provider_boundary, "passenger", 400)


@pytest.mark.parametrize("initial_status", ["pending", "succeeded"])
def test_rejected_combined_update_changes_neither_method_nor_amount(
        client, user_factory, provider_boundary, initial_status):
    provider_boundary["state"]["status"] = initial_status
    driver, passenger, bid = _create_completed_booking(client, user_factory)
    _pay(client, passenger, bid)
    before = _financial_rows(bid)
    before_views = _participant_views(client, driver, passenger, bid)
    response = client.post(f"/bookings/{bid}/pay-agreement", headers=driver["auth"],
                           json={"pay_method": "cash", "pay_amount": 1200})
    assert response.status_code == 409, response.text
    assert set(response.json()["detail"]) == {"ru", "ba"}
    assert _financial_rows(bid) == before
    assert _participant_views(client, driver, passenger, bid) == before_views
    for participant in (driver, passenger):
        details = client.get(f"/bookings/{bid}/details", headers=participant["auth"])
        assert details.status_code == 200 and details.json()["pay_method"] == "sbp"


@pytest.mark.parametrize("legacy_null", [False, True])
def test_same_effective_amount_and_method_only_preserve_existing_invoice(
        client, user_factory, provider_boundary, legacy_null):
    driver, passenger, bid = _create_completed_booking(client, user_factory)
    amount = 1000 if legacy_null else 400
    if legacy_null:
        with Session(engine) as session:
            booking = session.get(Booking, bid)
            booking.pay_amount = None
            session.add(booking)
            session.commit()
    initial = _pay(client, passenger, bid)
    before = _financial_rows(bid)
    for payload in ({"pay_method": "cash"}, {"pay_amount": amount, "pay_method": "sbp"}):
        updated = client.post(f"/bookings/{bid}/pay-agreement", headers=passenger["auth"], json=payload)
        assert updated.status_code == 200, updated.text
        repeated = _pay(client, passenger, bid)
        assert repeated["payment_id"] == initial["payment_id"]
        assert _financial_rows(bid)["payments"] == before["payments"]
        assert _financial_rows(bid)["ledger"] == []
        for view in _participant_views(client, driver, passenger, bid).values():
            assert view["receipt_rub"] == amount and not view["paid"]
    assert len(provider_boundary["invoices"]) == 1
    assert next(iter(provider_boundary["invoices"].values()))["amount_kop"] == amount * 100


def test_cash_paid_amount_is_frozen_without_creating_online_charge(
        client, user_factory, provider_boundary):
    driver, passenger, bid = _create_completed_booking(client, user_factory)
    paid = client.post(f"/bookings/{bid}/pay", headers=passenger["auth"], json={"method": "cash"})
    assert paid.status_code == 200 and paid.json()["status"] == "paid", paid.text
    before = _financial_rows(bid)
    assert before["paid"] and before["payments"] == before["ledger"] == []
    changed = _edit(client, driver, bid, 1200)
    assert changed["http"] == 409
    same = _edit(client, passenger, bid, 400)
    assert same["http"] == 200
    assert _financial_rows(bid) == before
    for view in _participant_views(client, driver, passenger, bid).values():
        assert view["paid"] and view["receipt_rub"] == 400
    assert provider_boundary["invoices"] == {} and provider_boundary["lookups"] == []
    balance = client.get("/wallet/balance", headers=driver["auth"])
    assert balance.status_code == 200 and balance.json()["balance_kop"] == 0
