"""Поездка без свободных мест не висит в общей ленте (аудит 2026-08-07).

Выдача «Ближайшие» (`/rides/near`) отсеивает поездки без мест с самого начала, а общая
лента (`/rides`) — нет. Заметнее всего это на поездке, созданной из принятого отклика: она
рождается сразу занятой под одного пассажира и всё равно оставалась в списке.

Что видел человек: открывает карточку, жмёт «Забронировать» и получает отказ. Это читается
как поломка приложения, а не как «мест уже нет» — тем более что поездка была прямо перед
глазами секунду назад.
"""
from datetime import timedelta
import uuid

from sqlmodel import Session

from app.db import engine
from app.models import Ride, RideStatus, UserRole
from app.timeutil import utcnow


def _make_ride(driver_id: int, seats_left: int, *, from_city: str, to_city: str) -> int:
    with Session(engine) as s:
        ride = Ride(driver_id=driver_id, from_city=from_city, to_city=to_city,
                    depart_at=utcnow() + timedelta(hours=3), seats=4, seats_left=seats_left,
                    price=300, status=RideStatus.active)
        s.add(ride)
        s.commit()
        s.refresh(ride)
        return ride.id


def _feed_ids(client, auth, *, from_city: str, to_city: str) -> set:
    r = client.get("/rides", headers=auth, params={"from_city": from_city, "to_city": to_city})
    assert r.status_code == 200, r.text
    body = r.json()
    items = body if isinstance(body, list) else body.get("items", [])
    return {it["id"] for it in items}


def test_ride_without_seats_is_hidden(client, user_factory):
    drv = user_factory("Водитель", role=UserRole.driver)
    pax = user_factory("Пассажир")
    # Обе поездки проверяются вместе; соседние тесты не вытесняют свободную из первой страницы.
    tag = uuid.uuid4().hex[:8]
    route = {"from_city": f"МестаОт{tag}", "to_city": f"МестаДо{tag}"}
    free = _make_ride(drv["id"], seats_left=2, **route)
    full = _make_ride(drv["id"], seats_left=0, **route)

    ids = _feed_ids(client, pax["auth"], **route)
    assert free in ids, "поездка со свободными местами пропала из ленты"
    assert full not in ids, "поездка без мест осталась в ленте — человек жмёт «Забронировать» и получает отказ"
