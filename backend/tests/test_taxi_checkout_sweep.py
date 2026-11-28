"""Taxi recovery uses bounded authenticated reads, not a second checkout."""
from datetime import timedelta
from pathlib import Path

import httpx
import pytest
from alembic import command
from alembic.config import Config
from sqlalchemy import create_engine, inspect, text
from sqlmodel import Session, select

from app import taxi_worker
from app.config import settings
from app.db import engine
from app.models import InstantOrder, LedgerEntry, LedgerKind, Payment, TaxiCheckoutScan, UserRole
from app.payments import YOOKASSA_API
from app.taxi_checkout_sweep import _claim, sweep_taxi_checkouts
from app.timeutil import utcnow
from test_money_holes_audit import _order

SHOP = "fixture-merchant"
BANK_ID = "58782bd0-66fe-4849-9045-ba115daa8a72"


@pytest.fixture
def provider(monkeypatch):
    monkeypatch.setattr(settings, "taxi_checkout_sweep_enabled", True)
    monkeypatch.setattr(settings, "payments_provider", "yookassa")
    monkeypatch.setattr(settings, "yookassa_shop_id", SHOP)
    monkeypatch.setattr(settings, "yookassa_secret_key", "fixture-not-a-key")


def _bank(pid, status, provider_id=BANK_ID):
    return {"id": provider_id, "status": status, "metadata": {"payment_id": str(pid)},
            "recipient": {"account_id": SHOP}, "amount": {"value": "600.00", "currency": "RUB"}}


def _response(method, url, payload):
    return httpx.Response(200, request=httpx.Request(method, url), json=payload)


def _payment(passenger_id, order_id, when, **updates):
    fields = dict(user_id=passenger_id, order_id=order_id, purpose="ride",
                  method="yookassa", amount_kop=60000, created_at=when)
    fields.update(updates)
    with Session(engine) as s:
        row = Payment(**fields)
        s.add(row)
        s.commit()
        return row.id


@pytest.mark.parametrize("status", ["succeeded", "canceled", "pending", "wrong_amount", "wrong_merchant", "wrong_meta"])
def test_normal_checkout_is_recovered_after_acknowledged_webhook_loses_its_read(
    client, monkeypatch, provider, user_factory, status,
):
    passenger = user_factory("SweptPassenger")
    driver = user_factory("SweptDriver", role=UserRole.driver)
    oid = _order(passenger["id"], driver["id"], price=600)
    provider_id = f"58782bd0-66fe-4849-9045-{oid:012x}"
    path = f"/instant/orders/{oid}/pay"
    posted, reads = [], []
    now = utcnow()

    def post(url, **kwargs):
        assert not posted and url == YOOKASSA_API
        posted.append(kwargs)
        return _response("POST", url, {
            "id": provider_id, "status": "pending",
            "confirmation": {"confirmation_url": "https://yookassa.ru/fixture"},
        })

    monkeypatch.setattr(httpx, "post", post)
    checkout = client.post(path, json={"method": "card"}, headers=passenger["auth"])
    assert checkout.status_code == 200, checkout.text
    pid = checkout.json()["payment_id"]

    def get(url, *, auth, timeout, **kwargs):
        assert url == f"{YOOKASSA_API}/{provider_id}" and auth[0] == SHOP
        reads.append(timeout)
        if len(reads) == 1:
            raise httpx.ReadTimeout("status read failed before webhook acknowledgment")
        data = _bank(pid, status if not status.startswith("wrong") else "succeeded", provider_id)
        if status == "wrong_amount":
            data["amount"]["value"] = "660.00"
        if status == "wrong_merchant":
            data["recipient"]["account_id"] = "some-other-shop"
        if status == "wrong_meta":
            data["metadata"]["payment_id"] = str(pid + 1)
        return _response("GET", url, data)

    monkeypatch.setattr(httpx, "get", get)
    assert client.post("/payments/yookassa/webhook", json={"object": {"id": provider_id}}).status_code == 200
    with Session(engine) as s:
        assert s.get(Payment, pid).status == "pending"
        assert s.get(Payment, pid).provider_id == provider_id
        assert s.get(TaxiCheckoutScan, pid) is None
        assert taxi_worker.run_once(s, dry_run=True)["taxi_checkouts_polled"] == 0
    assert reads == [15]
    assert sweep_taxi_checkouts(now=now + timedelta(minutes=6)) == 1
    assert sweep_taxi_checkouts(now=now + timedelta(minutes=7)) == 0
    assert reads == [15, 3]
    assert len(posted) == 1

    with Session(engine) as s:
        attempt = s.get(Payment, pid)
        assert attempt.status == ("pending" if status.startswith("wrong") else status)
        assert (attempt.taxi_provider_canceled_at is not None) == (status == "canceled")
        assert s.get(InstantOrder, oid).paid == (status == "succeeded")
        assert list(s.exec(select(LedgerEntry.amount_kop).where(
            LedgerEntry.order_id == oid, LedgerEntry.kind == LedgerKind.earn,
        ))) == ([60000] if status == "succeeded" else [])

    if status == "canceled":
        # The original receipt is still open. A new unresolved charge supersedes the old cancellation.
        r = client.post(path, json={"method": "check"}, headers=passenger["auth"])
        assert r.status_code == 200 and r.json()["status"] == "canceled"
        assert r.json()["payment_id"] == pid and reads == [15, 3]
        second = _payment(passenger["id"], oid, now)
        with monkeypatch.context() as offline:
            offline.setattr(settings, "payments_provider", "unavailable")
            r = client.post(path, json={"method": "check"}, headers=passenger["auth"])
        assert r.status_code == 409 and r.json()["detail"]["code"] == "taxi_checkout_unresolved"
        newer_id = f"1482f6c3-4a0e-4226-a1a7-{pid:012x}"
        with Session(engine) as s:
            attempt = s.get(Payment, second)
            attempt.provider_id = newer_id
            s.add(attempt)
            s.commit()
        with monkeypatch.context() as pending:
            def read_newer(url, **kwargs):
                assert url == f"{YOOKASSA_API}/{newer_id}"
                return _response("GET", url, _bank(second, "pending", newer_id))

            pending.setattr(httpx, "get", read_newer)
            r = client.post(path, json={"method": "check"}, headers=passenger["auth"])
        assert r.status_code == 200 and r.json()["status"] == "pending"
        assert r.json()["payment_id"] == second
        with Session(engine) as s:
            attempt = s.get(Payment, second)
            attempt.status = "canceled"  # local only, no bank confirmation
            s.add(attempt)
            s.commit()
        r = client.post(path, json={"method": "check"}, headers=passenger["auth"])
        assert r.status_code == 409 and r.json()["detail"]["code"] == "taxi_checkout_unresolved"
        assert reads == [15, 3]


def test_sweep_independent_lanes_missed_create_response_and_expiring_claim(client, monkeypatch, provider, user_factory):
    now = utcnow() - timedelta(hours=1)
    passenger = user_factory("SweepLanesPassenger")
    driver = user_factory("SweepLanesDriver", role=UserRole.driver)
    def order():
        return _order(passenger["id"], driver["id"], price=600)
    old = now - timedelta(days=2)
    known = _payment(passenger["id"], order(), old, provider_id=BANK_ID)
    unknown = _payment(passenger["id"], order(), old)
    parked = _payment(passenger["id"], order(), old - timedelta(days=1))
    _payment(passenger["id"], None, old, purpose="booking")
    _payment(passenger["id"], order(), now + timedelta(hours=1))
    with Session(engine) as s:
        s.add(TaxiCheckoutScan(payment_id=parked, cutoff=old, shop_id=SHOP, state="ambiguous"))
        s.commit()
    second_id = "58782bd0-66fe-4849-9045-ba115daa8a73"
    calls = []

    def get(url, *, auth, timeout, params=None):
        assert timeout == 3 and auth[0] == SHOP
        calls.append((url, (params or {}).get("cursor")))
        if url != YOOKASSA_API:
            pid = known if url.endswith(BANK_ID) else unknown
            return _response("GET", url, _bank(pid, "succeeded", url.rsplit("/", 1)[1]))
        if not params.get("cursor"):
            return _response("GET", url, {"type": "list", "items": [], "next_cursor": "p2"})
        assert params["cursor"] == "p2"
        return _response("GET", url, {"type": "list", "items": [_bank(unknown, "succeeded", second_id)]})

    monkeypatch.setattr(httpx, "get", get)
    monkeypatch.setattr(httpx, "post", lambda *a, **k: pytest.fail("sweep must never create a checkout"))
    with monkeypatch.context() as disabled:
        disabled.setattr(settings, "taxi_checkout_sweep_enabled", False)
        assert sweep_taxi_checkouts(now=now) == 0
    assert calls == []
    # Pretend a process died after claiming the known-ID row, before reading it.
    assert _claim(engine, now=now, known=True, shop=SHOP) == known
    assert sweep_taxi_checkouts(now=now) == 1
    assert calls == [(YOOKASSA_API, None)]
    assert sweep_taxi_checkouts(now=now + timedelta(minutes=5)) == 2
    assert calls[1:] == [(f"{YOOKASSA_API}/{BANK_ID}", None), (YOOKASSA_API, "p2")]
    assert sweep_taxi_checkouts(now=now + timedelta(minutes=10)) == 1
    assert calls[-1] == (f"{YOOKASSA_API}/{second_id}", None)
    with Session(engine) as s:
        assert s.get(Payment, known).status == s.get(Payment, unknown).status == "succeeded"
        assert s.get(Payment, parked).status == "pending"


def test_migration_leaves_old_cancellation_unverified(monkeypatch, tmp_path):
    url = f"sqlite:///{tmp_path / 'payment.db'}"
    backend = Path(__file__).resolve().parents[1]
    cfg = Config(str(backend / "alembic.ini"))
    cfg.set_main_option("script_location", str(backend / "alembic"))
    monkeypatch.setenv("DATABASE_URL", url)
    db = create_engine(url)
    previous = "df_taxi_manual_commission_intent"
    with db.begin() as c:
        c.execute(text("CREATE TABLE payment (id INTEGER PRIMARY KEY, status TEXT, purpose TEXT, "
                       "order_id INTEGER, method TEXT, created_at DATETIME)"))
        c.execute(text("INSERT INTO payment VALUES (1, 'canceled', 'ride', 11, 'yookassa', NULL)"))
    command.stamp(cfg, previous)
    for _ in range(2):
        command.upgrade(cfg, "dg_taxi_checkout_sweep")
        with db.connect() as c:
            assert c.execute(text(
                "SELECT taxi_reconcile_after, taxi_provider_canceled_at FROM payment"
            )).one() == (None, None)
        assert "ix_payment_taxi_sweep_pending" in {i["name"] for i in inspect(db).get_indexes("payment")}
        command.downgrade(cfg, previous)
    db.dispose()
