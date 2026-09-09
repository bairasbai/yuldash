"""Регрессия BE06: все денежные маршруты повторяют неизвестный платёж с тем же ключом."""

import pytest
from sqlmodel import Session, select

from app.config import settings
from app.db import engine
from app.models import CommissionDebt, Payment, UserRole

from test_courier_c3 import _deliver, _make_courier
from test_ledger import _make_done_booking, _make_done_order


def _timeout_then_pending(calls: list[str], provider_id: str):
    def create_payment(
        amount_kop, description, metadata, customer_phone="", idempotence_key="",
    ):
        calls.append(idempotence_key)
        if len(calls) == 1:
            raise TimeoutError("provider accepted, response was lost")
        return {
            "provider_id": provider_id,
            "confirmation_url": f"https://pay.example/{provider_id}",
            "status": "pending",
            "mock": False,
        }

    return create_payment


def test_debt_timeout_retries_the_same_payment(client, user_factory, monkeypatch):
    driver = user_factory("Be06RetryDebt", role=UserRole.driver)
    with Session(engine) as session:
        session.add(CommissionDebt(
            driver_id=driver["id"], amount_kop=1_725, week="2026-W37",
        ))
        session.commit()

    monkeypatch.setattr(settings, "payments_provider", "yookassa")
    calls: list[str] = []
    monkeypatch.setattr(
        "app.routers.payments.create_payment",
        _timeout_then_pending(calls, "yk_be06_retry_debt"),
    )

    first = client.post("/driver/debt/paid", headers=driver["auth"])
    assert first.status_code == 503
    with Session(engine) as session:
        first_payment = session.exec(select(Payment).where(
            Payment.user_id == driver["id"], Payment.purpose == "taxi_debt",
        )).one()
        payment_id = first_payment.id
        assert first_payment.amount_kop == 1_725
        assert first_payment.provider_id == ""

    second = client.post("/driver/debt/paid", headers=driver["auth"])
    assert second.status_code == 200, second.text
    assert second.json()["payment_id"] == payment_id
    assert second.json()["confirmation_url"] == "https://pay.example/yk_be06_retry_debt"
    assert calls == [f"yuldash-payment-{payment_id}"] * 2
    with Session(engine) as session:
        rows = session.exec(select(Payment).where(
            Payment.user_id == driver["id"], Payment.purpose == "taxi_debt",
        )).all()
        assert len(rows) == 1
        assert rows[0].amount_kop == 1_725
        assert rows[0].provider_id == "yk_be06_retry_debt"


@pytest.mark.parametrize("kind", ["instant", "booking"])
def test_trip_timeout_retries_the_same_payment(client, user_factory, monkeypatch, kind):
    driver = user_factory(f"Be06Retry{kind}Driver", role=UserRole.driver)
    passenger = user_factory(f"Be06Retry{kind}Passenger")
    if kind == "instant":
        target_id = _make_done_order(driver["id"], passenger["id"], price_rub=245)
        path = f"/instant/orders/{target_id}/pay"
        purpose = "ride"
        link_field = "order_id"
    else:
        target_id = _make_done_booking(driver["id"], passenger["id"], price_rub=345)
        path = f"/bookings/{target_id}/pay"
        purpose = "booking"
        link_field = "booking_id"

    monkeypatch.setattr(settings, "payments_provider", "yookassa")
    calls: list[str] = []
    provider_id = f"yk_be06_retry_{kind}"
    monkeypatch.setattr(
        "app.routers.payments.create_payment",
        _timeout_then_pending(calls, provider_id),
    )

    first = client.post(path, headers=passenger["auth"], json={"method": "card"})
    assert first.status_code == 503
    with Session(engine) as session:
        first_payment = session.exec(select(Payment).where(
            Payment.user_id == passenger["id"], Payment.purpose == purpose,
        )).one()
        payment_id = first_payment.id
        amount_kop = first_payment.amount_kop
        assert getattr(first_payment, link_field) == target_id
        assert first_payment.provider_id == ""

    second = client.post(path, headers=passenger["auth"], json={"method": "card"})
    assert second.status_code == 200, second.text
    assert second.json()["payment_id"] == payment_id
    assert second.json()["confirmation_url"] == f"https://pay.example/{provider_id}"
    assert calls == [f"yuldash-payment-{payment_id}"] * 2
    with Session(engine) as session:
        rows = session.exec(select(Payment).where(
            Payment.user_id == passenger["id"], Payment.purpose == purpose,
        )).all()
        assert len(rows) == 1
        assert rows[0].amount_kop == amount_kop
        assert getattr(rows[0], link_field) == target_id
        assert rows[0].provider_id == provider_id


def test_courier_timeout_retries_the_same_payment(client, user_factory, monkeypatch):
    monkeypatch.setattr(settings, "courier_enabled", True)
    monkeypatch.setattr(settings, "payments_provider", "yookassa")
    courier = _make_courier(client, user_factory, name="Be06RetryCourier")
    sender = user_factory("Be06RetryCourierSender")
    _deliver(client, courier, sender)

    calls: list[str] = []
    monkeypatch.setattr(
        "app.routers.payments.create_payment",
        _timeout_then_pending(calls, "yk_be06_retry_courier"),
    )

    first = client.post("/courier/pay-commission", headers=courier["auth"])
    assert first.status_code == 503
    with Session(engine) as session:
        first_payment = session.exec(select(Payment).where(
            Payment.user_id == courier["id"], Payment.purpose == "courier_commission",
        )).one()
        payment_id = first_payment.id
        amount_kop = first_payment.amount_kop
        assert first_payment.provider_id == ""

    second = client.post("/courier/pay-commission", headers=courier["auth"])
    assert second.status_code == 200, second.text
    assert second.json()["payment_id"] == payment_id
    assert second.json()["confirmation_url"] == "https://pay.example/yk_be06_retry_courier"
    assert calls == [f"yuldash-payment-{payment_id}"] * 2
    with Session(engine) as session:
        rows = session.exec(select(Payment).where(
            Payment.user_id == courier["id"], Payment.purpose == "courier_commission",
        )).all()
        assert len(rows) == 1
        assert rows[0].amount_kop == amount_kop
        assert rows[0].provider_id == "yk_be06_retry_courier"
