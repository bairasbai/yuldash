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
                          "pay_method": "sbp", "pay_amount": 400})
    assert booked.status_code == 200 and booked.json()["pay_amount"] == 400, booked.text
    booking_id = booked.json()["id"]
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


def _assert_amount(observed, amount, driver, paid=False, historical_canceled=0):
    rows = observed["rows"]
    assert rows["agreement_rub"] == amount and rows["paid"] is paid
    active = [payment for payment in rows["payments"] if payment["status"] != "canceled"]
    assert len(active) == 1, "one current logical payment must have one Payment row"
    assert len(rows["payments"]) == 1 + historical_canceled
    assert active[0]["amount_kop"] == amount * 100
    assert active[0]["status"] == ("succeeded" if paid else "pending")
    assert observed["provider"]["invoices"][active[0]["provider_id"]]["amount_kop"] == amount * 100
    assert len(observed["provider"]["invoices"]) == 1 + historical_canceled
    for view in observed["views"].values():
        assert view == {"receipt": amount, "paid": paid, "agreement": amount}
    if paid:
        assert len(rows["ledger"]) == 1 and rows["ledger"][0]["kind"] == "earn"
        assert rows["ledger"][0]["driver"] == driver["id"]
        assert rows["ledger"][0]["amount_kop"] == observed["balance_kop"] == amount * 100
    else:
        assert rows["ledger"] == [] and observed["balance_kop"] == 0


def _settle(client, driver, passenger, booking_id, provider, amount, historical_canceled=0):
    current = next(row for row in _rows(booking_id)["payments"] if row["status"] == "pending")
    provider["states"][current["provider_id"]] = "succeeded"
    hook = _request(client, "settle_webhook", "/payments/yookassa/webhook", body={"object": {"id": current["provider_id"]}})
    assert hook.status_code == 200 and hook.json() == {"ok": True}, hook.text
    first = _observe(client, driver, passenger, booking_id, provider, responses=(hook,))
    _assert_amount(first, amount, driver, paid=True, historical_canceled=historical_canceled)
    repeated = _request(client, "settled_repeat", f"/bookings/{booking_id}/pay", passenger, {"method": "card"})
    assert repeated.status_code == 200 and repeated.json()["status"] == "already_paid", repeated.text
    assert _rows(booking_id) == first["rows"], "retry must not mutate immutable ledger or Payment"


@pytest.mark.parametrize("first", ["edit", "pay"])
def test_pg_edit_and_pay_both_real_booking_lock_orders(client, user_factory, provider_boundary, first):
    driver, passenger, booking_id = _setup(client, user_factory)
    with SqlLocks() as locks, ThreadPoolExecutor(max_workers=2) as executor:
        first_gate = locks.gate(first)
        operations = {"edit": (f"/bookings/{booking_id}/pay-agreement", driver, {"pay_amount": 1200}),
                      "pay": (f"/bookings/{booking_id}/pay", passenger, {"method": "card"})}
        a = executor.submit(_request, client, first, *operations[first])
        try:
            assert first_gate.entered.wait(_BOUND), "first HTTP did not acquire the real Booking FOR UPDATE"
            second = "pay" if first == "edit" else "edit"
            b = executor.submit(_request, client, second, *operations[second])
            locks.blocked_by(locks.lock_pid(second, 1, b), locks.lock_pid(first, 1, a), b)
        finally:
            first_gate.release.set()
        responses = {first: a.result(timeout=25), second: b.result(timeout=25)}
        observed = _observe(client, driver, passenger, booking_id, provider_boundary, locks, tuple(responses.values()))
    assert responses["pay"].status_code == 200, responses["pay"].text
    if first == "edit":
        assert responses["edit"].status_code == 200, responses["edit"].text
        amount = 1200
    else:
        assert 400 <= responses["edit"].status_code < 500, "issued invoice's effective amount must be frozen"
        amount = 400
    _assert_amount(observed, amount, driver)
    _settle(client, driver, passenger, booking_id, provider_boundary, amount)


def test_pg_two_pay_requests_in_committed_row_before_outbound_window(client, user_factory, provider_boundary):
    driver, passenger, booking_id = _setup(client, user_factory)
    create_gate = provider_boundary["create_gates"]["first_pay"] = Gate()
    with SqlLocks() as locks, ThreadPoolExecutor(max_workers=2) as executor:
        a = executor.submit(_request, client, "first_pay", f"/bookings/{booking_id}/pay", passenger, {"method": "card"})
        try:
            assert create_gate.entered.wait(_BOUND)
            durable_gap = _rows(booking_id)
            print("QA_BOOKING_COMMITTED_GAP " + json.dumps(durable_gap, sort_keys=True))
            b = executor.submit(_request, client, "second_pay", f"/bookings/{booking_id}/pay", passenger, {"method": "card"})
            second = b.result(timeout=25)
        finally:
            create_gate.release.set()
        first = a.result(timeout=25)
        observed = _observe(client, driver, passenger, booking_id, provider_boundary, locks, (first, second))
    assert first.status_code == second.status_code == 200
    assert durable_gap["payments"][0]["method"] == "yookassa", "first commit must already identify the pending provider operation"
    assert first.json()["payment_id"] == second.json()["payment_id"]
    assert {call["payment_id"] for call in provider_boundary["create_calls"]} == {first.json()["payment_id"]}
    _assert_amount(observed, 400, driver)
    _settle(client, driver, passenger, booking_id, provider_boundary, 400)


def test_pg_repeat_pending_and_http_webhook_follow_payment_then_booking_without_deadlock(
        client, user_factory, provider_boundary):
    driver, passenger, booking_id = _setup(client, user_factory)
    initial = _request(client, "initial_pay", f"/bookings/{booking_id}/pay", passenger, {"method": "card"})
    assert initial.status_code == 200 and initial.json()["status"] == "pending"
    row = _rows(booking_id)["payments"][0]
    provider_boundary["states"][row["provider_id"]] = "succeeded"
    with SqlLocks() as locks, ThreadPoolExecutor(max_workers=2) as executor:
        repeat_gate = locks.gate("repeat_pay")
        repeat = executor.submit(_request, client, "repeat_pay", f"/bookings/{booking_id}/pay", passenger, {"method": "card"})
        try:
            assert repeat_gate.entered.wait(_BOUND)
            hook = executor.submit(_request, client, "webhook", "/payments/yookassa/webhook", None,
                                   {"object": {"id": row["provider_id"]}})
            # Webhook has acquired Payment and now actually waits for repeat's Booking.
            webhook_pid = locks.lock_pid("webhook", 1, hook)
            locks.blocked_by(webhook_pid, locks.lock_pid("repeat_pay", 1, repeat), hook)
            assert any(row.get("operation") == "webhook" and row["phase"] == "acquired_payment_lock"
                       and row["pid"] == webhook_pid for row in locks.observations)
        finally:
            repeat_gate.release.set()
        repeated, hooked = repeat.result(timeout=25), hook.result(timeout=25)
        observed = _observe(client, driver, passenger, booking_id, provider_boundary, locks, (repeated, hooked))
    assert repeated.status_code == hooked.status_code == 200
    assert repeated.json()["status"] in ("succeeded", "already_paid")
    assert hooked.json() == {"ok": True}
    _assert_amount(observed, 400, driver, paid=True)
    before = _rows(booking_id)
    duplicated_hook = _request(client, "webhook_repeat", "/payments/yookassa/webhook", body={"object": {"id": row["provider_id"]}})
    assert duplicated_hook.status_code == 200 and _rows(booking_id) == before


def test_pg_canceled_sync_then_agreement_edit_relocks_and_uses_fresh_amount(client, user_factory, provider_boundary):
    driver, passenger, booking_id = _setup(client, user_factory)
    initial = _request(client, "initial_pay", f"/bookings/{booking_id}/pay", passenger, {"method": "card"})
    assert initial.status_code == 200
    old = _rows(booking_id)["payments"][0]
    provider_boundary["states"][old["provider_id"]] = "canceled"
    with SqlLocks() as locks, ThreadPoolExecutor(max_workers=1) as executor:
        relock_gate = locks.gate("renew", occurrence=2, before=True)
        renewed = executor.submit(_request, client, "renew", f"/bookings/{booking_id}/pay", passenger, {"method": "card"})
        try:
            assert relock_gate.entered.wait(_BOUND), "canceled path did not reacquire Booking"
            assert _rows(booking_id)["payments"][0]["status"] == "canceled", "provider cancel must already be committed"
            edited = _request(client, "edit_after_cancel", f"/bookings/{booking_id}/pay-agreement", driver, {"pay_amount": 1200})
            assert edited.status_code == 200, edited.text
        finally:
            relock_gate.release.set()
        response = renewed.result(timeout=25)
        observed = _observe(client, driver, passenger, booking_id, provider_boundary, locks, (edited, response))
    assert response.status_code == 200 and response.json()["status"] == "pending"
    assert response.json()["payment_id"] != old["id"]
    _assert_amount(observed, 1200, driver, historical_canceled=1)
    _settle(client, driver, passenger, booking_id, provider_boundary, 1200, historical_canceled=1)


def test_pg_two_canceled_repeats_keep_only_one_new_pending_invoice(client, user_factory, provider_boundary):
    driver, passenger, booking_id = _setup(client, user_factory)
    initial = _request(client, "initial_pay", f"/bookings/{booking_id}/pay", passenger, {"method": "card"})
    assert initial.status_code == 200
    old = _rows(booking_id)["payments"][0]
    provider_boundary["states"][old["provider_id"]] = "canceled"
    fetch_a = provider_boundary["fetch_gates"]["renew_a"] = Gate()
    fetch_b = provider_boundary["fetch_gates"]["renew_b"] = Gate()
    with SqlLocks() as locks, ThreadPoolExecutor(max_workers=2) as executor:
        relock_a = locks.gate("renew_a", occurrence=2)
        a = executor.submit(_request, client, "renew_a", f"/bookings/{booking_id}/pay", passenger, {"method": "card"})
        try:
            assert fetch_a.entered.wait(_BOUND)
            b = executor.submit(_request, client, "renew_b", f"/bookings/{booking_id}/pay", passenger, {"method": "card"})
            assert fetch_b.entered.wait(_BOUND), "Booking must be released before the provider fetch"
            fetch_a.release.set()
            assert relock_a.entered.wait(_BOUND)
            fetch_b.release.set()
            locks.blocked_by(locks.lock_pid("renew_b", 2, b), locks.lock_pid("renew_a", 2, a), b)
        finally:
            fetch_a.release.set()
            fetch_b.release.set()
            relock_a.release.set()
        responses = (a.result(timeout=25), b.result(timeout=25))
        observed = _observe(client, driver, passenger, booking_id, provider_boundary, locks, responses)
    assert all(response.status_code == 200 for response in responses)
    assert responses[0].json()["payment_id"] == responses[1].json()["payment_id"], "two renewals must reuse the one replacement invoice"
    _assert_amount(observed, 400, driver, historical_canceled=1)
    _settle(client, driver, passenger, booking_id, provider_boundary, 400, historical_canceled=1)
