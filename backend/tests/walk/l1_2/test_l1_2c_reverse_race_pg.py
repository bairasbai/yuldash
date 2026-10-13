"""leaf-1.2c — обратная гонка на НАСТОЯЩЕЙ блокировке строки PostgreSQL.

Зеркало `test_l1_2b_taxi_debt_snapshot_pg.py`. Там админ держал строку долга, а вебхук карты ждал;
здесь наоборот: вебхук карты (`_activate_payment`, purpose=taxi_debt) уже закрыл долг своим UPDATE и
держит строку до коммита, а админ в этот момент жмёт «простить» / «подтвердить» / «отклонить».

Админ прочитал долг ДО коммита карты — для него долг ещё не оплачен. Его запись встаёт в очередь за
лок карты (подтверждаем через `pg_blocking_pids`, не домыслом). Когда карта коммитит, Postgres
перепроверяет WHERE записи админа на СВЕЖЕЙ версии строки: условие «ещё не оплачен» уже ложно, и
запись не применяется. Без условия в WHERE запись админа применялась и затирала оплату картой.
"""
import threading
import time

import pytest
from sqlalchemy import text
from sqlmodel import Session

from app import debt as debt_mod
from app.db import engine
from app.models import DebtStatus, Payment, UserRole
from app.routers.payments import _activate_payment

from test_l1_2b_taxi_debt_snapshot import _debt, _driver_with_debts, _snapshot_payment

pytestmark = pytest.mark.skipif(engine.dialect.name != "postgresql", reason="requires isolated PostgreSQL row locks")


class _CardHoldsCommit(Session):
    """Сессия вебхука: всё сделала, но коммит придерживает, пока тест не отпустит."""
    holds: threading.Event
    may_commit: threading.Event
    pid_box: dict

    def commit(self):
        self.pid_box["pid"] = self.exec(text("SELECT pg_backend_pid()")).one()[0]
        self.holds.set()
        if not self.may_commit.wait(15):
            raise AssertionError("основной поток не отпустил вебхук вовремя")
        super().commit()


@pytest.mark.parametrize("action", ["forgive", "confirm", "reject"])
def test_pg_admin_write_waits_for_the_card_and_then_does_not_overwrite_it(client, user_factory, action):
    admin = user_factory(f"L12cPgAdm{action}", role=UserRole.admin)
    drv, (d1, d2) = _driver_with_debts(user_factory, f"L12cPg{action}", [10_000, 15_000])
    if action != "forgive":
        with Session(engine) as s:
            assert debt_mod.declare_paid(s, drv["id"]) == 25_000
    pay_id = _snapshot_payment(drv["id"], 25_000, tier=debt_mod.make_taxi_debt_snapshot_tier((d1, d2)))

    holds, may_commit, card_pid, card_err = threading.Event(), threading.Event(), {}, {}

    def card_webhook():
        try:
            with _CardHoldsCommit(engine) as s:
                s.holds, s.may_commit, s.pid_box = holds, may_commit, card_pid
                _activate_payment(s, s.get(Payment, pay_id))
        except Exception as e:  # noqa: BLE001 — сохраняем ошибку фонового потока
            card_err["error"] = e
            holds.set()

    card = threading.Thread(target=card_webhook)
    card.start()
    assert holds.wait(15), "вебхук не дошёл до коммита"
    if "error" in card_err:
        raise card_err["error"]

    admin_box = {}

    def admin_click():
        body = {"reason": "гонка"} if action == "forgive" else {"amount_kop": 25_000}
        admin_box["r"] = client.post(f"/admin/debts/{d1}/{action}", headers=admin["auth"], json=body)

    admin_thread = threading.Thread(target=admin_click)
    admin_thread.start()

    confirmed = False
    deadline = time.monotonic() + 6
    with engine.connect().execution_options(isolation_level="AUTOCOMMIT") as observer:
        while time.monotonic() < deadline and admin_thread.is_alive():
            rows = observer.execute(text(
                "SELECT pg_blocking_pids(pid) AS blockers FROM pg_stat_activity "
                "WHERE lower(query) LIKE '%commissiondebt%' AND wait_event_type = 'Lock'"
            )).mappings().all()
            if any(card_pid["pid"] in r["blockers"] for r in rows):
                confirmed = True
                break
            time.sleep(0.05)
    may_commit.set()
    card.join(timeout=15)
    admin_thread.join(timeout=15)
    if "error" in card_err:
        raise card_err["error"]

    assert confirmed, "запись админа не встала за лок вебхука — гонка не воспроизведена"
    r = admin_box["r"]
    assert r.status_code == 200, r.text
    assert r.json().get("already") is True
    for d in (d1, d2):
        after = _debt(d)
        assert after.status == DebtStatus.paid, "оплаченный картой долг снова не оплачен"
        assert not (after.note or "").startswith("Списан админом"), "оплата картой затёрта прощением"
