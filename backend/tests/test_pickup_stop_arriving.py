"""Честные «Подъезжаю» и «Не пришёл» для пассажира на остановке; окно живой точки (18.10.2026, PR1).

Главный сценарий (из плана): в машину садятся двое — Айгуль в начале маршрута (Сибай) и Зарина на
остановке в Ургазе, за 41 км. Водитель у автовокзала Сибая жмёт «Подъезжаю» обеим. Айгуль — можно
(и «подтверждено по GPS»). Зарине — отказ «Ты ещё далеко от остановки «Ургаза» (≈41 км)»: раньше она
получала срочное «Водитель подъезжает» и сорок минут стояла на трассе.

Правила по месту посадки (`ride_stops.pickup_of`):
* начало маршрута — пин подачи, 800 м; «Не пришёл» — от выезда, «Подъезжаю» не требуется;
* остановка (село/райцентр) — GPS к её координатам: дальше 5 км отказ, ближе 2 км «подтверждено»;
  «Подъезжаю» не раньше, чем машина могла доехать (90 км/ч по прямой); «Не пришёл» — только после
  «Подъезжаю» и не раньше расчётного приезда (60 км/ч) + 15 минут;
* город и неизвестная справочнику остановка — без отказа и без «подтверждено»;
* бронь без места (NULL) — правила PR184.
Живая точка у брони на остановке открывается позже (не от «Выехал»), закрывается до посадки через
15 минут после срока «Не пришёл». Ссылка близким, канал пассажира и SOS берут машину по любой брони
рейса, но по правилам своей брони; SOS — только «в пути».
"""
from __future__ import annotations

import json
import re
import time
from datetime import timedelta

import fakeredis
import pytest
from sqlmodel import Session, select

import app.routers.bookings as bookings_mod
import app.services as svc
from app import instant_service as isv
from app import livepos
from app.config import settings
from app.db import engine
from app.models import Block, Booking, BookingStatus, Ride, SosEvent, TrustedContact, UserRole
from app.ride_stops import parse_stops, ride_has_stops_raw
from app.routers import location
from app.services import haversine_km
from app.timeutil import utcnow
from app.visibility import trip_is_live

from test_api import wait_gate

pytestmark = pytest.mark.timeout(30)

SIBAY = (52.716, 58.664)                 # пин подачи в начале маршрута
URGAZA = (52.34768, 58.61438)            # село Ургаза (Баймакский р-н) — остановка
NEAR_URGAZA = (52.3530, 58.6150)         # ≈ 0,6 км от остановки
MID_URGAZA = (52.3750, 58.6144)          # ≈ 3 км: без отказа и без «подтверждено»
STOPS = "Ургаза | Бурибай"
KM_TO_URGAZA = haversine_km(SIBAY[0], SIBAY[1], URGAZA[0], URGAZA[1])   # ≈ 41 км по прямой


@pytest.fixture
def redis_on():
    r = fakeredis.FakeStrictRedis(decode_responses=True)
    isv._redis_override = r
    yield r
    isv._redis_override = None


@pytest.fixture
def clock(monkeypatch):
    """Часы сервера в ручке брони (`bookings.utcnow`)."""
    def set_now(moment):
        monkeypatch.setattr(bookings_mod, "utcnow", lambda: moment)
    return set_now


@pytest.fixture
def pushes(monkeypatch) -> list:
    got: list = []
    monkeypatch.setattr(svc, "send_push", lambda session, uid, title, body, **k: got.append((uid, title)))
    return got


def _trip(client, user_factory, tag, *, depart_in=timedelta(minutes=-5), stops=STOPS,
          places=("Сибай", "Ургаза"), pin=True, frm="Сибай", to="Акъяр"):
    """Рейс с остановками и подтверждённые брони по местам `places` (None — место не указано).
    Мест в машине столько, сколько броней: полный рейс не теснит ленты других тестов."""
    drv = user_factory(f"PSA Driver {tag}", role=UserRole.driver)
    body = {"from_city": frm, "to_city": to, "comment": f"psa-{tag}", "waypoints": stops,
            "depart_at": (utcnow() + depart_in).isoformat() + "+00:00",
            "seats_total": len(places), "price": 700}
    if pin:
        body |= {"pickup_lat": SIBAY[0], "pickup_lng": SIBAY[1], "pickup": "Автовокзал"}
    r = client.post("/rides", headers=drv["auth"], json=body)
    assert r.status_code == 200, r.text
    ride = r.json()
    paxes, bids = [], []
    for i, place in enumerate(places):
        pax = user_factory(f"PSA Pax {tag} {i}")
        extra = {} if place is None else {"pickup_stop": place}
        b = client.post("/bookings", headers=pax["auth"], json={"ride_id": ride["id"], "seats": 1, **extra})
        assert b.status_code == 200, b.text
        assert client.post(f"/bookings/{b.json()['id']}/confirm", headers=drv["auth"]).status_code == 200
        paxes.append(pax)
        bids.append(b.json()["id"])
    return drv, paxes, ride, bids


def _arriving(client, drv, bid):
    return client.post(f"/bookings/{bid}/driver-status", headers=drv["auth"], json={"status": "arriving"})


def _departed(client, drv, bid):
    """Водитель нажал «Выехал» по брони `bid`: машина правда поехала (P2-2 для ссылки близким)."""
    r = client.post(f"/bookings/{bid}/driver-status", headers=drv["auth"], json={"status": "departed"})
    assert r.status_code == 200, r.text


def _driver_at(point, bid):
    """Водитель прислал точку в канал брони `bid` (как это делает `location.py`, роль driver)."""
    livepos.livepos_set("booking", bid, point[0], point[1])


def _booking(bid: int) -> Booking:
    with Session(engine) as s:
        return s.get(Booking, bid)


def _depart(ride_id: int):
    with Session(engine) as s:
        return s.get(Ride, ride_id).depart_at


def _role(client, pax, bid) -> dict:
    return client.get(f"/bookings/{bid}/role", headers=pax["auth"]).json()


# ── «Подъезжаю»: главный сценарий ──

def test_two_passengers_driver_at_the_start(client, user_factory, redis_on, pushes):
    drv, (aigul, zarina), _ride, (b_start, b_stop) = _trip(client, user_factory, "main")
    _driver_at(SIBAY, b_start)                 # водитель открыл поездку Айгуль: точка только в её канале
    pushes.clear()

    ok = _arriving(client, drv, b_start)
    assert ok.status_code == 200, ok.text
    assert _role(client, aigul, b_start)["arrival_verified"] is True

    far = _arriving(client, drv, b_stop)
    assert far.status_code == 409, far.text
    d = far.json()["detail"]
    assert d["code"] == "arriving_too_far" and abs(d["distance_km"] - KM_TO_URGAZA) < 1.0
    assert "Ургаза" in d["ru"] and "Ургаза" in d["ba"] and "Яҡынлашам" in d["ba"]
    b = _booking(b_stop)
    assert b.arriving_at is None and b.driver_phase == "", "отказ ничего не записал"
    assert [t for uid, t in pushes if uid == zarina["id"]] == [], "Зарине ничего не ушло"


def test_near_the_stop_is_verified(client, user_factory, redis_on, pushes):
    drv, (_a, zarina), _ride, (b_start, b_stop) = _trip(client, user_factory, "near")
    _driver_at(NEAR_URGAZA, b_start)
    pushes.clear()
    r = _arriving(client, drv, b_stop)
    assert r.status_code == 200, r.text
    assert _role(client, zarina, b_stop)["arrival_verified"] is True
    assert [t for uid, t in pushes if uid == zarina["id"]] == ["Водитель подъезжает"]


def test_second_device_at_home_does_not_refuse_at_the_stop(client, user_factory, redis_on):
    """Телефон водителя у Ургазы, а планшет дома (Сибай) с открытой бронью Зарины шлёт пульс позже:
    сверяем ближайшую из свежих точек — «Подъезжаю» проходит (приёмка PR0, P2)."""
    drv, (_a, zarina), _ride, (b_start, b_stop) = _trip(client, user_factory, "twodev")
    _driver_at(NEAR_URGAZA, b_start)
    _driver_at(SIBAY, b_stop)
    r = _arriving(client, drv, b_stop)
    assert r.status_code == 200, r.text
    assert _role(client, zarina, b_stop)["arrival_verified"] is True


def test_nan_point_at_the_stop_gives_no_badge_and_does_not_hide_a_far_one(client, user_factory, redis_on):
    """Кадр `{"lat": NaN}` в кэше: у остановки — без галочки; рядом с нормальной дальней — отказ."""
    drv, (_a, zarina), _ride, (b_start, b_stop) = _trip(client, user_factory, "nan")
    livepos.livepos_set("booking", b_start, float("nan"), float("nan"))
    assert _arriving(client, drv, b_stop).status_code == 200
    assert _role(client, zarina, b_stop)["arrival_verified"] is False
    drv2, _p2, _ride2, (s2, st2) = _trip(client, user_factory, "nan2")
    _driver_at(SIBAY, s2)
    livepos.livepos_set("booking", st2, float("nan"), float("nan"))
    r = _arriving(client, drv2, st2)
    assert r.status_code == 409 and r.json()["detail"]["code"] == "arriving_too_far", r.text


def test_between_two_and_five_km_passes_without_the_badge(client, user_factory, redis_on):
    drv, (_a, zarina), _ride, (b_start, b_stop) = _trip(client, user_factory, "mid")
    _driver_at(MID_URGAZA, b_start)
    assert _arriving(client, drv, b_stop).status_code == 200
    assert _role(client, zarina, b_stop)["arrival_verified"] is False


def test_no_driver_point_passes_without_the_badge(client, user_factory, redis_on):
    drv, (_a, zarina), _ride, (_b_start, b_stop) = _trip(client, user_factory, "nopoint")
    assert _arriving(client, drv, b_stop).status_code == 200
    assert _role(client, zarina, b_stop)["arrival_verified"] is False


def test_city_stop_is_never_refused_nor_verified(client, user_factory, redis_on):
    """Баймак — город: центр не подставляем (P2-1) — ни отказа издалека, ни галочки рядом."""
    drv, (pax,), _ride, (bid,) = _trip(client, user_factory, "city", stops="Баймак | Ургаза", places=("Баймак",))
    _driver_at(SIBAY, bid)                      # 25 км от Баймака
    assert _arriving(client, drv, bid).status_code == 200
    assert _role(client, pax, bid)["arrival_verified"] is False


def test_unknown_stop_has_no_refusal_and_no_late_limit(client, user_factory, redis_on, clock):
    """«Поворот на Юлук» справочник не знает: ни GPS-отказа, ни раннего предела — только `depart − 30`."""
    drv, (pax,), ride, (bid,) = _trip(client, user_factory, "unknown", stops="Поворот на Юлук | Бурибай",
                                      places=("Поворот на Юлук",), depart_in=timedelta(hours=2))
    _driver_at(SIBAY, bid)
    clock(_depart(ride["id"]) - bookings_mod.DONE_EARLY_GRACE)
    r = _arriving(client, drv, bid)
    assert r.status_code == 200, r.text
    assert _role(client, pax, bid)["arrival_verified"] is False


def test_unknown_place_keeps_pr184_rules(client, user_factory, redis_on):
    """Бронь без места на поездке с остановками: без GPS-отказа (даже у пина), без галочки."""
    drv, (pax,), _ride, (bid,) = _trip(client, user_factory, "null", places=(None,))
    _driver_at(URGAZA, bid)                     # 41 км от пина начала
    assert _arriving(client, drv, bid).status_code == 200
    assert _role(client, pax, bid)["arrival_verified"] is False


def test_start_passenger_on_ride_with_stops_is_checked_at_the_pin(client, user_factory, redis_on):
    """Пассажир начала — правила начала, даже на поездке с остановками (раньше PR184 их выключал)."""
    drv, (_pax,), _ride, (bid,) = _trip(client, user_factory, "startpin", places=("Сибай",))
    _driver_at(URGAZA, bid)
    r = _arriving(client, drv, bid)
    assert r.status_code == 409 and "места подачи" in r.json()["detail"]["ru"], r.text


def test_the_stops_switch_is_separate(client, user_factory, redis_on, monkeypatch):
    drv, _paxes, _ride, (b_start, b_stop) = _trip(client, user_factory, "switch")
    _driver_at(URGAZA, b_start)                 # у остановки Зарины, в 41 км от пина Айгуль
    monkeypatch.setattr(settings, "arrival_verify_stops_enabled", False)
    assert _arriving(client, drv, b_stop).status_code == 200, "рубильник остановок выключил отказ у них"
    assert _arriving(client, drv, b_start).status_code == 409, "проверку 800 м у пина он не трогает"


def test_the_general_switch_does_not_touch_stops(client, user_factory, redis_on, monkeypatch):
    drv, _paxes, _ride, (b_start, b_stop) = _trip(client, user_factory, "switch2")
    _driver_at(SIBAY, b_start)
    monkeypatch.setattr(settings, "arrival_verify_enabled", False)
    assert _arriving(client, drv, b_stop).status_code == 409, "отказ у остановки — по своему рубильнику"


# ── «Подъезжаю»: ранний предел у остановки ──

def test_stop_arriving_opens_when_the_car_could_be_there(client, user_factory, redis_on, clock, pushes):
    drv, (_a, zarina), ride, (_b_start, b_stop) = _trip(client, user_factory, "early",
                                                         depart_in=timedelta(hours=2))
    pushes.clear()                              # «Бронь подтверждена» — не про это
    depart = _depart(ride["id"])
    opens = depart - bookings_mod.DONE_EARLY_GRACE + timedelta(hours=KM_TO_URGAZA / settings.stop_fast_kmh)
    # За час до выезда «Сел» ещё нельзя — текст общий, без ««Сел» отметить можно» (A-P3-3); срок в
    # ответе — всё равно срок остановки.
    clock(depart - timedelta(hours=1))
    too_early = _arriving(client, drv, b_stop)
    assert too_early.status_code == 409, too_early.text
    d0 = too_early.json()["detail"]
    assert d0["code"] == "arriving_too_early" and "«Сел»" not in d0["ru"] and "Ултырҙы" not in d0["ba"], d0
    assert "30 минут до выезда" in d0["ru"] and "30 минут" in d0["ba"]
    clock(opens - timedelta(seconds=1))
    early = _arriving(client, drv, b_stop)
    assert early.status_code == 409, early.text
    d = early.json()["detail"]
    assert d["code"] == "arriving_too_early"
    from datetime import datetime
    got = datetime.fromisoformat(d["arriving_opens_at"])
    assert abs((got - opens).total_seconds()) < 60, (got, opens)
    assert d0["arriving_opens_at"] == d["arriving_opens_at"]
    assert 0 < d["arriving_wait_sec"] <= 61
    hhmm = (got + timedelta(hours=settings.local_tz_offset_hours)).strftime("%H:%M")
    assert "Ургаза" in d["ru"] and hhmm in d["ru"] and "Позвони или напиши" in d["ru"] and "«Сел»" in d["ru"]
    assert "Ултырҙы" in d["ba"] and hhmm in d["ba"]
    assert _booking(b_stop).arriving_at is None and [t for u, t in pushes if u == zarina["id"]] == []
    row = next(x for x in client.get("/driver/bookings", headers=drv["auth"]).json() if x["booking_id"] == b_stop)
    assert row["arriving_opens_at"] == d["arriving_opens_at"]
    clock(got)
    assert _arriving(client, drv, b_stop).status_code == 200


def test_start_passenger_keeps_the_thirty_minutes(client, user_factory, redis_on, clock):
    drv, _p, ride, (b_start, _b_stop) = _trip(client, user_factory, "early2", depart_in=timedelta(hours=2))
    opens = _depart(ride["id"]) - bookings_mod.DONE_EARLY_GRACE
    clock(opens)
    assert _arriving(client, drv, b_start).status_code == 200


# ── «Не пришёл» ──

def test_stop_no_show_needs_arriving_then_waits_for_the_expected_arrival(client, user_factory, redis_on, clock):
    drv, _p, ride, (b_start, b_stop) = _trip(client, user_factory, "noshow", depart_in=timedelta(minutes=-5))
    depart = _depart(ride["id"])
    r = client.post(f"/bookings/{b_stop}/no-show", headers=drv["auth"])
    assert r.status_code == 409 and r.json()["detail"]["code"] == "no_show_needs_arriving", r.text
    pressed = utcnow()
    clock(pressed)
    _driver_at(NEAR_URGAZA, b_start)
    ok = _arriving(client, drv, b_stop)
    assert ok.status_code == 200, ok.text
    expected = max(depart, pressed, depart + timedelta(hours=KM_TO_URGAZA / settings.stop_expected_kmh)) \
        + bookings_mod.NO_SHOW_WAIT
    from datetime import datetime
    at = datetime.fromisoformat(ok.json()["no_show_at"])
    assert abs((at - expected).total_seconds()) < 60, (at, expected)
    assert at > pressed + bookings_mod.NO_SHOW_WAIT + timedelta(minutes=20), "не раньше расчётного приезда"
    clock(at - timedelta(seconds=1))
    early = client.post(f"/bookings/{b_stop}/no-show", headers=drv["auth"])
    assert early.status_code == 409 and early.json()["detail"]["code"] == "no_show_too_early"
    clock(at)
    done = client.post(f"/bookings/{b_stop}/no-show", headers=drv["auth"])
    assert done.status_code == 200 and done.json()["no_show"] is True, done.text


def test_start_passenger_no_show_does_not_need_arriving(client, user_factory, redis_on):
    drv, _p, _ride, (b_start, _b) = _trip(client, user_factory, "noshow2",
                                          depart_in=-(bookings_mod.NO_SHOW_WAIT + timedelta(minutes=1)))
    row = next(x for x in client.get("/driver/bookings", headers=drv["auth"]).json() if x["booking_id"] == b_start)
    assert row["no_show_needs_arriving"] is False and row["pickup_stop"] == ""
    r = client.post(f"/bookings/{b_start}/no-show", headers=drv["auth"])
    assert r.status_code == 200 and r.json()["no_show"] is True, r.text


def test_unknown_stop_no_show_waits_for_the_estimate(client, user_factory, redis_on, clock):
    """Неизвестная остановка — первая из двух: оценка пути 1/3 прямой Сибай → Акъяр (≈32 км)."""
    drv, _p, ride, (bid,) = _trip(client, user_factory, "noshow3", stops="Поворот на Юлук | Бурибай",
                                  places=("Поворот на Юлук",), depart_in=timedelta(minutes=-5))
    depart = _depart(ride["id"])
    clock(utcnow())
    assert _arriving(client, drv, bid).status_code == 200
    with Session(engine) as s:
        r = s.get(Ride, ride["id"])
        est = haversine_km(r.from_lat, r.from_lng, r.to_lat, r.to_lng) / 3
    row = next(x for x in client.get("/driver/bookings", headers=drv["auth"]).json() if x["booking_id"] == bid)
    from datetime import datetime
    at = datetime.fromisoformat(row["no_show_at"])
    assert at >= depart + timedelta(hours=est / settings.stop_expected_kmh) + bookings_mod.NO_SHOW_WAIT - timedelta(seconds=1)


def test_old_ride_with_only_bad_stops_keeps_pr184_rules(client, user_factory, redis_on):
    """Старая поездка «ул. Ленина 5 | 8917…»: допустимых остановок нет — показать нечего, но правила
    остановок не выключаются (`ride_has_stops_raw`, а не `parse_stops`, A-P3-1). Бронь без места
    (NULL) — «неизвестно» по PR184: «Не пришёл» только после «Подъезжаю», GPS-отказа нет. Иначе
    пассажирку, которая ждёт на трассе, сняли бы через 15 минут после выезда из начала маршрута."""
    drv = user_factory("PSA Driver rawonly", role=UserRole.driver)
    pax = user_factory("PSA Pax rawonly")
    depart = utcnow() - bookings_mod.NO_SHOW_WAIT - timedelta(minutes=1)   # у начала «Не пришёл» уже можно
    ride = client.post("/rides", headers=drv["auth"], json={
        "from_city": "Сибай", "to_city": "Акъяр", "comment": "psa-rawonly", "depart_at": depart.isoformat() + "+00:00",
        "seats_total": 1, "price": 700, "pickup_lat": SIBAY[0], "pickup_lng": SIBAY[1], "pickup": "Автовокзал"}).json()
    with Session(engine) as s:
        r = s.get(Ride, ride["id"])
        r.waypoints = "ул. Ленина 5 | 89171234567"
        s.add(r)
        s.commit()
    bid = client.post("/bookings", headers=pax["auth"], json={"ride_id": ride["id"], "seats": 1}).json()["id"]
    assert client.post(f"/bookings/{bid}/confirm", headers=drv["auth"]).status_code == 200
    with Session(engine) as s:
        b = s.get(Booking, bid)
        r = s.get(Ride, ride["id"])
        assert b.pickup_stop is None and parse_stops(r) == [] and ride_has_stops_raw(r) is True
        assert bookings_mod.no_show_needs_arriving(r, b) is True
    row = next(x for x in client.get("/driver/bookings", headers=drv["auth"]).json() if x["booking_id"] == bid)
    assert row["no_show_needs_arriving"] is True and row["ride_has_stops"] is True, row
    ns = client.post(f"/bookings/{bid}/no-show", headers=drv["auth"])
    assert ns.status_code == 409 and ns.json()["detail"]["code"] == "no_show_needs_arriving", ns.text
    _driver_at(URGAZA, bid)                      # 41 км от пина: «неизвестно» — без GPS-отказа
    ok = _arriving(client, drv, bid)
    assert ok.status_code == 200, ok.text
    assert _booking(bid).arrival_verified is False


# ── Окно живой точки у брони на остановке ──

def _unit(stop_place, *, status=BookingStatus.confirmed, phase="", arriving_at=None, stops=STOPS):
    depart = utcnow().replace(microsecond=0) + timedelta(days=1)
    ride = Ride(driver_id=1, from_city="Сибай", to_city="Акъяр", depart_at=depart, waypoints=stops,
                from_lat=52.716, from_lng=58.664, to_lat=51.872, to_lng=58.236,
                pickup_lat=SIBAY[0], pickup_lng=SIBAY[1])
    b = Booking(ride_id=1, passenger_id=2, status=status, driver_phase=phase, pickup_stop=stop_place,
                arriving_at=arriving_at)
    return ride, b, depart


def test_stop_window_opens_later_and_not_from_departed(client):
    ride, b, depart = _unit("Ургаза")
    start_ride, start_b, _ = _unit("")
    hour_before = depart - timedelta(hours=1)
    assert trip_is_live(start_b, start_ride, now=hour_before) is True, "у начала — за час, как раньше"
    assert trip_is_live(b, ride, now=hour_before) is False, "у остановки — позже: машина ещё у дома"
    opens = hour_before + timedelta(hours=KM_TO_URGAZA / settings.stop_fast_kmh)
    assert trip_is_live(b, ride, now=opens - timedelta(minutes=1)) is False
    assert trip_is_live(b, ride, now=opens + timedelta(minutes=1)) is True
    departed_ride, departed_b, _ = _unit("Ургаза", phase="departed")
    assert trip_is_live(departed_b, departed_ride, now=hour_before) is False, "«Выехал» окно остановки не открывает"


def test_unknown_stop_window_opens_by_arriving(client):
    """Неизвестная остановка далеко по оценке: окно по времени позже, а «Подъезжаю» открывает сразу."""
    stops = "Бурибай | Поворот на Юлук"
    ride, b, depart = _unit("Поворот на Юлук", stops=stops)
    moment = depart - timedelta(minutes=25)
    assert trip_is_live(b, ride, now=moment) is False
    ride2, b2, _ = _unit("Поворот на Юлук", stops=stops, arriving_at=moment - timedelta(minutes=1))
    assert trip_is_live(b2, ride2, now=moment) is True


def test_stop_window_closes_after_the_no_show_deadline_until_boarding(client):
    ride, b, depart = _unit("Ургаза", arriving_at=None)
    arrived = depart + timedelta(minutes=50)
    ride, b, depart = _unit("Ургаза", arriving_at=arrived)
    deadline = bookings_mod.no_show_available_at(ride, b)
    assert deadline is not None
    assert trip_is_live(b, ride, now=deadline + timedelta(minutes=14)) is True
    assert trip_is_live(b, ride, now=deadline + bookings_mod.LIVE_AFTER_NO_SHOW) is False, \
        "не села — до посадки машину больше не показываем"
    onboard_ride, onboard_b, _ = _unit("Ургаза", status=BookingStatus.onboard, arriving_at=arrived)
    assert trip_is_live(onboard_b, onboard_ride, now=deadline + timedelta(hours=2)) is True


def test_stop_window_closes_even_without_arriving(client):
    """Водитель проехал Ургазу, не нажав ни «Подъезжаю», ни «Сел», ни «Не пришёл» (у остановки без
    «Подъезжаю» срока неявки нет). Окно всё равно закрывается: через 15 + 15 минут после расчётного
    приезда на остановку (60 км/ч по прямой) — а не висит часами (приёмка PR186, S1)."""
    ride, b, depart = _unit("Ургаза", arriving_at=None)
    assert bookings_mod.no_show_available_at(ride, b) is None, "без «Подъезжаю» неявки нет"
    closes = depart + timedelta(hours=KM_TO_URGAZA / settings.stop_expected_kmh) \
        + bookings_mod.NO_SHOW_WAIT + bookings_mod.LIVE_AFTER_NO_SHOW
    assert abs((bookings_mod.stop_live_window(ride, b)[1] - closes).total_seconds()) < 1
    assert trip_is_live(b, ride, now=closes - timedelta(minutes=1)) is True
    assert trip_is_live(b, ride, now=closes) is False
    assert trip_is_live(b, ride, now=depart + timedelta(hours=6)) is False
    # Позднее «Подъезжаю» (водитель всё-таки доехал) сдвигает срок неявки — окно открывается снова.
    late = depart + timedelta(hours=2)
    ride2, b2, _ = _unit("Ургаза", arriving_at=late)
    assert trip_is_live(b2, ride2, now=late + timedelta(minutes=1)) is True


def test_unknown_stop_window_without_arriving_closes_by_the_estimate(client):
    """Неизвестная справочнику остановка — срок по оценке пути ((i+1)/(n+1) прямой), не от выезда."""
    stops = "Бурибай | Поворот на Юлук"
    ride, b, depart = _unit("Поворот на Юлук", stops=stops)
    p = bookings_mod.pickup_of(ride, b)
    assert p.kind == "stop" and p.point is None and p.est_km and p.est_km > 50, p
    closes = depart + timedelta(hours=p.est_km / settings.stop_expected_kmh) \
        + bookings_mod.NO_SHOW_WAIT + bookings_mod.LIVE_AFTER_NO_SHOW
    assert abs((bookings_mod.stop_live_window(ride, b)[1] - closes).total_seconds()) < 1
    assert trip_is_live(b, ride, now=closes - timedelta(minutes=1)) is True
    assert trip_is_live(b, ride, now=closes) is False
    late = closes + timedelta(minutes=10)
    ride2, b2, _ = _unit("Поворот на Юлук", stops=stops, arriving_at=late)
    assert trip_is_live(b2, ride2, now=late + timedelta(minutes=1)) is True, "позднее «Подъезжаю» открывает снова"


def test_family_link_loses_the_car_hours_after_a_missed_stop(client, user_factory, redis_on):
    """Сквозь ссылку близким: «Подъезжаю» по Зарине не жали, выезд был 5 часов назад — её маме
    машину больше не показываем (а до сдвига времени показывали: тест не пустой)."""
    drv, (_aigul, zarina), ride, (a, b) = _trip(client, user_factory, "missedstop", places=("Сибай", "Ургаза"),
                                                depart_in=timedelta(minutes=20))
    token = _share(client, zarina, b, "+79170000999")
    _driver_at(SIBAY, a)
    _departed(client, drv, a)                   # машина поехала (P2-2): без этого близким машины нет
    assert client.get(f"/t/{token}/state.json").json()["car"] is not None, "окно Ургазы открыто — машина видна"
    # Выезд — 5 часов назад (мимо ручки: с бронями время поездки не правится).
    with Session(engine) as s:
        r = s.get(Ride, ride["id"])
        r.depart_at = utcnow() - timedelta(hours=5)
        s.add(r)
        s.commit()
    _driver_at((51.9, 58.3), a)                 # машина уже у конца маршрута
    state = client.get(f"/t/{token}/state.json").json()
    assert state["car"] is None, f"через 5 ч после выезда не севшей Зарине всё ещё видна машина: {state}"


def test_channel_gate_closes_the_open_window_without_a_poke(client, user_factory, monkeypatch):
    """Канал, уже пересылающий точку, сам замечает конец окна остановки — без толчка (`_Пора`)."""
    _drv, (pax,), _ride, (bid,) = _trip(client, user_factory, "gateclose", places=("Ургаза",),
                                        depart_in=timedelta(minutes=-5))
    with Session(engine) as s:
        b = s.get(Booking, bid)
        b.arriving_at = utcnow()
        s.add(b)
        s.commit()
        closes = bookings_mod.no_show_available_at(s.get(Ride, b.ride_id), b) + bookings_mod.LIVE_AFTER_NO_SHOW
    решение = location._Пора(bid, pax["id"])
    assert решение.наступила() is True and решение._закрыть_в is not None
    # Прошло время: окно закрылось, толчка не было.
    monkeypatch.setattr("app.timeutil.utcnow", lambda: closes + timedelta(seconds=1))
    monkeypatch.setattr(location, "_wall", lambda: решение._закрыть_в + 1)
    assert решение.наступила() is False, "окно остановки закрылось — точка больше не идёт"


# ── «Выехал» по брони на остановке ничего не открывает ──

def test_departed_for_a_stop_booking_opens_nothing(client, user_factory, redis_on, gate_seen):
    """Бурибай — 87 км от Сибая: окно точки откроется за 2 минуты до выезда. Сейчас выезд через 40
    минут, водитель жмёт «Выехал» по брони Зарины: принято (за час до выезда можно), но её окно
    закрыто — точка водителя в её канал не идёт и в кэш не пишется, у её близких машины нет."""
    drv, (zarina,), ride, (bid,) = _trip(client, user_factory, "departedstop", places=("Бурибай",),
                                         depart_in=timedelta(minutes=40))
    r = client.post(f"/bookings/{bid}/driver-status", headers=drv["auth"], json={"status": "departed"})
    assert r.status_code == 200, r.text
    with Session(engine) as s:
        b = s.get(Booking, bid)
        assert b.driver_phase == "departed"
        assert trip_is_live(b, s.get(Ride, b.ride_id)) is False
    path = f"/ws/trip/{bid}/location"
    with client.websocket_connect(path) as mouth:
        mouth.send_text(json.dumps({"type": "auth", "token": drv["token"]}))
        before = gate_seen["n"]
        mouth.send_text(json.dumps({"type": "loc", "lat": SIBAY[0], "lng": SIBAY[1], "ts": 1}))
        wait_gate(gate_seen, before)
    assert livepos.livepos_get("booking", bid) is None, "точка водителя ушла в кэш брони на остановке"
    c = client.post("/trusted-contacts", headers=zarina["auth"],
                    json={"name": "Мама", "relation": "мама", "phone": "+79170000931"}).json()
    token = client.post(f"/bookings/{bid}/share", headers=zarina["auth"], json={"contact_id": c["id"]}).json()["token"]
    state = client.get(f"/t/{token}/state.json").json()
    assert state["status"] == "wait" and state["car"] is None


def test_undo_boarded_rolls_the_open_channel_back_for_a_stop_booking(client, user_factory, redis_on):
    """Бурибай — 87 км: окно Зарины откроется за 2 минуты до выезда, сейчас до выезда 20 минут.
    Водитель промахнулся карточкой — «Сел» по Зарине (за 30 минут до выезда можно) и тут же
    «Отменить». Бронь снова подтверждена и вне окна — уже открытый канал откатывается: её точка не
    идёт водителю, его — ей (приёмка PR186, S3). Посадка у начала маршрута так не откатывается —
    там окно и так открыто за час."""
    drv, (zarina,), _ride, (bid,) = _trip(client, user_factory, "undostop", places=("Бурибай",),
                                          depart_in=timedelta(minutes=20))
    её_канал = location._Пора(bid, zarina["id"])
    его_канал = location._Пора(bid, drv["id"])
    assert её_канал.наступила() is False and его_канал.наступила() is False, "окно Зарины ещё закрыто"
    r = client.post(f"/bookings/{bid}/driver-status", headers=drv["auth"], json={"status": "boarded"})
    assert r.status_code == 200, r.text
    assert её_канал.наступила() is True and его_канал.наступила() is True, "«Сел» открыл канал (ожидаемо)"
    u = client.post(f"/bookings/{bid}/boarded/undo", headers=drv["auth"])
    assert u.status_code == 200, u.text
    with Session(engine) as s:
        bk = s.get(Booking, bid)
        assert bk.status == BookingStatus.confirmed
        assert trip_is_live(bk, s.get(Ride, bk.ride_id)) is False, "по правилу брони окно закрыто"
    assert её_канал.наступила() is False, "после «Отменить» её канал всё ещё пересылает точки"
    assert его_канал.наступила() is False, "после «Отменить» его канал всё ещё пересылает точки"
    assert её_канал.кончилась is False, "канал не рвём: окно откроется само, по времени или «Подъезжаю»"


def test_undo_boarded_keeps_the_channel_of_a_start_booking(client, user_factory, redis_on):
    """Контроль: у пассажира начала окно открыто за час до выезда — «Отменить» его не закрывает."""
    drv, (aigul,), _ride, (bid,) = _trip(client, user_factory, "undostart", places=("Сибай",),
                                         depart_in=timedelta(minutes=20))
    канал = location._Пора(bid, aigul["id"])
    assert канал.наступила() is True
    assert client.post(f"/bookings/{bid}/driver-status", headers=drv["auth"], json={"status": "boarded"}).status_code == 200
    assert client.post(f"/bookings/{bid}/boarded/undo", headers=drv["auth"]).status_code == 200
    assert канал.наступила() is True and канал.кончилась is False


def test_passenger_frame_never_reaches_the_cache_nor_another_booking(client, user_factory, redis_on, gate_seen):
    """Сторож (приёмка PR186, S2): точка ПАССАЖИРКИ в кэш брони не ложится никогда — ни ссылке её
    близких, ни пассажиру B того же рейса (через `driver_point_for_booking`) она не уходит. В кэш
    пишет только водитель."""
    _drv, (a_pax, _b_pax), _ride, (a, b) = _trip(client, user_factory, "paxframe", places=("Сибай", "Сибай"),
                                                  depart_in=timedelta(minutes=20))
    with client.websocket_connect(f"/ws/trip/{a}/location") as mouth:
        mouth.send_text(json.dumps({"type": "auth", "token": a_pax["token"]}))
        before = gate_seen["n"]
        mouth.send_text(json.dumps({"type": "loc", "lat": 52.0, "lng": 58.0, "ts": 1}))
        wait_gate(gate_seen, before)
    assert livepos.livepos_get("booking", a) is None, "точка пассажирки A легла в кэш брони"
    with Session(engine) as s:
        bk = s.get(Booking, b)
        assert location.driver_point_for_booking(s, bk, s.get(Ride, bk.ride_id)) is None, \
            "точка пассажирки A ушла пассажиру B"


# ── Ссылка близким, SOS и канал пассажира B: машина по любой брони рейса, правила — свои ──

def _share(client, pax, bid, phone):
    c = client.post("/trusted-contacts", headers=pax["auth"],
                    json={"name": "Мама", "relation": "мама", "phone": phone}).json()
    return client.post(f"/bookings/{bid}/share", headers=pax["auth"], json={"contact_id": c["id"]}).json()["token"]


def test_family_of_b_sees_the_car_from_a_inside_b_window(client, user_factory, redis_on):
    drv, (aigul, bota), _ride, (a, b) = _trip(client, user_factory, "shareb", places=("Сибай", "Сибай"),
                                              depart_in=timedelta(minutes=20))
    token = _share(client, bota, b, "+79170000941")
    _driver_at(SIBAY, a)                        # водитель открыл поездку Айгуль
    _departed(client, drv, a)                   # и нажал там «Выехал» (P2-2)
    state = client.get(f"/t/{token}/state.json").json()
    assert state["status"] == "to_pickup" and state["car"] is not None, state
    assert abs(state["car"]["lat"] - SIBAY[0]) < 1e-6
    assert livepos.livepos_get("booking", b) is None, "в кэш брони B ничего не писали"


def test_family_of_b_prefers_b_own_channel_point(client, user_factory, redis_on):
    """Канал брони B жив — её близкие видят ровно его точку (как раньше), а не чужую посвежее."""
    drv, (_aigul, bota), _ride, (a, b) = _trip(client, user_factory, "shareown", places=("Сибай", "Сибай"),
                                               depart_in=timedelta(minutes=20))
    token = _share(client, bota, b, "+79170000946")
    _departed(client, drv, a)
    _driver_at(SIBAY, b)
    _driver_at(NEAR_URGAZA, a)                  # позже и в другом месте (второе устройство)
    state = client.get(f"/t/{token}/state.json").json()
    assert state["car"] is not None and abs(state["car"]["lat"] - SIBAY[0]) < 1e-6, state


def test_family_of_b_sees_nothing_before_b_window(client, user_factory, redis_on):
    drv, (_aigul, zarina), _ride, (a, b) = _trip(client, user_factory, "shareearly", places=("Сибай", "Бурибай"),
                                                 depart_in=timedelta(minutes=20))
    token = _share(client, zarina, b, "+79170000942")
    _driver_at(SIBAY, a)
    _departed(client, drv, a)                   # машина поехала — но окно Зарины ещё закрыто
    state = client.get(f"/t/{token}/state.json").json()
    # Бурибай — 87 км: окно Зарины откроется за 2 минуты до выезда, а сейчас 20 минут до него.
    assert state["status"] == "wait" and state["car"] is None, state


def test_the_booking_helper_itself_keeps_the_window(client, user_factory, redis_on):
    """Вторая стена: сама обёртка (`driver_point_for_booking`) вне окна брони ничего не отдаёт —
    даже если вызывающий забыл спросить `trip_is_live`."""
    _drv, (_aigul, zarina), _ride, (a, b) = _trip(client, user_factory, "helperwin", places=("Сибай", "Бурибай"),
                                                  depart_in=timedelta(minutes=20))
    _driver_at(SIBAY, a)
    with Session(engine) as s:
        bk = s.get(Booking, b)
        assert location.driver_point_for_booking(s, bk, s.get(Ride, bk.ride_id)) is None
        ak = s.get(Booking, a)
        assert location.driver_point_for_booking(s, ak, s.get(Ride, ak.ride_id)) is not None


def test_block_before_boarding_hides_the_car_from_b(client, user_factory, redis_on):
    drv, (_aigul, bota), _ride, (a, b) = _trip(client, user_factory, "shareblock", places=("Сибай", "Сибай"),
                                               depart_in=timedelta(minutes=20))
    token = _share(client, bota, b, "+79170000943")
    _driver_at(SIBAY, a)
    _departed(client, drv, a)
    assert client.get(f"/t/{token}/state.json").json()["car"] is not None, "без блокировки машина видна"
    with Session(engine) as s:
        s.add(Block(user_id=bota["id"], blocked_user_id=drv["id"]))
        s.commit()
    assert client.get(f"/t/{token}/state.json").json()["car"] is None


def test_his_block_before_boarding_beats_her_later_block(client, user_factory, redis_on):
    """Он заблокировал её до посадки, «Сел» отмечен по старому пути, она блокирует в ответ уже «в пути»:
    её ссылка машину не показывает (P3: готовое правило `_пишем_без_пересылки`)."""
    drv, (_aigul, bota), _ride, (a, b) = _trip(client, user_factory, "sharehis", places=("Сибай", "Сибай"),
                                               depart_in=timedelta(minutes=20))
    token = _share(client, bota, b, "+79170000944")
    _driver_at(SIBAY, a)
    now = utcnow()
    with Session(engine) as s:
        s.add(Block(user_id=drv["id"], blocked_user_id=bota["id"], created_at=now - timedelta(minutes=10)))
        bk = s.get(Booking, b)
        bk.status = BookingStatus.onboard
        bk.onboard_at = now - timedelta(minutes=5)
        s.add(bk)
        s.add(Block(user_id=bota["id"], blocked_user_id=drv["id"], created_at=now - timedelta(minutes=1)))
        s.commit()
    assert client.get(f"/t/{token}/state.json").json()["car"] is None


def test_her_block_in_transit_keeps_the_car_for_family(client, user_factory, redis_on):
    drv, (_aigul, bota), _ride, (a, b) = _trip(client, user_factory, "sharetransit", places=("Сибай", "Сибай"),
                                               depart_in=timedelta(minutes=20))
    token = _share(client, bota, b, "+79170000945")
    _driver_at(SIBAY, a)
    now = utcnow()
    with Session(engine) as s:
        bk = s.get(Booking, b)
        bk.status = BookingStatus.onboard
        bk.onboard_at = now - timedelta(minutes=5)
        s.add(bk)
        s.add(Block(user_id=bota["id"], blocked_user_id=drv["id"], created_at=now - timedelta(minutes=1)))
        s.commit()
    assert client.get(f"/t/{token}/state.json").json()["car"] is not None


def _sos_note(client, pax, bid) -> str:
    with Session(engine) as s:
        s.add(TrustedContact(user_id=pax["id"], name="Сестра", phone=f"+7917{pax['id']:07d}"[:12]))
        s.commit()
    r = client.post("/sos", headers=pax["auth"], json={"category": "other", "booking_id": bid, "note": "тест"})
    assert r.status_code == 200, r.text
    with Session(engine) as s:
        ev = s.exec(select(SosEvent).where(SosEvent.user_id == pax["id"]).order_by(SosEvent.id.desc())).first()
        return ev.note or ""


def test_sos_takes_the_car_only_onboard(client, user_factory, redis_on, monkeypatch):
    monkeypatch.setattr("app.routers.safety._send_sos_sms", lambda phones, text: None)
    monkeypatch.setattr("app.routers.safety.notify_admin_telegram", lambda *a, **kw: True)
    drv, (_aigul, bota), _ride, (a, b) = _trip(client, user_factory, "sos", places=("Сибай", "Сибай"),
                                               depart_in=timedelta(minutes=20))
    _driver_at(URGAZA, a)
    before = _sos_note(client, bota, b)
    assert "Место:" not in before, "до посадки машина — не место человека: сигнал без места"
    with Session(engine) as s:
        bk = s.get(Booking, b)
        bk.status = BookingStatus.onboard
        bk.onboard_at = utcnow()
        s.add(bk)
        s.commit()
    after = _sos_note(client, bota, b)
    m = re.search(r"pt=([\d.]+),([\d.]+)", after)
    assert m and abs(float(m.group(2)) - URGAZA[0]) < 1e-4, after


def test_passenger_b_channel_gets_the_car_from_a(client, user_factory, redis_on, monkeypatch):
    """Карта в приложении у пассажира B: водитель открыл поездку Айгуль, а машина у B всё равно едет."""
    monkeypatch.setattr(location, "CAR_FEED_SEC", 0.05)
    drv, (_aigul, bota), _ride, (a, b) = _trip(client, user_factory, "chanb", places=("Сибай", "Сибай"),
                                               depart_in=timedelta(minutes=20))
    _driver_at(NEAR_URGAZA, a)
    with client.websocket_connect(f"/ws/trip/{b}/location") as ear:
        ear.send_text(json.dumps({"type": "auth", "token": bota["token"]}))
        got = json.loads(ear.receive_text())
    assert got["type"] == "loc" and got["role"] == "driver" and abs(got["lat"] - NEAR_URGAZA[0]) < 1e-6, got
    assert isinstance(got["ts"], int)


def test_passenger_b_step_respects_her_rules(client, user_factory, redis_on):
    """Шаг потока машины: вне окна B — ничего; отмена B — ничего и канал «кончился»."""
    drv, (_aigul, zarina), _ride, (a, b) = _trip(client, user_factory, "chanrules", places=("Сибай", "Бурибай"),
                                                 depart_in=timedelta(minutes=20))
    _driver_at(SIBAY, a)
    assert location._машина_для(b, location._Пора(b, zarina["id"])) is None, "окно Зарины ещё закрыто"
    _drv, (_aigul2, bota), _ride2, (a2, b2) = _trip(client, user_factory, "chanrules2", places=("Сибай", "Сибай"),
                                                    depart_in=timedelta(minutes=20))
    _driver_at(SIBAY, a2)
    пора = location._Пора(b2, bota["id"])
    assert location._машина_для(b2, пора) is not None
    assert client.post(f"/bookings/{b2}/cancel", headers=bota["auth"]).status_code == 200
    assert location._машина_для(b2, пора) is None and пора.кончилась is True


# ── Точка водителя по рейсу (приёмка PR186, A1; наружу — с 24.10.2026, только по правилам брони) ──

def _driver_sends_in_closed_window(client, drv, bid, ride_id, point, redis):
    """Водитель открыл поездку брони `bid`, чьё окно ещё закрыто, и прислал точку. В кэш брони она
    не ложится; ждём, пока ляжет во внутренний ключ рейса (кадр обработан)."""
    key = f"livepos:ride_driver:{ride_id}"
    with client.websocket_connect(f"/ws/trip/{bid}/location") as mouth:
        mouth.send_text(json.dumps({"type": "auth", "token": drv["token"]}))
        mouth.send_text(json.dumps({"type": "loc", "lat": point[0], "lng": point[1], "ts": 1}))
        deadline = utcnow() + timedelta(seconds=5)
        while redis.get(key) is None and utcnow() < deadline:
            time.sleep(0.01)
    assert redis.get(key) is not None, "кадр водителя не дошёл до ключа рейса"
    assert livepos.livepos_get("booking", bid) is None, "окно брони закрыто — в её кэш не пишем"


def test_driver_in_a_closed_window_is_still_checked_for_a_stop(client, user_factory, redis_on, clock):
    """Водитель открыл поездку Зарины (Бурибай, окно — за 2 минуты до выезда) и стоит у пина в Сибае.
    «Подъезжаю» для Ургазы (41 км) — отказ: проверка видит машину по внутренней точке рейса.
    Раньше точек не было ни в одном кэше, и слово водителя проходило без проверки."""
    drv, (_zarina, _ur), ride, (b_far, b_ur) = _trip(client, user_factory, "ridekey", places=("Бурибай", "Ургаза"),
                                                     depart_in=timedelta(minutes=30))
    _driver_sends_in_closed_window(client, drv, b_far, ride["id"], SIBAY, redis_on)
    clock(_depart(ride["id"]) - timedelta(minutes=1))    # «Подъезжаю» к Ургазе уже можно по времени
    far = _arriving(client, drv, b_ur)
    assert far.status_code == 409, far.text
    d = far.json()["detail"]
    assert d["code"] == "arriving_too_far" and abs(d["distance_km"] - KM_TO_URGAZA) < 1.0, d


def test_driver_at_a_stop_cannot_arrive_at_the_start(client, user_factory, redis_on, clock):
    """Водитель в Ургазе с открытой поездкой Зарины (окно закрыто) жмёт «Подъезжаю» пассажирке в
    начале маршрута — отказ «далеко от места подачи»: она не выйдет на мороз за 40 км до машины."""
    drv, (_aigul, _zarina), ride, (a, b) = _trip(client, user_factory, "ridekeystart", places=("Сибай", "Бурибай"),
                                                 depart_in=timedelta(minutes=30))
    _driver_sends_in_closed_window(client, drv, b, ride["id"], URGAZA, redis_on)
    clock(_depart(ride["id"]) - timedelta(minutes=1))
    r = _arriving(client, drv, a)
    assert r.status_code == 409 and "км" in r.json()["detail"]["ru"], r.text
    assert _booking(a).arriving_at is None, "отказ ничего не записал"


def test_the_ride_point_reaches_only_a_live_booking_by_its_rules(client, user_factory, redis_on):
    """Точка рейса наружу — только через обёртку и по правилам брони (24.10.2026, задача «машину видит
    только ОДИН пассажир»; заменяет правило A1 «наружу никогда»). Водитель открыл поездку Зарины (окно
    закрыто), его точка лежит только в ключе рейса. Раньше Айгуль с открытым окном не видела машину
    вовсе. Теперь видит в приложении, её близкие — когда машина поехала (P2-2). Зарине — ничего."""
    drv, (aigul, zarina), ride, (a, b) = _trip(client, user_factory, "ridekeylive", places=("Сибай", "Бурибай"),
                                               depart_in=timedelta(minutes=30))
    token_a = _share(client, aigul, a, "+79170000951")
    token_b = _share(client, zarina, b, "+79170000952")
    _driver_sends_in_closed_window(client, drv, b, ride["id"], SIBAY, redis_on)
    with Session(engine) as s:
        r = s.get(Ride, ride["id"])
        assert [p["booking_id"] for p in livepos.driver_points_for_ride(s, r, with_ride_point=True)] == [None]
        assert livepos.driver_points_for_ride(s, r) == [], "без флага — по-прежнему только кэши броней"
        ak = s.get(Booking, a)
        assert trip_is_live(ak, r) is True
        got = location.driver_point_for_booking(s, ak, r)
        assert got is not None and abs(got["lat"] - SIBAY[0]) < 1e-6, got
        assert location.driver_point_for_booking(s, s.get(Booking, b), r) is None, "окно Зарины закрыто"
    assert location._машина_для(a, location._Пора(a, aigul["id"])) is not None, "машина в приложении Айгуль"
    assert location._машина_для(b, location._Пора(b, zarina["id"])) is None
    assert client.get(f"/t/{token_a}/state.json").json()["car"] is None, "водитель ещё не выехал (P2-2)"
    _departed(client, drv, a)
    assert client.get(f"/t/{token_a}/state.json").json()["car"] is not None
    assert client.get(f"/t/{token_b}/state.json").json()["car"] is None, "окно Зарины закрыто"


def test_no_ride_point_before_the_ride_is_live(client, user_factory, redis_on, gate_seen):
    """За два часа до выезда рейс ещё не идёт — водителя дома не отслеживаем даже для проверки."""
    drv, (_aigul,), ride, (a,) = _trip(client, user_factory, "ridekeyearly", places=("Сибай",),
                                       depart_in=timedelta(hours=2))
    with client.websocket_connect(f"/ws/trip/{a}/location") as mouth:
        mouth.send_text(json.dumps({"type": "auth", "token": drv["token"]}))
        before = gate_seen["n"]
        mouth.send_text(json.dumps({"type": "loc", "lat": SIBAY[0], "lng": SIBAY[1], "ts": 1}))
        wait_gate(gate_seen, before)
    assert redis_on.get(f"livepos:ride_driver:{ride['id']}") is None
    assert livepos.livepos_get("booking", a) is None


def test_passenger_frame_never_reaches_the_ride_point(client, user_factory, redis_on, gate_seen):
    """Кадр пассажирки (её окно открыто) во внутренний ключ рейса не пишется: только водитель."""
    _drv, (aigul,), ride, (a,) = _trip(client, user_factory, "ridekeypax", places=("Сибай",),
                                       depart_in=timedelta(minutes=20))
    with client.websocket_connect(f"/ws/trip/{a}/location") as mouth:
        mouth.send_text(json.dumps({"type": "auth", "token": aigul["token"]}))
        before = gate_seen["n"]
        mouth.send_text(json.dumps({"type": "loc", "lat": 52.0, "lng": 58.0, "ts": 1}))
        wait_gate(gate_seen, before)
    assert redis_on.get(f"livepos:ride_driver:{ride['id']}") is None

# ── Сторожа A1 при закрытом окне брони (повторная проверка безопасности PR186, 23.10.2026) ──


def test_g1_passenger_in_closed_window_never_writes_the_ride_key(client, user_factory, redis_on, gate_seen):
    """Сторож A1 для ветки «окно закрыто»: Зарина (Бурибай, окно — за 2 мин до выезда), рейс уже идёт
    (выезд через 20 минут). Её кадр не должен лечь во внутренний ключ рейса — иначе её точка стала бы
    «машиной» для «Подъезжаю», а `distance_km` в отказе сказал бы водителю, где она."""
    _drv, (zarina,), ride, (b,) = _trip(client, user_factory, "g1", places=("Бурибай",),
                                        depart_in=timedelta(minutes=20))
    with client.websocket_connect(f"/ws/trip/{b}/location") as mouth:
        mouth.send_text(json.dumps({"type": "auth", "token": zarina["token"]}))
        before = gate_seen["n"]
        mouth.send_text(json.dumps({"type": "loc", "lat": 52.0, "lng": 58.0, "ts": 1}))
        wait_gate(gate_seen, before)
    assert redis_on.get(f"livepos:ride_driver:{ride['id']}") is None, "кадр пассажирки лёг в ключ рейса"


def test_g2_ride_key_respects_the_anti_teleport_in_a_closed_window(client, user_factory, redis_on, gate_seen):
    """Сторож A1: в ветке «окно брони закрыто» ключ рейса пишется только после анти-телепорта."""
    drv, (_zarina,), ride, (b,) = _trip(client, user_factory, "g2", places=("Бурибай",),
                                        depart_in=timedelta(minutes=20))
    key = f"livepos:ride_driver:{ride['id']}"
    with client.websocket_connect(f"/ws/trip/{b}/location") as mouth:
        mouth.send_text(json.dumps({"type": "auth", "token": drv["token"]}))
        before = gate_seen["n"]
        mouth.send_text(json.dumps({"type": "loc", "lat": SIBAY[0], "lng": SIBAY[1], "ts": 1}))
        wait_gate(gate_seen, before)
        mouth.send_text(json.dumps({"type": "loc", "lat": 54.735, "lng": 55.958, "ts": 2}))   # Уфа за секунду
    pos = json.loads(redis_on.get(key))
    assert abs(pos["lat"] - SIBAY[0]) < 1e-6, f"телепорт лёг в ключ рейса: {pos}"
