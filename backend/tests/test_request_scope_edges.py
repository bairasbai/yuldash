# -*- coding: utf-8 -*-
"""Границы отбора «по пути» и подбора в базе (ревью PR190, второй круг, 23.10.2026).

Отбор в базе — только НЕОБХОДИМОЕ условие сверки маршрута: заявка на все свободные места, заявка
на первую и последнюю минуту местных суток и рейс на первую и последнюю минуту остаются в выдаче.
А соседние сутки и лишнее место до Python-проверки не доходят. Сдвиг границы на одно деление
(`<=` -> `<` по местам, начало суток +1 час, конец суток -1 час) должен краснеть эти тесты.
"""
from datetime import timedelta

import pytest
from sqlmodel import Session

from app.db import engine
from app.models import UserRole
from app.timeutil import local_date, local_day_bounds_utc, utcnow
from test_request_scan_cost import _add_request, _add_ride, _feed, _spy_segment

MIN = timedelta(minutes=1)


def _midnight(days_ahead: int):
    """Начало местных суток через N суток, в наивном UTC."""
    return local_day_bounds_utc(local_date(utcnow() + timedelta(days=days_ahead)))[0]


# (минута рейса, минута заявки) внутри ОДНИХ местных суток, включая обе крайние минуты.
SAME_DAY = [(1439, 0), (0, 1439), (0, 0), (1439, 1439), (600, 1), (600, 1438)]


@pytest.mark.parametrize("ride_minute,req_minute", SAME_DAY)
def test_on_route_keeps_requests_at_both_ends_of_the_local_day(client, user_factory, ride_minute, req_minute):
    driver = user_factory("Edge feed driver", role=UserRole.driver)
    passenger = user_factory("Edge feed passenger")
    midnight = _midnight(11)
    with Session(engine) as session:
        ride = _add_ride(session, driver["id"], midnight + ride_minute * MIN)
        req = _add_request(session, passenger["id"], midnight + req_minute * MIN)
        session.commit()
        ride_id, req_id = ride.id, req.id
    full = {row["id"]: row for row in _feed(client, driver)}
    assert full[req_id]["matched_ride_id"] == ride_id                 # полная лента видит совпадение
    on_route = _feed(client, driver, on_route=True)
    assert req_id in {row["id"] for row in on_route}                  # и «по пути» его не потеряла


@pytest.mark.parametrize("ride_minute,req_offset", [(0, -1), (1439, 1440), (0, -30), (1439, 1470)])
def test_on_route_never_hands_python_a_request_from_the_neighbour_day(
        client, user_factory, monkeypatch, ride_minute, req_offset):
    driver = user_factory("Edge neighbour driver", role=UserRole.driver)
    passenger = user_factory("Edge neighbour passenger")
    midnight = _midnight(12)
    with Session(engine) as session:
        _add_ride(session, driver["id"], midnight + ride_minute * MIN)
        req = _add_request(session, passenger["id"], midnight + req_offset * MIN)   # минута соседних суток
        session.commit()
        req_id = req.id
    seen = _spy_segment(monkeypatch)
    assert req_id not in {row["id"] for row in _feed(client, driver, on_route=True)}
    assert req_id not in {request_id for _ride, request_id in seen}   # отсеяла база, а не Python


@pytest.mark.parametrize("seats", [1, 2, 3])
def test_on_route_keeps_a_request_for_every_free_seat_up_to_all_of_them(client, user_factory, seats):
    driver = user_factory("Edge seats driver", role=UserRole.driver)
    passenger = user_factory("Edge seats passenger")
    noon = _midnight(13) + timedelta(hours=12)
    with Session(engine) as session:
        ride = _add_ride(session, driver["id"], noon, seats=3)            # свободно три места
        req = _add_request(session, passenger["id"], noon, seats=seats)
        session.commit()
        ride_id, req_id = ride.id, req.id
    full = {row["id"]: row for row in _feed(client, driver)}
    assert full[req_id]["matched_ride_id"] == ride_id
    assert req_id in {row["id"] for row in _feed(client, driver, on_route=True)}


def test_on_route_drops_a_request_for_one_seat_more_than_any_ride_has(client, user_factory, monkeypatch):
    driver = user_factory("Edge seats over driver", role=UserRole.driver)
    passenger = user_factory("Edge seats over passenger")
    noon = _midnight(13) + timedelta(hours=12)
    with Session(engine) as session:
        _add_ride(session, driver["id"], noon, seats=3)
        req = _add_request(session, passenger["id"], noon, seats=4)
        session.commit()
        req_id = req.id
    seen = _spy_segment(monkeypatch)
    assert req_id not in {row["id"] for row in _feed(client, driver, on_route=True)}
    assert req_id not in {request_id for _ride, request_id in seen}


def _match(client, passenger, req_id):
    response = client.get("/match/rides", headers=passenger["auth"], params={"request_id": req_id})
    assert response.status_code == 200, response.text
    return {row["id"] for row in response.json()}


@pytest.mark.parametrize("ride_minute,req_minute", SAME_DAY)
def test_match_rides_keeps_rides_at_both_ends_of_the_local_day(client, user_factory, ride_minute, req_minute):
    driver = user_factory("Edge match driver", role=UserRole.driver)
    passenger = user_factory("Edge match passenger")
    midnight = _midnight(14)
    with Session(engine) as session:
        ride = _add_ride(session, driver["id"], midnight + ride_minute * MIN)
        req = _add_request(session, passenger["id"], midnight + req_minute * MIN)
        session.commit()
        ride_id, req_id = ride.id, req.id
    assert ride_id in _match(client, passenger, req_id)


@pytest.mark.parametrize("ride_minute,req_minute", [(1440, 1439), (-1, 0)])
def test_match_rides_excludes_a_ride_one_minute_over_the_day_line(client, user_factory, ride_minute, req_minute):
    driver = user_factory("Edge match over driver", role=UserRole.driver)
    passenger = user_factory("Edge match over passenger")
    midnight = _midnight(15)
    with Session(engine) as session:
        ride = _add_ride(session, driver["id"], midnight + ride_minute * MIN)
        req = _add_request(session, passenger["id"], midnight + req_minute * MIN)
        session.commit()
        ride_id, req_id = ride.id, req.id
    assert ride_id not in _match(client, passenger, req_id)


@pytest.mark.parametrize("seats,found", [(1, True), (3, True), (4, False)])
def test_match_rides_for_all_free_seats_found_one_more_not(client, user_factory, seats, found):
    driver = user_factory("Edge match seats driver", role=UserRole.driver)
    passenger = user_factory("Edge match seats passenger")
    noon = _midnight(16) + timedelta(hours=12)
    with Session(engine) as session:
        ride = _add_ride(session, driver["id"], noon, seats=3)
        req = _add_request(session, passenger["id"], noon, seats=seats)
        session.commit()
        ride_id, req_id = ride.id, req.id
    assert (ride_id in _match(client, passenger, req_id)) is found
