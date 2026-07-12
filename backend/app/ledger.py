"""Деньги v1 (Фаза 3, D3) — ledger (кошелёк водителя) + комиссия + сверка.

Принципы (деньги — критично):
  • Только целые копейки (int amount_kop). Никаких float для сумм — округление комиссии
    через Decimal ROUND_HALF_UP (предсказуемо, без дрейфа float).
  • Ledger APPEND-ONLY: историю денег НЕ редактируем и НЕ удаляем. Баланс = SUM(amount_kop).
    Ошиблись — добавляем корректирующую запись kind=adj, а не правим старую.
  • Оплата поездки начисляет водителю ДВЕ записи: earn (+вся сумма) и fee (−комиссия).
    Баланс водителя за поездку = earn − fee (нетто ему причитается).
  • Наличные (method=cash) — деньги идут МИМО нас: помечаем заказ оплаченным, но ledger
    НЕ двигаем (комиссии/начисления через платформу нет).
  • Идемпотентность: начисление за заказ/бронь выполняется РОВНО один раз — под row-lock
    по строке заказа/брони, гейт по флагу paid. Повторный webhook не задваивает ledger.

Приватность: суммы не логируем с привязкой к персоне (телефон/имя) — только id.
"""
from decimal import ROUND_HALF_UP, Decimal
from typing import Optional

from sqlalchemy import func
from sqlmodel import Session, select

from .config import settings
from .models import Booking, InstantOrder, LedgerEntry, LedgerKind
from .timeutil import utcnow

# Способы оплаты, при которых деньги идут ЧЕРЕЗ нас (начисляем водителю через ledger).
# cash — мимо нас (ledger не трогаем).
_CASHLESS = ("card", "sbp", "yookassa")


def fee_kop_for(amount_kop: int, percent: Optional[float] = None) -> int:
    """Комиссия сервиса в копейках. Детерминированно, ROUND_HALF_UP, всегда int.

    Пример: 15 000 коп × 15% = 2 250 коп. Считаем через Decimal, чтобы не ловить дрейф
    float на «некруглых» процентах (напр. 12.5%)."""
    if percent is None:
        percent = settings.service_fee_percent
    if amount_kop <= 0 or percent <= 0:
        return 0
    fee = (Decimal(amount_kop) * Decimal(str(percent)) / Decimal(100)).quantize(
        Decimal(1), rounding=ROUND_HALF_UP
    )
    return int(fee)


def driver_balance(session: Session, driver_id: int) -> int:
    """Баланс водителя в копейках = SUM(amount_kop) по всем его записям ledger.
    Никакого «изменяемого баланса» — всегда пересчёт из append-only истории."""
    # M5: считаем сумму в БД (SQL SUM), не тянем весь append-only ledger водителя в память.
    total = session.exec(
        select(func.coalesce(func.sum(LedgerEntry.amount_kop), 0)).where(LedgerEntry.driver_id == driver_id)
    ).one()
    return int(total or 0)


def ledger_entries(session: Session, driver_id: int, limit: int = 100) -> list[LedgerEntry]:
    """Записи ledger водителя (свежие сверху) — для экрана кошелька/истории."""
    return session.exec(
        select(LedgerEntry).where(LedgerEntry.driver_id == driver_id)
        .order_by(LedgerEntry.id.desc()).limit(max(1, min(limit, 500)))
    ).all()


def _post_earn_and_fee(session: Session, driver_id: int, amount_kop: int, *,
                       order_id: Optional[int] = None, booking_id: Optional[int] = None,
                       note: str = "") -> None:
    """Добавить в ledger начисление за поездку: earn (+вся сумма) и fee (−комиссия).
    Вызывать ТОЛЬКО под уже открытой транзакцией с залоченной строкой заказа/брони."""
    fee = fee_kop_for(amount_kop)
    session.add(LedgerEntry(
        driver_id=driver_id, order_id=order_id, booking_id=booking_id,
        kind=LedgerKind.earn, amount_kop=amount_kop, note=note,
    ))
    if fee > 0:
        session.add(LedgerEntry(
            driver_id=driver_id, order_id=order_id, booking_id=booking_id,
            kind=LedgerKind.fee, amount_kop=-fee,
            note=f"Комиссия сервиса {settings.service_fee_percent:g}%",
        ))


def settle_instant_order(session: Session, order_id: int, method: str, amount_kop: int) -> str:
    """Провести оплату завершённого быстрого заказа. Идемпотентно, под row-lock.

    Возврат: "settled" (только что провели), "already" (было оплачено), "skip" (нельзя).
    Наличные → помечаем paid, ledger НЕ трогаем. Безнал → paid + earn/fee водителю."""
    order = session.exec(
        select(InstantOrder).where(InstantOrder.id == order_id).with_for_update()
    ).first()
    if not order or order.driver_id is None:
        return "skip"
    if order.paid:
        return "already"            # уже начислено — повторный webhook НЕ задваивает
    order.paid = True
    order.payment_method = method
    session.add(order)
    if method in _CASHLESS:
        _post_earn_and_fee(session, order.driver_id, amount_kop,
                           order_id=order.id, note=f"Быстрый заказ #{order.id}")
    session.commit()
    return "settled"


def settle_booking(session: Session, booking_id: int, method: str, amount_kop: int) -> str:
    """Провести оплату завершённой брони плановой поездки. Идемпотентно, под row-lock."""
    booking = session.exec(
        select(Booking).where(Booking.id == booking_id).with_for_update()
    ).first()
    if not booking:
        return "skip"
    if booking.paid:
        return "already"
    from .models import Ride
    ride = session.get(Ride, booking.ride_id)
    if not ride:
        return "skip"
    booking.paid = True
    booking.payment_method = method
    session.add(booking)
    if method in _CASHLESS:
        _post_earn_and_fee(session, ride.driver_id, amount_kop,
                           booking_id=booking.id, note=f"Бронь #{booking.id}")
    session.commit()
    return "settled"


def reconcile(session: Session, date_from, date_to) -> dict:
    """Сверка за период: начисления ledger (earn) ↔ безналичные оплаты поездок (Payment).

    Инвариант: каждая успешная безналичная оплата поездки порождает РОВНО одну запись earn
    на ту же сумму → SUM(earn) должно совпасть с SUM(успешных безналичных Payment ride/booking).
    Расхождение (diff ≠ 0) = сигнал бага/пропущенного/двойного начисления → алерт (вешает Александр).
    Наличные в сверку НЕ входят (деньги мимо нас)."""
    from .models import Payment

    def _in_period(col):
        return (col >= date_from, col <= date_to)

    earn_rows = session.exec(
        select(LedgerEntry.amount_kop).where(
            LedgerEntry.kind == LedgerKind.earn, *_in_period(LedgerEntry.created_at)
        )
    ).all()
    fee_rows = session.exec(
        select(LedgerEntry.amount_kop).where(
            LedgerEntry.kind == LedgerKind.fee, *_in_period(LedgerEntry.created_at)
        )
    ).all()
    pay_rows = session.exec(
        select(Payment.amount_kop).where(
            Payment.purpose.in_(["ride", "booking"]),
            Payment.status == "succeeded",
            Payment.method.in_(list(_CASHLESS)),
            *_in_period(Payment.created_at),
        )
    ).all()

    earn_kop = int(sum(earn_rows))
    fee_kop = int(-sum(fee_rows))               # fee хранится отрицательным → комиссия = −сумма
    payments_kop = int(sum(pay_rows))
    diff_kop = earn_kop - payments_kop
    return {
        "date_from": date_from.isoformat(),
        "date_to": date_to.isoformat(),
        "earn_kop": earn_kop,                   # начислено водителям (полные суммы поездок)
        "fee_kop": fee_kop,                     # комиссия сервиса за период
        "net_drivers_kop": earn_kop - fee_kop,  # чистыми водителям
        "payments_kop": payments_kop,           # прошло безналом через ЮKassa (отчёт)
        "diff_kop": diff_kop,                   # расхождение ledger↔оплаты (0 = сходится)
        "ok": diff_kop == 0,
        "earn_count": len(earn_rows),
        "payments_count": len(pay_rows),
        "checked_at": utcnow().isoformat(),
    }
