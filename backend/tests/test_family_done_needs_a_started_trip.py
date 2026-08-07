"""Второй путь к «поездка завершена» закрыт той же планкой (аудит 2026-08-07).

Водительская ручка `POST /bookings/{id}/driver-status {"status":"done"}` получила проверку
«завершить можно только начавшуюся поездку» — иначе цикл «опубликовал на 2030 год →
забронировал вторым аккаунтом → завершил» из трёх запросов рисовал бейдж «N поездок»
и открывал обеим сторонам оценку, без единого метра пути.

Но к тому же переходу ведёт вторая дверь — пассажирская, из семейного контроля:
`POST /bookings/{id}/trip-status {"status":"done"}`. Она закрывает бронь ровно так же
и отсюда же начисляет реферальный бонус за водителя. Закрыв одну дверь и оставив вторую,
дыру не убираешь, а переносишь.
"""
from datetime import timedelta

from sqlmodel import Session

from app.db import engine
from app.models import Booking, BookingStatus, Ride, RideStatus, UserRole
from app.timeutil import utcnow


def _booking(driver_id: int, passenger_id: int, depart_in: timedelta) -> int:
    with Session(engine) as s:
        ride = Ride(driver_id=driver_id, from_city="Баймак", to_city="Сибай",
                    depart_at=utcnow() + depart_in, seats=4, seats_left=3, price=300,
                    status=RideStatus.active)
        s.add(ride)
        s.commit()
        s.refresh(ride)
        b = Booking(ride_id=ride.id, passenger_id=passenger_id, seats=1,
                    status=BookingStatus.confirmed)
        s.add(b)
        s.commit()
        s.refresh(b)
        return b.id


def test_passenger_cannot_close_a_trip_that_has_not_departed(client, user_factory):
    drv = user_factory("Водитель", role=UserRole.driver)
    pax = user_factory("Пассажир")
    bid = _booking(drv["id"], pax["id"], depart_in=timedelta(days=365))

    r = client.post(f"/bookings/{bid}/trip-status", headers=pax["auth"], json={"status": "done"})
    assert r.status_code == 409, f"поездку 2027 года закрыли как состоявшуюся: {r.text}"
    assert r.json()["detail"]["ba"], "ошибка обязана быть двуязычной"

    with Session(engine) as s:
        assert s.get(Booking, bid).status == BookingStatus.confirmed


def test_passenger_can_close_a_trip_that_already_departed(client, user_factory):
    """Обратная сторона: настоящую поездку пассажир по-прежнему закрывает сам."""
    drv = user_factory("Водитель2", role=UserRole.driver)
    pax = user_factory("Пассажир2")
    bid = _booking(drv["id"], pax["id"], depart_in=timedelta(hours=-2))

    r = client.post(f"/bookings/{bid}/trip-status", headers=pax["auth"], json={"status": "done"})
    assert r.status_code == 200, r.text

    with Session(engine) as s:
        assert s.get(Booking, bid).status == BookingStatus.done
