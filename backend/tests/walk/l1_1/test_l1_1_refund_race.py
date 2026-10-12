"""leaf-1.1 · F3 (найдено независимым ревью Opus, 2026-10-02): двойной возврат комиссии при
гонке двух «подтвердить жалобу».

Сценарий: водитель уже перевёл комиссию по СБП (долг `paid`), пассажир позже доказуемо не
заплатил. Админ (или два админа) кликают «подтвердить жалобу» на эту поездку ДВАЖДЫ почти
одновременно. `void_debt_for_order` → `refund_commission_to_wallet` проверял «уже возвращали?»
только в коде («нашёл запись → не пишу») — без замка и без уникальности в БД. Обе сессии видят
«возврата нет» и обе пишут свою запись `refund:order:N`: водителю возвращают одну и ту же
комиссию дважды — деньги платформы уходят в никуда.

Воспроизводится и на SQLite (замков в этом пути не было совсем) — поэтому гонку here вклиниваем
через `before_flush`-перехват (тот же приём, что test_money_holes_audit.py::
test_гонка_двух_завершил_не_задваивает_компенсацию использует для компенсации промокода), а не
через настоящие потоки.

Исправление — зеркало защиты промокода: частичный UNIQUE-индекс `uq_ledgerentry_refund`
(ledger.py, kind=adj И ext_id начинается с "refund:") плюс вставка под SAVEPOINT
(`session.begin_nested`) в `refund_commission_to_wallet` — проигравшая гонку сторона ловит
IntegrityError и откатывает ТОЛЬКО свою вставку.
"""
from sqlalchemy import event
from sqlmodel import Session, select

from app import debt as debt_mod
from app import ledger
from app.db import engine
from app.models import CommissionDebt, DebtStatus, InstantOrder, InstantOrderStatus, LedgerEntry, LedgerKind, UserRole
from app.timeutil import utcnow


def _водитель_с_оплаченным_долгом(user_factory, имя: str, amount_kop: int = 4_000):
    водитель = user_factory(имя, role=UserRole.driver)
    пассажир = user_factory(имя + "Пас")
    with Session(engine) as s:
        o = InstantOrder(passenger_id=пассажир["id"], driver_id=водитель["id"],
                         from_lat=52.5, from_lng=58.3, to_lat=52.7, to_lng=58.6,
                         status=InstantOrderStatus.done, price_estimate=200, price_final=200,
                         paid=True, done_at=utcnow())
        s.add(o)
        s.commit()
        s.refresh(o)
        oid = o.id
        d = CommissionDebt(driver_id=водитель["id"], order_id=oid, amount_kop=amount_kop,
                           week="2026-W40", status=DebtStatus.paid, created_at=utcnow(),
                           confirmed_at=utcnow())    # уже ОПЛАЧЕН — деньги у платформы есть
        s.add(d)
        s.commit()
    return водитель, oid


def _возвраты(order_id: int) -> list[LedgerEntry]:
    with Session(engine) as s:
        return list(s.exec(select(LedgerEntry).where(
            LedgerEntry.kind == LedgerKind.adj,
            LedgerEntry.ext_id == debt_mod.refund_ext_id(order_id=order_id),
        )).all())


def test_r1_concurrent_report_confirmations_refund_the_commission_exactly_once(client, user_factory):
    водитель, oid = _водитель_с_оплаченным_долгом(user_factory, "F3Возврат")

    session = Session(engine)
    fired = {"n": 0}

    @event.listens_for(session, "before_flush")
    def _соперник(sess, ctx, instances):          # noqa: ARG001
        """Второй админ успел кликнуть «подтвердить» и закоммитить СВОЙ возврат раньше нас."""
        if fired["n"]:
            return
        fired["n"] = 1
        with Session(engine) as rival:
            исход = debt_mod.void_debt_for_order(rival, oid)
            assert исход == "refunded", "соперник обязан был успешно вернуть комиссию первым"
            rival.commit()

    try:
        исход = debt_mod.void_debt_for_order(session, oid)
        session.commit()
    finally:
        event.remove(session, "before_flush", _соперник)
        session.close()

    assert fired["n"] == 1, "соперник не вклинился — тест ничего не проверил"
    assert исход is None, "проигравшая гонку сторона обязана увидеть «уже возвращено», а не записать свою"
    возвраты = _возвраты(oid)
    assert len(возвраты) == 1, f"возврат списан {len(возвраты)} раз(а) вместо одного: {возвраты}"
    with Session(engine) as s:
        баланс = ledger.driver_balance(s, водитель["id"])
    assert баланс == 4_000, f"баланс {баланс} коп. — комиссию вернули не ровно один раз"
