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
from datetime import date, datetime, timedelta
from typing import Optional

from sqlalchemy.exc import IntegrityError
from sqlmodel import Session, select

from .config import settings
from .ledger import fee_kop_for
from .models import (
    CommissionDebt, DebtStatus, InstantOrder, InstantOrderStatus,
    TaxiApplication, TaxiApplicationStatus,
)
from .timeutil import utcnow


def _week_key(dt) -> str:
    """ISO-неделя начисления, напр. '2026-W28' — по ней группируем долг для оплаты."""
    y, w, _ = dt.isocalendar()
    return f"{y}-W{w:02d}"


def _launch_promo_percent(session: Session, driver_id: int, now) -> Optional[float]:
    """Промо запуска «первым водителям — 0%»: если заявка таксиста одобрена ДО даты
    launch_promo_until (ISO, из конфига) и с одобрения прошло ≤ launch_promo_days — водитель
    платит launch_promo_percent. Пустая дата → промо выключено (None = промо не действует)."""
    raw = settings.launch_promo_until.strip()
    if not raw:
        return None
    try:
        promo_until = date.fromisoformat(raw)
    except ValueError:
        return None                     # кривая дата в конфиге → промо не применяем, не падаем
    app = session.exec(select(TaxiApplication).where(
        TaxiApplication.user_id == driver_id,
        TaxiApplication.status == TaxiApplicationStatus.approved,
    )).first()
    if app is None:
        return None
    approved_at = app.reviewed_at or app.created_at
    if approved_at is None or approved_at.date() > promo_until:
        return None                     # одобрен после окна набора — промо не для него
    if now > approved_at + timedelta(days=settings.launch_promo_days):
        return None                     # промо-период истёк — дальше обычная лесенка
    return settings.launch_promo_percent


def driver_fee_percent(session: Session, driver_id: int, now=None) -> float:
    """Процент комиссии для водителя (лесенка 3% → 5% → 8%, §5 Деньги).

    Стаж = дни с ПЕРВОГО его завершённого (done) быстрого заказа:
    ≤ fee_tier1_days → fee_tier1_percent; ≤ fee_tier2_days → fee_tier2_percent;
    дальше — service_fee_percent (навсегда). Промо запуска (одобрен до launch_promo_until)
    перекрывает лесенку на первые launch_promo_days дней."""
    now = now or utcnow()
    promo = _launch_promo_percent(session, driver_id, now)
    if promo is not None:
        return promo
    first_done = session.exec(
        select(InstantOrder.done_at).where(
            InstantOrder.driver_id == driver_id,
            InstantOrder.status == InstantOrderStatus.done,
            InstantOrder.done_at.is_not(None),                     # noqa: E711
        ).order_by(InstantOrder.done_at)
    ).first()
    if first_done is None:
        return settings.fee_tier1_percent      # первый заказ — стаж 0 дней
    days = (now - first_done).days
    if days <= settings.fee_tier1_days:
        return settings.fee_tier1_percent
    if days <= settings.fee_tier2_days:
        return settings.fee_tier2_percent
    return settings.service_fee_percent


def driver_dashboard(session: Session, driver_id: int, now: Optional[datetime] = None) -> dict:
    """Данные дашборда таксиста для кабинета: заработок и заказы ЗА СЕГОДНЯ + текущая ступень
    комиссии. Лесенка комиссии — по СТАЖУ (дни с первого done-заказа), не по деньгам:
    первый месяц дешевле, потом растёт. Показываем честно, когда ступень поднимется.

    earnings_today — сумма фактических цен (price_final, ₽) завершённых такси-заказов за
    местный день; fee_percent — сколько платформа берёт сейчас (с учётом промо запуска)."""
    now = now or utcnow()
    tz = timedelta(hours=settings.local_tz_offset_hours)
    ln = now + tz                                        # местное «сейчас»
    start_utc = datetime(ln.year, ln.month, ln.day) - tz  # местная полночь → обратно в UTC
    end_utc = start_utc + timedelta(days=1)
    done_today = session.exec(
        select(InstantOrder).where(
            InstantOrder.driver_id == driver_id,
            InstantOrder.status == InstantOrderStatus.done,
            InstantOrder.done_at >= start_utc,
            InstantOrder.done_at < end_utc,
        )
    ).all()
    earnings = sum(int(o.price_final if o.price_final is not None else o.price_estimate) for o in done_today)

    percent = driver_fee_percent(session, driver_id, now)
    first_done = session.exec(
        select(InstantOrder.done_at).where(
            InstantOrder.driver_id == driver_id,
            InstantOrder.status == InstantOrderStatus.done,
            InstantOrder.done_at.is_not(None),
        ).order_by(InstantOrder.done_at.asc()).limit(1)
    ).first()
    tenure_days = (now - first_done).days if first_done else 0
    if tenure_days <= settings.fee_tier1_days:
        next_percent, days_to_next = settings.fee_tier2_percent, settings.fee_tier1_days - tenure_days
    elif tenure_days <= settings.fee_tier2_days:
        next_percent, days_to_next = settings.service_fee_percent, settings.fee_tier2_days - tenure_days
    else:
        next_percent, days_to_next = None, None       # верхняя ступень — дальше не растёт
    return {
        "earnings_today": earnings,
        "orders_today": len(done_today),
        "fee_percent": percent,
        "tenure_days": tenure_days,
        "fee_tiers": [settings.fee_tier1_percent, settings.fee_tier2_percent, settings.service_fee_percent],
        "fee_tier_days": [settings.fee_tier1_days, settings.fee_tier2_days],
        "fee_next_percent": next_percent,
        "fee_days_to_next": days_to_next,
    }


def order_commission_kop(order: InstantOrder, percent: Optional[float] = None) -> int:
    """Комиссия платформы по завершённому такси-заказу, копейки.
    База = финальная цена (или оценка) в ₽ → копейки; процент — лесенка по стажу
    (driver_fee_percent) либо service_fee_percent, если процент не передан."""
    price_rub = int(order.price_final if order.price_final is not None else order.price_estimate)
    if price_rub <= 0:
        return 0
    return fee_kop_for(price_rub * 100, percent)


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
    now = utcnow()
    percent = driver_fee_percent(session, order.driver_id, now)   # лесенка 3/5/8 + промо запуска
    amount = order_commission_kop(order, percent)
    if amount <= 0:
        return None                           # нулевая комиссия (промо 0% / грошовый заказ) — долг не заводим
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
    try:
        session.commit()
    except IntegrityError:
        # Гонка: параллельный «done» успел вставить долг по этому order_id первым (UNIQUE order_id).
        # Не задваиваем — откатываемся и возвращаем уже существующую запись.
        session.rollback()
        return session.exec(
            select(CommissionDebt).where(CommissionDebt.order_id == order.id)
        ).first()
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


def mark_all_paid(session: Session, driver_id: int, up_to: Optional[datetime] = None) -> int:
    """Погасить долг водителя (unpaid + pending) → paid. Используется при оплате картой
    (ЮKassa): подтверждение приходит вебхуком, деньги уже у платформы, админ не нужен.
    Идемпотентно (уже paid не трогаем). Возврат: погашенная сумма (копейки).

    up_to (граница снапшота): гасим только долг, начисленный ДО момента создания платежа
    (created_at <= up_to). Иначе долг, накопленный в окне между «жму оплатить» и подтверждением,
    погасился бы бесплатно. None → без границы (весь долг)."""
    now = utcnow()
    conds = [
        CommissionDebt.driver_id == driver_id,
        CommissionDebt.status != DebtStatus.paid,
    ]
    if up_to is not None:
        conds.append(CommissionDebt.created_at <= up_to)
    rows = session.exec(select(CommissionDebt).where(*conds)).all()
    total = 0
    for d in rows:
        d.status = DebtStatus.paid
        d.confirmed_at = now
        session.add(d)
        total += d.amount_kop
    if rows:
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
