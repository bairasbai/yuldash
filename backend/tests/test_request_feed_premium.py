"""Премиальная лента заявок показывает только реальные факты заявки и пассажира."""

from sqlmodel import Session

from app import models as M
from app.db import engine
from app.models import UserRole


def test_feed_carries_real_route_budget_and_passenger_trust(client, user_factory):
    passenger = user_factory("Айгуль")
    driver = user_factory("Ринат", role=UserRole.driver, gender="female")
    rater = user_factory("Оценивший водитель", role=UserRole.driver)

    created = client.post("/requests", headers=passenger["auth"], json={
        "from_city": "Уфа",
        "to_city": "Сибай",
        "desired_at": "2030-09-05T18:30:00",
        "seats": 1,
        "max_price": 450,
        "category": "regular",
        "comment": "У автовокзала",
    })
    assert created.status_code == 200, created.text

    with Session(engine) as session:
        session.add(M.Rating(rater_id=rater["id"], ratee_id=passenger["id"], stars=4))
        session.commit()

    feed = client.get("/requests/feed", headers=driver["auth"])
    assert feed.status_code == 200, feed.text
    row = next(item for item in feed.json() if item["id"] == created.json()["id"])

    # Клиент прислал 18:30 местного времени; сервер хранит UTC (−5 часов), Android
    # через formatDepart вернёт человеку исходные 18:30.
    assert row["desired_at"].startswith("2030-09-05T13:30")
    assert row["max_price"] == 450
    assert row["distance_km"] is not None and row["distance_km"] > 100
    assert row["category"] == "regular"
    assert row["passenger_rating"] == 4.0
    assert row["passenger_rating_count"] == 1
    assert row["passenger_verified"] is True
    assert "phone" not in row and "from_lat" not in row and "to_lat" not in row


def test_feed_keeps_unknown_optional_facts_null(client, user_factory):
    passenger = user_factory("Новый пассажир")
    driver = user_factory("Водитель без маршрута", role=UserRole.driver)
    created = client.post("/requests", headers=passenger["auth"], json={
        "from_city": "Неизвестное село А",
        "to_city": "Неизвестное село Б",
        "seats": 2,
        "comment": "Договоримся",
    })
    assert created.status_code == 200, created.text

    row = next(item for item in client.get("/requests/feed", headers=driver["auth"]).json()
               if item["id"] == created.json()["id"])
    assert row["max_price"] is None
    assert row["passenger_rating"] is None
    assert row["passenger_rating_count"] == 0
