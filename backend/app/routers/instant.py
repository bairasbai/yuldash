"""«Быстрый заказ» (такси-режим, Фаза 2): presence, оценка цены, заказ + машина состояний.

Отдельный поток от плановых поездок (Ride/Booking) — тот не трогаем.
Приватность: координаты не логируем; телефоны сторон — только после accept.
"""
from datetime import datetime, timedelta, timezone
from typing import Literal, Optional

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field
from sqlmodel import Session, select

from ..db import get_session
from ..errors import herr
from ..models import DriverProfile, InstantOrder, InstantOrderStatus as S, Rating, Settlement, User
from ..security import current_user
from ..timeutil import utcnow
from ..services import user_rating
from .referral import reward_driver_referral
from .. import debt as debt_mod
from .. import geo as geo_mod
from .. import instant_service as isv
from .. import quality as quality_mod
from .. import taxi as taxi_mod
from .. import workday as workday_mod

router = APIRouter(tags=["instant"])


def _guard_taxi_not_blocked(session: Session, driver_id: int) -> None:
    """Долг по комиссии просрочен / выше порога → водитель НЕ может возить такси.
    ПОПУТКА (плановые Ride/Booking) этим не затрагивается — там своего долга нет."""
    if debt_mod.taxi_block_reason(session, driver_id) is not None:
        raise HTTPException(403, debt_mod.TAXI_BLOCKED_MSG)


def _guard_taxi_available(session: Session, lat: float | None = None, lng: float | None = None) -> None:
    """Гейт (a), волна 2: такси выключено глобально (taxi_enabled=False) или в этом городе
    (список TaxiCity) → 403 «Такси скоро». Применяется к ПАССАЖИРСКИМ ручкам (estimate,
    создание заказа) и к водительским. ПОПУТКА (rides/bookings) не затрагивается."""
    av = taxi_mod.availability(session, lat, lng)
    if not av["enabled"]:
        raise herr(403, av["message"]["ru"], av["message"].get("ba", av["message"]["ru"]))


def _guard_taxi_driver(session: Session, driver_id: int, lat: float | None = None, lng: float | None = None) -> None:
    """Водительские ручки такси: (a) флаг/город + (b) одобренная заявка таксиста (580-ФЗ) +
    долг + отдых (волна 2 §8) + пауза качества (§9: жалобы). Всё блокирует ТОЛЬКО такси
    (presence/offer/accept), попутка работает; активный заказ НЕ рубится —
    arrived/onboard/done через этот гейт не ходят."""
    _guard_taxi_available(session, lat, lng)
    if not taxi_mod.is_approved_taxi_driver(session, driver_id):
        raise HTTPException(403, taxi_mod.TAXI_NOT_APPROVED_MSG)
    _guard_taxi_not_blocked(session, driver_id)
    workday_mod.guard_taxi_rested(session, driver_id)
    quality_mod.guard_taxi_quality(session, driver_id)


# ------------------------------ схемы ------------------------------
class EstimateIn(BaseModel):
    from_lat: float = Field(..., ge=-90, le=90)
    from_lng: float = Field(..., ge=-180, le=180)
    to_lat: float = Field(..., ge=-90, le=90)
    to_lng: float = Field(..., ge=-180, le=180)
    from_text: str = Field("", max_length=200)
    to_text: str = Field("", max_length=200)
    # Классы (§6): standard = Эконом, comfort = Комфорт (авто новее/чище, тариф дороже).
    category: Literal["standard", "comfort"] = "standard"
    # ВНИМАНИЕ: поля цены здесь НЕТ намеренно — сервер считает сам, клиенту не верим.


class OrderIn(EstimateIn):
    pass


class PresenceIn(BaseModel):
    lat: float = Field(..., ge=-90, le=90)
    lng: float = Field(..., ge=-180, le=180)


class CancelIn(BaseModel):
    reason: str = Field("", max_length=200)


class ZoneIn(BaseModel):
    work_zone: Literal["city", "intercity", "region"]
    work_city: Optional[str] = Field(None, max_length=100)
    work_direction_id: Optional[int] = None


# ------------------------------ зона работы (волна 2, география) ------------------------------
def _zone_payload(session: Session, dp: Optional[DriverProfile]) -> dict:
    direction = None
    if dp is not None and dp.work_direction_id is not None:
        s = session.get(Settlement, dp.work_direction_id)
        direction = geo_mod.settlement_payload(s) if s else None
    return {
        "work_zone": dp.work_zone if dp else None,
        "work_city": dp.work_city if dp else None,
        "work_direction_id": dp.work_direction_id if dp else None,
        "work_direction": direction,
    }


@router.get("/instant/zone")
def get_zone(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Текущая зона работы таксиста (показ в кабинете рядом с тумблером «на линии»)."""
    dp = session.exec(select(DriverProfile).where(DriverProfile.user_id == user.id)).first()
    return _zone_payload(session, dp)


@router.post("/instant/zone")
def set_zone(body: ZoneIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Выбор зоны: 🏙 мой город / 🛣 межгород (+опц. направление) / 🌍 соседний регион.
    Только водитель с одобренной заявкой таксиста (580-ФЗ). Влияет ТОЛЬКО на такси-matcher,
    попутка (Ride/Booking) не затрагивается."""
    dp = session.exec(select(DriverProfile).where(DriverProfile.user_id == user.id)).first()
    if not dp:
        raise HTTPException(409, "Сначала стань водителем (профиль водителя не найден)")
    if not taxi_mod.is_approved_taxi_driver(session, user.id):
        raise HTTPException(403, taxi_mod.TAXI_NOT_APPROVED_MSG)
    direction_id = body.work_direction_id
    if body.work_zone == "city":
        direction_id = None                      # направление имеет смысл только для межгорода
    if direction_id is not None and session.get(Settlement, direction_id) is None:
        raise HTTPException(404, "Направление не найдено в справочнике")
    work_city = (body.work_city or "").strip() or None
    dp.work_zone = body.work_zone
    dp.work_city = work_city if body.work_zone == "city" else None
    dp.work_direction_id = direction_id
    session.add(dp)
    session.commit()
    session.refresh(dp)
    return _zone_payload(session, dp)


# ------------------------------ смена / отдых (волна 2, §8) ------------------------------
@router.get("/instant/workday")
def get_workday(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Сводка смены таксиста для кабинета: сколько на линии, сколько осталось, блок отдыха,
    когда разблокировка, использован ли «один попутчик домой» + дашборд (заработок/заказы
    за сегодня, текущая ступень комиссии)."""
    s = workday_mod.summary(session, user.id)
    s.update(debt_mod.driver_dashboard(session, user.id))
    return s


@router.get("/driver/earnings")
def driver_earnings_ep(period: str = "week", user: User = Depends(current_user),
                       session: Session = Depends(get_session)):
    """История заработка водителя за период (week|month|all): суммарно + разбивка по дням.
    Только СВОИ данные (по токену). Считаем SQL-агрегатом, база — цена завершённых
    такси-заказов (как «заработок за сегодня»)."""
    return debt_mod.driver_earnings(session, user.id, period)


@router.get("/instant/demand")
def instant_demand(city: Optional[str] = None, user: User = Depends(current_user),
                   session: Session = Depends(get_session)):
    """Карта спроса для водителя: АНОНИМНЫЕ тепловые зоны «где сейчас ищут такси».
    Только агрегаты (зоны огрублены до ~1 км), без личности/телефонов/конкретных заказов.
    Источник — те же активные поиски, что и surge (переиспользуем, не дублируем сбор).
    Доступ — одобренный таксист (роль водителя). Такси выключено в зоне → зона в ответ
    не попадает; выключенный город → пустой zones + честный updated_at."""
    if not taxi_mod.is_approved_taxi_driver(session, user.id):
        raise HTTPException(403, taxi_mod.TAXI_NOT_APPROVED_MSG)
    return isv.demand_zones(session, city)


@router.get("/instant/nearby-drivers")
def nearby_drivers_ep(lat: float, lng: float, user: User = Depends(current_user)):
    """Свободные машины «на линии» рядом с пассажиром — АНОНИМНЫЕ точки на карте + ≈ETA
    до подачи (для карты в режиме такси). Только реальные presence-данные, без личности
    водителя (ни id, ни телефона). Нет Redis → пустой список (карта просто без машинок)."""
    if not (-90.0 <= lat <= 90.0 and -180.0 <= lng <= 180.0):
        raise HTTPException(400, "Некорректные координаты")
    return {"drivers": isv.nearby_drivers(lat, lng)}


# ------------------------------ presence ------------------------------
@router.post("/instant/presence")
def presence(body: PresenceIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Heartbeat координат водителя «на линии» → Redis GEO. Только для онлайн-водителя.
    Координаты в БД/логи не пишем — только эфемерно в Redis (TTL сам чистит)."""
    dp = session.exec(select(DriverProfile).where(DriverProfile.user_id == user.id)).first()
    if not dp or not dp.online:
        raise herr(409, "Сначала включи «Я на линии»", "Башта «Мин линияла»-ны ҡабыҙ")
    _guard_taxi_driver(session, user.id, body.lat, body.lng)   # флаг/город + заявка таксиста + долг + отдых
    ok = isv.presence_heartbeat(user.id, body.lat, body.lng)
    # Учёт смены (§8): +интервал от прошлого пинга (кэп ≤ workday_step_cap_sec),
    # предупреждения ≤60/≤15 мин, на лимите — limit_reached_at (следующий presence → 403).
    wd = workday_mod.record_heartbeat(session, user.id)
    return {
        "ok": ok, "ttl_sec": isv.settings.presence_ttl_sec,
        "shift_seconds_online": wd.seconds_online,
        "shift_remaining_sec": max(0, workday_mod.shift_limit_sec() - wd.seconds_online),
    }


# ------------------------------ оценка цены ------------------------------
@router.post("/instant/estimate")
def estimate(body: EstimateIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Оценка цены ДО заказа. Сервер считает сам (haversine × road_k) — цена из клиента игнорируется."""
    _guard_taxi_available(session, body.from_lat, body.from_lng)   # пассажиру — только гейт (a)
    return isv.estimate(session, (body.from_lat, body.from_lng), (body.to_lat, body.to_lng), body.category)


# ------------------------------ заказ ------------------------------
@router.post("/instant/orders")
def create_order(body: OrderIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Создать быстрый заказ: сервер считает цену → matcher ищет и предлагает ближайшему водителю.
    Нет свободных/нет Redis → заказ сразу expired («рядом никого»), но запрос не падает.
    Сурж фиксируется на заказе (price_estimate уже с ним). Страйки (§5, Модель А):
    ≥3 платные отмены/no-show за 7 дней → такси-заказы на паузе 24 ч (попутка работает)."""
    _guard_taxi_available(session, body.from_lat, body.from_lng)   # пассажиру — только гейт (a)
    # Страйки §5 + resolved-жалобы no_show/unpaid/damage §9 — общий счётчик (попутка работает).
    if quality_mod.passenger_pause_until(session, user.id) is not None:
        raise HTTPException(403, isv.strike_pause_message())
    est = isv.estimate(session, (body.from_lat, body.from_lng), (body.to_lat, body.to_lng), body.category)
    # Не даём плодить параллельные активные заказы одному пассажиру (двойной тап/спам).
    existing = session.exec(
        select(InstantOrder).where(
            InstantOrder.passenger_id == user.id,
            InstantOrder.status.in_([S.created, S.searching, S.offered, S.accepted, S.arriving, S.onboard]),
        )
    ).first()
    if existing:
        return isv.order_payload(session, existing, user)
    order = InstantOrder(
        passenger_id=user.id,
        from_lat=body.from_lat, from_lng=body.from_lng,
        to_lat=body.to_lat, to_lng=body.to_lng,
        from_text=body.from_text, to_text=body.to_text,
        category=body.category,
        price_estimate=est["price"], distance_km=est["distance_km"],
        eta_min=est["eta_min"], tariff_id=est["tariff_id"],
        surge_k=est["surge_k"],
    )
    session.add(order)
    session.commit()
    session.refresh(order)
    order = isv.start_matching(session, order)   # created → searching → offered|expired
    return isv.order_payload(session, order, user)


# ------------------------------ предзаказ «на время» (MVP) ------------------------------
class ScheduleIn(OrderIn):
    # Время подачи в будущем (iso с таймзоной или naive-UTC). Обязательно для предзаказа.
    scheduled_at: datetime


def _parse_scheduled_at(dt: datetime) -> datetime:
    """Нормализуем время подачи в aware-UTC и валидируем горизонт: не в прошлом,
    не дальше scheduled_max_days вперёд. Naive-время трактуем как UTC (клиент шлёт iso-UTC)."""
    if dt.tzinfo is not None:
        dt = dt.astimezone(timezone.utc).replace(tzinfo=None)
    now = utcnow()
    if dt <= now:
        raise herr(422, "Время подачи должно быть в будущем",
                   "Килеү ваҡыты киләсәктә булырға тейеш")
    if dt > now + timedelta(days=isv.settings.scheduled_max_days):
        d = isv.settings.scheduled_max_days
        raise herr(422, f"Предзаказ можно оформить максимум на {d} суток вперёд",
                   f"Алдан заказды иң күбендә {d} тәүлеккә алдан бирергә була")
    return dt


@router.post("/instant/schedule")
def create_scheduled(body: ScheduleIn, user: User = Depends(current_user),
                     session: Session = Depends(get_session)):
    """Оформить предзаказ такси «на время». Заказ создаётся в статусе `scheduled` и НЕ уходит
    в поиск сразу — активируется ко времени подачи (клиент вызывает /activate, либо ленивая
    авто-активация при GET /instant/scheduled). Цену показываем как предварительную оценку
    (сурж фиксируется НЕ сейчас, а на момент активации). Гейт (a): такси доступно в этом городе."""
    _guard_taxi_available(session, body.from_lat, body.from_lng)   # пассажиру — только гейт (a)
    if quality_mod.passenger_pause_until(session, user.id) is not None:
        raise HTTPException(403, isv.strike_pause_message())
    when = _parse_scheduled_at(body.scheduled_at)
    est = isv.estimate(session, (body.from_lat, body.from_lng), (body.to_lat, body.to_lng), body.category)
    order = InstantOrder(
        passenger_id=user.id, status=S.scheduled, scheduled_at=when,
        from_lat=body.from_lat, from_lng=body.from_lng,
        to_lat=body.to_lat, to_lng=body.to_lng,
        from_text=body.from_text, to_text=body.to_text,
        category=body.category,
        price_estimate=est["price"], distance_km=est["distance_km"],
        eta_min=est["eta_min"], tariff_id=est["tariff_id"], surge_k=est["surge_k"],
    )
    session.add(order)
    session.commit()
    session.refresh(order)
    return isv.order_payload(session, order, user)


@router.get("/instant/scheduled")
def my_scheduled(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Мои будущие предзаказы (только СВОИ). Ленивая авто-активация (ограничение MVP —
    без фонового шедулера): у кого время подачи уже наступило → переводим в обычный поиск
    прямо здесь. Возврат: {scheduled: [ещё ждут], activated: [только что запустились в поиск]}."""
    rows = session.exec(
        select(InstantOrder).where(
            InstantOrder.passenger_id == user.id,
            InstantOrder.status == S.scheduled,
        ).order_by(InstantOrder.scheduled_at.asc())
    ).all()
    now = utcnow()
    scheduled, activated = [], []
    for o in rows:
        if o.scheduled_at is not None and o.scheduled_at <= now:
            activated.append(isv.order_payload(session, isv.activate_scheduled(session, o), user))
        else:
            scheduled.append(isv.order_payload(session, o, user))
    return {"scheduled": scheduled, "activated": activated}


@router.post("/instant/scheduled/{order_id}/activate")
def activate_scheduled_ep(order_id: int, user: User = Depends(current_user),
                          session: Session = Depends(get_session)):
    """Активировать предзаказ ко времени (клиент вызывает, когда время подошло): scheduled →
    поиск водителя. Только СВОЙ предзаказ. Не-scheduled (уже активирован/отменён) → 409."""
    order = session.get(InstantOrder, order_id)
    if not order or order.passenger_id != user.id:
        raise HTTPException(404, "Предзаказ не найден")
    if order.status != S.scheduled:
        raise herr(409, "Предзаказ уже активирован или отменён",
                   "Алдан заказ инде әүҙемләштерелгән йәки кире алынған")
    order = isv.activate_scheduled(session, order)
    return isv.order_payload(session, order, user)


@router.post("/instant/scheduled/{order_id}/cancel")
def cancel_scheduled(order_id: int, user: User = Depends(current_user),
                     session: Session = Depends(get_session)):
    """Отменить предзаказ (пока он ещё `scheduled`). Только СВОЙ. Штрафов нет — до поиска."""
    order = session.get(InstantOrder, order_id)
    if not order or order.passenger_id != user.id:
        raise HTTPException(404, "Предзаказ не найден")
    order = isv.cancel_order(session, order_id, isv.Actor.passenger, user.id, "scheduled_cancel")
    return isv.order_payload(session, order, user)


def _order_for_view(session: Session, order_id: int, user: User) -> InstantOrder:
    order = session.get(InstantOrder, order_id)
    if not order:
        raise HTTPException(404, "Заказ не найден")
    if user.id not in (order.passenger_id, order.driver_id, order.current_offer_driver_id):
        raise HTTPException(403, "Нет доступа к заказу")
    return order


@router.get("/instant/orders/mine")
def my_orders(limit: int = 20, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Заказы пассажира (свежие сверху) — для экрана статуса/истории."""
    rows = session.exec(
        select(InstantOrder).where(InstantOrder.passenger_id == user.id)
        .order_by(InstantOrder.id.desc()).limit(max(1, min(limit, 100)))
    ).all()
    return [isv.order_payload(session, o, user) for o in rows]


@router.get("/instant/driver/offer")
def driver_offer(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Активный оффер для водителя (поллинг-фолбэк к пушу). Протухший — сам двигается дальше."""
    if not taxi_mod.is_approved_taxi_driver(session, user.id):
        return {"offer": None}   # гейт (b): нет одобренной заявки таксиста — офферов нет
    if debt_mod.taxi_block_reason(session, user.id) is not None:
        return {"offer": None}   # заблокирован долгом — офферы такси не показываем
    if workday_mod.blocking_workday(session, user.id) is not None:
        return {"offer": None}   # отдых (§8): 8ч на линии — офферы не показываем до разблокировки
    if quality_mod.taxi_pause_until(session, user.id) is not None:
        return {"offer": None}   # пауза качества (§9: жалобы) — офферы такси не показываем
    order = session.exec(
        select(InstantOrder).where(
            InstantOrder.current_offer_driver_id == user.id,
            InstantOrder.status == S.offered,
        ).order_by(InstantOrder.id.desc())
    ).first()
    if not order:
        return {"offer": None}
    order = isv.reconcile_offer(session, order)
    if order.status != S.offered or order.current_offer_driver_id != user.id:
        return {"offer": None}
    # Гейт (a) по точке подачи: такси выключено глобально/в этом городе → оффер не показываем.
    if not taxi_mod.availability(session, order.from_lat, order.from_lng)["enabled"]:
        return {"offer": None}
    return {"offer": isv.order_payload(session, order, user)}


@router.get("/instant/orders/{order_id}")
def get_order(order_id: int, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Детали заказа. По пути разрешаем протухший оффер (ленивый таймаут без фонового воркера)."""
    order = _order_for_view(session, order_id, user)
    order = isv.reconcile_offer(session, order)
    return isv.order_payload(session, order, user)


# --------- переходы водителя (accept/decline/arrived/onboard/done) ---------
@router.post("/instant/orders/{order_id}/accept")
def accept(order_id: int, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Водитель принимает оффер. Гонка двух accept → второму 409 (row-lock + условный UPDATE)."""
    existing = session.get(InstantOrder, order_id)
    if not existing:
        raise HTTPException(404, "Заказ не найден")
    # Гейты водителя: (a) флаг/город по точке подачи + (b) заявка таксиста + долг.
    _guard_taxi_driver(session, user.id, existing.from_lat, existing.from_lng)
    order = isv.transition(session, order_id, isv.Actor.driver, S.accepted, user.id, idempotent=False)
    return isv.order_payload(session, order, user)


@router.post("/instant/orders/{order_id}/decline")
def decline(order_id: int, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Водитель отклоняет оффер → matcher предлагает следующему. Идемпотентно."""
    order = isv.decline_offer(session, order_id, user.id)
    return isv.order_payload(session, order, user)


@router.post("/instant/orders/{order_id}/arrived")
def arrived(order_id: int, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """«Я на месте»: accepted → arriving. С этого момента идёт ожидание пассажира
    (wait_free_minutes бесплатно, дальше wait_fee_rub_per_min ₽/мин — фиксируется на onboard)."""
    order = isv.transition(session, order_id, isv.Actor.driver, S.arriving, user.id)
    return isv.order_payload(session, order, user)


@router.post("/instant/orders/{order_id}/onboard")
def onboard(order_id: int, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Пассажир сел: arriving → onboard."""
    order = isv.transition(session, order_id, isv.Actor.driver, S.onboard, user.id)
    return isv.order_payload(session, order, user)


@router.post("/instant/orders/{order_id}/done")
def done(order_id: int, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Поездка завершена: onboard → done (фиксируем price_final).
    Начисляем долг по комиссии (Модель А «на доверии»): 8% с завершённого такси-заказа —
    водитель получил деньги напрямую, комиссию должен платформе. Идемпотентно (на заказ — раз)."""
    order = isv.transition(session, order_id, isv.Actor.driver, S.done, user.id)
    if order.status == S.done:
        debt_mod.accrue_for_order(session, order)
        # B7b-4: мягкое напоминание про чек «Мой налог» (дедуп 1/сутки внутри).
        isv.maybe_receipt_reminder(session, order.driver_id)
        # B8-4: реферальный бонус пригласившему — только когда водитель реально раскатался
        # (≥3 живых done-поездок с ≥3 разными пассажирами; идемпотентно внутри).
        reward_driver_referral(session, order.driver_id)
    return isv.order_payload(session, order, user)


# --------- взаимная оценка заказа (§9 Качество) ---------
class RateIn(BaseModel):
    stars: int = Field(..., ge=1, le=5)


@router.post("/instant/orders/{order_id}/rate")
def rate_order(order_id: int, body: RateIn, user: User = Depends(current_user),
               session: Session = Depends(get_session)):
    """Оценить вторую сторону ЗАВЕРШЁННОГО быстрого заказа (1..5). Пассажир → водитель,
    водитель → пассажир. Одна оценка на (rater, order) — повтор обновляет. Оценка анонимна:
    наружу идёт только агрегат (кто поставил — не раскрывается). Пересчёт driver.rating
    учитывает и заказы, и попутку (общий агрегат по ratee_id)."""
    order = session.get(InstantOrder, order_id)
    if not order:
        raise HTTPException(404, "Заказ не найден")
    if user.id == order.passenger_id and order.driver_id is not None:
        ratee_id = order.driver_id           # пассажир → водитель
    elif order.driver_id is not None and user.id == order.driver_id:
        ratee_id = order.passenger_id        # водитель → пассажир
    else:
        raise HTTPException(403, "Нельзя оценить этот заказ")
    if order.status != S.done:
        raise herr(409, "Оценить можно только завершённую поездку", "Тик тамамланған сәфәрҙе генә баһалап була")
    stars = max(1, min(5, body.stars))
    existing = session.exec(
        select(Rating).where(Rating.order_id == order_id, Rating.rater_id == user.id)
    ).first()
    if existing:
        existing.stars = stars
        session.add(existing)
    else:
        session.add(Rating(order_id=order_id, rater_id=user.id, ratee_id=ratee_id, stars=stars))
    session.commit()
    avg, cnt = user_rating(session, ratee_id)
    prof = session.exec(select(DriverProfile).where(DriverProfile.user_id == ratee_id)).first()
    if prof and cnt > 0:
        prof.rating = round(avg, 1)
        session.add(prof)
        session.commit()
    # 🟡 Лестница §9: рейтинг просел → мягкий пуш-совет (дедуп 1/нед), без наказания.
    if cnt > 0:
        quality_mod.maybe_low_rating_advice(session, ratee_id, avg)
    # Анонимность: rater не раскрываем, отдаём только агрегат оценённого.
    return {"ratee_id": ratee_id, "rating": round(avg, 1), "count": cnt}


# --------- отмена (обе стороны) ---------
@router.post("/instant/orders/{order_id}/cancel")
def cancel(order_id: int, body: CancelIn | None = None, user: User = Depends(current_user),
           session: Session = Depends(get_session)):
    """Отмена заказа. Пассажир — до посадки; водитель — после accept. Причина опциональна.
    Водитель с reason="no_show" («пассажир не вышел») — только после «Я на месте» +
    бесплатное ожидание + запас; фиксирует no_show и штраф-подачу (Модель А, денег не двигаем)."""
    order = session.get(InstantOrder, order_id)
    if not order:
        raise HTTPException(404, "Заказ не найден")
    if user.id == order.passenger_id:
        actor = isv.Actor.passenger
    elif user.id == order.driver_id:
        actor = isv.Actor.driver
    else:
        raise HTTPException(403, "Нет доступа к заказу")
    reason = body.reason if body else ""
    order = isv.cancel_order(session, order_id, actor, user.id, reason)
    return isv.order_payload(session, order, user)
