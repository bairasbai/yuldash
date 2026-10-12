"""leaf-1.2 — настоящая гонка двух ПАРАЛЛЕЛЬНЫХ запросов на оплату одного быстрого заказа.

Независимое ревью (Opus 5.5, 2026-10-02) нашло: предыдущая правка этого листа (упростила
условие дедупа в `_pay_cashless`) закрывала только часть окна — «узкое окно между двумя
commit внутри ОДНОГО запроса» и «строку-сироту» после падения процесса (см.
`test_l1_2_wallet_race.py`, который воспроизводит именно это, засевая промежуточное
состояние). Она НЕ закрывала случай двух ПО-НАСТОЯЩЕМУ параллельных запросов: если оба
проходят SELECT дедупа ДО того, как любой из них вставил свою строку, оба видят «пусто»
и оба заводят свою — на настоящей ЮKassa это два разных Idempotence-Key, то есть два
реальных списания за одну и ту же поездку.

От этого защищает блокировка строки заказа (`with_for_update`) в начале `pay_instant_order`:
вторая нить ждёт, пока первая закоммитит свой INSERT (коммит снимает блокировку сразу после
дедупа — см. комментарий в `_pay_cashless`), и заново проходит дедуп, уже видя её строку.

Доказываем НАСТОЯЩЕЙ конкуренцией (образец — `test_pay_agreement_payment_concurrency.py` для
брони): два потока, реальная блокировка PostgreSQL, подтверждённая через `pg_blocking_pids` —
не имитация, а зафиксированный факт ожидания одной нитью другой на уровне СУБД. Ключевая
тонкость: барьер держит нить ПОСЛЕ того, как её FOR UPDATE реально выполнился (лок уже взят),
а не до — иначе вторая нить просто не успевает встретить живую блокировку.
"""
from concurrent.futures import ThreadPoolExecutor
from contextvars import ContextVar
from threading import Event, Lock
from time import monotonic

import pytest
from sqlalchemy import event, text
from sqlmodel import Session, select

from app.db import engine
from app.models import Payment, UserRole

from test_ledger import _make_done_order

pytestmark = pytest.mark.skipif(engine.dialect.name != "postgresql", reason="requires isolated PostgreSQL row locks")
_operation = ContextVar("qa_order_payment_operation", default=None)
_BOUND = 15


class Gate:
    def __init__(self):
        self.entered, self.release = Event(), Event()

    def hold(self):
        self.entered.set()
        assert self.release.wait(_BOUND), "race gate was not released"


class SqlLocks:
    """Наблюдает за РЕАЛЬНЫМ SQL. Держит нить ПОСЛЕ того, как её FOR UPDATE на заказе реально
    выполнился (лок уже взят СУБД) — ничего в самом SQL не подменяется."""

    def __init__(self):
        self.pids, self.lock_counts, self.lock_pids, self.lock_started = {}, {}, {}, {}
        self.after_gates = {}
        self.observations, self.mutex = [], Lock()

    def gate_after(self, label, occurrence=1):
        gate = Gate()
        self.after_gates[(label, occurrence)] = gate
        return gate

    def _before(self, connection, cursor, statement, parameters, context, executemany):
        label = _operation.get()
        if label is None:
            return
        with connection.connection.cursor() as control:
            control.execute("SET LOCAL lock_timeout = '8s'")
            control.execute("SET LOCAL statement_timeout = '12s'")
            control.execute("SELECT pg_backend_pid()")
            pid = control.fetchone()[0]
        sql = " ".join(statement.lower().split())
        with self.mutex:
            self.pids[label] = pid
            if "from instantorder" in sql and "for update" in sql:
                occurrence = self.lock_counts.get(label, 0) + 1
                self.lock_counts[label] = occurrence
                context.qa_order_lock = (label, occurrence)
                self.lock_pids[(label, occurrence)] = pid
                # Событие ставим ЗДЕСЬ, до самого execute(): PID известен независимо от того,
                # получит ли ЭТА нить лок сразу или будет ждать его на стороне СУБД.
                self.lock_started.setdefault((label, occurrence), Event()).set()
                self.observations.append({"operation": label, "pid": pid,
                                          "phase": "before_order_lock", "occurrence": occurrence})
            else:
                context.qa_order_lock = None

    def _after(self, connection, cursor, statement, parameters, context, executemany):
        lock = getattr(context, "qa_order_lock", None)
        if not lock:
            return
        label, occurrence = lock
        with self.mutex:
            self.observations.append({"operation": label, "pid": self.pids[label],
                                      "phase": "acquired_order_lock", "occurrence": occurrence})
        gate = self.after_gates.get(lock)
        if gate:
            gate.hold()

    def __enter__(self):
        event.listen(engine, "before_cursor_execute", self._before)
        event.listen(engine, "after_cursor_execute", self._after)
        return self

    def __exit__(self, *args):
        for gate in self.after_gates.values():
            gate.release.set()
        event.remove(engine, "before_cursor_execute", self._before)
        event.remove(engine, "after_cursor_execute", self._after)

    def lock_pid(self, label, occurrence, future):
        with self.mutex:
            started = self.lock_started.setdefault((label, occurrence), Event())
        if not started.wait(_BOUND):
            if future.done():
                future.result()
            raise AssertionError(f"{label}: InstantOrder lock#{occurrence} не наблюдалась")
        with self.mutex:
            return self.lock_pids[(label, occurrence)]

    def blocked_by(self, waiter, blocker, future):
        assert waiter != blocker
        deadline = monotonic() + 6
        last = None
        with engine.connect().execution_options(isolation_level="AUTOCOMMIT") as observer:
            observer_pid = observer.execute(text("SELECT pg_backend_pid()")).scalar_one()
            assert observer_pid not in (waiter, blocker)
            while monotonic() < deadline:
                row = observer.execute(text(
                    "SELECT pid, wait_event_type, query, pg_blocking_pids(pid) AS blockers "
                    "FROM pg_stat_activity WHERE pid=:pid"), {"pid": waiter}).mappings().one_or_none()
                last = dict(row) if row else None
                if last and blocker in last["blockers"] and last["wait_event_type"] == "Lock":
                    assert "instantorder" in last["query"].lower(), last
                    self.observations.append({"phase": "pg_confirmed_wait", "observer_pid": observer_pid, **last})
                    return last
                if future.done():
                    future.result()
                    break
        raise AssertionError(f"Не нашли реальную PG-блокировку {waiter}->{blocker}; последнее: {last}")


def _pay(client, label, order_id, auth):
    token = _operation.set(label)
    try:
        return client.post(f"/instant/orders/{order_id}/pay", headers=auth, json={"method": "card"})
    finally:
        _operation.reset(token)


def _ride_payments(order_id):
    with Session(engine) as session:
        return list(session.exec(
            select(Payment).where(Payment.order_id == order_id, Payment.purpose == "ride")
            .order_by(Payment.id)
        ).all())


def test_pg_two_concurrent_pay_requests_before_either_insert_share_one_invoice(client, user_factory):
    """Раскладка, на которой ломалась предыдущая правка: оба запроса доходят до дедупа
    ДО insert первого. A придерживаем СРАЗУ ПОСЛЕ того, как её FOR UPDATE на заказе реально
    взял лок (но до её собственного дедупа/INSERT) — запускаем B и подтверждаем настоящую
    блокировку PostgreSQL, потом отпускаем A."""
    driver = user_factory("L12PgRaceDriver", role=UserRole.driver)
    passenger = user_factory("L12PgRacePassenger")
    order_id = _make_done_order(driver["id"], passenger["id"], price_rub=260)

    with SqlLocks() as locks, ThreadPoolExecutor(max_workers=2) as executor:
        gate_a = locks.gate_after("A")
        a = executor.submit(_pay, client, "A", order_id, passenger["auth"])
        try:
            assert gate_a.entered.wait(_BOUND), "A не дошла до настоящей блокировки заказа"
            b = executor.submit(_pay, client, "B", order_id, passenger["auth"])
            locks.blocked_by(locks.lock_pid("B", 1, b), locks.lock_pid("A", 1, a), b)
        finally:
            gate_a.release.set()
        ra, rb = a.result(timeout=25), b.result(timeout=25)

    assert ra.status_code == 200 and rb.status_code == 200, (ra.text, rb.text)
    rows = _ride_payments(order_id)
    assert len(rows) == 1, (
        f"два по-настоящему параллельных запроса завели {len(rows)} счетов вместо одного — "
        "на реальной ЮKassa это РАЗНЫЕ Idempotence-Key, то есть два настоящих списания "
        "за одну и ту же поездку"
    )
    assert {ra.json()["payment_id"], rb.json()["payment_id"]} == {rows[0].id}, (
        "оба ответа обязаны называть ОДИН и тот же payment_id"
    )
