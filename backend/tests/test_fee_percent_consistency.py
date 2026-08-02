"""Ставка комиссии водителя фиксируется в ОДИН момент во всех денежных путях.

Путей два, и раньше они расходились:
  * долг Модели А (debt.accrue_for_order) — ставка на момент СОЗДАНИЯ заказа;
  * онлайн-оплата (ledger.settle_instant_order) — ставка была на момент ЗАВЕРШЕНИЯ (done_at).

Пока заказ короткий, оба момента дают одну ступень, и расхождения не видно. Но заказ,
пересёкший границу ступени (или конец промо), давал при оплате картой один процент,
а в долге — другой: водителю в оффере обещали net по одной ставке, а удержали по другой.

Здесь фиксируем инвариант: обе ветки берут ставку на момент создания заказа.
"""
from datetime import timedelta

from sqlmodel import Session, select

from app import debt as debt_mod
from app import ledger as ledger_mod
from app.config import settings
from app.db import engine
from app.models import (InstantOrder, InstantOrderStatus, LedgerEntry, LedgerKind, UserRole)
from app.timeutil import utcnow


def _mk_order(driver_id: int, pax_id: int, *, created_at, done_at, price: int) -> int:
    """Заказ прямо в БД: нужен точный контроль created_at/done_at, через API их не задать."""
    with Session(engine) as s:
        o = InstantOrder(
            passenger_id=pax_id, driver_id=driver_id,
            from_lat=52.59, from_lng=58.31, to_lat=52.71, to_lng=58.66,
            from_text="Баймак", to_text="Сибай",
            price_estimate=price, price_final=price,
            status=InstantOrderStatus.done, created_at=created_at, done_at=done_at,
        )
        s.add(o)
        s.commit()
        s.refresh(o)
        return o.id


def test_cashless_fee_matches_debt_fee_when_order_crosses_tier_boundary(client, user_factory):
    """Заказ создан на последнем дне 1-й ступени, завершён на первом дне 2-й.

    Обе денежные ветки обязаны взять ставку на момент СОЗДАНИЯ (3%), а не завершения (5%):
    именно её водитель видел в оффере.
    """
    drv = user_factory(name="ГраницаВодитель", role=UserRole.driver)["id"]
    pax = user_factory(name="ГраницаПассажир")["id"]
    now = utcnow()
    # Стаж считается от ПЕРВОГО завершённого заказа. Ставим его так, чтобы граница ступени
    # прошла между created_at и done_at нашего заказа.
    first_done = now - timedelta(days=settings.fee_tier1_days + 2)
    _mk_order(drv, pax, created_at=first_done, done_at=first_done, price=100)

    created_at = first_done + timedelta(days=settings.fee_tier1_days)      # ещё 1-я ступень
    done_at = first_done + timedelta(days=settings.fee_tier1_days + 1)     # уже 2-я
    order_id = _mk_order(drv, pax, created_at=created_at, done_at=done_at, price=1000)

    with Session(engine) as s:
        pct_created = debt_mod.driver_fee_percent(s, drv, created_at)
        pct_done = debt_mod.driver_fee_percent(s, drv, done_at)
    assert pct_created != pct_done, "тест бессмысленен, если граница ступени не пересечена"
    assert pct_created == settings.fee_tier1_percent

    with Session(engine) as s:
        debt = debt_mod.accrue_for_order(s, s.get(InstantOrder, order_id))
        s.commit()
        debt_amount = debt.amount_kop
    assert debt_amount == debt_mod.order_commission_kop(
        _snapshot(order_id), settings.fee_tier1_percent)

    with Session(engine) as s:
        assert ledger_mod.settle_instant_order(s, order_id, "card", 100_000) == "settled"

    with Session(engine) as s:
        fee = s.exec(
            select(LedgerEntry).where(LedgerEntry.order_id == order_id,
                                      LedgerEntry.kind == LedgerKind.fee)
        ).first()
    assert fee is not None, "безналичная оплата обязана удержать комиссию"
    # Комиссия в ledger считается от суммы платежа, долг — от цены заказа; сравниваем СТАВКУ,
    # а не рубли: важно, что обе ветки взяли один процент.
    assert f"{settings.fee_tier1_percent:g}%" in fee.note
    assert fee.amount_kop == -ledger_mod.fee_kop_for(100_000, settings.fee_tier1_percent)


def _snapshot(order_id: int) -> InstantOrder:
    with Session(engine) as s:
        return s.get(InstantOrder, order_id)
