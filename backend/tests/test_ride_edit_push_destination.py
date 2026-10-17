"""Изменение рейса открывает собственную бронь каждого получателя push."""
import pytest
from sqlmodel import Session, select

from app.db import engine
from app.models import Notification, User, UserRole
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

    monkeypatch.setattr("app.services.send_push", capture)
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


def test_ride_edit_notice_speaks_the_passengers_language_and_stays_in_the_feed(client, user_factory, monkeypatch):
    """Пассажирка с башкирским в приложении получала «Поездка обновлена» по-русски, а не дошёл
    пуш (телефон выключен, нет сети) — о новой цене и времени узнавала только у машины: записи
    в Центре уведомлений не было (аудит BlaBlaCar, 17.10.2026)."""
    driver = user_factory("EditLangDriver", role=UserRole.driver)
    ba_pax = user_factory("EditLangPassengerBa")
    with Session(engine) as s:
        u = s.get(User, ba_pax["id"])
        u.language = "ba"
        s.add(u)
        s.commit()
    ride = _publish(client, driver, price=500)
    booking = _book(client, ba_pax, ride["id"])
    sent = []
    monkeypatch.setattr("app.services.send_push", lambda session, uid, title, body, **kw: sent.append((uid, title, body)))

    response = client.patch(f"/rides/{ride['id']}", headers=driver["auth"], json={"price": 400, "comment": "Жду у заправки"})
    assert response.status_code == 200, response.text

    assert len(sent) == 1
    uid, title, body = sent[0]
    assert uid == ba_pax["id"]
    assert title == "Сәфәр яңыртылды"
    assert "хаҡ, аңлатма" in body and "цена" not in body
    with Session(engine) as s:
        rows = s.exec(select(Notification).where(
            Notification.user_id == ba_pax["id"], Notification.ref_kind == "booking",
            Notification.ref_id == booking["id"], Notification.title_ru == "Поездка обновлена",
        )).all()
    assert len(rows) == 1, "правка рейса не оставила записи в Центре уведомлений"
    assert "цена, комментарий" in rows[0].body_ru
    assert "хаҡ, аңлатма" in rows[0].body_ba
