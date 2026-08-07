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
from app.models import Booking, BookingStatus, Ride, RideStatus, User, UserRole
from app.timeutil import utcnow


@pytest.fixture(autouse=True)
def _db(client):
    """Схему поднимает фикстура client (через lifespan приложения) — без неё таблиц нет."""
    return client


def _person(session: Session, marker: str, role: UserRole) -> int:
    """Настоящий пользователь для посева — по одному на роль.

    Раньше тут стояли голые номера (`driver_id=1`, `passenger_id=2`): пользователей с такими
    номерами в базе может не быть. SQLite это пропускал (проверка связей выключена), Postgres
    отказывал — тест был зелёным дома и красным в CI.
    """
    u = session.exec(select(User).where(User.phone == marker)).first()
    if u is None:
        u = User(phone=marker, name="Посев", telegram_id=marker, verified=True, role=role)
        session.add(u)
        session.commit()
        session.refresh(u)
    return u.id


def _ride(session: Session, hours_ago: float, status=RideStatus.active) -> int:
    """Возвращает id, а не объект: после закрытия сессии объект отвязывается и обращение
    к его полям падает DetachedInstanceError. id — обычное число, оно переживёт что угодно."""
    r = Ride(
        driver_id=_person(session, "seed-drv-close", UserRole.driver),
        from_city="Баймак", to_city="Сибай",
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


def test_past_ride_without_bookings_becomes_expired():
    """Никто не поехал — «выполненной» называть нечестно."""
    with Session(engine) as s:
        rid = _ride(s, hours_ago=RIDE_CLOSE_GRACE_HOURS + 10)
    close_past_rides()
    assert _status(rid) == RideStatus.expired


def test_past_ride_with_confirmed_booking_becomes_done():
    """Поездка состоялась, её просто забыли закрыть."""
    with Session(engine) as s:
        rid = _ride(s, hours_ago=RIDE_CLOSE_GRACE_HOURS + 10)
        pid = _person(s, "seed-pass-close", UserRole.passenger)
        s.add(Booking(ride_id=rid, passenger_id=pid, seats=1, status=BookingStatus.confirmed))
        s.commit()
    close_past_rides()
    assert _status(rid) == RideStatus.done


def test_pending_booking_is_not_enough_for_done():
    """Бронь висела неподтверждённой — значит поездки не было."""
    with Session(engine) as s:
        rid = _ride(s, hours_ago=RIDE_CLOSE_GRACE_HOURS + 10)
        pid = _person(s, "seed-pass-close", UserRole.passenger)
        s.add(Booking(ride_id=rid, passenger_id=pid, seats=1, status=BookingStatus.pending))
        s.commit()
    close_past_rides()
    assert _status(rid) == RideStatus.expired


def test_future_ride_untouched():
    with Session(engine) as s:
        rid = _ride(s, hours_ago=-48)   # выезд через двое суток
    close_past_rides()
    assert _status(rid) == RideStatus.active


def test_ride_inside_grace_untouched():
    """Запас нужен: выехать могли позже, чем объявили, и бронь возможна впритык."""
    with Session(engine) as s:
        rid = _ride(s, hours_ago=RIDE_CLOSE_GRACE_HOURS - 1)
    close_past_rides()
    assert _status(rid) == RideStatus.active


def test_cancelled_stays_cancelled():
    """Отменённую не переписываем: причина закрытия важна для разбора спора."""
    with Session(engine) as s:
        rid = _ride(s, hours_ago=RIDE_CLOSE_GRACE_HOURS + 10, status=RideStatus.cancelled)
    close_past_rides()
    assert _status(rid) == RideStatus.cancelled


def test_second_run_changes_nothing():
    """Задача идёт по расписанию — повторный проход не должен ничего перекраивать."""
    with Session(engine) as s:
        rid = _ride(s, hours_ago=RIDE_CLOSE_GRACE_HOURS + 10)
    close_past_rides()
    first = _status(rid)
    done, expired = close_past_rides()
    assert _status(rid) == first
    assert done == 0 and expired == 0
