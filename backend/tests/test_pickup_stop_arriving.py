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
    clock(opens - timedelta(seconds=1))
    early = _arriving(client, drv, b_stop)
    assert early.status_code == 409, early.text
    d = early.json()["detail"]
    assert d["code"] == "arriving_too_early"
    from datetime import datetime
    got = datetime.fromisoformat(d["arriving_opens_at"])
    assert abs((got - opens).total_seconds()) < 60, (got, opens)
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
    state = client.get(f"/t/{token}/state.json").json()
    assert state["status"] == "to_pickup" and state["car"] is not None, state
    assert abs(state["car"]["lat"] - SIBAY[0]) < 1e-6
    assert livepos.livepos_get("booking", b) is None, "в кэш брони B ничего не писали"


def test_family_of_b_prefers_b_own_channel_point(client, user_factory, redis_on):
    """Канал брони B жив — её близкие видят ровно его точку (как раньше), а не чужую посвежее."""
    drv, (_aigul, bota), _ride, (a, b) = _trip(client, user_factory, "shareown", places=("Сибай", "Сибай"),
                                               depart_in=timedelta(minutes=20))
    token = _share(client, bota, b, "+79170000946")
    _driver_at(SIBAY, b)
    _driver_at(NEAR_URGAZA, a)                  # позже и в другом месте (второе устройство)
    state = client.get(f"/t/{token}/state.json").json()
    assert state["car"] is not None and abs(state["car"]["lat"] - SIBAY[0]) < 1e-6, state


def test_family_of_b_sees_nothing_before_b_window(client, user_factory, redis_on):
    drv, (_aigul, zarina), _ride, (a, b) = _trip(client, user_factory, "shareearly", places=("Сибай", "Бурибай"),
                                                 depart_in=timedelta(minutes=20))
    token = _share(client, zarina, b, "+79170000942")
    _driver_at(SIBAY, a)
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
