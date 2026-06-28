"""Платежи за свои услуги платформы (самозанятый): Boost поездки, платная реклама.

`GET  /boost/plans`              — тарифы поднятия (цены с бэкенда, не хардкод клиента).
`POST /boost/create`            — оплатить поднятие своей поездки → confirmation_url ЮKassa.
`POST /payments/yookassa/webhook` — уведомление ЮKassa: перепроверяем платёж и активируем.
"""
from datetime import timedelta

from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import BaseModel
from sqlmodel import Session, select

from ..config import settings
from ..db import get_session
from ..models import Payment, Ride, RideStatus, User
from ..payments import BOOST_PLANS, create_payment, fetch_payment
from ..security import current_user
from ..timeutil import utcnow

router = APIRouter(tags=["payments"])


@router.get("/boost/plans")
def boost_plans():
    """Тарифы Boost (цена ₽ + длительность). Источник истины — бэкенд."""
    return [
        {"tier": t, "title": title, "price": kop // 100, "hours": hours}
        for t, (title, kop, hours) in BOOST_PLANS.items()
    ]


def _activate_boost(session: Session, payment: Payment) -> None:
    """Применить оплаченный boost к поездке (идемпотентно: только из pending)."""
    if payment.status == "succeeded":
        return
    payment.status = "succeeded"
    session.add(payment)
    if payment.purpose == "boost" and payment.ride_id is not None:
        ride = session.get(Ride, payment.ride_id)
        plan = BOOST_PLANS.get(payment.tier)
        if ride and plan:
            ride.boosted_until = utcnow() + timedelta(hours=plan[2])
            ride.boost_tier = payment.tier
            session.add(ride)
    session.commit()


class BoostIn(BaseModel):
    ride_id: int
    tier: str


@router.post("/boost/create")
def boost_create(body: BoostIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Создать платёж за поднятие СВОЕЙ поездки. dev/mock → активируем сразу;
    yookassa → возвращаем confirmation_url (оплата → вебхук активирует)."""
    plan = BOOST_PLANS.get(body.tier)
    if not plan:
        raise HTTPException(400, "Неизвестный тариф")
    ride = session.get(Ride, body.ride_id)
    if not ride:
        raise HTTPException(404, "Поездка не найдена")
    if ride.driver_id != user.id:
        raise HTTPException(403, "Поднять можно только свою поездку")
    if ride.status != RideStatus.active:
        raise HTTPException(400, "Поездка неактивна")
    # В проде без настроенного провайдера не выдаём «бесплатный» boost через mock.
    if settings.is_prod and settings.payments_provider != "yookassa":
        raise HTTPException(503, "Оплата скоро будет доступна")

    title, amount_kop, _hours = plan
    payment = Payment(user_id=user.id, purpose="boost", ride_id=ride.id, tier=body.tier, amount_kop=amount_kop)
    session.add(payment)
    session.commit()
    session.refresh(payment)

    # user.phone — реальный (current_user не пускает плейсхолдер): на него ЮKassa шлёт чек.
    res = create_payment(amount_kop, f"Юлдаш · {title}", {"payment_id": str(payment.id)}, customer_phone=user.phone)
    payment.provider_id = res["provider_id"]
    session.add(payment)
    session.commit()

    if res["status"] == "succeeded":          # mock/dev — оплачено сразу
        _activate_boost(session, payment)
        session.refresh(ride)
        return {"status": "succeeded", "payment_id": payment.id, "boosted_until": ride.boosted_until}
    return {"status": "pending", "payment_id": payment.id, "confirmation_url": res["confirmation_url"]}


@router.post("/payments/yookassa/webhook")
async def yookassa_webhook(request: Request, session: Session = Depends(get_session)):
    """Уведомление ЮKassa. Телу НЕ доверяем — по id перепроверяем статус через API ЮKassa."""
    try:
        body = await request.json()
    except Exception:
        return {"ok": True}
    provider_id = ((body.get("object") or {}).get("id")) or ""
    if not provider_id:
        return {"ok": True}
    try:
        info = fetch_payment(provider_id)   # перепроверка через API ЮKassa (не доверяем телу)
    except Exception:  # noqa: BLE001 — неизвестный/битый id → просто игнор (ЮKassa повторит)
        return {"ok": True}
    if info["status"] != "succeeded":
        return {"ok": True}
    payment = session.exec(select(Payment).where(Payment.provider_id == provider_id)).first()
    if payment:
        _activate_boost(session, payment)
    return {"ok": True}
