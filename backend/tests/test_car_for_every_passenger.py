"""Машину видят пассажиры рейса с открытым окном, а не только тот, чью поездку открыл водитель
(24.10.2026; находка проверки PR170 и P2-2 приёмки PR179). Открыто Д2 (клиент): «Завершить» по одной
брони гасит GPS водителя — тогда машина пропадает у остальных (`docs/tasks.md`).

Что было. Водитель открывает поездку ОДНОЙ брони («Едут с тобой» → бронь). Если у этой брони окно
точки закрыто (Зарина садится в Бурибае, её окно — за 2 минуты до выезда), его точка лежала только во
внутреннем ключе рейса. Айгуль и Бота, у которых окно открыто, машину не видели ни в приложении, ни у
близких по ссылке, ни в SOS.

Что стало (план прошёл проверку безопасности, «ПРИНЯТО с условиями»):
* ключ рейса — последний запас обёртки `location.driver_point_for_booking`, и только по правилам
  брони: своя точка → точки других броней рейса → ключ рейса;
* не севший пассажир получает точки через чужие брони и ключ рейса только до срока «Не пришёл» +
  15 минут (`visibility.pre_boarding_until`); пересылка по своему каналу брони — как раньше;
* ссылка близким до посадки показывает машину, только когда машина поехала
  (`visibility.family_car_open`): «Выехал»/«Подъезжаю» по живой брони рейса или посадка.
Пассажиры точки друг друга не получают; отменённая, заблокированная и чужая бронь — ничего.
"""
from __future__ import annotations

import json
import re
import time
from datetime import timedelta

import fakeredis
import pytest
from sqlmodel import Session, select
from starlette.websockets import WebSocketDisconnect

from app import instant_service as isv
from app import livepos
from app.db import engine
from app.models import Block, Booking, BookingStatus, Ride, SosEvent, TrustedContact, UserRole
from app.routers import bookings as bookings_mod
from app.routers import location
from app.timeutil import utcnow
from app.visibility import family_car_open, pre_boarding_until, ride_started

from test_api import wait_gate

pytestmark = pytest.mark.timeout(30)

SIBAY = (52.716, 58.664)        # пин подачи в начале маршрута
ON_ROAD = (52.55, 58.64)        # машина на трассе
HOME = (52.731, 58.702)         # дом водителя: второе устройство (планшет)
STOPS = "Ургаза | Бурибай"
_phones = iter(range(8_100, 9_000))


@pytest.fixture
def redis_on():
    r = fakeredis.FakeStrictRedis(decode_responses=True)
    isv._redis_override = r
    yield r
    isv._redis_override = None


@pytest.fixture
def fast_feed(monkeypatch):
    """Поток машины в канал пассажира — не раз в 5 с, а сразу."""
    monkeypatch.setattr(location, "CAR_FEED_SEC", 0.05)


@pytest.fixture
def quiet_sos(monkeypatch):
    monkeypatch.setattr("app.routers.safety._send_sos_sms", lambda phones, text: None)
    monkeypatch.setattr("app.routers.safety.notify_admin_telegram", lambda *a, **kw: True)


def _trip(client, user_factory, tag, places, *, depart_in=timedelta(minutes=20), confirm=None):
    """Рейс Сибай → Акъяр с остановками и брони по местам `places`; подтверждены все или `confirm`."""
    drv = user_factory(f"CEP Driver {tag}", role=UserRole.driver)
    r = client.post("/rides", headers=drv["auth"], json={
        "from_city": "Сибай", "to_city": "Акъяр", "comment": f"cep-{tag}", "waypoints": STOPS,
        "depart_at": (utcnow() + depart_in).isoformat() + "+00:00",
        "seats_total": len(places), "price": 700,
        "pickup_lat": SIBAY[0], "pickup_lng": SIBAY[1], "pickup": "Автовокзал"})
    assert r.status_code == 200, r.text
    ride = r.json()
    paxes, bids = [], []
    for i, place in enumerate(places):
        pax = user_factory(f"CEP Pax {tag} {i}")
        b = client.post("/bookings", headers=pax["auth"], json={"ride_id": ride["id"], "seats": 1, "pickup_stop": place})
        assert b.status_code == 200, b.text
        if confirm is None or i in confirm:
            assert client.post(f"/bookings/{b.json()['id']}/confirm", headers=drv["auth"]).status_code == 200
        paxes.append(pax)
        bids.append(b.json()["id"])
    return drv, paxes, ride, bids


def _status(client, drv, bid, status):
    r = client.post(f"/bookings/{bid}/driver-status", headers=drv["auth"], json={"status": status})
    assert r.status_code == 200, r.text


def _put(redis, key, point, age_sec):
    redis.set(key, json.dumps({"lat": point[0], "lng": point[1], "bearing": None,
                               "ts": (utcnow() - timedelta(seconds=age_sec)).isoformat()}),
              ex=livepos.LIVEPOS_TTL_SEC)


def _booking_point(redis, bid, point, age_sec=1):
    """Точка водителя, записанная каналом брони `bid` (как это делает `location.py`, роль driver)."""
    _put(redis, f"livepos:booking:{bid}", point, age_sec)


def _ride_point(redis, ride_id, point, age_sec=1):
    """Точка водителя только в ключе рейса — канал брони с закрытым окном."""
    _put(redis, f"livepos:ride_driver:{ride_id}", point, age_sec)


def _driver_frame(client, drv, bid, ride_id, point, redis):
    """Водитель открыл поездку брони `bid` и прислал кадр; ждём, пока кадр ляжет в ключ рейса."""
    key = f"livepos:ride_driver:{ride_id}"
    redis.delete(key)
    with client.websocket_connect(f"/ws/trip/{bid}/location") as mouth:
        mouth.send_text(json.dumps({"type": "auth", "token": drv["token"]}))
        mouth.send_text(json.dumps({"type": "loc", "lat": point[0], "lng": point[1], "ts": 1}))
        deadline = time.monotonic() + 5
        while redis.get(key) is None and time.monotonic() < deadline:
            time.sleep(0.01)
    assert redis.get(key) is not None, "кадр водителя не дошёл до сервера"


def _point_for(bid, now=None):
    with Session(engine) as s:
        b = s.get(Booking, bid)
        return location.driver_point_for_booking(s, b, s.get(Ride, b.ride_id), now)


def _app_feed(bid, pax_id):
    """Шаг потока машины в приложение пассажира (`_машина_пассажиру`) — по его собственному `_Пора`."""
    return location._машина_для(bid, location._Пора(bid, pax_id))


def _ear(client, bid, pax) -> dict:
    """Пассажир открыл экран поездки: первый кадр, пришедший в его канал."""
    with client.websocket_connect(f"/ws/trip/{bid}/location") as ear:
        ear.send_text(json.dumps({"type": "auth", "token": pax["token"]}))
        return json.loads(ear.receive_text())


def _share(client, pax, bid) -> str:
    c = client.post("/trusted-contacts", headers=pax["auth"],
                    json={"name": "Мама", "relation": "мама", "phone": f"+7917000{next(_phones):04d}"})
    assert c.status_code == 200, c.text
    r = client.post(f"/bookings/{bid}/share", headers=pax["auth"], json={"contact_id": c.json()["id"]})
    assert r.status_code == 200, r.text
    return r.json()["token"]


def _family(client, token) -> dict:
    r = client.get(f"/t/{token}/state.json")
    assert r.status_code == 200, r.text
    return r.json()


def _edit(model, obj_id, **fields):
    with Session(engine) as s:
        obj = s.get(model, obj_id)
        for k, v in fields.items():
            setattr(obj, k, v)
        s.add(obj)
        s.commit()


def _sos_note(client, pax, bid) -> str:
    with Session(engine) as s:
        s.add(TrustedContact(user_id=pax["id"], name="Сестра", phone=f"+7917{pax['id']:07d}"[:12]))
        s.commit()
    r = client.post("/sos", headers=pax["auth"], json={"category": "other", "booking_id": bid, "note": "тест"})
    assert r.status_code == 200, r.text
    with Session(engine) as s:
        ev = s.exec(select(SosEvent).where(SosEvent.user_id == pax["id"]).order_by(SosEvent.id.desc())).first()
        return ev.note or ""


# ── Главный сценарий ──

def test_passengers_with_an_open_window_see_the_car_while_the_driver_holds_another_trip(
        client, user_factory, redis_on, fast_feed, quiet_sos):
    drv, (zarina, aigul, bota), ride, (z, a, b) = _trip(client, user_factory, "main", ("Бурибай", "Сибай", "Сибай"))
    tokens = {bid: _share(client, pax, bid) for pax, bid in ((zarina, z), (aigul, a), (bota, b))}
    # Водитель открыл поездку Зарины: её окно закрыто, точка ложится только в ключ рейса.
    _driver_frame(client, drv, z, ride["id"], SIBAY, redis_on)
    assert livepos.livepos_get("booking", z) is None
    # Айгуль и Бота видят машину в приложении — по своему каналу, хотя водитель держит чужую бронь.
    for pax, bid in ((aigul, a), (bota, b)):
        got = _ear(client, bid, pax)
        assert got["type"] == "loc" and got["role"] == "driver" and got["lat"] == pytest.approx(SIBAY[0]), got
    assert _app_feed(z, zarina["id"]) is None, "окно Зарины закрыто — ей ничего"
    # Близкие — когда машина поехала (P2-2): «Выехал» на экране брони Зарины.
    for bid in (a, b):
        assert _family(client, tokens[bid])["car"] is None, "водитель ещё ничего не нажал"
    _status(client, drv, z, "departed")
    for bid in (a, b):
        st = _family(client, tokens[bid])
        assert st["status"] == "to_pickup" and st["car"]["lat"] == pytest.approx(SIBAY[0]), st
    st = _family(client, tokens[z])
    assert st["status"] == "wait" and st["car"] is None, "«Выехал» окно остановки Зарины не открывает"
    # Бота села — её SOS без своего GPS берёт место машины.
    _status(client, drv, b, "boarded")
    note = _sos_note(client, bota, b)
    m = re.search(r"pt=([\d.]+),([\d.]+)", note)
    assert m and float(m.group(2)) == pytest.approx(SIBAY[0], abs=1e-4), note


def test_passengers_never_get_each_others_points(client, user_factory, redis_on, gate_seen):
    drv, (aigul, bota), _ride, (a, b) = _trip(client, user_factory, "paxpax", ("Сибай", "Сибай"))
    token_b = _share(client, bota, b)
    _status(client, drv, a, "departed")
    with client.websocket_connect(f"/ws/trip/{a}/location") as mouth:
        mouth.send_text(json.dumps({"type": "auth", "token": aigul["token"]}))
        before = gate_seen["n"]
        mouth.send_text(json.dumps({"type": "loc", "lat": ON_ROAD[0], "lng": ON_ROAD[1], "ts": 1}))
        wait_gate(gate_seen, before)
    assert redis_on.keys("livepos:*") == [], "точка пассажирки легла в кэш или в ключ рейса"
    assert _point_for(b) is None
    assert _app_feed(b, bota["id"]) is None
    assert _family(client, token_b)["car"] is None


def test_passenger_frames_on_a_closed_window_channel_never_reach_any_key(client, user_factory, redis_on, gate_seen):
    """Кадр Зарины (её окно закрыто) не ложится ни в кэш брони, ни в ключ рейса — Айгуль его не получит."""
    _drv, (zarina, _aigul), _ride, (z, a) = _trip(client, user_factory, "paxclosed", ("Бурибай", "Сибай"))
    with client.websocket_connect(f"/ws/trip/{z}/location") as mouth:
        mouth.send_text(json.dumps({"type": "auth", "token": zarina["token"]}))
        before = gate_seen["n"]
        mouth.send_text(json.dumps({"type": "loc", "lat": ON_ROAD[0], "lng": ON_ROAD[1], "ts": 1}))
        wait_gate(gate_seen, before)
    assert redis_on.keys("livepos:*") == []
    assert _point_for(a) is None


def test_her_block_in_transit_with_only_the_ride_point(client, user_factory, redis_on):
    """Q16 при одном ключе рейса: Бота «в пути» заблокировала водителя после посадки — её близкие
    машину видят, в её приложение машина не идёт (как и при кэше брони)."""
    drv, (_zarina, bota), ride, (z, b) = _trip(client, user_factory, "q16ride", ("Бурибай", "Сибай"),
                                               depart_in=timedelta(minutes=10))
    token = _share(client, bota, b)
    _status(client, drv, b, "boarded")
    with Session(engine) as s:
        s.add(Block(user_id=bota["id"], blocked_user_id=drv["id"], created_at=utcnow() + timedelta(seconds=1)))
        s.commit()
    _driver_frame(client, drv, z, ride["id"], ON_ROAD, redis_on)   # водитель держит поездку Зарины
    st = _family(client, token)
    assert st["status"] == "onboard" and st["car"] is not None, st
    assert _app_feed(b, bota["id"]) is None, "в её приложение — нет"


# ── Порядок выбора точки (находка 4) ──

def test_a_booking_point_beats_a_fresher_ride_point(client, user_factory, redis_on):
    """Телефон в машине на брони Айгуль (60 с назад) против планшета дома на закрытой брони Зарины
    (ключ рейса, 5 с назад): Бота видит машину, а не дом водителя."""
    _drv, _paxes, ride, (a, b, _z) = _trip(client, user_factory, "order", ("Сибай", "Сибай", "Бурибай"))
    _booking_point(redis_on, a, ON_ROAD, 60)
    _ride_point(redis_on, ride["id"], HOME, 5)
    got = _point_for(b)
    assert (got["lat"], got["lng"]) == ON_ROAD, got
    _booking_point(redis_on, b, SIBAY, 30)
    got = _point_for(b)
    assert (got["lat"], got["lng"]) == SIBAY, "своя точка брони — главнее всех"
    redis_on.delete(f"livepos:booking:{a}", f"livepos:booking:{b}")
    got = _point_for(b)
    assert (got["lat"], got["lng"]) == HOME, "только ключ рейса — он (остаточный риск второго устройства)"


# ── В кэше только ключ рейса, правило брони нарушено — пусто (находка 4) ──

def _break_pending(client, drv, victim, bid, ride):
    pass   # бронь создана без подтверждения


def _break_cancelled(client, drv, victim, bid, ride):
    assert client.post(f"/bookings/{bid}/cancel", headers=victim["auth"]).status_code == 200


def _break_no_show(client, drv, victim, bid, ride):
    _edit(Booking, bid, status=BookingStatus.cancelled, no_show=True, cancel_reason="no_show",
          cancelled_by=drv["id"], cancelled_at=utcnow())


def _break_her_block(client, drv, victim, bid, ride):
    with Session(engine) as s:
        s.add(Block(user_id=victim["id"], blocked_user_id=drv["id"]))
        s.commit()


def _break_his_block(client, drv, victim, bid, ride):
    with Session(engine) as s:
        s.add(Block(user_id=drv["id"], blocked_user_id=victim["id"]))
        s.commit()


def _break_his_block_beats_her_later(client, drv, victim, bid, ride):
    now = utcnow()
    with Session(engine) as s:
        s.add(Block(user_id=drv["id"], blocked_user_id=victim["id"], created_at=now - timedelta(minutes=10)))
        bk = s.get(Booking, bid)
        bk.status = BookingStatus.onboard
        bk.onboard_at = now - timedelta(minutes=5)
        s.add(bk)
        s.add(Block(user_id=victim["id"], blocked_user_id=drv["id"], created_at=now - timedelta(minutes=1)))
        s.commit()


@pytest.mark.parametrize("place,confirmed,breaker", [
    ("Сибай", False, _break_pending),
    ("Сибай", True, _break_cancelled),
    ("Сибай", True, _break_no_show),
    ("Бурибай", True, None),                       # окно Зарины ещё закрыто (87 км, за 2 мин до выезда)
    ("Сибай", True, _break_her_block),
    ("Сибай", True, _break_his_block),
    ("Сибай", True, _break_his_block_beats_her_later),
], ids=["pending", "cancelled", "no_show", "stop_closed", "her_block", "his_block", "his_beats_her_later"])
def test_only_the_ride_point_and_a_broken_rule_gives_nothing(client, user_factory, redis_on, place, confirmed, breaker):
    drv, (aigul, victim), ride, (a, v) = _trip(client, user_factory, f"rules-{place}-{confirmed}-{getattr(breaker, '__name__', 'none')}",
                                               ("Сибай", place), confirm={0, 1} if confirmed else {0})
    _status(client, drv, a, "boarded")              # контроль: Айгуль в машине — ей ключ рейса отдаётся
    _ride_point(redis_on, ride["id"], ON_ROAD)
    assert _point_for(a) is not None, "контроль: без нарушений ключ рейса доходит"
    if breaker is not None:
        breaker(client, drv, victim, v, ride)
    assert _point_for(v) is None
    assert _app_feed(v, victim["id"]) is None


def test_the_stop_window_closed_after_the_no_show_deadline_gives_nothing(client, user_factory, redis_on, monkeypatch):
    drv, (aigul, zarina), ride, (a, z) = _trip(client, user_factory, "stopover", ("Сибай", "Ургаза"),
                                               depart_in=timedelta(minutes=-5))
    _status(client, drv, a, "boarded")
    _edit(Booking, z, arriving_at=utcnow())
    _ride_point(redis_on, ride["id"], ON_ROAD)
    assert _point_for(z) is not None, "окно Ургазы открыто по «Подъезжаю»"
    with Session(engine) as s:
        bk = s.get(Booking, z)
        closes = bookings_mod.no_show_available_at(s.get(Ride, bk.ride_id), bk) + bookings_mod.LIVE_AFTER_NO_SHOW
    monkeypatch.setattr("app.timeutil.utcnow", lambda: closes + timedelta(seconds=1))
    assert _point_for(z) is None, "не села к сроку «Не пришёл» + 15 минут — машины нет"
    assert _point_for(a) is not None, "Айгуль в машине — видит дальше"


# ── Не севший пассажир: чужие точки только до срока (находка 1, вариант «а») ──

def test_a_passenger_who_never_boarded_loses_foreign_points_after_the_deadline(client, user_factory, redis_on, monkeypatch):
    drv, (aigul, bota), ride, (a, b) = _trip(client, user_factory, "never", ("Сибай", "Сибай"))
    _status(client, drv, a, "boarded")              # Айгуль в машине, Бота так и не вышла
    _edit(Ride, ride["id"], depart_at=utcnow() - timedelta(hours=3))
    _booking_point(redis_on, a, ON_ROAD)
    _ride_point(redis_on, ride["id"], ON_ROAD)
    assert _point_for(a) is not None, "Айгуль в машине — видит"
    assert _point_for(b) is None, "выезд 3 ч назад, Бота не села — ни чужая точка, ни ключ рейса"
    assert _app_feed(b, bota["id"]) is None
    # Своя пересылка — как раньше: водитель открыл поездку Боты, её канал получает его кадр.
    with client.websocket_connect(f"/ws/trip/{b}/location") as ear:
        ear.send_text(json.dumps({"type": "auth", "token": bota["token"]}))
        with client.websocket_connect(f"/ws/trip/{b}/location") as mouth:
            mouth.send_text(json.dumps({"type": "auth", "token": drv["token"]}))
            mouth.send_text(json.dumps({"type": "loc", "lat": SIBAY[0], "lng": SIBAY[1], "ts": 7}))
            got = json.loads(ear.receive_text())
    assert got["role"] == "driver" and got["lat"] == pytest.approx(SIBAY[0]), got
    assert _point_for(b)["lat"] == pytest.approx(SIBAY[0]), "своя точка брони — как раньше"


def test_the_deadline_is_closed_exactly_at_the_border(client, user_factory, redis_on, monkeypatch):
    drv, (_aigul, _bota), ride, (a, b) = _trip(client, user_factory, "border", ("Сибай", "Сибай"))
    _status(client, drv, a, "departed")
    _booking_point(redis_on, a, ON_ROAD)
    with Session(engine) as s:
        bk = s.get(Booking, b)
        r = s.get(Ride, bk.ride_id)
        until = pre_boarding_until(bk, r)
        assert until == r.depart_at + bookings_mod.NO_SHOW_WAIT + bookings_mod.LIVE_AFTER_NO_SHOW
    monkeypatch.setattr("app.timeutil.utcnow", lambda: until - timedelta(seconds=1))
    assert _point_for(b) is not None
    with Session(engine) as s:
        bk = s.get(Booking, b)
        assert family_car_open(s, bk, s.get(Ride, bk.ride_id)) is True
    monkeypatch.setattr("app.timeutil.utcnow", lambda: until)
    assert _point_for(b) is None, "на границе — уже закрыто"
    with Session(engine) as s:
        bk = s.get(Booking, b)
        assert family_car_open(s, bk, s.get(Ride, bk.ride_id)) is False


# ── Ссылка близким: только когда машина поехала (P2-2) ──

def test_the_family_sees_no_parked_car_but_the_passenger_app_does(client, user_factory, redis_on):
    """За 20 минут до выезда, водитель ничего не нажимал, машина стоит у дома (пульс 45 с)."""
    _drv, (_aigul, bota), ride, (a, b) = _trip(client, user_factory, "parked", ("Сибай", "Сибай"))
    token = _share(client, bota, b)
    _booking_point(redis_on, a, HOME)
    st = _family(client, token)
    assert st["status"] == "wait" and st["car"] is None, st
    assert _app_feed(b, bota["id"]) is not None, "в приложении пассажира машина есть, как раньше"


def test_departed_on_a_dead_or_unconfirmed_booking_does_not_open_the_family_link(client, user_factory, redis_on):
    drv, paxes, ride, (b, pending, cancelled, no_show) = _trip(
        client, user_factory, "deadphase", ("Сибай", "Сибай", "Сибай", "Сибай"), confirm={0, 2, 3})
    token = _share(client, paxes[0], b)
    _booking_point(redis_on, b, ON_ROAD)
    _status(client, drv, pending, "departed")       # сервер принимает «Выехал» и по неподтверждённой
    _status(client, drv, cancelled, "departed")
    assert client.post(f"/bookings/{cancelled}/cancel", headers=paxes[2]["auth"]).status_code == 200
    _status(client, drv, no_show, "departed")
    _edit(Booking, no_show, status=BookingStatus.cancelled, no_show=True, cancel_reason="no_show",
          cancelled_by=drv["id"], cancelled_at=utcnow())
    with Session(engine) as s:
        assert ride_started(s, s.get(Ride, ride["id"])) is False
    assert _family(client, token)["car"] is None
    _status(client, drv, b, "departed")
    assert _family(client, token)["car"] is not None, "контроль: «Выехал» по живой брони открывает"


def test_boarded_then_undo_closes_again_and_a_transit_cancel_counts(client, user_factory, redis_on):
    drv, (_aigul, bota), ride, (a, b) = _trip(client, user_factory, "undo", ("Сибай", "Сибай"))
    token = _share(client, bota, b)
    _booking_point(redis_on, a, ON_ROAD)
    _ride_point(redis_on, ride["id"], ON_ROAD)      # после отмены брони Айгуль её кэш не читается
    _status(client, drv, a, "boarded")
    assert _family(client, token)["car"] is not None, "Айгуль села — машина поехала"
    assert client.post(f"/bookings/{a}/boarded/undo", headers=drv["auth"]).status_code == 200
    assert _family(client, token)["car"] is None, "«Отменить» посадку — признак ушёл"
    _status(client, drv, a, "boarded")
    _edit(Booking, a, status=BookingStatus.cancelled, cancelled_by=drv["id"], cancelled_at=utcnow())
    with Session(engine) as s:
        assert ride_started(s, s.get(Ride, ride["id"])) is True, "отменённая в пути ехала в этой машине"
    assert _family(client, token)["car"] is not None


def test_the_moving_car_does_not_open_a_closed_stop_window_for_the_family(client, user_factory, redis_on):
    drv, (_aigul, zarina), _ride, (a, z) = _trip(client, user_factory, "stopfam", ("Сибай", "Бурибай"))
    token = _share(client, zarina, z)
    _booking_point(redis_on, a, ON_ROAD)
    _status(client, drv, a, "departed")
    st = _family(client, token)
    assert st["status"] == "wait" and st["car"] is None, st
    _status(client, drv, a, "boarded")
    st = _family(client, token)
    assert st["status"] == "wait" and st["car"] is None, "«Сел» по Айгуль окно Зарины не открывает"


def test_before_boarding_the_family_loses_the_car_after_the_deadline(client, user_factory, redis_on):
    drv, (_aigul, bota), ride, (a, b) = _trip(client, user_factory, "famdeadline", ("Сибай", "Сибай"))
    token = _share(client, bota, b)
    _booking_point(redis_on, a, SIBAY)              # машина у пина
    _status(client, drv, a, "departed")
    assert _family(client, token)["car"] is not None
    _edit(Ride, ride["id"], depart_at=utcnow() - timedelta(minutes=21))
    st = _family(client, token)
    assert st["status"] == "wait" and st["car"] is None, "выезд + 20 мин без «Подъезжаю» — машины нет"
    _status(client, drv, b, "arriving")             # первое «Подъезжаю» по Боте продлевает срок
    assert _family(client, token)["car"] is not None
    _edit(Booking, b, arriving_at=utcnow() - timedelta(hours=1))
    assert _family(client, token)["car"] is None
    _status(client, drv, b, "boarded")
    st = _family(client, token)
    assert st["status"] == "onboard" and st["car"] is not None, "в машине — без срока"


# ── Чужая бронь ──

def test_a_foreign_booking_gets_nothing(client, user_factory, redis_on, fast_feed):
    drv, (aigul,), ride, (a,) = _trip(client, user_factory, "own", ("Сибай",))
    _other_drv, (stranger,), _other, (x,) = _trip(client, user_factory, "foreign", ("Сибай",))
    _ride_point(redis_on, ride["id"], ON_ROAD)
    _booking_point(redis_on, a, ON_ROAD)
    assert _point_for(a) is not None
    assert _point_for(x) is None, "точка водителя другого рейса"
    assert _app_feed(x, stranger["id"]) is None
    with pytest.raises((WebSocketDisconnect, Exception)):
        with client.websocket_connect(f"/ws/trip/{a}/location") as ws:
            ws.send_text(json.dumps({"type": "auth", "token": stranger["token"]}))
            ws.receive_text()


def test_the_same_drivers_other_ride_gets_nothing(client, user_factory, redis_on):
    """Пассажир рейса X того же водителя не получает ключ его рейса Y — ни в приложении, ни у близких."""
    drv, (zarina,), ride_y, (y,) = _trip(client, user_factory, "sameY", ("Бурибай",))
    rx = client.post("/rides", headers=drv["auth"], json={
        "from_city": "Сибай", "to_city": "Акъяр", "comment": "cep-sameX", "waypoints": STOPS,
        "depart_at": (utcnow() + timedelta(minutes=25)).isoformat() + "+00:00",
        "seats_total": 1, "price": 700, "pickup_lat": SIBAY[0], "pickup_lng": SIBAY[1], "pickup": "Автовокзал"})
    assert rx.status_code == 200, rx.text
    px = user_factory("CEP Pax sameX")
    x = client.post("/bookings", headers=px["auth"], json={"ride_id": rx.json()["id"], "seats": 1, "pickup_stop": "Сибай"})
    assert x.status_code == 200, x.text
    x = x.json()["id"]
    assert client.post(f"/bookings/{x}/confirm", headers=drv["auth"]).status_code == 200
    token = _share(client, px, x)
    _driver_frame(client, drv, y, ride_y["id"], ON_ROAD, redis_on)   # точка лежит в ключе рейса Y
    _status(client, drv, x, "departed")
    assert _point_for(x) is None, "ключ рейса Y ушёл пассажиру рейса X"
    assert _app_feed(x, px["id"]) is None
    assert _family(client, token)["car"] is None
