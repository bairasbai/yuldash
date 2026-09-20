"""Изменение рейса открывает собственную бронь каждого получателя push."""
import pytest

from app.models import UserRole
from test_flows import _book, _publish


@pytest.mark.parametrize("method", ["patch", "post"])
def test_ride_edit_push_opens_each_passenger_booking(client, user_factory, monkeypatch, method):
    driver = user_factory("PushEditDriver", role=UserRole.driver)
    passengers = [user_factory("PushEditPassengerA"), user_factory("PushEditPassengerB")]
    ride = _publish(client, driver, price=500)
    bookings = [_book(client, passenger, ride["id"]) for passenger in passengers]
    sent = []

    def capture(session, user_id, title, body, data=None):
        sent.append((user_id, data))

    monkeypatch.setattr("app.routers.rides.send_push", capture)
    url = f"/rides/{ride['id']}" + ("/edit" if method == "post" else "")
    response = getattr(client, method)(url, headers=driver["auth"], json={"price": 400})
    assert response.status_code == 200, response.text
    assert response.json()["price"] == 400
    assert sorted(sent, key=lambda item: item[0]) == sorted(
        [(passenger["id"], {"type": "booking", "id": str(booking["id"])})
         for passenger, booking in zip(passengers, bookings)], key=lambda item: item[0]
    )

    # Повтор без изменений не рассылает повторное уведомление.
    sent.clear()
    assert getattr(client, method)(url, headers=driver["auth"], json={"price": 400}).status_code == 200
    assert sent == []
