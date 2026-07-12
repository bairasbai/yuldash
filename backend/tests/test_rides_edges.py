"""Regression tests for ride search, caching, and recurrence edge cases."""

from app.models import UserRole

from test_flows import _publish


def test_create_ride_clamps_price_and_seats(client, user_factory):
    driver = user_factory("ClampRideDriver", role=UserRole.driver)
    created = client.post("/rides", headers=driver["auth"], json={
        "from_city": "ClampA",
        "to_city": "ClampB",
        "depart_at": "2030-01-01T10:00:00",
        "seats_total": 99,
        "price": 999999,
    })
    assert created.status_code == 200
    assert created.json()["seats_total"] == 8
    assert created.json()["seats_left"] == 8
    assert created.json()["price"] == 100000


def test_past_rides_hidden_from_search(client, user_factory):
    """Аудит 2026-07-04: уже уехавшие поездки не висят в выдаче (фильтр по depart_at + грейс)."""
    from datetime import timedelta
    from sqlmodel import Session
    from app.db import engine
    from app.models import Ride, RideStatus
    from app.timeutil import utcnow
    drv = user_factory("PastDrv", role=UserRole.driver)
    _publish(client, drv, frm="ВремяА", to="Будущее")   # depart_at 2030 → видна
    with Session(engine) as s:                            # уехала вчера → скрыта
        s.add(Ride(driver_id=drv["id"], from_city="ВремяА", to_city="Прошлое",
                   depart_at=utcnow() - timedelta(days=1), seats_total=2, seats_left=2,
                   price=100, status=RideStatus.active))
        s.commit()
    rows = client.get("/rides", params={"from_city": "ВремяА"}).json()
    tos = [r["to_city"] for r in rows]
    assert "Будущее" in tos          # будущая поездка в выдаче
    assert "Прошлое" not in tos      # вчерашняя отсеяна


def test_date_filter_in_search_and_near(client, user_factory):
    """F4: параметр date=YYYY-MM-DD оставляет только поездки этого дня (в /rides и /rides/near)."""
    drv = user_factory("DateDrv", role=UserRole.driver)
    _publish(client, drv, frm="ДатаА", to="Первое", depart_at="2030-01-01T10:00:00")
    _publish(client, drv, frm="ДатаА", to="Второе", depart_at="2030-01-02T10:00:00")
    day1 = client.get("/rides", params={"from_city": "ДатаА", "date": "2030-01-01"}).json()
    assert [r["to_city"] for r in day1] == ["Первое"]
    day2 = client.get("/rides", params={"from_city": "ДатаА", "date": "2030-01-02"}).json()
    assert [r["to_city"] for r in day2] == ["Второе"]
    # без date — обе (обратная совместимость)
    assert len(client.get("/rides", params={"from_city": "ДатаА"}).json()) == 2
    # /rides/near — тот же фильтр
    near = client.get("/rides/near", params={"from_city": "ДатаА", "date": "2030-01-01"}).json()
    assert near["count"] == 1 and near["items"][0]["to_city"] == "Первое"
    # кривая дата → 422, не 500
    assert client.get("/rides", params={"date": "не-дата"}).status_code == 422


def test_ride_input_validation_rejects_junk(client, user_factory):
    """WP-9: слишком длинный город и мусорные координаты отклоняются (422), а не пишутся в БД."""
    driver = user_factory("ValRideDriver", role=UserRole.driver)
    base = {"to_city": "Сибай", "depart_at": "2030-01-01T10:00:00", "seats_total": 2, "price": 100}
    # город > 120 символов → 422
    assert client.post("/rides", headers=driver["auth"],
                       json={**base, "from_city": "Г" * 200}).status_code == 422
    # координаты вне диапазона → 422
    assert client.post("/rides", headers=driver["auth"],
                       json={**base, "from_city": "Баймаҡ", "pickup_lat": 999.0}).status_code == 422


def test_weekly_and_weekday_recurrence_create_four_followups(client, user_factory):
    driver = user_factory("RecurringRideDriver", role=UserRole.driver)
    weekly = client.post("/rides", headers=driver["auth"], json={
        "from_city": "WeeklyA",
        "to_city": "WeeklyB",
        "depart_at": "2030-01-01T10:00:00",
        "seats_total": 2,
        "price": 100,
        "recurrence": "weekly",
    })
    assert weekly.status_code == 200
    assert len(client.get("/rides", params={"from_city": "WeeklyA"}).json()) == 5

    weekdays = client.post("/rides", headers=driver["auth"], json={
        "from_city": "WeekdayA",
        "to_city": "WeekdayB",
        "depart_at": "2030-01-04T10:00:00",
        "seats_total": 2,
        "price": 100,
        "recurrence": "weekdays",
    })
    assert weekdays.status_code == 200
    rows = client.get("/rides", params={"from_city": "WeekdayA"}).json()
    assert len(rows) == 5
    assert all(row["from_city"] == "WeekdayA" for row in rows)


def test_rides_cache_hit_still_applies_user_block_filter(client, user_factory, monkeypatch):
    driver = user_factory("CachedBlockedDriver", role=UserRole.driver)
    passenger = user_factory("CachedBlockedPassenger")
    ride = _publish(client, driver, frm="CachedBlockA", to="CachedBlockB")
    cached_payload = client.get(f"/rides/{ride['id']}").json()

    monkeypatch.setattr("app.routers.rides.cache_get_json", lambda key: [cached_payload] if key == "rides:active:v1" else None)
    assert len(client.get("/rides").json()) == 1

    assert client.post("/blocks", headers=passenger["auth"], json={"blocked_user_id": driver["id"]}).status_code == 200
    blocked = client.get("/rides", headers=passenger["auth"]).json()
    assert blocked == []


def test_price_hint_empty_route_returns_zero(client):
    assert client.get("/rides/price_hint", params={"from_city": "NoSuchFrom", "to_city": "NoSuchTo"}).json() == {"avg": 0, "count": 0}


def test_ride_quiet_amenity_flows_through(client, user_factory):
    """«Тихая поездка» (quiet) проходит create→read как остальные удобства; по умолчанию False."""
    driver = user_factory("QuietRideDriver", role=UserRole.driver)
    _publish(client, driver, frm="QuietA", to="QuietB", quiet=True)
    _publish(client, driver, frm="QuietA", to="QuietB")   # обычная (quiet по умолчанию)
    rows = client.get("/rides", params={"from_city": "QuietA"}).json()
    assert len(rows) == 2
    assert sum(1 for r in rows if r["quiet"]) == 1          # ровно одна тихая
    assert any(r["quiet"] is False for r in rows)           # и одна обычная


def test_ride_filters_for_category_and_preferences(client, user_factory):
    driver = user_factory("FilterRideDriver", role=UserRole.driver)
    _publish(
        client,
        driver,
        frm="FilterA",
        to="FilterB",
        category="parcel",
        pets_allowed=True,
        child_seat=True,
        baggage=True,
    )
    _publish(client, driver, frm="FilterA", to="FilterB", category="regular")

    rows = client.get("/rides", params={
        "from_city": "FilterA",
        "category": "parcel",
        "pets_allowed": True,
        "child_seat": True,
        "baggage": True,
    }).json()
    assert len(rows) == 1
    assert rows[0]["category"] == "parcel"
    assert rows[0]["pets_allowed"] is True
    assert rows[0]["child_seat"] is True
    assert rows[0]["baggage"] is True
