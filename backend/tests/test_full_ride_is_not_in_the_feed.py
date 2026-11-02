"""Поездка без свободных мест не висит в общей ленте (аудит 2026-08-07).

Выдача «Ближайшие» (`/rides/near`) отсеивает поездки без мест с самого начала, а общая
лента (`/rides`) — нет. Заметнее всего это на поездке, созданной из принятого отклика: она
рождается сразу занятой под одного пассажира и всё равно оставалась в списке.

Что видел человек: открывает карточку, жмёт «Забронировать» и получает отказ. Это читается
как поломка приложения, а не как «мест уже нет» — тем более что поездка была прямо перед
глазами секунду назад.
"""
from datetime import timedelta
from uuid import uuid4

import pytest
from sqlalchemy import update
from sqlmodel import Session

from app.db import engine
from app.models import Ride, RideStatus, UserRole
from app.timeutil import utcnow


def _make_ride(driver_id: int, seats_left: int, from_city: str) -> int:
    with Session(engine) as s:
        ride = Ride(driver_id=driver_id, from_city=from_city, to_city="Сибай",
                    depart_at=utcnow() + timedelta(hours=3), seats_total=4, seats_left=seats_left,
                    price=300, status=RideStatus.active)
        s.add(ride)
        s.commit()
        s.refresh(ride)
        return ride.id


def _feed_ids(client, auth, **params) -> set:
    r = client.get("/rides", headers=auth, params=params)
    assert r.status_code == 200, r.text
    body = r.json()
    items = body if isinstance(body, list) else body.get("items", [])
    return {it["id"] for it in items}


@pytest.mark.parametrize("earlier_rides", [0, 200])
def test_ride_without_seats_is_hidden(client, user_factory, earlier_rides):
    drv = user_factory("Водитель", role=UserRole.driver)
    pax = user_factory("Пассажир")
    # Первая страница общей sessionDB ограничена 200 строками. Ищем свой маршрут,
    # сохраняя обе проверки мест, а не требуем попадания новой поездки в общий топ.
    route = f"Баймак-{uuid4().hex[:12]}"
    created = []
    try:
        with Session(engine) as s:
            rows = [Ride(driver_id=drv["id"], from_city="Уфа", to_city="Белорецк",
                         depart_at=utcnow() + timedelta(hours=1), seats_total=4, seats_left=4,
                         price=300, status=RideStatus.active,
                         boosted_until=utcnow() + timedelta(days=1)) for _ in range(earlier_rides)]
            s.add_all(rows)
            s.commit()
            for row in rows:
                s.refresh(row)
                created.append(row.id)
        free = _make_ride(drv["id"], seats_left=2, from_city=route)
        created.append(free)
        full = _make_ride(drv["id"], seats_left=0, from_city=route)
        created.append(full)

        if earlier_rides:
            assert free not in _feed_ids(client, pax["auth"]), "контроль переполнения первой страницы не сработал"
        ids = _feed_ids(client, pax["auth"], from_city=route)
        assert free in ids, "поездка со свободными местами пропала из ленты"
        assert full not in ids, "поездка без мест осталась в ленте — человек жмёт «Забронировать» и получает отказ"
    finally:
        # Только собственные записи: не оставляем 200 поднятых поездок следующим тестам.
        if created:
            with Session(engine) as s:
                s.execute(update(Ride).where(Ride.id.in_(created))
                          .values(status=RideStatus.cancelled, boosted_until=None))
                s.commit()
