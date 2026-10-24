"""Профиль подписывает человека по тому, что он делает (просьба Александра 23.10.2026).

Один аккаунт может и возить, и ездить. Подпись в шапке профиля бралась только из роли на сервере,
а роль от публикации поездок не меняется: человек, который возит соседей каждую неделю, был
«Пассажир». `/me` теперь говорит, публиковал ли он поездки (`drives`) и бронировал ли чужие (`books`).
"""
from app.models import UserRole

from test_flows import _book, _publish


def _me(client, who):
    r = client.get("/me", headers=who["auth"])
    assert r.status_code == 200, r.text
    return r.json()


def test_new_account_neither_drives_nor_books(client, user_factory):
    me = _me(client, user_factory("Профиль Новичок"))
    assert me["drives"] is False and me["books"] is False


def test_publishing_makes_a_driver_and_booking_makes_a_passenger(client, user_factory):
    both = user_factory("Профиль Оба")
    other = user_factory("Профиль Водитель", role=UserRole.driver)

    _publish(client, both, frm="Сибай", to="Баймак")
    me = _me(client, both)
    assert me["drives"] is True and me["books"] is False

    _book(client, both, _publish(client, other)["id"])
    me = _me(client, both)
    assert me["drives"] is True and me["books"] is True


def test_cancelled_or_declined_records_do_not_label_forever(client, user_factory):
    """Одна ошибочная бронь или поездка, сразу отменённая, — не повод навсегда писать «Водитель и
    пассажир» (приёмка PR210). Отклонённая водителем бронь — тоже не «ездит пассажиром»."""
    who = user_factory("Профиль Ошибся")
    drv = user_factory("Профиль Чужой Водитель", role=UserRole.driver)

    own_ride = _publish(client, who, frm="Сибай", to="Учалы")
    assert client.post(f"/rides/{own_ride['id']}/cancel", headers=who["auth"]).status_code == 200
    withdrawn = _book(client, who, _publish(client, drv)["id"])
    assert client.post(f"/bookings/{withdrawn['id']}/cancel", headers=who["auth"]).status_code == 200
    declined = _book(client, who, _publish(client, drv)["id"])
    assert client.post(f"/bookings/{declined['id']}/cancel", headers=drv["auth"]).status_code == 200

    me = _me(client, who)
    assert me["drives"] is False and me["books"] is False


def test_trip_cancelled_in_transit_still_counts(client, user_factory):
    """Водитель отметил посадку, потом отменил в пути: человек всё-таки ехал — подпись остаётся."""
    from sqlmodel import Session

    from app.db import engine
    from app.models import Booking, BookingStatus, Ride, RideStatus
    from app.timeutil import utcnow

    drv = user_factory("Профиль Отменил В Пути", role=UserRole.driver)
    pax = user_factory("Профиль Ехал")
    ride = _publish(client, drv)
    booking = _book(client, pax, ride["id"])
    with Session(engine) as s:
        b = s.get(Booking, booking["id"])
        b.status, b.onboard_at, b.cancelled_by = BookingStatus.cancelled, utcnow(), drv["id"]
        r = s.get(Ride, ride["id"])
        r.status = RideStatus.cancelled
        s.add(b)
        s.add(r)
        s.commit()

    assert _me(client, pax)["books"] is True
    assert _me(client, drv)["drives"] is True
