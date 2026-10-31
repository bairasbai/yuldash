"""Места по участкам маршрута (RS-02 А) — единственная дверь для записи свободных мест.

Точки маршрута: P0 = from_city, P1…Pn = parse_stops, Pn+1 = to_city; участок i = Pi → Pi+1.
Бронь занимает [a, b): посадка «»/None/незнакомая → 0, высадка → n+1 (старая бронь = весь рейс).

Правда — живые брони; в `ride` лежит снимок свободных мест по участкам (`leg_free`), чтобы
лента не ходила в базу на каждую поездку. Каждая запись снимка — условный UPDATE (CAS) по
проверенной `terms_version` и прежнему `leg_free`, как прежний `seats_left >= N`: держит и
PostgreSQL (рядом с FOR UPDATE), и SQLite, где блокировки строки нет. `seats_left` — min по
участкам (весь путь, осторожно для старых клиентов), `seats_free_max` — max.
"""
from __future__ import annotations

from typing import Optional, Sequence, Tuple

from sqlalchemy import or_
from sqlmodel import select, update

from .models import Booking, BookingStatus, Ride
from .ride_stops import parse_stops

LIVE = (BookingStatus.pending, BookingStatus.confirmed, BookingStatus.onboard)


def legs_count(ride) -> int:
    return len(parse_stops(ride)) + 1


def _span_on_stops(stops: Sequence[str], pickup: Optional[str], dropoff: Optional[str]) -> Tuple[int, int]:
    n = len(stops)
    a = stops.index(pickup) + 1 if pickup and pickup in stops else 0
    b = stops.index(dropoff) + 1 if dropoff and dropoff in stops else n + 1
    return a, max(a, b)   # пустой участок не уходит в минус


def span(ride, pickup: Optional[str], dropoff: Optional[str], entry: Optional[dict] = None) -> Tuple[int, int]:
    """Участок [a, b) брони по её точкам посадки/высадки."""
    return _span_on_stops(parse_stops(ride, entry), pickup, dropoff)


def _parse_leg_free(ride, count: Optional[int] = None) -> Optional[list[int]]:
    raw = (getattr(ride, "leg_free", "") or "").strip()
    if not raw:
        return None
    try:
        legs = [int(x) for x in raw.split(",")]
    except ValueError:
        return None
    return legs if len(legs) == (count if count is not None else legs_count(ride)) else None


def _initialized(ride) -> bool:
    return _parse_leg_free(ride) is not None


def _dump(legs: Sequence[int]) -> str:
    return ",".join(str(int(x)) for x in legs)


def legs_free(ride, count: Optional[int] = None) -> list[int]:
    """Свободные места по участкам из снимка; снимок не заведён — старый счётчик на все участки.
    `count` — уже известное число участков (выдачи считают стопы сами, без повторного разбора)."""
    legs = _parse_leg_free(ride, count)
    if legs is not None:
        return legs
    return [ride.seats_left] * (count if count is not None else legs_count(ride))


def free_on(ride, sp: Tuple[int, int], count: Optional[int] = None) -> int:
    a, b = sp
    if a >= b:
        return 0
    return min(legs_free(ride, count)[a:b])


def max_free(ride, count: Optional[int] = None) -> int:
    legs = legs_free(ride, count)
    return max(legs) if legs else 0


def occupancy_snapshot(session, ride, proposed_stops: Optional[Sequence[str]] = None,
                       proposed_seats_total: Optional[int] = None) -> list[int]:
    """Чистый расчёт свободных мест по участкам из живых броней. Без записи, commit и роста версии."""
    stops = list(proposed_stops) if proposed_stops is not None else parse_stops(ride)
    total = ride.seats_total if proposed_seats_total is None else proposed_seats_total
    legs = [total] * (len(stops) + 1)
    rows = session.exec(
        select(Booking.seats, Booking.pickup_stop, Booking.dropoff_stop)
        .where(Booking.ride_id == ride.id, Booking.status.in_(LIVE))
    ).all()
    for seats, pickup, dropoff in rows:
        a, b = _span_on_stops(stops, pickup, dropoff)
        for i in range(a, b):
            legs[i] -= seats
    return legs


def occupancy(session, ride) -> list[int]:
    """Совместимый alias чистого расчёта."""
    return occupancy_snapshot(session, ride)


def _write_cas(session, ride, legs: Sequence[int], extra_where=()) -> bool:
    """Одна CAS-попытка записать снимок: WHERE id + проверенная версия + прежний leg_free."""
    old_leg_free = ride.leg_free or ""
    new_leg_free = _dump(legs)
    seats_left = max(0, min(legs))
    seats_free_max = max(0, max(legs))
    res = session.execute(
        update(Ride)
        .where(Ride.id == ride.id, Ride.terms_version == ride.terms_version,
               Ride.leg_free == old_leg_free, *extra_where)
        .values(leg_free=new_leg_free, seats_left=seats_left, seats_free_max=seats_free_max,
                terms_version=Ride.terms_version + 1)
        # Синхронизация сессии выключена: объект обновляем сами ниже — иначе ORM прибавил бы
        # версию ещё раз поверх SQL-инкремента (double bump).
        .execution_options(synchronize_session=False)
    )
    if res.rowcount == 0:
        return False
    ride.leg_free = new_leg_free
    ride.seats_left = seats_left
    ride.seats_free_max = seats_free_max
    ride.terms_version += 1
    return True


def recompute(session, ride) -> list[int]:
    """Пересчитать снимок из живых броней и записать одной CAS-попыткой. Без commit."""
    legs = occupancy_snapshot(session, ride)
    _write_cas(session, ride, legs)
    return legs


def write_terms(session, ride, legs: Sequence[int], extra_where=()) -> bool:
    """CAS-запись снимка/остатков с ростом terms_version — для правок условий рейса, где
    снимок идёт в одном commit с остальными полями (edit_ride; самостоятельно — recompute)."""
    return _write_cas(session, ride, legs, extra_where=extra_where)


def take(session, ride, sp: Tuple[int, int], seats: int, expected_terms_version: Optional[int] = None,
         extra_where=()) -> bool:
    """Ровно одна CAS-попытка занять `seats` на участке [a, b).

    Версию и условия целиком проверил внешний алгоритм до вызова; take не перечитывает версию
    и не продолжает со старой ценой/машиной. Снимок не заведён — считаем occupancy под теми
    же замками. False — мест не хватает или версия ушла (внешняя транзакция откатывается)."""
    if expected_terms_version is not None and ride.terms_version != expected_terms_version:
        return False
    legs = legs_free(ride) if _initialized(ride) else occupancy_snapshot(session, ride)
    a, b = sp
    if a >= b or min(legs[a:b]) < seats:
        return False
    new_legs = legs[:]
    for i in range(a, b):
        new_legs[i] -= seats
    return _write_cas(session, ride, new_legs, extra_where=extra_where)


def release(session, ride_id: int, sp: Tuple[int, int], seats: int) -> None:
    """Вернуть `seats` на участке брони после смены её статуса (та же CAS, до seats_total)."""
    ride = session.exec(select(Ride).where(Ride.id == ride_id)).first()
    if ride is None:
        return
    if not _initialized(ride):
        # Снимка не было — пересчёт из живых броней уже учитывает ушедшую бронь.
        recompute(session, ride)
        return
    a, b = sp
    new_legs = legs_free(ride)
    for i in range(a, b):
        new_legs[i] = min(ride.seats_total, new_legs[i] + seats)
    _write_cas(session, ride, new_legs)


def any_free_sql(n: int = 1):
    """«Есть место хотя бы на одном участке» для выдач — фильтр по столбцам, без заполнения базы."""
    return or_(Ride.seats_left >= n, Ride.seats_free_max >= n)
