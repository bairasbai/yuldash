"""leaf-1.2b — настоящая гонка: админ прощает долг (`/admin/debts/{id}/forgive`, routers/debt.py)
РОВНО в тот момент, когда атомарный UPDATE вебхука (`_activate_payment`, purpose=taxi_debt)
пытается тронуть ТУ ЖЕ строку `CommissionDebt`. Ни тот путь, ни этот не берут общий лок на
строку долга (в `admin_forgive` — обычный ORM read-then-write; в моём коде — один атомарный
`UPDATE ... WHERE status != paid RETURNING id`, но лок на САМУ строку — тот же implicit
row-lock Postgres, что и у любого UPDATE). Доказываем НАСТОЯЩЕЙ конкуренцией (образец —
`test_l1_2_wallet_race_pg.py`): держим строку долга залоченной (flush без commit — имитация
`admin_forgive` ДО фактического коммита), запускаем вебхук, подтверждаем через
`pg_blocking_pids`, что он РЕАЛЬНО ждёт на уровне СУБД, затем отпускаем админа.

Направление гонки, которое это закрывает: форгив УСПЕВАЕТ закоммититься РАНЬШЕ, чем атомарный
UPDATE вебхука фактически применится к этой строке (даже если обе транзакции СТАРТОВАЛИ и
«увидели» долг ещё unpaid) — `WHERE status != paid` переоценивается Postgres'ом ПОСЛЕ снятия
блокировки, на СВЕЖИХ данных (EvalPlanQual), поэтому вебхук правильно исключает прощённый долг
и возвращает его сумму в кошелёк, а не перезаписывает прощение.

ОБРАТНОЕ направление (вебхук коммитится первым, `admin_forgive` читает долг ДО этого коммита
и потом вслепую перезаписывает его статус/note своим ORM-UPDATE без проверки текущего
состояния) этим НЕ закрыто — `routers/debt.py`/`admin_forgive` вне зоны этого листа (только
чтение). См. «ВНЕ ЗОНЫ» в отчёте.
"""
import threading
import time

import pytest
from sqlalchemy import text
from sqlmodel import Session

from app import debt as debt_mod
from app.db import engine
from app.models import CommissionDebt, DebtStatus
from app.timeutil import utcnow

from test_l1_2b_taxi_debt_snapshot import _activate, _debt, _driver_with_debts, _ledger_adj_entries, _snapshot_payment, _wallet_balance

pytestmark = pytest.mark.skipif(engine.dialect.name != "postgresql", reason="requires isolated PostgreSQL row locks")


def test_pg_webhook_atomically_excludes_a_debt_the_admin_is_forgiving_right_now(client, user_factory):
    drv, (d1, d2) = _driver_with_debts(user_factory, "L12bPgRace", [10_000, 15_000])
    tier = debt_mod.make_taxi_debt_snapshot_tier((d1, d2))
    pay_id = _snapshot_payment(drv["id"], 25_000, tier=tier)

    admin_holds = threading.Event()
    admin_may_commit = threading.Event()
    admin_pid_box = {}
    admin_error_box = {}

    def admin_forgive_d2_and_hold():
        try:
            with Session(engine) as s:
                debt = s.get(CommissionDebt, d2)
                debt.status = DebtStatus.paid
                debt.confirmed_at = utcnow()
                debt.note = "Списан админом: гонка leaf-1.2b"
                s.add(debt)
                s.flush()  # UPDATE уже выполнен в PostgreSQL — строка d2 реально заблокирована
                admin_pid_box["pid"] = s.exec(text("SELECT pg_backend_pid()")).one()[0]
                admin_holds.set()
                if not admin_may_commit.wait(15):
                    raise AssertionError("основной поток не отпустил админа вовремя")
                s.commit()
        except Exception as e:  # noqa: BLE001 — сохраняем, чтобы не потерять ошибку фонового потока
            admin_error_box["error"] = e
            admin_holds.set()

    admin_thread = threading.Thread(target=admin_forgive_d2_and_hold)
    admin_thread.start()
    assert admin_holds.wait(15), "админ не успел взять лок на строке долга"
    if "error" in admin_error_box:
        raise admin_error_box["error"]
    admin_pid = admin_pid_box["pid"]

    result_box = {}

    def run_webhook():
        result_box["payment"] = _activate(pay_id)

    webhook_thread = threading.Thread(target=run_webhook)
    webhook_thread.start()

    # Подтверждаем РЕАЛЬНУЮ блокировку PostgreSQL — не домысел, а зафиксированный факт ожидания
    # одной сессией другой на уровне СУБД (тот же приём, что test_l1_2_wallet_race_pg.py).
    deadline = time.monotonic() + 6
    confirmed = False
    with engine.connect().execution_options(isolation_level="AUTOCOMMIT") as observer:
        while time.monotonic() < deadline:
            rows = observer.execute(text(
                "SELECT pid, pg_blocking_pids(pid) AS blockers, query FROM pg_stat_activity "
                "WHERE lower(query) LIKE '%commissiondebt%' AND wait_event_type = 'Lock'"
            )).mappings().all()
            if any(admin_pid in r["blockers"] for r in rows):
                confirmed = True
                break
            if not webhook_thread.is_alive():
                break
            time.sleep(0.05)
    admin_may_commit.set()
    admin_thread.join(timeout=15)
    webhook_thread.join(timeout=15)

    if "error" in admin_error_box:
        raise admin_error_box["error"]
    assert confirmed, "вебхук не встал в настоящую PostgreSQL-блокировку за админом — гонка не воспроизведена"

    payment = result_box["payment"]
    assert payment.status == "succeeded"
    assert _debt(d1).status == DebtStatus.paid, "единственный реально открытый долг снимка обязан закрыться"
    forgiven = _debt(d2)
    assert forgiven.note == "Списан админом: гонка leaf-1.2b", (
        "вебхук перезаписал реальное прощение, выигравшее настоящую гонку на строке долга"
    )
    assert _wallet_balance(drv["id"]) == 15_000, "деньги за прощённый (но списанный картой) долг обязаны вернуться"
    assert len(_ledger_adj_entries(drv["id"])) == 1
