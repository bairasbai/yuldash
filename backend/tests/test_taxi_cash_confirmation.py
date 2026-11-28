"""Direct cash confirmation must not race an unresolved online checkout."""

import pytest
from sqlmodel import Session, select

from app.config import settings
from app.db import engine
from app.models import InstantOrder, LedgerEntry, LedgerKind, Payment, UserRole
from app.routers.payments import _activate_payment

from test_money_holes_audit import _order


@pytest.mark.parametrize("provider_succeeds", [True, False])
def test_driver_cash_confirmation_waits_for_pending_checkout(
    client, user_factory, monkeypatch, provider_succeeds,
):
    monkeypatch.setattr(settings, "fee_tier1_percent", 3.0)
    driver = user_factory("CashCheckoutDriver", role=UserRole.driver)
    passenger = user_factory("CashCheckoutPassenger")
    oid = _order(passenger["id"], driver["id"], price=600, payment_method="cash")
    endpoint = f"/instant/orders/{oid}/cash-received"
    with Session(engine) as session:
        payment = Payment(
            user_id=passenger["id"], purpose="ride", order_id=oid, method="yookassa",
            provider_id="taxi-cash-checkout-control", amount_kop=60000, status="pending",
        )
        session.add(payment)
        session.commit()
        payment_id = payment.id

    response = client.post(endpoint, headers=driver["auth"])
    assert response.status_code == 409, response.text
    with Session(engine) as session:
        payment = session.get(Payment, payment_id)
        assert payment.status == "pending"
        assert not session.get(InstantOrder, oid).paid
        if provider_succeeds:
            _activate_payment(session, payment)
            _activate_payment(session, payment)
            assert payment.status == "succeeded"
        else:
            payment.status = "canceled"
            session.commit()

    response = client.post(endpoint, headers=driver["auth"])
    assert response.status_code == 200, response.text
    if provider_succeeds:
        assert response.json() == {"status": "already_paid", "method": "yookassa"}
    else:
        assert response.json() == {"status": "paid", "method": "cash", "amount_kop": 60000}
    with Session(engine) as session:
        order = session.get(InstantOrder, oid)
        assert order.paid and order.payment_method == ("yookassa" if provider_succeeds else "cash")
        entries = session.exec(select(LedgerEntry).where(LedgerEntry.order_id == oid)).all()
        assert [e.amount_kop for e in entries if e.kind == LedgerKind.earn] == (
            [60000] if provider_succeeds else []
        )
        assert [e.amount_kop for e in entries if e.kind == LedgerKind.fee] == (
            [-1800] if provider_succeeds else []
        )


@pytest.mark.parametrize("method,endpoint", [
    ("cash", "cash-received"),
    ("sbp", "direct-sbp-received"),
])
def test_fully_discounted_trip_cannot_be_marked_paid_by_driver(
    client, user_factory, method, endpoint,
):
    driver = user_factory("FullyDiscountedDriver", role=UserRole.driver)
    passenger = user_factory("FullyDiscountedPassenger")
    oid = _order(passenger["id"], driver["id"], price=600, payment_method=method, promo_discount_kop=60000)
    response = client.post(f"/instant/orders/{oid}/{endpoint}", headers=driver["auth"])
    assert response.status_code == 409, response.text
    with Session(engine) as session:
        order = session.get(InstantOrder, oid)
        assert not order.paid
        assert order.payment_method == method
        assert not session.exec(select(Payment).where(Payment.order_id == oid)).all()
        assert not session.exec(select(LedgerEntry).where(LedgerEntry.order_id == oid)).all()
