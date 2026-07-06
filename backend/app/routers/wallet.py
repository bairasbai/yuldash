"""Деньги v1 (Фаза 3, D3): оплата поездки после done + кошелёк-ledger водителя + сверка.

Оплата ТОЛЬКО завершённой (done) поездки/брони — пассажир платит картой/СБП (ЮKassa) или
отмечает «наличными». Безнал начисляет водителю через ledger (earn − комиссия); наличные
идут мимо нас (ledger не двигаем). Начисление идемпотентно (флаг paid + row-lock в ledger.py).

Права (анти-IDOR): платить может ТОЛЬКО пассажир-владелец заказа; кошелёк/историю ledger
видит ТОЛЬКО сам водитель (по своему токену); сверка — только админ.

`POST /instant/orders/{id}/pay`  — оплатить завершённый быстрый заказ.
`POST /bookings/{id}/pay`        — оплатить завершённую бронь плановой поездки.
`GET  /wallet/balance`           — баланс водителя (SUM ledger).
`GET  /wallet/ledger`            — записи ledger водителя (свои).
`GET  /admin/ledger/reconcile`   — сверка ledger↔оплаты за период (админ).
"""
from datetime import datetime, timedelta

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field
from sqlmodel import Session

from ..config import settings
from ..db import get_session
from ..ledger import driver_balance, ledger_entries, reconcile
from ..models import Booking, BookingStatus, InstantOrder, InstantOrderStatus, Payment, User, UserRole
from ..payments import create_payment
from ..security import current_user
from ..timeutil import utcnow
from .payments import _activate_payment

router = APIRouter(tags=["wallet"])

# Способы оплаты, которые принимает пассажир на экране «Оплата».
_METHODS = ("cash", "card", "sbp")


class PayIn(BaseModel):
    method: str = Field("card", max_length=16)   # cash | card | sbp


def _guard_method(method: str) -> None:
    if method not in _METHODS:
        raise HTTPException(400, "Неизвестный способ оплаты")


def _pay_cashless(session: Session, payer: User, *, purpose: str, amount_kop: int,
                  method: str, description: str, order_id=None, booking_id=None) -> dict:
    """Общий безналичный поток через ЮKassa (карта/СБП). mock/dev → succeeded сразу
    (активируем и начисляем); yookassa → confirmation_url, начисление придёт по webhook."""
    # В проде mock = «оплата» без денег → не начисляем «бесплатно».
    if settings.is_prod and settings.payments_provider == "mock":
        raise HTTPException(503, "Оплата скоро будет доступна")
    payment = Payment(
        user_id=payer.id, purpose=purpose, amount_kop=amount_kop, method=method,
        order_id=order_id, booking_id=booking_id,
    )
    session.add(payment)
    session.commit()
    session.refresh(payment)
    res = create_payment(amount_kop, description, {"payment_id": str(payment.id)}, customer_phone=payer.phone)
    payment.provider_id = res["provider_id"]
    session.add(payment)
    session.commit()
    if res["status"] == "succeeded":              # mock/dev — оплачено сразу → начисляем
        _activate_payment(session, payment)
        return {"status": "succeeded", "method": "yookassa", "payment_id": payment.id}
    return {"status": "pending", "method": "yookassa", "payment_id": payment.id,
            "confirmation_url": res["confirmation_url"]}


# ------------------------------ оплата быстрого заказа ------------------------------
@router.post("/instant/orders/{order_id}/pay")
def pay_instant_order(order_id: int, body: PayIn, user: User = Depends(current_user),
                      session: Session = Depends(get_session)):
    """Пассажир оплачивает ЗАВЕРШЁННЫЙ быстрый заказ. Только владелец, только статус done."""
    order = session.get(InstantOrder, order_id)
    if not order:
        raise HTTPException(404, "Заказ не найден")
    if order.passenger_id != user.id:                     # анти-IDOR: чужой заказ не оплатить
        raise HTTPException(403, "Это не твой заказ")
    if order.status != InstantOrderStatus.done:
        raise HTTPException(409, "Оплатить можно только завершённую поездку")
    if order.driver_id is None:
        raise HTTPException(409, "У заказа нет водителя")
    if order.paid:                                        # идемпотентно: повторная оплата не начисляет второй раз
        return {"status": "already_paid", "method": order.payment_method}
    _guard_method(body.method)
    amount_kop = int(order.price_final or order.price_estimate) * 100   # цена в ₽ → копейки
    if body.method == "cash":
        from .. import ledger
        ledger.settle_instant_order(session, order.id, "cash", amount_kop)   # paid=True, ledger НЕ трогаем
        return {"status": "paid", "method": "cash"}
    return _pay_cashless(session, user, purpose="ride", amount_kop=amount_kop, method=body.method,
                         description=f"Юлдаш · поездка #{order.id}", order_id=order.id)


# ------------------------------ оплата брони плановой поездки ------------------------------
@router.post("/bookings/{booking_id}/pay")
def pay_booking(booking_id: int, body: PayIn, user: User = Depends(current_user),
                session: Session = Depends(get_session)):
    """Пассажир оплачивает ЗАВЕРШЁННУЮ бронь плановой поездки. Только владелец, только done."""
    booking = session.get(Booking, booking_id)
    if not booking:
        raise HTTPException(404, "Бронь не найдена")
    if booking.passenger_id != user.id:                   # анти-IDOR
        raise HTTPException(403, "Это не твоя бронь")
    if booking.status != BookingStatus.done:
        raise HTTPException(409, "Оплатить можно только завершённую поездку")
    if booking.paid:
        return {"status": "already_paid", "method": booking.payment_method}
    _guard_method(body.method)
    amount_kop = int(booking.price) * 100                 # цена брони в ₽ → копейки
    if amount_kop <= 0:
        raise HTTPException(409, "У брони нет суммы к оплате")
    if body.method == "cash":
        from .. import ledger
        ledger.settle_booking(session, booking.id, "cash", amount_kop)
        return {"status": "paid", "method": "cash"}
    return _pay_cashless(session, user, purpose="booking", amount_kop=amount_kop, method=body.method,
                         description=f"Юлдаш · поездка #{booking.id}", booking_id=booking.id)


# ------------------------------ кошелёк водителя ------------------------------
@router.get("/wallet/balance")
def wallet_balance(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Баланс кошелька — сумма всех записей ledger по СВОЕМУ id (нельзя запросить чужой)."""
    bal = driver_balance(session, user.id)
    return {"balance_kop": bal, "balance_rub": bal // 100}


@router.get("/wallet/ledger")
def wallet_ledger(limit: int = 100, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """История начислений/комиссий/выплат водителя. Всегда по СВОЕМУ id — чужую не видно."""
    rows = ledger_entries(session, user.id, limit)
    return [
        {
            "id": e.id, "kind": e.kind.value if hasattr(e.kind, "value") else e.kind,
            "amount_kop": e.amount_kop, "amount_rub": e.amount_kop / 100.0,
            "order_id": e.order_id, "booking_id": e.booking_id,
            "note": e.note, "created_at": e.created_at,
        }
        for e in rows
    ]


# ------------------------------ сверка (админ) ------------------------------
@router.get("/admin/ledger/reconcile")
def admin_ledger_reconcile(days: int = 1, date_from: str = "", date_to: str = "",
                           user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Сверка: SUM(earn ledger) ↔ SUM(успешных безналичных оплат) за период. Только админ.
    По умолчанию — сутки. diff ≠ 0 → расхождение (алерт вешает Александр)."""
    if user.role != UserRole.admin:
        raise HTTPException(403, "Только для админа")
    try:
        end = datetime.fromisoformat(date_to) if date_to else utcnow()
        start = datetime.fromisoformat(date_from) if date_from else end - timedelta(days=max(1, min(days, 366)))
    except ValueError:
        raise HTTPException(400, "Неверный формат даты (нужен ISO 8601)")
    return reconcile(session, start, end)
