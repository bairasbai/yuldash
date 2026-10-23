"""Квитанция говорит, можно ли ещё оценить поездку (приёмка 18.10, п.7).

Пассажир пропустил экран «Поездка завершена» — и оценить водителя позже было негде. Квитанция
теперь отдаёт `can_rate` по тому же сроку, что проверяет `/bookings/{id}/rate` (60 дней после
выезда), и клиент показывает «Оценить водителя» только пока оценка действительно пройдёт.
"""
from datetime import timedelta

from sqlmodel import Session

from app.db import engine
from app.models import Booking, BookingStatus, Ride, SafetyProfile, UserRole
from app.rating_service import RATING_WINDOW_DAYS
from app.timeutil import utcnow

from test_flows import _book, _publish


def _done_trip(client, user_factory, days_ago: float):
    drv = user_factory("Квитанция Водитель", role=UserRole.driver)
    pax = user_factory("Квитанция Пассажир")
    ride = _publish(client, drv)
    booking = _book(client, pax, ride["id"])
    with Session(engine) as s:
        r = s.get(Ride, ride["id"])
        r.depart_at = utcnow() - timedelta(days=days_ago)
        b = s.get(Booking, booking["id"])
        b.status = BookingStatus.done
        s.add(r)
        s.add(b)
        s.commit()
    return pax, booking["id"]


def test_fresh_trip_can_still_be_rated_and_rating_goes_through(client, user_factory):
    pax, bid = _done_trip(client, user_factory, days_ago=3)
    receipt = client.get(f"/trips/{bid}/receipt", headers=pax["auth"]).json()
    assert receipt["can_rate"] is True and receipt["my_stars"] == 0
    assert client.post(f"/bookings/{bid}/rate", headers=pax["auth"], json={"stars": 4}).status_code == 200
    assert client.get(f"/trips/{bid}/receipt", headers=pax["auth"]).json()["my_stars"] == 4


def test_old_trip_is_not_offered_for_rating(client, user_factory):
    pax, bid = _done_trip(client, user_factory, days_ago=RATING_WINDOW_DAYS + 1)
    assert client.get(f"/trips/{bid}/receipt", headers=pax["auth"]).json()["can_rate"] is False
    assert client.post(f"/bookings/{bid}/rate", headers=pax["auth"], json={"stars": 4}).status_code == 409


def test_paused_account_is_not_offered_old_trips(client, user_factory):
    """На паузе старую поездку оценить нельзя — и звёзд в квитанции нет (ревью Opus 23.10)."""
    pax, bid = _done_trip(client, user_factory, days_ago=10)
    with Session(engine) as s:
        s.add(SafetyProfile(user_id=pax["id"], suspended_until=utcnow() + timedelta(days=7)))
        s.commit()
    assert client.get(f"/trips/{bid}/receipt", headers=pax["auth"]).json()["can_rate"] is False
    assert client.post(f"/bookings/{bid}/rate", headers=pax["auth"], json={"stars": 1}).status_code >= 400
