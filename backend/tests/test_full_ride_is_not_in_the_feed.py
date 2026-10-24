"""Поездка без свободных мест не висит в общей ленте (аудит 2026-08-07).

Выдача «Ближайшие» (`/rides/near`) отсеивает поездки без мест с самого начала, а общая
лента (`/rides`) — нет. Заметнее всего это на поездке, созданной из принятого отклика: она
рождается сразу занятой под одного пассажира и всё равно оставалась в списке.

Что видел человек: открывает карточку, жмёт «Забронировать» и получает отказ. Это читается
как поломка приложения, а не как «мест уже нет» — тем более что поездка была прямо перед
глазами секунду назад.
"""
from datetime import timedelta

from sqlalchemy import func
from sqlmodel import Session, select

from app.db import engine
from app.models import Ride, RideStatus, UserRole
from app.timeutil import utcnow
from feed_hygiene import rides_do_not_leak_into_the_feed  # noqa: F401 — собственные поездки не остаются в общей ленте


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
    # Положительный контроль может быть дальше первых 200 поездок общей БД. Явный limit
    # также обходит кеш: _make_ride пишет напрямую в тестовую БД, без notify_map_changed.
    # Общее число записей — только безопасная верхняя граница страниц, НЕ повтор фильтра API.
    # После выборки сервер ещё фильтрует видимость: пустая/короткая страница не доказывает конец.
    with Session(engine) as s:
        total = s.exec(select(func.count(Ride.id))).one()
    ids = set()
    page_size = 200
    for offset in range(0, total + page_size, page_size):
        r = client.get(f"/rides?limit={page_size}&offset={offset}", headers=auth)
        assert r.status_code == 200, r.text
        body = r.json()
        items = body if isinstance(body, list) else body.get("items", [])
        ids.update(it["id"] for it in items)
    return ids


def test_ride_without_seats_is_hidden(client, user_factory):
    drv = user_factory("Водитель", role=UserRole.driver)
    pax = user_factory("Пассажир")
    free = _make_ride(drv["id"], seats_left=2)
    full = _make_ride(drv["id"], seats_left=0)

    # Даже отдельно от большого набора free лежит после первой страницы: старый helper
    # обязан был бы упасть, а отрицательный full-контроль теперь проверяет всю выдачу.
    with Session(engine) as s:
        s.add_all([Ride(driver_id=drv["id"], from_city="Баймак", to_city="Сибай",
                        depart_at=utcnow() + timedelta(hours=2), seats_total=3, seats_left=1,
                        price=300, status=RideStatus.active) for _ in range(201)])
        s.commit()

    ids = _feed_ids(client, pax["auth"])
    assert free in ids, "поездка со свободными местами пропала из ленты"
    assert full not in ids, "поездка без мест осталась в ленте — человек жмёт «Забронировать» и получает отказ"
