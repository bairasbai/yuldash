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
from ..models import Ad, Payment, Ride, RideStatus, User, UserRole
from ..payments import BOOST_PLANS, create_payment, fetch_payment
from ..security import current_user
from ..services import notify_admin_telegram
from ..timeutil import utcnow

router = APIRouter(tags=["payments"])


@router.get("/boost/plans")
def boost_plans():
    """Тарифы Boost (цена ₽ + длительность). Источник истины — бэкенд."""
    return [
        {"tier": t, "title": title, "price": kop // 100, "hours": hours}
        for t, (title, kop, hours) in BOOST_PLANS.items()
    ]


def _activate_payment(session: Session, payment: Payment) -> None:
    """Применить оплаченный платёж (идемпотентно, только из pending):
    boost → поднять поездку; ad → опубликовать рекламу; donate → просто succeeded."""
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
    elif payment.purpose == "ad" and payment.ad_id is not None:
        ad = session.get(Ad, payment.ad_id)
        if ad:
            ad.status = "active"        # реклама публикуется после подтверждения оплаты
            # Срок показа отсчитываем от ОПЛАТЫ (go-live), а не от одобрения модерацией:
            # партнёр получает полный оплаченный период, даже если оплатил не сразу
            # (и если окно от одобрения успело истечь — оплата даёт свежий период).
            if ad.period_days > 0:
                ad.starts_at = utcnow()
                ad.ends_at = utcnow() + timedelta(days=ad.period_days)
            session.add(ad)
    session.commit()


def _notify_new_payment(session: Session, payment: Payment) -> None:
    """Telegram админу о новой заявке на оплату (СБП): сверь карту → подтверди в кабинете."""
    payer = session.get(User, payment.user_id)
    who = (payer.name if payer and payer.name else "—") + (f" · {payer.phone}" if payer and payer.phone else "")
    label = {"boost": "Буст", "donate": "Донат", "ad": "Реклама"}.get(payment.purpose, payment.purpose)
    notify_admin_telegram(
        (
            f"💳 Новая оплата СБП\n"
            f"ID: {payment.id}\n"
            f"Тип: {label}\n"
            f"Сумма: {payment.amount_kop // 100} ₽\n"
            f"От: {who}\n\n"
            "Сначала проверь поступление в банке, потом подтверди здесь."
        ),
        reply_markup={
            "inline_keyboard": [[
                {"text": "✅ Подтвердить", "callback_data": f"pay:ok:{payment.id}"},
                {"text": "❌ Отклонить", "callback_data": f"pay:no:{payment.id}"},
            ]]
        },
    )


class BoostFreeIn(BaseModel):
    ride_id: int


@router.post("/boost/free")
def boost_free(body: BoostFreeIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Поднять СВОЮ поездку бесплатно за реферальный бонус (1 бонус = 24ч поднятия)."""
    ride = session.get(Ride, body.ride_id)
    if not ride or ride.driver_id != user.id:
        raise HTTPException(403, "Это не ваша поездка")
    # Списание бонуса под row-lock (как book()): два параллельных free-boost не потратят
    # один и тот же бонус дважды (иначе гонка read-modify-write → 2 бесплатных подъёма, кредиты в минус).
    locked = session.exec(select(User).where(User.id == user.id).with_for_update()).one()
    if locked.referral_credits < 1:
        raise HTTPException(400, "Нет бонусов")
    locked.referral_credits -= 1
    ride.boosted_until = utcnow() + timedelta(hours=24)
    ride.boost_tier = "free"
    session.add(ride)
    session.add(locked)
    session.commit()
    return {"ok": True, "credits": locked.referral_credits}


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
    # В проде mock = «оплата» без денег → не выдаём бесплатный boost.
    if settings.is_prod and settings.payments_provider == "mock":
        raise HTTPException(503, "Оплата скоро будет доступна")

    title, amount_kop, _hours = plan
    payment = Payment(user_id=user.id, purpose="boost", ride_id=ride.id, tier=body.tier, amount_kop=amount_kop)
    session.add(payment)
    session.commit()
    session.refresh(payment)

    # СБП-перевод по номеру: платёж висит pending, активирует админ после получения денег.
    if settings.payments_provider == "sbp_manual":
        _notify_new_payment(session, payment)
        return {
            "status": "pending", "method": "sbp_manual", "payment_id": payment.id,
            "amount": amount_kop // 100,
            # Имя получателя НЕ отдаём: банк сам покажет его при подтверждении перевода по СБП.
            # Раньше раздавали ФИО владельца всем вошедшим → почва для имперсонации.
            "payee": {"phone": settings.sbp_phone, "bank": settings.sbp_bank},
        }

    # mock/yookassa. user.phone реальный (current_user не пускает плейсхолдер) → на него ЮKassa шлёт чек.
    res = create_payment(amount_kop, f"Юлдаш · {title}", {"payment_id": str(payment.id)}, customer_phone=user.phone)
    payment.provider_id = res["provider_id"]
    session.add(payment)
    session.commit()

    if res["status"] == "succeeded":          # mock/dev — оплачено сразу
        _activate_payment(session, payment)
        session.refresh(ride)
        return {"status": "succeeded", "method": "yookassa", "payment_id": payment.id, "boosted_until": ride.boosted_until}
    return {"status": "pending", "method": "yookassa", "payment_id": payment.id, "confirmation_url": res["confirmation_url"]}


class DonateIn(BaseModel):
    amount: int   # сумма доната, ₽


@router.post("/donate")
def donate_create(body: DonateIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Донат на платформу. Интерим (до ЮKassa): СБП-перевод вручную → заявка админу на подтверждение.
    Подтверждённые донаты идут в счётчик (/admin/payments/summary)."""
    amount = body.amount
    if amount < 10 or amount > 100000:
        raise HTTPException(400, "Сумма доната — от 10 до 100000 ₽")
    if settings.is_prod and settings.payments_provider == "mock":
        raise HTTPException(503, "Оплата скоро будет доступна")

    payment = Payment(user_id=user.id, purpose="donate", amount_kop=amount * 100)
    session.add(payment)
    session.commit()
    session.refresh(payment)

    if settings.payments_provider == "sbp_manual":
        _notify_new_payment(session, payment)
        return {
            "status": "pending", "method": "sbp_manual", "payment_id": payment.id,
            "amount": amount,
            # Имя получателя НЕ отдаём: банк сам покажет его при подтверждении перевода по СБП.
            # Раньше раздавали ФИО владельца всем вошедшим → почва для имперсонации.
            "payee": {"phone": settings.sbp_phone, "bank": settings.sbp_bank},
        }

    res = create_payment(amount * 100, "Юлдаш · донат", {"payment_id": str(payment.id)}, customer_phone=user.phone)
    payment.provider_id = res["provider_id"]
    session.add(payment)
    session.commit()
    if res["status"] == "succeeded":          # mock/dev — оплачено сразу
        _activate_payment(session, payment)     # для donate просто помечает succeeded (поездку не трогает)
        return {"status": "succeeded", "method": "yookassa", "payment_id": payment.id}
    return {"status": "pending", "method": "yookassa", "payment_id": payment.id, "confirmation_url": res["confirmation_url"]}


# ----------------------------- Админ: подтверждение СБП-переводов -----------------------------
def _require_admin(user: User) -> None:
    if user.role != UserRole.admin:
        raise HTTPException(403, "Только для админа")


@router.get("/admin/payments/pending")
def admin_pending_payments(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Список ожидающих подтверждения платежей (СБП). Для админа."""
    _require_admin(user)
    rows = session.exec(select(Payment).where(Payment.status == "pending").order_by(Payment.id.desc())).all()
    out = []
    for p in rows:
        payer = session.get(User, p.user_id)
        name = (payer.name if payer else "")
        note = ""
        if p.purpose == "ad" and p.ad_id is not None:
            ad = session.get(Ad, p.ad_id)
            if ad:
                name = ad.partner_name or name      # для рекламы в очереди показываем партнёра
                note = ad.title
        elif p.purpose == "boost" and p.ride_id is not None:
            note = f"Поездка #{p.ride_id}"
        out.append({
            "payment_id": p.id, "purpose": p.purpose, "tier": p.tier,
            "amount": p.amount_kop // 100, "ride_id": p.ride_id, "note": note,
            "payer_name": name, "payer_phone": (payer.phone if payer else ""),
            "created_at": p.created_at,
        })
    return out


@router.post("/admin/payments/{payment_id}/confirm")
def admin_confirm_payment(payment_id: int, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Подтвердить получение СБП-перевода → активировать (boost). Для админа."""
    _require_admin(user)
    payment = session.get(Payment, payment_id)
    if not payment:
        raise HTTPException(404, "Платёж не найден")
    if payment.status == "succeeded":
        return {"payment_id": payment.id, "status": "succeeded"}
    _activate_payment(session, payment)
    return {"payment_id": payment.id, "status": "succeeded"}


@router.post("/admin/payments/{payment_id}/reject")
def admin_reject_payment(payment_id: int, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Отклонить платёж (деньги не пришли). Для админа."""
    _require_admin(user)
    payment = session.get(Payment, payment_id)
    if not payment:
        raise HTTPException(404, "Платёж не найден")
    if payment.status == "pending":
        payment.status = "canceled"
        session.add(payment)
        session.commit()
    return {"payment_id": payment.id, "status": payment.status}


@router.get("/admin/payments/summary")
def admin_payments_summary(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Сводка подтверждённых платежей (счётчик донатов/буста). Для админа."""
    _require_admin(user)

    def agg(purpose: str) -> dict:
        rows = session.exec(select(Payment).where(Payment.purpose == purpose, Payment.status == "succeeded")).all()
        return {"count": len(rows), "sum_rub": sum(p.amount_kop for p in rows) // 100}

    return {"donate": agg("donate"), "boost": agg("boost")}


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
    # Сначала ищем СВОЙ платёж по id (параметризованный запрос). Нет совпадения / уже
    # оплачен → тихо выходим БЕЗ исходящего запроса к ЮKassa. Иначе любой мог бы флудить
    # вебхук случайными id и заставлять сервер ходить наружу (амплификация/DoS), а чужой id
    # уходил бы в URL-путь ЮKassa. Наружу ходим только за id, что сами выпустили.
    payment = session.exec(select(Payment).where(Payment.provider_id == provider_id)).first()
    if not payment or payment.status == "succeeded":
        return {"ok": True}
    try:
        info = fetch_payment(provider_id)   # верификация статуса у ЮKassa (телу не доверяем)
    except Exception:  # noqa: BLE001 — ошибка сети/ЮKassa → игнор (ЮKassa повторит вебхук)
        return {"ok": True}
    if info["status"] == "succeeded":
        _activate_payment(session, payment)
    return {"ok": True}
