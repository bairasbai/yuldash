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
from types import SimpleNamespace

import pytest
from sqlmodel import Session

from app import debt as debt_mod
from app import ledger
from app.config import settings
from app.db import engine
from app.models import DebtStatus, InstantOrder, InstantOrderStatus, Report, UserRole
from app.timeutil import utcnow


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


def _debt(amount_kop=1000, status=DebtStatus.unpaid, declare_count=0, due_at=None, created_at=None):
    return SimpleNamespace(amount_kop=amount_kop, status=status, declare_count=declare_count,
                           due_at=due_at, created_at=created_at, paid_declared_at=None, note="")


def test_r4_abuse_outranks_everything_even_with_no_unpaid_at_all(monkeypatch):
    import datetime as dt
    now = dt.datetime(2026, 1, 10, 12, 0, 0)
    abused_pending = _debt(declare_count=settings.debt_max_declares + 1)
    assert debt_mod._reason_from(unpaid=[], pending=[abused_pending], now=now) == "declare_abuse"


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
