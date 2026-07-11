"""Экран «Мой Юлдаш» — личная статистика попутчика.

`GET /me/stats` (auth) — агрегаты ТОЛЬКО по своим данным:
  • км, проеханные вместе (сумма дистанций завершённых поездок);
  • число поездок (как пассажир: завершённые брони + как водитель: завершённые поездки);
  • сэкономлено ₽ (ориентир такси, коэффициент в config — уточнит Александр);
  • сэкономлено CO₂, кг (коэффициент в config — уточнит Александр);
  • «звание» попутчика (по числу поездок) + прогресс до следующего.

Приватность: current_user → пользователь видит только свою статистику, чужую — нет.
Новый пользователь (0 поездок) → все нули + звание уровня 0 (без «ложных наград»).
Дистанция берётся из сохранённых координат концов маршрута, иначе — геокод города
(известные города БашРТ, бесплатно). Нет ни того, ни другого → поездка считается,
но без километража (не завышаем цифры).
"""
from fastapi import APIRouter, Depends
from sqlmodel import Session, select

from ..config import settings
from ..db import get_session
from ..models import Booking, BookingStatus, Ride, RideStatus, User
from ..security import current_user
from ..services import geocode_city, haversine_km

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


def _ride_km(ride: Ride) -> float:
    """Дистанция поездки в км. Сначала сохранённые координаты концов маршрута,
    иначе геокод городов (известные города БашРТ — бесплатно). Нет данных → 0."""
    if (
        ride.from_lat is not None and ride.from_lng is not None
        and ride.to_lat is not None and ride.to_lng is not None
    ):
        return haversine_km(ride.from_lat, ride.from_lng, ride.to_lat, ride.to_lng)
    f = geocode_city(ride.from_city)
    t = geocode_city(ride.to_city)
    if f and t:
        return haversine_km(f[0], f[1], t[0], t[1])
    return 0.0


@router.get("/me/stats")
def my_stats(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Личная статистика текущего пользователя (пассажир + водитель)."""
    # Кешируем дистанцию поездки внутри запроса (одна поездка ↔ несколько ролей/строк).
    km_cache: dict[int, float] = {}

    def km_of(ride: Ride) -> float:
        if ride.id not in km_cache:
            km_cache[ride.id] = _ride_km(ride)
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

    total_km = round(total_km, 1)
    saved_rub = round(total_km * settings.stats_taxi_rub_per_km)
    co2_saved_kg = round(total_km * settings.stats_co2_grams_per_km / 1000.0, 1)

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
