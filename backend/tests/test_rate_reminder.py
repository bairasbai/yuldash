"""Напоминание оценить поездку (фоновая задача app/rate_reminder.py).

Проверяем: напоминаем ТОЛЬКО не оценившей стороне завершённой брони; один раз на бронь
(флаг rate_reminded → второй проход молчит, без спама); активные брони не трогаем;
выключенный флаг — детерминированно ничего.
"""
from app.config import settings
from app.db import engine
from app.models import Booking, BookingStatus, Rating, Ride, UserRole
from app.rate_reminder import rate_reminder_once
from app.timeutil import utcnow
from sqlmodel import Session


def _done_booking(passenger_id, driver_id, status=BookingStatus.done):
    with Session(engine) as s:
        ride = Ride(driver_id=driver_id, from_city="A", to_city="B", depart_at=utcnow())
        s.add(ride)
        s.commit()
        s.refresh(ride)
        b = Booking(ride_id=ride.id, passenger_id=passenger_id, status=status)
        s.add(b)
        s.commit()
        s.refresh(b)
        return b.id


def _seed_rating(booking_id, rater_id, ratee_id, stars=5):
    with Session(engine) as s:
        s.add(Rating(booking_id=booking_id, rater_id=rater_id, ratee_id=ratee_id, stars=stars))
        s.commit()


def test_reminds_only_unrated_party_once(client, user_factory):
    """Пассажир оценил, водитель нет → напоминание уходит ТОЛЬКО водителю; второй проход молчит."""
    drv = user_factory("RRDrv", role=UserRole.driver)
    pax = user_factory("RRPax")
    bid = _done_booking(pax["id"], drv["id"])
    _seed_rating(bid, pax["id"], drv["id"])          # пассажир оценил водителя
    with Session(engine) as s:
        reminded = rate_reminder_once(s)
    assert (bid, drv["id"]) in reminded              # водитель ещё не оценил → напомнили
    assert (bid, pax["id"]) not in reminded          # пассажир уже оценил → не трогаем
    # второй проход: бронь помечена rate_reminded → никого не дёргаем (без спама)
    with Session(engine) as s:
        assert rate_reminder_once(s) == []
        assert s.get(Booking, bid).rate_reminded is True


def test_skips_active_bookings(client, user_factory):
    """Незавершённую (confirmed) бронь не трогаем — рано напоминать."""
    drv = user_factory("RRActDrv", role=UserRole.driver)
    pax = user_factory("RRActPax")
    bid = _done_booking(pax["id"], drv["id"], status=BookingStatus.confirmed)
    with Session(engine) as s:
        reminded = rate_reminder_once(s)
    assert all(b != bid for b, _ in reminded)
    with Session(engine) as s:
        assert s.get(Booking, bid).rate_reminded is False   # активную не помечали


def test_both_rated_marks_without_reminding(client, user_factory):
    """Оба оценили → напоминать некого, но бронь помечаем (не пере-сканируем каждый проход)."""
    drv = user_factory("RRBothDrv", role=UserRole.driver)
    pax = user_factory("RRBothPax")
    bid = _done_booking(pax["id"], drv["id"])
    _seed_rating(bid, pax["id"], drv["id"])
    _seed_rating(bid, drv["id"], pax["id"])
    with Session(engine) as s:
        reminded = rate_reminder_once(s)
    assert all(b != bid for b, _ in reminded)           # никому по этой броне не напомнили
    with Session(engine) as s:
        assert s.get(Booking, bid).rate_reminded is True   # но помечено


def test_disabled_flag_noop(client, user_factory, monkeypatch):
    """rate_reminder_enabled=False → детерминированно ничего и бронь не помечаем."""
    drv = user_factory("RROffDrv", role=UserRole.driver)
    pax = user_factory("RROffPax")
    bid = _done_booking(pax["id"], drv["id"])
    monkeypatch.setattr(settings, "rate_reminder_enabled", False)
    with Session(engine) as s:
        assert rate_reminder_once(s) == []
        assert s.get(Booking, bid).rate_reminded is False   # задача выключена — не трогали
