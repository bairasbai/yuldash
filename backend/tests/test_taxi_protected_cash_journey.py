"""One price-confirmed NOW order through both roles and direct-cash settlement."""
import os
import socket
import tempfile
from datetime import timedelta
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

from app import instant_service
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
from app.timeutil import utcnow


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


def test_protected_initial_fare_excludes_later_metered_waiting(request, monkeypatch):
    _synthetic_boundaries(monkeypatch)
    monkeypatch.setattr(settings, "wait_free_minutes", 3)
    monkeypatch.setattr(settings, "wait_fee_rub_per_min", 5)
    monkeypatch.setattr(settings, "wait_fee_cap_rub", 100)
    client = request.getfixturevalue("client")
    make_user = request.getfixturevalue("user_factory")
    request.getfixturevalue("consent_market")
    set_base(600)
    passenger = make_user("Synthetic Protected Waiting Passenger")
    driver = make_user("Synthetic Protected Waiting Driver", role=UserRole.driver)
    for actor in (passenger, driver):
        actor["auth"] = _bearer(_issue(actor["id"])["access_token"])

    def call(method, path, actor, **kwargs):
        response = client.request(method, path, headers=actor["auth"], **kwargs)
        assert response.status_code == 200
        return response.json()

    call("POST", "/driver/online", driver, json={"online": True})
    _heartbeat(client, driver)
    q = quote(client, passenger)
    assert (q["price_kop"], q["promo_discount_kop"], q["payable_kop"]) == (60000, 0, 60000)
    committed = confirm(client, passenger, q, payment_method="cash")
    assert committed.status_code == 200
    initial = committed.json()
    oid = initial["order"]["id"]
    assert initial["order"]["status"] == "offered"
    assert initial["consent"]["accepted"]["payable_kop"] == 60000
    route = f"/instant/orders/{oid}"
    assert call("POST", f"{route}/accept", driver)["status"] == "accepted"
    def wallet_rows():
        with Session(engine) as session:
            return session.exec(select(LedgerEntry.id).where(LedgerEntry.driver_id == driver["id"])).all()

    assert _accounting(oid) == ([], [], [])  # no cash or commission before travel
    assert wallet_rows() == []  # debt cannot be settled automatically from the driver's wallet

    # Move only the transition's clock. Both paid-wait events are ordinary
    # mounted requests; no manual insertion or mutation of the order.
    clock = utcnow()
    monkeypatch.setattr(instant_service, "utcnow", lambda: clock)
    arrived = call("POST", f"{route}/arrived", driver, json={"lat": ORIG[0], "lng": ORIG[1]})
    assert arrived["status"] == "arriving" and arrived["waiting_started_at"]
    assert (arrived["wait_free_min"], arrived["wait_fee_rub_per_min"]) == (3, 5)
    clock += timedelta(minutes=5, seconds=30)
    onboard = call("POST", f"{route}/onboard", driver)
    assert (onboard["status"], onboard["waiting_fee_kop"]) == ("onboard", 1000)
    done = call("POST", f"{route}/done", driver)
    assert (done["status"], done["price_final"], done["waiting_fee_kop"]) == ("done", 610, 1000)

    def receipt(actor, role, paid):
        bill = call("GET", f"{route}/receipt", actor)
        assert (bill["order_id"], bill["role"], bill["paid"]) == (oid, role, paid)
        assert tuple(bill[key] for key in (
            "price_kop", "amount_kop", "promo_discount_kop", "waiting_fee_kop",
        )) == (61000, 61000, 0, 1000)
        return bill

    assert "driver_fee_kop" not in receipt(passenger, "passenger", False)
    driver_bill = receipt(driver, "driver", False)
    assert tuple(driver_bill[key] for key in (
        "driver_fee_percent", "driver_gross_kop", "driver_fee_kop", "driver_net_kop", "commission_free_kop",
    )) == (5, 61000, 3050, 57950, 0)

    def debts():
        with Session(engine) as session:
            return session.exec(select(CommissionDebt.id, CommissionDebt.driver_id,
                                       CommissionDebt.amount_kop).where(CommissionDebt.order_id == oid)).all()

    original_debts = debts()
    [(_, driver_id, amount_kop)] = original_debts
    assert (driver_id, amount_kop) == (driver["id"], 3050)
    assert call("POST", f"{route}/done", driver)["price_final"] == 610
    assert call("POST", f"{route}/cash-received", driver) == {
        "status": "paid", "method": "cash", "amount_kop": 61000,
    }
    assert call("POST", f"{route}/cash-received", driver) == {"status": "already_paid", "method": "cash"}
    assert receipt(passenger, "passenger", True)["payment_method"] == "cash"
    assert receipt(driver, "driver", True)["driver_net_kop"] == 57950
    assert debts() == original_debts
    assert _accounting(oid) == ([], [original_debts[0][0]], [])

    recovered = lookup(client, passenger, q["request_key"])
    assert recovered.status_code == 200
    assert (recovered.json()["order_id"], recovered.json()["initial_payable_kop"]) == (oid, 60000)
    replay = confirm(client, passenger, q, payment_method="cash")
    assert replay.status_code == 200
    assert replay.json()["order"]["id"] == oid
    assert replay.json()["consent"] == initial["consent"]
    assert debts() == original_debts
    assert _accounting(oid) == ([], [original_debts[0][0]], [])
    assert wallet_rows() == []
