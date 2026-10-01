"""QA-B07-004: real HTTP / PostgreSQL manual confirm-reject serialization.

Four first Payment-lock orders and one committed booking-settlement gap probe.
Only SQL observers/Events control ordering: handlers, activation, ledger, commits,
notifications and responses remain real. An independent MVCC reader distinguishes
the old committed paid/earn + pending window from an atomic fixed transaction.
SQLite skip is not PG evidence. Root alone runs against its isolated local PG.
"""
from concurrent.futures import ThreadPoolExecutor
from contextvars import ContextVar
import json
from threading import Event, Lock
from time import monotonic

import pytest
from sqlalchemy import event, text

from app.config import settings
from app.db import engine
from test_manual_payment_terminal_confirmation import (
    isolated_manual_configuration, _action, _input, _observe, _snapshot, _truthful_confirm,
)

pytestmark = pytest.mark.skipif(engine.dialect.name != "postgresql", reason="requires isolated PostgreSQL row locks")
_operation = ContextVar("qa_manual_terminal_operation", default=None)
_BOUND = 15


@pytest.fixture(autouse=True)
def isolated_pg_configuration(isolated_manual_configuration):
    assert settings.env == "dev" and engine.url.host in ("127.0.0.1", "localhost", "::1")
    assert engine.url.database != "postgres", "root must provide an isolated audit database"
    # The imported fixture checks absent actual credentials and configures only
    # sbp_manual. No product function, notification or financial effect is patched.


class Gate:
    def __init__(self):
        self.entered, self.release = Event(), Event()

    def hold(self):
        self.entered.set()
        assert self.release.wait(_BOUND), "causal SQL gate was not released"


def _committed_booking(context, product_pid=None):
    """One real, externally committed MVCC snapshot; no session identity cache."""
    token = _operation.set(None)  # observer SQL must not label itself as handler SQL
    try:
        with engine.connect().execution_options(isolation_level="AUTOCOMMIT") as reader:
            row = reader.execute(text(
                "SELECT pg_backend_pid() AS observer_pid, p.id AS payment_id, p.status, p.amount_kop, "
                "b.paid, (SELECT COALESCE(SUM(e.amount_kop), 0) FROM ledgerentry e "
                "WHERE e.booking_id=b.id AND e.driver_id=:driver AND e.kind='earn') AS earned_kop, "
                "ARRAY(SELECT n.id FROM notification n WHERE n.user_id=:owner ORDER BY n.id) AS notification_ids "
                "FROM payment p JOIN booking b ON b.id=p.booking_id WHERE p.id=:payment"),
                {"payment": context["payment_id"], "driver": context["driver"]["id"],
                 "owner": context["owner"]["id"]}).mappings().one()
            observed = dict(row)
            # PostgreSQL SUM(bigint) is numeric/Decimal; retain exact integer
            # kopecks for JSON evidence without using float or changing SQL.
            observed["earned_kop"] = int(observed["earned_kop"])
        if product_pid is not None:
            assert observed["observer_pid"] != product_pid, "financial visibility must use another PG connection"
        return observed
    finally:
        _operation.reset(token)


class SqlRace:
    """Observe acquired Payment locks and the actual first confirm UPDATE."""
    def __init__(self, gap_context=None):
        self.mutex, self.observations = Lock(), []
        self.started, self.lock_pids, self.counts, self.after_gates = {}, {}, {}, {}
        self.gap_context, self.update_observed = gap_context, Event()
        self.gap_gate, self.gap_observation = Gate(), None

    def gate_first_lock(self, label):
        gate = self.after_gates[(label, 1)] = Gate()
        return gate

    def _before(self, connection, cursor, statement, parameters, context, executemany):
        label = _operation.get()
        if label is None:
            return
        # Raw control cursor does not recurse through SQLAlchemy instrumentation.
        # These limits bound real DB failures rather than manufacture race timing.
        with connection.connection.cursor() as control:
            control.execute("SET LOCAL lock_timeout = '8s'")
            control.execute("SET LOCAL statement_timeout = '12s'")
            control.execute("SELECT pg_backend_pid()")
            pid = control.fetchone()[0]
        sql = " ".join(statement.lower().split())
        locking = ("from payment" in sql and "for update" in sql) or sql.startswith("update payment ")
        context.qa_manual_lock = None
        if locking:
            with self.mutex:
                count = self.counts.get(label, 0) + 1
                self.counts[label] = count
                key = context.qa_manual_lock = (label, count)
                self.lock_pids[key] = pid
                self.started.setdefault(key, Event()).set()
                self.observations.append({"operation": label, "phase": "before_payment_lock",
                                          "occurrence": count, "pid": pid,
                                          "sql_kind": "update" if sql.startswith("update") else "select_for_update"})
        if self.gap_context is not None and label == "gap_confirm" and sql.startswith("update payment "):
            with self.mutex:
                first_update = self.gap_observation is None
            if first_update:
                external = _committed_booking(self.gap_context, product_pid=pid)
                # No dependency on admin's early lock/guard implementation. This
                # callback observes BEFORE the actual UPDATE, after any real commit.
                applied = external["paid"] or external["earned_kop"] != 0
                mode = "observed_committed_gap" if applied else "no_observed_gap"
                self.gap_observation = {"mode": mode, "product_pid": pid, "external": external}
                self.observations.append({"phase": "before_first_confirm_update", **self.gap_observation})
                self.update_observed.set()
                if applied:
                    self.gap_gate.hold()

    def _after(self, connection, cursor, statement, parameters, context, executemany):
        key = getattr(context, "qa_manual_lock", None)
        if key:
            with self.mutex:
                self.observations.append({"operation": key[0], "phase": "acquired_payment_lock",
                                          "occurrence": key[1], "pid": self.lock_pids[key]})
            gate = self.after_gates.get(key)
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
        print("QA_MANUAL_PG_SQL " + json.dumps(self.observations, ensure_ascii=False, sort_keys=True))

    def release_all(self):
        self.gap_gate.release.set()
        for gate in self.after_gates.values():
            gate.release.set()

    def lock_pid(self, label, future):
        key = (label, 1)
        with self.mutex:
            started = self.started.setdefault(key, Event())
        if not started.wait(_BOUND):
            if future.done():
                future.result()
            raise AssertionError(f"HTTP {label} actual first Payment lock was not observed (source/fixture)")
        with self.mutex:
            return self.lock_pids[key]

    def await_blocked(self, waiter, blocker, future, allow_completed=False):
        assert waiter != blocker
        deadline, last = monotonic() + 6, None
        if allow_completed and future.done():
            future.result()
            self.observations.append({"phase": "contender_completed_before_release", "pid": waiter})
            return "completed"
        token = _operation.set(None)
        try:
            with engine.connect().execution_options(isolation_level="AUTOCOMMIT") as reader:
                observer_pid = reader.execute(text("SELECT pg_backend_pid()")).scalar_one()
                # A completed HTTP may already return its connection to the pool.
                # Do not mistake that recycled PID for a concurrently waiting lock.
                if allow_completed and future.done():
                    future.result()
                    self.observations.append({"phase": "contender_completed_before_release", "pid": waiter})
                    return "completed"
                assert observer_pid not in (waiter, blocker)
                while monotonic() < deadline:
                    row = reader.execute(text(
                        "SELECT pid, state, wait_event_type, wait_event, query, pg_blocking_pids(pid) AS blockers "
                        "FROM pg_stat_activity WHERE pid=:pid"), {"pid": waiter}).mappings().one_or_none()
                    last = dict(row) if row else None
                    if last and blocker in last["blockers"] and last["wait_event_type"] == "Lock":
                        assert "payment" in last["query"].lower(), last
                        self.observations.append({"phase": "pg_confirmed_wait", "observer_pid": observer_pid, **last})
                        return "blocked"
                    if future.done():
                        future.result()  # preserve real DB/HTTP error, not a fake observer timeout
                        if allow_completed:
                            self.observations.append({"phase": "contender_completed_before_release", "pid": waiter})
                            return "completed"
                        break
        finally:
            _operation.reset(token)
        raise AssertionError(f"No actual PG block {waiter}->{blocker}; last={last}")


def _request(client, context, label, action):
    token = _operation.set(label)
    try:
        return _action(client, context["admin"], context["payment_id"], action)
    finally:
        _operation.reset(token)


def _await_gate(gate, future):
    if not gate.entered.wait(_BOUND):
        if future.done():
            future.result()
        raise AssertionError("first HTTP did not acquire its actual Payment row lock")


def _assert_effect(context, observed, expected_status):
    final = observed["after"]
    assert final["payment"]["status"] == observed["owner_status"]["status"] == expected_status
    assert final["payment"]["amount_kop"] == context["amount_kop"]
    assert not observed["in_manual_queue"]
    paid = expected_status == "succeeded"
    assert (final["payment"]["settled_at"] is not None) is paid
    if context["kind"] == "legacy_booking":
        assert final["booking"]["paid"] is paid
        assert observed["receipts"] == {"driver": {"amount": 400, "paid": paid},
                                         "passenger": {"amount": 400, "paid": paid}}
        assert final["earned_kop"] == observed["driver_balance_kop"] == (40000 if paid else 0)
        if paid:
            assert len(final["ledger"]) == 1 and final["ledger"][0] == {
                "id": final["ledger"][0]["id"], "kind": "earn", "amount_kop": 40000,
                "booking_id": context["booking_id"], "driver": context["driver"]["id"]}
        else:
            assert final["ledger"] == []
    else:
        assert context["kind"] == "boost"
        assert (final["ride"]["boosted_until"] is not None) is paid
        if paid:
            assert final["ride"]["boost_tier"] == "quick"
        assert final["ledger"] == [] and observed["driver_balance_kop"] == 0


def _assert_truthful_reject(response, snapshot, applied):
    assert response.status_code < 500, response.text
    if applied:
        # Android .map{} shows rejection for ANY successful HTTP. An applied
        # payment cannot claim that its transfer was rejected, regardless of code.
        assert not 200 <= response.status_code < 300, "applied payment cannot report successful rejection"
        assert snapshot["payment"]["status"] == "succeeded"
    else:
        assert response.status_code == 200 and response.json()["status"] == "canceled", response.text
        assert snapshot["payment"]["status"] == "canceled"


@pytest.mark.parametrize("kind", ["boost", "legacy_booking"])
@pytest.mark.parametrize("first", ["confirm", "reject"])
def test_pg_manual_confirmation_and_rejection_both_first_payment_lock_orders(client, user_factory, kind, first):
    context = _input(client, user_factory, kind)
    before = _snapshot(context)
    second = "reject" if first == "confirm" else "confirm"
    with SqlRace() as locks, ThreadPoolExecutor(max_workers=2) as executor:
        gate = locks.gate_first_lock(first)
        a = executor.submit(_request, client, context, first, first)
        try:
            _await_gate(gate, a)
            b = executor.submit(_request, client, context, second, second)
            locks.await_blocked(locks.lock_pid(second, b), locks.lock_pid(first, a), b)
        finally:
            locks.release_all()
        responses = {first: a.result(timeout=25), second: b.result(timeout=25)}
        observed = _observe(client, context, tuple(responses.values()), before,
                            first=first, sql_observations=locks.observations)
    expected = "succeeded" if first == "confirm" else "canceled"
    _assert_effect(context, observed, expected)
    _truthful_confirm(responses["confirm"], observed["after"], context["payment_id"])
    _assert_truthful_reject(responses["reject"], observed["after"], applied=first == "confirm")
    # Repeat both real endpoints: no second earn, boost extension or notification.
    repeated_confirm = _action(client, context["admin"], context["payment_id"], "confirm")
    repeated_reject = _action(client, context["admin"], context["payment_id"], "reject")
    repeated = _observe(client, context, (repeated_confirm, repeated_reject), observed["after"], first=first)
    assert repeated["after"] == observed["after"], "terminal retry must not rewrite ledger/boost/notification IDs"
    _truthful_confirm(repeated_confirm, repeated["after"], context["payment_id"])
    _assert_truthful_reject(repeated_reject, repeated["after"], applied=first == "confirm")


def test_pg_manual_booking_confirmation_has_no_committed_paid_pending_rejection_window(client, user_factory):
    # Positive calibration: this independent reader really sees committed paid,
    # succeeded and earn, rather than always reading an empty/stale test snapshot.
    calibration = _input(client, user_factory, "legacy_booking")
    calibration_response = _action(client, calibration["admin"], calibration["payment_id"], "confirm")
    calibration_external = _committed_booking(calibration)
    print("QA_MANUAL_PG_CALIBRATION " + json.dumps({"http": calibration_response.status_code,
          "body": calibration_response.json(), "external": calibration_external}, sort_keys=True))
    assert calibration_response.status_code == 200 and calibration_external["status"] == "succeeded"
    assert calibration_external["paid"] is True and calibration_external["earned_kop"] == 40000

    context = _input(client, user_factory, "legacy_booking")
    before, external_before = _snapshot(context), _committed_booking(context)
    assert external_before["status"] == "pending" and external_before["paid"] is False
    assert external_before["earned_kop"] == 0
    rejected_while_paused = None
    with SqlRace(gap_context=context) as locks, ThreadPoolExecutor(max_workers=2) as executor:
        confirmed = executor.submit(_request, client, context, "gap_confirm", "confirm")
        try:
            if not locks.update_observed.wait(_BOUND):
                if confirmed.done():
                    confirmed.result()
                raise AssertionError("actual confirm UPDATE was not observed; cannot infer atomic settlement")
            gap = locks.gap_observation
            if gap["mode"] == "observed_committed_gap":
                # The callback pauses BEFORE the real Payment UPDATE, and external
                # paid/earn prove that the preceding financial transaction committed.
                assert locks.gap_gate.entered.wait(_BOUND)
                rejected = executor.submit(_request, client, context, "gap_reject", "reject")
                relation = locks.await_blocked(locks.lock_pid("gap_reject", rejected),
                                              gap["product_pid"], rejected, allow_completed=True)
                if relation == "completed":
                    reject_response = rejected.result(timeout=25)
                    rejected_while_paused = {
                        "response": {"http": reject_response.status_code, "body": reject_response.json()},
                        "external": _committed_booking(context), "snapshot": _snapshot(context)}
                    print("QA_MANUAL_PG_GAP_REJECT " + json.dumps(rejected_while_paused, sort_keys=True))
                locks.gap_gate.release.set()
                confirm_response, reject_response = confirmed.result(timeout=25), rejected.result(timeout=25)
            else:
                # Fixed atomic code has no committed applied effect at UPDATE. Do
                # not wait for the removed gap; require its observer plus calibration.
                confirm_response = confirmed.result(timeout=25)
                reject_response = _request(client, context, "after_atomic_confirm", "reject")
        finally:
            locks.release_all()
        external_final = _committed_booking(context)
        observed = _observe(client, context, (confirm_response, reject_response), before,
                            calibration=calibration_external, external_before=external_before,
                            gap=gap, rejected_while_paused=rejected_while_paused,
                            external_final=external_final, sql_observations=locks.observations)
    # Capture BOTH actual responses and any intermediate canceled row/notification
    # before asserting: a later UPDATE must not hide an already false rejection.
    if rejected_while_paused is not None:
        interim = rejected_while_paused
        assert not 200 <= interim["response"]["http"] < 300, "reject succeeded after actual committed payment effect"
        assert interim["external"]["status"] != "canceled", "paid booking was exposed as a canceled payment"
        assert interim["snapshot"]["notification_ids"] == before["notification_ids"], "no false transfer-not-found notification"
    if gap["mode"] == "no_observed_gap":
        assert gap["external"]["status"] == "pending" and gap["external"]["paid"] is False
        assert gap["external"]["earned_kop"] == 0
    else:
        assert gap["external"]["paid"] is True and gap["external"]["earned_kop"] == 40000
        assert gap["external"]["status"] == "pending", "capture the actual settlement-to-status commit window"
    assert confirm_response.status_code == 200, confirm_response.text
    _assert_effect(context, observed, "succeeded")
    _truthful_confirm(confirm_response, observed["after"], context["payment_id"])
    _assert_truthful_reject(reject_response, observed["after"], applied=True)
    assert external_final["status"] == "succeeded" and external_final["paid"] is True
    assert external_final["earned_kop"] == 40000
    assert observed["after"]["notification_ids"] == before["notification_ids"]
    repeated_confirm = _action(client, context["admin"], context["payment_id"], "confirm")
    repeated_reject = _action(client, context["admin"], context["payment_id"], "reject")
    repeated = _observe(client, context, (repeated_confirm, repeated_reject), observed["after"], gap_mode=gap["mode"])
    assert repeated["after"] == observed["after"], "retry must preserve the one append-only earn and notifications"
    _truthful_confirm(repeated_confirm, repeated["after"], context["payment_id"])
    _assert_truthful_reject(repeated_reject, repeated["after"], applied=True)
