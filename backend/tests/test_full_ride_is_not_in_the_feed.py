"""Поездка без свободных мест не висит в общей ленте (аудит 2026-08-07).

Выдача «Ближайшие» (`/rides/near`) отсеивает поездки без мест с самого начала, а общая
лента (`/rides`) — нет. Заметнее всего это на поездке, созданной из принятого отклика: она
рождается сразу занятой под одного пассажира и всё равно оставалась в списке.

Что видел человек: открывает карточку, жмёт «Забронировать» и получает отказ. Это читается
как поломка приложения, а не как «мест уже нет» — тем более что поездка была прямо перед
глазами секунду назад.
"""
from datetime import timedelta

from sqlmodel import Session

from app.db import engine
from app.models import Ride, RideStatus, UserRole
from app.timeutil import utcnow


def _make_ride(driver_id: int, seats_left: int) -> int:
    with Session(engine) as s:
        ride = Ride(driver_id=driver_id, from_city="Баймак", to_city="Сибай",
                    depart_at=utcnow() + timedelta(hours=3), seats=4, seats_left=seats_left,
                    price=300, status=RideStatus.active)
        s.add(ride)
        s.commit()
        s.refresh(ride)
        return ride.id


def _feed_ids(client, auth) -> set:
    r = client.get("/rides", headers=auth)
    assert r.status_code == 200, r.text
    body = r.json()
    items = body if isinstance(body, list) else body.get("items", [])
    return {it["id"] for it in items}


def test_ride_without_seats_is_hidden(client, user_factory):
    drv = user_factory("Водитель", role=UserRole.driver)
    pax = user_factory("Пассажир")
    free = _make_ride(drv["id"], seats_left=2)
    full = _make_ride(drv["id"], seats_left=0)

    ids = _feed_ids(client, pax["auth"])
    assert free in ids, "поездка со свободными местами пропала из ленты"
    assert full not in ids, "поездка без мест осталась в ленте — человек жмёт «Забронировать» и получает отказ"
