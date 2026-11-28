"""Order «Сейчас»: authenticated passenger and driver APIs through receipt and history."""
from datetime import timedelta
from uuid import uuid4

import fakeredis
import httpx
import pytest
from sqlmodel import Session, select

from app import instant_service
from app.config import settings
from app.db import engine
from app.models import CommissionDebt, InstantOrder, LedgerEntry, LedgerKind, UserRole
from app.payments import YOOKASSA_API
from app.taxi_checkout_sweep import sweep_taxi_checkouts
from app.timeutil import utcnow
from test_honest_receipt import ORIG, _driver, _order_body
from test_promo_taxi import _pax_with_discount


@pytest.fixture
def isolated_matcher(monkeypatch):
    redis = fakeredis.FakeStrictRedis(decode_responses=True)
    monkeypatch.setattr(instant_service, "_redis_override", redis)


def _ok(response):
    assert response.status_code == 200, response.text
    return response.json()


@pytest.mark.parametrize("method,promo_rub", [
    ("cash", 0), ("sbp", 0), ("card", 0), ("card", 30), ("card", 1),
])
def test_taxi_now_money_journey(client, user_factory, isolated_matcher, monkeypatch, method, promo_rub):
    monkeypatch.setattr(settings, "fee_tier1_percent", 3.0)
    monkeypatch.setattr(settings, "payments_provider", "yookassa")
    monkeypatch.setattr(settings, "yookassa_shop_id", "fixture-journey")
    monkeypatch.setattr(settings, "yookassa_secret_key", "fixture-not-a-credential")
    monkeypatch.setattr(settings, "taxi_checkout_sweep_enabled", True)
    driver = _driver(client, user_factory, "JourneyDriver", coord=ORIG)
    if promo_rub:
        admin = user_factory("JourneyPromoAdmin", role=UserRole.admin)
        passenger = _pax_with_discount(
            client, user_factory, admin, f"JOURNEY{promo_rub}", rub=promo_rub, name="JourneyPassenger",
        )
    else:
        passenger = user_factory("JourneyPassenger")
    outsider = user_factory("JourneyOutsider")
    pax, drv = passenger["auth"], driver["auth"]
    # 'sbp' here is negotiated direct transfer, not the separate bank-hosted online checkout.
    request = {**_order_body(), "payment_method": method}
    quote = _ok(client.post("/instant/estimate", headers=pax, json=request))
    created = _ok(client.post("/instant/orders", headers=pax, json=request))
    oid = created["id"]
    path = f"/instant/orders/{oid}"
    assert (created["status"], created["price_estimate"]) == ("offered", quote["price"])
    offer = _ok(client.get("/instant/driver/offer", headers=drv))["offer"]
    assert offer["id"] == oid
    assert offer["driver_net_kop"] > 0
    for action, expected in (("accept", "accepted"), ("arrived", "arriving"),
                             ("onboard", "onboard"), ("done", "done")):
        assert _ok(client.post(f"{path}/{action}", headers=drv))["status"] == expected
        assert _ok(client.get(path, headers=pax))["status"] == expected

    passenger_before = _ok(client.get(f"{path}/receipt", headers=pax))
    driver_before = _ok(client.get(f"{path}/receipt", headers=drv))
    gross = quote["price"] * 100
    assert passenger_before["amount_kop"] == gross - promo_rub * 100
    assert passenger_before["paid"] is False
    assert driver_before["driver_net_kop"] == gross - (gross * 3 // 100)
    assert driver_before["driver_fee_kop"] == max((gross * 3 // 100) - promo_rub * 100, 0)

    monkeypatch.setattr(settings, "fee_tier1_percent", 12.0)
    if method == "card":
        bank_id = str(uuid4())
        bank_calls = []
        checkout_now = utcnow()

        def post(url, **kwargs):
            assert url == YOOKASSA_API and not bank_calls
            bank_calls.append(kwargs["json"])
            return httpx.Response(200, request=httpx.Request("POST", url), json={
                "id": bank_id, "status": "pending",
                "confirmation": {"confirmation_url": "https://yookassa.ru/fixture"},
            })

        monkeypatch.setattr(httpx, "post", post)
        opened = _ok(client.post(f"{path}/pay", headers=pax, json={"method": "card"}))
        assert opened["status"] == "pending"
        pid = opened["payment_id"]
        assert _ok(client.get(f"{path}/receipt", headers=pax))["online_payment_pending"]
        assert client.post(f"{path}/cash-received", headers=drv).status_code == 409

        def get(url, *, auth, timeout, **kwargs):
            assert url == f"{YOOKASSA_API}/{bank_id}" and auth[0] == "fixture-journey" and timeout == 3
            return httpx.Response(200, request=httpx.Request("GET", url), json={
                "id": bank_id, "status": "succeeded", "metadata": {"payment_id": str(pid)},
                "recipient": {"account_id": "fixture-journey"}, "amount": bank_calls[0]["amount"],
            })

        monkeypatch.setattr(httpx, "get", get)
        assert sweep_taxi_checkouts(now=checkout_now + timedelta(minutes=6)) == 1
        assert sweep_taxi_checkouts(now=checkout_now + timedelta(minutes=6)) == 0
        assert len(bank_calls) == 1
        assert _ok(client.post(f"{path}/pay", headers=pax, json={"method": "check"}))["status"] == "already_paid"
        expected_method = "yookassa"
    else:
        action = "cash-received" if method == "cash" else "direct-sbp-received"
        assert _ok(client.post(f"{path}/{action}", headers=drv))["status"] == "paid"
        assert _ok(client.post(f"{path}/{action}", headers=drv))["status"] == "already_paid"
        expected_method = method

    assert _ok(client.post(f"{path}/done", headers=drv))["status"] == "done"
    receipt = _ok(client.get(f"{path}/receipt", headers=pax))
    driver_receipt = _ok(client.get(f"{path}/receipt", headers=drv))
    history = _ok(client.get("/driver/taxi-rides", headers=drv))["rides"]
    ride = next(item for item in history if item["order_id"] == oid)
    assert receipt["paid"] and receipt["amount_kop"] == passenger_before["amount_kop"]
    assert receipt["payment_method"] == expected_method
    assert "driver_fee_kop" not in receipt
    assert client.get(f"{path}/receipt", headers=outsider["auth"]).status_code == 403
    assert driver_receipt["driver_fee_kop"] == ride["fee_kop"] == driver_before["driver_fee_kop"]
    assert driver_receipt["driver_net_kop"] == ride["net_kop"] == driver_before["driver_net_kop"]
    with Session(engine) as session:
        assert session.get(InstantOrder, oid).commission_assessed_kop == ride["fee_kop"]
        earn = session.exec(select(LedgerEntry.amount_kop).where(
            LedgerEntry.order_id == oid, LedgerEntry.kind == LedgerKind.earn,
        )).all()
        assert earn == ([receipt["amount_kop"]] if method == "card" else [])
        assert len(session.exec(select(CommissionDebt.id).where(CommissionDebt.order_id == oid)).all()) <= 1
