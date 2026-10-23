"""«Люди по пути» — точный порядок остановок и дата, а не маленький крюк по прямой."""
from datetime import timedelta

import pytest
from sqlalchemy import event
from sqlmodel import Session

from app.db import engine
from app.geo import _to_row, fold
from app.models import Ride, RideRequest, RideStatus, UserRole
from app.routers import rides as rides_mod
from app.routers import requests as requests_mod
from app.stop_search import route_fit, variants
from test_booking_segments import seed_route


@pytest.mark.parametrize("frm,to,pickup,dropoff", [
    ("Сибай", "Акъяр", "Сибай", "Акъяр"),
    ("Ургаза", "Акъяр", "Ургаза", "Акъяр"),
    ("Сибай", "Бурибай", "Сибай", "Бурибай"),
    ("Урғаҙа", "Бурибай", "Ургаза", "Бурибай"),
])
def test_feed_and_request_match_use_the_same_ordered_route(client, user_factory, frm, to, pickup, dropoff):
    driver = user_factory("Route feed driver", role=UserRole.driver)
    passenger = user_factory("Route feed passenger")
    rid, reqid = seed_route(driver, passenger, frm=frm, to=to)
    feed = client.get("/requests/feed", headers=driver["auth"], params={"on_route": True})
    assert feed.status_code == 200
    row = next(r for r in feed.json() if r["id"] == reqid)
    assert (row["matched_ride_id"], row["matched_pickup_stop"], row["matched_dropoff_stop"]) == (rid, pickup, dropoff)
    matched = client.get("/match/rides", headers=passenger["auth"], params={"request_id": reqid})
    assert matched.status_code == 200
    ride = next(r for r in matched.json() if r["id"] == rid)
    assert ride["matched_stop"] == (None if pickup == "Сибай" else pickup)
    assert ride["matched_from_start"] is (pickup == "Сибай")
    assert ride["matched_dropoff_stop"] == (None if dropoff == "Акъяр" else dropoff)
    assert ride["matched_to_end"] is (dropoff == "Акъяр")


@pytest.mark.parametrize("frm,to", [("Бурибай", "Ургаза"), ("Акъяр", "Сибай"),
                                     ("Ургаза", "Ургаза"), ("Ург", "Акъяр")])
def test_reverse_same_place_and_partial_names_are_not_on_route(client, user_factory, frm, to):
    driver = user_factory("Wrong route driver", role=UserRole.driver)
    passenger = user_factory("Wrong route passenger")
    rid, reqid = seed_route(driver, passenger, frm=frm, to=to)
    feed = client.get("/requests/feed", headers=driver["auth"], params={"on_route": True}).json()
    assert not any(r["id"] == reqid for r in feed)
    matched = client.get("/match/rides", headers=passenger["auth"], params={"request_id": reqid}).json()
    assert not any(r["id"] == rid for r in matched)


def test_on_route_filter_precedes_the_two_hundred_result_limit(client, user_factory):
    driver = user_factory("Before limit driver", role=UserRole.driver)
    passenger = user_factory("Before limit passenger")
    rid, reqid = seed_route(driver, passenger)
    with Session(engine) as session:
        for _ in range(205):
            session.add(RideRequest(passenger_id=passenger["id"], from_city="Не по пути", to_city="Другое село"))
        session.commit()
    found = client.get("/requests/feed", headers=driver["auth"], params={"on_route": True}).json()
    assert any(r["id"] == reqid and r["matched_ride_id"] == rid for r in found)


def test_earliest_departure_then_id_is_stable_and_dates_are_local(client, user_factory):
    driver = user_factory("Stable route driver", role=UserRole.driver)
    passenger = user_factory("Stable route passenger")
    rid, reqid = seed_route(driver, passenger)
    with Session(engine) as session:
        first = session.get(Ride, rid)
        # В UTC это ещё предыдущий день, в Уфе уже следующий — совпадение по местному дню.
        first.depart_at = first.depart_at.replace(hour=21, minute=0, second=0, microsecond=0)
        req = session.get(RideRequest, reqid)
        req.desired_at = first.depart_at + timedelta(hours=4)
        session.add(first)
        session.add(req)
        session.add(Ride(driver_id=driver["id"], from_city=first.from_city, to_city=first.to_city,
                         waypoints=first.waypoints, depart_at=first.depart_at,
                         seats_total=3, seats_left=3))
        session.commit()
    feed = client.get("/requests/feed", headers=driver["auth"], params={"on_route": True}).json()
    assert next(r for r in feed if r["id"] == reqid)["matched_ride_id"] == rid
    with Session(engine) as session:
        req = session.get(RideRequest, reqid)
        req.desired_at += timedelta(days=1)
        session.add(req)
        session.commit()
    feed = client.get("/requests/feed", headers=driver["auth"], params={"on_route": True}).json()
    assert not any(r["id"] == reqid for r in feed)


@pytest.mark.parametrize("reason", ["full", "stale", "cancelled", "child", "no_time"])
def test_feed_rechecks_capacity_activity_preferences_and_unknown_time(client, user_factory, reason):
    driver = user_factory("Capacity feed driver", role=UserRole.driver)
    passenger = user_factory("Capacity feed passenger")
    rid, reqid = seed_route(driver, passenger)
    with Session(engine) as session:
        ride, req = session.get(Ride, rid), session.get(RideRequest, reqid)
        if reason == "full":
            ride.seats_left = 0
        elif reason == "stale":
            ride.depart_at -= timedelta(days=30)
        elif reason == "cancelled":
            ride.status = RideStatus.cancelled
        elif reason == "child":
            req.child_seat = True
        else:
            req.desired_at = None
        session.add(ride)
        session.add(req)
        session.commit()
    feed = client.get("/requests/feed", headers=driver["auth"], params={"on_route": True}).json()
    if reason == "no_time":
        row = next(r for r in feed if r["id"] == reqid)
        assert row["matched_ride_id"] == rid and row["matched_ride_depart_at"] is not None
        assert row["desired_at"] is None
    else:
        assert not any(r["id"] == reqid for r in feed)


def test_strict_namesakes_require_district_even_if_only_one_is_on_the_ride():
    rows = [_to_row(1, "Абзаково", "Абҙаҡ", "Башкортостан", "village", "Баймакский р-н", 52, 58),
            _to_row(2, "Абзаково", "Абҙаҡ", "Башкортостан", "village", "Учалинский р-н", 54, 59)]
    entry = {"rows": rows, "exact": {}, "with_district": {}, "namesakes": {}}
    for row in rows:
        for key in {row.key_ru, row.key_ba}:
            entry["exact"].setdefault(key, row)
            entry["namesakes"].setdefault(key, []).append(row)
            entry["with_district"][(key, fold(row.district))] = row
    ride = {"from_city": "Сибай", "to_city": "Акъяр", "waypoints": "Абзаково (Учалинский р-н)"}
    assert not route_fit(ride, variants(None, "Абзаково", entry), ["Акъяр"], entry, strict=True).ok
    assert not route_fit(ride, ["Абзаково (Баймакский р-н)"], ["Акъяр"], entry, strict=True).ok
    fit = route_fit(ride, ["Абҙаҡ (Учалинский р-н)"], ["Акъяр"], entry, strict=True)
    assert fit.ok and fit.matched_stop == "Абзаково (Учалинский р-н)" and fit.matched_to_end


def test_partial_endpoint_filter_never_claims_a_canonical_pickup_or_dropoff():
    ride = {"from_city": "Сибай", "to_city": "Акъяр", "waypoints": "Ургаза | Бурибай"}
    fit = route_fit(ride, ["Си"], ["Ак"], {"exact": {}, "with_district": {}, "namesakes": {}})
    assert fit.ok
    assert not fit.matched_from_start and not fit.matched_to_end
    assert fit.matched_stop is None and fit.matched_dropoff_stop is None


def test_filtered_search_hints_do_not_enter_the_shared_cache(client, user_factory, monkeypatch):
    driver = user_factory("Cache segment driver", role=UserRole.driver)
    passenger = user_factory("Cache segment passenger")
    rid, _ = seed_route(driver, passenger)
    writes = []
    monkeypatch.setattr(rides_mod, "cache_set_json", lambda *args: writes.append(args))
    result = client.get("/rides", params={"from_city": "Ургаза", "to_city": "Бурибай", "limit": 200})
    assert result.status_code == 200
    row = next(r for r in result.json() if r["id"] == rid)
    assert row["matched_dropoff_stop"] == "Бурибай"
    assert not writes


def test_match_scans_bounded_pages_until_an_exact_section_is_found(client, user_factory, monkeypatch):
    driver = user_factory("Paged section driver", role=UserRole.driver)
    passenger = user_factory("Paged section passenger")
    rid, reqid = seed_route(driver, passenger)
    with Session(engine) as session:
        ride = session.get(Ride, rid)
        req = session.get(RideRequest, reqid)
        ride.waypoints = "ПромежуточнаяТестоваяБ | ПромежуточнаяТестоваяВ"
        req.from_city, req.to_city = "ПромежуточнаяТестоваяБ", "ПромежуточнаяТестоваяВ"
        for minutes in range(6, 0, -1):
            session.add(Ride(driver_id=driver["id"], from_city="НеподходящаяТестоваяА", to_city="НеподходящаяТестоваяГ",
                             depart_at=ride.depart_at - timedelta(minutes=minutes),
                             seats_total=3, seats_left=3))
        session.add(ride)
        session.add(req)
        session.commit()
    monkeypatch.setattr(requests_mod, "FEED_MAX", 3)
    scanned = []

    def observe(_conn, _cursor, statement, parameters, _context, _many):
        text = statement.lower()
        if "from ride " in text and "ride.seats_left >=" in text:
            scanned.append(text)

    event.listen(engine, "before_cursor_execute", observe)
    try:
        result = client.get("/match/rides", headers=passenger["auth"], params={"request_id": reqid})
    finally:
        event.remove(engine, "before_cursor_execute", observe)
    assert result.status_code == 200, result.text
    assert [r["id"] for r in result.json()] == [rid]
    assert len(scanned) >= 3 and all("limit" in statement for statement in scanned)
