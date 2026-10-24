"""After a real interrupted ride only its passenger may be invited to rate the driver."""
from datetime import timedelta

import pytest
from sqlalchemy import event
from sqlmodel import Session

from app import rate_reminder, safety_logic
from app.config import settings
from app.db import engine
from app.models import Booking, BookingStatus, Rating, Ride, SafetyProfile, UserRole
from app.rating_service import FRESH_RATING_HOURS, RATING_WINDOW_DAYS
from app.timeutil import utcnow


@pytest.fixture(autouse=True)
def reminder_clock_and_push(monkeypatch):
    now = utcnow().replace(microsecond=0)
    monkeypatch.setattr(rate_reminder, "utcnow", lambda: now)
    monkeypatch.setattr(safety_logic, "utcnow", lambda: now)
    monkeypatch.setattr(settings, "rate_reminder_enabled", True)
    sent = []
    monkeypatch.setattr(rate_reminder, "push_notification", lambda session, uid, kind,
                        ru, ba, body_ru, body_ba, **kw:
                        sent.append((uid, kw["ref_kind"], kw["ref_id"], ru, ba, body_ru, body_ba)))
    return now, sent


def _trip(user_factory, now, *, age=timedelta(hours=1), status=BookingStatus.cancelled,
          boarded=True, by_passenger=False, reason="other", created_age=None):
    driver = user_factory("reminder driver", role=UserRole.driver)
    passenger = user_factory("reminder passenger")
    with Session(engine) as session:
        ride = Ride(driver_id=driver["id"], from_city="A", to_city="B", depart_at=now-age)
        session.add(ride)
        session.commit()
        session.refresh(ride)
        booking = Booking(ride_id=ride.id, passenger_id=passenger["id"], status=status,
                          created_at=now-(created_age or age),
                          onboard_at=now-age if boarded else None,
                          cancelled_at=now-timedelta(minutes=1), cancel_reason=reason,
                          cancelled_by=passenger["id"] if by_passenger else driver["id"])
        session.add(booking)
        session.commit()
        session.refresh(booking)
        return driver, passenger, booking.id


def _run(*, dry=False):
    with Session(engine) as session:
        return rate_reminder.rate_reminder_once(session, dry_run=dry)


def _sent_for(sent, booking_id):
    return [entry for entry in sent if entry[1:3] == ("booking", booking_id)]


def test_only_the_passenger_is_reminded_once(user_factory, reminder_clock_and_push):
    now, sent = reminder_clock_and_push
    driver, passenger, bid = _trip(user_factory, now)
    stranger = user_factory("not a participant")
    assert (bid, passenger["id"]) in _run()
    entries = _sent_for(sent, bid)
    assert [entry[0] for entry in entries] == [passenger["id"]]
    assert all(entry[0] not in (driver["id"], stranger["id"]) for entry in entries)
    assert all(all(entry[3:]) for entry in entries), "the existing RU/BA title and body stay complete"
    assert (bid, passenger["id"]) not in _run()
    assert len(_sent_for(sent, bid)) == 1
    with Session(engine) as session:
        assert session.get(Booking, bid).rate_reminded is True


@pytest.mark.parametrize("fields", [
    {"boarded": False},  # ordinary cancellation / undone boarding
    {"by_passenger": True},
    {"reason": "no_show", "boarded": False},
    {"reason": "no_show"},  # inconsistent legacy row must not invent an actual ride
    {"status": BookingStatus.pending},
    {"status": BookingStatus.confirmed},
    {"status": BookingStatus.onboard},
])
def test_no_reminder_without_a_driver_cancelled_real_ride(user_factory, reminder_clock_and_push, fields):
    now, sent = reminder_clock_and_push
    _driver, _passenger, bid = _trip(user_factory, now, **fields)
    assert all(ref != bid for ref, _uid in _run())
    assert _sent_for(sent, bid) == []


def test_already_rated_passenger_is_not_bothered(user_factory, reminder_clock_and_push):
    now, sent = reminder_clock_and_push
    driver, passenger, bid = _trip(user_factory, now)
    with Session(engine) as session:
        session.add(Rating(booking_id=bid, rater_id=passenger["id"], ratee_id=driver["id"], stars=2))
        session.commit()
    assert all(ref != bid for ref, _uid in _run())
    assert _sent_for(sent, bid) == []


@pytest.mark.parametrize("age,allowed", [
    (timedelta(days=RATING_WINDOW_DAYS), True),
    (timedelta(days=RATING_WINDOW_DAYS, microseconds=1), False),
])
def test_same_sixty_day_boundary_as_rating_even_for_an_old_booking(user_factory, reminder_clock_and_push, age, allowed):
    now, sent = reminder_clock_and_push
    _driver, passenger, bid = _trip(user_factory, now, age=age, created_age=age+timedelta(days=1))
    assert ((bid, passenger["id"]) in _run()) is allowed
    assert bool(_sent_for(sent, bid)) is allowed


@pytest.mark.parametrize("age,allowed", [
    (timedelta(hours=FRESH_RATING_HOURS), True),
    (timedelta(hours=FRESH_RATING_HOURS, microseconds=1), False),
])
def test_same_fresh_trip_boundary_on_account_pause(user_factory, reminder_clock_and_push, age, allowed):
    now, sent = reminder_clock_and_push
    _driver, passenger, bid = _trip(user_factory, now, age=age)
    with Session(engine) as session:
        session.add(SafetyProfile(user_id=passenger["id"], suspended_until=now+timedelta(days=1)))
        session.commit()
    assert ((bid, passenger["id"]) in _run()) is allowed
    assert bool(_sent_for(sent, bid)) is allowed


def test_temporary_pause_does_not_consume_a_later_allowed_reminder(user_factory, reminder_clock_and_push):
    now, sent = reminder_clock_and_push
    _driver, passenger, bid = _trip(user_factory, now, age=timedelta(days=3))
    with Session(engine) as session:
        profile = SafetyProfile(user_id=passenger["id"], suspended_until=now+timedelta(days=1))
        session.add(profile)
        session.commit()
        session.refresh(profile)
        profile_id = profile.id
    assert (bid, passenger["id"]) not in _run()
    with Session(engine) as session:
        assert session.get(Booking, bid).rate_reminded is False
        profile = session.get(SafetyProfile, profile_id)
        profile.suspended_until = now-timedelta(seconds=1)
        session.add(profile)
        session.commit()
    assert (bid, passenger["id"]) in _run()
    assert len(_sent_for(sent, bid)) == 1


def test_dry_run_does_not_send_or_mark(user_factory, reminder_clock_and_push):
    now, sent = reminder_clock_and_push
    _driver, passenger, bid = _trip(user_factory, now)
    assert (bid, passenger["id"]) in _run(dry=True)
    assert _sent_for(sent, bid) == []
    with Session(engine) as session:
        assert session.get(Booking, bid).rate_reminded is False
    assert (bid, passenger["id"]) in _run()
    assert len(_sent_for(sent, bid)) == 1


def test_cancelled_reminders_do_not_add_queries_per_booking(user_factory, reminder_clock_and_push):
    now, _sent = reminder_clock_and_push
    def query_count():
        queries = []
        def capture(_conn, _cursor, statement, *_args):
            if statement.lstrip().upper().startswith("SELECT"):
                queries.append(statement)
        event.listen(engine, "before_cursor_execute", capture)
        try:
            _run(dry=True)
        finally:
            event.remove(engine, "before_cursor_execute", capture)
        return len(queries)
    for _ in range(3):
        _trip(user_factory, now)
    small = query_count()
    for _ in range(5):
        _trip(user_factory, now)
    assert query_count() <= small
