"""leaf-1.1 · замок в settle_debt_from_wallet при ДВУХ одновременных долгах (PostgreSQL).

Первая редакция этого обхода сняла мутацию M25 («снять `with_for_update` и прогнать гонку на
PostgreSQL») честно — на ОДНОМ долге условный `UPDATE ... WHERE status=unpaid` сам по себе уже
не даёт списать дважды, замок для этого конкретного сценария не нужен. Независимое ревью Opus
это подтвердило и дополнило: замок НУЖЕН, когда долгов НЕСКОЛЬКО — тогда устаревший баланс даёт
не двойное списание ОДНОГО долга, а ПЕРЕРАСХОД суммарно (кошелёк уходит в минус).

Сценарий (PostgreSQL, READ COMMITTED). Кошелёк 300 ₽. Два долга по 200 ₽ каждый (D1 старше,
D2 младше), оба по оплаченным заказам. Без замка: оба зачёта читают баланс 300, зачёт A успевает
закрыть D1 (200 ≤ 300) и остановиться на D2 (400 > 300); зачёт B, прочитавший ТОТ ЖЕ устаревший
баланс 300 ДО того, как A закрыл D1, со своей стороны тоже видит «D2 умещается» (0+200 ≤ 300,
раз D1 его интерфейс уже пропустил как «занят» через atomic UPDATE) и закрывает D2. Итог:
оба долга paid, списано 400 ₽ с кошелька в 300 ₽ — минус 100 ₽, которого ни на ком нет.

Это НЕ «тот же долг дважды» (это уже защищено условным UPDATE и проверено отдельно), а
суммарный перерасход по ДВУМ долгам сразу — то, от чего защищает именно замок строки водителя
(`with_for_update`, взятый ДО чтения баланса).

Тест детерминированный: без потоков, без sleep. Пока A держит замок (внутри своей открытой
транзакции, между чтением баланса и коммитом), B тем же способом пытается зачесть кошелёк —
с коротким `lock_timeout`, чтобы тест не завис, если защита вдруг снята и B пришлось бы ждать
коммита A целиком (в этом случае B просто пройдёт без ожидания — ровно то, что мы и проверяем).
"""
import pytest
from sqlalchemy import text
from sqlalchemy.exc import OperationalError
from sqlmodel import Session, select

from app import debt as debt_mod
from app import ledger
from app.db import engine
from app.models import CommissionDebt, DebtStatus, InstantOrder, InstantOrderStatus, LedgerEntry, LedgerKind, UserRole
from app.timeutil import utcnow

pytestmark = pytest.mark.skipif(
    engine.dialect.name != "postgresql",
    reason="FOR UPDATE — пустышка на SQLite; гонка нескольких долгов доказательна только на PostgreSQL",
)


def _водитель_с_двумя_долгами_и_кошельком(user_factory):
    водитель = user_factory("ЗамокДваДолга", role=UserRole.driver)
    пассажир = user_factory("ЗамокДваДолгаПас")
    ids = []
    with Session(engine) as s:
        import datetime as dt
        база_времени = utcnow()
        for i, смещение in enumerate((2, 1)):   # D1 старше (2 дня), D2 младше (1 день)
            o = InstantOrder(passenger_id=пассажир["id"], driver_id=водитель["id"],
                             from_lat=52.5, from_lng=58.3, to_lat=52.7, to_lng=58.6,
                             status=InstantOrderStatus.done, price_estimate=200, price_final=200,
                             paid=True, done_at=utcnow())
            s.add(o)
            s.commit()
            s.refresh(o)
            d = CommissionDebt(driver_id=водитель["id"], order_id=o.id, amount_kop=20_000,
                               week="2026-W40", status=DebtStatus.unpaid,
                               created_at=база_времени - dt.timedelta(days=смещение))
            s.add(d)
            s.commit()
            s.refresh(d)
            ids.append(d.id)
        s.add(LedgerEntry(driver_id=водитель["id"], kind=LedgerKind.adj, amount_kop=30_000,
                          note="тест: кошелёк 300 ₽"))
        s.commit()
    return водитель, ids[0], ids[1]


def test_r1_lock_prevents_overdraw_when_two_debts_fit_a_stale_balance(client, user_factory, monkeypatch):
    drv, d1, d2 = _водитель_с_двумя_долгами_и_кошельком(user_factory)
    настоящий_driver_balance = ledger.driver_balance
    состояние = {"armed": True, "b_blocked": False, "b_result": None}

    def driver_balance_с_вклинившимся_b(s, driver_id):
        значение = настоящий_driver_balance(s, driver_id)   # A уже держит замок (debt.py) и прочла баланс
        if состояние["armed"]:
            состояние["armed"] = False
            with Session(engine) as b:
                b.execute(text("SET LOCAL lock_timeout = '500ms'"))
                try:
                    состояние["b_result"] = debt_mod.settle_debt_from_wallet(b, driver_id)
                    b.commit()
                except OperationalError:
                    # С замком B упирается в замок A и честно ждёт/отказывает — так и должно быть.
                    b.rollback()
                    состояние["b_blocked"] = True
        return значение

    monkeypatch.setattr(debt_mod, "driver_balance", driver_balance_с_вклинившимся_b)
    with Session(engine) as a:
        a.execute(text("SET LOCAL lock_timeout = '5s'"))
        debt_mod.settle_debt_from_wallet(a, drv["id"])

    assert состояние["armed"] is False, "соперник B не вклинился — тест ничего не проверил"
    assert состояние["b_blocked"] is True, (
        "B прошёл ПАРАЛЛЕЛЬНО с A, не упёршись в замок — без замка на строке водителя два "
        "зачёта кошелька по разным долгам могут вместе потратить больше баланса, чем есть"
    )

    with Session(engine) as s:
        баланс = ledger.driver_balance(s, drv["id"])
        fee_rows = s.exec(select(LedgerEntry).where(
            LedgerEntry.driver_id == drv["id"], LedgerEntry.kind == LedgerKind.fee)).all()
        долг1 = s.get(CommissionDebt, d1)
        долг2 = s.get(CommissionDebt, d2)

    assert баланс == 10_000, f"баланс {баланс} коп. — без замка ушёл бы в минус (−10 000)"
    assert [e.amount_kop for e in fee_rows] == [-20_000], (
        f"списаний {len(fee_rows)} вместо одного на 200 ₽: {[e.amount_kop for e in fee_rows]}"
    )
    assert долг1.status == DebtStatus.paid, "старший долг (D1) обязан закрыться первым (FIFO)"
    assert долг2.status == DebtStatus.unpaid, "младший долг (D2) должен остаться неоплаченным — денег не хватило"
