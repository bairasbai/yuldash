"""«Быстрый заказ» (такси-режим, Фаза 2): presence, оценка цены, заказ + машина состояний.

Отдельный поток от плановых поездок (Ride/Booking) — тот не трогаем.
Приватность: координаты не логируем; телефоны сторон — только после accept.
"""
import json
from datetime import datetime, timedelta, timezone
from typing import Literal, Optional

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field
from sqlalchemy import update
from sqlmodel import Session, select

from ..antifraud import moderate_open_text
from ..config import settings as app_settings
from ..db import get_session
from ..errors import herr
from ..middleware import user_over_limit
from ..models import (DriverProfile, InstantOrder, InstantOrderStatus as S, PriceComplaint,
                      Settlement, User, UserRole)
from ..safety_logic import account_paused, ensure_active
from ..security import current_user
from ..timeutil import utcnow
from .referral import reward_driver_referral
from .. import car_class
from .. import rating_service
from ..rating_service import guard_rating_window
from .. import debt as debt_mod
from .. import geo as geo_mod
from .. import instant_service as isv
from .. import pretrip as pretrip_mod
from .. import promo_ride
from .. import quality as quality_mod
from .. import taxi as taxi_mod
from .. import workday as workday_mod

router = APIRouter(tags=["instant"])


def _guard_taxi_not_blocked(session: Session, driver_id: int) -> None:
    """Долг по комиссии просрочен / выше порога → водитель НЕ может возить такси.
    ПОПУТКА (плановые Ride/Booking) этим не затрагивается — там своего долга нет."""
    if debt_mod.taxi_block_reason(session, driver_id) is not None:
        raise herr(403, debt_mod.TAXI_BLOCKED_MSG, debt_mod.TAXI_BLOCKED_MSG_BA)


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
    # Пауза «Справедливости» (§2). Стоит первой и намеренно здесь, а не в каждой ручке
    # по отдельности: отстранённый разбором жалобы водитель не выходит на линию и не берёт
    # заказ. Раньше проверки не было — пауза рубила публикацию попутки, но такси продолжало
    # возить (аудит 2026-08-06). Активный заказ не рвётся: arrived/onboard/done сюда не ходят.
    ensure_active(session, driver_id)
    if not taxi_mod.is_approved_taxi_driver(session, driver_id):
        # Документы просрочены — это не «ты не прошёл проверку», а «продли и возвращайся».
        # Разный текст важен: первый обвиняет человека, второй объясняет, что делать.
        if taxi_mod.taxi_docs_expired(session, driver_id):
            raise herr(403, taxi_mod.MSG_DOCS_EXPIRED["ru"], taxi_mod.MSG_DOCS_EXPIRED["ba"])
        raise herr(403, taxi_mod.TAXI_NOT_APPROVED_MSG, taxi_mod.TAXI_NOT_APPROVED_MSG_BA)
    _guard_taxi_not_blocked(session, driver_id)
    pretrip_mod.guard_pretrip(session, driver_id)   # 580-ФЗ: подтверждение готовности на сегодня
    workday_mod.guard_taxi_rested(session, driver_id)
    quality_mod.guard_taxi_quality(session, driver_id)


# ------------------------------ схемы ------------------------------
class WaypointIn(BaseModel):
    """Остановка по пути. С координатами, а не названием: по ней строят маршрут и считают цену."""
    lat: float = Field(..., ge=-90, le=90)
    lng: float = Field(..., ge=-180, le=180)
    text: str = Field("", max_length=200)


class EstimateIn(BaseModel):
    from_lat: float = Field(..., ge=-90, le=90)
    from_lng: float = Field(..., ge=-180, le=180)
    to_lat: float = Field(..., ge=-90, le=90)
    to_lng: float = Field(..., ge=-180, le=180)
    from_text: str = Field("", max_length=200)
    to_text: str = Field("", max_length=200)
    # Классы: standard = Эконом, comfort = Комфорт, business = Бизнес, minivan = Минивэн
    # (docs/taxi-classes-2026-08.md). Класс машины считается классификатором, а не заявляется.
    category: Literal["standard", "comfort", "business", "minivan"] = "standard"
    # Опции салона (детское кресло по группе, бустер, коляска, собака-проводник, животное,
    # большой багаж). Это НЕ класс: одна машина не может стоять в двух классах, а кресло
    # возить может любая. Фильтр жёсткий — машине без кресла такой заказ не предлагаем.
    options: list[str] = Field(default_factory=list)
    # Круговой рейс: водитель везёт туда, ждёт и везёт обратно, обратная дорога со скидкой.
    # Только межгород — в городе порожняка нет (isv.round_trip_available).
    round_trip: bool = False
    # Сколько водитель ждёт на месте, минут. Ноль = обычная поездка в одну сторону.
    return_wait_min: int = Field(0, ge=0, le=24 * 60)
    # Остановки по пути: A → точки → B. Не больше трёх (решение Александра, 2026-08-23):
    # каждая удлиняет поездку и цену, а на четвёртой водители начинают отказываться — заказ
    # висит до истечения, и виноватым выглядит приложение. Экран даёт столько же.
    waypoints: list[WaypointIn] = Field(default_factory=list, max_length=3)
    # ВНИМАНИЕ: поля цены здесь НЕТ намеренно — сервер считает сам, клиенту не верим.


class OrderIn(EstimateIn):
    # Как найти пассажира (аудит 2026-07-26): в селе адрес «Ленина 12» — пять домов без
    # табличек, а чат открывается только ПОСЛЕ принятия заказа. Комментарий и подъезд уходят
    # водителю вместе с оффером.
    comment: str = Field("", max_length=300)
    entrance: str = Field("", max_length=60)
    # Заказ ДЛЯ ДРУГОГО человека: сын из Уфы вызывает такси маме в Баймаке. Без этих полей
    # водитель звонил заказчику в другой город, а мама стояла у ворот и не знала, приехала ли машина.
    for_name: str = Field("", max_length=120)
    for_phone: str = Field("", max_length=32)
    # «Только женщина за рулём». В попутках такой выбор был с самого начала, а в такси —
    # нет, хотя ночью в чужую машину садятся именно здесь (аудит 2026-08-06). Фильтр
    # ЖЁСТКИЙ: молча подсунуть мужчину — обмануть в том, ради чего галочку и ставили.
    # Не нашлось никого — заказ честно истекает, и человек сам решает, искать ли шире.
    women_only: bool = False
    # Чем рассчитаются: cash | sbp | negotiate. Пусто → «договоримся на месте».
    # Карты и корпоративный счёт заведены, но выключены — их сервер не примет.
    payment_method: str = Field("", max_length=16)


class WaypointsIn(BaseModel):
    """Новый набор остановок. Проеденные сюда не входят — сервер сохранит их сам.

    Лимит тот же, что при заказе: три ещё не проеденные точки. Проеденные не считаются —
    спорить о том, что уже позади, не о чем.
    """
    waypoints: list[WaypointIn] = Field(default_factory=list, max_length=3)


class DestinationIn(BaseModel):
    """Новый адрес назначения. Цену считает сервер — из клиента она не принимается."""
    to_lat: float = Field(..., ge=-90, le=90)
    to_lng: float = Field(..., ge=-180, le=180)
    to_text: str = Field("", max_length=200)
    # true = только посчитать и показать, ничего не менять. Пассажир должен увидеть новую
    # цену ДО того, как согласится: «станет 480 ₽ вместо 320 ₽».
    preview: bool = False


class DeclineDestinationIn(BaseModel):
    """Почему водитель не может ехать по новому адресу.

    Причина нужна не для отчётности, а пассажиру: «просто завершено» посреди поездки звучит
    как произвол, а названная причина превращает отказ в понятную ситуацию. Плюс это данные:
    если половина отказов «далеко от моей зоны» — значит мы плохо спрашиваем зону на линии.
    """
    reason: Literal["shift_end", "out_of_zone", "no_fuel", "other"] = "other"


class PresenceIn(BaseModel):
    lat: float = Field(..., ge=-90, le=90)
    lng: float = Field(..., ge=-180, le=180)


class CancelIn(BaseModel):
    reason: str = Field("", max_length=200)


class ZoneIn(BaseModel):
    """База зоны + два согласия. Старые значения (intercity/region) принимаем ради
    приложений, которые ещё не обновились: сервер сам переводит их в базу + тумблеры."""
    work_zone: Literal["city", "district", "intercity", "region"]
    work_city: Optional[str] = Field(None, max_length=100)
    work_district: Optional[str] = Field(None, max_length=100)
    work_intercity: Optional[bool] = None
    work_regions: Optional[bool] = None
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
        "work_district": dp.work_district if dp else None,
        "work_intercity": bool(dp.work_intercity) if dp else False,
        "work_regions": bool(dp.work_regions) if dp else False,
        "work_direction_id": dp.work_direction_id if dp else None,
        "work_direction": direction,
    }


def normalize_zone(body) -> tuple:
    """Вход приложения → (база, город, район, загород, регионы, направление).

    Старое приложение шлёт work_zone=intercity/region — переводим: база остаётся городом,
    включаются тумблеры. Новое шлёт city/district + тумблеры явно."""
    zone = body.work_zone
    intercity = body.work_intercity
    regions = body.work_regions
    if zone in ("intercity", "region"):
        intercity = True if intercity is None else intercity
        regions = (zone == "region") if regions is None else regions
        zone = "city"
    base = "district" if zone == "district" else "city"
    city = (body.work_city or "").strip() or None
    district = (body.work_district or "").strip() or None
    if base == "city":
        district = None
    else:
        city = None
    intercity = bool(intercity)
    regions = bool(regions) and intercity      # «соседние регионы» без выезда загород бессмысленны
    direction_id = body.work_direction_id if intercity else None
    return base, city, district, intercity, regions, direction_id


@router.get("/instant/zone")
def get_zone(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Текущая зона работы таксиста (показ в кабинете рядом с тумблером «на линии»)."""
    dp = session.exec(select(DriverProfile).where(DriverProfile.user_id == user.id)).first()
    return _zone_payload(session, dp)


@router.post("/instant/zone")
def set_zone(body: ZoneIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Выбор зоны: база (🏙 мой город/село или 🗺 мой район) + тумблеры «выезд загород»
    (опц. с направлением) и «соседние регионы». Как «Мой район» у Яндекс Про, но бесплатно:
    в базовом режиме обе точки заказа внутри зоны, выход за неё — только с тумблером.
    Только водитель с одобренной заявкой таксиста (580-ФЗ). Влияет ТОЛЬКО на такси-matcher,
    попутка (Ride/Booking) не затрагивается."""
    dp = session.exec(select(DriverProfile).where(DriverProfile.user_id == user.id)).first()
    if not dp:
        raise herr(409, "Сначала стань водителем (профиль водителя не найден)", "Башта йөрөтөүсе бул (йөрөтөүсе профиле табылманы)")
    if not taxi_mod.is_approved_taxi_driver(session, user.id):
        raise herr(403, taxi_mod.TAXI_NOT_APPROVED_MSG, taxi_mod.TAXI_NOT_APPROVED_MSG_BA)
    base, city, district, intercity, regions, direction_id = normalize_zone(body)
    if direction_id is not None and session.get(Settlement, direction_id) is None:
        raise herr(404, "Направление не найдено в справочнике", "Йүнәлеш белешмәлектә табылманы")
    if district and not geo_mod.district_exists(session, district):
        # Опечатка в районе = тишина в офферах, и человек не поймёт почему. Лучше честный отказ.
        raise herr(422, "Такого района нет в справочнике. Выбери район из подсказок.",
                   "Бындай район белешмәлә юҡ. Районды тәҡдимдәрҙән һайла.")
    dp.work_zone = base
    dp.work_city = city
    dp.work_district = district
    dp.work_intercity = intercity
    dp.work_regions = regions
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
        raise herr(403, taxi_mod.TAXI_NOT_APPROVED_MSG, taxi_mod.TAXI_NOT_APPROVED_MSG_BA)
    return isv.demand_zones(session, city)


@router.get("/instant/nearby-drivers")
def nearby_drivers_ep(lat: float, lng: float, user: User = Depends(current_user),
                      session: Session = Depends(get_session)):
    """Свободные машины «на линии» рядом с пассажиром — АНОНИМНЫЕ точки на карте + ≈ETA
    до подачи и класс кузова (для карты в режиме такси). Только реальные presence-данные,
    без личности водителя (ни id, ни телефона). Нет Redis → пустой список (карта без машинок).

    Класс нужен, чтобы метка выглядела как та машина, которая приедет: человек выбрал Бизнес
    и должен видеть на карте бизнес-машины, а не одинаковые точки."""
    if not (-90.0 <= lat <= 90.0 and -180.0 <= lng <= 180.0):
        raise herr(400, "Некорректные координаты", "Координаталар дөрөҫ түгел")
    # Наружу отдаём РОВНО точку, ETA и класс машины. `nearby_drivers` знает больше (например,
    # расстояние — оно нужно расчёту дальней подачи), но карта не место, где это раздают:
    # список полей публичной ручки должен расти осознанно, а не сам собой вслед за внутренним.
    return {"drivers": [
        {k: v for k, v in d.items() if k in ("lat", "lng", "eta_min", "category")}
        for d in isv.nearby_drivers(lat, lng, session=session)
    ]}


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
def guard_estimate_budget(user_id: int) -> None:
    """Персональный потолок на оценку цены. За каждым вызовом может стоять платный запрос в
    Yandex Routing/Weather, а кэш обходится чуть сдвинутыми координатами. IP-лимит стоит в
    middleware, но IP меняется прокси — аккаунт нет, поэтому потолок и здесь (аудит 2026-08-03)."""
    if user_over_limit("estimate", user_id, app_settings.rate_limit_estimate_per_user_per_min):
        raise herr(429, "Слишком много расчётов подряд. Подожди минуту и попробуй снова.",
                   "Артыҡ күп иҫәпләү. Бер минут көт тә яңынан ҡабатла.")


@router.post("/instant/estimate")
def estimate(body: EstimateIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Оценка цены ДО заказа. Сервер считает сам (haversine × road_k) — цена из клиента игнорируется.

    Скидку по промокоду показываем ЗДЕСЬ, а не после поездки: человек должен видеть выгоду до
    того, как нажал «Заказать», иначе промокод для него не существует."""
    guard_estimate_budget(user.id)
    _guard_taxi_available(session, body.from_lat, body.from_lng)   # пассажиру — только гейт (a)
    est = isv.estimate(session, (body.from_lat, body.from_lng), (body.to_lat, body.to_lng),
                       body.category, round_trip=body.round_trip,
                       waypoints=[w.model_dump() for w in body.waypoints],
                       # Опции салона стоят денег (кресло 150 ₽) — цена обязана их учитывать
                       # ещё ДО заказа, иначе на экране одна сумма, а в заказе другая.
                       options=body.options)
    est.update(promo_ride.preview(session, user.id, est["price"]))
    return est


# ------------------------------ заказ ------------------------------
@router.post("/instant/orders")
def create_order(body: OrderIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Создать быстрый заказ: сервер считает цену → matcher ищет и предлагает ближайшему водителю.
    Нет свободных/нет Redis → заказ сразу expired («рядом никого»), но запрос не падает.
    Сурж фиксируется на заказе (price_estimate уже с ним). Страйки (§5, Модель А):
    ≥3 платные отмены/no-show за 7 дней → такси-заказы на паузе 24 ч (попутка работает)."""
    ensure_active(session, user.id)   # пауза лестницы «Справедливости» (§2) блокирует новый заказ
    _guard_taxi_available(session, body.from_lat, body.from_lng)   # пассажиру — только гейт (a)
    # Страйки §5 + resolved-жалобы no_show/unpaid/damage §9 — общий счётчик (попутка работает).
    if quality_mod.passenger_pause_until(session, user.id) is not None:
        raise HTTPException(403, isv.strike_pause_message())
    est = isv.estimate(session, (body.from_lat, body.from_lng), (body.to_lat, body.to_lng),
                       body.category, round_trip=body.round_trip,
                       waypoints=[w.model_dump() for w in body.waypoints],
                       # Опции салона стоят денег (кресло 150 ₽) — цена обязана их учитывать
                       # ещё ДО заказа, иначе на экране одна сумма, а в заказе другая.
                       options=body.options)
    # Не даём плодить параллельные активные заказы одному пассажиру (двойной тап/спам).
    # Лочим строку пассажира → два одновременных POST сериализуются: первый создаёт заказ,
    # второй под локом видит existing и возвращает его (без row-lock оба проходили SELECT→INSERT).
    session.exec(select(User).where(User.id == user.id).with_for_update()).first()
    # «Заказать заново» из очереди «подожду машину». Заказ в очереди лежит в статусе `expired`
    # с живым `wait_until` — воркер по нему продолжает искать, но в список активных ниже он
    # не попадает (статус-то терминальный), и анти-дубль его не видел. Человек получал ВТОРОЙ
    # заказ, а матчер отправлял к подъезду ДВУХ разных водителей: одну машину он берёт, вторая
    # уезжает пустой и получает страйк ни за что (аудит 2026-08-07). Нажал «заказать заново» —
    # значит старого больше не ждёт: гасим очередь до создания нового заказа.
    session.execute(
        update(InstantOrder)
        .where(InstantOrder.passenger_id == user.id,
               InstantOrder.status == S.expired,
               InstantOrder.wait_until != None)          # noqa: E711 — SQL IS NOT NULL
        .values(wait_until=None)
    )
    session.commit()
    existing = session.exec(
        select(InstantOrder).where(
            InstantOrder.passenger_id == user.id,
            InstantOrder.status.in_(isv.LIVE_ORDER_STATUSES),
        )
    ).first()
    if existing:
        return isv.order_payload(session, existing, user)
    # Комментарий к заказу читает каждый водитель, кому уходит оффер. В такси с водителя берётся
    # комиссия, поэтому «звони мне на +7…» здесь — не обмен контактами по-соседски, а увод сделки
    # мимо приложения (и мимо защиты: вне заказа нет ни SOS, ни чека, ни разбора спора).
    # Проверялись комментарий заявки и отклик, а этот — нет (аудит 2026-08-06).
    # Вместе с комментарием проверяем АДРЕСА: их пишет человек руками, их читает водитель
    # в оффере и любой, кому дали ссылку слежения (`/t/{token}` показывает «откуда → куда»).
    # Проверка стояла только на комментарии — телефон в поле «Куда» проезжал мимо (волна 40).
    moderate_open_text("\n".join(p for p in (body.comment, body.from_text, body.to_text) if p),
                       user.id, place="order_comment", session=session)
    order = InstantOrder(
        passenger_id=user.id,
        from_lat=body.from_lat, from_lng=body.from_lng,
        to_lat=body.to_lat, to_lng=body.to_lng,
        from_text=body.from_text, to_text=body.to_text,
        category=body.category,
        options=car_class.dump_options(body.options),
        # Цена целиком: сама поездка + дорога водителя к пассажиру (см. isv.price_fields).
        **isv.price_fields(est),
        # Круговой рейс фиксируем на заказе: по нему водитель поймёт, что его ждёт обратная
        # дорога, а не просто высадка. Сервер сам решает, доступен ли он (город — нет).
        round_trip=bool(est.get("round_trip")),
        waypoints_json=isv.dump_waypoints([w.model_dump() for w in body.waypoints]),
        return_wait_min=(body.return_wait_min if est.get("round_trip") else 0), distance_km=est["distance_km"],
        eta_min=est["eta_min"], tariff_id=est["tariff_id"],
        surge_k=est["surge_k"],
        pricing_k=est.get("pricing_k", est["surge_k"]),
        # Как найти пассажира + «еду не сам» — водителю в оффер (см. OrderIn).
        comment=(body.comment or "").strip()[:300],
        entrance=(body.entrance or "").strip()[:60],
        for_name=(body.for_name or "").strip()[:120],
        for_phone=(body.for_phone or "").strip()[:32],
        payment_method=_pay_method_or_default(body.payment_method),
        women_only=bool(body.women_only),
    )
    session.add(order)
    session.commit()
    session.refresh(order)
    # Скидка по промокоду ФИКСИРУЕТСЯ в заказе и списывается ровно один раз (row-lock + CAS
    # внутри). Делаем это ДО поиска водителя, чтобы и пассажир, и водитель уже в карточке
    # видели честную сумму «к оплате».
    promo_ride.consume(session, user.id, order)
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
    # Предзаказ — та же поездка, оформленная заранее. Без этой строки пауза закрывала обычный
    # заказ, но обходилась предзаказом в два тапа (аудит 2026-08-06).
    ensure_active(session, user.id)
    if quality_mod.passenger_pause_until(session, user.id) is not None:
        raise HTTPException(403, isv.strike_pause_message())
    when = _parse_scheduled_at(body.scheduled_at)
    # Цену считаем на время ПОДАЧИ, а не нажатия (волна 163): иначе заказ на пять утра,
    # оформленный днём, уходит по дневной ставке и в мороз за ним никто не едет.
    # Круговой рейс и остановки — тоже часть цены, их нельзя терять у предзаказа.
    est = isv.estimate(session, (body.from_lat, body.from_lng), (body.to_lat, body.to_lng),
                       body.category, round_trip=body.round_trip,
                       waypoints=[w.model_dump() for w in body.waypoints], when=when,
                       options=body.options)
    # Предзаказ — та же открытая тройка «комментарий + два адреса», что и обычный заказ.
    moderate_open_text("\n".join(p for p in (body.comment, body.from_text, body.to_text) if p),
                       user.id, place="order_comment", session=session)
    order = InstantOrder(
        passenger_id=user.id, status=S.scheduled, scheduled_at=when,
        from_lat=body.from_lat, from_lng=body.from_lng,
        to_lat=body.to_lat, to_lng=body.to_lng,
        from_text=body.from_text, to_text=body.to_text,
        category=body.category,
        options=car_class.dump_options(body.options),
        # Цена целиком: сама поездка + дорога водителя к пассажиру (см. isv.price_fields).
        **isv.price_fields(est),
        # Круговой рейс фиксируем на заказе: по нему водитель поймёт, что его ждёт обратная
        # дорога, а не просто высадка. Сервер сам решает, доступен ли он (город — нет).
        round_trip=bool(est.get("round_trip")),
        waypoints_json=isv.dump_waypoints([w.model_dump() for w in body.waypoints]),
        return_wait_min=(body.return_wait_min if est.get("round_trip") else 0), distance_km=est["distance_km"],
        eta_min=est["eta_min"], tariff_id=est["tariff_id"], surge_k=est["surge_k"],
        pricing_k=est.get("pricing_k", est["surge_k"]),
        # Как найти пассажира + «еду не сам» — водителю в оффер (см. OrderIn).
        comment=(body.comment or "").strip()[:300],
        entrance=(body.entrance or "").strip()[:60],
        for_name=(body.for_name or "").strip()[:120],
        for_phone=(body.for_phone or "").strip()[:32],
        payment_method=_pay_method_or_default(body.payment_method),
        women_only=bool(body.women_only),
    )
    session.add(order)
    session.commit()
    session.refresh(order)
    # Предзаказ тоже получает скидку сразу (человек видел её в оценке). Цена пересчитывается в
    # момент активации — там же скидка при необходимости ужимается до доли новой цены.
    promo_ride.consume(session, user.id, order)
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
            started = isv.activate_scheduled(session, o)
            # Активация могла не состояться: человек уже едет по другому заказу — тогда предзаказ
            # ждёт своей очереди (волна 76). В «поехали» его класть нельзя, иначе экран отчитается
            # о начале поездки, которой нет.
            if started.status == S.scheduled:
                scheduled.append(isv.order_payload(session, started, user))
            else:
                activated.append(isv.order_payload(session, started, user))
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
        raise herr(404, "Предзаказ не найден", "Алдан заказ табылманы")
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
        raise herr(404, "Предзаказ не найден", "Алдан заказ табылманы")
    order = isv.cancel_order(session, order_id, isv.Actor.passenger, user.id, "scheduled_cancel")
    return isv.order_payload(session, order, user)


def _order_for_view(session: Session, order_id: int, user: User) -> InstantOrder:
    order = session.get(InstantOrder, order_id)
    if not order:
        raise herr(404, "Заказ не найден", "Заказ табылманы")
    if user.id not in (order.passenger_id, order.driver_id, order.current_offer_driver_id):
        raise herr(403, "Нет доступа к заказу", "Заказға рөхсәт юҡ")
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
    if account_paused(session, user.id):
        return {"offer": None}   # пауза «Справедливости» (§2) — офферов нет, пока идёт разбор
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


class AlternativeIn(BaseModel):
    category: Literal["standard", "comfort", "business", "minivan"]


@router.get("/instant/orders/{order_id}/alternatives")
def alternatives(order_id: int, user: User = Depends(current_user),
                 session: Session = Depends(get_session)):
    """Что предложить, если в выбранном классе никого нет (docs/taxi-classes-2026-08.md §5).

    Клиент дёргает через `after_sec` секунд поиска и показывает список с ценами. Решение —
    за пассажиром: молчаливой подмены класса у нас нет. Пустой список = предлагать нечего,
    честно ждём дальше."""
    order = _order_for_view(session, order_id, user)
    if order.passenger_id != user.id:
        raise herr(403, "Это не твой заказ", "Был һинең заказың түгел")
    return {
        "after_sec": app_settings.class_fallback_after_sec,
        "options": isv.fallback_options(session, order),
    }


@router.post("/instant/orders/{order_id}/alternatives")
def add_alternative(order_id: int, body: AlternativeIn, user: User = Depends(current_user),
                    session: Session = Depends(get_session)):
    """Пассажир согласился искать и в соседнем классе. Цена сразу пересчитывается вниз и
    фиксируется — он видел её на экране до нажатия и заплатит ровно её."""
    order = _order_for_view(session, order_id, user)
    if order.passenger_id != user.id:
        raise herr(403, "Это не твой заказ", "Был һинең заказың түгел")
    if order.status not in (S.created, S.searching, S.offered):
        raise herr(409, "Заказ уже не в поиске", "Заказ инде эҙләүҙә түгел")
    return isv.add_fallback_category(session, order, body.category)


# --------- переходы водителя (accept/decline/arrived/onboard/done) ---------
@router.post("/instant/orders/{order_id}/accept")
def accept(order_id: int, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Водитель принимает оффер. Гонка двух accept → второму 409 (row-lock + условный UPDATE)."""
    existing = session.get(InstantOrder, order_id)
    if not existing:
        raise herr(404, "Заказ не найден", "Заказ табылманы")
    # Гейты водителя: (a) флаг/город по точке подачи + (b) заявка таксиста + долг.
    _guard_taxi_driver(session, user.id, existing.from_lat, existing.from_lng)
    # Анти-дубль назначения: нельзя взять ВТОРОЙ заказ при активном первом. Matcher мог
    # предложить одного водителя двум заказам, пока оба ещё offered (на малом рынке «между
    # своими» вероятно) → accept обоих дал бы двойное назначение, один пассажир брошен.
    other_active = session.exec(
        select(InstantOrder.id).where(
            InstantOrder.driver_id == user.id,
            InstantOrder.status.in_([S.accepted, S.arriving, S.onboard]),
            InstantOrder.id != order_id,
        )
    ).first()
    if other_active is not None:
        raise herr(409, "У тебя уже есть активная поездка — заверши её сначала", "Һинең актив сәфәрең бар — башта уны тамамла")
    # Пауза за брошенные заказы (разбор №2): офферы такому водителю не шлём, но заказ может
    # прийти и другим путём (ссылка, повторный тап по старому уведомлению) — закрываем и здесь.
    if isv.driver_pause_until(session, user.id) is not None:
        raise HTTPException(403, isv.driver_pause_message())
    order = isv.transition(session, order_id, isv.Actor.driver, S.accepted, user.id, idempotent=False)
    return isv.order_payload(session, order, user)


class DeclineIn(BaseModel):
    """Почему водитель не взял заказ (опц.: старый клиент тела не шлёт).

    Зачем спрашиваем: без причины платформа видит только «не берут» и продолжает слать те же
    заказы тем же людям. С причиной становится видно, что чинить — далеко подавать, мало денег,
    неудобное направление. Это диагностика матчинга, а не наказание: на отказ никаких санкций
    нет и не будет, иначе водители начнут просто уходить в офлайн вместо честного отказа.
    """
    reason: Optional[str] = Field(None, max_length=32)


# Причины отказа от оффера. Закрытый список — свободный текст никто не читает и не агрегирует.
_DECLINE_REASONS = {"far", "cheap", "direction", "busy", "break", "other"}


@router.post("/instant/orders/{order_id}/decline")
def decline(order_id: int, body: DeclineIn | None = None,
            user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Водитель отклоняет оффер → matcher предлагает следующему. Идемпотентно.

    Права проверяем ДО отказа: ручка идемпотентна и постороннему ничего не меняла, но
    исправно возвращала витрину чужого заказа — с точкой подачи и телефоном водителя
    (аудит 2026-08-07). После отказа заказ уходит следующему кандидату, и отказавшийся
    перестаёт быть участником — поэтому витрину отдаём с `actor_authorized`.
    """
    order = _order_for_view(session, order_id, user)
    reason = ((body.reason if body else "") or "").strip().lower()
    if reason and reason not in _DECLINE_REASONS:
        reason = "other"      # незнакомое значение не роняет отказ: отказаться важнее, чем классифицировать
    order = isv.decline_offer(session, order_id, user.id, reason=reason)
    return isv.order_payload(session, order, user, actor_authorized=True)


class ArrivedIn(BaseModel):
    """Координаты водителя в момент «Я на месте» (опц.: старый клиент их не шлёт)."""
    lat: Optional[float] = Field(None, ge=-90, le=90)
    lng: Optional[float] = Field(None, ge=-180, le=180)


# Насколько далеко от точки подачи ещё считаем «на месте» (GPS в селе гуляет, дом большой).
_ARRIVED_RADIUS_KM = 0.5
# Насколько СВЕЖЕЙ должна быть позиция из кэша WS-трека, чтобы по ней судить «где водитель».
# Кэш живёт 2 минуты, а за две минуты машина проезжает километр-полтора: у водителя, который
# реально доехал, но потерял связь на подъезде (в селе это обычное дело), последняя точка
# осталась бы позади — и кнопка «Я на месте» отбилась бы у честного человека. Судим только
# по свежей точке; протухла — проверку пропускаем. Недобросовестного это не спасает: он сидит
# дома с открытым приложением, и его трек как раз свежий.
_ARRIVED_POS_FRESH_SEC = 60


@router.post("/instant/orders/{order_id}/arrived")
def arrived(order_id: int, body: ArrivedIn | None = None,
            user: User = Depends(current_user), session: Session = Depends(get_session)):
    """«Я на месте»: accepted → arriving. С этого момента идёт ожидание пассажира
    (wait_free_minutes бесплатно, дальше wait_fee_rub_per_min ₽/мин — фиксируется на onboard).

    Гео-проверка (аудит 2026-07-26): раньше кнопку можно было нажать откуда угодно — прямо из
    дома. С неё идёт ПЛАТНОЕ ожидание, а через 8 минут открывается «пассажир не вышел» со
    штрафом и страйком: невиновный человек получал деньги в минус и блокировку такси на сутки.
    Координаты берём из тела, иначе из последней СВЕЖЕЙ позиции водителя (WS-трек, Redis).
    Нет ни того, ни другого — пропускаем (не ломаем работу там, где GPS недоступен)."""
    # Права — ПЕРЕД гео-проверкой. Раньше порядок был обратный, и разные ответы («ты ещё не
    # на месте» против успеха) отвечали постороннему на вопрос «водитель в 500 м от точки
    # подачи?» для ЛЮБОГО заказа. Перебором координат так находится чужой адрес подачи —
    # гео-оракул, утечка без всякой витрины (аудит 2026-08-07).
    order = _order_for_view(session, order_id, user)
    lat = body.lat if body else None
    lng = body.lng if body else None
    if lat is None or lng is None:
        try:
            from .. import livepos
            # Ключ ровно тот, что пишет WS-трек поездки (location.py, livepos_set("order", …)).
            # Здесь читалось "instant" — ключи разные, позиция всегда None, проверка молча
            # пропускалась ВСЕГДА. А тела запроса Android не шлёт, значит гео-проверки «Я на
            # месте» в проде не существовало вовсе (аудит 2026-08-07).
            pos = livepos.livepos_get("order", order_id)
            if pos and pos.get("ts"):
                age = (utcnow() - datetime.fromisoformat(pos["ts"])).total_seconds()
                if 0 <= age <= _ARRIVED_POS_FRESH_SEC:
                    lat, lng = pos.get("lat"), pos.get("lng")
        except Exception:  # noqa: BLE001 — Redis недоступен: проверку пропускаем, поездку не рубим
            lat = lng = None
    if lat is not None and lng is not None and order.from_lat and order.from_lng:
        from ..services import haversine_km
        if haversine_km(lat, lng, order.from_lat, order.from_lng) > _ARRIVED_RADIUS_KM:
            raise herr(409, "Ты ещё не на месте подачи — ожидание начнётся, когда подъедешь",
                       "Һин әле килеп етмәнең — көтөү килеп еткәс башлана")
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
        debt = debt_mod.accrue_for_order(session, order)
        # Крупная комиссия (дальний межгород) гасится сразу — предупреждаем в тот же момент,
        # иначе короткий срок сработает как молчаливая блокировка через пару часов.
        # Не срочная, но долг дорос до предела — предупреждаем ДО блокировки, а не после.
        # Через `or`: срочный пуш уже зовёт платить, второй следом читался бы как шум.
        isv.notify_pay_now_debt(session, debt) or isv.notify_debt_near_block(session, debt)
        # B7b-4: мягкое напоминание про чек «Мой налог» (дедуп 1/сутки внутри).
        isv.maybe_receipt_reminder(session, order.driver_id)
        # B8-4: реферальный бонус пригласившему — только когда водитель реально раскатался
        # (≥3 живых done-поездок с ≥3 разными пассажирами; идемпотентно внутри).
        reward_driver_referral(session, order.driver_id)
    return isv.order_payload(session, order, user)


# --------- взаимная оценка заказа (§9 Качество) ---------
class RateIn(BaseModel):
    stars: int = Field(..., ge=1, le=5)
    # Отзыв и метки принимались схемой и молча выбрасывались: человек писал о водителе такси,
    # а в базе оставались одни звёзды (волна 57). Теперь идут тем же путём, что у попутки.
    text: str = Field("", max_length=500)
    tags: str = Field("", max_length=300)


@router.post("/instant/orders/{order_id}/rate")
def rate_order(order_id: int, body: RateIn, user: User = Depends(current_user),
               session: Session = Depends(get_session)):
    """Оценить вторую сторону ЗАВЕРШЁННОГО быстрого заказа (1..5). Пассажир → водитель,
    водитель → пассажир. Одна оценка на (rater, order) — повтор обновляет. Оценка анонимна:
    наружу идёт только агрегат (кто поставил — не раскрывается). Пересчёт driver.rating
    учитывает и заказы, и попутку (общий агрегат по ratee_id)."""
    order = session.get(InstantOrder, order_id)
    if not order:
        raise herr(404, "Заказ не найден", "Заказ табылманы")
    if user.id == order.passenger_id and order.driver_id is not None:
        ratee_id = order.driver_id           # пассажир → водитель
    elif order.driver_id is not None and user.id == order.driver_id:
        ratee_id = order.passenger_id        # водитель → пассажир
    else:
        raise herr(403, "Нельзя оценить этот заказ", "Был заказды баһалап булмай")
    if order.status != S.done:
        raise herr(409, "Оценить можно только завершённую поездку", "Тик тамамланған сәфәрҙе генә баһалап була")
    # Тот же срок и та же логика, что у попутки: обе двери зовут одну функцию, иначе они
    # снова разъедутся — здесь молча терялись отзыв и метки (волна 57).
    guard_rating_window(order.done_at or order.created_at)
    avg, cnt = rating_service.apply_rating(
        session, user, ratee_id, stars=body.stars, text=body.text, tags=body.tags,
        order_id=order_id, place="review",
        happened_at=(order.done_at or order.created_at))
    # Анонимность: rater не раскрываем, отдаём только агрегат оценённого.
    return {"ratee_id": ratee_id, "rating": round(avg, 1), "count": cnt}


# --------- очередь «рядом никого» ---------
@router.post("/instant/orders/{order_id}/wait")
def wait_for_driver(order_id: int, user: User = Depends(current_user),
                    session: Session = Depends(get_session)):
    """«Подожду машину» после «рядом никого».

    Раньше отказ был мгновенным и окончательным: свободных водителей нет → заказ сразу expired,
    повтора поиска не было вообще. В райцентре ночью на линии 2-3 водителя и оба заняты — это
    не исключение, а норма: человек получал отказ за 2 секунды и уходил к конкуренту.
    Теперь пассажир ставит заказ в очередь, а фоновый воркер (app/taxi_worker.py) спокойно
    перезапускает поиск до order_wait_max_min минут и пушит, как только машина найдётся."""
    order = session.get(InstantOrder, order_id)
    if not order:
        raise herr(404, "Заказ не найден", "Заказ табылманы")
    if order.passenger_id != user.id:                     # анти-IDOR: ждать можно только свой заказ
        raise herr(403, "Это не твой заказ", "Был һинең заказың түгел")
    if order.status not in (isv.S.expired, isv.S.searching):
        raise herr(409, "Ожидание доступно, пока машина не найдена",
                   "Машина табылғанға тиклем генә көтөп була")
    order.wait_until = utcnow() + timedelta(minutes=isv.settings.order_wait_max_min)
    session.add(order)
    session.commit()
    session.refresh(order)
    return {"ok": True, "wait_until": order.wait_until,
            "wait_minutes": isv.settings.order_wait_max_min,
            "order": isv.order_payload(session, order, user)}


@router.post("/instant/orders/{order_id}/im-coming")
def im_coming(order_id: int, user: User = Depends(current_user),
              session: Session = Depends(get_session)):
    """«Уже выхожу» — пассажир спускается, водитель это видит.

    До сих пор водитель, приехав, знал ровно одно: тикает бесплатное ожидание. Человек мог
    быть уже в лифте, а мог не выйти вовсе — на экране разницы не было никакой, и водитель
    либо звонил, либо молча копил повод для спора о простое. Одна кнопка снимает и то и другое.

    Ничего не меняет в деньгах: таймер ожидания продолжает идти как шёл. Это сообщение,
    а не сделка, — иначе кнопкой начали бы отматывать платное ожидание.
    """
    order = session.get(InstantOrder, order_id)
    if not order:
        raise herr(404, "Заказ не найден", "Заказ табылманы")
    if order.passenger_id != user.id:      # анти-IDOR: предупредить можно только о своём заказе
        raise herr(403, "Это не твой заказ", "Был һинең заказың түгел")
    if order.status != isv.S.arriving:
        raise herr(409, "Водитель ещё не на месте", "Йөрөтөүсе әле урынында түгел")
    if order.driver_id:
        from ..services import push_bilingual   # локальный импорт — как в остальном файле
        # Имя не подставляем: водитель и так видит, чей это заказ, а лишнее имя в пуше —
        # лишние персональные данные на заблокированном экране.
        push_bilingual(session, order.driver_id,
                       "Пассажир выходит", "Пассажир сыға",
                       "Уже спускается — подожди пару минут.",
                       "Төшөп килә — бер-ике минут көт.",
                       ref_kind="instant", ref_id=order.id)
    return {"ok": True}


# --------- отмена (обе стороны) ---------
@router.post("/instant/orders/{order_id}/destination")
def change_destination(order_id: int, body: DestinationIn,
                       user: User = Depends(current_user),
                       session: Session = Depends(get_session)):
    """Пассажир меняет адрес назначения.

    Цена = уже проеденное + остаток до нового адреса. Не «новая поездка от текущей точки»:
    крюк, который водитель успел сделать, тогда пропал бы бесплатно.

    Обычно адрес меняется сразу — пассажир хозяин своего маршрута. Но если поездка стала
    междугородной или цена выросла втрое, сначала спрашиваем водителя: пять часов за руль
    и ночёвка в чужом городе — это не «поменял адрес», а другая работа.
    """
    order = session.get(InstantOrder, order_id)
    if not order or order.passenger_id != user.id:
        raise herr(404, "Заказ не найден", "Заказ табылманы")

    blocked = isv.can_change_destination(order)
    if blocked == "status":
        raise herr(409, "Сейчас адрес поменять нельзя",
                   "Хәҙер адресты үҙгәртеп булмай")
    if blocked == "too_often":
        raise herr(429, "Слишком часто. Подожди пару секунд",
                   "Бик йыш. Бер-ике секунд көт")
    if blocked == "almost_there":
        raise herr(409, "Уже почти на месте — закажи новую поездку",
                   "Инде барып еттек — яңы сәфәр заказ ит")

    quote = isv.destination_quote(session, order, (body.to_lat, body.to_lng))
    if not quote.get("ok"):
        raise herr(503, "Не удалось посчитать цену. Попробуй ещё раз",
                   "Хаҡты иҫәпләп булманы. Ҡабат ҡара")

    # Превью: показать человеку цену до того, как он согласится.
    if body.preview:
        return {**quote, "applied": False}

    # Крупная смена — сначала слово водителю.
    if quote["needs_driver_ok"] and order.driver_id:
        isv.offer_destination_to_driver(session, order, (body.to_lat, body.to_lng),
                                        body.to_text, quote)
        isv.notify_driver_destination(session, order, quote, pending=True)
        return {**quote, "applied": False, "waiting_driver": True}

    # Предложение уже висит у водителя со старым адресом — отзываем, иначе он примет заказ,
    # которого не видел. Дальше обычный поиск подхватит заказ уже с новым адресом.
    if order.status == S.offered:
        isv.advance_after_no_accept(session, order, notify=False)
        session.refresh(order)

    isv.apply_destination(session, order, (body.to_lat, body.to_lng), body.to_text, quote)
    if order.driver_id:
        isv.notify_driver_destination(session, order, quote)
    return {**quote, "applied": True, "order": isv.order_payload(session, order, user)}


@router.post("/instant/orders/{order_id}/destination/ack")
def ack_destination(order_id: int, user: User = Depends(current_user),
                    session: Session = Depends(get_session)):
    """Водитель нажал «Понял» — он видел новый адрес.

    Пока подтверждения нет, пассажиру через минуту покажем «водитель ещё не видел, позвони».
    Заставить человека посмотреть в телефон за рулём мы не можем, но честно сказать
    пассажиру, что происходит, — можем.
    """
    order = session.get(InstantOrder, order_id)
    if not order or order.driver_id != user.id:
        raise herr(404, "Заказ не найден", "Заказ табылманы")
    isv.ack_destination(session, order)
    return {"ok": True, "order": isv.order_payload(session, order, user)}


@router.post("/instant/orders/{order_id}/destination/accept")
def accept_destination(order_id: int, user: User = Depends(current_user),
                       session: Session = Depends(get_session)):
    """Водитель согласился на крупную смену (межгород / тройная цена)."""
    order = session.get(InstantOrder, order_id)
    if not order or order.driver_id != user.id:
        raise herr(404, "Заказ не найден", "Заказ табылманы")
    if order.pending_to_lat is None or order.pending_to_lng is None:
        raise herr(409, "Нечего подтверждать", "Раҫлар нәмә юҡ")
    new_to = (order.pending_to_lat, order.pending_to_lng)
    to_text = order.pending_to_text
    # Пересчитываем заново: пока водитель думал, машина ехала, и старая цифра устарела.
    quote = isv.destination_quote(session, order, new_to)
    if not quote.get("ok"):
        raise herr(503, "Не удалось посчитать цену", "Хаҡты иҫәпләп булманы")
    isv.apply_destination(session, order, new_to, to_text, quote)
    isv.ack_destination(session, order)     # согласился — значит точно видел
    return {"ok": True, "order": isv.order_payload(session, order, user)}


@router.post("/instant/orders/{order_id}/destination/decline")
def decline_destination(order_id: int, body: DeclineDestinationIn | None = None,
                        user: User = Depends(current_user),
                        session: Session = Depends(get_session)):
    """Водитель не может ехать дальше: поездка ЗАВЕРШАЕТСЯ там, где стоит машина.

    Именно завершается, а не отменяется. Работа сделана: человека везли, километры накрутили,
    деньги за них причитаются. Назвать это отменой — наказать водителя за то, что он честно
    предупредил, вместо того чтобы бросить пассажира на дороге. Заодно это закрывает лазейку
    «поменяй адрес, я откажусь, поездки как будто не было»: комиссию водитель платит.

    Если ждало согласия на крупную смену — адрес просто не меняется, поездка продолжается
    по старому. Отказ от НОВОГО маршрута не должен рвать тот, на который человек соглашался.
    """
    order = session.get(InstantOrder, order_id)
    if not order or order.driver_id != user.id:
        raise herr(404, "Заказ не найден", "Заказ табылманы")
    reason = (body.reason if body else "other")

    # Ждали согласия — просто снимаем предложение, поездка идёт по старому адресу.
    if order.pending_to_lat is not None:
        isv.decline_pending_destination(session, order, reason)
        return {"ok": True, "kept_old_destination": True,
                "order": isv.order_payload(session, order, user)}

    if order.status != S.onboard:
        raise herr(409, "Так можно только в поездке", "Былай тик сәфәр барышында була")
    order = isv.finish_early(session, order, reason)
    debt = debt_mod.accrue_for_order(session, order)
    isv.notify_pay_now_debt(session, debt) or isv.notify_debt_near_block(session, debt)
    return {"ok": True, "finished_early": True,
            "order": isv.order_payload(session, order, user)}


@router.post("/instant/orders/{order_id}/waypoints")
def set_waypoints(order_id: int, body: WaypointsIn,
                  user: User = Depends(current_user),
                  session: Session = Depends(get_session)):
    """Пассажир меняет остановки уже в поездке.

    Добавлять можно всегда, убирать — тоже: «мама сама доехала» это живой случай, цена
    при этом падает, водителю проще. А вот ПОРЯДОК менять нельзя: водитель уже едет
    к первой точке, навигатор ведёт туда же, и перестановка на ходу — это путаница ради
    редкого случая. Кому нужен другой порядок, удалит и добавит заново.

    Проеденные остановки не трогаем: их уже проехали, спорить не о чем.
    """
    order = session.get(InstantOrder, order_id)
    if not order or order.passenger_id != user.id:
        raise herr(404, "Заказ не найден", "Заказ табылманы")
    blocked = isv.can_change_destination(order)
    if blocked == "status":
        raise herr(409, "Сейчас маршрут поменять нельзя", "Хәҙер маршрутты үҙгәртеп булмай")
    if blocked == "too_often":
        raise herr(429, "Слишком часто. Подожди пару секунд", "Бик йыш. Бер-ике секунд көт")

    done = [w for w in isv.parse_waypoints(order.waypoints_json) if w.get("done")]
    fresh = done + [{**w.model_dump(), "done": False} for w in body.waypoints]
    quote = isv.destination_quote(session, order, (order.to_lat, order.to_lng),
                                  waypoints_override=fresh)
    if not quote.get("ok"):
        raise herr(503, "Не удалось посчитать цену", "Хаҡты иҫәпләп булманы")
    isv.apply_waypoints(session, order, fresh, quote)
    if order.driver_id:
        isv.notify_driver_destination(session, order, quote)
    return {**quote, "applied": True, "order": isv.order_payload(session, order, user)}


@router.post("/instant/orders/{order_id}/stop")
def toggle_stop(order_id: int, user: User = Depends(current_user),
                session: Session = Depends(get_session)):
    """Водитель отмечает «Стоим» на остановке и «Поехали», когда тронулись.

    Кнопкой, а не автоматом по координатам: машина, застрявшая в пробке у светофора рядом
    с остановкой, начала бы «зарабатывать» сама, а разбираться потом пришлось бы пассажиру.
    Нажал — оба видят, что счётчик пошёл: те же правила, что при подаче.
    """
    order = session.get(InstantOrder, order_id)
    if not order or order.driver_id != user.id:
        raise herr(404, "Заказ не найден", "Заказ табылманы")
    if order.status != S.onboard:
        raise herr(409, "Так можно только в поездке", "Былай тик сәфәр барышында була")
    order = isv.toggle_stop(session, order)
    return {"ok": True, "standing": order.stop_started_at is not None,
            "order": isv.order_payload(session, order, user)}


@router.post("/instant/orders/{order_id}/cancel")
def cancel(order_id: int, body: CancelIn | None = None, user: User = Depends(current_user),
           session: Session = Depends(get_session)):
    """Отмена заказа. Пассажир — до посадки; водитель — после accept. Причина опциональна.
    Водитель с reason="no_show" («пассажир не вышел») — только после «Я на месте» +
    бесплатное ожидание + запас; фиксирует no_show и штраф-подачу (Модель А, денег не двигаем)."""
    order = session.get(InstantOrder, order_id)
    if not order:
        raise herr(404, "Заказ не найден", "Заказ табылманы")
    if user.id == order.passenger_id:
        actor = isv.Actor.passenger
    elif user.id == order.driver_id:
        actor = isv.Actor.driver
    else:
        raise herr(403, "Нет доступа к заказу", "Заказға рөхсәт юҡ")
    reason = body.reason if body else ""
    order = isv.cancel_order(session, order_id, actor, user.id, reason)
    return isv.order_payload(session, order, user)


@router.get("/instant/orders/{order_id}/receipt")
def order_receipt(order_id: int, user: User = Depends(current_user),
                  session: Session = Depends(get_session)):
    """Квитанция за такси-поездку (по образцу /trips/{id}/receipt у попуток).

    Раньше чека за такси не было вообще: «мне на работе нужен документ о поездке» — дать
    нечего, а в споре «я заплатил / он не заплатил» не было ни одной записи (аудит 2026-07-26).
    Телефоны в квитанцию не кладём — только факт, маршрут, сумма и способ оплаты."""
    order = session.get(InstantOrder, order_id)
    if not order:
        raise herr(404, "Заказ не найден", "Заказ табылманы")
    if user.id not in (order.passenger_id, order.driver_id):
        raise herr(403, "Это не твой заказ", "Был һинең заказың түгел")
    if order.status != S.done:
        raise herr(409, "Квитанция появится после завершения поездки",
                   "Квитанция сәфәр тамамланғандан һуң күренәсәк")
    driver = session.get(User, order.driver_id) if order.driver_id else None
    payable = promo_ride.payable_kop(order)   # цена минус скидка по промокоду
    role = "driver" if order.driver_id == user.id else "passenger"

    # Строки счёта. Раньше в чеке была одна сумма и «в том числе ожидание» — на вопрос
    # «куда делись деньги» ответить было нечем. Теперь видно каждую часть.
    ride_price = isv.order_ride_price(order)
    ride_base = int(getattr(order, "ride_base_price", 0) or 0)
    surge_rub = max(ride_price - ride_base, 0) if ride_base > 0 else 0

    body = {
        "order_id": order.id,
        "role": role,
        "from_text": order.from_text, "to_text": order.to_text,
        "done_at": order.done_at.isoformat() if order.done_at else "",
        "distance_km": order.distance_km,
        # В чеке — сумма, которую человек РЕАЛЬНО заплатил (без промокода она равна цене).
        "amount": payable // 100,
        "amount_kop": payable,
        "price_kop": promo_ride.price_kop(order),          # цена поездки до скидки
        "promo_discount_kop": int(order.promo_discount_kop or 0),
        "waiting_fee_kop": order.waiting_fee_kop,
        # --- из чего сложилась сумма (аддитивно: старый клиент этих полей не читает) ---
        "ride_price": ride_price,
        "ride_base_price": ride_base,
        "surge_rub": surge_rub,
        "pickup_fee_kop": int(order.pickup_fee_kop or 0),
        "pickup_km": float(order.pickup_km or 0.0),
        "pickup_enroute": bool(getattr(order, "pickup_enroute", False)),
        "options_fee_kop": int(getattr(order, "options_fee_kop", 0) or 0),
        "options": car_class.parse_options(order.options),
        "weather_fee_kop": int(getattr(order, "weather_fee_kop", 0) or 0),
        "weather_kind": getattr(order, "weather_kind", "") or "",
        "payment_method": order.payment_method or "",
        "paid": bool(order.paid),
        "driver_name": (driver.name if driver and driver.name else "Водитель"),
        "driver_verified": bool(driver.verified) if driver else False,
    }

    # Комиссию видит ТОЛЬКО водитель — он её реально платит. Пассажиру её показывать нельзя:
    # в Модели А он платит водителю напрямую, наши 15% через него не проходят, и строка
    # «комиссия платформы 45 ₽» была бы неправдой о его собственных деньгах.
    if role == "driver":
        fee_percent = debt_mod.driver_fee_percent(session, user.id, order.created_at or utcnow())
        fee_kop = debt_mod.order_commission_kop(order, fee_percent)
        gross_kop = max(int(order.price_final if order.price_final is not None
                            else order.price_estimate), 0) * 100
        body.update({
            "driver_fee_percent": fee_percent,
            "driver_fee_kop": fee_kop,
            "driver_gross_kop": gross_kop,
            "driver_net_kop": max(gross_kop - fee_kop, 0),
            # С компенсаций комиссия не берётся — водителю важно видеть это отдельной цифрой,
            # иначе процент на экране не сходится с вычетом, и он считает нас лгунами.
            "commission_free_kop": isv.order_compensation_rub(order) * 100,
        })
    return body


# ------------------------------ «Что-то не так с ценой» ------------------------------
# Клапан для злости. У Яндекса это «Пожаловаться на цену», но слово «жалоба» из другого мира:
# у нас «между своими», и человек не жалуется на соседа — он не понимает наш расчёт.
#
# Принимаем и БЕЗ заказа: чаще всего возмущение рождается ДО него — увидел 450 ₽ и закрыл
# приложение. Именно эти случаи мы иначе не увидим никогда.
PRICE_COMPLAINT_REASONS = ("expensive_for_distance", "was_cheaper", "line_unclear", "other")


class PriceComplaintIn(BaseModel):
    # Заказ необязателен: жалуются чаще на оценку, чем на завершённую поездку.
    order_id: Optional[int] = None
    price: int = Field(0, ge=0, le=1_000_000)
    reason: str = Field("other", max_length=32)
    comment: str = Field("", max_length=500)
    # Строки счёта, как их видел человек. Координат тут нет и быть не должно.
    breakdown: dict = Field(default_factory=dict)


@router.post("/instant/price-complaint")
def price_complaint(body: PriceComplaintIn, user: User = Depends(current_user),
                    session: Session = Depends(get_session)):
    """Человек говорит, что с ценой что-то не так. Сохраняем сумму и расчёт, отвечаем тепло.

    Приватность (§8): в жалобу кладём ТОЛЬКО числа расчёта и причину. Ни адресов, ни
    координат: чтобы разобраться в цене, знать, откуда человек собирался ехать, не нужно.
    """
    if user_over_limit("price_complaint", user.id, 5):
        raise herr(429, "Спасибо, мы уже получили. Посмотрим и ответим",
                   "Рәхмәт, беҙ алдыҡ инде. Ҡарап сығып яуап бирербеҙ")
    reason = body.reason if body.reason in PRICE_COMPLAINT_REASONS else "other"
    order_id = None
    if body.order_id:
        order = session.get(InstantOrder, body.order_id)
        # Чужой заказ в жалобу не пишем: иначе по номеру можно было бы проверить, что заказ
        # существует. Молча роняем ссылку — сама жалоба всё равно принимается.
        if order is not None and user.id in (order.passenger_id, order.driver_id):
            order_id = order.id
    try:
        safe = {k: v for k, v in (body.breakdown or {}).items()
                if isinstance(v, (int, float, str, bool)) and len(str(k)) <= 32}
        session.add(PriceComplaint(
            user_id=user.id, order_id=order_id, price=int(body.price or 0),
            reason=reason, comment=(body.comment or "").strip()[:500],
            breakdown_json=json.dumps(safe, ensure_ascii=False)[:2000],
        ))
        session.commit()
    except Exception:  # noqa: BLE001 — сорвавшаяся запись не повод показать человеку ошибку
        session.rollback()
    return {"ok": True,
            "message": {"ru": "Спасибо. Посмотрим и ответим",
                        "ba": "Рәхмәт. Ҡарап сығып яуап бирербеҙ"}}


@router.get("/admin/price-complaints")
def admin_price_complaints(limit: int = 50, user: User = Depends(current_user),
                           session: Session = Depends(get_session)):
    """Жалобы на цену для админа: свежие сверху. Нужны, чтобы менять тариф по фактам,
    а не по ощущениям — и чтобы видеть, на какой сумме люди отваливаются."""
    if user.role != UserRole.admin:
        raise HTTPException(403, "Только для админа")
    rows = session.exec(
        select(PriceComplaint).order_by(PriceComplaint.id.desc()).limit(max(1, min(limit, 200)))
    ).all()
    return {"items": [{
        "id": r.id, "order_id": r.order_id, "price": r.price, "reason": r.reason,
        "comment": r.comment, "breakdown": r.breakdown_json,
        "created_at": r.created_at.isoformat() if r.created_at else "",
        "handled": r.handled_at is not None,
    } for r in rows]}


def _pay_method_or_default(method: str) -> str:
    """Способ расчёта из запроса. Незнакомое или выключенное → «договоримся на месте».

    Не ругаемся ошибкой: старый клиент поля не шлёт вовсе, а новый может прислать способ,
    который мы ещё не включили. И в том, и в другом случае честный ответ один — договорятся
    на месте, как это и работает сегодня.
    """
    m = (method or "").strip().lower()
    return m if isv.pay_method_open(m) else isv.PAY_NEGOTIATE


class PaymentMethodIn(BaseModel):
    """Новый способ расчёта."""
    method: str = Field("", max_length=16)


@router.post("/instant/orders/{order_id}/payment")
def set_payment_method(order_id: int, body: PaymentMethodIn,
                       user: User = Depends(current_user),
                       session: Session = Depends(get_session)):
    """Пассажир меняет способ расчёта — до самого конца поездки.

    Почему не только до заказа: про наличные человек вспоминает ровно тогда, когда лезет
    в карман, то есть уже сидя в машине. Запрещать смену значит заставлять его звонить
    водителю и договариваться голосом — то есть мимо приложения.

    Водителю уходит уведомление. Без него смена была бы тихой подменой договорённости:
    он везёт, рассчитывая на наличные, а на высадке узнаёт про перевод.
    """
    order = session.get(InstantOrder, order_id)
    if not order or order.passenger_id != user.id:
        raise herr(404, "Заказ не найден", "Заказ табылманы")
    if order.status in (S.done, S.cancelled, S.expired):
        raise herr(409, "Поездка уже завершена", "Сәфәр тамамланған")

    method = _pay_method_or_default(body.method)
    if method == (order.payment_method or isv.PAY_NEGOTIATE):
        return {"payment_method": method, "changed": False}
    order.payment_method = method
    # Помечаем смену только у заказа, который уже везут: до принятия водителя нет, и
    # «видел / не видел» не про кого. Иначе первый же выбор способа выглядел бы как
    # непрочитанное сообщение.
    if order.driver_id:
        order.payment_changed_at = utcnow()
        order.payment_ack_at = None
    session.add(order)
    session.commit()

    if order.driver_id:
        from ..services import push_bilingual   # локальный импорт — как в остальном файле
        ru, ba = isv.pay_method_label(method)
        push_bilingual(
            session, order.driver_id,
            "Способ оплаты изменён", "Түләү ысулы үҙгәрҙе",
            f"Пассажир будет платить: {ru.lower()}", f"Пассажир түләй: {ba.lower()}",
            data={"type": "instant_payment", "order_id": str(order.id), "method": method},
        )
    return {"payment_method": method, "changed": True}


@router.post("/instant/orders/{order_id}/payment/ack")
def ack_payment_method(order_id: int, user: User = Depends(current_user),
                       session: Session = Depends(get_session)):
    """Водитель подтверждает, что видел новый способ расчёта.

    Пуш за рулём пропускают, и без этой отметки пассажир не знает, дошло ли до водителя,
    что платить будут переводом. Заставить человека посмотреть в телефон мы не можем —
    можем честно сказать пассажиру, дошло или нет.
    """
    order = session.get(InstantOrder, order_id)
    if not order:
        raise herr(404, "Заказ не найден", "Заказ табылманы")
    if order.driver_id != user.id:
        raise herr(403, "Это не твой заказ", "Был һинең заказың түгел")
    if order.payment_changed_at is not None and order.payment_ack_at is None:
        order.payment_ack_at = utcnow()
        session.add(order)
        session.commit()
    return {"payment_method": order.payment_method or isv.PAY_NEGOTIATE, "acked": True}


@router.post("/instant/orders/{order_id}/cash-received")
def cash_received(order_id: int, user: User = Depends(current_user),
                  session: Session = Depends(get_session)):
    """Водитель подтверждает, что получил НАЛИЧНЫЕ за поездку.

    Раньше отметить оплату мог ТОЛЬКО пассажир: он вышел из машины и закрыл приложение —
    и заказ навсегда оставался «не оплачен», а в отчётах зияла дыра (аудит 2026-07-26).
    Деньги при этом мимо платформы (Модель А) — ledger не двигаем, только фиксируем факт."""
    order = session.get(InstantOrder, order_id)
    if not order:
        raise herr(404, "Заказ не найден", "Заказ табылманы")
    if order.driver_id != user.id:
        raise herr(403, "Это не твой заказ", "Был һинең заказың түгел")
    if order.status != S.done:
        raise herr(409, "Отметить оплату можно после завершения поездки",
                   "Түләүҙе сәфәр тамамланғандан һуң билдәләп була")
    if order.paid:
        return {"status": "already_paid", "method": order.payment_method}
    from .. import ledger
    amount_kop = promo_ride.payable_kop(order)   # наличными водитель берёт цену МИНУС скидку
    ledger.settle_instant_order(session, order.id, "cash", amount_kop)
    return {"status": "paid", "method": "cash", "amount_kop": amount_kop}


@router.post("/instant/orders/{order_id}/lost-item")
def lost_item(order_id: int, user: User = Depends(current_user),
              session: Session = Depends(get_session)):
    """«Я забыл вещь в машине» — открывает чат заказа на запись ещё на 48 часов.

    Раньше связаться было нечем: телефон второй стороны виден только пока заказ активен,
    а чат после завершения — только на чтение. Телефон, забытый на заднем сиденье, терялся
    навсегда (аудит 2026-07-26). Доступно обеим сторонам: водитель тоже находит вещи."""
    order = session.get(InstantOrder, order_id)
    if not order:
        raise herr(404, "Заказ не найден", "Заказ табылманы")
    if user.id not in (order.passenger_id, order.driver_id):
        raise herr(403, "Это не твой заказ", "Был һинең заказың түгел")
    if order.status != S.done:
        raise herr(409, "Доступно после завершения поездки", "Сәфәр тамамланғандан һуң мөмкин")
    order.lost_item_until = utcnow() + timedelta(hours=48)
    session.add(order)
    session.commit()
    other_id = order.driver_id if user.id == order.passenger_id else order.passenger_id
    if other_id:
        try:
            from ..services import push_notification
            # Запись, а не голый пуш: человек ищет свою вещь и вернётся к этому сообщению
            # позже — пуш к тому времени уже смахнули (аудит 2026-08-12, волна 24).
            push_notification(
                session, other_id, "instant",
                "Забытая вещь", "Онотолған әйбер",
                "Вторая сторона ищет вещь из этой поездки — чат снова открыт на 48 часов.",
                "Сәфәрҙән әйбер эҙләйҙәр — чат 48 сәғәткә асыҡ.",
                ref_kind="instant", ref_id=order.id,
                # «order_chat», а не «chat»: id — заказ такси. Под «chat» приложение
                # открывает бронь попутки с этим номером (аудит 2026-08-08).
                data={"type": "order_chat", "id": order.id},
            )
        except Exception:  # noqa: BLE001 — пуш вторичен
            pass
    return {"ok": True, "chat_open_until": order.lost_item_until.isoformat()}
