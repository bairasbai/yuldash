"""One price-confirmed NOW order through both roles and direct-cash settlement."""
import os
import socket
import tempfile
from pathlib import Path

import pytest
from sqlalchemy import text
from sqlmodel import Session, select
from test_auth_session_logout import _bearer, _issue
from test_money_rules import ORIG, _heartbeat
from test_taxi_quote_consent import (
    confirm,
    consent_market,  # noqa: F401 - pytest fixture
    promo_user,
    quote,
    set_base,
)
from test_taxi_quote_result import UNKNOWN, lookup

from app.config import settings
from app.db import engine
from app.models import (
    CommissionDebt,
    InstantOrder,
    LedgerEntry,
    LedgerKind,
    Payment,
    UserRole,
)


def _synthetic_boundaries(monkeypatch):
    url = engine.url
    if engine.dialect.name == "sqlite":
        expected = Path(tempfile.gettempdir()) / f"yuldash_test_{os.getpid()}.db"
        if url.database != str(expected):
            pytest.skip("pytest-owned SQLite file required")
    elif not (os.environ.get("AUDIT_PG_CI_SERVICE") == "1"
              and url.drivername == "postgresql+psycopg2" and url.username == "yuldash"
              and url.host in ("localhost", "127.0.0.1", "::1") and url.port == 5432
              and url.database == "yuldash_run" and not url.query):
        pytest.skip("disposable PostgreSQL CI service required")
    else:
        with engine.connect() as connection:
            assert tuple(connection.execute(text("SELECT current_database(), current_user")).one()) == (
                "yuldash_run", "yuldash",
            )

    def isolated(original):
        def checked(sock, *args, **kwargs):
            if sock.family in (socket.AF_INET, socket.AF_INET6):
                pytest.fail("unexpected external socket in synthetic journey")
            return original(sock, *args, **kwargs)
        return checked

    for operation in ("connect", "connect_ex"):
        monkeypatch.setattr(socket.socket, operation, isolated(getattr(socket.socket, operation)))
    def deny_dns(*args, **kwargs):
        pytest.fail("unexpected DNS in synthetic journey")

    for operation in ("getaddrinfo", "gethostbyname", "gethostbyname_ex", "gethostbyaddr"):
        monkeypatch.setattr(socket, operation, deny_dns)
    monkeypatch.setattr(settings, "taxi_external_pricing_enabled", False)
    monkeypatch.setattr(settings, "redis_url", "")
    monkeypatch.setattr(settings, "launch_promo_until", "")
    monkeypatch.setattr(settings, "fee_tier1_trips", 30)
    monkeypatch.setattr(settings, "fee_tier1_percent", 5.0)


def _accounting(order_id):
    with Session(engine) as session:
        ledger = session.exec(
            select(LedgerEntry.id, LedgerEntry.driver_id, LedgerEntry.kind, LedgerEntry.amount_kop)
            .where(LedgerEntry.order_id == order_id).order_by(LedgerEntry.id)
        ).all()
        debts = session.exec(select(CommissionDebt.id).where(CommissionDebt.order_id == order_id)).all()
        payments = session.exec(select(Payment.id).where(Payment.order_id == order_id)).all()
        return ledger, debts, payments


def test_protected_quote_stays_the_cash_amount_after_market_moves(request, monkeypatch):
    _synthetic_boundaries(monkeypatch)
    client = request.getfixturevalue("client")
    make_user = request.getfixturevalue("user_factory")
    request.getfixturevalue("consent_market")
    set_base(600)
    passenger = promo_user(client, make_user)
    driver = make_user("Synthetic Protected Cash Driver", role=UserRole.driver)
    other = make_user("Synthetic Protected Cash Other")
    tokens = [_issue(actor["id"]) for actor in (passenger, driver, other)]
    assert len({pair["session_id"] for pair in tokens}) == 3
    for actor, pair in zip((passenger, driver, other), tokens, strict=True):
        actor["auth"] = _bearer(pair["access_token"])

    def call(method, path, actor, *, status=200, **kwargs):
        response = client.request(method, path, headers=actor["auth"], **kwargs)
        assert response.status_code == status
        return response.json()

    call("POST", "/driver/online", driver, json={"online": True})
    _heartbeat(client, driver)
    q = quote(client, passenger)
    fare = (q["price_kop"], q["promo_discount_kop"], q["payable_kop"])
    assert fare == (60000, 15000, 45000)
    response = confirm(client, passenger, q, payment_method="cash")
    assert response.status_code == 200
    first = response.json()
    order = first["order"]
    oid = order["id"]
    route = f"/instant/orders/{oid}"
    assert order["status"] == "offered" and order["driver_id"] is None
    accepted_fare = first["consent"]["accepted"]
    assert tuple(accepted_fare[key] for key in ("price_kop", "promo_discount_kop", "payable_kop")) == fare
    offered = call("GET", "/instant/driver/offer", driver)["offer"]
    assert (offered["id"], offered["status"]) == (oid, "offered")
    call("GET", f"{route}/receipt", passenger, status=409)

    # Prove the current tariff diverged before the driver acts; it must not
    # replace the accepted fare for this fixed route (no paid waiting).
    set_base(900)
    fresh = quote(client, other)
    assert fresh["price_kop"] == 90000
    for action, expected in (("accept", "accepted"), ("arrived", "arriving"),
                             ("onboard", "onboard"), ("done", "done")):
        body = {"lat": ORIG[0], "lng": ORIG[1]} if action == "arrived" else None
        updated = call("POST", f"{route}/{action}", driver, json=body)
        observed = call("GET", route, passenger)
        for view in (updated, observed):
            assert (view["id"], view["status"], view["driver_id"]) == (oid, expected, driver["id"])
    assert observed["price_final"] * 100 == fare[0]

    recovered = lookup(client, passenger, q["request_key"])
    assert recovered.status_code == 200 and recovered.headers["cache-control"] == "no-store"
    assert (recovered.json()["order_id"], recovered.json()["initial_payable_kop"]) == (oid, fare[2])
    foreign = lookup(client, other, q["request_key"])
    assert foreign.status_code == 200 and foreign.json() == UNKNOWN
    call("GET", f"{route}/receipt", other, status=403)
    call("POST", f"{route}/cash-received", passenger, status=403)

    def verify_receipts(paid):
        for actor, role, counterparty in (
            (passenger, "passenger", driver), (driver, "driver", passenger),
        ):
            bill = call("GET", f"{route}/receipt", actor)
            assert (bill["order_id"], bill["role"], bill["counterparty_id"]) == (oid, role, counterparty["id"])
            assert (bill["price_kop"], bill["promo_discount_kop"], bill["amount_kop"]) == fare
            assert bill["waiting_fee_kop"] == 0
            assert bill["paid"] is paid
            if paid:
                assert bill["payment_method"] == "cash"

    verify_receipts(False)
    before = _accounting(oid)
    [(_, driver_id, kind, amount)] = before[0]
    assert (driver_id, kind, amount) == (driver["id"], LedgerKind.adj, 12000)
    assert before[1:] == ([], [])  # coupon exceeds 5% commission; no debt or platform payment
    assert call("POST", f"{route}/cash-received", driver) == {
        "status": "paid", "method": "cash", "amount_kop": fare[2],
    }
    assert call("POST", f"{route}/cash-received", driver) == {"status": "already_paid", "method": "cash"}
    verify_receipts(True)
    assert _accounting(oid) == before  # the one compensation survives; no cash posting or duplicate
    replay = confirm(client, passenger, q, payment_method="cash")
    assert replay.status_code == 200 and replay.json()["consent"] == first["consent"]
    assert replay.json()["order"]["id"] == oid
    with Session(engine) as session:
        (stored,) = session.exec(select(InstantOrder).where(InstantOrder.passenger_id == passenger["id"])).all()
        assert stored.id == oid and stored.paid and stored.payment_method == "cash"
    assert _accounting(oid) == before
