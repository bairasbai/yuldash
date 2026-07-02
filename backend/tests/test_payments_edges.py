"""Regression tests for payment edge cases and admin payment actions."""

from sqlmodel import Session

from app.config import settings
from app.db import engine
from app.models import Ad, Payment, Ride, UserRole

from test_flows import _publish


def test_donate_bounds_mock_success_and_admin_summary(client, user_factory):
    user = user_factory("DonateUser")

    assert client.post("/donate", headers=user["auth"], json={"amount": 9}).status_code == 400
    assert client.post("/donate", headers=user["auth"], json={"amount": 100001}).status_code == 400

    ok = client.post("/donate", headers=user["auth"], json={"amount": 123})
    assert ok.status_code == 200
    assert ok.json()["status"] == "succeeded"

    assert client.get("/admin/payments/summary", headers=user["auth"]).status_code == 403
    admin = user_factory("DonateAdmin", role=UserRole.admin)
    summary = client.get("/admin/payments/summary", headers=admin["auth"])
    assert summary.status_code == 200
    assert summary.json()["donate"]["count"] >= 1
    assert summary.json()["donate"]["sum_rub"] >= 123


def test_sbp_donate_can_be_rejected_by_admin(client, user_factory):
    user = user_factory("SbpDonateUser")
    admin = user_factory("SbpDonateAdmin", role=UserRole.admin)
    old_provider, old_phone, old_bank, old_name = (
        settings.payments_provider,
        settings.sbp_phone,
        settings.sbp_bank,
        settings.sbp_name,
    )
    settings.payments_provider = "sbp_manual"
    settings.sbp_phone = "+79990000001"
    settings.sbp_bank = "TestBank"
    settings.sbp_name = "Test Receiver"
    try:
        created = client.post("/donate", headers=user["auth"], json={"amount": 77})
        assert created.status_code == 200
        body = created.json()
        assert body["status"] == "pending"
        assert body["method"] == "sbp_manual"
        payment_id = body["payment_id"]

        pending = client.get("/admin/payments/pending", headers=admin["auth"])
        assert pending.status_code == 200
        assert any(row["payment_id"] == payment_id and row["purpose"] == "donate" for row in pending.json())

        assert client.post(f"/admin/payments/{payment_id}/reject", headers=user["auth"]).status_code == 403
        rejected = client.post(f"/admin/payments/{payment_id}/reject", headers=admin["auth"])
        assert rejected.status_code == 200
        assert rejected.json()["status"] == "canceled"

        with Session(engine) as session:
            assert session.get(Payment, payment_id).status == "canceled"
    finally:
        settings.payments_provider = old_provider
        settings.sbp_phone = old_phone
        settings.sbp_bank = old_bank
        settings.sbp_name = old_name


def test_admin_confirm_ad_payment_publishes_ad(client, user_factory):
    owner = user_factory("AdPaymentOwner")
    admin = user_factory("AdPaymentAdmin", role=UserRole.admin)
    with Session(engine) as session:
        ad = Ad(
            partner_name="Local Partner",
            title="Airport transfer",
            text="Safe ride",
            erid="test-erid",
            status="draft",
            created_by=owner["id"],
        )
        session.add(ad)
        session.commit()
        session.refresh(ad)
        payment = Payment(user_id=owner["id"], purpose="ad", ad_id=ad.id, amount_kop=9900)
        session.add(payment)
        session.commit()
        session.refresh(payment)
        payment_id = payment.id
        ad_id = ad.id

    confirm = client.post(f"/admin/payments/{payment_id}/confirm", headers=admin["auth"])
    assert confirm.status_code == 200
    assert confirm.json()["status"] == "succeeded"
    assert client.post("/admin/payments/99999999/confirm", headers=admin["auth"]).status_code == 404

    with Session(engine) as session:
        assert session.get(Payment, payment_id).status == "succeeded"
        assert session.get(Ad, ad_id).status == "active"


def test_rejected_sbp_boost_does_not_boost_ride(client, user_factory):
    driver = user_factory("RejectBoostDriver", role=UserRole.driver)
    admin = user_factory("RejectBoostAdmin", role=UserRole.admin)
    ride = _publish(client, driver, frm="RejectCity", to="Ufa")
    old_provider, old_phone = settings.payments_provider, settings.sbp_phone
    settings.payments_provider = "sbp_manual"
    settings.sbp_phone = "+79990000002"
    try:
        created = client.post("/boost/create", headers=driver["auth"], json={"ride_id": ride["id"], "tier": "quick"})
        assert created.status_code == 200
        payment_id = created.json()["payment_id"]

        rejected = client.post(f"/admin/payments/{payment_id}/reject", headers=admin["auth"])
        assert rejected.status_code == 200
        assert rejected.json()["status"] == "canceled"

        with Session(engine) as session:
            assert session.get(Payment, payment_id).status == "canceled"
            assert session.get(Ride, ride["id"]).boosted_until is None
    finally:
        settings.payments_provider = old_provider
        settings.sbp_phone = old_phone


def test_yookassa_webhook_bad_json_and_fetch_failure_are_noop(client, user_factory, monkeypatch):
    driver = user_factory("WebhookFailureDriver", role=UserRole.driver)
    ride = _publish(client, driver, frm="WebhookCity", to="Ufa")
    with Session(engine) as session:
        payment = Payment(
            user_id=driver["id"],
            purpose="boost",
            ride_id=ride["id"],
            tier="day",
            amount_kop=5000,
            provider_id="pid_fetch_failure",
            status="pending",
        )
        session.add(payment)
        session.commit()
        session.refresh(payment)
        payment_id = payment.id

    assert client.post(
        "/payments/yookassa/webhook",
        content=b"{bad-json",
        headers={"content-type": "application/json"},
    ).status_code == 200

    def fail_fetch(_provider_id):
        raise RuntimeError("network down")

    monkeypatch.setattr("app.routers.payments.fetch_payment", fail_fetch)
    assert client.post(
        "/payments/yookassa/webhook",
        json={"object": {"id": "pid_fetch_failure"}},
    ).status_code == 200

    with Session(engine) as session:
        assert session.get(Payment, payment_id).status == "pending"
        assert session.get(Ride, ride["id"]).boosted_until is None
