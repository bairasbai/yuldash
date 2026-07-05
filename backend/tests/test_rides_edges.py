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
