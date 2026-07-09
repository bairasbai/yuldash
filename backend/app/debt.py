"""Долг по комиссии за ТАКСИ (Модель А «на доверии», Фаза 3).

Суть: за завершённый быстрый заказ (instant) водитель получает деньги напрямую
(нал / прямой СБП), а комиссию 8% ДОЛЖЕН платформе. Раз в неделю водитель сам переводит
долг Александру по СБП и жмёт «Я оплатил» (unpaid → pending); Александр (админ) подтверждает
(pending → paid) или отклоняет (pending → unpaid). Просроченный неоплаченный долг (или сумма
неоплаченного > порога) → режим ТАКСИ блокируется, пока не погасит.

ВАЖНО: блокируется ТОЛЬКО такси (instant). ПОПУТКА (плановые Ride/Booking) — отдельный поток,
её долг по комиссии НЕ трогает.

Принципы (деньги — критично):
  • Только целые копейки (int amount_kop). Комиссия считается тем же fee_kop_for, что и ledger
    (Decimal ROUND_HALF_UP — без float-дрейфа).
  • Начисление идемпотентно: на один заказ — не больше одной записи долга (гейт по order_id).
  • Долг — append-запись на заказ; статус меняем, историю сумм не переписываем.

Приватность: суммы не логируем с привязкой к персоне — только id.
"""
from datetime import timedelta
from typing import Optional

from sqlmodel import Session, select

from .config import settings
from .ledger import fee_kop_for
from .models import CommissionDebt, DebtStatus, InstantOrder
from .timeutil import utcnow


def _week_key(dt) -> str:
    """ISO-неделя начисления, напр. '2026-W28' — по ней группируем долг для оплаты."""
    y, w, _ = dt.isocalendar()
    return f"{y}-W{w:02d}"


def order_commission_kop(order: InstantOrder) -> int:
    """Комиссия платформы по завершённому такси-заказу, копейки.
    База = финальная цена (или оценка) в ₽ → копейки; процент — service_fee_percent."""
    price_rub = int(order.price_final if order.price_final is not None else order.price_estimate)
    if price_rub <= 0:
        return 0
    return fee_kop_for(price_rub * 100)


def accrue_for_order(session: Session, order: InstantOrder) -> Optional[CommissionDebt]:
    """Начислить долг по комиссии за завершённый такси-заказ. Идемпотентно.

    Вызывать ПОСЛЕ перехода в done. На один order_id заводим не больше одной записи долга —
    повторный тап «done» (идемпотентный переход) не задваивает долг. Нулевая комиссия
    (бесплатный/грошовый заказ) долг не создаёт."""
    if order.driver_id is None:
        return None
    existing = session.exec(
        select(CommissionDebt).where(CommissionDebt.order_id == order.id)
    ).first()
    if existing:
        return existing                       # уже начислено — не задваиваем
    amount = order_commission_kop(order)
    if amount <= 0:
        return None
    now = utcnow()
    debt = CommissionDebt(
        driver_id=order.driver_id,
        order_id=order.id,
        amount_kop=amount,
        week=_week_key(now),
        status=DebtStatus.unpaid,
        created_at=now,
        due_at=now + timedelta(days=settings.debt_due_days),
    )
    session.add(debt)
    session.commit()
    session.refresh(debt)
    return debt


def _unpaid(session: Session, driver_id: int) -> list[CommissionDebt]:
    return session.exec(
        select(CommissionDebt).where(
            CommissionDebt.driver_id == driver_id,
            CommissionDebt.status == DebtStatus.unpaid,
        )
    ).all()


def _pending(session: Session, driver_id: int) -> list[CommissionDebt]:
    return session.exec(
        select(CommissionDebt).where(
            CommissionDebt.driver_id == driver_id,
            CommissionDebt.status == DebtStatus.pending,
        )
    ).all()


def taxi_block_reason(session: Session, driver_id: int, now=None) -> Optional[str]:
    """Причина блокировки такси для водителя или None (можно возить).

    Блокируем, если есть ПРОСРОЧЕННЫЙ неоплаченный долг (due_at < now) ИЛИ сумма неоплаченного
    долга превысила порог debt_block_threshold_kop. Долг в статусе pending (водитель заявил
    оплату, ждём админа) НЕ блокирует — работаем «на доверии». Ничего не должен → None."""
    now = now or utcnow()
    unpaid = _unpaid(session, driver_id)
    if not unpaid:
        return None
    if any(d.due_at is not None and d.due_at < now for d in unpaid):
        return "overdue"
    if sum(d.amount_kop for d in unpaid) > settings.debt_block_threshold_kop:
        return "over_threshold"
    return None


# Понятная ошибка блокировки такси (RU — серверная строка; UI локализует через appText).
TAXI_BLOCKED_MSG = "Оплати долг сервису, чтобы возить такси"


def debt_summary(session: Session, driver_id: int) -> dict:
    """Сводка долга для кабинета водителя: сколько должен, до какой даты, реквизиты СБП,
    блокировка. Разбивка по неделям — для наглядности."""
    now = utcnow()
    unpaid = _unpaid(session, driver_id)
    pending = _pending(session, driver_id)
    unpaid_kop = sum(d.amount_kop for d in unpaid)
    pending_kop = sum(d.amount_kop for d in pending)
    due_dates = [d.due_at for d in unpaid if d.due_at is not None]
    earliest_due = min(due_dates) if due_dates else None
    reason = taxi_block_reason(session, driver_id, now)

    # Разбивка по неделям (unpaid + pending) — свежие сверху.
    by_week: dict[str, dict] = {}
    for d in unpaid + pending:
        w = by_week.setdefault(d.week, {"week": d.week, "amount_kop": 0, "status": d.status.value})
        w["amount_kop"] += d.amount_kop
        if d.status == DebtStatus.pending:
            w["status"] = DebtStatus.pending.value   # ждёт подтверждения — важнее показать
    weeks = sorted(by_week.values(), key=lambda x: x["week"], reverse=True)

    return {
        "unpaid_kop": unpaid_kop,
        "pending_kop": pending_kop,
        "due_at": earliest_due.isoformat() if earliest_due else None,
        "overdue": reason == "overdue",
        "blocked": reason is not None,
        "block_reason": reason,
        "threshold_kop": settings.debt_block_threshold_kop,
        "sbp": {"phone": settings.owner_sbp_phone, "name": settings.owner_sbp_name},
        "weeks": weeks,
    }


def declare_paid(session: Session, driver_id: int) -> int:
    """Водитель заявил оплату: все его unpaid-долги → pending (ждут подтверждения админом).
    Возврат: сумма переведённого в pending (копейки). Ничего не должен → 0."""
    now = utcnow()
    unpaid = _unpaid(session, driver_id)
    total = 0
    for d in unpaid:
        d.status = DebtStatus.pending
        d.paid_declared_at = now
        session.add(d)
        total += d.amount_kop
    if unpaid:
        session.commit()
    return total


def admin_confirm(session: Session, debt_id: int) -> Optional[int]:
    """Админ подтвердил перевод: ВЕСЬ pending-долг этого водителя → paid (блок снят).
    debt_id — любая запись из батча водителя (в /admin/debts группируем по водителю).
    Возврат: подтверждённая сумma (копейки) или None, если долг не найден."""
    debt = session.get(CommissionDebt, debt_id)
    if not debt:
        return None
    now = utcnow()
    pending = _pending(session, debt.driver_id)
    total = 0
    for d in pending:
        d.status = DebtStatus.paid
        d.confirmed_at = now
        session.add(d)
        total += d.amount_kop
    if pending:
        session.commit()
    return total


def admin_reject(session: Session, debt_id: int) -> Optional[int]:
    """Админ отклонил (деньги не пришли): pending-долг водителя → обратно unpaid.
    Возврат: сумма возвращённого в unpaid (копейки) или None, если долг не найден."""
    debt = session.get(CommissionDebt, debt_id)
    if not debt:
        return None
    pending = _pending(session, debt.driver_id)
    total = 0
    for d in pending:
        d.status = DebtStatus.unpaid
        d.paid_declared_at = None
        session.add(d)
        total += d.amount_kop
    if pending:
        session.commit()
    return total
