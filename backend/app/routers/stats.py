"""Экран «Мой Юлдаш» — личная статистика попутчика.

`GET /me/stats` (auth) — агрегаты ТОЛЬКО по своим данным:
  • км, проеханные вместе (сумма дистанций завершённых поездок);
  • число поездок (как пассажир: завершённые брони + как водитель: завершённые поездки);
  • сэкономлено ₽ (ориентир такси, коэффициент в config — уточнит Александр);
  • сэкономлено CO₂, кг (коэффициент в config — уточнит Александр);
  • «звание» попутчика (по числу поездок) + прогресс до следующего.

Быстрые заказы (такси) считаются в «поездках» и «километрах» наравне с попутками —
человек ездил с нами, и экран это обязан показывать. Но в «сэкономлено ₽» и «CO₂» они
НЕ идут: поездка на такси не экономит ни рубля относительно такси и никого не подвозит
попутно. Цифры не фейкуем (аудит 2026-08-06: такси не считалось нигде вообще).

Приватность: current_user → пользователь видит только свою статистику, чужую — нет.
Новый пользователь (0 поездок) → все нули + звание уровня 0 (без «ложных наград»).
Дистанция берётся из сохранённых координат концов маршрута, иначе — геокод города
(известные города БашРТ, бесплатно). Нет ни того, ни другого → поездка считается,
но без километража (не завышаем цифры).
"""
from fastapi import APIRouter, Depends
from sqlalchemy import func, or_
from sqlmodel import Session, select

from ..config import settings
from ..db import get_session
from ..models import (
    Booking, BookingStatus, InstantOrder, InstantOrderStatus, ParcelDelivery, Ride, RideStatus, User,
)
from ..security import current_user
from ..services import geocode_city, haversine_km
from ..timeutil import utcnow

router = APIRouter(tags=["stats"])


# Звания попутчика по числу завершённых поездок (порог включительно).
# level 0 — стартовое, БЕЗ награды (новый юзер): «нули без ложных званий».
# Башкирский — черновик (docs/tasks.md «Переводы на проверку — F18»).
RANKS = [
    (0,  "Новичок",            "Яңы юлдаш"),
    (1,  "Попутчик",           "Юлдаш"),
    (10, "Бывалый попутчик",   "Тәжрибәле юлдаш"),
    (30, "Мастер дорог",       "Юл оҫтаһы"),
    (75, "Аҡһаҡал дороги",     "Юл аҡһаҡалы"),
]


def _rank_for(trips: int) -> dict:
    """Звание по числу поездок + прогресс до следующего уровня (для полоски в UI)."""
    level = 0
    for i, (threshold, _ru, _ba) in enumerate(RANKS):
        if trips >= threshold:
            level = i
    cur = RANKS[level]
    nxt = RANKS[level + 1] if level + 1 < len(RANKS) else None
    return {
        "level": level,
        "title_ru": cur[1],
        "title_ba": cur[2],
        # Следующее звание и сколько поездок до него (None → уже максимум).
        "next_title_ru": nxt[1] if nxt else None,
        "next_title_ba": nxt[2] if nxt else None,
        "next_at": nxt[0] if nxt else None,
        "to_next": max(0, nxt[0] - trips) if nxt else 0,
    }


def _ride_km(ride: Ride, geo=geocode_city) -> float:
    """Дистанция поездки в км. Сначала сохранённые координаты концов маршрута,
    иначе геокод городов (известные города БашРТ — бесплатно). Нет данных → 0.
    `geo` — геокодер (по умолчанию `geocode_city`); в `my_stats` передаём мемоизированный,
    чтобы один и тот же город не геокодился заново для каждой поездки (новая Session + Яндекс)."""
    if (
        ride.from_lat is not None and ride.from_lng is not None
        and ride.to_lat is not None and ride.to_lng is not None
    ):
        return haversine_km(ride.from_lat, ride.from_lng, ride.to_lat, ride.to_lng)
    f = geo(ride.from_city)
    t = geo(ride.to_city)
    if f and t:
        return haversine_km(f[0], f[1], t[0], t[1])
    return 0.0


@router.get("/me/stats")
def my_stats(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Личная статистика текущего пользователя (пассажир + водитель)."""
    # Кешируем дистанцию поездки внутри запроса (одна поездка ↔ несколько ролей/строк).
    km_cache: dict[int, float] = {}
    # Мемо геокода по имени города за запрос: один город геокодится РАЗ, а не для каждой поездки
    # (иначе N поездок = 2N новых Session + 2N блокирующих Яндекс-вызовов на горячем /me/stats).
    geo_memo: dict[str, object] = {}

    def _geo(city: str):
        if city not in geo_memo:
            geo_memo[city] = geocode_city(city)
        return geo_memo[city]

    def km_of(ride: Ride) -> float:
        if ride.id not in km_cache:
            km_cache[ride.id] = _ride_km(ride, geo=_geo)
        return km_cache[ride.id]

    trips = 0
    total_km = 0.0

    # --- Как пассажир: завершённые брони ---
    done_bookings = session.exec(
        select(Booking).where(
            Booking.passenger_id == user.id,
            Booking.status == BookingStatus.done,
        )
    ).all()
    ride_ids = {b.ride_id for b in done_bookings}
    rides_by_id = (
        {r.id: r for r in session.exec(select(Ride).where(Ride.id.in_(ride_ids))).all()}
        if ride_ids else {}
    )
    for b in done_bookings:
        r = rides_by_id.get(b.ride_id)
        if not r:
            continue
        trips += 1
        total_km += km_of(r)

    # --- Как водитель: завершённые поездки ---
    done_rides = session.exec(
        select(Ride).where(
            Ride.driver_id == user.id,
            Ride.status == RideStatus.done,
        )
    ).all()
    for r in done_rides:
        trips += 1
        total_km += km_of(r)

    # «Сэкономлено» и «CO₂» считаем ТОЛЬКО по попуткам: такси не экономит относительно такси
    # и никого не подвозит попутно. Фиксируем километраж до добавления быстрых заказов.
    share_km = total_km

    # --- Быстрые заказы (такси), обе роли: до аудита 2026-08-06 их не считал никто, и человек
    # со ста заказами видел «0 поездок, Новичок». Расстояние берём с самого заказа.
    done_orders = session.exec(
        select(InstantOrder).where(
            or_(InstantOrder.passenger_id == user.id, InstantOrder.driver_id == user.id),
            InstantOrder.status == InstantOrderStatus.done,
        )
    ).all()
    for o in done_orders:
        trips += 1
        total_km += max(0.0, o.distance_km or 0.0)

    total_km = round(total_km, 1)
    saved_rub = round(share_km * settings.stats_taxi_rub_per_km)
    co2_saved_kg = round(share_km * settings.stats_co2_grams_per_km / 1000.0, 1)

    return {
        "trips": trips,
        "km": total_km,
        "saved_rub": saved_rub,
        "co2_saved_kg": co2_saved_kg,
        "rank": _rank_for(trips),
        # Прозрачность коэффициентов для клиента (можно показать «как считали»).
        "coeffs": {
            "taxi_rub_per_km": settings.stats_taxi_rub_per_km,
            "co2_grams_per_km": settings.stats_co2_grams_per_km,
        },
    }


# G8 — Достижения-«пряники»: тёплые бейджи профиля из РЕАЛЬНЫХ данных. Только украшение и
# удержание в духе «между своими» — на распределение заказов НЕ влияют. Цифры не фейкуем.
# BA — черновики (docs/tasks.md на проверку). (code, ru, ba, метрика, порог включительно).
_ACHIEVEMENTS = [
    ("first_trip",        "Первая поездка",    "Беренсе сәфәр",           "trips",    1),
    ("trips_10",          "10 поездок",        "10 сәфәр",                "trips",    10),
    ("trips_50",          "50 поездок",        "50 сәфәр",                "trips",    50),
    ("trips_100",         "100 поездок",       "100 сәфәр",               "trips",    100),
    ("parcel_helper",     "Помог 5 посылкам",  "5 ебәрмәгә ярҙам иттең",  "parcels",  5),
    ("year_with_yuldash", "Год с Юлдаш",       "Юлдаш менән бер йыл",     "days",     365),
    ("verified",          "Проверенный",       "Тикшерелгән",             "verified", 1),
]


@router.get("/me/achievements")
def my_achievements(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """G8: тёплые бейджи профиля из реальных данных (поездки + помощь посылкам + стаж + проверка).
    Только свои. На распределение заказов НЕ влияют — украшение и удержание. Пороги включительно,
    для не полученных показываем прогресс (value/goal). Двуязычно."""
    trips_pax = session.exec(
        select(func.count()).select_from(Booking)
        .where(Booking.passenger_id == user.id, Booking.status == BookingStatus.done)
    ).one()
    trips_drv = session.exec(
        select(func.count()).select_from(Ride)
        .where(Ride.driver_id == user.id, Ride.status == RideStatus.done)
    ).one()
    # Быстрые заказы (такси) — те же поездки: иначе значок «Первая поездка» не приходит
    # человеку, который уже съездил десять раз (аудит 2026-08-06).
    trips_taxi = session.exec(
        select(func.count()).select_from(InstantOrder)
        .where(
            or_(InstantOrder.passenger_id == user.id, InstantOrder.driver_id == user.id),
            InstantOrder.status == InstantOrderStatus.done,
        )
    ).one()
    trips = int(trips_pax or 0) + int(trips_drv or 0) + int(trips_taxi or 0)
    parcels = int(session.exec(
        select(func.count()).select_from(ParcelDelivery)
        .where(ParcelDelivery.courier_id == user.id, ParcelDelivery.status == "delivered")
    ).one() or 0)
    days = (utcnow() - user.created_at).days if user.created_at else 0
    metric = {"trips": trips, "parcels": parcels, "days": days, "verified": 1 if user.verified else 0}
    badges = [
        {"code": code, "ru": ru, "ba": ba, "goal": goal,
         "value": metric[m], "earned": metric[m] >= goal}
        for (code, ru, ba, m, goal) in _ACHIEVEMENTS
    ]
    return {
        "trips": trips,
        "parcels_helped": parcels,
        "days_with_yuldash": days,
        "earned_count": sum(1 for b in badges if b["earned"]),
        "achievements": badges,
    }
