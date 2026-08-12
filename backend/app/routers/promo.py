"""M2 — промокоды и кампании (рычаг роста: именные коды для блогеров/партнёров/акций).

Философия (красные линии):
- Промокод = инструмент ПРИВЛЕЧЕНИЯ, а не прямой доход и НЕ штраф за отказ. Отказ ввести код
  ничего не ломает — попутка остаётся бесплатной, код НЕ вводит плату за попутку.
- Бонус пользователю только приятный: kind="boost" даёт водителю бесплатные поднятия поездки
  (referral_credits, тот же механизм и кэп, что в реферале B8); kind="welcome" — чистая атрибуция.
- Честная измеримость: блогеру платим «на результат» — за РЕАЛЬНО активных приведённых. Переиспользуем
  анти-фрод-критерий «живой поездки» из referral.py (не «просто скачал»).
- Один код на всю жизнь аккаунта (у юзера максимум одна PromoRedemption) — анти-абуз.
- Все пользовательские ошибки 4xx — двуязычные через herr(status, ru, ba).

Устройство повторяет стиль M1 (coupons.py): herr, идемпотентность, закрытый IDOR (чужая статистика → 404),
admin через _require_admin (обычный HTTPException-строка). Бонус boost начисляем через ту же логику,
что reward_driver_referral: user.referral_credits += perk_value с кэпом MAX_REFERRAL_CREDITS.
"""
from datetime import datetime, timedelta
from typing import Optional

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field
from sqlalchemy.exc import IntegrityError
from sqlmodel import Session, select

from .. import promo_ride
from ..db import get_session
from ..errors import herr
from ..models import (
    Booking, BookingStatus, InstantOrder, InstantOrderStatus, PromoCode, PromoRedemption,
    Ride, User, UserRole,
)
from ..security import current_user
from ..timeutil import utcnow
from .referral import LIVE_TRIP_MIN_KM, LIVE_TRIP_MIN_MINUTES, MAX_REFERRAL_CREDITS, _live_driver_trips

router = APIRouter(tags=["promo"])

# Допустимые типы бонуса пользователю.
# welcome — чистая атрибуция; boost — бесплатные поднятия поездки; taxi_ride — скидка в рублях
# на поездку в такси (её оплачивает платформа из своей комиссии, см. app/promo_ride.py).
_PROMO_KINDS = ("welcome", "boost", promo_ride.KIND)


# ---------- Тела запросов ----------

class ApplyIn(BaseModel):
    code: str = Field("", max_length=32)


class AdminPromoIn(BaseModel):
    code: str = Field("", max_length=32)
    title: str = Field("", max_length=120)
    description: str = Field("", max_length=2000)
    owner_phone: Optional[str] = Field(default=None, max_length=40)
    campaign: str = Field("", max_length=80)
    kind: str = Field("welcome", max_length=16)
    perk_value: int = 0
    limit_total: int = 0
    limit_per_user: int = 1
    valid_from: Optional[datetime] = None
    valid_until: Optional[datetime] = None


class AdminPromoEditIn(BaseModel):
    title: Optional[str] = Field(default=None, max_length=120)
    description: Optional[str] = Field(default=None, max_length=2000)
    campaign: Optional[str] = Field(default=None, max_length=80)
    perk_value: Optional[int] = None
    limit_total: Optional[int] = None
    limit_per_user: Optional[int] = None
    valid_from: Optional[datetime] = None
    valid_until: Optional[datetime] = None


class StatusIn(BaseModel):
    active: bool = True


# ---------- Хелперы ----------

# Окно действия кампании — общий хелпер из promo_ride: тем же правилом проверяется и скидка
# на такси (выключили/просрочили кампанию — гаснет и ещё не потраченная скидка).
_in_window = promo_ride.in_window


def _user_is_live(session: Session, user_id: int) -> bool:
    """«Живой» ли приведённый пользователь — сделал ≥1 РЕАЛЬНУЮ поездку (не «просто скачал»).

    Как пассажир: done-InstantOrder с движением (дистанция>KM ИЛИ длительность onboard→done>MIN)
    ИЛИ done-Booking на попутку длиннее KM. Как водитель: ≥1 «живая» done-поездка (переиспользуем
    _live_driver_trips из referral.py). Считается лениво в статистике — hot-path не трогаем."""
    # Пассажир — такси (InstantOrder).
    orders = session.exec(select(InstantOrder).where(
        InstantOrder.passenger_id == user_id, InstantOrder.status == InstantOrderStatus.done,
    )).all()
    for o in orders:
        long_enough = (o.onboard_at is not None and o.done_at is not None
                       and o.done_at - o.onboard_at > timedelta(minutes=LIVE_TRIP_MIN_MINUTES))
        if o.distance_km > LIVE_TRIP_MIN_KM or long_enough:
            return True
    # Пассажир — попутка (Booking + маршрут Ride длиннее KM).
    from ..services import haversine_km   # локальный импорт: без циклов на старте
    bookings = session.exec(select(Booking).where(
        Booking.passenger_id == user_id, Booking.status == BookingStatus.done,
    )).all()
    if bookings:
        ride_ids = list({b.ride_id for b in bookings})
        rides = {r.id: r for r in session.exec(select(Ride).where(Ride.id.in_(ride_ids))).all()}
        for b in bookings:
            r = rides.get(b.ride_id)
            if (r and None not in (r.from_lat, r.from_lng, r.to_lat, r.to_lng)
                    and haversine_km(r.from_lat, r.from_lng, r.to_lat, r.to_lng) > LIVE_TRIP_MIN_KM):
                return True
    # Водитель — ≥1 «живая» поездка.
    live, _ = _live_driver_trips(session, user_id)
    return live >= 1


def _promo_counts(session: Session, promo: PromoCode) -> tuple[int, int]:
    """(applied, active): всего применивших код и сколько из них — РЕАЛЬНО активные («живые»)."""
    reds = session.exec(select(PromoRedemption).where(PromoRedemption.promo_id == promo.id)).all()
    applied = len(reds)
    active = sum(1 for r in reds if _user_is_live(session, r.user_id))
    return applied, active


def _apply_message(promo: PromoCode) -> tuple[str, str]:
    """Дружелюбное двуязычное сообщение об активации (бонус/приветствие)."""
    disc_kop = promo_ride.granted_kop(promo)
    if disc_kop > 0:
        rub = disc_kop // 100
        return (
            f"Код принят — {rub} ₽ скидки на поездку в такси 🚕 Она сработает сама при заказе.",
            f"Код ҡабул ителде — таксиға {rub} һум ташлама 🚕 Заказ биргәндә үҙе эшләй.",
        )
    if promo.kind == "boost" and promo.perk_value > 0:
        return (
            f"Код принят — тебе начислено {promo.perk_value} бесплатных поднятий поездки 🎉",
            f"Код ҡабул ителде — һиңә {promo.perk_value} бушлай күтәреү өҫтәлде 🎉",
        )
    return (
        "Код принят — добро пожаловать в Юлдаш! 🚗",
        "Код ҡабул ителде — Юлдашҡа рәхим ит! 🚗",
    )


# ---------- Пользователь ----------

@router.post("/promo/apply")
def promo_apply(body: ApplyIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Применить промокод. Один код на всю жизнь аккаунта. Отказ ничего не ломает — попутка бесплатна.

    Порядок валидации с точными кодами:
    нет/выключен → 404; истёк по окну дат → 422; уже активировал любой код → 409;
    свой же код → 409; общий лимит исчерпан → 409. Успех → начисление бонуса + PromoRedemption."""
    now = utcnow()
    code = (body.code or "").strip().upper()
    promo = None
    if code:
        # V3: row-lock на промокоде сериализует параллельные активации — иначе двойной POST одним
        # юзером с одним кодом проходит проверку «уже активировал» дважды (двойной бонус + счётчик).
        promo = session.exec(select(PromoCode).where(PromoCode.code == code).with_for_update()).first()
    # Существование и активность кампании — не раскрываем детали, общий 404.
    if not promo or not promo.active:
        raise herr(404, "Промокод не найден", "Промокод табылманы")
    # Окно дат: истёк / ещё не начался → 422 (код есть, но не в силе).
    if not _in_window(promo, now):
        raise herr(422, "Срок промокода истёк", "Промокод ваҡыты үтте")
    # Один код на всю жизнь аккаунта.
    already = session.exec(select(PromoRedemption).where(PromoRedemption.user_id == user.id)).first()
    if already:
        raise herr(409, "Ты уже активировал промокод", "Һин промокодты активлаштырҙың инде")
    # Нельзя активировать свой же код.
    if promo.owner_id is not None and promo.owner_id == user.id:
        raise herr(409, "Свой код активировать нельзя", "Үҙ кодыңды активлаштырып булмай")
    # Общий лимит.
    if promo.limit_total > 0 and promo.redeemed_count >= promo.limit_total:
        raise herr(409, "Промокод исчерпан", "Промокод бөттө")

    # Начисление бонуса. boost → бесплатные поднятия (referral_credits) с общим кэпом;
    # taxi_ride → скидка на поездку в такси (сумма фиксируется здесь и живёт на PromoRedemption,
    # чтобы правка кампании задним числом не меняла уже данное человеку обещание);
    # welcome → чистая атрибуция.
    if promo.kind == "boost" and promo.perk_value > 0:
        user.referral_credits = min(user.referral_credits + promo.perk_value, MAX_REFERRAL_CREDITS)
        session.add(user)
    discount_kop = promo_ride.granted_kop(promo)
    promo.redeemed_count += 1
    session.add(promo)
    session.add(PromoRedemption(promo_id=promo.id, user_id=user.id, discount_kop=discount_kop))
    try:
        session.commit()
    except IntegrityError:   # гонка: параллельный запрос уже активировал код у этого юзера (UNIQUE)
        session.rollback()
        raise herr(409, "Ты уже активировал промокод", "Һин промокодты активлаштырҙың инде")

    msg_ru, msg_ba = _apply_message(promo)
    return {
        "ok": True,
        "kind": promo.kind,
        "perk_value": promo.perk_value,
        # Скидка на поездку в такси, копейки (0 у welcome/boost) — клиент сразу знает, что обещать.
        "discount_kop": discount_kop,
        "message_ru": msg_ru,
        "message_ba": msg_ba,
    }


@router.get("/promo/mine")
def promo_mine(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Мой применённый промокод (один на жизнь) или {promo: null}.

    Для скидки на такси показываем ещё и её судьбу: цела ли она и на каком заказе потрачена —
    иначе человек не понимает, почему в цене скидки нет (потратил) или почему она вернулась
    (поездка не состоялась)."""
    red = session.exec(select(PromoRedemption).where(PromoRedemption.user_id == user.id)).first()
    if not red:
        return {"promo": None}
    promo = session.get(PromoCode, red.promo_id)
    if not promo:
        return {"promo": None}
    avail, _ = promo_ride.available(session, user.id)
    return {
        "promo": {
            "code": promo.code,
            "title": promo.title,
            "kind": promo.kind,
            "perk_value": promo.perk_value,
        },
        "redeemed_at": red.redeemed_at.isoformat() if red.redeemed_at else None,
        # Скидка на поездку в такси (копейки). available=false + used_order_id=null →
        # кампанию выключили или срок вышел.
        "discount_kop": int(red.discount_kop or 0),
        "discount_available": avail is not None,
        "discount_used_order_id": red.used_order_id,
    }


# ---------- Владелец кампании / блогер ----------

@router.get("/promo/{code}/stats")
def promo_stats(code: str, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Статистика по коду. Доступ — только владелец кода ИЛИ админ (чужой → 404, не раскрываем).

    applied = сколько всего применили код; active = сколько из них РЕАЛЬНО активны («живая поездка»),
    именно по active платим блогеру «на результат»."""
    norm = (code or "").strip().upper()
    promo = session.exec(select(PromoCode).where(PromoCode.code == norm)).first() if norm else None
    is_admin = user.role == UserRole.admin
    if not promo or (not is_admin and promo.owner_id != user.id):
        raise herr(404, "Промокод не найден", "Промокод табылманы")
    applied, active = _promo_counts(session, promo)
    issued = int(promo.redeemed_count or 0)
    return {
        "code": promo.code,
        "title": promo.title,
        "campaign": promo.campaign,
        # issued — сколько раз код ВЫДАН (и списан с бюджета кампании), applied — сколько
        # применивших ещё существует. Числа расходятся, когда аккаунт удалили: строка о выдаче
        # уходит вместе с ним, а бюджет уже потрачен. Большой разрыв = чью-то ферму видно
        # невооружённым глазом (аудит 2026-08-12, волна 25).
        "issued": issued,
        "applied": applied,
        "active": active,
        "vanished": max(issued - applied, 0),
    }


# ---------- Админ ----------
# Админские ошибки — обычный HTTPException-строка (двуязычие требуется только для 4xx пользователю).

def _require_admin(user: User) -> None:
    if user.role != UserRole.admin:
        raise HTTPException(403, "Только для админа")


def _promo_admin(promo: PromoCode, session: Session) -> dict:
    """Карточка кампании для админа (со счётчиками applied/active)."""
    applied, active = _promo_counts(session, promo)
    return {
        "id": promo.id,
        "code": promo.code,
        "title": promo.title,
        "description": promo.description,
        "owner_id": promo.owner_id,
        "campaign": promo.campaign,
        "kind": promo.kind,
        "perk_value": promo.perk_value,
        "limit_total": promo.limit_total,
        "limit_per_user": promo.limit_per_user,
        "redeemed_count": promo.redeemed_count,
        "applied": applied,
        "active": active,
        # Сколько применивших исчезло вместе с аккаунтами: бюджет кампании потрачен,
        # а спросить уже не с кого. Признак накрутки «удалил аккаунт — взял скидку заново».
        "vanished": max(int(promo.redeemed_count or 0) - applied, 0),
        "valid_from": promo.valid_from.isoformat() if promo.valid_from else None,
        "valid_until": promo.valid_until.isoformat() if promo.valid_until else None,
        "active_flag": promo.active,
        "created_at": promo.created_at.isoformat() if promo.created_at else None,
    }


@router.post("/admin/promo")
def admin_promo_create(body: AdminPromoIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Создать промокод/кампанию. code → upper, уникальность (409). owner_phone (опц.) привязывает
    к блогеру (нашли по phone → owner_id; нет — общая акция Юлдаша).

    kind ∈ {welcome, boost, taxi_ride}. Для taxi_ride perk_value — РУБЛИ скидки на поездку в
    такси; выше потолка promo_ride_max_discount_rub она всё равно не выдастся (и не больше
    доли promo_ride_max_price_share от цены конкретной поездки)."""
    _require_admin(user)
    code = (body.code or "").strip().upper()
    if not code:
        raise herr(422, "Нужен код промокода", "Промокод кәрәк")
    kind = (body.kind or "welcome").strip()
    if kind not in _PROMO_KINDS:
        raise HTTPException(422, "Недопустимый тип бонуса (welcome|boost|taxi_ride)")
    exists = session.exec(select(PromoCode).where(PromoCode.code == code)).first()
    if exists:
        raise herr(409, "Такой код уже есть", "Ундай код бар инде")
    owner_id = None
    phone = (body.owner_phone or "").strip()
    if phone:
        owner = session.exec(select(User).where(User.phone == phone)).first()
        owner_id = owner.id if owner else None
    promo = PromoCode(
        code=code,
        title=body.title.strip(),
        description=body.description.strip(),
        owner_id=owner_id,
        campaign=body.campaign.strip(),
        kind=kind,
        perk_value=max(0, body.perk_value),
        limit_total=max(0, body.limit_total),
        # Всегда 1: код даётся ОДИН РАЗ НА ЖИЗНЬ аккаунта (UNIQUE(user_id) в PromoRedemption),
        # и никакое другое число сервер выполнить не может. Раньше поле принималось как есть
        # и врало: админ ставил «5 на человека», а второй раз код не принимался вовсе
        # (аудит 2026-08-12, волна 27 — «мёртвая настройка выглядит работающей»).
        limit_per_user=1,
        valid_from=body.valid_from,
        valid_until=body.valid_until,
        active=True,
    )
    session.add(promo)
    session.commit()
    session.refresh(promo)
    return _promo_admin(promo, session)


@router.get("/admin/promo")
def admin_promo_list(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Все промокоды со счётчиками applied/active, новые сверху."""
    _require_admin(user)
    rows = session.exec(select(PromoCode).order_by(PromoCode.id.desc())).all()
    return [_promo_admin(p, session) for p in rows]


@router.post("/admin/promo/{promo_id}/status")
def admin_promo_status(promo_id: int, body: StatusIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Вкл/выкл кампанию (active)."""
    _require_admin(user)
    promo = session.get(PromoCode, promo_id)
    if not promo:
        raise herr(404, "Промокод не найден", "Промокод табылманы")
    promo.active = bool(body.active)
    session.add(promo)
    session.commit()
    session.refresh(promo)
    return _promo_admin(promo, session)


@router.post("/admin/promo/{promo_id}")
def admin_promo_update(promo_id: int, body: AdminPromoEditIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Правка кампании (title/description/campaign/perk_value/лимиты/срок). Только переданные поля."""
    _require_admin(user)
    promo = session.get(PromoCode, promo_id)
    if not promo:
        raise herr(404, "Промокод не найден", "Промокод табылманы")
    if body.title is not None:
        promo.title = body.title.strip()
    if body.description is not None:
        promo.description = body.description.strip()
    if body.campaign is not None:
        promo.campaign = body.campaign.strip()
    if body.perk_value is not None:
        promo.perk_value = max(0, body.perk_value)
    if body.limit_total is not None:
        promo.limit_total = max(0, body.limit_total)
    # `limit_per_user` осознанно НЕ правим: см. создание кампании — на человека код всегда один.
    if body.valid_from is not None:
        promo.valid_from = body.valid_from
    if body.valid_until is not None:
        promo.valid_until = body.valid_until
    session.add(promo)
    session.commit()
    session.refresh(promo)
    return _promo_admin(promo, session)
