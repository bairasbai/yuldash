"""Bounded operator passes resume a taxi checkout scan beyond the first 300 payments."""

from datetime import timedelta

import httpx
import pytest
from sqlmodel import Session, select

from app.config import settings
from app.db import engine
from app.models import InstantOrder, LedgerEntry, LedgerKind, Payment, TaxiCheckoutScan, UserRole
from app.payments import YOOKASSA_API
from app.taxi_checkout_worker import reconcile_taxi_checkout_once
from app.timeutil import utcnow

from test_money_holes_audit import _order


@pytest.mark.parametrize("variant", ["late_match", "late_conflict", "missing"])
def test_merchant_scan_resumes_without_losing_cursor_or_early_match(
    client, monkeypatch, user_factory, variant,
):
    monkeypatch.setattr(settings, "payments_provider", "yookassa")
    monkeypatch.setattr(settings, "yookassa_shop_id", "fixture-shop")
    monkeypatch.setattr(settings, "yookassa_secret_key", "fixture-not-a-credential")
    driver = user_factory("WorkerCheckoutDriver", role=UserRole.driver)
    passenger = user_factory("WorkerCheckoutPassenger")
    oid = _order(passenger["id"], driver["id"], price=600, payment_method="cash")
    now = utcnow()
    created = now - timedelta(hours=25)
    with Session(engine) as s:
        p = Payment(user_id=passenger["id"], order_id=oid, purpose="ride",
                    method="yookassa", amount_kop=60000, created_at=created)
        s.add(p)
        s.commit()
        pid = p.id
    original = f"58f76a39-0152-4249-8df6-{pid:012x}"
    other = f"026482d5-84bc-4a07-a892-{pid:012x}"
    candidate = {"id": original, "metadata": {"payment_id": str(pid)},
                 "recipient": {"account_id": "fixture-shop"},
                 "amount": {"value": "600.00", "currency": "RUB"}}
    merchant_rows = [{"id": str(i), "metadata": {}} for i in range(301)]
    if variant == "late_conflict":
        merchant_rows[0] = candidate
    if variant != "missing":
        merchant_rows.append({**candidate, "id": other if variant == "late_conflict" else original})

    list_calls, status_calls = [], []

    def fake_get(url, *, auth, timeout, params=None):
        assert auth == ("fixture-shop", "fixture-not-a-credential")
        if url == f"{YOOKASSA_API}/{original}":
            status_calls.append(url)
            assert variant == "late_match"
            # Provider read failure after link: the next command must resume status lookup.
            if len(status_calls) == 1:
                raise httpx.ReadTimeout("synthetic lost status response")
            data = {"status": "succeeded", "metadata": {"payment_id": str(pid)}}
        else:
            assert url == YOOKASSA_API and timeout == 3
            assert params["created_at.gte"] == (created - timedelta(minutes=5)).isoformat() + "Z"
            assert params["created_at.lt"] == now.isoformat() + "Z"
            assert params["limit"] == "100"
            offset = int(params.get("cursor", "0"))
            list_calls.append(offset)
            if offset == 100 and list_calls.count(100) == 1:
                raise httpx.ReadTimeout("synthetic interrupted middle page")
            data = {"type": "list", "items": merchant_rows[offset:offset + 100]}
            if offset + 100 < len(merchant_rows):
                data["next_cursor"] = str(offset + 100)
        return httpx.Response(200, request=httpx.Request("GET", url), json=data)

    def disallow_post(*args, **kwargs):
        pytest.fail("reconciliation is read-only at the bank")

    monkeypatch.setattr(httpx, "post", disallow_post)
    monkeypatch.setattr(httpx, "get", fake_get)
    # Separate DB sessions for each pass; the cutoff must not move on restart.
    for index, expectation in enumerate(("scanning", "retry_later", "scanning", "scanning")):
        assert reconcile_taxi_checkout_once(engine, pid, now=now + timedelta(minutes=index)) == expectation
        with Session(engine) as s:
            assert not s.get(Payment, pid).provider_id
            assert not s.get(InstantOrder, oid).paid
    with Session(engine) as s:
        scan = s.get(TaxiCheckoutScan, pid)
        assert scan.cutoff == now and scan.cursor == "300"
        assert scan.candidate == (original if variant == "late_conflict" else None)

    final = {"late_match": "pending", "late_conflict": "ambiguous", "missing": "not_seen"}[variant]
    assert reconcile_taxi_checkout_once(engine, pid) == final
    assert list_calls == [0, 100, 100, 200, 300]
    if variant == "late_match":
        with Session(engine) as s:
            assert s.get(Payment, pid).provider_id == original
            assert s.get(Payment, pid).status == "pending"
        final = "succeeded"
    assert reconcile_taxi_checkout_once(engine, pid) == final
    assert reconcile_taxi_checkout_once(engine, pid) == final
    assert list_calls == [0, 100, 100, 200, 300]
    with Session(engine) as s:
        payment, order = s.get(Payment, pid), s.get(InstantOrder, oid)
        assert payment.provider_id == (original if variant == "late_match" else "")
        assert payment.status == ("succeeded" if variant == "late_match" else "pending")
        assert order.paid == (variant == "late_match")
        rows = s.exec(select(LedgerEntry).where(LedgerEntry.order_id == oid)).all()
        assert [r.amount_kop for r in rows if r.kind == LedgerKind.earn] == (
            [60000] if variant == "late_match" else []
        )
    if variant != "late_match":
        assert client.post(f"/instant/orders/{oid}/cash-received", headers=driver["auth"]).status_code == 409
