"""Реферал «позови своего»: свой код, кто пригласил, бонусы (1 бонус = 1 бесплатное поднятие).
Виральность в тесной общине: пригласил соседа → оба получают бонус."""
from datetime import timedelta

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field
from sqlmodel import Session, select

from ..db import get_session
from ..models import (
    Booking, BookingStatus, InstantOrder, InstantOrderStatus, ReferralBonus, Ride, User,
)
from ..security import current_user, gen_referral_code
from ..timeutil import utcnow

router = APIRouter(tags=["referral"])

# Потолок ОСТАТКА бонусов на руках. Держит кошелёк в разумных рамках, но накрутку сам по себе
# НЕ закрывает: потратил бонусы — потолок освободился (проба волны 25: 50 приглашённых = 40 бонусов).
MAX_REFERRAL_CREDITS = 20

# Пожизненный потолок: сколько бонусов человек может получить КАК ПРИГЛАСИВШИЙ за всё время
# (аудит 2026-08-12, волна 25). Именно он закрывает ферму «новый номер → ввёл код → +1 бонус»:
# бонус = бесплатное поднятие поездки, то есть деньги, а цена одного фейк-аккаунта — цена
# виртуального номера. Считается у пригласившего, поэтому не обнуляется ни тратой бонусов,
# ни удалением приглашённых аккаунтов. Двадцать бесплатных поднятий — это уже очень много
# для честной виральности «позови соседа».
MAX_REFERRAL_BONUS_LIFETIME = 20

# --- B8-4: водительский бонус пригласившему — только за НАСТОЯЩЕГО водителя ---
# Анти-накрутка фейк-поездками (пара аккаунтов гоняет done туда-сюда): бонус выдаётся,
# когда приглашённый сделал ≥3 «живых» done-поездок с ≥3 РАЗНЫМИ пассажирами.
# «Живая» — есть реальное движение по имеющимся данным: такси — дистанция трека > 1 км
# ИЛИ длительность (onboard→done) > 5 мин; попутка — маршрут поездки длиннее 1 км.
DRIVER_BONUS_MIN_TRIPS = 3
DRIVER_BONUS_MIN_PASSENGERS = 3
DRIVER_BONUS_MONTHLY_CAP = 5        # ≤5 водительских бонусов на пригласившего в календарный месяц
LIVE_TRIP_MIN_KM = 1.0
LIVE_TRIP_MIN_MINUTES = 5


def grant_referral_credit(session: Session, referrer: User) -> bool:
    """Начислить пригласившему ОДИН бонус. Единственная точка начисления — обе двери зовут её.

    Два разных потолка, и они правда разные:
      * `MAX_REFERRAL_CREDITS` — сколько бонусов лежит на руках. Полный кошелёк не значит
        «хватит приглашать»: слот не сгорает, потратил — начислим следующий;
      * `MAX_REFERRAL_BONUS_LIFETIME` — сколько бонусов человек получил за всю жизнь. Вот он
        и закрывает ферму: 21-й приглашённый аккаунт бонуса пригласившему уже не приносит.

    Возвращает True, если бонус реально начислен (вызывающий решает, писать ли запись/пуш).
    Сессию НЕ коммитим — это делает вызывающий вместе со своими изменениями.
    """
    if int(referrer.referral_bonus_lifetime or 0) >= MAX_REFERRAL_BONUS_LIFETIME:
        return False
    if referrer.referral_credits >= MAX_REFERRAL_CREDITS:
        return False          # кошелёк полон: пожизненный слот не тратим впустую
    referrer.referral_credits += 1
    referrer.referral_bonus_lifetime = int(referrer.referral_bonus_lifetime or 0) + 1
    session.add(referrer)
    return True


def _live_driver_trips(session: Session, driver_id: int) -> tuple[int, set]:
    """Сколько «живых» done-поездок у водителя и с какими пассажирами (такси + попутка)."""
    from ..services import haversine_km   # локальный импорт: без циклов на старте
    live = 0
    passengers: set = set()
    orders = session.exec(select(InstantOrder).where(
        InstantOrder.driver_id == driver_id, InstantOrder.status == InstantOrderStatus.done,
    )).all()
    for o in orders:
        long_enough = (o.onboard_at is not None and o.done_at is not None
                       and o.done_at - o.onboard_at > timedelta(minutes=LIVE_TRIP_MIN_MINUTES))
        if o.distance_km > LIVE_TRIP_MIN_KM or long_enough:
            live += 1
            passengers.add(o.passenger_id)
    rides = {r.id: r for r in session.exec(select(Ride).where(Ride.driver_id == driver_id)).all()}
    if rides:
        bookings = session.exec(select(Booking).where(
            Booking.ride_id.in_(list(rides)), Booking.status == BookingStatus.done,
        )).all()
        for b in bookings:
            r = rides.get(b.ride_id)
            if (r and None not in (r.from_lat, r.from_lng, r.to_lat, r.to_lng)
                    and haversine_km(r.from_lat, r.from_lng, r.to_lat, r.to_lng) > LIVE_TRIP_MIN_KM):
                live += 1
                passengers.add(b.passenger_id)
    return live, passengers


def reward_driver_referral(session: Session, driver_id: int | None) -> bool:
    """Выдать пригласившему бонус за «раскатавшегося» приглашённого водителя (B8-4).

    Зовётся после каждого done (такси и попутка) — дёшево и идемпотентно:
    один бонус на приглашённого (unique) + месячный кэп на пригласившего.
    Накрутка той же парой пассажир↔водитель не проходит (нужны ≥3 РАЗНЫХ пассажира)."""
    if driver_id is None:
        return False
    driver = session.get(User, driver_id)
    if not driver or driver.referred_by is None:
        return False
    referrer = session.get(User, driver.referred_by)
    if not referrer:
        return False
    already = session.exec(select(ReferralBonus).where(
        ReferralBonus.invited_user_id == driver_id, ReferralBonus.kind == "driver",
    )).first()
    if already:
        return False
    now = utcnow()
    month_start = now.replace(day=1, hour=0, minute=0, second=0, microsecond=0)
    granted_this_month = len(session.exec(select(ReferralBonus.id).where(
        ReferralBonus.referrer_id == referrer.id,
        ReferralBonus.kind == "driver",
        ReferralBonus.created_at >= month_start,
    )).all())
    if granted_this_month >= DRIVER_BONUS_MONTHLY_CAP:
        return False
    live, passengers = _live_driver_trips(session, driver_id)
    if live < DRIVER_BONUS_MIN_TRIPS or len(passengers) < DRIVER_BONUS_MIN_PASSENGERS:
        return False
    if not grant_referral_credit(session, referrer):
        return False          # пожизненный потолок выбран или кошелёк полон — молча не начисляем
    session.add(ReferralBonus(referrer_id=referrer.id, invited_user_id=driver_id, kind="driver"))
    session.commit()
    from ..services import push_notification   # локальный импорт: тесты патчат services
    # Бонус = бесплатное поднятие поездки, то есть деньги. Пуш живёт секунды и не доходит
    # при выключенном телефоне — начисление должно остаться записью, к которой можно
    # вернуться (аудит 2026-08-12, волна 24). Ссылки нет намеренно: бонус не про один экран,
    # он лежит в профиле и тратится при поднятии любой поездки.
    push_notification(
        session, referrer.id, "referral",
        "Бонус за друга 🎉", "Дуҫ өсөн бонус 🎉",
        "Твой приглашённый водитель раскатался — держи бесплатное поднятие поездки!",
        "Саҡырған водителең ысынлап йөрөй башланы — бушлай күтәреү ал!",
    )
    return True


def _ensure_code(session: Session, user: User) -> str:
    """Лениво генерим уникальный код, если ещё нет (старые юзеры — без бэкфилл-миграции)."""
    if user.referral_code:
        return user.referral_code
    for _ in range(10):
        code = gen_referral_code()
        if not session.exec(select(User).where(User.referral_code == code)).first():
            user.referral_code = code
            session.add(user)
            session.commit()
            session.refresh(user)
            return code
    return user.referral_code


@router.get("/referral/me")
def referral_me(user: User = Depends(current_user), session: Session = Depends(get_session)):
    code = _ensure_code(session, user)
    invited = len(session.exec(select(User.id).where(User.referred_by == user.id)).all())
    return {
        "code": code,
        "invited": invited,                       # сколько привёл (бейдж «Позвал N»)
        "credits": user.referral_credits,         # бонусов = бесплатных поднятий
        "redeemed": user.referred_by is not None,  # уже ввёл чей-то код
    }


class RedeemIn(BaseModel):
    code: str = Field(..., max_length=12)


@router.post("/referral/redeem")
def referral_redeem(body: RedeemIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    # Row-lock себя: параллельные redeem двух кодов иначе оба проходят проверку referred_by is None
    # → двойной бонус себе + кредит двум реферерам (read-modify-write без лока).
    user = session.exec(select(User).where(User.id == user.id).with_for_update()).one()
    if user.referred_by is not None:
        raise HTTPException(400, "Код уже введён")
    code = body.code.strip().upper()
    if not code:
        raise HTTPException(400, "Нужен код")
    referrer = session.exec(select(User).where(User.referral_code == code).with_for_update()).first()
    if not referrer or referrer.id == user.id:
        raise HTTPException(400, "Код не найден")
    # Награда обоим: по 1 бонусу (бесплатное поднятие поездки), но с потолком на пользователя
    # (анти-накрутка взаимными редимами через новые аккаунты).
    user.referred_by = referrer.id
    user.referral_credits = min(user.referral_credits + 1, MAX_REFERRAL_CREDITS)
    # Пригласившему — через общую точку с пожизненным потолком. Приглашение при этом
    # засчитывается ВСЕГДА (`referred_by` выше): человек привёл друга, и статистика это видит,
    # даже если бонусов ему больше не положено.
    grant_referral_credit(session, referrer)
    session.add(user)
    session.commit()
    return {"ok": True, "credits": user.referral_credits}
