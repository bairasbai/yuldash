"""Нельзя исчезнуть, оставив попутчика ждать на трассе.

Аудит 2026-08-06, третья волна. Удаление аккаунта уже проверяло долги, живой такси-заказ
и посылку в пути — а попутку не проверяло, хотя это самый старый сценарий.

Что происходило. Водитель удалял аккаунт → его поездка сносилась ВМЕСТЕ с чужими бронями
(так и написано в каскаде: «чужие на МОИХ поездках»). Пассажир приходил к назначенному
времени, машины не было, а в приложении не было и самой поездки — как будто её не существовало.
Позвонить тоже некому: телефон второй стороны виден только внутри брони. В другую сторону
то же самое: пассажир исчезал, водитель до последнего держал для него место.

Правило файла: пока договорённость в силе, аккаунт не удаляется — и отказ говорит, что именно
отменить. Отмена шлёт уведомление второй стороне, поэтому выход всегда честный, а не тупик.
"""
from datetime import timedelta

import pytest
from sqlmodel import Session

from app import account as acct
from app.db import engine
from fastapi import HTTPException
from app.models import Booking, BookingStatus, Ride, RideStatus, User, UserRole
from app.timeutil import utcnow


def _ride_with_booking(driver_id: int, passenger_id: int, status=BookingStatus.confirmed):
    with Session(engine) as s:
        r = Ride(driver_id=driver_id, from_city="Баймак", to_city="Сибай",
                 depart_at=utcnow() + timedelta(hours=5),
                 seats_total=3, seats_left=2, price=300, status=RideStatus.active)
        s.add(r)
        s.commit()
        s.refresh(r)
        b = Booking(ride_id=r.id, passenger_id=passenger_id, seats=1, price=300, status=status)
        s.add(b)
        s.commit()
        s.refresh(b)
        return r.id, b.id


def _try_delete(uid: int):
    """Возвращает текст отказа (ru, ba) или None, если удаление разрешено."""
    with Session(engine) as s:
        try:
            acct.guard_can_delete(s, s.get(User, uid))
            return None
        except HTTPException as e:
            return e.detail["ru"], e.detail["ba"]


def test_passenger_cannot_vanish_with_a_live_booking(client, user_factory):
    drv = user_factory("VanishDrv1", role=UserRole.driver)
    pax = user_factory("VanishPax1")
    _ride_with_booking(drv["id"], pax["id"])

    refusal = _try_delete(pax["id"])
    assert refusal is not None, "пассажир исчез вместе с бронью — водитель ждал бы зря"
    ru, ba = refusal
    assert "бронь" in ru.lower(), f"отказ не объясняет, что закрыть: {ru}"
    assert ba and ba != ru, "отказ не на двух языках"


def test_driver_cannot_vanish_while_passengers_wait(client, user_factory):
    """Самое опасное направление: пассажир приедет на трассу к машине, которой не будет."""
    drv = user_factory("VanishDrv2", role=UserRole.driver)
    pax = user_factory("VanishPax2")
    _ride_with_booking(drv["id"], pax["id"])

    refusal = _try_delete(drv["id"])
    assert refusal is not None, "водитель исчез, а пассажир остался ждать на трассе"
    ru, ba = refusal
    assert "рейс" in ru.lower() or "поездк" in ru.lower(), f"отказ не объясняет, что закрыть: {ru}"
    assert ba and ba != ru, "отказ не на двух языках"


@pytest.mark.parametrize("status", [BookingStatus.done, BookingStatus.cancelled])
def test_closed_trips_do_not_block_deletion(client, user_factory, status):
    """Право на удаление священно (152-ФЗ): закрытая поездка держать человека не должна."""
    drv = user_factory(f"VanishDrv3{status.value}", role=UserRole.driver)
    pax = user_factory(f"VanishPax3{status.value}")
    _ride_with_booking(drv["id"], pax["id"], status=status)

    assert _try_delete(pax["id"]) is None, f"завершённая бронь ({status}) не пускает удалиться"
    assert _try_delete(drv["id"]) is None, f"завершённая поездка ({status}) не пускает удалиться"


def test_ride_without_bookings_does_not_block(client, user_factory):
    """Поездка, на которую никто не сел, никого не подводит — удаляться можно."""
    drv = user_factory("VanishDrv4", role=UserRole.driver)
    with Session(engine) as s:
        s.add(Ride(driver_id=drv["id"], from_city="Баймак", to_city="Уфа",
                   depart_at=utcnow() + timedelta(hours=5),
                   seats_total=3, seats_left=3, price=300, status=RideStatus.active))
        s.commit()

    assert _try_delete(drv["id"]) is None, "пустая поездка зря держит аккаунт"


def test_cancelling_the_booking_opens_the_door(client, user_factory):
    """Выход честный, а не тупик: отменил — можешь удаляться."""
    drv = user_factory("VanishDrv5", role=UserRole.driver)
    pax = user_factory("VanishPax5")
    _rid, bid = _ride_with_booking(drv["id"], pax["id"])
    assert _try_delete(pax["id"]) is not None

    with Session(engine) as s:
        b = s.get(Booking, bid)
        b.status = BookingStatus.cancelled
        s.add(b)
        s.commit()

    assert _try_delete(pax["id"]) is None, "после отмены брони удаление всё ещё заблокировано"
