"""Подтверждение и отмена в одну секунду; повтор брони с «едет подросток» (приёмка PR #189).

Что было. Водитель жал «Подтвердить», пассажир в ту же секунду — «Отменить». Статус подтверждение
читало без замка, а записывало безусловно: отмена успевала вернуть места в продажу, а потом бронь
«воскресала» подтверждённой. На трёх местах — три чужих места в машине, которые уже можно купить.
И повтор брони после потерянного ответа молча отдавал старую бронь, даже если человек теперь отметил
«едет подросток»: водитель не узнавал о ребёнке.
"""
from sqlmodel import Session

from app.db import engine
from app.models import Booking, BookingStatus, UserRole
from app.routers import bookings as bookings_mod
from test_last_seat_race import _ride, _seats_left


def test_confirm_does_not_resurrect_a_booking_cancelled_in_the_same_second(client, user_factory, monkeypatch):
    driver = user_factory("ConfirmRaceDriver", role=UserRole.driver)
    passenger = user_factory("ConfirmRacePassenger")
    ride_id = _ride(client, driver, seats=4)
    booked = client.post("/bookings", headers=passenger["auth"], json={"ride_id": ride_id, "seats": 3})
    assert booked.status_code == 200, booked.text
    bid = booked.json()["id"]
    assert _seats_left(ride_id) == 1

    real_ensure_active = bookings_mod.ensure_active

    def cancel_in_between(session, user_id):
        # Подтверждение уже прочитало «ждёт», а отмена пассажира успела записаться.
        real_ensure_active(session, user_id)
        reply = client.post(f"/bookings/{bid}/cancel", headers=passenger["auth"], json={})
        assert reply.status_code == 200, reply.text

    monkeypatch.setattr(bookings_mod, "ensure_active", cancel_in_between)
    reply = client.post(f"/bookings/{bid}/confirm", headers=driver["auth"])
    assert reply.status_code == 409, reply.text
    with Session(engine) as s:
        assert s.get(Booking, bid).status == BookingStatus.cancelled, "отменённая бронь ожила"
    assert _seats_left(ride_id) == 4, "места вернулись один раз и остались свободными"


def test_retry_with_minor_flag_is_refused_instead_of_losing_it(client, user_factory):
    driver = user_factory("MinorRetryDriver", role=UserRole.driver)
    passenger = user_factory("MinorRetryPassenger")
    ride_id = _ride(client, driver, seats=3)
    first = client.post("/bookings", headers=passenger["auth"], json={"ride_id": ride_id, "seats": 1})
    assert first.status_code == 200, first.text
    reply = client.post("/bookings", headers=passenger["auth"], json={
        "ride_id": ride_id, "seats": 1, "minor_passenger": True,
        "minor_guardian_name": "Гульнара", "minor_guardian_phone": "+79027770001"})
    assert reply.status_code == 409, reply.text
    detail = reply.json()["detail"]
    assert detail["code"] == "booking_minor_conflict" and detail["ru"] and detail["ba"]
    assert detail["booking_id"] == first.json()["id"], "клиент откроет свою бронь, а не оставит человека с отказом"
    with Session(engine) as s:
        assert s.get(Booking, first.json()["id"]).minor_passenger is False
    same = client.post("/bookings", headers=passenger["auth"], json={"ride_id": ride_id, "seats": 1})
    assert same.status_code == 200 and same.json()["id"] == first.json()["id"], "обычный повтор — та же бронь"


def test_seats_conflict_names_the_own_booking(client, user_factory):
    """Бронь на 2 места уже есть (например, по заявке), человек открыл ту же поездку из ленты и жмёт
    «Забронировать место»: отказ называет его бронь — приложение откроет её."""
    driver = user_factory("SeatsConflictDriver", role=UserRole.driver)
    passenger = user_factory("SeatsConflictPassenger")
    ride_id = _ride(client, driver, seats=3)
    first = client.post("/bookings", headers=passenger["auth"], json={"ride_id": ride_id, "seats": 2})
    assert first.status_code == 200, first.text
    reply = client.post("/bookings", headers=passenger["auth"], json={"ride_id": ride_id, "seats": 1})
    assert reply.status_code == 409, reply.text
    detail = reply.json()["detail"]
    assert detail["code"] == "booking_seats_conflict" and detail["booking_id"] == first.json()["id"]
    assert _seats_left(ride_id) == 1
