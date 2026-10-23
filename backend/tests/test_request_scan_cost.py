# -*- coding: utf-8 -*-
"""Лента «по пути» и подбор рейсов: цена запроса ограничена, результат не изменился (ревью PR190, 23.10.2026).

Сверка маршрута идёт в Python по каждой паре «заявка × рейс» (`stop_search.route_fit`, ~0,3 мс).
Раньше лента перебирала ВСЕ живые заявки против ВСЕХ рейсов водителя, а подбор — все активные рейсы
страны: 600 заявок × 20 рейсов давали 2,4 с на один запрос. Теперь:
* всё, что умеет отсеять база (день по местному времени, категория, места), отсеивает она;
* сверка маршрута зависит только от линии рейса и пары городов заявки и считается один раз на запрос;
* за один запрос просматривается не больше `_SCAN_MAX` строк.
Тесты считают вызовы `route_fit` (а не секунды — время на разных машинах разное) и сверяют
результат с «ленточным» путём без отбора в базе.
"""
from datetime import datetime, timedelta

from sqlmodel import Session

from app import request_segments, stop_search
from app.db import engine
from app.models import Ride, RideCategory, RideRequest, UserRole
from app.routers import requests as requests_mod
from app.timeutil import local_date, local_day_bounds_utc, utcnow
from test_booking_segments import seed_route


def _count_route_fit(monkeypatch, line, pair=None):
    """Сколько раз сверяли маршрут рейсов ОДНОЙ ЛИНИИ (и, если задано, для пары городов заявки).

    `line` — функция по рейсу: «наша линия». База теста общая: чужие живые заявки и рейсы других
    тестов тоже проходят через ленту/подбор (а кэш сверки общий для всей линии, кто бы ни был
    водителем), поэтому считаем только свою линию — иначе число зависит от соседних тестов."""
    calls = {"n": 0}
    real = stop_search.route_fit

    def counted(ride, fv, tv, *args, **kwargs):
        if line(ride) and (pair is None or (pair[0] in (fv or []) and pair[1] in (tv or []))):
            calls["n"] += 1
        return real(ride, fv, tv, *args, **kwargs)

    monkeypatch.setattr(request_segments.stop_search, "route_fit", counted)
    return calls


def _spy_segment(monkeypatch):
    """Какие пары (рейс, заявка) дошли до Python-проверки `segment` — то, что не отсеяла база."""
    seen = []
    real = request_segments.segment

    def spy(ride, req, *args, **kwargs):
        seen.append((ride.id, req.id))
        return real(ride, req, *args, **kwargs)

    monkeypatch.setattr(request_segments, "segment", spy)
    return seen


def _noon_of_day(days_ahead: int) -> datetime:
    """Местный полдень через N суток, в наивном UTC (день не плывёт от часа запуска теста)."""
    day = local_date(utcnow() + timedelta(days=days_ahead))
    start, _end = local_day_bounds_utc(day)
    return start + timedelta(hours=12)


def _add_ride(session, driver_id, depart, *, waypoints="Ургаза | Бурибай", seats=3, **extra):
    ride = Ride(driver_id=driver_id, from_city="Сибай", to_city="Акъяр", waypoints=waypoints,
                depart_at=depart, seats_total=seats, seats_left=seats, price=700, **extra)
    session.add(ride)
    session.flush()
    return ride


def _add_request(session, passenger_id, desired_at, *, frm="Ургаза", to="Бурибай", **extra):
    req = RideRequest(passenger_id=passenger_id, from_city=frm, to_city=to, desired_at=desired_at, **extra)
    session.add(req)
    session.flush()
    return req


def _feed(client, driver, **params):
    response = client.get("/requests/feed", headers=driver["auth"], params=params)
    assert response.status_code == 200, response.text
    return response.json()


def test_on_route_feed_is_exactly_the_matched_part_of_the_full_feed(client, user_factory, monkeypatch):
    """База отсеивает день/категорию/места, но состав «по пути» тот же, что у полной ленты с совпадением."""
    driver = user_factory("Scope driver", role=UserRole.driver)
    passenger = user_factory("Scope passenger")
    day = _noon_of_day(5)
    with Session(engine) as session:
        _add_ride(session, driver["id"], day)
        mine = {
            "same_day": _add_request(session, passenger["id"], day + timedelta(hours=2)).id,
            "no_time": _add_request(session, passenger["id"], None).id,
            "other_day": _add_request(session, passenger["id"], day + timedelta(days=1)).id,
            "other_category": _add_request(session, passenger["id"], day, category=RideCategory.cargo).id,
            "too_many_seats": _add_request(session, passenger["id"], day, seats=4).id,
            "other_route": _add_request(session, passenger["id"], day, frm="Уфа", to="Казань").id,
        }
        session.commit()
    ours = set(mine.values())
    seen = _spy_segment(monkeypatch)
    on_route = [row for row in _feed(client, driver, on_route=True) if row["id"] in ours]
    checked = {req_id for _ride, req_id in seen}
    # До Python-проверки дошли заявки, которые могут лечь на рейс; остальные отсеяла база.
    assert {mine["same_day"], mine["no_time"], mine["other_route"]} <= checked
    assert not checked & {mine["other_day"], mine["other_category"], mine["too_many_seats"]}
    full = [row for row in _feed(client, driver) if row["id"] in ours]
    assert {row["id"] for row in full} == ours                                  # полная лента видит все шесть
    expected = {row["id"] for row in full if row["matched_ride_id"] is not None}
    assert {row["id"] for row in on_route} == expected == {mine["same_day"], mine["no_time"]}
    assert [row["id"] for row in on_route] == sorted(expected, reverse=True)    # порядок прежний: новые первыми


def test_on_route_feed_of_a_driver_without_rides_is_empty(client, user_factory):
    driver = user_factory("Scope idle driver", role=UserRole.driver)
    assert _feed(client, driver, on_route=True) == []


def test_route_check_runs_once_per_line_and_city_pair_in_the_feed(client, user_factory, monkeypatch):
    driver = user_factory("Memo feed driver", role=UserRole.driver)
    passenger = user_factory("Memo feed passenger")
    day = _noon_of_day(6)
    with Session(engine) as session:
        for i in range(5):                                           # пять рейсов одной линии
            _add_ride(session, driver["id"], day + timedelta(minutes=i))
        ids = [_add_request(session, passenger["id"], day).id for _ in range(30)]   # тридцать одинаковых заявок
        session.commit()
    calls = _count_route_fit(monkeypatch, lambda ride: ride.driver_id == driver["id"], ("Ургаза", "Бурибай"))
    found = [row for row in _feed(client, driver, on_route=True) if row["id"] in set(ids)]
    assert {row["id"] for row in found} == set(ids)                  # результат полный
    assert all(row["matched_pickup_stop"] == "Ургаза" and row["matched_dropoff_stop"] == "Бурибай" for row in found)
    assert calls["n"] == 1, calls                                    # а не 30 × 5


def test_different_waypoints_are_never_confused_by_the_route_cache(client, user_factory, monkeypatch):
    """Рейсы с одинаковыми откуда/куда, но разными остановками, сверяются отдельно."""
    driver = user_factory("Memo lines driver", role=UserRole.driver)
    passenger = user_factory("Memo lines passenger")
    day = _noon_of_day(6)
    with Session(engine) as session:
        with_stop = _add_ride(session, driver["id"], day, waypoints="Ургаза | Бурибай")
        without_stop = _add_ride(session, driver["id"], day + timedelta(minutes=1), waypoints="")
        req = _add_request(session, passenger["id"], day)
        session.commit()
        ids = (with_stop.id, without_stop.id, req.id)
    calls = _count_route_fit(monkeypatch, lambda ride: ride.driver_id == driver["id"], ("Ургаза", "Бурибай"))
    row = next(r for r in _feed(client, driver, on_route=True) if r["id"] == ids[2])
    assert row["matched_ride_id"] == ids[0]                          # остановки есть только у первого
    assert calls["n"] == 1                                           # первый подошёл — второй не понадобился
    # Напрямую: один и тот же memo, две линии с одинаковыми откуда/куда — ответы не склеиваются.
    memo = {}
    fields = dict(driver_id=0, from_city="Сибай", to_city="Акъяр", depart_at=day, seats_total=3,
                  seats_left=3, price=700)
    ride_with, ride_without = Ride(**fields, waypoints="Ургаза | Бурибай"), Ride(**fields, waypoints="")
    request = RideRequest(passenger_id=passenger["id"], from_city="Ургаза", to_city="Бурибай", desired_at=day)
    assert request_segments.segment(ride_with, request, None, memo) == ("Ургаза", "Бурибай")
    assert request_segments.segment(ride_without, request, None, memo) is None


def test_match_rides_hands_the_route_check_only_rides_of_the_requested_local_day(client, user_factory, monkeypatch):
    driver = user_factory("Day driver", role=UserRole.driver)
    passenger = user_factory("Day passenger")
    day = local_date(utcnow() + timedelta(days=7))
    start, end = local_day_bounds_utc(day)
    # Границы местных суток: первая и последняя минуты нужного дня — внутри, соседние — снаружи.
    inside = [start, start + timedelta(minutes=1), end - timedelta(minutes=1)]
    outside = [start - timedelta(minutes=1), end, end + timedelta(hours=3), start - timedelta(days=1)]
    with Session(engine) as session:
        ride_ids = {}
        for n, depart in enumerate(inside + outside):
            # Разные остановки — чтобы кэш линии не склеил рейсы, а счётчик считал каждый.
            ride_ids[n] = _add_ride(session, driver["id"], depart, waypoints=f"Ургаза | Бурибай | Село{n}").id
        req = _add_request(session, passenger["id"], start + timedelta(hours=12))
        session.commit()
        req_id = req.id
    calls = _count_route_fit(monkeypatch, lambda ride: (ride.waypoints or "").startswith("Ургаза | Бурибай | Село"))
    seen = _spy_segment(monkeypatch)
    response = client.get("/match/rides", headers=passenger["auth"], params={"request_id": req_id})
    assert response.status_code == 200, response.text
    inside_ids = {ride_ids[n] for n in range(len(inside))}
    outside_ids = {ride_ids[n] for n in range(len(inside), len(inside) + len(outside))}
    assert {row["id"] for row in response.json()} & (inside_ids | outside_ids) == inside_ids   # из наших — ровно рейсы этого дня
    checked = {ride_id for ride_id, _req in seen}
    assert inside_ids <= checked and not checked & outside_ids       # соседние дни до Python не дошли
    assert calls["n"] == len(inside), calls


def test_match_rides_without_a_desired_time_keeps_every_day(client, user_factory):
    driver = user_factory("Anyday driver", role=UserRole.driver)
    passenger = user_factory("Anyday passenger")
    base = _noon_of_day(8)
    with Session(engine) as session:
        rides = [_add_ride(session, driver["id"], base + timedelta(days=i)).id for i in range(3)]
        req = _add_request(session, passenger["id"], None)
        session.commit()
        req_id = req.id
    response = client.get("/match/rides", headers=passenger["auth"], params={"request_id": req_id})
    assert response.status_code == 200, response.text
    assert set(rides) <= {row["id"] for row in response.json()}


def test_match_rides_checks_one_line_once(client, user_factory, monkeypatch):
    driver = user_factory("Line driver", role=UserRole.driver)
    passenger = user_factory("Line passenger")
    day = _noon_of_day(9)
    with Session(engine) as session:
        rides = [_add_ride(session, driver["id"], day + timedelta(minutes=i)).id for i in range(40)]
        req = _add_request(session, passenger["id"], day)
        session.commit()
        req_id = req.id
    calls = _count_route_fit(monkeypatch, lambda ride: ride.waypoints == "Ургаза | Бурибай", ("Ургаза", "Бурибай"))
    response = client.get("/match/rides", headers=passenger["auth"], params={"request_id": req_id})
    assert response.status_code == 200, response.text
    assert set(rides) <= {row["id"] for row in response.json()}      # все сорок рейсов линии найдены
    assert calls["n"] == 1, calls


def test_one_request_never_scans_more_than_the_ceiling(client, user_factory, monkeypatch):
    """Потолок просмотра: заявка глубже `_SCAN_MAX` самых новых в «по пути» не попадает.

    Обычный потолок (2000) далеко за разумным числом живых заявок, поэтому тест сужает его до
    одной порции. Без потолка подходящая заявка нашлась бы — и тест краснеет."""
    driver = user_factory("Ceiling driver", role=UserRole.driver)
    passenger = user_factory("Ceiling passenger")
    day = _noon_of_day(10)
    with Session(engine) as session:
        _add_ride(session, driver["id"], day)
        old_match = _add_request(session, passenger["id"], day).id           # самая старая — подходит
        for _ in range(requests_mod.FEED_MAX + 5):                           # новее неё — посторонние
            _add_request(session, passenger["id"], day, frm="Не по пути", to="Другое село")
        session.commit()
    assert any(row["id"] == old_match for row in _feed(client, driver, on_route=True))   # обычный потолок
    monkeypatch.setattr(requests_mod, "_SCAN_MAX", requests_mod.FEED_MAX)
    assert not any(row["id"] == old_match for row in _feed(client, driver, on_route=True))
