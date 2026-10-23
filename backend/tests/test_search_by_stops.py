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
import sqlmodel
from sqlmodel import Session, select

from app.db import engine
from app.geo import snapshot_cached
from app.models import Block, Ride, UserRole
from app.routers import rides as rides_mod
from app.services import haversine_km
from app.stop_search import column_match, pair_match, request_snapshot, route_fit

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


@pytest.mark.parametrize("query", ["Ургаза (⑤)", "Ургаза (٥)", "Ургаза (⁵)", "Ургаза (Ⅴ)"])
def test_query_with_unicode_digits_is_not_searched_in_stops(client, user_factory, query):
    """Как в публикации остановки (S-P3-1 в PR1): обведённые, арабские, надстрочные и римские цифры —
    тоже цифры. Для регулярки `\\d` они «буквы», и «Ургаза (⑤)» искалась бы как остановка «Ургаза»."""
    day = _day()
    drv = user_factory("SBS UDigits Driver", role=UserRole.driver)
    ride = _publish(client, drv, day, waypoints="Ургаза", tag="udigits")
    assert ride["id"] not in _feed(client, day, from_city=query, to_city="Акъяр"), query
    assert ride["id"] not in _feed(client, day, from_city="Сибай", to_city=query), query
    near = _near(client, day, from_city=query, to_city="Акъяр")
    assert all(x["id"] != ride["id"] for x in near["items"]), query
    near = _near(client, day, from_cities=query, to_cities="Акъяр")
    assert all(x["id"] != ride["id"] for x in near["items"]), query
    assert ride["id"] in _feed(client, day, from_city="Ургаза", to_city="Акъяр"), "контроль: без цифр находится"


def test_like_wildcards_in_the_query_are_letters(client, trip):
    """«%» и «_» — не шаблон: запрос «%» не находит всё подряд (autoescape)."""
    day, _drv, ride = trip
    assert ride["id"] not in _feed(client, day, from_city="%")
    assert ride["id"] not in _feed(client, day, from_city="Ург_за")
    assert ride["id"] not in _feed(client, day, from_city="%", to_city="%")
    near = _near(client, day, from_city="%")
    assert near["count"] == 0


def test_like_wildcards_do_not_match_in_sql_itself(client, trip):
    """Вторая стена после `route_fit`: само SQL-условие с «%» не совпадает с каждой строкой. Смотрим
    СВОЮ поездку (`trip`): она в базе есть — иначе пустая таблица прошла бы и без экранирования, — а
    чужие строки общей базы (у кого-то в остановках бывает «_») тест не задевают."""
    _day_, _drv, ride = trip
    with Session(engine) as s:
        mine = Ride.id == ride["id"]
        assert s.exec(select(Ride.id).where(mine, column_match(Ride.from_city, ["%"]))).first() is None
        assert s.exec(select(Ride.id).where(mine, column_match(Ride.waypoints, ["_"]))).first() is None


def test_pair_match_escapes_wildcards_in_sql(client, trip):
    """Попарный LIKE общей ленты («откуда…куда» в остановках): «_» и «%» в имени — буквы, порядок держится."""
    _day_, _drv, ride = trip
    with Session(engine) as s:
        def hit(froms, tos):
            return s.exec(select(Ride.id).where(Ride.id == ride["id"], pair_match(Ride.waypoints, froms, tos))).first()
        assert hit(["Ург_за"], ["Бур%ай"]) is None, "«_» и «%» сработали как шаблон"
        assert hit(["Ург_за"], ["Бурибай"]) is None and hit(["Ургаза"], ["Бур%ай"]) is None
        assert hit(["Ургаза"], ["Бурибай"]) == ride["id"], "контроль: обычная пара совпадает"
        assert hit(["Бурибай"], ["Ургаза"]) is None, "попарный LIKE держит порядок"


def test_price_hint_does_not_treat_percent_as_everything(client, trip):
    """`_city_match` (подсказка цены, советы водителю) — тоже с autoescape: «%» не берёт все поездки.
    Своя поездка `trip` с ценой в базе есть; чужие строки общей базы — только в проверке ответа."""
    _day_, _drv, ride = trip
    with Session(engine) as s:
        cond = rides_mod._city_match(Ride.from_city, s, "%")
        assert s.exec(select(Ride.id).where(Ride.id == ride["id"], cond)).first() is None
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


def test_feed_next_pages_are_the_same_as_slices_of_the_full_list(client, user_factory):
    """Лента читает выдачу по порядку и останавливается, набрав `offset + limit` совпадений (P2-2):
    «Показать ещё» отдаёт ровно то же, что срез полного списка, — без пропусков и повторов, и мимо
    подстрочных совпадений, которые точная проверка отбросила."""
    day = _day()
    drv = user_factory("SBS Pages Driver", role=UserRole.driver)
    ids = []
    for i in range(5):
        ids.append(_publish(client, drv, day, tag=f"pg{i}", hour=8 + i)["id"])
        if i == 1:   # подстрока «Ургаза…Бурибай» есть, а остановкой не считается — в страницы не попадёт
            junk = _publish(client, drv, day, waypoints="", tag="pgjunk", hour=8)["id"]
            with Session(engine) as s:
                r = s.get(Ride, junk)
                r.waypoints = "Ургаза (ул. Ленина 5) | Бурибай"
                s.add(r)
                s.commit()
    params = {"date": day.isoformat(), "from_city": "Ургаза", "to_city": "Бурибай"}
    full = [x["id"] for x in client.get("/rides", params={**params, "limit": 200}).json()]
    assert full == ids, (full, ids)
    pages = []
    for offset in (0, 2, 4, 6):
        page = [x["id"] for x in client.get("/rides", params={**params, "limit": 2, "offset": offset}).json()]
        assert page == full[offset:offset + 2], (offset, page)
        pages += page
    assert pages == full


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


def _picked_point(item, ride_id):
    """Где на самом деле то место, которое карточка предлагает как посадку (начало маршрута — из базы)."""
    if item["matched_stop"] == "Ургаза":
        return URGAZA
    assert item["matched_from_start"] is True and item["matched_stop"] is None, item
    with Session(engine) as s:
        ride = s.get(Ride, ride_id)
        return ride.from_lat, ride.from_lng


def test_near_multi_from_distance_matches_the_preselected_pickup(client, user_factory):
    """Человек в Ургазе выбрал в шторке «Откуда: Сибай, Ургаза». Расстояние ≈ 0 — до Ургазы, поэтому и
    карточка зовёт садиться в Ургазе, а не в начале маршрута (Сибай, 41 км): иначе клиент предвыберет
    начало, и пассажирка окажется «не пришедшей» по правилам начала (P2-1, приёмка PR #188)."""
    day = _day()
    drv = user_factory("SBS Multi Driver", role=UserRole.driver)
    ride = _publish(client, drv, day, tag="multi")
    near = _near(client, day, from_cities="Сибай,Ургаза", lat=URGAZA[0], lng=URGAZA[1])
    item = next(x for x in near["items"] if x["id"] == ride["id"])
    assert item["distance_km"] is not None and item["distance_km"] < 1.0, item
    assert item["matched_stop"] == "Ургаза" and item["matched_from_start"] is False, item


@pytest.mark.parametrize("where", [
    URGAZA,                     # у остановки
    (52.7160, 58.6640),         # в начале маршрута (Сибай)
    (54.735, 55.958),           # в стороне от всего (Уфа)
])
@pytest.mark.parametrize("query", [{"from_cities": "Сибай,Ургаза"}, {"from_city": "Сибай,Ургаза"}])
def test_near_distance_is_always_to_the_place_the_card_preselects(client, user_factory, where, query):
    """Что бы ни выбрала «ближайшая», расстояние на карточке — ровно до того места, которое она
    предлагает как посадку (а не до одного места и посадка в другом)."""
    day = _day()
    drv = user_factory("SBS Same Driver", role=UserRole.driver)
    ride = _publish(client, drv, day, tag="same")
    near = _near(client, day, lat=where[0], lng=where[1], **query)
    item = next(x for x in near["items"] if x["id"] == ride["id"])
    point = _picked_point(item, ride["id"])
    assert item["distance_km"] == round(haversine_km(where[0], where[1], point[0], point[1]), 1), item


def test_near_several_pickups_without_coordinates_preselect_nothing(client, user_factory):
    """Нет точки человека — расстояния нет, а подходящих мест посадки два (начало и Ургаза): выбирать
    за него нечем — ни остановки, ни «начала»."""
    day = _day()
    drv = user_factory("SBS NoCoord Driver", role=UserRole.driver)
    ride = _publish(client, drv, day, tag="nocoord")
    near = _near(client, day, from_cities="Сибай,Ургаза")
    item = next(x for x in near["items"] if x["id"] == ride["id"])
    assert item["distance_km"] is None
    assert item["matched_stop"] is None and item["matched_from_start"] is False, item


def test_feed_and_near_agree_when_several_pickups(client, user_factory):
    """Один договор предвыбора для ленты и «Ближайших» (приёмка PR #188, P3-2). Начало «Сибай автовокзал»
    совпадает с запросом «Сибай» подстрокой, остановка «Сибай» — по ключу: два варианта посадки, координат
    человека нет. «Ближайшие» ничего не выбирают — и лента тоже (раньше она брала начало)."""
    day = _day()
    drv = user_factory("SBS Both Driver", role=UserRole.driver)
    ride = _publish(client, drv, day, frm="Сибай автовокзал", waypoints="Сибай | Ургаза", tag="both")
    near = next(x for x in _near(client, day, from_city="Сибай", to_city="Акъяр")["items"] if x["id"] == ride["id"])
    feed = _feed(client, day, from_city="Сибай", to_city="Акъяр")[ride["id"]]
    assert (near["matched_stop"], near["matched_from_start"]) == (None, False), near
    assert (feed["matched_stop"], feed["matched_from_start"]) == (None, False), feed


def test_unknown_stop_and_start_with_coordinates_preselect_nothing(client, user_factory):
    """«Откуда: Сибай, Поворот на Юлук»: остановку справочник не знает — координат у неё нет. Человек стоит
    в стороне от Сибая. Расстояние честное — до Сибая, единственного известного места; но предвыбирать
    начало нельзя: неизвестная остановка могла быть ближе (приёмка PR #188, P3-3)."""
    day = _day()
    drv = user_factory("SBS Unknown Driver", role=UserRole.driver)
    ride = _publish(client, drv, day, waypoints="Поворот на Юлук | Ургаза", tag="unknown")
    where = (52.55, 58.40)
    item = next(x for x in _near(client, day, from_cities="Сибай,Поворот на Юлук", lat=where[0], lng=where[1])["items"]
                if x["id"] == ride["id"])
    with Session(engine) as s:
        start = s.get(Ride, ride["id"])
        expected = round(haversine_km(where[0], where[1], start.from_lat, start.from_lng), 1)
    assert item["distance_km"] == expected, "расстояние — честное, до единственного известного места"
    assert item["matched_stop"] is None and item["matched_from_start"] is False, item
    # Спросили только места с известными координатами — выбор есть, и он по расстоянию: человек в Ургазе.
    item = next(x for x in _near(client, day, from_cities="Сибай,Ургаза", lat=URGAZA[0], lng=URGAZA[1])["items"]
                if x["id"] == ride["id"])
    assert item["matched_stop"] == "Ургаза" and item["matched_from_start"] is False, item


def test_near_unknown_stop_without_from_filter_selects_nothing(client, user_factory):
    """Без «Откуда» тот же договор: в поездке есть остановка без координат — какая из мест посадки ближе,
    неизвестно, поэтому не выбираем; расстояние остаётся до ближайшего из известных."""
    day = _day()
    drv = user_factory("SBS Unknown2 Driver", role=UserRole.driver)
    ride = _publish(client, drv, day, waypoints="Поворот на Юлук", tag="unknown2")
    item = next(x for x in _near(client, day, lat=URGAZA[0], lng=URGAZA[1])["items"] if x["id"] == ride["id"])
    assert item["distance_km"] is not None and item["distance_km"] > 30, "до начала (Сибай) — единственное известное"
    assert item["matched_stop"] is None and item["matched_from_start"] is False, item


def test_near_single_pickup_without_coordinates_is_preselected(client, user_factory):
    """Место одно — выбирать есть что и без координат: «из Сибая» — начало, «из Ургазы» — остановка."""
    day = _day()
    drv = user_factory("SBS Single Driver", role=UserRole.driver)
    ride = _publish(client, drv, day, tag="single")
    item = next(x for x in _near(client, day, from_city="Сибай")["items"] if x["id"] == ride["id"])
    assert item["matched_from_start"] is True and item["matched_stop"] is None
    item = next(x for x in _near(client, day, from_city="Ургаза")["items"] if x["id"] == ride["id"])
    assert item["matched_stop"] == "Ургаза" and item["matched_from_start"] is False


def test_near_preselect_follows_the_pickups_all_filters_agree_on(client, user_factory):
    """Два фильтра вместе: общее место посадки одно — Ургаза. Начало, подошедшее только первому фильтру
    («Сибай, Ургаза»), предлагать нельзя: второй («Ургаза») его не допускает."""
    day = _day()
    drv = user_factory("SBS Common Driver", role=UserRole.driver)
    ride = _publish(client, drv, day, tag="common")
    near = _near(client, day, from_city="Сибай,Ургаза", from_cities="Ургаза")
    item = next(x for x in near["items"] if x["id"] == ride["id"])
    assert item["matched_stop"] == "Ургаза" and item["matched_from_start"] is False, item


def test_near_same_route_with_different_coordinates_keeps_its_own_distance(client, user_factory):
    """Ответ по «виду маршрута» считается один раз на запрос (P2-2) — но координаты начала входят в
    вид: две поездки «Сибай → Акъяр» с разными точками выезда не получают одно расстояние на двоих."""
    day = _day()
    drv = user_factory("SBS Coords Driver", role=UserRole.driver)
    far = _publish(client, drv, day, waypoints="", tag="coords-far", hour=9)
    near_me = _publish(client, drv, day, waypoints="", tag="coords-near", hour=10)
    with Session(engine) as s:
        r = s.get(Ride, near_me["id"])
        r.from_lat, r.from_lng = URGAZA
        s.add(r)
        s.commit()
    items = {x["id"]: x for x in _near(client, day, lat=URGAZA[0], lng=URGAZA[1])["items"]}
    assert items[near_me["id"]]["distance_km"] < 1.0
    assert items[far["id"]]["distance_km"] > 30


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
    if not lines and engine.dialect.name == "postgresql":
        pytest.skip("на этом Postgres есть PostGIS — префильтр не упал, ловить нечего")
    assert lines, "предупреждение не записано"
    assert all("ST_" not in ln and "52.34" not in ln and "58.61" not in ln for ln in lines), lines


def test_postgis_prefilter_does_not_drop_rides_with_stops(client, trip, monkeypatch):
    """PostGIS ни в CI, ни у нас нет, поэтому подменяем `session.execute` и смотрим на ТЕКСТ префильтра:
    поездки с остановками он не выкидывает (`waypoints <> ''`) — ближе к человеку может быть остановка,
    а не начало маршрута (Сибай в 41 км от Ургазы). Подставленный ответ базы — «все активные», как
    у префильтра, который поездок не режет."""
    day, _drv, ride = trip
    monkeypatch.setattr(rides_mod, "_postgis_dialect", lambda session: True)
    real_execute = sqlmodel.Session.execute
    seen: list = []

    class _AllRides:
        def all(self):
            return [(ride["id"],)]

    def spy(self, statement, *args, **kwargs):
        sql = str(statement)
        if "ST_DWithin" in sql:
            seen.append(sql)
            return _AllRides()
        return real_execute(self, statement, *args, **kwargs)

    monkeypatch.setattr(sqlmodel.Session, "execute", spy)
    near = _near(client, day, lat=URGAZA[0], lng=URGAZA[1], radius_km=5)
    assert len(seen) == 1, "префильтр PostGIS не вызван"
    assert "(from_lat IS NULL OR waypoints <> '' OR ST_DWithin(" in seen[0], seen[0]
    item = next(x for x in near["items"] if x["id"] == ride["id"])
    assert item["matched_stop"] == "Ургаза" and item["distance_km"] < 1.0, "остановка в радиусе — поездка видна"


# ── Общая лента: метки «где сядет» принадлежат только тому, кто искал ──

def test_matched_stop_never_reaches_the_shared_feed_cache(client, trip, user_factory, monkeypatch):
    """Общая лента без фильтров кэшируется на 20 секунд и отдаётся всем. Метки `matched_*` считаются из
    запроса конкретного человека — в кэш (и в чужие ответы) они не попадают никогда."""
    store: dict = {}
    monkeypatch.setattr(rides_mod, "cache_get_json", lambda key: store.get(key))
    monkeypatch.setattr(rides_mod, "cache_set_json", lambda key, value, ttl: store.__setitem__(key, value))
    day, _drv, ride = trip
    searcher = user_factory("SBS Cache Searcher")
    mine = _feed(client, day, from_city="Ургаза", to_city="Акъяр")
    assert mine[ride["id"]]["matched_stop"] == "Ургаза", "контроль: поиск видит остановку"
    for _ in range(2):   # первый раз — свежая лента и запись в кэш, второй — из кэша
        feed = client.get("/rides", headers=searcher["auth"]).json()
        marked = [x["id"] for x in feed if x.get("matched_stop") is not None or x.get("matched_from_start")]
        assert not marked, marked
    assert store, "контроль: лента без фильтров в кэш записалась"
    for value in store.values():
        assert all(x.get("matched_stop") is None and x.get("matched_from_start") is False
                   and x.get("matched_dropoff_stop") is None and not x.get("matched_to_end") for x in value), \
            "в кэше ленты метки чужого поиска"


def test_request_snapshot_does_not_change_route_fit(client):
    """Запоминалка ключей мест на запрос (P2-2) — только скорость: ответ тот же, что без неё, а общий
    снимок справочника она не пачкает."""
    base = snapshot_cached()
    memo = request_snapshot(base)
    assert memo is not base and "_place_keys_memo" not in base
    r = _r()
    cases = [(["Ургаза"], ["Акъяр"]), (["Сибай"], ["Бурибай"]), (["Бурибай"], ["Ургаза"]), (["Урғаҙа"], None),
             (None, ["Аҡъяр"]), (["Акъяр"], None), (["Темясово"], ["Акъяр"]), (None, None)]
    for _ in range(2):   # второй проход — ответы уже из запоминалки
        for fv, tv in cases:
            assert route_fit(r, fv, tv, memo) == route_fit(r, fv, tv, base), (fv, tv)
    assert memo["_place_keys_memo"], "запоминалка заполнилась"


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


def test_make_fit_never_drops_off_before_the_pickup():
    """«Где выйдет» — только дальше по маршруту, чем посадка (сведение #188 с веткой Codex, 23.10.2026).
    Посадка не выбрана — выход сверяем с самым ранним местом посадки: остановка до него — не выход."""
    from app.stop_search import make_fit
    stops = ("Ургаза", "Бурибай")
    assert make_fit(stops, (1,), (), (0,)).matched_dropoff_stop is None, "выход раньше любой посадки"
    assert make_fit(stops, (1,), (), (0, 2)).matched_to_end is True, "из двух точных годится только конец"
    chosen = make_fit(stops, (-1, 0, 1), (0,), (1, 2))
    assert chosen.matched_stop == "Ургаза" and chosen.matched_dropoff_stop is None, "после посадки два варианта"
    same = make_fit(stops, (0,), (0,), (0, 2))
    assert same.matched_to_end is True, "выход строго после посадки: «Ургаза» → «Ургаза» не выход"


def test_near_dropoff_is_after_the_pickup_chosen_by_distance(client, user_factory):
    """«Ближайшие»: посадку выбрало расстояние (Бурибай — рядом с человеком), а «Куда» точно называет ту же
    остановку — карточка не скажет «сядешь в Бурибае, выйдешь в Бурибае»."""
    from app.geo import lookup
    day = _day()
    drv = user_factory("SBS Drop Driver", role=UserRole.driver)
    ride = _publish(client, drv, day, to="Бурибай-2", tag="drop-after")
    entry = snapshot_cached()
    here, end = lookup(entry, "Бурибай"), lookup(entry, "Акъяр")
    assert len(here) == 1 and len(end) == 1
    with Session(engine) as s:   # «Бурибай-2» справочник не знает: конец маршрута — у Акъяра, остановки в коридоре
        r = s.get(Ride, ride["id"])
        r.to_lat, r.to_lng = end[0].lat, end[0].lng
        s.add(r)
        s.commit()
    item = next(x for x in _near(client, day, to_city="Бурибай", lat=here[0].lat, lng=here[0].lng)["items"]
                if x["id"] == ride["id"])
    assert item["matched_stop"] == "Бурибай", item
    assert item["matched_dropoff_stop"] is None and item["matched_to_end"] is False, item
    far = next(x for x in _near(client, day, to_city="Бурибай", lat=URGAZA[0], lng=URGAZA[1])["items"]
               if x["id"] == ride["id"])
    assert far["matched_stop"] == "Ургаза" and far["matched_dropoff_stop"] == "Бурибай", far


def test_near_prefix_alone_does_not_preselect_the_start(client, user_factory):
    """«Сиб» — лишь начало слова: поездка находится, но место посадки «Ближайшие» без координат не выбирают
    (точно названное место — только полное имя; сведение с Codex, 23.10.2026)."""
    day = _day()
    drv = user_factory("SBS Prefix Driver", role=UserRole.driver)
    ride = _publish(client, drv, day, waypoints="", tag="prefix")
    item = next(x for x in _near(client, day, from_city="Сиб")["items"] if x["id"] == ride["id"])
    assert item["matched_stop"] is None and item["matched_from_start"] is False, item
    full = next(x for x in _near(client, day, from_city="Сибай")["items"] if x["id"] == ride["id"])
    assert full["matched_from_start"] is True, "контроль: полное имя выбирает начало"


def test_near_dropoff_must_be_named_exactly_by_every_to_filter(client, trip):
    """Два фильтра «Куда»: «Ургаза» точно, «Акъ» — лишь начало «Акъяр». Выход должен точно называть КАЖДЫЙ
    фильтр — здесь такого нет, и карточка выход не выбирает."""
    day, _drv, ride = trip
    item = next(x for x in _near(client, day, to_city="Ургаза", to_cities="Акъ")["items"] if x["id"] == ride["id"])
    assert item["matched_dropoff_stop"] is None and item["matched_to_end"] is False, item


def test_near_dropoff_is_strictly_after_the_pickup_named_by_the_query(client, trip):
    """«Откуда: Ургаза», «Куда: Ургаза или Акъяр» — сесть и выйти в одном месте нельзя: выход — конец."""
    day, _drv, ride = trip
    item = next(x for x in _near(client, day, from_city="Ургаза", to_city="Ургаза,Акъяр")["items"]
                if x["id"] == ride["id"])
    assert item["matched_stop"] == "Ургаза" and item["matched_to_end"] is True, item


def test_feed_dropoff_counts_from_the_pickup_the_card_shows(client, user_factory):
    """Лента не показывает посадку (вариантов два) — выход считается от самой ранней посадки, а не от той,
    что запрос назвал бы «точно»: после начала маршрута два «Ургаза»-подобных выхода — выход не выбираем."""
    from app.stop_search import make_fit
    fit = make_fit(("Абзаково", "Сибай"), (-1, 1), (1,), (0, 2))
    assert fit.matched_dropoff_stop is None and fit.matched_to_end is True, "контроль: от посадки «Сибай» — конец"
    assert rides_mod._matched(fit, None, by_query=True) == (None, False), "в ленте посадка не показана"
    assert rides_mod._dropoff(fit, None, by_query=True) == (None, False), "от начала — два выхода: не выбираем"
    assert rides_mod._dropoff(fit, 1, by_query=True) == (None, True), "показана посадка «Сибай» — выход в конце"
