"""Телефон пассажира водителю — только после подтверждения брони (решение Александра 23.10.2026).

Раньше в попутке водитель не видел номер пассажира вовсе — только чат, хотя подсказка обещала,
что номер «открывается обоим». Теперь — так же, как телефон водителя пассажиру: открыт, пока бронь
подтверждена или пассажир в машине, и ещё немного после поездки; до подтверждения и после отмены — нет.
Пассажиру свой номер в деталях не нужен — у него поле пустое.
"""
from sqlmodel import Session

from app.db import engine
from app.models import Booking, User, UserRole

from test_flows import _book, _publish


def _pair(client, user_factory):
    drv = user_factory("Номер Водитель", role=UserRole.driver)
    pax = user_factory("Номер Пассажир")
    with Session(engine) as s:
        u = s.get(User, pax["id"])
        u.phone = f"+7917{pax['id']:07d}"   # номер уникален в базе — у каждого теста свой
        s.add(u)
        s.commit()
    booking = _book(client, pax, _publish(client, drv)["id"])
    return drv, pax, booking["id"], f"+7917{pax['id']:07d}"


def _phone(client, who, bid):
    r = client.get(f"/bookings/{bid}/details", headers=who["auth"])
    assert r.status_code == 200, r.text
    return r.json()["passenger_phone"]


def test_driver_gets_passenger_phone_only_after_confirming(client, user_factory):
    drv, pax, bid, phone = _pair(client, user_factory)
    assert _phone(client, drv, bid) == "", "до подтверждения номер закрыт"

    assert client.post(f"/bookings/{bid}/confirm", headers=drv["auth"]).status_code == 200
    assert _phone(client, drv, bid) == phone
    assert _phone(client, pax, bid) == "", "пассажиру свой номер в деталях не отдаём"

    assert client.post(f"/bookings/{bid}/cancel", headers=drv["auth"], json={"reason": "not_going"}).status_code == 200
    assert _phone(client, drv, bid) == "", "после отмены номер снова закрыт"


def test_stranger_still_gets_nothing(client, user_factory):
    _drv, _pax, bid, _phone_no = _pair(client, user_factory)
    stranger = user_factory("Номер Чужой")
    assert client.get(f"/bookings/{bid}/details", headers=stranger["auth"]).status_code in (403, 404)


def test_minor_passengers_own_number_stays_with_the_child(client, user_factory):
    """Едет подросток: водитель звонит взрослому, а не ребёнку (правило Q14; ревью Opus 23.10, P1-2)."""
    drv, _pax, bid, _phone_no = _pair(client, user_factory)
    with Session(engine) as s:
        b = s.get(Booking, bid)
        b.minor_passenger = True
        b.minor_guardian_name = "Мама"
        b.minor_guardian_phone = "+79170009900"
        s.add(b)
        s.commit()
    assert client.post(f"/bookings/{bid}/confirm", headers=drv["auth"]).status_code == 200
    details = client.get(f"/bookings/{bid}/details", headers=drv["auth"]).json()
    assert details["passenger_phone"] == ""
    assert details["minor_guardian_phone"] == "+79170009900"
