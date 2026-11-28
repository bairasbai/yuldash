"""An assessed zero commission must not be recomputed at a later tariff."""

import pytest
from sqlmodel import Session, select

from app.config import settings
from app.db import engine
from app.models import CommissionDebt, InstantOrder, LedgerEntry, LedgerKind
from test_honest_receipt import _driver, _order_body
from test_honest_receipt import fake_redis as fake_redis


@pytest.mark.usefixtures("fake_redis")
@pytest.mark.parametrize("payment_method", ["cash", "sbp"])
def test_zero_fee_stays_zero_after_tariff_change(client, user_factory, monkeypatch, payment_method):
    monkeypatch.setattr(settings, "fee_tier1_percent", 0.0)
    driver = _driver(client, user_factory, "ZeroCashDriver")
    passenger = user_factory("ZeroCashPassenger")
    created = client.post("/instant/orders", headers=passenger["auth"],
                          json={**_order_body(), "payment_method": payment_method})
    assert created.status_code == 200
    order_id = created.json()["id"]
    path = f"/instant/orders/{order_id}"
    for step in ("accept", "arrived", "onboard", "done"):
        reply = client.post(f"{path}/{step}", headers=driver["auth"])
        assert reply.status_code == 200, reply.text
    assert reply.json()["driver_fee_kop"] == 0
    with Session(engine) as session:
        assert session.get(InstantOrder, order_id).commission_assessed_kop == 0

    monkeypatch.setattr(settings, "fee_tier1_percent", 12.0)
    for endpoint in (path, f"{path}/receipt"):
        receipt = client.get(endpoint, headers=driver["auth"])
        assert receipt.status_code == 200
        assert receipt.json()["driver_fee_kop"] == 0
    retried = client.post(f"{path}/done", headers=driver["auth"])
    assert retried.status_code == 200
    assert retried.json()["driver_fee_kop"] == 0
    action = "cash-received" if payment_method == "cash" else "direct-sbp-received"
    paid = client.post(f"{path}/{action}", headers=driver["auth"])
    assert paid.status_code == 200, paid.text
    receipt = client.get(f"{path}/receipt", headers=driver["auth"]).json()
    assert receipt["paid"] and receipt["driver_fee_kop"] == 0
    assert receipt["driver_net_kop"] == receipt["amount_kop"]
    history = client.get("/driver/taxi-rides", headers=driver["auth"]).json()
    assert next(r for r in history["rides"] if r["order_id"] == order_id)["fee_kop"] == 0
    with Session(engine) as session:
        assert session.exec(select(CommissionDebt.id).where(CommissionDebt.order_id == order_id)).all() == []
        assert session.exec(select(LedgerEntry.id).where(
            LedgerEntry.order_id == order_id, LedgerEntry.kind == LedgerKind.fee,
        )).all() == []
