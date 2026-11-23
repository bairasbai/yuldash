"""One mounted-API ride with real scoped logins and synthetic cash marking."""
import os
from pathlib import Path
import socket
import tempfile

import pytest
from sqlalchemy import text
from sqlmodel import Session, select

from app.config import settings
from app.db import engine
from app.models import CommissionDebt, InstantOrder, InstantOrderStatus, LedgerEntry, Payment, UserRole
from test_auth_session_logout import _bearer, _issue
from test_instant import ORIG, _heartbeat, _order_body, fake_redis  # noqa: F401 - reused fixture


def _assert_disposable_db():
    url = engine.url
    if engine.dialect.name == "sqlite":
        isolated = Path(tempfile.gettempdir()) / f"yuldash_test_{os.getpid()}.db"
        if url.database != str(isolated):
            pytest.skip("pytest-owned SQLite file required")
        return
    if not (os.environ.get("AUDIT_PG_CI_SERVICE") == "1"
            and url.drivername == "postgresql+psycopg2" and url.username == "yuldash"
            and url.host in ("localhost", "127.0.0.1", "::1") and url.port == 5432
            and url.database == "yuldash_run" and not url.query):
        pytest.skip("disposable PostgreSQL CI service required")
    with engine.connect() as conn:
        assert tuple(conn.execute(text("SELECT current_database(), current_user")).one()) == ("yuldash_run", "yuldash")


def _fence_network(monkeypatch):
    # All HTTP stays in TestClient's ASGI transport. Block Python Internet
    # sockets (pricing, push, Redis); psycopg2 uses the guarded local libpq DSN.
    def local_only(original):
        def fenced(sock, *args, **kwargs):
            if sock.family in (socket.AF_INET, socket.AF_INET6):
                pytest.fail("outside network not permitted in synthetic taxi journey")
            return original(sock, *args, **kwargs)
        return fenced

    for operation in ("connect", "connect_ex"):
        monkeypatch.setattr(socket.socket, operation, local_only(getattr(socket.socket, operation)))
    monkeypatch.setattr(settings, "taxi_external_pricing_enabled", False)
    monkeypatch.setattr(settings, "redis_url", "")


def _debts(session, oid):
    return session.exec(select(CommissionDebt.id, CommissionDebt.amount_kop).where(
        CommissionDebt.order_id == oid,
    )).all()


def test_fixed_route_quote_to_cash_receipts_with_session_replacement(request, monkeypatch):
    _assert_disposable_db()
    _fence_network(monkeypatch)
    client = request.getfixturevalue("client")
    user = request.getfixturevalue("user_factory")
    request.getfixturevalue("fake_redis")
    passenger, driver, stranger = user("Journey Passenger"), user("Journey Driver", role=UserRole.driver), user("Journey Other")
    p_login, d_login, third_login = (_issue(actor["id"]) for actor in (passenger, driver, stranger))
    assert len({pair["session_id"] for pair in (p_login, d_login, third_login)}) == 3
    p, d, third = (_bearer(pair["access_token"]) for pair in (p_login, d_login, third_login))

    def get(path, auth):
        response = client.get(path, headers=auth)
        assert response.status_code == 200
        return response.json()

    def post(path, auth, body=None):
        response = client.post(path, headers=auth, json=body)
        assert response.status_code == 200
        return response.json()

    post("/driver/online", d, {"online": True})
    _heartbeat(client, {"auth": d}, ORIG)
    route = _order_body()
    quote = post("/instant/estimate", p, route)
    assert type(quote["price"]) is int and quote["price"] > 0 and quote["price_locked_sec"] > 0
    command = route | {"payment_method": "cash"}
    order = post("/instant/orders", p, command)
    oid = order["id"]
    detail, receipt = f"/instant/orders/{oid}", f"/instant/orders/{oid}/receipt"
    assert order["status"] == "offered" and order["price_estimate"] == quote["price"]
    assert order["driver_id"] is None and not order["driver_phone"]
    offered = get("/instant/driver/offer", d)["offer"]
    assert (offered["id"], offered["status"], offered["driver_id"]) == (oid, "offered", None)
    assert not offered["passenger_phone"]
    assert client.get(detail, headers=third).status_code == 403
    assert client.get(receipt, headers=p).status_code == 409
    assert client.post(f"{detail}/accept", headers=third).status_code == 403

    accepted = post(f"{detail}/accept", d)
    assert (accepted["id"], accepted["status"], accepted["driver_id"]) == (oid, "accepted", driver["id"])
    assert get(detail, p)["driver_id"] == driver["id"]
    assert post("/auth/logout/session", _bearer(p_login["logout_token"])) == {"ok": True}
    assert client.get(detail, headers=p).status_code == 401
    assert client.post("/instant/orders", headers=p, json=command).status_code == 401
    replacement = _issue(passenger["id"])
    assert replacement["session_id"] != p_login["session_id"]
    fresh = _bearer(replacement["access_token"])
    assert not client.cookies
    resumed = post("/instant/orders", fresh, command)
    assert (resumed["id"], resumed["status"], resumed["price_estimate"]) == (oid, "accepted", quote["price"])
    for action, status in (("arrived", "arriving"), ("onboard", "onboard"), ("done", "done")):
        location = {"lat": ORIG[0], "lng": ORIG[1]} if action == "arrived" else None
        changed = post(f"{detail}/{action}", d, location)
        observed = get(detail, fresh)
        assert (changed["id"], observed["id"], changed["status"], observed["status"]) == (oid, oid, status, status)
        assert changed["driver_id"] == observed["driver_id"] == driver["id"]
    assert observed["price_final"] == quote["price"]  # payment is authoritative on the receipt
    amount = 100 * quote["price"]
    with Session(engine) as session:
        debts = _debts(session, oid)
    assert len(debts) == 1  # ordinary commission accrual, distinct from cash marking
    for auth, role in ((fresh, "passenger"), (d, "driver")):
        bill = get(receipt, auth)
        assert (bill["order_id"], bill["role"], bill["amount_kop"], bill["paid"]) == (oid, role, amount, False)
    assert client.post(f"{detail}/cash-received", headers=fresh).status_code == 403
    assert post(f"{detail}/cash-received", d) == {"status": "paid", "method": "cash", "amount_kop": amount}
    assert post(f"{detail}/cash-received", d) == {"status": "already_paid", "method": "cash"}
    for auth, role, counterparty in ((fresh, "passenger", driver), (d, "driver", passenger)):
        bill = get(receipt, auth)
        assert (bill["order_id"], bill["role"], bill["counterparty_id"]) == (oid, role, counterparty["id"])
        assert bill["paid"] and bill["payment_method"] == "cash" and bill["amount_kop"] == amount
    assert client.get(receipt, headers=p).status_code == 401
    assert client.get(receipt, headers=third).status_code == 403

    with Session(engine) as session:
        (stored,) = session.exec(select(InstantOrder).where(InstantOrder.passenger_id == passenger["id"])).all()
        assert (stored.id, stored.driver_id, stored.status) == (oid, driver["id"], InstantOrderStatus.done)
        assert stored.paid and stored.payment_method == "cash" and stored.price_final == quote["price"]
        assert stored.accepted_at and stored.done_at and _debts(session, oid) == debts
        assert not session.exec(select(Payment.id).where(Payment.order_id == oid)).all()
        assert not session.exec(select(LedgerEntry.id).where(LedgerEntry.order_id == oid)).all()
