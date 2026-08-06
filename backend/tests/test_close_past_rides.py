"""Прошедшие поездки должны закрываться сами.

На проде 2026-08-03 нашлись три поездки от 5, 6 и 15 июля со статусом active. Состояния
«просрочена» не существовало вовсе, а лента прячет поездку через 2 часа после выезда — но
статус ей не менял никто. Пассажирам не видно, у водителя навсегда числятся текущими.

Главное, что здесь проверяется, — РАЗДЕЛЕНИЕ done и expired. На done строится статистика
поездок и рейтинг водителя; записать туда несостоявшуюся поездку значит соврать в цифрах.
"""
from datetime import timedelta

import pytest
from sqlmodel import Session, select

from app.cleanup import RIDE_CLOSE_GRACE_HOURS, close_past_rides
from app.db import engine
from app.models import Booking, BookingStatus, Ride, RideStatus, UserRole
from app.timeutil import utcnow


@pytest.fixture(autouse=True)
def _db(client):
    """Схему поднимает фикстура client (через lifespan приложения) — без неё таблиц нет."""
    return client


@pytest.fixture
def people(user_factory):
    """Настоящие водитель и пассажир.

    Раньше тут стояли номера людей руками — `driver_id=1`, `passenger_id=2`. На SQLite это
    проходило: она по умолчанию не проверяет, существует ли такой человек. Postgres проверяет —
    и весь файл падал на вставке, то есть проверка «прошедшие поездки закрываются» на настоящей
    базе не работала вовсе (найдено 2026-08-06 при разборе красной CI).
    """
    return {"driver": user_factory("PastRidesDrv", role=UserRole.driver)["id"],
            "passenger": user_factory("PastRidesPax")["id"]}


def _ride(session: Session, *, driver_id: int, hours_ago: float, status=RideStatus.active) -> int:
    # driver_id обязателен и без значения по умолчанию нарочно: значение «1» тут и было ловушкой,
    # в которую этот файл однажды уже попал.
    """Возвращает id, а не объект: после закрытия сессии объект отвязывается и обращение
    к его полям падает DetachedInstanceError. id — обычное число, оно переживёт что угодно."""
    r = Ride(
        driver_id=driver_id, from_city="Баймак", to_city="Сибай",
        depart_at=utcnow() - timedelta(hours=hours_ago),
        seats_total=3, seats_left=3, price=300, status=status,
    )
    session.add(r)
    session.commit()
    session.refresh(r)
    return r.id


def _status(rid: int) -> str:
    with Session(engine) as s:
        return s.exec(select(Ride).where(Ride.id == rid)).one().status


def test_past_ride_without_bookings_becomes_expired(people):
    """Никто не поехал — «выполненной» называть нечестно."""
    with Session(engine) as s:
        rid = _ride(s, driver_id=people['driver'], hours_ago=RIDE_CLOSE_GRACE_HOURS + 10)
    close_past_rides()
    assert _status(rid) == RideStatus.expired


def test_past_ride_with_confirmed_booking_becomes_done(people):
    """Поездка состоялась, её просто забыли закрыть."""
    with Session(engine) as s:
        rid = _ride(s, driver_id=people['driver'], hours_ago=RIDE_CLOSE_GRACE_HOURS + 10)
        s.add(Booking(ride_id=rid, passenger_id=people['passenger'], seats=1, status=BookingStatus.confirmed))
        s.commit()
    close_past_rides()
    assert _status(rid) == RideStatus.done


def test_pending_booking_is_not_enough_for_done(people):
    """Бронь висела неподтверждённой — значит поездки не было."""
    with Session(engine) as s:
        rid = _ride(s, driver_id=people['driver'], hours_ago=RIDE_CLOSE_GRACE_HOURS + 10)
        s.add(Booking(ride_id=rid, passenger_id=people['passenger'], seats=1, status=BookingStatus.pending))
        s.commit()
    close_past_rides()
    assert _status(rid) == RideStatus.expired


def test_future_ride_untouched(people):
    with Session(engine) as s:
        rid = _ride(s, driver_id=people['driver'], hours_ago=-48)   # выезд через двое суток
    close_past_rides()
    assert _status(rid) == RideStatus.active


def test_ride_inside_grace_untouched(people):
    """Запас нужен: выехать могли позже, чем объявили, и бронь возможна впритык."""
    with Session(engine) as s:
        rid = _ride(s, driver_id=people['driver'], hours_ago=RIDE_CLOSE_GRACE_HOURS - 1)
    close_past_rides()
    assert _status(rid) == RideStatus.active


def test_cancelled_stays_cancelled(people):
    """Отменённую не переписываем: причина закрытия важна для разбора спора."""
    with Session(engine) as s:
        rid = _ride(s, driver_id=people['driver'], hours_ago=RIDE_CLOSE_GRACE_HOURS + 10, status=RideStatus.cancelled)
    close_past_rides()
    assert _status(rid) == RideStatus.cancelled


def test_second_run_changes_nothing(people):
    """Задача идёт по расписанию — повторный проход не должен ничего перекраивать."""
    with Session(engine) as s:
        rid = _ride(s, driver_id=people['driver'], hours_ago=RIDE_CLOSE_GRACE_HOURS + 10)
    close_past_rides()
    first = _status(rid)
    done, expired = close_past_rides()
    assert _status(rid) == first
    assert done == 0 and expired == 0
