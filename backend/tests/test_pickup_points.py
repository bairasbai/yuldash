"""F14 — точки сбора по ориентирам города/села.

Проверяем: сид популярных городов есть; подсказки фильтруются по городу; при создании
поездки известная точка поднимается (usage_count), а новая — пополняет справочник;
эндпоинт публичный и не отдаёт персональных данных."""
from datetime import timedelta

from sqlmodel import Session, select

from app.db import engine
from app.models import PickupPoint, UserRole
from app.timeutil import utcnow


def _ride_body(**overrides):
    body = {
        "from_city": "Сибай",
        "to_city": "Уфа",
        "depart_at": (utcnow() + timedelta(hours=5)).isoformat(),
        "seats_total": 3,
        "price": 400,
    }
    body.update(overrides)
    return body


def test_seed_has_points_for_major_cities(client):
    # Сид крупных городов присутствует (lifespan → seed_pickup_points).
    for city in ("Уфа", "Сибай", "Баймаҡ", "Белорецк", "Учалы"):
        r = client.get("/pickup-points", params={"city": city})
        assert r.status_code == 200, r.text
        items = r.json()
        assert len(items) >= 1, f"нет сидовых точек для {city}"
        for it in items:
            assert it["city"] == city
            assert it["title_ru"], "должно быть русское название ориентира"
            assert it["title_ba"], "должно быть башкирское название ориентира"


def test_suggestions_filter_by_city(client):
    ufa = client.get("/pickup-points", params={"city": "Уфа"}).json()
    sibay = client.get("/pickup-points", params={"city": "Сибай"}).json()
    assert {p["city"] for p in ufa} == {"Уфа"}
    assert {p["city"] for p in sibay} == {"Сибай"}
    # Разные города — разные наборы ориентиров.
    assert {p["title_ru"] for p in ufa} != {p["title_ru"] for p in sibay}


def test_endpoint_is_public_and_leaks_no_personal_data(client):
    # Публичный справочник ориентиров: работает без токена, персональных полей нет.
    r = client.get("/pickup-points", params={"city": "Баймаҡ"})   # без Authorization
    assert r.status_code == 200
    allowed = {"id", "city", "title_ru", "title_ba", "lat", "lng", "usage_count"}
    for it in r.json():
        assert set(it.keys()) <= allowed
        assert "phone" not in it and "passenger_id" not in it and "driver_id" not in it


def test_choosing_known_point_on_ride_bumps_usage(client, user_factory):
    driver = user_factory("PickupDriver", role=UserRole.driver)
    sibay = client.get("/pickup-points", params={"city": "Сибай"}).json()
    point = sibay[0]
    before = point["usage_count"]

    resp = client.post("/rides", headers=driver["auth"], json=_ride_body(pickup_point_id=point["id"]))
    assert resp.status_code == 200, resp.text
    ride = resp.json()
    # Привязка: координаты/название точки перенеслись в поездку.
    assert ride["pickup"] == point["title_ru"]
    assert ride["pickup_lat"] == point["lat"]
    assert ride["pickup_lng"] == point["lng"]

    with Session(engine) as s:
        pt = s.get(PickupPoint, point["id"])
        assert pt.usage_count == before + 1

    # В подсказках выбранная точка теперь не ниже — сортировка по usage_count ↓.
    after = client.get("/pickup-points", params={"city": "Сибай"}).json()
    assert after[0]["usage_count"] >= before + 1


def test_new_pickup_on_ride_grows_directory(client, user_factory):
    driver = user_factory("PickupDriver2", role=UserRole.driver)
    city = "Кушнаренково"   # не сидовый город
    assert client.get("/pickup-points", params={"city": city}).json() == []

    resp = client.post("/rides", headers=driver["auth"], json=_ride_body(
        from_city=city, to_city="Уфа",
        pickup="У сельсовета", pickup_lat=55.101, pickup_lng=55.353,
    ))
    assert resp.status_code == 200, resp.text

    after = client.get("/pickup-points", params={"city": city}).json()
    assert len(after) == 1
    assert after[0]["title_ru"] == "У сельсовета"
    assert after[0]["usage_count"] == 1
    assert after[0]["lat"] == 55.101 and after[0]["lng"] == 55.353

    # Повторная поездка с тем же ориентиром — не дублируем, а поднимаем usage.
    resp2 = client.post("/rides", headers=driver["auth"], json=_ride_body(
        from_city=city, to_city="Уфа",
        pickup="У сельсовета", pickup_lat=55.101, pickup_lng=55.353,
    ))
    assert resp2.status_code == 200
    after2 = client.get("/pickup-points", params={"city": city}).json()
    assert len(after2) == 1
    assert after2[0]["usage_count"] == 2


def test_choosing_known_point_on_request_bumps_usage(client, user_factory):
    passenger = user_factory("PickupPassenger")
    ufa = client.get("/pickup-points", params={"city": "Уфа"}).json()
    point = ufa[0]
    before = point["usage_count"]

    resp = client.post("/requests", headers=passenger["auth"], json={
        "from_city": "Уфа", "to_city": "Сибай", "seats": 1,
        "pickup_point_id": point["id"],
    })
    assert resp.status_code == 200, resp.text

    with Session(engine) as s:
        pt = s.get(PickupPoint, point["id"])
        assert pt.usage_count == before + 1
