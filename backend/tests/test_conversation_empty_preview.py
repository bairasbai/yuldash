"""Inbox emptiness is per viewer; legacy preview text stays wire-compatible."""
import pytest

from app.models import UserRole


def _trip(client, user_factory):
    driver = user_factory("PreviewDriver", role=UserRole.driver)
    passenger = user_factory("PreviewPassenger")
    ride = client.post("/rides", headers=driver["auth"], json={
        "from_city": "PreviewFrom", "to_city": "PreviewTo",
        "depart_at": "2030-01-01T10:00:00", "seats_total": 3, "price": 300,
    })
    assert ride.status_code == 200, ride.text
    booking = client.post("/bookings", headers=passenger["auth"], json={
        "ride_id": ride.json()["id"], "seats": 1,
    })
    assert booking.status_code == 200, booking.text
    return driver, passenger, booking.json()["id"]


def _row(client, actor, booking_id):
    response = client.get("/conversations", headers=actor["auth"])
    assert response.status_code == 200, response.text
    return next(r for r in response.json() if r["booking_id"] == booking_id)


def test_empty_active_chat_has_explicit_marker_for_both_participants(client, user_factory):
    driver, passenger, booking_id = _trip(client, user_factory)
    for actor in (driver, passenger):
        row = _row(client, actor, booking_id)
        assert row["has_messages"] is False
        assert row["last_message"] == "Чат открыт"  # old clients keep the same response
    outsider = user_factory("PreviewOutsider")
    assert client.get("/conversations", headers=outsider["auth"]).json() == []


@pytest.mark.parametrize("text", ["Чат открыт", "Голосовое", "Сообщение удалено", "Обычное сообщение"])
def test_actual_human_text_is_not_a_placeholder(client, user_factory, text):
    driver, passenger, booking_id = _trip(client, user_factory)
    sent = client.post(f"/bookings/{booking_id}/messages", headers=passenger["auth"], json={"text": text})
    assert sent.status_code == 200, sent.text
    for actor in (driver, passenger):
        row = _row(client, actor, booking_id)
        assert row["has_messages"] is True
        assert row["last_message"] == text


def test_hidden_message_does_not_leak_existence_to_viewer(client, user_factory):
    driver, passenger, booking_id = _trip(client, user_factory)
    sent = client.post(f"/bookings/{booking_id}/messages", headers=passenger["auth"], json={"text": "Чат открыт"})
    assert sent.status_code == 200, sent.text
    hidden = client.delete(f"/bookings/{booking_id}/messages/{sent.json()['id']}?scope=me", headers=driver["auth"])
    assert hidden.status_code == 200, hidden.text
    assert _row(client, driver, booking_id)["has_messages"] is False
    assert _row(client, passenger, booking_id)["has_messages"] is True
