"""Поиск поездок по остановкам (решение Александра 18.10.2026, PR2 серии «место посадки»).

Зарина из Ургазы ищет «Ургаза → Акъяр». Поездка «Сибай → Акъяр через Ургазу» раньше ей не
находилась — лента сравнивала только начало и конец. Теперь находится, и карточка говорит, где она
сядет: `matched_stop = "Ургаза"`. Поездка в обратную сторону («Бурибай → Ургаза» на маршруте, где
Бурибай после Ургазы) — не находится. Остановка «ул. Ургаза 5» старой поездки подстрокой совпадает,
но остановкой не считается. «%» в запросе — буква, а не «всё подряд».

Тесты ищут свою поездку в ленте, где у всех общая база и до 200 ближайших строк, — поэтому у
каждого свой день выезда (`date`) далеко в будущем (урок PR184).
"""
from __future__ import annotations

import logging
from datetime import date, datetime, timedelta

import pytest
from sqlmodel import Session

from app.db import engine
from app.geo import snapshot_cached
from app.models import Block, Ride, UserRole
from app.routers import rides as rides_mod
from app.stop_search import route_fit

STOPS = "Ургаза | Бурибай"
URGAZA = (52.34768, 58.61438)
_DAY0 = date(2031, 3, 1)
_day_counter = {"n": 0}


def _day() -> date:
    """Свой день выезда для каждого теста (местная дата)."""
    _day_counter["n"] += 1
    return _DAY0 + timedelta(days=_day_counter["n"] * 3)


def _publish(client, drv, day: date, *, frm="Сибай", to="Акъяр", waypoints=STOPS, tag="", seats=3, hour=10,
             **extra):
    depart = datetime(day.year, day.month, day.day, hour, 0).isoformat()   # местное время (старый формат)
    r = client.post("/rides", headers=drv["auth"], json={
        "from_city": frm, "to_city": to, "depart_at": depart, "seats_total": seats, "price": 600,
        "waypoints": waypoints, "comment": f"stops-{tag}-{day}", **extra})
    assert r.status_code == 200, r.text
    return r.json()


def _feed(client, day, **params):
    r = client.get("/rides", params={"date": day.isoformat(), "limit": 200, **params})
    assert r.status_code == 200, r.text
    return {x["id"]: x for x in r.json()}


def _near(client, day, **params):
    r = client.get("/rides/near", params={"date": day.isoformat(), "limit": 200, **params})
    assert r.status_code == 200, r.text
    return r.json()


@pytest.fixture
def trip(client, user_factory):
    day = _day()
    drv = user_factory("SBS Driver", role=UserRole.driver)
    return day, drv, _publish(client, drv, day, tag="main")


# ── Лента: какие запросы находят поездку «Сибай → Ургаза → Бурибай → Акъяр» ──

@pytest.mark.parametrize("frm,to,stop,from_start", [
    ("Сибай", "Акъяр", None, True),          # начало → конец (как раньше)
    ("Ургаза", "Акъяр", "Ургаза", False),     # остановка → конец
    ("Сибай", "Бурибай", None, True),         # начало → остановка
    ("Ургаза", "Бурибай", "Ургаза", False),   # остановка i → j
    ("Урғаҙа", "Аҡъяр", "Ургаза", False),     # башкирское написание
    ("д. Ургаза", "Акъяр", "Ургаза", False),  # приставка «д.»
])
def test_feed_finds_the_ride_through_stops(client, trip, frm, to, stop, from_start):
    day, _drv, ride = trip
    got = _feed(client, day, from_city=frm, to_city=to)
    assert ride["id"] in got, (frm, to)
    assert got[ride["id"]]["matched_stop"] == stop
    assert got[ride["id"]]["matched_from_start"] is from_start


@pytest.mark.parametrize("frm,to", [
    ("Бурибай", "Ургаза"),     # против хода маршрута
    ("Акъяр", None),           # «Откуда» — конец маршрута
    (None, "Сибай"),           # «Куда» — начало маршрута
    ("Ургаза", "Ургаза"),      # одна и та же остановка
    ("Темясово", "Акъяр"),     # такой остановки нет
])
def test_feed_does_not_find_wrong_directions(client, trip, frm, to):
    day, _drv, ride = trip
    params = {k: v for k, v in (("from_city", frm), ("to_city", to)) if v}
    assert ride["id"] not in _feed(client, day, **params), params


def test_only_from_or_only_to(client, trip):
    day, _drv, ride = trip
    got = _feed(client, day, from_city="Бурибай")
    assert got[ride["id"]]["matched_stop"] == "Бурибай"
    to_only = _feed(client, day, to_city="Ургаза")
    assert ride["id"] in to_only
    assert to_only[ride["id"]]["matched_stop"] is None and to_only[ride["id"]]["matched_from_start"] is False


def test_invalid_stop_text_of_an_old_ride_never_matches(client, user_factory):
    """Старая поездка с «Ургаза (ул. Ленина 5)» в остановках: без района это «Ургаза», но часть
    недопустима (цифры) — остановкой не считается и поиском не находится."""
    day = _day()
    drv = user_factory("SBS Old Driver", role=UserRole.driver)
    ride = _publish(client, drv, day, waypoints="", tag="old")
    with Session(engine) as s:
        r = s.get(Ride, ride["id"])
        r.waypoints = "Ургаза (ул. Ленина 5) | Бурибай"
        s.add(r)
        s.commit()
    assert ride["id"] not in _feed(client, day, from_city="Ургаза", to_city="Акъяр")
    near = _near(client, day, from_city="Ургаза", to_city="Акъяр")
    assert all(x["id"] != ride["id"] for x in near["items"]) and near["count"] == 0
    assert ride["id"] in _feed(client, day, from_city="Бурибай", to_city="Акъяр"), "допустимая часть работает"


def test_query_with_digits_is_not_searched_in_stops(client, user_factory):
    """«Ургаза (2)» без района — «Ургаза», но в запросе цифры: по остановкам такой запрос не ищем (P3)."""
    day = _day()
    drv = user_factory("SBS Digits Driver", role=UserRole.driver)
    ride = _publish(client, drv, day, waypoints="Ургаза", tag="digits")
    assert ride["id"] not in _feed(client, day, from_city="Ургаза (2)", to_city="Акъяр")
    near = _near(client, day, from_city="Ургаза (2)", to_city="Акъяр")
    assert all(x["id"] != ride["id"] for x in near["items"])
    assert ride["id"] in _feed(client, day, from_city="Ургаза", to_city="Акъяр")


def test_like_wildcards_in_the_query_are_letters(client, trip):
    """«%» и «_» — не шаблон: запрос «%» не находит всё подряд (autoescape)."""
    day, _drv, ride = trip
    assert ride["id"] not in _feed(client, day, from_city="%")
    assert ride["id"] not in _feed(client, day, from_city="Ург_за")
    assert ride["id"] not in _feed(client, day, from_city="%", to_city="%")
    near = _near(client, day, from_city="%")
    assert near["count"] == 0


def test_like_wildcards_do_not_match_in_sql_itself(client, trip):
    """Вторая стена после `route_fit`: само SQL-условие с «%» не совпадает с каждой строкой
    (поездка `trip` в базе есть — иначе пустая таблица прошла бы и без экранирования)."""
    from sqlmodel import select

    from app.stop_search import column_match
    with Session(engine) as s:
        assert s.exec(select(Ride.id).where(column_match(Ride.from_city, ["%"]))).first() is None
        assert s.exec(select(Ride.id).where(column_match(Ride.waypoints, ["_"]))).first() is None


def test_price_hint_does_not_treat_percent_as_everything(client, trip):
    """`_city_match` (подсказка цены, советы водителю) — тоже с autoescape: «%» не берёт все поездки
    (поездка `trip` с ценой в базе есть)."""
    r = client.get("/rides/price_hint", params={"from_city": "%"})
    assert r.status_code == 200 and r.json()["count"] == 0, r.json()


def test_too_long_name_is_refused(client):
    assert client.get("/rides", params={"from_city": "а" * 121}).status_code == 422
    assert client.get("/rides/near", params={"to_city": "а" * 121}).status_code == 422


def test_unfiltered_feed_has_no_match_marks(client, trip):
    day, _drv, ride = trip
    got = _feed(client, day)
    assert got[ride["id"]]["matched_stop"] is None and got[ride["id"]]["matched_from_start"] is False


def test_pagination_counts_only_real_matches(client, user_factory):
    """Подстрочные совпадения, которые `route_fit` отбросил, не съедают место на странице."""
    day = _day()
    drv = user_factory("SBS Page Driver", role=UserRole.driver)
    good = [_publish(client, drv, day, tag=f"good{i}")["id"] for i in range(3)]
    # Раньше всех по времени — поездка, которую SQL возьмёт («Ургаза…Бурибай» подстрокой), а точная
    # проверка выбросит (часть с цифрами — не остановка). Она не должна занять первую страницу.
    early_bad = _publish(client, drv, day, waypoints="", tag="earlybad", hour=8)["id"]
    with Session(engine) as s:
        r = s.get(Ride, early_bad)
        r.waypoints = "Ургаза (ул. Ленина 5) | Бурибай"
        s.add(r)
        s.commit()
    first = client.get("/rides", params={"date": day.isoformat(), "from_city": "Ургаза", "to_city": "Бурибай",
                                         "limit": 1}).json()
    assert len(first) == 1 and first[0]["id"] in good, first
    bad = _publish(client, drv, day, waypoints="", tag="bad")["id"]
    with Session(engine) as s:
        r = s.get(Ride, bad)
        r.waypoints = "Бурибай | Ургаза"          # обратный порядок: подстрокой «Ургаза…Бурибай» не совпадёт,
        s.add(r)                                   # а «Ургаза» есть — широкое условие его возьмёт
        s.commit()
    page1 = client.get("/rides", params={"date": day.isoformat(), "from_city": "Бурибай", "to_city": "Ургаза",
                                         "limit": 1}).json()
    assert [x["id"] for x in page1] == [bad]
    allp = client.get("/rides", params={"date": day.isoformat(), "from_city": "Ургаза", "to_city": "Бурибай",
                                        "limit": 2, "offset": 0}).json()
    assert len(allp) == 2 and all(x["id"] in good for x in allp)


# ── «Ближайшие» ──

def test_near_finds_through_a_stop_and_counts_after_route_fit(client, trip, user_factory):
    day, drv, ride = trip
    wrong = _publish(client, drv, day, waypoints="Бурибай | Ургаза", tag="wrongdir")   # Ургаза после Бурибая
    near = _near(client, day, from_city="Ургаза", to_city="Бурибай")
    ids = [x["id"] for x in near["items"]]
    assert ride["id"] in ids and wrong["id"] not in ids
    assert near["count"] == len(ids), "count — после точной проверки маршрута"
    item = next(x for x in near["items"] if x["id"] == ride["id"])
    assert item["matched_stop"] == "Ургаза" and item["matched_from_start"] is False


def test_near_distance_to_the_nearer_stop(client, trip):
    """Человек в Ургазе: расстояние — до остановки (≈ 0), а не до Сибая (41 км); карточка зовёт её."""
    day, _drv, ride = trip
    near = _near(client, day, lat=URGAZA[0], lng=URGAZA[1])
    item = next(x for x in near["items"] if x["id"] == ride["id"])
    assert item["distance_km"] is not None and item["distance_km"] < 1.0
    assert item["matched_stop"] == "Ургаза" and item["matched_from_start"] is False


def test_near_distance_never_from_the_pin(client, user_factory):
    """Пин точки сбора (часто дом водителя) не участвует в расстоянии: только начало маршрута (P2-4)."""
    day = _day()
    drv = user_factory("SBS Pin Driver", role=UserRole.driver)
    ride = _publish(client, drv, day, waypoints="", tag="pin", pickup_lat=URGAZA[0], pickup_lng=URGAZA[1])
    near = _near(client, day, lat=URGAZA[0], lng=URGAZA[1])
    item = next(x for x in near["items"] if x["id"] == ride["id"])
    assert item["distance_km"] > 30, f"расстояние посчитано от пина: {item['distance_km']}"
    assert item["matched_from_start"] is True and item["matched_stop"] is None


def test_near_radius_keeps_rides_whose_stop_is_close(client, trip):
    day, _drv, ride = trip
    near = _near(client, day, lat=URGAZA[0], lng=URGAZA[1], radius_km=5)
    assert any(x["id"] == ride["id"] for x in near["items"]), "остановка в радиусе — поездка видна"


def test_near_hidden_unverified_is_counted_after_route_fit(client, user_factory):
    day = _day()
    drv = user_factory("SBS Unver Driver", role=UserRole.driver)
    with Session(engine) as s:
        from app.models import User
        u = s.get(User, drv["id"])
        u.verified = False
        s.add(u)
        s.commit()
    _publish(client, drv, day, tag="unver-good")
    bad = _publish(client, drv, day, waypoints="Бурибай | Ургаза", tag="unver-bad")
    near = _near(client, day, from_city="Ургаза", to_city="Бурибай", verified_only=True)
    assert near["count"] == 0 and near["hidden_unverified"] == 1, near
    assert bad  # обратный маршрут спрятанным не считается


def test_near_multi_city_filter_with_a_stop(client, trip):
    day, _drv, ride = trip
    near = _near(client, day, from_cities="Темясово,Ургаза", to_cities="Акъяр")
    item = next(x for x in near["items"] if x["id"] == ride["id"])
    assert item["matched_stop"] == "Ургаза"


def test_near_both_filters_must_fit(client, trip):
    """Место из поиска и места из шторок вместе — поездка должна подойти обоим (как раньше)."""
    day, _drv, ride = trip
    near = _near(client, day, from_city="Ургаза", from_cities="Темясово")
    assert all(x["id"] != ride["id"] for x in near["items"])
    # SQL пропустит (обе подстроки есть), но шторки просят обратный ход «Бурибай → Ургаза».
    near = _near(client, day, from_city="Ургаза", from_cities="Бурибай", to_cities="Ургаза")
    assert all(x["id"] != ride["id"] for x in near["items"])


@pytest.mark.parametrize("frm,to", [("Бурибай", "Ургаза"), ("Ургаза", "Ургаза")])
def test_near_does_not_find_wrong_directions(client, trip, frm, to):
    """В «Ближайших» SQL широкий (без попарных LIKE) — порядок держит только `route_fit`."""
    day, _drv, ride = trip
    near = _near(client, day, from_city=frm, to_city=to)
    assert all(x["id"] != ride["id"] for x in near["items"]), (frm, to)


def test_from_the_end_of_the_route_is_not_found_even_if_it_is_written_as_a_stop(client, user_factory):
    """Водитель вписал конец маршрута ещё и в остановки («Ургаза | Акъяр»): «из Акъяра» поездка не
    находится — «Откуда» не бывает концом маршрута."""
    day = _day()
    drv = user_factory("SBS End Driver", role=UserRole.driver)
    ride = _publish(client, drv, day, waypoints="Ургаза | Акъяр", tag="endstop")
    near = _near(client, day, from_city="Акъяр")
    assert all(x["id"] != ride["id"] for x in near["items"])
    assert ride["id"] not in _feed(client, day, from_city="Акъяр")


def test_postgis_error_is_logged_without_the_query(client, trip, monkeypatch, caplog):
    """Ошибка префильтра PostGIS — в лог только её вид: в тексте ошибки SQL с координатами человека."""
    day, _drv, _ride = trip
    monkeypatch.setattr(rides_mod, "_postgis_dialect", lambda session: True)   # на SQLite ST_* нет
    with caplog.at_level(logging.WARNING, logger="yuldash"):
        near = _near(client, day, lat=URGAZA[0], lng=URGAZA[1], radius_km=5)
    assert near["count"] >= 1, "Python-фолбэк отработал"
    lines = [r.getMessage() for r in caplog.records if "PostGIS prefilter skipped" in r.getMessage()]
    assert lines, "предупреждение не записано"
    assert all("ST_" not in ln and "52.34" not in ln and "58.61" not in ln for ln in lines), lines


# ── /rides/cities: остановки — в чипах, только узнанные и только видимые ──

def test_cities_show_known_stops_of_visible_rides_only(client, user_factory, monkeypatch):
    """Чипы — топ-12 по всей базе; чтобы не зависеть от чужих поездок общей базы тестов, оставляем
    в выборке только свою поездку (после всех фильтров видимости — они работают как есть)."""
    day = _day()
    drv = user_factory("SBS Chips Driver", role=UserRole.driver)
    ride = _publish(client, drv, day, waypoints="Ишмурзино | Поворот на Юлук", tag="chips", frm="Баймак", to="Акъяр")
    real_hide = rides_mod.hide_trusted_only
    monkeypatch.setattr(rides_mod, "FEED_MAX", 1_000_000)
    monkeypatch.setattr(rides_mod, "hide_trusted_only",
                        lambda rows, user, session: [r for r in real_hide(rows, user, session) if r.id == ride["id"]])
    chips = client.get("/rides/cities").json()
    names = {c["ru"] for c in chips["from"]} | {c["ru"] for c in chips["to"]}
    assert "Ишмурзино" in {c["ru"] for c in chips["from"]} and "Ишмурзино" in {c["ru"] for c in chips["to"]}
    assert {"ru": "Ишмурзино", "ba": "Ишмырҙа"} in chips["from"], "двуязычно, как города"
    assert "Поворот на Юлук" not in names, "свободный текст справочник не знает — в чипы не идёт"
    blocker = user_factory("SBS Chips Blocker")
    with Session(engine) as s:
        s.add(Block(user_id=blocker["id"], blocked_user_id=drv["id"]))
        s.commit()
    hidden = client.get("/rides/cities", headers=blocker["auth"]).json()
    assert "Ишмурзино" not in {c["ru"] for c in hidden["from"]} | {c["ru"] for c in hidden["to"]}


# ── route_fit без сервера ──

def _r(**kw):
    base = {"driver_id": 1, "from_city": "Сибай", "to_city": "Акъяр", "depart_at": datetime(2031, 1, 1),
            "waypoints": STOPS}
    return Ride(**{**base, **kw})


def test_route_fit_positions(client):
    entry = snapshot_cached()
    r = _r()
    f = route_fit(r, ["Ургаза"], ["Акъяр"], entry)
    assert f.ok and f.matched_stop == "Ургаза" and f.pickups == (0,)
    assert route_fit(r, ["Сибай"], ["Ургаза"], entry).matched_from_start is True
    assert not route_fit(r, ["Бурибай"], ["Ургаза"], entry).ok
    assert not route_fit(r, ["Акъяр"], None, entry).ok
    assert not route_fit(r, None, ["Сибай"], entry).ok
    every = route_fit(r, None, None, entry)
    assert every.ok and every.pickups == (-1, 0, 1) and every.matched_stop is None
    assert not route_fit(r, ["Ургаза"], ["Акъяр"], entry, from_stops=False).ok, "без поиска по остановкам"
