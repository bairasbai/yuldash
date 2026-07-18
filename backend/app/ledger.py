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


class PayoutError(Exception):
    """Отказ вывода средств (границы / недостаточно баланса / провайдер).
    code — машинный (для тестов/логики), message — человеку (RU, на клиент)."""
    def __init__(self, code: str, message: str):
        self.code = code
        self.message = message
        super().__init__(message)


def request_payout(session: Session, driver_id: int, amount_kop: int, *,
                   payout_token: str = "", card_last4: str = "",
                   idempotency_key: str = "") -> dict:
    """Вывод с баланса водителя на карту (Модель Б). Идемпотентно, под row-lock.

    Инварианты денег: сумма в границах [min, max]; нельзя вывести больше баланса; списание
    пишется в ledger записью kind=payout (−сумма) РОВНО один раз (ключ идемпотентности в ext_id).
    Полный номер карты НЕ фигурирует — платим по токену, в note храним только последние 4."""
    from .models import User
    from .payments import create_payout

    if amount_kop <= 0:
        raise PayoutError("amount", "Сумма вывода должна быть больше нуля")
    if amount_kop < settings.payout_min_kop:
        raise PayoutError("min", f"Минимальная сумма вывода — {settings.payout_min_kop // 100} ₽")
    if amount_kop > settings.payout_max_kop:
        raise PayoutError("max", f"Максимум за один вывод — {settings.payout_max_kop // 100} ₽")

    # Row-lock строки водителя → два параллельных вывода сериализуются (не спишут баланс дважды).
    locked = session.exec(select(User).where(User.id == driver_id).with_for_update()).one_or_none()
    if locked is None:
        raise PayoutError("no_user", "Водитель не найден")

    # Идемпотентность: вывод с тем же ключом уже проведён → возвращаем его, второй раз НЕ списываем.
    if idempotency_key:
        existing = session.exec(
            select(LedgerEntry).where(
                LedgerEntry.driver_id == driver_id,
                LedgerEntry.kind == LedgerKind.payout,
                LedgerEntry.ext_id == idempotency_key,
            )
        ).first()
        if existing:
            return {"status": "already", "entry_id": existing.id, "amount_kop": -existing.amount_kop,
                    "balance_kop": driver_balance(session, driver_id)}

    bal = driver_balance(session, driver_id)
    if amount_kop > bal:
        raise PayoutError("insufficient", "Недостаточно средств на балансе")

    # Отправляем выплату провайдеру (без реальных ключей выплат — mock succeeded, денег не двигает).
    res = create_payout(amount_kop, payout_token, f"Юлдаш · выплата водителю #{driver_id}",
                        {"driver_id": str(driver_id)}, idempotence_key=idempotency_key)
    if res["status"] not in ("succeeded", "pending"):
        raise PayoutError("provider", "Не получилось отправить выплату. Попробуй позже")

    entry = LedgerEntry(
        driver_id=driver_id, kind=LedgerKind.payout, amount_kop=-amount_kop,
        ext_id=idempotency_key or res.get("payout_id", ""),
        note=(f"Вывод на карту ····{card_last4}" if card_last4 else "Вывод на карту"),
    )
    session.add(entry)
    session.commit()
    session.refresh(entry)
    return {"status": "ok", "entry_id": entry.id, "amount_kop": amount_kop,
            "provider_status": res["status"], "balance_kop": driver_balance(session, driver_id)}


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
        # B2: комиссия удержана в ledger fee → снимаем долг Модели А по этому заказу, иначе
        # двойная комиссия + фантомный unpaid-долг заблокирует водителя на онлайн-оплате.
        from . import debt as _debt
        _debt.void_debt_for_order(session, order.id)
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
