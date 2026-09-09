"""Регрессия BE06: неизвестный исход создания платежа в ЮKassa."""

import pytest

from sqlmodel import Session, select

from app.config import settings
from app.db import engine
from app.models import Payment, UserRole

from test_flows import _publish


def test_yookassa_timeout_keeps_payment_and_retries_same_idempotence_key(
    client, user_factory, monkeypatch,
):
    """Обрыв ответа не теряет счёт и не превращает карточный платёж в ручной СБП."""
    driver = user_factory("Be06Driver", role=UserRole.driver)
    admin = user_factory("Be06Admin", role=UserRole.admin)
    ride = _publish(client, driver, frm="Be06From", to="Be06To")
    old = (settings.payments_provider, settings.yookassa_shop_id, settings.yookassa_secret_key)
    settings.payments_provider = "yookassa"
    settings.yookassa_shop_id = "shop"
    settings.yookassa_secret_key = "secret"

    calls: list[str] = []
    accepted: dict[str, str] = {}

    def provider_accepts_but_first_response_times_out(
        amount_kop, description, metadata, customer_phone="", idempotence_key="",
    ):
        calls.append(idempotence_key)
        provider_id = accepted.setdefault(idempotence_key, "yk_be06_accepted")
        if len(calls) == 1:
            raise TimeoutError("provider accepted, response was lost")
        return {
            "provider_id": provider_id,
            "confirmation_url": "https://pay.example/be06",
            "status": "pending",
            "mock": False,
        }

    monkeypatch.setattr(
        "app.routers.payments.create_payment",
        provider_accepts_but_first_response_times_out,
    )
    try:
        first = client.post(
            "/boost/create",
            headers=driver["auth"],
            json={"ride_id": ride["id"], "tier": "quick"},
        )
        assert first.status_code == 503

        with Session(engine) as session:
            payment = session.exec(
                select(Payment).where(
                    Payment.user_id == driver["id"],
                    Payment.purpose == "boost",
                    Payment.ride_id == ride["id"],
                )
            ).one()
            payment_id = payment.id
            assert payment.status == "pending"
            assert payment.method == "yookassa"
            assert payment.provider_id == ""

        manual_queue = client.get("/admin/payments/pending", headers=admin["auth"])
        assert manual_queue.status_code == 200
        assert payment_id not in {row["payment_id"] for row in manual_queue.json()}
        assert client.post(
            f"/admin/payments/{payment_id}/confirm", headers=admin["auth"],
        ).status_code == 409

        second = client.post(
            "/boost/create",
            headers=driver["auth"],
            json={"ride_id": ride["id"], "tier": "quick"},
        )
        assert second.status_code == 200
        assert second.json()["payment_id"] == payment_id
        assert second.json()["confirmation_url"] == "https://pay.example/be06"
        assert calls == [
            f"yuldash-payment-{payment_id}",
            f"yuldash-payment-{payment_id}",
        ]

        with Session(engine) as session:
            rows = session.exec(
                select(Payment).where(
                    Payment.user_id == driver["id"],
                    Payment.purpose == "boost",
                    Payment.ride_id == ride["id"],
                )
            ).all()
            assert len(rows) == 1
            assert rows[0].provider_id == "yk_be06_accepted"
    finally:
        settings.payments_provider, settings.yookassa_shop_id, settings.yookassa_secret_key = old


@pytest.mark.parametrize(
    ("path", "body", "purpose", "name"),
    [
        ("/donate", {"amount": 50}, "donate", "Be06Donate"),
        ("/support/donate", {"amount_kop": 5000}, "support", "Be06Support"),
    ],
)
def test_yookassa_timeout_reuses_same_donate_or_support_payment(
    client, user_factory, monkeypatch, path, body, purpose, name,
):
    """Оба общих платёжных маршрута после таймаута повторяют ту же локальную строку."""
    user = user_factory(name)
    old = (settings.payments_provider, settings.yookassa_shop_id, settings.yookassa_secret_key)
    settings.payments_provider = "yookassa"
    settings.yookassa_shop_id = "shop"
    settings.yookassa_secret_key = "secret"
    calls: list[str] = []

    def timeout_then_success(
        amount_kop, description, metadata, customer_phone="", idempotence_key="",
    ):
        calls.append(idempotence_key)
        if len(calls) == 1:
            raise TimeoutError("provider accepted, response was lost")
        return {
            "provider_id": f"yk_be06_{purpose}",
            "confirmation_url": f"https://pay.example/be06/{purpose}",
            "status": "pending",
            "mock": False,
        }

    monkeypatch.setattr("app.routers.payments.create_payment", timeout_then_success)
    try:
        assert client.post(path, headers=user["auth"], json=body).status_code == 503
        with Session(engine) as session:
            payment = session.exec(
                select(Payment).where(
                    Payment.user_id == user["id"], Payment.purpose == purpose,
                )
            ).one()
            payment_id = payment.id
            assert payment.method == "yookassa"

        second = client.post(path, headers=user["auth"], json=body)
        assert second.status_code == 200
        assert second.json()["payment_id"] == payment_id
        assert calls == [
            f"yuldash-payment-{payment_id}",
            f"yuldash-payment-{payment_id}",
        ]
        with Session(engine) as session:
            rows = session.exec(
                select(Payment).where(
                    Payment.user_id == user["id"], Payment.purpose == purpose,
                )
            ).all()
            assert len(rows) == 1
    finally:
        settings.payments_provider, settings.yookassa_shop_id, settings.yookassa_secret_key = old
