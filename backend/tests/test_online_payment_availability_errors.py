"""Provider-off and lost-provider-reply are different errors; no real payment I/O."""

import pytest
from sqlmodel import Session, select

from app.config import settings
from app.db import engine
from app.models import InstantOrder, Payment, UserRole
from app.routers import payments as payment_routes

from test_ledger import _make_done_order


@pytest.mark.parametrize("provider, env", [("mock", "prod"), ("sbp_manual", "prod"), ("unknown", "dev")])
def test_disabled_online_payment_has_explicit_code_and_creates_nothing(
    client, user_factory, monkeypatch, provider, env,
):
    driver = user_factory("DisabledProviderDriver", role=UserRole.driver)
    passenger = user_factory("DisabledProviderPassenger")
    order_id = _make_done_order(driver["id"], passenger["id"])
    monkeypatch.setattr(settings, "payments_provider", provider)
    monkeypatch.setattr(settings, "env", env)

    def forbidden(*args, **kwargs):
        pytest.fail("a disabled provider must not be called")

    monkeypatch.setattr(payment_routes, "create_payment", forbidden)
    response = client.post(f"/instant/orders/{order_id}/pay", headers=passenger["auth"], json={"method": "card"})
    assert response.status_code == 503
    with Session(engine) as session:
        assert session.get(InstantOrder, order_id).paid is False
        assert session.exec(select(Payment).where(Payment.order_id == order_id)).all() == []
    assert response.json()["detail"]["code"] == "online_payment_disabled"


def test_lost_provider_reply_reuses_payment_key_and_fake_invoice_on_explicit_retry(
    client, user_factory, monkeypatch,
):
    driver = user_factory("LostProviderReplyDriver", role=UserRole.driver)
    passenger = user_factory("LostProviderReplyPassenger")
    order_id = _make_done_order(driver["id"], passenger["id"], price_rub=245)
    monkeypatch.setattr(settings, "payments_provider", "yookassa")
    calls = []
    invoices = {}

    def provider(amount_kop, description, metadata, customer_phone="", idempotence_key=""):
        calls.append(idempotence_key)
        invoice = invoices.setdefault(idempotence_key, {
            "provider_id": f"fake-invoice-{len(invoices) + 1}",
            "status": "pending", "confirmation_url": "https://provider.invalid/pay", "mock": False,
        })
        if len(calls) == 1:
            raise TimeoutError("fake provider accepted the request but its reply was lost")
        return invoice

    def fetch(provider_id):
        invoice = next(value for value in invoices.values() if value["provider_id"] == provider_id)
        return {"status": invoice["status"], "confirmation_url": invoice["confirmation_url"]}

    monkeypatch.setattr(payment_routes, "create_payment", provider)
    monkeypatch.setattr(payment_routes, "fetch_payment", fetch)
    path = f"/instant/orders/{order_id}/pay"
    first = client.post(path, headers=passenger["auth"], json={"method": "card"})
    assert first.status_code == 503
    with Session(engine) as session:
        payment = session.exec(select(Payment).where(Payment.order_id == order_id)).one()
        payment_id = payment.id
        assert payment.status == "pending"
        assert payment.provider_id == ""
        assert payment.amount_kop == 24_500
        assert session.get(InstantOrder, order_id).paid is False

    second = client.post(path, headers=passenger["auth"], json={"method": "card"})
    third = client.post(path, headers=passenger["auth"], json={"method": "card"})
    assert second.status_code == third.status_code == 200
    assert second.json()["payment_id"] == third.json()["payment_id"] == payment_id
    assert second.json()["status"] == third.json()["status"] == "pending"
    assert calls == [f"yuldash-payment-{payment_id}"] * 2
    assert len(invoices) == 1, "same idempotence key must reuse the fake provider invoice"
    with Session(engine) as session:
        rows = session.exec(select(Payment).where(Payment.order_id == order_id)).all()
        assert len(rows) == 1
        assert rows[0].id == payment_id
        assert rows[0].provider_id == "fake-invoice-1"
        assert session.get(InstantOrder, order_id).paid is False
    assert first.json()["detail"]["code"] == "payment_provider_unavailable"
