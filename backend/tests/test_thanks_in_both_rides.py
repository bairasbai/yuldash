"""«Рәхмәт» можно сказать и таксисту, и соседу, который подвёз.

Аудит 2026-08-06, продолжение охоты. Благодарность придумали для ПОПУТОК — так и написано
в комментарии к такси-ручке, которая появилась позже. Но кнопка в итоге выросла только
в чеке такси, а обе booking-ручки (`GET /bookings/{id}/tip`, `POST /bookings/{id}/thanks`)
годами лежали без единого вызова из приложения. Сосед, который довёз бесплатно, спасибо
получал реже платного таксиста — при том что сервер всё умел.

Правило файла: обе стороны сценария равны. Сказать спасибо может только пассажир и только
после поездки, повтор ничего не ломает, денег жест не двигает.
"""
from datetime import timedelta

from sqlmodel import Session

from app.db import engine
from app.models import Booking, BookingStatus, Ride, RideStatus, UserRole
from app.timeutil import utcnow


def _finished_trip(driver_id: int, passenger_id: int, *, status=BookingStatus.done):
    with Session(engine) as s:
        ride = Ride(driver_id=driver_id, from_city="Баймак", to_city="Сибай",
                    depart_at=utcnow() - timedelta(hours=3),
                    seats_total=3, seats_left=2, price=300, status=RideStatus.done)
        s.add(ride)
        s.commit()
        s.refresh(ride)
        b = Booking(ride_id=ride.id, passenger_id=passenger_id, seats=1, price=300, status=status)
        s.add(b)
        s.commit()
        s.refresh(b)
        return b


def test_passenger_can_thank_the_neighbour_who_gave_a_lift(client, user_factory):
    drv = user_factory("ThanksDrv1", role=UserRole.driver)
    pax = user_factory("ThanksPax1")
    b = _finished_trip(drv["id"], pax["id"])

    info = client.get(f"/bookings/{b.id}/tip", headers=pax["auth"])
    assert info.status_code == 200, info.text
    assert info.json()["already_thanked"] is False
    assert info.json()["driver_name"] == "ThanksDrv1"

    said = client.post(f"/bookings/{b.id}/thanks", headers=pax["auth"], json={})
    assert said.status_code == 200, said.text

    again = client.get(f"/bookings/{b.id}/tip", headers=pax["auth"])
    assert again.json()["already_thanked"] is True, "спасибо не запомнилось — предложим второй раз"


def test_saying_thanks_twice_is_harmless(client, user_factory):
    """Двойной тап на медленной сети не должен ничего ломать."""
    drv = user_factory("ThanksDrv2", role=UserRole.driver)
    pax = user_factory("ThanksPax2")
    b = _finished_trip(drv["id"], pax["id"])

    first = client.post(f"/bookings/{b.id}/thanks", headers=pax["auth"], json={})
    second = client.post(f"/bookings/{b.id}/thanks", headers=pax["auth"], json={})
    assert first.status_code == 200 and second.status_code == 200, second.text


def test_only_after_the_trip(client, user_factory):
    """До конца поездки спасибо не говорят — иначе жест теряет смысл."""
    drv = user_factory("ThanksDrv3", role=UserRole.driver)
    pax = user_factory("ThanksPax3")
    b = _finished_trip(drv["id"], pax["id"], status=BookingStatus.confirmed)

    r = client.post(f"/bookings/{b.id}/thanks", headers=pax["auth"], json={})
    assert r.status_code == 409, f"спасибо приняли до поездки: {r.status_code}"


def test_stranger_cannot_thank_for_someone_else(client, user_factory):
    drv = user_factory("ThanksDrv4", role=UserRole.driver)
    pax = user_factory("ThanksPax4")
    stranger = user_factory("ThanksStranger")
    b = _finished_trip(drv["id"], pax["id"])

    r = client.post(f"/bookings/{b.id}/thanks", headers=stranger["auth"], json={})
    assert r.status_code in (403, 404), f"посторонний сказал спасибо за другого: {r.status_code}"


def test_driver_side_is_not_offered_the_button(client, user_factory):
    """Водителю благодарить самого себя нечего — сервер отвечает отказом, экран кнопку не рисует."""
    drv = user_factory("ThanksDrv5", role=UserRole.driver)
    pax = user_factory("ThanksPax5")
    b = _finished_trip(drv["id"], pax["id"])

    r = client.get(f"/bookings/{b.id}/tip", headers=drv["auth"])
    assert r.status_code == 403, f"водителю отдали карточку благодарности себе: {r.status_code}"
