"""Волна 200: зачёт долга из кошелька не должен списать дважды.

Компенсацию промо-скидки платформа кладёт водителю в кошелёк, а комиссию он платформе
должен. Одно гасит другое (волна 154). Гасит функция `debt.settle_debt_from_wallet`:
читает баланс, читает долги, помечает их оплаченными и пишет в кошелёк одну строку
списания.

Всё это она делала БЕЗ блокировки. Два одновременных вызова — а их легко получить: заказ
завершается и водитель в ту же секунду обновляет экран долга, или просто дважды жмёт кнопку —
оба читают один и тот же баланс, оба видят один и тот же долг и оба пишут списание.
Долг закрыт один раз, деньги сняты дважды: кошелёк уходит в минус, и это ЕГО деньги.

На SQLite гонку не воспроизвести — она живёт на боевом Postgres. Поэтому проверяем двумя
способами: договором (перед чтением баланса берётся блокировка строки водителя — тот же
приём, что у выплат в `ledger._payout`) и сценарием с принудительным чередованием.
"""
from __future__ import annotations

from datetime import timedelta

from sqlmodel import Session, select

from app import debt as debt_mod
from app import ledger
from app.db import engine
from app.models import (
    CommissionDebt, DebtStatus, InstantOrder, InstantOrderStatus as S, LedgerEntry,
    LedgerKind, UserRole,
)
from app.timeutil import utcnow


def _водитель_с_долгом(user_factory, имя: str, кошелёк_коп: int, долг_коп: int):
    """Водитель, у которого в кошельке лежат деньги платформы и висит долг по комиссии."""
    водитель = user_factory(имя, role=UserRole.driver)
    пассажир = user_factory(имя + "Пас")
    with Session(engine) as s:
        o = InstantOrder(passenger_id=пассажир["id"], driver_id=водитель["id"],
                         from_lat=52.591, from_lng=58.317, to_lat=52.716, to_lng=58.664,
                         status=S.done, price_estimate=300, price_final=300,
                         paid=True, done_at=utcnow())
        s.add(o)
        s.commit()
        s.refresh(o)
        oid = o.id
        s.add(LedgerEntry(driver_id=водитель["id"], kind=LedgerKind.adj,
                          amount_kop=кошелёк_коп, ext_id=f"promo:{oid}",
                          note="Компенсация промокода пассажира"))
        s.add(CommissionDebt(driver_id=водитель["id"], order_id=oid, week="2026-W35",
                             amount_kop=долг_коп, status=DebtStatus.unpaid,
                             created_at=utcnow() - timedelta(days=1), due_at=utcnow()))
        s.commit()
    return водитель, oid


class _СессияСоСчётчиком:
    """Настоящая сессия, но запоминает, брали ли блокировку строки (SELECT ... FOR UPDATE).

    Гонку на SQLite не воспроизвести: он сериализует запись сам, а Postgres — нет. Значит
    сценарием правило не закрепить, и проверяем ДОГОВОР (приём из волны 198).
    """

    def __init__(self, настоящая):
        self._с = настоящая
        self.замки: list = []          # какие таблицы блокировали, по порядку
        self.баланс_прочитан_после_блокировки = None

    @property
    def блокировок(self) -> int:
        return len(self.замки)

    def exec(self, statement, *a, **kw):
        текст = " ".join(str(statement).upper().split())
        if "FOR UPDATE" in текст:
            # Имя таблицы из «FROM имя»: важно КАКУЮ строку замкнули, а не только факт замка.
            части = текст.split(" FROM ")
            self.замки.append(части[1].split()[0].strip('"').lower() if len(части) > 1 else "?")
        if "SUM(LEDGERENTRY.AMOUNT_KOP)" in текст:
            self.баланс_прочитан_после_блокировки = self.блокировок > 0
        return self._с.exec(statement, *a, **kw)

    def __getattr__(self, имя):
        return getattr(self._с, имя)


def test_settlement_takes_a_row_lock_before_reading_the_balance(client, user_factory):
    """Договор: сперва блокировка водителя, потом чтение баланса.

    Наоборот нельзя: прочитал баланс, подождал на блокировке, а к моменту записи он уже
    другой — и списание уходит по устаревшему числу.
    """
    водитель, _ = _водитель_с_долгом(user_factory, "ГонкаДоговор", 30_000, 20_000)

    with Session(engine) as s:
        обёртка = _СессияСоСчётчиком(s)
        debt_mod.settle_debt_from_wallet(обёртка, водитель["id"])

    assert "user" in обёртка.замки, (
        f"замок не на водителе, а на {обёртка.замки or 'ничём'}: все денежные операции водителя "
        "обязаны сериализоваться ОДНИМ замком — тем же, что у выплат (`ledger._payout`)"
    )
    assert обёртка.баланс_прочитан_после_блокировки is True, (
        "баланс прочитан ДО блокировки — к моменту записи он уже устареет"
    )


def test_parallel_settlement_does_not_charge_twice(client, user_factory, monkeypatch):
    """Сценарий: пока один вызов читает баланс, второй успевает пройти целиком.

    Принудительное чередование вместо настоящих потоков — так проверка детерминированна
    и не зависит от того, какую СУБД подставили тестам.
    """
    водитель, _ = _водитель_с_долгом(user_factory, "ГонкаСценарий", 30_000, 20_000)
    настоящий = debt_mod.driver_balance
    сработало = {"раз": False}

    def баланс_с_вклиниванием(session, driver_id):
        значение = настоящий(session, driver_id)
        if not сработало["раз"]:
            сработало["раз"] = True
            with Session(engine) as чужая:      # параллельный запрос успевает целиком
                debt_mod.settle_debt_from_wallet(чужая, driver_id)
        return значение

    monkeypatch.setattr(debt_mod, "driver_balance", баланс_с_вклиниванием)
    with Session(engine) as s:
        debt_mod.settle_debt_from_wallet(s, водитель["id"])

    with Session(engine) as s:
        баланс = ledger.driver_balance(s, водитель["id"])
        списаний = [e for e in s.exec(
            select(LedgerEntry).where(LedgerEntry.driver_id == водитель["id"],
                                      LedgerEntry.kind == LedgerKind.fee)
        ).all()]

    assert баланс == 10_000, (
        f"в кошельке {баланс / 100:g} ₽ вместо 100 ₽: долг 200 ₽ списали дважды с 300 ₽"
    )
    assert sum(-e.amount_kop for e in списаний) == 20_000, (
        f"списаний на {sum(-e.amount_kop for e in списаний) / 100:g} ₽ при долге 200 ₽"
    )


def test_debt_is_closed_exactly_once(client, user_factory, monkeypatch):
    """И сам долг закрыт один раз: повторный зачёт не должен трогать уже оплаченный."""
    водитель, oid = _водитель_с_долгом(user_factory, "ГонкаДолг", 30_000, 20_000)

    with Session(engine) as s:
        первое = debt_mod.settle_debt_from_wallet(s, водитель["id"])
        второе = debt_mod.settle_debt_from_wallet(s, водитель["id"])

    assert первое == 20_000
    assert второе == 0, "второй зачёт снова списал деньги за уже закрытый долг"
    with Session(engine) as s:
        d = s.exec(select(CommissionDebt).where(CommissionDebt.order_id == oid)).first()
        assert d.status == DebtStatus.paid


def test_settlement_still_works_normally(client, user_factory):
    """Защита не сломана: обычный зачёт по-прежнему гасит долг и уменьшает кошелёк."""
    водитель, _ = _водитель_с_долгом(user_factory, "ГонкаОбычный", 28_140, 1_860)

    with Session(engine) as s:
        было = ledger.driver_balance(s, водитель["id"])
        снято = debt_mod.settle_debt_from_wallet(s, водитель["id"])
        стало = ledger.driver_balance(s, водитель["id"])

    assert снято == 1_860
    assert было - стало == 1_860


def test_not_enough_money_changes_nothing(client, user_factory):
    """На старейший долг не хватило — не гасим ничего и денег не трогаем."""
    водитель, _ = _водитель_с_долгом(user_factory, "ГонкаМало", 1_000, 20_000)

    with Session(engine) as s:
        снято = debt_mod.settle_debt_from_wallet(s, водитель["id"])
        баланс = ledger.driver_balance(s, водитель["id"])

    assert снято == 0
    assert баланс == 1_000
