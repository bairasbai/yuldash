"""Завершение рейса приглашает оценить собственную состоявшуюся бронь."""
from sqlmodel import Session, select

from app.db import engine
from app.models import Booking, BookingStatus, Notification, UserRole
from app.services import _web_push_url
from test_flows import _book, _publish, just_left


def test_completion_targets_each_done_booking_and_rating(client, user_factory, monkeypatch):
    driver = user_factory("CompletionDriver", role=UserRole.driver)
    passengers = [user_factory(f"CompletionPassenger{i}") for i in range(4)]
    ride = _publish(client, driver, seats=4, depart_at=just_left())
    bookings = [_book(client, p, ride["id"]) for p in passengers]
    for booking in bookings[:2]:
        response = client.post(f"/bookings/{booking['id']}/confirm", headers=driver["auth"])
        assert response.status_code == 200, response.text
    # Неявка уже оформлена отменой: завершение рейса не приглашает её оценивать.
    with Session(engine) as session:
        missed = session.get(Booking, bookings[3]["id"])
        missed.status = BookingStatus.cancelled
        missed.no_show = True
        session.add(missed)
        session.commit()
    sent = []
    monkeypatch.setattr("app.services.send_push", lambda s, uid, title, body, data=None:
                        sent.append((uid, data)))
    response = client.post(f"/rides/{ride['id']}/complete", headers=driver["auth"])
    assert response.status_code == 200, response.text
    actual = [(uid, data) for uid, data in sent if uid in {p["id"] for p in passengers[:2]}]
    expected = [(p["id"], {"type": "booking_done", "id": str(b["id"])})
                for p, b in zip(passengers[:2], bookings[:2])]
    assert sorted(actual) == sorted(expected)
    with Session(engine) as session:
        notes = session.exec(select(Notification).where(
            Notification.user_id.in_([p["id"] for p in passengers]),
            Notification.ref_kind == "booking_done",
        )).all()
        assert sorted((n.user_id, n.ref_id) for n in notes) == sorted(
            (p["id"], b["id"]) for p, b in zip(passengers[:2], bookings[:2]))
    for passenger, booking in zip(passengers[:2], bookings[:2]):
        response = client.post(f"/bookings/{booking['id']}/rate",
                               headers=passenger["auth"], json={"stars": 5})
        assert response.status_code == 200, response.text
        assert response.json()["ratee_id"] == driver["id"]
    for passenger, booking in zip(passengers[2:], bookings[2:]):
        response = client.post(f"/bookings/{booking['id']}/rate",
                               headers=passenger["auth"], json={"stars": 5})
        assert response.status_code == 409, response.text
    assert not any(uid == passengers[3]["id"] for uid, _ in sent)
    assert (passengers[2]["id"], {"type": "booking", "id": str(bookings[2]["id"])}) in sent
    sent.clear()
    assert client.post(f"/rides/{ride['id']}/complete", headers=driver["auth"]).status_code == 200
    assert sent == []


def test_completed_booking_web_push_opens_rating_trip():
    assert _web_push_url({"type": "booking_done", "id": "42"}) == "/trip/42"
    assert _web_push_url({"ref_kind": "booking_done", "ref_id": 43}) == "/trip/43"
