"""Штатные входы в редактор не должны частично применять отклонённую правку."""
from datetime import timedelta

import pytest
from sqlalchemy import event
from sqlmodel import Session, select

from app.db import engine
from app.models import Booking, Notification, Ride, TextFlag, UserRole
from app.timeutil import utcnow


MARKED_COMMENT = "ты мудак"


def _edit_scene(client, user_factory):
    driver = user_factory("Edit atomic driver", role=UserRole.driver)
    passenger = user_factory("Edit atomic passenger")
    departure = utcnow() + timedelta(days=1)
    created = client.post("/rides", headers=driver["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай", "depart_at": departure.isoformat(),
        "seats_total": 3, "price": 500, "comment": "Старый комментарий",
    })
    assert created.status_code in (200, 201), created.text
    ride_id = created.json()["id"]
    booked = client.post("/bookings", headers=passenger["auth"], json={
        "ride_id": ride_id, "seats": 2, "pay_amount": 1000,
    })
    assert booked.status_code in (200, 201), booked.text
    booking_id = booked.json()["id"]
    with Session(engine) as session:
        expected = session.get(Ride, ride_id).terms_version
    return driver, departure, ride_id, booking_id, expected


@pytest.mark.parametrize(("method", "suffix"), [("PATCH", ""), ("POST", "/edit")])
def test_rejected_mixed_edit_keeps_original_ride_and_booking(client, user_factory, method, suffix):
    driver, departure, ride_id, booking_id, expected = _edit_scene(client, user_factory)
    reply = client.request(method, f"/rides/{ride_id}{suffix}", headers=driver["auth"], json={
        "price": 400, "comment": MARKED_COMMENT, "expected_terms_version": expected,
        "depart_at": (departure + timedelta(hours=1)).isoformat(),
    })
    assert reply.status_code == 409, reply.text

    with Session(engine) as session:
        ride = session.get(Ride, ride_id)
        booking = session.get(Booking, booking_id)
        assert (ride.price, ride.comment, ride.terms_version) == (500, "Старый комментарий", expected)
        assert (booking.price, booking.pay_amount) == (1000, 1000)
        assert not session.exec(select(TextFlag).where(
            TextFlag.user_id == driver["id"], TextFlag.place == "ride_comment", TextFlag.ref_id == ride_id,
        )).first()
        assert not session.exec(select(Notification).where(
            Notification.ref_kind == "booking", Notification.ref_id == booking_id,
            Notification.title_ru == "Поездка обновлена",
        )).first()


@pytest.mark.parametrize("fail_journal", [False, True])
def test_successful_flagged_edit_survives_journal_failure(client, user_factory, fail_journal):
    driver, _, ride_id, booking_id, expected = _edit_scene(client, user_factory)

    def interrupt_journal(_connection, _cursor, statement, *_args):
        if statement.startswith("INSERT INTO textflag"):
            raise RuntimeError("Synthetic TextFlag write failure")

    if fail_journal:
        event.listen(engine, "before_cursor_execute", interrupt_journal)
    try:
        reply = client.patch(f"/rides/{ride_id}", headers=driver["auth"], json={
            "price": 400, "comment": MARKED_COMMENT, "expected_terms_version": expected,
        })
    finally:
        if fail_journal:
            event.remove(engine, "before_cursor_execute", interrupt_journal)
    assert reply.status_code == 200, reply.text
    assert reply.json()["comment"] == MARKED_COMMENT

    with Session(engine) as session:
        ride = session.get(Ride, ride_id)
        booking = session.get(Booking, booking_id)
        assert (ride.price, ride.terms_version, ride.comment) == (400, expected + 1, MARKED_COMMENT)
        assert (booking.price, booking.pay_amount) == (800, 800)
        flags = session.exec(select(TextFlag).where(
            TextFlag.user_id == driver["id"], TextFlag.place == "ride_comment", TextFlag.ref_id == ride_id,
        )).all()
        assert [flag.kind for flag in flags] == ([] if fail_journal else ["abuse"])
