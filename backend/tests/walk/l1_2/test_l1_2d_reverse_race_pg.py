"""leaf-1.2d — оставшиеся обратные гонки на НАСТОЯЩЕЙ блокировке строки PostgreSQL.

Зеркало `test_l1_2c_reverse_race_pg.py`: одна сторона уже записала долг и держит строку до коммита,
вторая в этот момент пишет тот же долг. Вторая прочитала долг ДО коммита первой — для неё он «ещё
не закрыт». Её запись встаёт в очередь за лок (подтверждаем через `pg_blocking_pids`, не домыслом).
После коммита первой Postgres перепроверяет WHERE на свежей версии строки: условие «ещё не закрыт»
уже ложно — запись не применяется. Без условия в WHERE она применялась и затирала закрытие.

  • «Я оплатил» ждёт вебхук карты;
  • разбор «пассажир не заплатил» ждёт вебхук карты;
  • старый счёт картой (без снимка) ждёт «простить» админа.
"""
import threading
import time

import pytest
from sqlalchemy import text
from sqlmodel import Session

from app import debt as debt_mod
from app.db import engine
from app.models import CommissionDebt, DebtStatus, Payment
from app.routers.payments import _activate_payment
from app.timeutil import utcnow

from test_l1_2b_taxi_debt_snapshot import _debt, _driver_with_debts, _snapshot_payment, _wallet_balance

pytestmark = pytest.mark.skipif(engine.dialect.name != "postgresql", reason="requires isolated PostgreSQL row locks")


class _HoldsCommit(Session):
    """Сессия первой стороны: всё записала, но коммит придерживает, пока тест не отпустит."""
    holds: threading.Event
    may_commit: threading.Event
    pid_box: dict

    def commit(self):
        self.pid_box["pid"] = self.exec(text("SELECT pg_backend_pid()")).one()[0]
        self.holds.set()
        if not self.may_commit.wait(15):
            raise AssertionError("основной поток не отпустил первую сторону вовремя")
        super().commit()


def _race(first, second):
    """`first(session)` пишет и держит коммит; `second()` пишет тот же долг и должна встать за лок."""
    holds, may_commit, first_pid, first_err = threading.Event(), threading.Event(), {}, {}

    def run_first():
        try:
            with _HoldsCommit(engine) as s:
                s.holds, s.may_commit, s.pid_box = holds, may_commit, first_pid
                first(s)
        except Exception as e:  # noqa: BLE001 — сохраняем ошибку фонового потока
            first_err["error"] = e
            holds.set()

    t1 = threading.Thread(target=run_first)
    t1.start()
    assert holds.wait(15), "первая сторона не дошла до коммита"
    if "error" in first_err:
        raise first_err["error"]

    box = {}

    def run_second():
        try:
            box["result"] = second()
        except Exception as e:  # noqa: BLE001
            box["error"] = e

    t2 = threading.Thread(target=run_second)
    t2.start()
    confirmed = False
    deadline = time.monotonic() + 6
    with engine.connect().execution_options(isolation_level="AUTOCOMMIT") as observer:
        while time.monotonic() < deadline and t2.is_alive():
            rows = observer.execute(text(
                "SELECT pg_blocking_pids(pid) AS blockers FROM pg_stat_activity "
                "WHERE lower(query) LIKE '%commissiondebt%' AND wait_event_type = 'Lock'"
            )).mappings().all()
            if any(first_pid["pid"] in r["blockers"] for r in rows):
                confirmed = True
                break
            time.sleep(0.05)
    may_commit.set()
    t1.join(timeout=15)
    t2.join(timeout=15)
    if "error" in first_err:
        raise first_err["error"]
    assert confirmed, "вторая запись не встала за лок первой — гонка не воспроизведена"
    if "error" in box:
        raise box["error"]
    return box.get("result")


def test_pg_declare_waits_for_the_card_and_does_not_reopen_the_debt(client, user_factory):
    drv, (d1, d2) = _driver_with_debts(user_factory, "L12dPgDeclare", [10_000, 15_000])
    pay_id = _snapshot_payment(drv["id"], 25_000, tier=debt_mod.make_taxi_debt_snapshot_tier((d1, d2)))
    with Session(engine) as s:
        assert len(debt_mod._unpaid(s, drv["id"])) == 2

    def card(s):
        _activate_payment(s, s.get(Payment, pay_id))

    def declare():
        with Session(engine) as s:
            return debt_mod.declare_paid(s, drv["id"])

    with pytest.raises(debt_mod.DebtAlreadySettled):
        _race(card, declare)

    for d in (d1, d2):
        after = _debt(d)
        assert after.status == DebtStatus.paid, "оплаченный картой долг снова «на подтверждении»"
        assert not after.declare_count


def test_pg_unpaid_report_waits_for_the_card_and_refunds_instead_of_writing_off(client, user_factory):
    drv, (d1,) = _driver_with_debts(user_factory, "L12dPgVoid", [10_000])
    pay_id = _snapshot_payment(drv["id"], 10_000, tier=debt_mod.make_taxi_debt_snapshot_tier((d1,)))
    order_id = _debt(d1).order_id

    def card(s):
        _activate_payment(s, s.get(Payment, pay_id))

    def unpaid_report():
        with Session(engine) as s:
            res = debt_mod.void_debt_for_order(s, order_id)
            s.commit()
            return res

    assert _race(card, unpaid_report) == "refunded"
    after = _debt(d1)
    assert after.status == DebtStatus.paid
    assert not (after.note or "").startswith(debt_mod.WRITTEN_OFF_PREFIX), "оплата картой «списана по разбору»"
    assert _wallet_balance(drv["id"]) == 10_000


def test_pg_legacy_card_bill_waits_for_forgive_and_refunds_it(client, user_factory):
    drv, (d1, d2) = _driver_with_debts(user_factory, "L12dPgLegacy", [10_000, 15_000])
    pay_id = _snapshot_payment(drv["id"], 25_000, tier="")

    def forgive(s):
        d = s.get(CommissionDebt, d2)
        d.status = DebtStatus.paid
        d.confirmed_at = utcnow()
        d.note = "Списан админом: гонка"
        s.add(d)
        s.flush()          # UPDATE уходит сейчас и берёт лок строки — коммит придержит _HoldsCommit
        s.commit()

    def card():
        with Session(engine) as s:
            p = s.get(Payment, pay_id)
            _activate_payment(s, p)
            s.refresh(p)
            return p.status

    assert _race(forgive, card) == "succeeded"
    assert _debt(d1).status == DebtStatus.paid
    assert _debt(d2).note == "Списан админом: гонка", "оплата картой затёрла прощение"
    assert _wallet_balance(drv["id"]) == 15_000, "карта взяла деньги за прощённый долг и не вернула"
