"""Interrupted live taxi checkout can be read back, never posted again blindly."""

from datetime import timedelta

import httpx
import pytest
from sqlmodel import Session, select

from app.config import settings
from app.db import engine
from app.models import InstantOrder, LedgerEntry, LedgerKind, Payment, User, UserRole
from app.payments import YOOKASSA_API
from app.timeutil import utcnow

from test_money_holes_audit import _order



@pytest.mark.parametrize("age_hours,result", [
    (23, "missing"),
    (25, "missing"),
    (23, "succeeded"),
    (25, "canceled"),
    (25, "conflict"),
    (25, "wrong_amount"),
    (25, "truncated"),
])
def test_interrupted_taxi_checkout_reconciles_without_resubmitting(
    client, monkeypatch, user_factory, age_hours, result,
):
    monkeypatch.setattr(settings, "payments_provider", "yookassa")
    monkeypatch.setattr(settings, "yookassa_shop_id", "fixture-shop")
    monkeypatch.setattr(settings, "yookassa_secret_key", "fixture-not-a-credential")
    driver = user_factory("ReconcileCheckoutDriver", role=UserRole.driver)
    passenger = user_factory("ReconcileCheckoutPassenger")
    oid = _order(passenger["id"], driver["id"], price=600, payment_method="cash")
    created = utcnow() - timedelta(hours=age_hours)
    with Session(engine) as s:
        # The original receipt contact is not available on the payment row.
        current_user = s.get(User, passenger["id"])
        current_user.phone = f"+7900{passenger['id']:07d}"
        s.add(current_user)
        p = Payment(user_id=passenger["id"], order_id=oid, purpose="ride", method="yookassa",
                    amount_kop=60000, created_at=created)
        s.add(p)
        s.commit()
        pid = p.id

    original = f"58f76a39-0152-4249-8df6-{pid:012x}"
    other = f"026482d5-84bc-4a07-a892-{pid:012x}"
    item = {"id": original, "metadata": {"payment_id": str(pid)},
            "recipient": {"account_id": "fixture-shop"},
            "amount": {"value": "600.00", "currency": "RUB"}}
    calls = []

    def fake_get(url, *, auth, timeout, params=None):
        calls.append((url, dict(params or {})))
        assert auth == ("fixture-shop", "fixture-not-a-credential")
        if url == f"{YOOKASSA_API}/{original}":
            assert result in {"succeeded", "canceled"}
            data = {"status": result, "metadata": {"payment_id": str(pid)}}
        else:
            assert url == YOOKASSA_API
            assert params["created_at.gte"] == (created - timedelta(minutes=5)).isoformat() + "Z"
            assert timeout == 3
            data = {"type": "list", "items": []}
            if result in {"succeeded", "canceled"}:
                if "cursor" not in params:
                    data["next_cursor"] = "second"
                else:
                    assert params["cursor"] == "second"
                    data["items"] = [item]
            elif result == "conflict":
                data["items"] = [item, {**item, "id": other}]
            elif result == "wrong_amount":
                data["items"] = [{**item, "amount": {"value": "599.00", "currency": "RUB"}}]
            elif result == "truncated":
                data["items"] = [item] if "cursor" not in params else []
                data["next_cursor"] = str(len(calls))
        return httpx.Response(200, request=httpx.Request("GET", url), json=data)

    posts = []
    monkeypatch.setattr(httpx, "get", fake_get)

    def fake_post(*args, **kwargs):
        posts.append((args, kwargs))
        return httpx.Response(200, request=httpx.Request("POST", YOOKASSA_API),
                              json={"id": other, "status": "succeeded"})

    monkeypatch.setattr(httpx, "post", fake_post)
    path = f"/instant/orders/{oid}"
    response = client.post(f"{path}/pay", headers=passenger["auth"], json={"method": "card"})
    assert posts == [], "do not re-create an unresolved payment, even with the original key"
    assert calls
    endpoint = f"{path}/cash-received"
    if result in {"succeeded", "canceled"}:
        assert response.status_code == 200 and response.json()["status"] == result
        assert response.json()["payment_id"] == pid
        assert calls[-1][0] == f"{YOOKASSA_API}/{original}"
        cash = client.post(endpoint, headers=driver["auth"])
        assert cash.status_code == 200
        assert cash.json()["status"] == ("already_paid" if result == "succeeded" else "paid")
    else:
        assert response.status_code == 409
        assert response.json()["detail"]["code"] == "taxi_checkout_unresolved"
        assert client.post(endpoint, headers=driver["auth"]).status_code == 409
        assert client.post(f"{path}/pay", headers=passenger["auth"], json={"method": "cash"}).status_code == 409
    with Session(engine) as s:
        order = s.get(InstantOrder, oid)
        row = s.exec(select(Payment).where(Payment.order_id == oid)).one()
        assert row.id == pid
        assert row.status == (result if result in {"succeeded", "canceled"} else "pending")
        assert row.provider_id == (original if result in {"succeeded", "canceled"} else "")
        assert order.paid == (result in {"succeeded", "canceled"})
        entries = s.exec(select(LedgerEntry).where(LedgerEntry.order_id == oid)).all()
        assert [e.amount_kop for e in entries if e.kind == LedgerKind.earn] == (
            [60000] if result == "succeeded" else []
        )
