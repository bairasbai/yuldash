"""Последнее место нельзя продать дважды — и это должно быть ПРОВЕРЯЕМО.

Точная картина, без преувеличения. Овербукинг в проде закрыт с самого начала: бронь
блокирует строку поездки (`select(Ride)…with_for_update()` в начале `book`), и на
PostgreSQL второй запрос ждёт коммита первого, а дождавшись — перечитывает уже нулевые
места и честно получает отказ. То есть на боевом сервере двойной брони не было.

Дырявым это было в двух местах:

1. **SQLite игнорирует `FOR UPDATE`.** На нём работают тесты, локальная разработка и
   демо-база эмулятора. Там блокировки нет вообще, и число мест правда уходило в минус:
   `ride.seats_left -= body.seats` — обычные чтение и запись, между которыми помещается
   чужая бронь. Проверить поведение приложения на дефекте, которого «в проде нет», было
   негде — а именно на этих базах и проверяют.

2. **Защиту не проверял НИ ОДИН тест.** `with_for_update()` на SQLite — пустая операция,
   поэтому её удаление (при рефакторинге, при переносе запроса) не уронило бы ничего.
   Единственная защита от овербукинга держалась на строке, которую можно было стереть
   незамеченной.

Что сделано: условие «мест хватает» переехало ВНУТРЬ `UPDATE`. Это работает на обеих
базах — база сама отсекает вторую бронь, ноль изменённых строк → 409. Блокировка строки
осталась на месте: она по-прежнему сериализует всю остальную проверку.

Почему обычный тест этого не ловил. `test_overbooking_blocked` бронирует ПО ОЧЕРЕДИ:
первая бронь успела записаться, и вторая честно получает отказ. Гонка живёт только там,
где запросы идут одновременно, — значит и проверять её надо одновременными запросами.
На старом коде эти тесты дают [200, 200], то есть дефект воспроизводят.
"""
from __future__ import annotations

import threading

from sqlmodel import Session, select

from app.db import engine
from app.models import Booking, BookingStatus, Ride, UserRole


def _ride(client, drv, seats=3, **extra):
    r = client.post("/rides", headers=drv["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай",
        "depart_at": "2030-01-01T10:00:00", "seats_total": seats, "price": 300, **extra,
    })
    assert r.status_code == 200, r.text
    return r.json()["id"]


def _seats_left(ride_id: int) -> int:
    with Session(engine) as s:
        return s.get(Ride, ride_id).seats_left


def _live_bookings(ride_id: int) -> int:
    with Session(engine) as s:
        return len(s.exec(
            select(Booking).where(
                Booking.ride_id == ride_id,
                Booking.status.in_([BookingStatus.pending, BookingStatus.confirmed, BookingStatus.onboard]),
            )
        ).all())


def _book_together(client, ride_id: int, riders: list[dict]) -> list[int]:
    """Все пассажиры жмут «Забронировать» в один момент. Барьер держит потоки до последнего,
    иначе они разъезжаются по времени и гонки не получается."""
    codes: list[int] = []
    lock = threading.Lock()
    barrier = threading.Barrier(len(riders))

    def book(rider: dict) -> None:
        barrier.wait()
        r = client.post("/bookings", headers=rider["auth"], json={"ride_id": ride_id, "seats": 1})
        with lock:
            codes.append(r.status_code)

    threads = [threading.Thread(target=book, args=(r,)) for r in riders]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    return codes


def test_последнее_место_не_уходит_двоим(client, user_factory):
    drv = user_factory("Водитель", role=UserRole.driver)
    ride_id = _ride(client, drv, seats=1)
    riders = [user_factory("Айгуль"), user_factory("Рустам")]

    codes = _book_together(client, ride_id, riders)

    assert 200 in codes, f"ни одна бронь не прошла: {codes}"
    assert _live_bookings(ride_id) == 1, (
        "одно место продано дважды — водитель приедет, а пассажиров двое (ответы: %s)" % codes
    )
    assert _seats_left(ride_id) == 0, "число мест разъехалось с числом броней"


def test_мест_не_бывает_меньше_нуля(client, user_factory):
    """Трое на одно место: счётчик не должен уйти в минус — иначе поездка пропадёт
    из поиска (`Ride.seats_left > 0`), хотя места в машине ещё есть."""
    drv = user_factory("Водитель", role=UserRole.driver)
    ride_id = _ride(client, drv, seats=1)
    riders = [user_factory(f"Пассажир{i}") for i in range(3)]

    _book_together(client, ride_id, riders)

    assert _seats_left(ride_id) >= 0, "счётчик мест ушёл в минус"
    assert _live_bookings(ride_id) == 1, "мест продано больше, чем было"


def test_обычная_бронь_по_прежнему_работает(client, user_factory):
    """Защита от гонки не должна мешать обычному случаю."""
    drv = user_factory("Водитель", role=UserRole.driver)
    ride_id = _ride(client, drv, seats=3)
    rider = user_factory("Айгуль")
    r = client.post("/bookings", headers=rider["auth"], json={"ride_id": ride_id, "seats": 2})
    assert r.status_code == 200, r.text
    assert _seats_left(ride_id) == 1


def test_мест_не_хватает_отвечаем_понятно(client, user_factory):
    drv = user_factory("Водитель", role=UserRole.driver)
    ride_id = _ride(client, drv, seats=1)
    rider = user_factory("Айгуль")
    r = client.post("/bookings", headers=rider["auth"], json={"ride_id": ride_id, "seats": 2})
    assert r.status_code == 400
    # `herr` отдаёт оба языка: detail — это {"ru": …, "ba": …}, а не строка.
    detail = r.json()["detail"]
    assert "мест" in detail["ru"].lower(), detail
    assert detail.get("ba"), "отказ пришёл без башкирского"
