"""«Отклонить» у водителя — это отказ, и пассажир должен слышать именно отказ (приёмка 18.10, п.6).

Водитель жмёт «Отклонить» на брони, которую ещё не подтверждал. Раньше пассажиру приходило то же,
что при любой отмене, — «Бронь отменена»: будто он сам что-то отменил или поездка сорвалась.
Теперь сервер узнаёт отказ сам (бронь ждала подтверждения, отменяет водитель) — так и старые
клиенты, которые шлют обычную отмену без причины, дают правильный текст.
"""
from sqlmodel import Session

from app.db import engine
from app.models import Booking, UserRole

from test_flows import _book, _publish


def _notes(client, user):
    r = client.get("/notifications", headers=user["auth"])
    assert r.status_code == 200, r.text
    return [n for n in r.json()["items"] if n["type"] == "booking"]


def test_driver_declining_pending_booking_tells_passenger_it_was_declined(client, user_factory):
    drv = user_factory("Отказ Водитель", role=UserRole.driver)
    pax = user_factory("Отказ Пассажир")
    ride = _publish(client, drv, frm="Баймак", to="Уфа")
    booking = _book(client, pax, ride["id"])

    r = client.post(f"/bookings/{booking['id']}/cancel", headers=drv["auth"])
    assert r.status_code == 200, r.text
    assert r.json()["status"] == "cancelled"

    note = next(n for n in _notes(client, pax) if n["ref_id"] == booking["id"])
    assert note["title_ru"] == "Водитель отклонил бронь"
    assert note["title_ba"] and note["title_ba"] != "Бронь ҡалдырылды"
    assert "Баймак → Уфа" in note["body_ru"]
    assert "Посмотри другие поездки" in note["body_ru"]
    with Session(engine) as s:
        assert s.get(Booking, booking["id"]).cancel_reason == "driver_declined"


def test_driver_cancelling_confirmed_booking_is_still_a_cancellation(client, user_factory):
    drv = user_factory("Отмена Водитель", role=UserRole.driver)
    pax = user_factory("Отмена Пассажир")
    ride = _publish(client, drv)
    booking = _book(client, pax, ride["id"])
    assert client.post(f"/bookings/{booking['id']}/confirm", headers=drv["auth"]).status_code == 200

    assert client.post(f"/bookings/{booking['id']}/cancel", headers=drv["auth"],
                       json={"reason": "not_going"}).status_code == 200

    titles = [n["title_ru"] for n in _notes(client, pax) if n["ref_id"] == booking["id"]]
    assert "Бронь отменена" in titles
    assert "Водитель отклонил бронь" not in titles


def test_passenger_withdrawing_pending_booking_is_not_a_decline(client, user_factory):
    drv = user_factory("Отзыв Водитель", role=UserRole.driver)
    pax = user_factory("Отзыв Пассажир")
    ride = _publish(client, drv)
    booking = _book(client, pax, ride["id"])

    assert client.post(f"/bookings/{booking['id']}/cancel", headers=pax["auth"]).status_code == 200

    titles = [n["title_ru"] for n in _notes(client, drv) if n["ref_id"] == booking["id"]]
    assert "Бронь отменена" in titles      # «Новая бронь» водителю тоже лежит в ленте
    assert "Водитель отклонил бронь" not in titles
    with Session(engine) as s:
        assert s.get(Booking, booking["id"]).cancel_reason is None
