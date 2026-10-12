"""leaf-1.2b — стык с leaf-1.1. `_activate_payment` (purpose == "taxi_debt") гасил долг
водителя ПО ВРЕМЕНИ (`mark_all_paid(up_to=payment.created_at)`), а не по причинному ID-снимку
состава счёта, который лист 1.1 пишет в `Payment.tier` (`taxi_debt_snapshot_ids`) — ровно тем
же приёмом, которым `courier_commission`-ветка рядом УЖЕ пользуется (`_courier_snapshot_ids`).

Дыра времени-границы: пока счёт ЮKassa висит `pending`, ничто не мешает админу простить один
из долгов этого счёта (`/admin/debts/{id}/forgive`, routers/debt.py — ничем не защищено).
Когда банк подтверждает оплату, время-граница молча гасит «все долги до счёта» — формально
идемпотентно (прощённый долг уже `paid`, второй раз его не тронет), но деньги за него СПИСАНЫ
С КАРТЫ по счёту целиком, и в кошелёк водителя разница не возвращается.

Правка: при наличии снимка — один атомарный `UPDATE ... WHERE status != paid RETURNING id`
(не SELECT-потом-правка: это и есть ядро защиты от гонки с /admin/debts/{id}/forgive, ни тот
путь, ни этот не берут общий лок на строку долга). ID снимка, которых в `closed_ids` не
оказалось (статус уже был `paid` ДО этого UPDATE — чем бы он ни был вызван), получают возврат
в кошелёк через существующий `debt.refund_commission_to_wallet` (идемпотентен по
`refund:order:N`, не коммитит сам).

R1 — обычный путь: снимок без сюрпризов гасит ровно свои ID, кошелёк не трогается.
R2 — один долг снимка прощён админом, пока счёт висел → остальные закрыты, разница в кошельке,
     прощённый долг и его note НЕ перезаписаны, платёж succeeded.
R3 — долг, начисленный ПОСЛЕ создания платежа (не в снимке), остаётся unpaid.
R4 — повреждённый маркер снимка → RuntimeError, ни один долг не тронут, платёж не succeeded.
R5 — платёж без маркера (исторический формат) → старое поведение по времени.
R6 — повторная активация (вебхук/поллинг дважды) не возвращает деньги дважды и не гасит дважды.
R7 (PostgreSQL) — НАСТОЯЩАЯ гонка: админ держит лок на строке долга (flush, ещё не commit) в
     момент, когда атомарный UPDATE вебхука пытается её тронуть — подтверждено `pg_blocking_pids`.
"""
from sqlmodel import Session, select

from app import debt as debt_mod
from app.db import engine
from app.models import CommissionDebt, DebtStatus, InstantOrder, InstantOrderStatus, LedgerEntry, LedgerKind, Payment, UserRole
from app.routers.payments import _activate_payment
from app.timeutil import utcnow


def _driver_with_debts(user_factory, name: str, amounts_kop: list[int]):
    driver = user_factory(name, role=UserRole.driver)
    passenger = user_factory(name + "Pass")
    ids = []
    with Session(engine) as s:
        for amt in amounts_kop:
            o = InstantOrder(passenger_id=passenger["id"], driver_id=driver["id"],
                             from_lat=52.5, from_lng=58.3, to_lat=52.7, to_lng=58.6,
                             status=InstantOrderStatus.done, price_estimate=200, price_final=200,
                             paid=True, done_at=utcnow())
            s.add(o)
            s.commit()
            s.refresh(o)
            d = CommissionDebt(driver_id=driver["id"], order_id=o.id, amount_kop=amt,
                               week="2026-W40", status=DebtStatus.unpaid, created_at=utcnow())
            s.add(d)
            s.commit()
            s.refresh(d)
            ids.append(d.id)
    return driver, ids


def _snapshot_payment(driver_id: int, amount_kop: int, *, tier: str) -> int:
    with Session(engine) as s:
        p = Payment(user_id=driver_id, purpose="taxi_debt", amount_kop=amount_kop, method="yookassa",
                   provider_id="qa-l12b-taxidebt", status="pending", tier=tier)
        s.add(p); s.commit(); s.refresh(p)
        return p.id


def _activate(payment_id: int) -> Payment:
    with Session(engine) as session:
        payment = session.get(Payment, payment_id)
        _activate_payment(session, payment)
        session.refresh(payment)
        return payment


def _debt(debt_id: int) -> CommissionDebt:
    with Session(engine) as s:
        d = s.get(CommissionDebt, debt_id)
        s.expunge(d)
        return d


def _wallet_balance(driver_id: int) -> int:
    with Session(engine) as s:
        return debt_mod.driver_balance(s, driver_id)


def _ledger_adj_entries(driver_id: int) -> list[LedgerEntry]:
    with Session(engine) as s:
        return list(s.exec(
            select(LedgerEntry).where(LedgerEntry.driver_id == driver_id, LedgerEntry.kind == LedgerKind.adj)
        ).all())


def test_snapshot_closes_exactly_its_own_debts(client, user_factory):
    drv, (d1, d2) = _driver_with_debts(user_factory, "L12bOK", [10_000, 15_000])
    tier = debt_mod.make_taxi_debt_snapshot_tier((d1, d2))
    pay_id = _snapshot_payment(drv["id"], 25_000, tier=tier)

    payment = _activate(pay_id)

    assert payment.status == "succeeded"
    assert _debt(d1).status == DebtStatus.paid
    assert _debt(d2).status == DebtStatus.paid
    assert _wallet_balance(drv["id"]) == 0, "ничего не прощали — компенсации быть не должно"


def test_debt_forgiven_mid_flight_refunds_the_difference_and_closes_the_rest(client, user_factory):
    """R2 — главный сценарий: админ простил один долг снимка, пока счёт висел у банка."""
    drv, (d1, d2) = _driver_with_debts(user_factory, "L12bForgive", [10_000, 15_000])
    tier = debt_mod.make_taxi_debt_snapshot_tier((d1, d2))
    pay_id = _snapshot_payment(drv["id"], 25_000, tier=tier)

    with Session(engine) as s:
        debt = s.get(CommissionDebt, d2)
        debt.status = DebtStatus.paid
        debt.confirmed_at = utcnow()
        debt.note = "Списан админом: тест"
        s.add(debt); s.commit()

    payment = _activate(pay_id)

    assert payment.status == "succeeded"
    assert _debt(d1).status == DebtStatus.paid, "единственный реально открытый долг снимка обязан закрыться"
    forgiven = _debt(d2)
    assert forgiven.note == "Списан админом: тест", "прощённый долг — не трогаем второй раз, не затираем note"

    assert _wallet_balance(drv["id"]) == 15_000, "разница (сумма прощённого d2) обязана вернуться в кошелёк"
    entries = _ledger_adj_entries(drv["id"])
    assert len(entries) == 1
    assert entries[0].amount_kop == 15_000
    assert entries[0].order_id == forgiven.order_id


def test_debt_accrued_after_payment_creation_stays_untouched(client, user_factory):
    """R3 — долг вне снимка (начислен ПОСЛЕ выставления счёта) не гасится этим платежом."""
    drv, (d1,) = _driver_with_debts(user_factory, "L12bLater", [10_000])
    tier = debt_mod.make_taxi_debt_snapshot_tier((d1,))
    pay_id = _snapshot_payment(drv["id"], 10_000, tier=tier)

    # Новый долг начислился ПОСЛЕ того, как счёт уже выставлен (снимок его не видел).
    _, (d2,) = _driver_with_debts(user_factory, "L12bLaterDonor", [5_000])
    with Session(engine) as s:
        later = s.get(CommissionDebt, d2)
        later.driver_id = drv["id"]  # тот же водитель, другой заказ — проще, чем новый фактори
        s.add(later); s.commit()

    payment = _activate(pay_id)

    assert payment.status == "succeeded"
    assert _debt(d1).status == DebtStatus.paid
    assert _debt(d2).status == DebtStatus.unpaid, "долг вне снимка не должен был тронут этим счётом"
    assert _wallet_balance(drv["id"]) == 0


def test_corrupted_snapshot_marker_fails_closed_touches_nothing(client, user_factory):
    """R4 — fail-closed, зеркало courier: повреждённый маркер не гасит и не возвращает вслепую."""
    drv, (d1,) = _driver_with_debts(user_factory, "L12bBroken", [10_000])
    broken_tier = debt_mod.TAXI_DEBT_SNAPSHOT_PREFIX + "not-a-number"
    pay_id = _snapshot_payment(drv["id"], 10_000, tier=broken_tier)

    raised = False
    try:
        _activate(pay_id)
    except RuntimeError:
        raised = True

    assert raised, "повреждённый снимок обязан бросить исключение"
    assert _debt(d1).status == DebtStatus.unpaid
    assert _wallet_balance(drv["id"]) == 0
    with Session(engine) as s:
        assert s.get(Payment, pay_id).status == "pending"


def test_payment_without_snapshot_marker_uses_time_cutoff(client, user_factory):
    """R5 — регресс-страховка: исторические платежи (tier="") гасят долг по времени, как раньше."""
    drv, (d1,) = _driver_with_debts(user_factory, "L12bLegacy", [10_000])
    pay_id = _snapshot_payment(drv["id"], 10_000, tier="")

    payment = _activate(pay_id)

    assert payment.status == "succeeded"
    assert _debt(d1).status == DebtStatus.paid
    assert _wallet_balance(drv["id"]) == 0


def test_repeat_activation_does_not_double_refund_or_double_close(client, user_factory):
    """R6 — повтор вебхука/поллинга на уже succeeded платёж не плодит возврат/гашение. Внешний
    гейт `_activate_payment` (status != pending → return) уже это обеспечивает — тест закрепляет,
    что он действительно накрывает и НОВУЮ ветку по снимку, а не только старые purpose."""
    drv, (d1, d2) = _driver_with_debts(user_factory, "L12bRepeat", [10_000, 15_000])
    tier = debt_mod.make_taxi_debt_snapshot_tier((d1, d2))
    pay_id = _snapshot_payment(drv["id"], 25_000, tier=tier)

    with Session(engine) as s:
        debt = s.get(CommissionDebt, d2)
        debt.status = DebtStatus.paid
        debt.confirmed_at = utcnow()
        debt.note = "Списан админом: тест"
        s.add(debt); s.commit()

    _activate(pay_id)
    balance_after_first = _wallet_balance(drv["id"])
    entries_after_first = len(_ledger_adj_entries(drv["id"]))

    _activate(pay_id)  # вебхук/поллинг повторили вызов на уже succeeded платёж
    _activate(pay_id)

    assert _wallet_balance(drv["id"]) == balance_after_first == 15_000
    assert len(_ledger_adj_entries(drv["id"])) == entries_after_first == 1
    assert _debt(d1).status == DebtStatus.paid
