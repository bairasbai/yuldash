from datetime import timedelta

import pytest
from sqlmodel import Session

from app.db import engine
from app.models import Block, Ride, User, UserRole
from app.routers import rides as rides_router
from app.timeutil import utcnow


def _ride(driver, free, *, origin="Групповое", destination="Сборное", stops="", minutes=0):
    with Session(engine, expire_on_commit=False) as session:
        ride = Ride(
            driver_id=driver["id"], from_city=origin, to_city=destination,
            waypoints=stops, depart_at=utcnow() + timedelta(days=2, minutes=minutes),
            seats_total=free, seats_left=free, price=300,
        )
        session.add(ride)
        session.commit()
        return ride.id


@pytest.mark.parametrize("path", ["/rides", "/rides/near"])
def test_group_size_bounds_and_default(client, path):
    assert client.get(path, params={"seats": 0}).status_code == 422
    assert client.get(path, params={"seats": 9}).status_code == 422
    assert client.get(path, params={"seats": 8}).status_code == 200
    assert client.get(path, params={"seats": 1}).json() == client.get(path).json()


def test_group_size_bypasses_unfiltered_cache(client, user_factory, monkeypatch):
    driver = user_factory("Group cache driver", role=UserRole.driver)
    one = _ride(driver, 1, origin="Кешево")
    public = client.get("/rides", params={"from_city": "Кешево"}).json()
    cached_ride = next(row for row in public if row["id"] == one)
    calls = []
    # A cached ride with one seat is exactly the stale result a group must never see.
    # Use the public cache payload to keep this independent of the size of the shared test feed.
    cache = {"rides:active:v3": [cached_ride]}

    def get_cached(key):
        calls.append(key)
        return cache.get(key)

    monkeypatch.setattr(rides_router, "cache_get_json", get_cached)
    assert any(row["id"] == one for row in client.get("/rides").json())
    assert calls == ["rides:active:v3"]
    assert all(row["id"] != one for row in client.get("/rides", params={"seats": 2}).json())
    assert calls == ["rides:active:v3"]
    assert any(row["id"] == one for row in client.get("/rides", params={"seats": 1}).json())


def test_near_suggestions_respect_access_verification_and_paging(client, user_factory):
    passenger = user_factory("Group passenger")
    verified = user_factory("Group verified", role=UserRole.driver)
    unverified = user_factory("Group unverified", role=UserRole.driver)
    blocked = user_factory("Group blocked", role=UserRole.driver)
    with Session(engine) as session:
        session.get(User, unverified["id"]).verified = False
        session.add(Block(user_id=passenger["id"], blocked_user_id=blocked["id"]))
        session.commit()
    _ride(verified, 1)
    eligible = [_ride(verified, 2, minutes=minute) for minute in (1, 2)]
    _ride(unverified, 2)
    _ride(unverified, 3)
    _ride(blocked, 3)
    args = {"from_city": "Групповое", "to_city": "Сборное", "verified_only": True, "limit": 1}

    response = client.get("/rides/near", params={**args, "seats": 3}, headers=passenger["auth"])
    assert response.status_code == 200, response.text
    assert response.json() == {
        "items": [], "count": 0, "hidden_unverified": 1,
        "hidden_by_seats": 3, "max_free_seats": 2, "count_at_max_free": 2,
    }
    for offset, expected in enumerate(eligible):
        response = client.get(
            "/rides/near", params={**args, "seats": 2, "offset": offset}, headers=passenger["auth"],
        ).json()
        assert response["count"] == 2
        assert [row["id"] for row in response["items"]] == [expected]


def test_group_search_reads_free_places_on_chosen_leg(client, user_factory):
    driver = user_factory("Group segment driver", role=UserRole.driver)
    passenger = user_factory("Group segment passenger")
    ride = _ride(driver, 4, origin="Сибай", destination="Акъяр", stops="Ургаза | Бурибай")
    booking = client.post("/bookings", json={
        "ride_id": ride, "seats": 2, "pickup_stop": "Сибай", "dropoff_stop": "Ургаза",
    }, headers=passenger["auth"])
    assert booking.status_code == 200, booking.text
    for path in ("/rides", "/rides/near"):
        def results(start, end, seats):
            response = client.get(path, params={"from_city": start, "to_city": end, "seats": seats})
            assert response.status_code == 200, response.text
            payload = response.json()
            return {row["id"]: row for row in (payload["items"] if isinstance(payload, dict) else payload)}

        assert results("Ургаза", "Акъяр", 4)[ride]["seats_on_segment"] == 4
        assert ride not in results("Сибай", "Ургаза", 3)
        assert results("Сибай", "Ургаза", 2)[ride]["seats_on_segment"] == 2
