"""QA-B07-003: actual HTTP/PG locks for agreement, invoice and webhook races.

Root alone launches this file against an isolated loopback PostgreSQL. Gates retain
actual acquired row locks or pause only outbound provider calls, never replace a
handler/sync/settle. pg_stat_activity and pg_blocking_pids prove the waiting relation.
Timeouts bound failures; no sleeps manufacture a passing race. SQLite is an explicit
platform skip, not concurrency evidence. No real provider credentials/money are used.
"""
from concurrent.futures import ThreadPoolExecutor
from contextvars import ContextVar
from datetime import timedelta, timezone
import json
from threading import Event, Lock
from time import monotonic

import pytest
from sqlalchemy import event, text
from sqlmodel import Session, select

from app.config import settings
from app.db import engine
from app.models import Booking, LedgerEntry, Payment, UserRole
from app.routers import payments as payments_router
from app.routers import wallet
from app.timeutil import utcnow

pytestmark = pytest.mark.skipif(engine.dialect.name != "postgresql", reason="requires isolated PostgreSQL row locks")
_operation = ContextVar("qa_booking_payment_operation", default=None)
_BOUND = 15


class Gate:
    def __init__(self):
        self.entered, self.release = Event(), Event()

    def hold(self):
        self.entered.set()
        assert self.release.wait(_BOUND), "causal race gate was not released"


class SqlLocks:
    """Observe actual SQL and pause after a chosen real lock, without changing SQL."""
    def __init__(self):
        self.pids, self.lock_started, self.lock_pids, self.lock_counts = {}, {}, {}, {}
        self.before_gates, self.after_gates = {}, {}
        self.observations, self.mutex = [], Lock()

    def gate(self, label, occurrence=1, before=False):
        gate = Gate()
        (self.before_gates if before else self.after_gates)[(label, occurrence)] = gate
        return gate

    def _before(self, connection, cursor, statement, parameters, context, executemany):
        label = _operation.get()
        if label is None:
            return
        # Raw control cursor does not recursively trigger SQLAlchemy observers. These
        # SET LOCAL limits apply to each product statement's current transaction.
        with connection.connection.cursor() as control:
            control.execute("SET LOCAL lock_timeout = '8s'")
            control.execute("SET LOCAL statement_timeout = '12s'")
            control.execute("SELECT pg_backend_pid()")
            pid = control.fetchone()[0]
        sql = " ".join(statement.lower().split())
        context.qa_payment_lock = label if "from payment" in sql and "for update" in sql else None
        with self.mutex:
            self.pids[label] = pid
            if "from booking" in sql and "for update" in sql:
                occurrence = self.lock_counts.get(label, 0) + 1
                self.lock_counts[label] = occurrence
                context.qa_booking_lock = (label, occurrence)
                self.lock_pids[(label, occurrence)] = pid
                self.lock_started.setdefault((label, occurrence), Event()).set()
                self.observations.append({"operation": label, "pid": pid,
                                          "phase": "before_booking_lock", "occurrence": occurrence})
            else:
                context.qa_booking_lock = None
        gate = self.before_gates.get(getattr(context, "qa_booking_lock", None))
        if gate:
            gate.hold()

    def _after(self, connection, cursor, statement, parameters, context, executemany):
        payment_operation = getattr(context, "qa_payment_lock", None)
        if payment_operation:
            with self.mutex:
                self.observations.append({"operation": payment_operation, "pid": self.pids[payment_operation],
                                          "phase": "acquired_payment_lock"})
        lock = getattr(context, "qa_booking_lock", None)
        if lock:
            with self.mutex:
                self.observations.append({"operation": lock[0], "pid": self.pids[lock[0]],
                                          "phase": "acquired_booking_lock", "occurrence": lock[1]})
            gate = self.after_gates.get(lock)
            if gate:
                gate.hold()

    def __enter__(self):
        event.listen(engine, "before_cursor_execute", self._before)
        event.listen(engine, "after_cursor_execute", self._after)
        return self

    def __exit__(self, *args):
        self.release_all()
        event.remove(engine, "before_cursor_execute", self._before)
        event.remove(engine, "after_cursor_execute", self._after)
        print("QA_BOOKING_LOCKS " + json.dumps(self.observations, ensure_ascii=False, sort_keys=True))

    def release_all(self):
        for gate in (*self.before_gates.values(), *self.after_gates.values()):
            gate.release.set()

    def lock_pid(self, label, occurrence, future):
        # A commit may return a connection to the pool. Record the PID for THIS
        # actual lock statement, not an earlier authentication/read transaction.
        with self.mutex:
            started = self.lock_started.setdefault((label, occurrence), Event())
        if not started.wait(_BOUND):
            if future.done():
                future.result()
            raise AssertionError(f"HTTP {label} Booking lock#{occurrence} was not observed (source/fixture)")
        with self.mutex:
            return self.lock_pids[(label, occurrence)]

    def blocked_by(self, waiter, blocker, future, expected_table="booking"):
        assert waiter != blocker
        deadline = monotonic() + 6
        last = None
        with engine.connect().execution_options(isolation_level="AUTOCOMMIT") as observer:
            observer_pid = observer.execute(text("SELECT pg_backend_pid()")).scalar_one()
            assert observer_pid not in (waiter, blocker)
            while monotonic() < deadline:
                row = observer.execute(text(
                    "SELECT pid, state, wait_event_type, wait_event, query, pg_blocking_pids(pid) AS blockers "
                    "FROM pg_stat_activity WHERE pid=:pid"), {"pid": waiter}).mappings().one_or_none()
                last = dict(row) if row else None
                if last and blocker in last["blockers"] and last["wait_event_type"] == "Lock":
                    assert expected_table in last["query"].lower(), last
                    self.observations.append({"phase": "pg_confirmed_wait", "observer_pid": observer_pid, **last})
                    return last
                if future.done():
                    future.result()  # preserve an actual handler/DB error rather than a fake timeout
                    break
        raise AssertionError(f"No actual PG blocking relation {waiter}->{blocker}; last={last}")


@pytest.fixture
def provider_boundary(monkeypatch):
    assert settings.env == "dev" and engine.url.host in ("127.0.0.1", "localhost", "::1")
    assert engine.url.database != "postgres", "root must use its isolated audit database"
    assert settings.ride_service_fee_percent == 0 and settings.sms_provider == "mock"
    assert not any((settings.redis_url, settings.firebase_credentials, settings.telegram_bot_token,
                    settings.yandex_geocoder_key, settings.vapid_private_key,
                    settings.yookassa_shop_id, settings.yookassa_secret_key))
    # Enable the real webhook's production gate as configuration, with outbound calls
    # replaced and all actual provider keys absent. No business rule is monkeypatched.
    monkeypatch.setattr(settings, "payments_provider", "yookassa")
    data = {"invoices": {}, "create_calls": [], "fetch_calls": [], "states": {},
            "create_gates": {}, "fetch_gates": {}, "default_status": "pending", "mutex": Lock()}

    def create(session, payment, description, phone):
        label = _operation.get()
        with data["mutex"]:
            data["create_calls"].append({"operation": label, "payment_id": payment.id,
                                         "amount_kop": payment.amount_kop, "method_at_entry": payment.method})
        gate = data["create_gates"].get(label)
        if gate:
            gate.hold()  # intentionally BEFORE the boundary's normal method tagging
        provider_id = f"qa-booking-pg-{payment.id}"
        invoice = {"payment_id": payment.id, "amount_kop": payment.amount_kop,
                   "confirmation_url": f"https://qa.invalid/booking/{payment.id}"}
        with data["mutex"]:
            assert data["invoices"].setdefault(provider_id, invoice) == invoice
            status = data["states"].setdefault(provider_id, data["default_status"])
        payment.method = "yookassa"
        session.add(payment)
        session.commit()
        return {"status": status, "provider_id": provider_id, "confirmation_url": invoice["confirmation_url"]}

    def fetch(provider_id):
        label = _operation.get()
        gate = data["fetch_gates"].get(label)
        if gate:
            gate.hold()
        with data["mutex"]:
            invoice = data["invoices"][provider_id]
            status = data["states"][provider_id]
            data["fetch_calls"].append({"operation": label, "provider_id": provider_id, "status": status})
        return {"status": status, "metadata": {"payment_id": str(invoice["payment_id"])},
                "confirmation_url": invoice["confirmation_url"]}

    monkeypatch.setattr(wallet, "_start_yookassa", create)
    monkeypatch.setattr(payments_router, "fetch_payment", fetch)
    yield data
    for gate in (*data["create_gates"].values(), *data["fetch_gates"].values()):
        gate.release.set()


def _request(client, label, path, participant=None, body=None):
    token = _operation.set(label)
    try:
        return client.post(path, headers=participant["auth"] if participant else {}, json=body)
    finally:
        _operation.reset(token)


def _setup(client, user_factory):
    driver = user_factory("QA_PG_AGREEMENT_DRIVER", role=UserRole.driver)
    passenger = user_factory("QA_PG_AGREEMENT_PASSENGER")
    depart = (utcnow() + timedelta(minutes=10)).replace(tzinfo=timezone.utc).isoformat()
    ride = client.post("/rides", headers=driver["auth"], json={"from_city": "Баймак", "to_city": "Сибай",
                       "depart_at": depart, "seats_total": 3, "price": 1000})
    assert ride.status_code == 200, ride.text
    booked = client.post("/bookings", headers=passenger["auth"], json={"ride_id": ride.json()["id"], "seats": 1,
                          "pay_method": "sbp"})
    assert booked.status_code == 200, booked.text
    booking_id = booked.json()["id"]
    # Сумму ниже цены поездки записывает водитель (скидка), не пассажир при брони — 13.10.
    agreed = client.post(f"/bookings/{booking_id}/pay-agreement", headers=driver["auth"],
                         json={"pay_amount": 400})
    assert agreed.status_code == 200 and agreed.json()["pay_amount"] == 400, agreed.text
    assert client.post(f"/bookings/{booking_id}/confirm", headers=driver["auth"]).status_code == 200
    done = client.post(f"/bookings/{booking_id}/driver-status", headers=driver["auth"], json={"status": "done"})
    assert done.status_code == 200 and done.json()["status"] == "done", done.text
    return driver, passenger, booking_id


def _rows(booking_id):
    with Session(engine) as session:
        booking = session.get(Booking, booking_id)
        payments = session.exec(select(Payment).where(Payment.booking_id == booking_id).order_by(Payment.id)).all()
        entries = session.exec(select(LedgerEntry).where(LedgerEntry.booking_id == booking_id).order_by(LedgerEntry.id)).all()
        return {"paid": booking.paid, "agreement_rub": booking.pay_amount,
                "payments": [{"id": p.id, "amount_kop": p.amount_kop, "status": p.status,
                              "provider_id": p.provider_id, "method": p.method} for p in payments],
                "ledger": [{"id": e.id, "kind": e.kind.value, "amount_kop": e.amount_kop,
                            "driver": e.driver_id} for e in entries]}


def _observe(client, driver, passenger, booking_id, provider, locks=None, responses=()):
    views = {}
    for role, participant in (("driver", driver), ("passenger", passenger)):
        receipt = client.get(f"/trips/{booking_id}/receipt", headers=participant["auth"])
        details = client.get(f"/bookings/{booking_id}/details", headers=participant["auth"])
        assert receipt.status_code == details.status_code == 200
        views[role] = {"receipt": receipt.json()["amount"], "paid": receipt.json()["paid"],
                       "agreement": details.json()["pay_amount"]}
    balance = client.get("/wallet/balance", headers=driver["auth"])
    assert balance.status_code == 200
    observed = {"rows": _rows(booking_id), "views": views, "balance_kop": balance.json()["balance_kop"],
                "provider": {key: provider[key] for key in ("invoices", "create_calls", "fetch_calls")},
                "locks": locks.observations if locks else [],
                "responses": [{"http": response.status_code, "body": response.json()} for response in responses]}
    print("QA_BOOKING_PG_RACE " + json.dumps(observed, ensure_ascii=False, sort_keys=True))
    return observed



@pytest.mark.parametrize("first", ["edit", "pay"])
def test_pg_edit_and_locked_pay_serialize_without_invoice(client, user_factory, provider_boundary, first):
    driver, passenger, booking_id = _setup(client, user_factory)
    with SqlLocks() as locks, ThreadPoolExecutor(max_workers=2) as executor:
        first_gate = locks.gate(first)
        operations = {"edit": (f"/bookings/{booking_id}/pay-agreement", driver, {"pay_amount": 1200}),
                      "pay": (f"/bookings/{booking_id}/pay", passenger, {"method": "card"})}
        a = executor.submit(_request, client, first, *operations[first])
        try:
            assert first_gate.entered.wait(_BOUND), "first HTTP did not acquire Booking FOR UPDATE"
            second = "pay" if first == "edit" else "edit"
            b = executor.submit(_request, client, second, *operations[second])
            locks.blocked_by(locks.lock_pid(second, 1, b), locks.lock_pid(first, 1, a), b)
        finally:
            first_gate.release.set()
        responses = {first: a.result(timeout=25), second: b.result(timeout=25)}
        observed = _observe(client, driver, passenger, booking_id, provider_boundary, locks, tuple(responses.values()))
    assert responses["pay"].status_code == 503
    assert responses["pay"].json()["detail"]["code"] == "booking_online_pay_off"
    assert responses["edit"].status_code == 200
    assert observed["rows"] == {"paid": False, "agreement_rub": 1200, "payments": [], "ledger": []}
    assert observed["balance_kop"] == 0
    assert observed["provider"] == {"invoices": {}, "create_calls": [], "fetch_calls": []}


@pytest.mark.parametrize("methods", [("card", "card"), ("card", "sbp")])
def test_pg_concurrent_public_payments_never_reach_provider(client, user_factory, provider_boundary, methods):
    driver, passenger, booking_id = _setup(client, user_factory)
    with SqlLocks() as locks, ThreadPoolExecutor(max_workers=2) as executor:
        gate = locks.gate("first_pay")
        a = executor.submit(_request, client, "first_pay", f"/bookings/{booking_id}/pay", passenger,
                            {"method": methods[0]})
        try:
            assert gate.entered.wait(_BOUND)
            b = executor.submit(_request, client, "second_pay", f"/bookings/{booking_id}/pay", passenger,
                                {"method": methods[1]})
            locks.blocked_by(locks.lock_pid("second_pay", 1, b), locks.lock_pid("first_pay", 1, a), b)
        finally:
            gate.release.set()
        responses = [a.result(timeout=25), b.result(timeout=25)]
        observed = _observe(client, driver, passenger, booking_id, provider_boundary, locks, responses)
    assert all(r.status_code == 503 and r.json()["detail"]["code"] == "booking_online_pay_off" for r in responses)
    assert observed["rows"] == {"paid": False, "agreement_rub": 400, "payments": [], "ledger": []}
    assert observed["provider"] == {"invoices": {}, "create_calls": [], "fetch_calls": []}
