"""leaf-1.1 · прямые (не через HTTP) проверки чистых денежных функций debt.py / ledger.py.

Дополняют уже большой существующий набор (test_debt.py, test_debt_pay_now.py, …): те гоняют
правила через HTTP end-to-end (создать заказ → закрыть → проверить ответ), здесь — напрямую
по чистым функциям, без БД и без клиента. Падение такого теста сразу показывает, какая именно
формула сломалась, не требуя сначала исключить обвязку (роутер, сессию, сериализацию ответа).

R1 — order_commission_kop: база = цена МИНУС компенсация, затем процент; price<=0 → 0.
R2 — fee_percent_for_trips: границы лесенки (0..tier1-1 → tier1; tier1..tier2-1 → tier2; дальше — база).
R3 — fee_kop_for: отрицательная/нулевая сумма и нулевой/отрицательный процент → 0, без исключений.
R4 — _reason_from: приоритет причин блокировки — abuse > stale > overdue > over_threshold > None,
     ПОПУТКА (эта функция вообще не знает о ней) тут ни при чём — она просто решает по уже
     переданным unpaid/pending долгам ТАКСИ; кто какие долги передаёт — решение вызывающего кода.
R5 — unpaid_confirmed_order_ids считает заказ «не заплатили» ТОЛЬКО когда разбор подтвердил
     жалобу (Report.status == "resolved"). Голое слово водителя (жалоба создана, но ещё не
     разобрана — status == "new") не должно втихую выкидывать поездку из заработка: это снимет
     с водителя комиссию и покажет «чистыми 0» за поездку, которую ещё никто не проверил.
"""
import datetime as dt
from types import SimpleNamespace

import pytest
from sqlmodel import Session

from app import debt as debt_mod
from app import ledger
from app.config import settings
from app.db import engine
from app.models import (
    CommissionDebt, DebtStatus, InstantOrder, InstantOrderStatus, LedgerEntry, LedgerKind, Report,
    UserRole,
)
from app.timeutil import utcnow


def ledger_promo_entry(driver_id: int, order_id: int, amount_kop: int) -> LedgerEntry:
    """Запись компенсации промокода — ТЕМ ЖЕ ключом, что считает debt_mod._promo_comp_kop."""
    return LedgerEntry(driver_id=driver_id, order_id=order_id, kind=LedgerKind.adj,
                       amount_kop=amount_kop, ext_id=ledger.promo_comp_ext_id(order_id),
                       note="тест: компенсация промокода")


def _order(price_final=None, price_estimate=0, **comp):
    return SimpleNamespace(price_final=price_final, price_estimate=price_estimate, **{
        "pickup_fee_kop": comp.get("pickup_fee_kop", 0),
        "options_fee_kop": comp.get("options_fee_kop", 0),
        "weather_fee_kop": comp.get("weather_fee_kop", 0),
    })


def test_r1_commission_base_excludes_compensation():
    # Цена 500 ₽, из них 100 ₽ (10000 коп) — компенсация водителю за дальнюю подачу.
    # База = 500 - 100 = 400 ₽ → при 15% комиссия = 60 ₽ = 6000 коп, а НЕ 15% от 500.
    order = _order(price_final=500, pickup_fee_kop=10000)
    assert debt_mod.order_commission_kop(order, percent=15.0) == 6000


def test_r1_compensation_covering_the_whole_price_means_zero_commission():
    order = _order(price_final=200, pickup_fee_kop=20000, weather_fee_kop=5000)  # комп. ₽ ≥ цены
    assert debt_mod.order_commission_kop(order, percent=15.0) == 0


def test_r1_non_positive_price_is_zero_commission_not_negative():
    assert debt_mod.order_commission_kop(_order(price_final=0), percent=15.0) == 0
    assert debt_mod.order_commission_kop(_order(price_final=-50), percent=15.0) == 0


@pytest.mark.parametrize("trips,expected_attr", [
    (0, "fee_tier1_percent"),
    (-1, "fee_tier1_percent"),           # защита от мусорного отрицательного числа поездок
])
def test_r2_ladder_tier1_at_the_start(trips, expected_attr):
    assert debt_mod.fee_percent_for_trips(trips) == getattr(settings, expected_attr)


def test_r2_ladder_boundary_is_exclusive_at_tier1():
    """Ровно fee_tier1_trips поездок — уже СЛЕДУЮЩАЯ ступень (строго <, не <=)."""
    at_boundary = debt_mod.fee_percent_for_trips(settings.fee_tier1_trips)
    just_before = debt_mod.fee_percent_for_trips(settings.fee_tier1_trips - 1)
    assert just_before == settings.fee_tier1_percent
    assert at_boundary == settings.fee_tier2_percent


def test_r2_ladder_boundary_is_exclusive_at_tier2():
    at_boundary = debt_mod.fee_percent_for_trips(settings.fee_tier2_trips)
    just_before = debt_mod.fee_percent_for_trips(settings.fee_tier2_trips - 1)
    assert just_before == settings.fee_tier2_percent
    assert at_boundary == settings.service_fee_percent


def test_r2_far_beyond_tier2_stays_on_the_base_rate():
    assert debt_mod.fee_percent_for_trips(settings.fee_tier2_trips + 10_000) == settings.service_fee_percent


def test_r3_fee_kop_for_non_positive_amount_or_percent_is_zero():
    assert ledger.fee_kop_for(-100, 15.0) == 0
    assert ledger.fee_kop_for(0, 15.0) == 0
    assert ledger.fee_kop_for(10_000, 0) == 0
    assert ledger.fee_kop_for(10_000, -5) == 0


def _debt(amount_kop=1000, status=DebtStatus.unpaid, declare_count=0, due_at=None, created_at=None,
         paid_declared_at=None):
    return SimpleNamespace(amount_kop=amount_kop, status=status, declare_count=declare_count,
                           due_at=due_at, created_at=created_at,
                           paid_declared_at=paid_declared_at, note="")


def test_r4_no_debts_at_all_means_no_block():
    import datetime as dt
    assert debt_mod._reason_from(unpaid=[], pending=[], now=dt.datetime(2026, 1, 10)) is None


def test_r4_over_threshold_without_any_overdue_row():
    import datetime as dt
    now = dt.datetime(2026, 1, 10, 12, 0, 0)
    far_future_due = now + dt.timedelta(days=30)   # не просрочен
    big = _debt(amount_kop=settings.debt_block_threshold_kop + 1, due_at=far_future_due,
               created_at=now - dt.timedelta(days=1))
    assert debt_mod._reason_from(unpaid=[big], pending=[], now=now) == "over_threshold"


# --- Исправлено по замечанию независимого ревью Opus (2026-10-02): прежний единственный тест
# «abuse важнее всего» проверял это на unpaid=[] — то есть НИЖЕ стоящих причин там просто не
# было, и «важнее» ничем не подтверждалось. Здесь — настоящий приоритет: на каждом уровне
# УСЛОВИЯ НИЖНИХ причин ТОЖЕ присутствуют, и функция обязана выбрать СТАРШУЮ.
def test_r4_priority_abuse_outranks_stale_even_when_both_are_true(monkeypatch):
    import datetime as dt
    now = dt.datetime(2026, 1, 10, 12, 0, 0)
    monkeypatch.setattr(settings, "debt_max_declares", 2, raising=False)
    abused_and_stale = _debt(declare_count=3, paid_declared_at=now - dt.timedelta(days=30))
    # Протухание (_stale_declares) тоже истинно для этой же записи — abuse обязан победить первым.
    assert debt_mod._stale_declares([abused_and_stale], now), "тест сломан: запись не протухшая"
    assert debt_mod._reason_from(unpaid=[], pending=[abused_and_stale], now=now) == "declare_abuse"


def test_r4_priority_stale_outranks_overdue_even_when_both_are_true(monkeypatch):
    import datetime as dt
    now = dt.datetime(2026, 1, 10, 12, 0, 0)
    stale_pending = _debt(declare_count=0, paid_declared_at=now - dt.timedelta(days=30))
    overdue_unpaid = _debt(amount_kop=1_000, due_at=now - dt.timedelta(days=1),
                           created_at=now - dt.timedelta(days=10))
    assert debt_mod._reason_from(unpaid=[overdue_unpaid], pending=[stale_pending], now=now) == "declare_stale"


def test_r4_priority_overdue_outranks_over_threshold_even_when_both_are_true(monkeypatch):
    import datetime as dt
    now = dt.datetime(2026, 1, 10, 12, 0, 0)
    overdue_and_big = _debt(amount_kop=settings.debt_block_threshold_kop + 1,
                            due_at=now - dt.timedelta(days=1), created_at=now - dt.timedelta(days=10))
    assert debt_mod._reason_from(unpaid=[overdue_and_big], pending=[], now=now) == "overdue"


# --- Границы порогов блокировки (найдено независимым ревью Opus, §4, п.3) --------------------
# Ровно НА границе правило обязано быть ещё МЯГКИМ (не блокировать) — строгое «больше», не «не меньше».
def test_r6_declare_abuse_boundary_is_strictly_greater_not_greater_or_equal(monkeypatch):
    monkeypatch.setattr(settings, "debt_max_declares", 2, raising=False)
    now = dt.datetime(2026, 1, 10)
    # created_at — СВЕЖИЙ (не протухший): иначе _reason_from дойдёт до _stale_declares и там
    # упадёт на сравнении None < edge — не про эту границу тест, не должен от неё зависеть.
    ровно_на_границе = _debt(declare_count=2, paid_declared_at=now, created_at=now)
    чуть_больше = _debt(declare_count=3, paid_declared_at=now, created_at=now)
    assert debt_mod._reason_from(unpaid=[], pending=[ровно_на_границе], now=now) is None, (
        "declare_count РОВНО на лимите не должен считаться злоупотреблением"
    )
    assert debt_mod._reason_from(unpaid=[], pending=[чуть_больше], now=now) == "declare_abuse"


def test_r7_declare_stale_boundary_is_strictly_less_not_less_or_equal():
    import datetime as dt
    now = dt.datetime(2026, 1, 10, 12, 0, 0)
    ровно_на_границе = _debt(paid_declared_at=now - dt.timedelta(days=debt_mod.DECLARE_TRUST_DAYS))
    чуть_старше = _debt(paid_declared_at=now - dt.timedelta(days=debt_mod.DECLARE_TRUST_DAYS, seconds=1))
    assert debt_mod._stale_declares([ровно_на_границе], now) == [], (
        "заявление РОВНО на границе срока доверия не должно считаться протухшим"
    )
    assert debt_mod._stale_declares([чуть_старше], now) == [чуть_старше]


def test_r8_over_threshold_boundary_is_strictly_greater_not_greater_or_equal():
    import datetime as dt
    now = dt.datetime(2026, 1, 10, 12, 0, 0)
    ровно_порог = _debt(amount_kop=settings.debt_block_threshold_kop, created_at=now - dt.timedelta(days=1))
    чуть_больше = _debt(amount_kop=settings.debt_block_threshold_kop + 1, created_at=now - dt.timedelta(days=1))
    assert debt_mod._reason_from(unpaid=[ровно_порог], pending=[], now=now) is None, (
        "сумма РОВНО равная порогу не должна блокировать — порог значит «свыше»"
    )
    assert debt_mod._reason_from(unpaid=[чуть_больше], pending=[], now=now) == "over_threshold"


# --- Возврат комиссии: идемпотентность БЕЗ гонки и честная пометка онлайн-оплаты (ревью §4, п.4) --
def test_r9_sequential_refund_is_idempotent_without_any_race(client, user_factory):
    """Не гонка — просто два последовательных вызова (повторный разбор той же жалобы вручную)."""
    drv = user_factory("ПоследовВозврат", role=UserRole.driver)
    pax = user_factory("ПоследовВозвратПас")
    with Session(engine) as s:
        o = InstantOrder(passenger_id=pax["id"], driver_id=drv["id"],
                         from_lat=52.5, from_lng=58.3, to_lat=52.7, to_lng=58.6,
                         status=InstantOrderStatus.done, price_estimate=150, price_final=150,
                         paid=True, done_at=utcnow())
        s.add(o)
        s.commit()
        s.refresh(o)
        oid = o.id

    with Session(engine) as s:
        первый = debt_mod.refund_commission_to_wallet(s, drv["id"], 1_500, order_id=oid)
        s.commit()
    with Session(engine) as s:
        второй = debt_mod.refund_commission_to_wallet(s, drv["id"], 1_500, order_id=oid)
        s.commit()
    assert первый is not None
    assert второй is None, "повторный возврат по тому же order_id начислил деньги ещё раз"
    with Session(engine) as s:
        assert ledger.driver_balance(s, drv["id"]) == 1_500


def test_r10_online_payment_note_is_preserved_not_overwritten_as_written_off(client, user_factory):
    """void_debt_for_order с непустым note (онлайн-оплата) обязан записать ИМЕННО его, а не
    общую пометку WRITTEN_OFF_PREFIX — от этого зависит, покажет ли fee_charged_kop комиссию
    удержанной (онлайн — да) или спишет её из расшифровки заработка (разбор жалобы — нет)."""
    drv = user_factory("ОнлайнПометка", role=UserRole.driver)
    pax = user_factory("ОнлайнПометкаПас")
    with Session(engine) as s:
        o = InstantOrder(passenger_id=pax["id"], driver_id=drv["id"],
                         from_lat=52.5, from_lng=58.3, to_lat=52.7, to_lng=58.6,
                         status=InstantOrderStatus.done, price_estimate=300, price_final=300,
                         paid=True, done_at=utcnow())
        s.add(o)
        s.commit()
        s.refresh(o)
        oid = o.id
        d = CommissionDebt(driver_id=drv["id"], order_id=oid, amount_kop=4_500,
                           week="2026-W40", status=DebtStatus.unpaid, created_at=utcnow())
        s.add(d)
        s.commit()
        did = d.id

    with Session(engine) as s:
        исход = debt_mod.void_debt_for_order(s, oid, note="Комиссия удержана при онлайн-оплате")
        s.commit()
    assert исход == "voided"
    with Session(engine) as s:
        долг = s.get(CommissionDebt, did)
        assert долг.note == "Комиссия удержана при онлайн-оплате"
        assert not долг.note.startswith(debt_mod.WRITTEN_OFF_PREFIX)
        assert debt_mod.fee_charged_kop(долг) == 4_500, "комиссия, реально удержанная онлайн, обязана быть видна"


# --- Счётчики «слова» (ревью §4, п.5) ---------------------------------------------------------
def test_r11_declare_count_increments_on_each_declare_paid_call(client, user_factory):
    drv = user_factory("СчётчикСлова", role=UserRole.driver)
    pax = user_factory("СчётчикСловаПас")
    with Session(engine) as s:
        o = InstantOrder(passenger_id=pax["id"], driver_id=drv["id"],
                         from_lat=52.5, from_lng=58.3, to_lat=52.7, to_lng=58.6,
                         status=InstantOrderStatus.done, price_estimate=300, price_final=300,
                         paid=True, done_at=utcnow())
        s.add(o)
        s.commit()
        s.refresh(o)
        d = CommissionDebt(driver_id=drv["id"], order_id=o.id, amount_kop=1_000,
                           week="2026-W40", status=DebtStatus.unpaid, created_at=utcnow())
        s.add(d)
        s.commit()
        did = d.id

    with Session(engine) as s:
        debt_mod.declare_paid(s, drv["id"])
    with Session(engine) as s:
        assert s.get(CommissionDebt, did).declare_count == 1
    with Session(engine) as s:
        debt_mod.admin_reject(s, did)
    with Session(engine) as s:
        debt_mod.declare_paid(s, drv["id"])
    with Session(engine) as s:
        assert s.get(CommissionDebt, did).declare_count == 2, "второе «Я оплатил» обязано увеличить счётчик"


def test_r12_admin_reject_clears_paid_declared_at(client, user_factory):
    drv = user_factory("СбросОтметки", role=UserRole.driver)
    pax = user_factory("СбросОтметкиПас")
    with Session(engine) as s:
        o = InstantOrder(passenger_id=pax["id"], driver_id=drv["id"],
                         from_lat=52.5, from_lng=58.3, to_lat=52.7, to_lng=58.6,
                         status=InstantOrderStatus.done, price_estimate=300, price_final=300,
                         paid=True, done_at=utcnow())
        s.add(o)
        s.commit()
        s.refresh(o)
        d = CommissionDebt(driver_id=drv["id"], order_id=o.id, amount_kop=1_000,
                           week="2026-W40", status=DebtStatus.unpaid, created_at=utcnow())
        s.add(d)
        s.commit()
        did = d.id

    with Session(engine) as s:
        debt_mod.declare_paid(s, drv["id"])
    with Session(engine) as s:
        assert s.get(CommissionDebt, did).paid_declared_at is not None
        debt_mod.admin_reject(s, did)
    with Session(engine) as s:
        долг = s.get(CommissionDebt, did)
        assert долг.status == DebtStatus.unpaid
        assert долг.paid_declared_at is None, (
            "после отклонения отметка времени заявления должна сброситься — иначе протухание "
            "«слова» (_stale_declares) будет считать его от СТАРОГО, уже отклонённого, нажатия"
        )


def test_r5_unresolved_report_does_not_count_as_confirmed_unpaid(client, user_factory):
    drv = user_factory("R5UnpaidDrv", role=UserRole.driver)
    pax = user_factory("R5UnpaidPax")
    with Session(engine) as s:
        order = InstantOrder(passenger_id=pax["id"], driver_id=drv["id"],
                             from_lat=52.5, from_lng=58.3, to_lat=52.7, to_lng=58.6,
                             status=InstantOrderStatus.done, price_estimate=400, price_final=400,
                             paid=True, done_at=utcnow())
        s.add(order)
        s.commit()
        s.refresh(order)
        oid = order.id
        # Жалоба только СОЗДАНА (статус по умолчанию "new") — админ её ещё не смотрел.
        s.add(Report(reporter_id=drv["id"], target_user_id=pax["id"], category="unpaid",
                     order_id=oid, reason="не заплатил"))
        s.commit()

    with Session(engine) as s:
        assert debt_mod.unpaid_confirmed_order_ids(s, drv["id"]) == set(), (
            "неразобранная жалоба уже вывела поездку из заработка — "
            "это решает только админ (status == resolved), не слово водителя"
        )

    with Session(engine) as s:
        from sqlmodel import select as _select
        r = s.exec(_select(Report).where(Report.order_id == oid)).one()
        r.status = "resolved"
        s.add(r)
        s.commit()

    with Session(engine) as s:
        assert debt_mod.unpaid_confirmed_order_ids(s, drv["id"]) == {oid}


# --- Арифметика «чистыми» (ревью §4, п.8) -------------------------------------------------------
def test_r13_driver_rides_net_kop_formula_is_price_minus_discount_minus_fee_plus_comp(client, user_factory):
    """driver_rides: net_kop = price_rub·100 − promo_discount_kop − fee_kop + promo_comp_kop.
    Перепутать знак у любого слагаемого — значит соврать водителю, сколько он реально получил."""
    drv = user_factory("ЧистымиПоездки", role=UserRole.driver)
    pax = user_factory("ЧистымиПоездкиПас")
    with Session(engine) as s:
        o = InstantOrder(passenger_id=pax["id"], driver_id=drv["id"],
                         from_lat=52.5, from_lng=58.3, to_lat=52.7, to_lng=58.6,
                         status=InstantOrderStatus.done, price_estimate=500, price_final=500,
                         promo_discount_kop=3_000, paid=True, done_at=utcnow())
        s.add(o)
        s.commit()
        s.refresh(o)
        oid = o.id
        s.add(CommissionDebt(driver_id=drv["id"], order_id=oid, amount_kop=9_000,
                             week="2026-W40", status=DebtStatus.paid, created_at=utcnow()))
        s.add(ledger_promo_entry(drv["id"], oid, 1_000))
        s.commit()

    with Session(engine) as s:
        rides = debt_mod.driver_rides(s, drv["id"])["rides"]
        row = next(r for r in rides if r["order_id"] == oid)

    ожидаемый_net = 500 * 100 - 3_000 - 9_000 + 1_000   # 50000 - 3000 - 9000 + 1000 = 39000
    assert row["net_kop"] == ожидаемый_net, f"net_kop={row['net_kop']}, ожидали {ожидаемый_net}"


def test_r14_driver_dashboard_net_today_formula_matches_driver_rides(client, user_factory):
    """driver_dashboard («сегодня») и driver_rides (история) считают «чистыми» ОДНОЙ формулой
    по одной и той же поездке — иначе два экрана показывают разные деньги за один день."""
    drv = user_factory("ЧистымиДашборд", role=UserRole.driver)
    pax = user_factory("ЧистымиДашбордПас")
    with Session(engine) as s:
        o = InstantOrder(passenger_id=pax["id"], driver_id=drv["id"],
                         from_lat=52.5, from_lng=58.3, to_lat=52.7, to_lng=58.6,
                         status=InstantOrderStatus.done, price_estimate=400, price_final=400,
                         promo_discount_kop=2_000, paid=True, done_at=utcnow())
        s.add(o)
        s.commit()
        s.refresh(o)
        oid = o.id
        s.add(CommissionDebt(driver_id=drv["id"], order_id=oid, amount_kop=6_000,
                             week="2026-W40", status=DebtStatus.paid, created_at=utcnow()))
        s.add(ledger_promo_entry(drv["id"], oid, 500))
        s.commit()

    with Session(engine) as s:
        dash = debt_mod.driver_dashboard(s, drv["id"])
        rides = debt_mod.driver_rides(s, drv["id"])["rides"]
        row = next(r for r in rides if r["order_id"] == oid)

    assert dash["net_today_kop"] == row["net_kop"], (
        f"дашборд за сегодня {dash['net_today_kop']} коп., история поездок {row['net_kop']} коп. "
        "— одна и та же единственная поездка дня должна совпасть"
    )


# --- Порог и окно «оплатить сразу» (ревью §4, п.9) ----------------------------------------------
def test_r15_pay_now_threshold_boundary_is_not_greater_or_equal(monkeypatch):
    """_due_at: крупная комиссия уходит в «оплатить сразу» при amount_kop >= порога — РОВНО на
    пороге это ещё «сразу» (>=, не строго >), а на копейку меньше — уже обычная неделя."""
    monkeypatch.setattr(settings, "debt_now_threshold_kop", 50_000, raising=False)
    monkeypatch.setattr(settings, "quiet_hours_to", 0, raising=False)   # тихие часы выключены — не мешают
    now = dt.datetime(2026, 1, 10, 12, 0, 0)

    ровно_порог = debt_mod._due_at(now, 50_000, pay_now_allowed=True)
    чуть_меньше = debt_mod._due_at(now, 49_999, pay_now_allowed=True)

    assert (ровно_порог - now) < dt.timedelta(days=1), "сумма РОВНО на пороге обязана идти в «оплатить сразу»"
    assert (чуть_меньше - now) >= dt.timedelta(days=1), "сумма ниже порога обязана идти в обычную неделю"


def test_r16_is_pay_now_window_boundary_is_strictly_less_than_a_day():
    """is_pay_now смотрит на ОКНО самой записи (due_at − created_at): РОВНО сутки — уже НЕ
    срочный долг (строгое <, не <=) — иначе ручной сдвиг даты на ровно 24 часа ошибочно
    считался бы «оплати сразу»."""
    created = dt.datetime(2026, 1, 1, 0, 0, 0)
    ровно_сутки = _debt(due_at=created + dt.timedelta(days=1), created_at=created)
    чуть_меньше_суток = _debt(due_at=created + dt.timedelta(days=1) - dt.timedelta(seconds=1), created_at=created)
    assert debt_mod.is_pay_now(ровно_сутки) is False
    assert debt_mod.is_pay_now(чуть_меньше_суток) is True


# --- Права: _unpaid/_pending фильтруют СТРОГО по своему driver_id (ревью §4, п.10, R14) ----------
def test_r17_unpaid_never_returns_another_drivers_debt(client, user_factory):
    свой = user_factory("СвойДолгIDOR", role=UserRole.driver)
    чужой = user_factory("ЧужойДолгIDOR", role=UserRole.driver)
    with Session(engine) as s:
        s.add(CommissionDebt(driver_id=чужой["id"], amount_kop=50_000, week="2026-W40",
                             status=DebtStatus.unpaid, created_at=utcnow()))
        s.commit()

    with Session(engine) as s:
        свои_долги = debt_mod._unpaid(s, свой["id"])

    assert свои_долги == [], "_unpaid вернул долг ДРУГОГО водителя"
