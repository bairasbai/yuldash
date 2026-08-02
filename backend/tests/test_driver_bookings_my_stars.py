# -*- coding: utf-8 -*-
"""Список «Пассажиры — оцените после поездки» помнит, что водитель уже поставил.

Зачем: раньше после перезагрузки экрана звёзды у оценённого пассажира снова были пустые.
Человек не понимал, засчиталась ли оценка, и ставил её второй раз. Теперь сервер отдаёт
my_stars: 0 — ещё не оценивал, 1..5 — поставил столько (оценку можно изменить).
"""
from sqlmodel import Session

from app.db import engine
from app.models import Booking, BookingStatus, UserRole


def _publish(client, drv, frm="Баймак", to="Сибай"):
    r = client.post("/rides", headers=drv["auth"], json={
        "from_city": frm, "to_city": to, "depart_at": "2030-01-01T08:00:00",
        "seats": 3, "price": 300,
    })
    assert r.status_code == 200, r.text
    return r.json()


def _book(client, pax, ride_id):
    r = client.post("/bookings", headers=pax["auth"], json={"ride_id": ride_id, "seats": 1})
    assert r.status_code == 200, r.text
    return r.json()


def _finish(booking_id):
    """Довести бронь до done напрямую — машину состояний тут не проверяем."""
    with Session(engine) as s:
        b = s.get(Booking, booking_id)
        b.status = BookingStatus.done
        s.add(b)
        s.commit()


def _my(client, drv, booking_id):
    items = client.get("/driver/bookings", headers=drv["auth"]).json()
    return next(b for b in items if b["booking_id"] == booking_id)


def test_my_stars_zero_until_rated(client, user_factory):
    drv = user_factory("StarsDrv", role=UserRole.driver)
    pax = user_factory("StarsPax")
    ride = _publish(client, drv)
    booking = _book(client, pax, ride["id"])
    assert _my(client, drv, booking["id"])["my_stars"] == 0


def test_my_stars_remembers_rating(client, user_factory):
    drv = user_factory("StarsDrv2", role=UserRole.driver)
    pax = user_factory("StarsPax2")
    ride = _publish(client, drv)
    booking = _book(client, pax, ride["id"])
    _finish(booking["id"])
    assert client.post(f"/bookings/{booking['id']}/rate", headers=drv["auth"],
                       json={"stars": 4}).status_code == 200
    assert _my(client, drv, booking["id"])["my_stars"] == 4


def test_my_stars_follows_changed_rating(client, user_factory):
    """Оценку разрешено изменить — список обязан показывать новую, а не первую."""
    drv = user_factory("StarsDrv3", role=UserRole.driver)
    pax = user_factory("StarsPax3")
    ride = _publish(client, drv)
    booking = _book(client, pax, ride["id"])
    _finish(booking["id"])
    client.post(f"/bookings/{booking['id']}/rate", headers=drv["auth"], json={"stars": 2})
    client.post(f"/bookings/{booking['id']}/rate", headers=drv["auth"], json={"stars": 5})
    assert _my(client, drv, booking["id"])["my_stars"] == 5


def test_my_stars_is_mine_not_someone_elses(client, user_factory):
    """Пассажир тоже оценивает водителя по этой же брони. Водителю нельзя показать ЕГО звёзды."""
    drv = user_factory("StarsDrv4", role=UserRole.driver)
    pax = user_factory("StarsPax4")
    ride = _publish(client, drv)
    booking = _book(client, pax, ride["id"])
    _finish(booking["id"])
    assert client.post(f"/bookings/{booking['id']}/rate", headers=pax["auth"],
                       json={"stars": 5}).status_code == 200
    assert _my(client, drv, booking["id"])["my_stars"] == 0, "показали чужую оценку как свою"
