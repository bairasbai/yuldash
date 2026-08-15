"""Платежи за свои услуги платформы (самозанятый): Boost поездки, платная реклама.

`GET  /boost/plans`              — тарифы поднятия (цены с бэкенда, не хардкод клиента).
`POST /boost/create`            — оплатить поднятие своей поездки → confirmation_url ЮKassa.
`POST /payments/yookassa/webhook` — уведомление ЮKassa: перепроверяем платёж и активируем.
"""
from datetime import timedelta

from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import BaseModel
from sqlalchemy import func
from sqlmodel import Session, select

from ..config import settings
from ..db import get_session
from ..errors import herr
from ..logs import admin_action, log
from ..models import Ad, Payment, Ride, RideStatus, User, UserRole
from ..payments import BOOST_PLANS, create_payment, fetch_payment
from ..security import current_user
from ..services import notify_admin_telegram
from ..timeutil import utcnow

router = APIRouter(tags=["payments"])

# Сколько минут неоплаченный счёт на поднятие считается «тем же самым». Окно короткое: человек
# либо платит по QR сразу, либо передумал. Дольше держать нельзя — цена тарифа может смениться.
BOOST_PENDING_REUSE_MIN = 30

# Деньги пришли, а применить их не к чему: наш платёж к этому моменту уже отменён (человек
# заплатил наличными / админ отклонил), а ссылка ЮKassa жила и по ней заплатили. Отменить
# неоплаченный платёж их API не умеет, так что случай реальный, а не выдуманный.
# Статус нужен, чтобы деньги не растворились: по нему видно, кому мы должны вернуть.
REFUND_DUE = "refund_due"


def _handle_unclaimed_payment(session: Session, payment: Payment) -> None:
    """Пришли деньги, которые не к чему применить → возврат, и об этом узнают все стороны.

    Раньше здесь был молчаливый выход. Для человека это выглядело так: заплатил наличными
    водителю, потом случайно открыл старую ссылку и заплатил ещё раз картой — деньги списались,
    в приложении ничего, спросить не у кого. Деньги оставались у платформы, и о долге не знал
    никто, включая нас самих (аудит 2026-08-12, волна 26).

    Что делаем: помечаем платёж как «нужен возврат» (это запись в базе, а не сигнал в воздух),
    открываем человеку обращение в поддержку — оно и есть его нить для разговора — и даём знать
    админу в Telegram. Идемпотентно: повторный вебхук по тому же платежу второго тикета
    не заведёт (гейт по статусу в самом вебхуке).
    """
    from ..models import SupportMessage, SupportSender, SupportTicket
    from ..services import push_notification

    payment.status = REFUND_DUE
    session.add(payment)
    session.commit()

    rub = payment.amount_kop // 100
    payer = session.get(User, payment.user_id)
    lang = (getattr(payer, "language", "") or "ru").lower()
    # Тред поддержки пишем НА ЯЗЫКЕ ЧЕЛОВЕКА: тело сообщения одно, выбрать язык можно только так.
    body_ru = (f"Мы получили от тебя оплату {rub} ₽, которую не к чему применить — эта поездка "
               "уже была оплачена другим способом. Деньги вернём на ту же карту. "
               "Напиши сюда, если возврат не придёт в течение 3 рабочих дней.")
    body_ba = (f"Беҙ һинән {rub} һум түләү алдыҡ, ләкин уны ҡулланырға урын юҡ — был сәфәр "
               "башҡа юл менән түләнгән инде. Аҡсаны шул уҡ картаға ҡайтарабыҙ. "
               "3 эш көнө эсендә ҡайтмаһа, бында яҙ.")
    try:
        ticket = SupportTicket(user_id=payment.user_id, subject="Возврат лишней оплаты")
        session.add(ticket)
        session.commit()
        session.refresh(ticket)
        session.add(SupportMessage(ticket_id=ticket.id, sender=SupportSender.admin,
                                   body=(body_ba if lang == "ba" else body_ru)))
        session.commit()
        ref_id = ticket.id
    except Exception as e:  # noqa: BLE001 — тред вторичен, отметку о возврате не теряем
        ref_id = None
        log.warning(f"[REFUND] не удалось открыть тред по платежу {payment.id}: {e}")

    push_notification(
        session, payment.user_id, "system",
        "Вернём лишнюю оплату", "Артыҡ түләүҙе ҡайтарабыҙ",
        f"Оплата {rub} ₽ пришла, когда поездка уже была оплачена. Деньги вернём на карту.",
        f"{rub} һумлыҡ түләү сәфәр түләнгәндән һуң килде. Аҡсаны картаға ҡайтарабыҙ.",
        ref_kind=("support" if ref_id else ""), ref_id=ref_id,
    )
    try:    # админу — best-effort; телефон не шлём (несрочное уведомление, волна 12)
        notify_admin_telegram(
            f"💸 Нужен ВОЗВРАТ: платёж #{payment.id} на {rub} ₽\n"
            f"Пришёл по старой ссылке, применить не к чему (заказ уже оплачен).\n"
            f"Плательщик: {(payer.name if payer else '—')} (id {payment.user_id})\n"
            f"Верни в кабинете ЮKassa, потом ответь человеку в поддержке."
        )
    except Exception:  # noqa: BLE001
        pass


@router.get("/boost/plans")
def boost_plans():
    """Тарифы Boost (цена ₽ + длительность). Источник истины — бэкенд."""
    return [
        {"tier": t, "title": title, "price": kop // 100, "hours": hours}
        for t, (title, kop, hours) in BOOST_PLANS.items()
    ]


def _start_yookassa(session: Session, payment: Payment, description: str, phone: str) -> dict:
    """M2: создать платёж в ЮKassa безопасно. При сбое (таймаут/недоступность ЮKassa) не роняем
    500 и не оставляем висящий pending без provider_id — удаляем orphan-строку и просим повторить."""
    try:
        return create_payment(payment.amount_kop, description, {"payment_id": str(payment.id)}, customer_phone=phone)
    except Exception:  # noqa: BLE001 — сеть/ЮKassa недоступна: чистим orphan, отдаём мягкую 503
        session.delete(payment)
        session.commit()
        raise herr(503, "Оплата временно недоступна. Попробуй ещё раз.", "Түләү ваҡытлыса эшләмәй. Тағы ҡабатла.")


def _mark_succeeded(payment: Payment) -> None:
    """Платёж применён: ставим статус И МОМЕНТ применения.

    `created_at` — когда человек НАЖАЛ «оплатить», а не когда деньги дошли. Сверка
    (`ledger.reconcile`) сравнивала начисления с оплатами по этой дате, и обычная ночная
    оплата (нажал в 23:58, деньги пришли в 00:03) давала −1000 ₽ вчера и +1000 ₽ сегодня
    при полном порядке с деньгами. Прибор, который краснеет сам по себе, перестают читать —
    и настоящую поломку он уже никому не покажет (аудит 2026-08-12, волна 27).
    """
    payment.status = "succeeded"
    payment.settled_at = utcnow()


def _activate_payment(session: Session, payment: Payment) -> None:
    """Применить оплаченный платёж (идемпотентно, только из pending).

    M3: блокируем строку платежа (FOR UPDATE) — webhook/поллинг/админ могут прийти параллельно.
    C1: для ДЕНЕЖНЫХ/идемпотентных эффектов (начисление водителю, гашение комиссии/долга)
    сначала выполняем ЭФФЕКТ, потом ставим succeeded. Иначе краш между commit(succeeded) и
    начислением оставил бы водителя недоплаченным навсегда (ретрай упёрся бы в guard succeeded).
    Для АДДИТИВНЫХ эффектов (boost/ad/подписка) наоборот — succeeded первым (защита от двойного
    применения при повторном/параллельном webhook)."""
    locked = session.exec(select(Payment).where(Payment.id == payment.id).with_for_update()).one_or_none()
    if locked is None:
        return
    payment = locked
    # Применяем ТОЛЬКО из pending. Раньше гейт стоял лишь на succeeded, и ОТМЕНЁННЫЙ платёж
    # спокойно оживал (аудит 2026-08-07): пассажир создал безнал, передумал и заплатил налом
    # → у нас платёж canceled, но ссылка ЮKassa жива (отменить неоплаченный pending их API не
    # умеет). Оплата по старой ссылке приходила вебхуком, платёж становился succeeded, а
    # начисление уже не срабатывало (заказ оплачен налом) — деньги у платформы, водителю ноль.
    # Тот же путь у админа: /admin/payments/{id}/reject, а потом /confirm.
    if payment.status != "pending":
        return
    # --- Идемпотентные эффекты: ЭФФЕКТ → потом succeeded (settle сам идемпотентен под FOR UPDATE+paid) ---
    if payment.purpose == "ride" and payment.order_id is not None:
        from .. import ledger
        ledger.settle_instant_order(session, payment.order_id, payment.method or "yookassa", payment.amount_kop)
        _mark_succeeded(payment); session.add(payment); session.commit()
        return
    if payment.purpose == "booking" and payment.booking_id is not None:
        from .. import ledger
        ledger.settle_booking(session, payment.booking_id, payment.method or "yookassa", payment.amount_kop)
        _mark_succeeded(payment); session.add(payment); session.commit()
        return
    if payment.purpose == "courier_commission":
        # Курьер оплатил накопленную комиссию → помечаем paid его доставленные неоплаченные заказы,
        # но ТОЛЬКО те, что вошли в снапшот суммы (delivered_at <= момент создания платежа). Иначе
        # доставки, сделанные в окне между «жму оплатить» и подтверждением, погасились бы бесплатно.
        # Идемпотентно (только ещё неоплаченные). Новые доставки останутся к оплате следующим платежом.
        from ..models import ParcelDelivery
        rows = session.exec(
            select(ParcelDelivery).where(
                ParcelDelivery.courier_id == payment.user_id,
                ParcelDelivery.status == "delivered",
                ParcelDelivery.commission_paid == False,  # noqa: E712
                ParcelDelivery.delivered_at <= payment.created_at,
            )
        ).all()
        for pd in rows:
            pd.commission_paid = True
            session.add(pd)
        _mark_succeeded(payment); session.add(payment); session.commit()
        return
    if payment.purpose == "taxi_debt":
        # Таксист оплатил недельную комиссию картой → гасим долг (unpaid+pending), но только тот, что
        # вошёл в снапшот суммы (created_at <= момент создания платежа). Долг, накопленный в окне до
        # подтверждения, останется к оплате следующим платежом (иначе гасился бы бесплатно). Идемпотентно.
        from .. import debt as debt_mod
        debt_mod.mark_all_paid(session, payment.user_id, up_to=payment.created_at)
        _mark_succeeded(payment); session.add(payment); session.commit()
        return
    # --- Аддитивные / прочие эффекты: succeeded ПЕРВЫМ (под тем же row-lock), потом эффект ---
    # donate / support → только отметка succeeded (доход платформы, ledger не трогаем).
    _mark_succeeded(payment)
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
    elif payment.purpose == "partner_sub" and payment.partner_id is not None:
        # Подписка бизнеса «Скидки по пути» (M1). Продление добавляет период к остатку
        # (как реклама даёт полный оплаченный период): если подписка ещё активна —
        # считаем от её конца, иначе от now. Тариф зафиксирован в payment.tier.
        from ..models import Partner
        from .coupons import PARTNER_PLANS
        partner = session.get(Partner, payment.partner_id)
        plan = PARTNER_PLANS.get(payment.tier)
        if partner and plan:
            now = utcnow()
            base = partner.subscription_until if (partner.subscription_until and partner.subscription_until > now) else now
            partner.subscription_until = base + timedelta(days=plan["period_days"])
            partner.subscription_plan = payment.tier
            # Оплата не понижает статус одобренного бизнеса, но и НЕ реанимирует отклонённого:
            # rejected (фрод/бан модерацией) не должен возвращаться в витрину через старый pending.
            if partner.status != "rejected":
                partner.status = "active"
            session.add(partner)
    session.commit()
    _tell_about_payment(session, payment, ok=True)


# Что человек получает за деньги — своими словами, для уведомления.
_PAID_TEXT = {
    "boost": ("Оплата получена — объявление поднято в ленте 💚",
              "Түләү ҡабул ителде — иғлан таҫмала өҫкә күтәрелде 💚"),
    "ad": ("Оплата получена — реклама опубликована 💚",
           "Түләү ҡабул ителде — реклама баҫылды 💚"),
    "partner_sub": ("Оплата получена — подписка продлена 💚",
                    "Түләү ҡабул ителде — яҙылыу оҙайтылды 💚"),
    "donate": ("Спасибо за поддержку 💚 Перевод получен.",
               "Ярҙамың өсөн рәхмәт 💚 Күсереү ҡабул ителде."),
    "taxi_debt": ("Комиссия закрыта — такси снова доступно 💚",
                  "Комиссия ябылды — такси кире асыҡ 💚"),
    "courier_commission": ("Комиссия курьера закрыта 💚",
                           "Курьер комиссияһы ябылды 💚"),
}


def _tell_about_payment(session: Session, payment: Payment, ok: bool) -> None:   # noqa: D401
    """Сказать человеку, чем кончился его перевод.

    Переводы у нас «на доверии»: человек отправляет деньги по СБП и ждёт, пока админ увидит
    поступление и нажмёт кнопку. Ни подтверждение, ни отказ не доходили до него никак —
    ни одного уведомления на всём пути (аудит 2026-08-08, волна 84).

    Для человека это выглядит так: заплатил и смотришь в приложение, пытаясь понять, сработало
    или нет. А если админ отклонил («перевод не нашли»), не происходит вообще ничего: человек
    уверен, что оплатил, и ждёт неделю. Деньги — то место, где тишина обходится дороже всего.

    Карточные платежи включаются вебхуком, без участия админа, — поэтому текст живёт здесь,
    в общей точке успеха, а не в админской ручке.
    """
    from ..services import push_notification   # локальный импорт: services тянет роутеры на старте
    ru_ba = _PAID_TEXT.get(payment.purpose or "")
    if ok and ru_ba is None:
        return   # служебные (ride/booking) человек и так видит по состоянию поездки
    if ok:
        title = ("Оплата получена", "Түләү ҡабул ителде")
        body = ru_ba
    else:
        title = ("Перевод не нашли", "Күсереү табылманы")
        body = ("Мы не увидели перевод. Если ты платил — напиши в поддержку, разберёмся 💚",
                "Күсереүҙе күрмәнек. Түләгән булһаң — ярҙам хеҙмәтенә яҙ, асыҡлайбыҙ 💚")
    # Уведомление должно ВЕСТИ туда, где человек увидит результат своих денег: поднятие —
    # в его поездку, реклама — в кабинет объявлений, подписка — в «Мой бизнес», комиссия —
    # в кабинет водителя. Донат никуда не ведёт: там нечего смотреть, это просто спасибо.
    # Вида «payment» приложение не знает — тапнув по такому пушу, человек упёрся бы в пустоту
    # (поймал сторож `test_notifications_lead_somewhere`).
    ref_kind, ref_id = {
        "boost": ("ride", payment.ride_id),
        "ad": ("ad", payment.ad_id),
        "partner_sub": ("partner", payment.partner_id),
        "taxi_debt": ("debt", payment.user_id),
        "courier_commission": ("debt", payment.user_id),
    }.get(payment.purpose or "", ("", None))
    push_notification(
        session, payment.user_id, "system",
        title[0], title[1], body[0], body[1],
        ref_kind=ref_kind, ref_id=ref_id,
    )


def _notify_new_payment(session: Session, payment: Payment) -> None:
    """Telegram админу о новой заявке на оплату (СБП): сверь карту → подтверди в кабинете."""
    payer = session.get(User, payment.user_id)
    who = (payer.name if payer and payer.name else "—") + (f" · {payer.phone}" if payer and payer.phone else "")
    label = {"boost": "Буст", "donate": "Донат", "support": "Поддержка", "ad": "Реклама",
             "courier_commission": "Комиссия курьера"}.get(payment.purpose, payment.purpose)
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
        raise herr(403, "Это не твоя поездка", "Был һинең сәфәрең түгел")
    # Списание бонуса под row-lock (как book()): два параллельных free-boost не потратят
    # один и тот же бонус дважды (иначе гонка read-modify-write → 2 бесплатных подъёма, кредиты в минус).
    locked = session.exec(select(User).where(User.id == user.id).with_for_update()).one()
    if locked.referral_credits < 1:
        raise herr(400, "Нет бонусов", "Бонус юҡ")
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
        raise herr(400, "Неизвестный тариф", "Билдәһеҙ тариф")
    ride = session.get(Ride, body.ride_id)
    if not ride:
        raise herr(404, "Поездка не найдена", "Сәфәр табылманы")
    if ride.driver_id != user.id:
        raise herr(403, "Поднять можно только свою поездку", "Тик үҙ сәфәреңде күтәреп була")
    if ride.status != RideStatus.active:
        raise herr(400, "Поездка неактивна", "Сәфәр актив түгел")
    # В проде mock = «оплата» без денег → не выдаём бесплатный boost.
    if settings.is_prod and settings.payments_provider == "mock":
        raise herr(503, "Оплата скоро будет доступна", "Түләү оҙаҡламай мөмкин буласаҡ")

    title, amount_kop, _hours = plan
    # Двойной тап / повтор после обрыва сети не должен плодить счета. Раньше каждый вызов
    # создавал НОВЫЙ платёж: человек видел три разных QR на одну поездку и мог заплатить
    # дважды (буст-то один), а админу прилетало три уведомления «поступил платёж»
    # (аудит 2026-08-08, волна 13 — проверено: 3 тапа = 3 счёта и 3 пинга).
    #
    # Возвращаем тот же неоплаченный счёт, если он свежий и ровно за это: та же поездка,
    # тот же тариф. Другой тариф — осознанный выбор человека, ему нужен новый счёт.
    fresh_since = utcnow() - timedelta(minutes=BOOST_PENDING_REUSE_MIN)
    payment = session.exec(
        select(Payment).where(
            Payment.user_id == user.id, Payment.purpose == "boost",
            Payment.ride_id == ride.id, Payment.tier == body.tier,
            Payment.status == "pending", Payment.created_at >= fresh_since,
        ).order_by(Payment.id.desc())
    ).first()
    reused = payment is not None
    if payment is None:
        payment = Payment(user_id=user.id, purpose="boost", ride_id=ride.id, tier=body.tier,
                          amount_kop=amount_kop)
        session.add(payment)
        session.commit()
        session.refresh(payment)

    # СБП-перевод по номеру: платёж висит pending, активирует админ после получения денег.
    if settings.payments_provider == "sbp_manual":
        if not reused:      # админа зовём один раз на счёт, а не на каждый тап
            _notify_new_payment(session, payment)
        return {
            "status": "pending", "method": "sbp_manual", "payment_id": payment.id,
            "amount": amount_kop // 100,
            "payee": {"phone": settings.sbp_phone, "bank": settings.sbp_bank, "name": settings.sbp_name},
        }

    # mock/yookassa. user.phone реальный (current_user не пускает плейсхолдер) → на него ЮKassa шлёт чек.
    if reused and payment.provider_id:
        # Счёт у провайдера уже заведён — второй раз не создаём, отдаём ту же ссылку на оплату.
        existing = fetch_payment(payment.provider_id)
        if existing and existing.get("confirmation_url"):
            return {"status": "pending", "method": "yookassa", "payment_id": payment.id,
                    "confirmation_url": existing["confirmation_url"]}
    res = _start_yookassa(session, payment, f"Юлдаш · {title}", user.phone)
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
        raise herr(400, "Сумма доната — от 10 до 100000 ₽", "Ярҙам суммаһы — 10-дан 100000 ₽-ға тиклем")
    if settings.is_prod and settings.payments_provider == "mock":
        raise herr(503, "Оплата скоро будет доступна", "Түләү оҙаҡламай мөмкин буласаҡ")

    payment = Payment(user_id=user.id, purpose="donate", amount_kop=amount * 100)
    session.add(payment)
    session.commit()
    session.refresh(payment)

    if settings.payments_provider == "sbp_manual":
        _notify_new_payment(session, payment)
        return {
            "status": "pending", "method": "sbp_manual", "payment_id": payment.id,
            "amount": amount,
            "payee": {"phone": settings.sbp_phone, "bank": settings.sbp_bank, "name": settings.sbp_name},
        }

    res = _start_yookassa(session, payment, "Юлдаш · донат", user.phone)
    payment.provider_id = res["provider_id"]
    session.add(payment)
    session.commit()
    if res["status"] == "succeeded":          # mock/dev — оплачено сразу
        _activate_payment(session, payment)     # для donate просто помечает succeeded (поездку не трогает)
        return {"status": "succeeded", "method": "yookassa", "payment_id": payment.id}
    return {"status": "pending", "method": "yookassa", "payment_id": payment.id, "confirmation_url": res["confirmation_url"]}


# ----------------------------- «Поддержать Юлдаш» (добровольная поддержка платформы) -----------------------------
# Пресеты берёт клиент (кнопки 20/50/100 ₽), но границы валидирует СЕРВЕР — клиенту не верим.
SUPPORT_MIN_KOP = 1000        # 10 ₽ — нижняя граница (символическая поддержка)
SUPPORT_MAX_KOP = 500_000     # 5000 ₽ — верхняя граница (защита от опечатки/фрода)


class SupportDonateIn(BaseModel):
    amount_kop: int   # сумма поддержки, целые копейки (деньги — только int)


@router.post("/support/donate")
def support_donate(body: SupportDonateIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """«Поддержать Юлдаш» — ДОБРОВОЛЬНАЯ поддержка платформы (не обязательная, не за проезд).

    Это доход платформы, а НЕ водителю: ledger НЕ трогаем (purpose=support → _activate_payment
    только помечает succeeded). Оплата через ту же ЮKassa-инфру, что boost/донат; без ключей —
    СБП-перевод по номеру (подтверждает админ). Идемпотентно на уровне денег: повторный webhook
    по тому же provider_id → no-op (payment уже succeeded), задвоения нет."""
    amount_kop = body.amount_kop
    if amount_kop < SUPPORT_MIN_KOP or amount_kop > SUPPORT_MAX_KOP:
        raise herr(400,
                   f"Сумма поддержки — от {SUPPORT_MIN_KOP // 100} до {SUPPORT_MAX_KOP // 100} ₽",
                   f"Ярҙам суммаһы — {SUPPORT_MIN_KOP // 100} һумдан {SUPPORT_MAX_KOP // 100} һумға тиклем")
    if settings.is_prod and settings.payments_provider == "mock":
        raise herr(503, "Оплата скоро будет доступна", "Түләү оҙаҡламай мөмкин буласаҡ")

    payment = Payment(user_id=user.id, purpose="support", amount_kop=amount_kop)
    session.add(payment)
    session.commit()
    session.refresh(payment)

    # СБП-перевод по номеру: платёж висит pending, подтверждает админ после получения денег.
    if settings.payments_provider == "sbp_manual":
        _notify_new_payment(session, payment)
        return {
            "status": "pending", "method": "sbp_manual", "payment_id": payment.id,
            "amount": amount_kop // 100,
            "payee": {"phone": settings.sbp_phone, "bank": settings.sbp_bank, "name": settings.sbp_name},
        }

    res = _start_yookassa(session, payment, "Юлдаш · поддержка платформы", user.phone)
    payment.provider_id = res["provider_id"]
    session.add(payment)
    session.commit()
    if res["status"] == "succeeded":          # mock/dev — оплачено сразу
        _activate_payment(session, payment)     # purpose=support → просто succeeded, ledger не трогаем
        return {"status": "succeeded", "method": "yookassa", "payment_id": payment.id}
    return {"status": "pending", "method": "yookassa", "payment_id": payment.id, "confirmation_url": res["confirmation_url"]}
@router.get("/payments/{payment_id}/status")
def payment_status(payment_id: int, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Статус СВОЕГО платежа — клиент поллит после возврата из браузера ЮKassa (ON_RESUME экрана).

    Подтверждение go-live не должно зависеть только от вебхука (он может задержаться/не дойти):
    если платёж ещё pending и провайдер yookassa — сами перепроверяем статус по своему
    provider_id (`fetch_payment`) и при успехе активируем (идемпотентно, как вебхук).
    Владелец — только сам плательщик (чужой платёж → 404, не раскрываем существование)."""
    payment = session.get(Payment, payment_id)
    if not payment or payment.user_id != user.id:
        raise herr(404, "Платёж не найден", "Түләү табылманы")
    # Дверей к «деньги пришли» две: вебхук и вот эта перепроверка. Отменённый платёж раньше
    # сюда не заходил вовсе — то есть если вебхук не дошёл (а он может), поздняя оплата
    # оставалась незамеченной и по второму пути тоже (аудит 2026-08-12, волна 26).
    if (payment.provider_id and settings.payments_provider == "yookassa"
            and payment.status not in ("succeeded", REFUND_DUE)):
        try:
            info = fetch_payment(payment.provider_id)   # перепроверка у ЮKassa (телу вебхука не доверяем)
        except Exception:  # noqa: BLE001 — сеть/ЮKassa недоступна → вернём текущий статус, клиент повторит
            info = None
        if info and info["status"] == "succeeded":
            if payment.status == "pending":
                _activate_payment(session, payment)
            else:
                _handle_unclaimed_payment(session, payment)   # применить не к чему → возврат
    boosted_until = None
    if payment.purpose == "boost" and payment.ride_id is not None:
        ride = session.get(Ride, payment.ride_id)
        boosted_until = ride.boosted_until if ride else None
    return {"payment_id": payment.id, "status": payment.status, "purpose": payment.purpose, "boosted_until": boosted_until}


# ----------------------------- Админ: подтверждение СБП-переводов -----------------------------
def _require_admin(user: User) -> None:
    if user.role != UserRole.admin:
        raise HTTPException(403, "Только для админа")


@router.get("/admin/payments/pending")
def admin_pending_payments(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Список ожидающих подтверждения платежей (СБП). Для админа.

    Платежи, созданные у провайдера (provider_id != ''), сюда НЕ попадают: их судьбу знает только
    вебхук/перепроверка ЮKassa. Иначе после флипа на yookassa в списке висели бы карточные pending,
    и случайный тап «подтвердить» начислил бы водителю деньги, которых не было."""
    _require_admin(user)
    rows = session.exec(select(Payment).where(
        Payment.status == "pending", Payment.provider_id == "",
    ).order_by(Payment.id.desc())).all()
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
        raise herr(404, "Платёж не найден", "Түләү табылманы")
    if payment.status == "succeeded":
        return {"payment_id": payment.id, "status": "succeeded"}
    # Карточный платёж (создан у провайдера) вручную не подтверждаем — его подтверждает вебхук
    # после реального списания. Ручной confirm здесь = начисление без денег (фантом в ledger).
    if payment.provider_id:
        raise herr(409, "Платёж у провайдера — подтвердится автоматически после оплаты", "Түләү провайдерҙа — түләгәс үҙе раҫлана")
    _activate_payment(session, payment)
    admin_action(user.id, "payment.confirm", payment_id=payment.id, user=payment.user_id,
                 amount_kop=getattr(payment, "amount_kop", None))
    return {"payment_id": payment.id, "status": "succeeded"}


@router.post("/admin/payments/{payment_id}/reject")
def admin_reject_payment(payment_id: int, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Отклонить платёж (деньги не пришли). Для админа."""
    _require_admin(user)
    payment = session.get(Payment, payment_id)
    if not payment:
        raise herr(404, "Платёж не найден", "Түләү табылманы")
    if payment.status == "pending":
        payment.status = "canceled"
        session.add(payment)
        session.commit()
        _tell_about_payment(session, payment, ok=False)   # молчащий отказ = человек ждёт вечно (волна 84)
    admin_action(user.id, "payment.reject", payment_id=payment.id, user=payment.user_id)
    return {"payment_id": payment.id, "status": payment.status}


@router.get("/admin/payments/summary")
def admin_payments_summary(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Сводка подтверждённых платежей (счётчик донатов/буста). Для админа."""
    _require_admin(user)

    def agg(purpose: str) -> dict:
        # Агрегируем в SQL (func.count/sum), не тянем все строки в Python — растущая таблица.
        cnt, total = session.exec(
            select(func.count(), func.coalesce(func.sum(Payment.amount_kop), 0))
            .where(Payment.purpose == purpose, Payment.status == "succeeded")
        ).one()
        return {"count": int(cnt), "sum_rub": int(total) // 100}

    return {"donate": agg("donate"), "boost": agg("boost"), "support": agg("support")}


@router.post("/payments/yookassa/webhook")
async def yookassa_webhook(request: Request, session: Session = Depends(get_session)):
    """Уведомление ЮKassa. Телу НЕ доверяем — по id перепроверяем статус через API ЮKassa."""
    # Вебхук релевантен ТОЛЬКО при активном yookassa. При mock/sbp_manual `fetch_payment`
    # возвращает succeeded без похода наружу → поддельный POST мог бы активировать чужой
    # pending-платёж (Boost/рекламу бесплатно). При sbp_manual платежи подтверждает админ в Telegram.
    if settings.payments_provider != "yookassa":
        return {"ok": True}
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
    if not payment or payment.status in ("succeeded", REFUND_DUE):
        return {"ok": True}
    try:
        info = fetch_payment(provider_id)   # верификация статуса у ЮKassa (телу не доверяем)
    except Exception:  # noqa: BLE001 — ошибка сети/ЮKassa → игнор (ЮKassa повторит вебхук)
        return {"ok": True}
    if info["status"] != "succeeded":
        return {"ok": True}
    if payment.status == "pending":
        _activate_payment(session, payment)
    else:
        # Деньги пришли, а применить их не к чему: платёж у нас уже отменён. Раньше здесь был
        # молчаливый выход — деньги оставались у платформы, и об этом не знал НИКТО (волна 26).
        _handle_unclaimed_payment(session, payment)
    return {"ok": True}
