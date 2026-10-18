"""Тёзки из разных районов — разные остановки и разные места посадки."""
from datetime import date, timedelta

import pytest
from sqlmodel import Session

from app.db import engine
from app.geo import _to_row, fold
from app.models import Ride, UserRole
from app.ride_stops import (
    canonical_stop, is_end, is_start, parse_stops, ride_has_stops_raw, start_point, stop_point,
)
from app.stop_search import route_fit, variants


BAY = "Абзаково (Баймакский р-н)"
BELO = "Абзаково (Белорецкий р-н)"
BAY_BA = "Абҙаҡ (Баймакский р-н)"
BELO_BA = "Абҙаҡ (Белорецкий р-н)"
UNKNOWN = "Абзаково (Несуществующий р-н)"


@pytest.fixture
def places():
    rows = [
        _to_row(1, "Абзаково", "Абҙаҡ", "Башкортостан", "village", "Баймакский р-н", 52.804, 58.362),
        _to_row(2, "Абзаково", "Абҙаҡ", "Башкортостан", "village", "Белорецкий р-н", 53.830, 58.590),
        _to_row(3, "Ургаза", "Урғаҙа", "Башкортостан", "village", "Баймакский р-н", 52.348, 58.614),
    ]
    entry = {"rows": rows, "exact": {}, "with_district": {}, "namesakes": {}}
    for row in rows:
        for key in {row.key_ru, row.key_ba} - {""}:
            entry["exact"].setdefault(key, row)
            entry["namesakes"].setdefault(key, []).append(row)
            entry["with_district"][(key, fold(row.district))] = row
    return entry


def _route(**extra):
    return {"from_city": "Сибай", "to_city": "Акъяр", "waypoints": f"{BAY} | {BELO}", **extra}


def test_two_districts_survive_but_same_place_in_two_languages_is_one_stop(places):
    assert parse_stops(_route(), places) == [BAY, BELO]
    assert parse_stops(_route(waypoints=f"{BELO} | {BELO_BA}"), places) == [BELO]


@pytest.mark.parametrize("endpoint", ["from_city", "to_city"])
def test_foreign_district_is_not_an_endpoint_and_does_not_remove_a_stop(places, endpoint):
    ride = _route(**{endpoint: BAY}, waypoints=BELO)
    assert ride_has_stops_raw(ride, places)
    assert parse_stops(ride, places) == [BELO]
    assert not is_start(ride, BELO, places)
    assert not is_end(ride, BELO, places)
    same = is_start if endpoint == "from_city" else is_end
    assert same(ride, BAY_BA, places)


def test_canonical_stop_keeps_the_district_and_refuses_ambiguous_bare_name(places):
    ride = _route()
    assert canonical_stop(ride, BAY_BA, places) == BAY
    assert canonical_stop(ride, BELO_BA, places) == BELO
    assert canonical_stop(_route(waypoints=BAY), BELO, places) is None
    assert canonical_stop(ride, "Абзаково", places) is None
    assert canonical_stop(ride, UNKNOWN, places) is None


def test_unknown_explicit_district_never_falls_back_to_another_village(places):
    ride = _route(from_lat=52.591, from_lng=58.317, to_lat=52.716, to_lng=58.664)
    assert stop_point(ride, UNKNOWN, places) is None
    unknown_unique = "Ургаза (Несуществующий р-н)"
    assert stop_point(_route(), unknown_unique, places) is None
    assert start_point(_route(from_city=unknown_unique), places) is None


def test_bare_stop_still_uses_the_corridor_and_stays_unknown_without_it(places):
    ride = _route(from_lat=52.591, from_lng=58.317, to_lat=52.716, to_lng=58.664)
    point = stop_point(ride, "Абзаково", places)
    assert point is not None and point.lat == 52.804
    assert stop_point(_route(), "Абзаково", places) is None
    assert stop_point(_route(), BELO_BA, places).lat == 53.830


def test_old_invalid_waypoint_still_keeps_safety_rules(places):
    ride = _route(from_city=BAY, waypoints="Абзаково (ул. Ленина 5)")
    assert ride_has_stops_raw(ride, places)
    assert parse_stops(ride, places) == []


def test_search_retains_explicit_district_but_bare_search_is_broad(places):
    asked = variants(None, BELO_BA, places)
    assert BELO in asked and BELO_BA in asked
    assert all("(Белорецкий р-н)" in name for name in asked)
    assert not route_fit(_route(waypoints=BAY), asked, None, places).ok
    assert route_fit(_route(waypoints=BELO), asked, None, places).matched_stop == BELO
    assert route_fit(_route(waypoints=BAY), variants(None, "Абзаково", places), None, places).ok


def test_endpoint_search_cannot_match_a_foreign_district(places):
    asked = variants(None, BELO, places)
    assert not route_fit(_route(from_city=BAY, waypoints=""), asked, None, places).ok
    assert not route_fit(_route(to_city=BAY, waypoints=""), None, asked, places).ok
    assert route_fit(_route(from_city=BELO_BA, waypoints=""), asked, None, places).matched_from_start


_day_counter = 0


def _publish(client, user_factory, *, stops=BAY, frm="Сибай"):
    global _day_counter
    _day_counter += 1
    day = date(2033, 5, 1) + timedelta(days=_day_counter)
    driver = user_factory("District Driver", role=UserRole.driver)
    response = client.post("/rides", headers=driver["auth"], json={
        "from_city": frm, "to_city": "Акъяр", "depart_at": f"{day}T12:00:00",
        "seats_total": 3, "price": 700, "waypoints": stops,
        "comment": f"district-{day}",
    })
    assert response.status_code == 200, response.text
    return day, response.json()


@pytest.mark.parametrize("asked", [BELO, BELO_BA])
def test_api_booking_selects_the_named_district(client, user_factory, asked):
    _, ride = _publish(client, user_factory, stops=f"{BAY} | {BELO}")
    passenger = user_factory("District Passenger")
    response = client.post("/bookings", headers=passenger["auth"], json={
        "ride_id": ride["id"], "seats": 1, "pickup_stop": asked,
    })
    assert response.status_code == 200, response.text
    assert response.json()["pickup_stop"] == BELO


@pytest.mark.parametrize("asked", [BELO, UNKNOWN])
def test_api_booking_refuses_a_foreign_district_without_taking_a_seat(client, user_factory, asked):
    _, ride = _publish(client, user_factory)
    passenger = user_factory("District Refusal")
    response = client.post("/bookings", headers=passenger["auth"], json={
        "ride_id": ride["id"], "seats": 1, "pickup_stop": asked,
    })
    assert response.status_code == 400, response.text
    assert response.json()["detail"]["code"] == "pickup_stop_unknown"
    with Session(engine) as session:
        assert session.get(Ride, ride["id"]).seats_left == 3


def test_api_foreign_district_is_a_stop_not_the_start(client, user_factory):
    _, ride = _publish(client, user_factory, frm=BAY, stops=BELO)
    passenger = user_factory("District Start")
    response = client.post("/bookings", headers=passenger["auth"], json={
        "ride_id": ride["id"], "seats": 1, "pickup_stop": BELO,
    })
    assert response.status_code == 200, response.text
    assert response.json()["pickup_stop"] == BELO


@pytest.mark.parametrize("path", ["/rides", "/rides/near"])
def test_api_search_never_substitutes_a_foreign_district(client, user_factory, path):
    day, ride = _publish(client, user_factory)
    for asked in (BELO, BELO_BA, UNKNOWN):
        response = client.get(path, params={"date": day.isoformat(), "from_city": asked})
        assert response.status_code == 200, response.text
        body = response.json()
        items = body["items"] if isinstance(body, dict) else body
        assert ride["id"] not in {item["id"] for item in items}, asked


@pytest.mark.parametrize("path", ["/rides", "/rides/near"])
def test_api_search_finds_the_district_in_both_languages(client, user_factory, path):
    day, ride = _publish(client, user_factory, stops=BELO_BA)
    for asked in (BELO, BELO_BA, "Абзаково"):
        response = client.get(path, params={"date": day.isoformat(), "from_city": asked})
        assert response.status_code == 200, response.text
        body = response.json()
        items = body["items"] if isinstance(body, dict) else body
        selected = next((item for item in items if item["id"] == ride["id"]), None)
        assert selected is not None, asked
        assert selected["matched_stop"] == BELO_BA
        assert "pickup_stop" not in selected


@pytest.mark.parametrize("path", ["/rides", "/rides/near"])
def test_api_explicit_district_finds_a_unique_village_written_without_district(client, user_factory, path):
    """Широкий SQL не теряет поездку: точная проверка узнаёт единственное село из справочника."""
    day, ride = _publish(client, user_factory, stops="Ургаза")
    response = client.get(path, params={
        "date": day.isoformat(), "from_city": "Урғаҙа (Баймакский р-н)",
    })
    assert response.status_code == 200, response.text
    body = response.json()
    items = body["items"] if isinstance(body, dict) else body
    selected = next((item for item in items if item["id"] == ride["id"]), None)
    assert selected is not None
    assert selected["matched_stop"] == "Ургаза"
