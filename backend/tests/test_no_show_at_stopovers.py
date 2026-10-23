"""«Не пришёл» и пассажиры на промежуточных остановках (решение Александра 18.10.2026).

Что было (после PR182). Срок неявки считался от выезда из начальной точки: `depart_at + 15 минут`.
Пассажира на остановке (`Ride.waypoints`) сажают позже — бывает, через час, — и к приезду машины
кнопка у водителя уже горела: его 15 минут не защищали ничего. Плюс «Подъезжаю» сверялся по GPS
только с пином начала маршрута: водитель у остановки получал «Ты ещё далеко от места подачи», и
пассажир на остановке так и не узнавал «Водитель подъезжает».

Что стало:
* `Booking.arriving_at` — время ПЕРВОГО «Подъезжаю» по брони; повтор не сдвигает;
* срок одним правилом: `max(выезд, «Подъезжаю») + NO_SHOW_WAIT`;
* поездка с остановками без «Подъезжаю» — 409 `no_show_needs_arriving`, в строке `/driver/bookings`
  `no_show_at = null` и `no_show_needs_arriving = true` (клиент пишет «Сначала нажми «Подъезжаю»»);
* на поездке с остановками «Подъезжаю» не отказывает из-за расстояния до пина начала маршрута,
  но и не подтверждается по GPS (`arrival_verified = false`): где садится пассажир, бронь не знает;
* «Подъезжаю» — не раньше чем за 30 минут до выезда (`DONE_EARLY_GRACE`, как «Сел»; приёмка PR184):
  иначе за неделю до поездки уходило срочное «Водитель подъезжает» и запускался срок «Не пришёл».
  Раньше — 409 `arriving_too_early`, без фазы, без `arriving_at`, без пуша;
* остановка — название хотя бы с одной буквой: «.» или «-» в поле GPS-проверку не выключают.
"""
from datetime import timedelta

import fakeredis
import pytest
from sqlmodel import Session

import app.services as svc
from app import instant_service as isv
from app import livepos
from app.db import engine
from app.models import Booking, BookingStatus, Ride, UserRole
from app.ride_stops import ride_has_stops_raw as ride_has_stops   # правило «есть остановки» (PR184 → ride_stops)
from app.routers.bookings import DONE_EARLY_GRACE, NO_SHOW_WAIT, arriving_opens_at, no_show_available_at
from app.timeutil import utcnow

STOPS = "Акъяр | Темясово"
WAITED_OUT = -(NO_SHOW_WAIT + timedelta(minutes=1))
# Пин начала маршрута (Баймак) и машина у остановки в 25 км от него (сторона Сибая).
BAYMAK = (52.5906, 58.3169)
FAR = (52.7200, 58.6600)


def _trip(client, user_factory, tag, *, depart_in=WAITED_OUT, waypoints=STOPS, pin=False):
    """Поездка (с остановками по умолчанию), подтверждённая бронь. `depart_in` минус — уже уехал."""
    drv = user_factory(f"NSS Driver {tag}", role=UserRole.driver)
    pax = user_factory(f"NSS Passenger {tag}")
    body = {"from_city": "Баймак", "to_city": "Уфа", "comment": f"nss-{tag}", "waypoints": waypoints,
            "depart_at": (utcnow() + depart_in).isoformat() + "+00:00", "seats_total": 3, "price": 500}
    if pin:
        body |= {"pickup_lat": BAYMAK[0], "pickup_lng": BAYMAK[1]}
    ride = client.post("/rides", headers=drv["auth"], json=body)
    assert ride.status_code == 200, ride.text
    ride = ride.json()
    b = client.post("/bookings", headers=pax["auth"], json={"ride_id": ride["id"], "seats": 1})
    assert b.status_code == 200, b.text
    bid = b.json()["id"]
    assert client.post(f"/bookings/{bid}/confirm", headers=drv["auth"]).status_code == 200
    return drv, pax, ride, bid


def _no_show(client, drv, bid):
    return client.post(f"/bookings/{bid}/no-show", headers=drv["auth"])


def _arriving(client, drv, bid):
    return client.post(f"/bookings/{bid}/driver-status", headers=drv["auth"], json={"status": "arriving"})


def _row(client, drv, bid) -> dict:
    return next(x for x in client.get("/driver/bookings", headers=drv["auth"]).json() if x["booking_id"] == bid)


def _booking(bid: int) -> Booking:
    with Session(engine) as s:
        return s.get(Booking, bid)


def _depart_at(ride_id: int):
    with Session(engine) as s:
        return s.get(Ride, ride_id).depart_at


def _untouched(bid: int, ride_id: int, seats_before: int):
    with Session(engine) as s:
        b = s.get(Booking, bid)
        assert b.status == BookingStatus.confirmed and b.no_show is False and b.cancel_reason is None
        assert s.get(Ride, ride_id).seats_left == seats_before


def _seats(ride_id: int) -> int:
    with Session(engine) as s:
        return s.get(Ride, ride_id).seats_left


@pytest.fixture
def at():
    """Поставить водителя в точку по брони `bid` — тем же кэшем, что пишет его канал координат."""
    r = fakeredis.FakeStrictRedis(decode_responses=True)
    isv._redis_override = r

    def place(point, bid):
        r.flushall()
        livepos.livepos_set("booking", bid, point[0], point[1])
    yield place
    isv._redis_override = None


@pytest.fixture
def clock(monkeypatch):
    """Часы сервера (`bookings.utcnow`) — подставить момент."""
    import app.routers.bookings as bookings_mod

    def set_now(moment):
        monkeypatch.setattr(bookings_mod, "utcnow", lambda: moment)
    return set_now


# ── Остановки: без «Подъезжаю» неявки нет ──

def test_without_arriving_the_no_show_is_refused_even_long_after_departure(client, user_factory):
    """Выезд был 16 минут назад — по старому правилу кнопка уже горела бы. На поездке с остановками —
    отказ с кодом: пассажир на остановке ещё не получил «Водитель подъезжает»."""
    drv, _pax, ride, bid = _trip(client, user_factory, "noarr")
    seats_before = _seats(ride["id"])
    r = _no_show(client, drv, bid)
    assert r.status_code == 409, r.text
    detail = r.json()["detail"]
    assert detail["code"] == "no_show_needs_arriving"
    assert "Подъезжаю" in detail["ru"] and "Яҡынлашам" in detail["ba"] and detail["ba"] != detail["ru"]
    assert detail["no_show_at"] is None
    _untouched(bid, ride["id"], seats_before)


def test_the_driver_row_says_arriving_comes_first(client, user_factory):
    """Строка водителя: срока нет (`no_show_at = null`), и признак — клиент пишет «Сначала нажми…»."""
    drv, _pax, _ride, bid = _trip(client, user_factory, "row")
    row = _row(client, drv, bid)
    assert row["no_show_at"] is None and row["no_show_wait_sec"] == 0
    assert row["no_show_needs_arriving"] is True


def test_after_arriving_the_wait_counts_from_it(client, user_factory, clock):
    """«Подъезжаю» → срок = «Подъезжаю» + 15 минут: раньше — «рано», ровно в срок — проходит."""
    drv, _pax, ride, bid = _trip(client, user_factory, "after")
    pressed = utcnow()
    clock(pressed)
    r = _arriving(client, drv, bid)
    assert r.status_code == 200, r.text
    assert _booking(bid).arriving_at == pressed
    opens = pressed + NO_SHOW_WAIT
    # Ответ «Подъезжаю» и строка водителя несут новый срок — клиенту не нужно перечитывать список.
    assert r.json()["no_show_at"] == opens.isoformat() and r.json()["no_show_needs_arriving"] is False
    # Секунды от ответа — клиент ждёт по ним, даже если часы телефона спешат.
    assert r.json()["no_show_wait_sec"] == int(NO_SHOW_WAIT.total_seconds())
    row = _row(client, drv, bid)
    assert row["no_show_at"] == opens.isoformat() and row["no_show_needs_arriving"] is False

    seats_before = _seats(ride["id"])
    clock(opens - timedelta(minutes=5))
    early = _no_show(client, drv, bid)
    assert early.status_code == 409, early.text
    detail = early.json()["detail"]
    assert detail["code"] == "no_show_too_early"
    assert detail["no_show_at"] == opens.isoformat() and detail["no_show_wait_sec"] == 300
    # Текст называет, от чего считаем: от «Подъезжаю», а не от давно прошедшего выезда.
    assert "после «Подъезжаю»" in detail["ru"] and "«Яҡынлашам»дан һуң" in detail["ba"]
    _untouched(bid, ride["id"], seats_before)

    clock(opens)
    ok = _no_show(client, drv, bid)
    assert ok.status_code == 200 and ok.json()["status"] == "cancelled" and ok.json()["no_show"] is True, ok.text
    assert _seats(ride["id"]) == seats_before + 1


def test_repeated_arriving_does_not_move_the_clock(client, user_factory, clock):
    """Водитель жмёт «Подъезжаю» ещё раз через 10 минут — срок не сдвигается: считаем от первого."""
    drv, _pax, _ride, bid = _trip(client, user_factory, "again")
    first = utcnow()
    clock(first)
    assert _arriving(client, drv, bid).status_code == 200
    clock(first + timedelta(minutes=10))
    again = _arriving(client, drv, bid)
    assert again.status_code == 200, again.text
    assert _booking(bid).arriving_at == first
    assert again.json()["no_show_at"] == (first + NO_SHOW_WAIT).isoformat()
    # «Выехал» между ними тоже не сбрасывает отметку: она про первое «Подъезжаю», а не про фазу.
    client.post(f"/bookings/{bid}/driver-status", headers=drv["auth"], json={"status": "departed"})
    assert _booking(bid).arriving_at == first


def test_arriving_before_departure_keeps_the_departure_clock(client, user_factory):
    """«Подъезжаю» за 10 минут до выезда (пассажир в начале маршрута) — срок от выезда, а не раньше."""
    drv, _pax, ride, bid = _trip(client, user_factory, "early", depart_in=timedelta(minutes=10))
    assert _arriving(client, drv, bid).status_code == 200
    assert _row(client, drv, bid)["no_show_at"] == (_depart_at(ride["id"]) + NO_SHOW_WAIT).isoformat()


# ── Поездка без остановок: как в PR182, но поздний «Подъезжаю» сдвигает срок ──

def test_no_stops_works_as_before_without_arriving(client, user_factory):
    drv, _pax, ride, bid = _trip(client, user_factory, "plain", waypoints="")
    row = _row(client, drv, bid)
    assert row["no_show_needs_arriving"] is False
    assert row["no_show_at"] == (_depart_at(ride["id"]) + NO_SHOW_WAIT).isoformat()
    r = _no_show(client, drv, bid)
    assert r.status_code == 200 and r.json()["no_show"] is True, r.text


def test_no_stops_late_arriving_moves_the_clock(client, user_factory, clock):
    """Водитель опоздал и нажал «Подъезжаю» через 16 минут после выезда: пассажир только что получил
    «Водитель подъезжает» — ему тоже 15 минут, одно правило для всех поездок."""
    drv, _pax, ride, bid = _trip(client, user_factory, "late", waypoints="")
    pressed = utcnow()
    clock(pressed)
    assert _arriving(client, drv, bid).status_code == 200
    seats_before = _seats(ride["id"])
    r = _no_show(client, drv, bid)
    assert r.status_code == 409 and r.json()["detail"]["code"] == "no_show_too_early", r.text
    assert r.json()["detail"]["no_show_at"] == (pressed + NO_SHOW_WAIT).isoformat()
    _untouched(bid, ride["id"], seats_before)
    clock(pressed + NO_SHOW_WAIT)
    assert _no_show(client, drv, bid).status_code == 200


def test_no_stops_too_early_still_names_the_departure(client, user_factory):
    """Без «Подъезжаю» отказ «рано» по-прежнему говорит про время выезда."""
    drv, _pax, _ride, bid = _trip(client, user_factory, "words", waypoints="", depart_in=timedelta(hours=1))
    detail = _no_show(client, drv, bid).json()["detail"]
    assert detail["code"] == "no_show_too_early" and "после времени выезда" in detail["ru"]
    assert f"Килмәүҙе сығыу ваҡытынан {int(NO_SHOW_WAIT.total_seconds() // 60)} минут үткәс кенә" in detail["ba"]


def test_arriving_between_the_check_and_the_lock_moves_the_clock(client, user_factory, monkeypatch):
    """Срок вышел, водитель жмёт «Не пришёл», а в ту же секунду со второго телефона ушло «Подъезжаю»:
    перепроверка под замком видит свежую отметку и отвечает «рано», бронь не трогает."""
    import app.routers.bookings as bookings_mod

    drv, _pax, ride, bid = _trip(client, user_factory, "race", waypoints="")
    seats_before = _seats(ride["id"])
    original = bookings_mod.booking_and_ride_for_user
    fired = {"yes": False}

    def arriving_in_between(*a, **kw):
        result = original(*a, **kw)
        if not fired["yes"]:
            fired["yes"] = True
            with Session(engine) as other:
                b = other.get(Booking, bid)
                b.arriving_at = utcnow()
                other.add(b)
                other.commit()
        return result

    monkeypatch.setattr(bookings_mod, "booking_and_ride_for_user", arriving_in_between)
    r = _no_show(client, drv, bid)
    assert fired["yes"]
    assert r.status_code == 409 and r.json()["detail"]["code"] == "no_show_too_early", r.text
    _untouched(bid, ride["id"], seats_before)


# ── «Подъезжаю» по GPS на поездке с остановками ──

def test_arriving_far_from_the_start_pin_is_accepted_on_a_ride_with_stops(client, user_factory, at):
    """Машина у остановки в 25 км от пина начала маршрута: «Подъезжаю» проходит, пассажир получает
    «Водитель подъезжает», но без «GPS подтверждено» — подтвердить нечем."""
    drv, pax, _ride, bid = _trip(client, user_factory, "gps", pin=True, depart_in=timedelta(minutes=20))
    at(FAR, bid)
    r = _arriving(client, drv, bid)
    assert r.status_code == 200, r.text
    role = client.get(f"/bookings/{bid}/role", headers=pax["auth"]).json()
    assert role["driver_phase"] == "arriving"
    assert role["arrival_verified"] is False
    assert _booking(bid).arriving_at is not None


def test_arriving_near_the_start_pin_on_a_ride_with_stops_is_not_verified_either(client, user_factory, at):
    """Даже у пина начала маршрута не ставим «GPS подтверждено»: этот пассажир может ждать на остановке."""
    drv, pax, _ride, bid = _trip(client, user_factory, "gpsnear", pin=True, depart_in=timedelta(minutes=20))
    at(BAYMAK, bid)
    assert _arriving(client, drv, bid).status_code == 200
    assert client.get(f"/bookings/{bid}/role", headers=pax["auth"]).json()["arrival_verified"] is False


def test_without_stops_the_gps_check_is_unchanged(client, user_factory, at):
    """Контроль: на поездке без остановок далёкое «Подъезжаю» по-прежнему отказ, и отметки нет."""
    drv, _pax, _ride, bid = _trip(client, user_factory, "gpsplain", pin=True, waypoints="",
                                  depart_in=timedelta(minutes=20))
    at(FAR, bid)
    r = _arriving(client, drv, bid)
    assert r.status_code == 409 and "км" in r.json()["detail"]["ru"], r.text
    assert _booking(bid).arriving_at is None, "отказ «далеко» не должен запускать срок неявки"


# ── Что считается остановкой ──

def test_blank_waypoints_are_not_stops():
    assert ride_has_stops(Ride(waypoints="Акъяр")) is True
    assert ride_has_stops(Ride(waypoints=STOPS)) is True
    assert ride_has_stops(Ride(waypoints="Һаҡмар")) is True, "башкирские буквы — тоже буквы"
    assert ride_has_stops(Ride(waypoints=". | Акъяр")) is True
    assert ride_has_stops(Ride(waypoints="")) is False
    assert ride_has_stops(Ride(waypoints="   ")) is False
    assert ride_has_stops(Ride(waypoints=" | ")) is False
    # Знаки без букв — не остановка: иначе «.» в поле выключала бы GPS-проверку у всей поездки.
    assert ride_has_stops(Ride(waypoints=".")) is False
    assert ride_has_stops(Ride(waypoints="- | . | 123")) is False


def test_a_dot_in_waypoints_keeps_the_gps_check(client, user_factory, at):
    """«.» в остановках СТАРОЙ поездки — поездка без остановок: далёкое «Подъезжаю» по-прежнему отказ.
    (С 18.10.2026 «.» при публикации не пройдёт — 400 `stop_invalid`; в базе такие строки остались.)"""
    drv, _pax, ride, bid = _trip(client, user_factory, "gpsdot", pin=True, waypoints="",
                                 depart_in=timedelta(minutes=20))
    with Session(engine) as s:
        r = s.get(Ride, ride["id"])
        r.waypoints = "."
        s.add(r)
        s.commit()
    at(FAR, bid)
    r = _arriving(client, drv, bid)
    assert r.status_code == 409 and "км" in r.json()["detail"]["ru"], r.text


# ── «Подъезжаю» — не раньше чем за 30 минут до выезда (приёмка PR184) ──

@pytest.fixture
def pushes(monkeypatch) -> list:
    got: list = []
    monkeypatch.setattr(svc, "send_push", lambda session, uid, title, body, **k: got.append((uid, title)))
    return got


def test_arriving_two_days_early_is_refused_without_a_push(client, user_factory, pushes):
    """Поездка с остановкой через два дня, водитель жмёт «Подъезжаю» сегодня: отказ с кодом, пассажиру
    ничего не ушло, срок «Не пришёл» не запустился — через 15 минут после выезда неявки по-прежнему нет."""
    drv, pax, ride, bid = _trip(client, user_factory, "twodays", depart_in=timedelta(days=2))
    pushes.clear()
    r = _arriving(client, drv, bid)
    assert r.status_code == 409, r.text
    detail = r.json()["detail"]
    assert detail["code"] == "arriving_too_early"
    assert "Подъезжаю" in detail["ru"] and "Яҡынлашам" in detail["ba"] and detail["ba"] != detail["ru"]
    opens = _depart_at(ride["id"]) - DONE_EARLY_GRACE
    assert detail["arriving_opens_at"] == opens.isoformat()
    assert 2 * 86_400 - 30 * 60 - 60 <= detail["arriving_wait_sec"] <= 2 * 86_400 - 30 * 60
    assert [t for uid, t in pushes if uid == pax["id"]] == [], "«Водитель подъезжает» за два дня — нельзя"
    b = _booking(bid)
    assert b.arriving_at is None and b.driver_phase == "", "отказ ничего не записал"
    row = _row(client, drv, bid)
    assert row["no_show_needs_arriving"] is True and row["no_show_at"] is None
    assert row["arriving_opens_at"] == opens.isoformat() and row["arriving_wait_sec"] > 0


def test_the_trip_screen_arriving_hits_the_same_refusal(client, user_factory, pushes):
    """Тот же отказ у «Подъезжаю» на экране поездки (та же ручка) и на поездке без остановок."""
    drv, pax, _ride, bid = _trip(client, user_factory, "tripscreen", waypoints="", depart_in=timedelta(hours=3))
    pushes.clear()
    r = _arriving(client, drv, bid)
    assert r.status_code == 409 and r.json()["detail"]["code"] == "arriving_too_early", r.text
    assert pushes == []
    assert _booking(bid).arriving_at is None
    # «Выехал» за три часа до выезда — тоже отказ (вторая проверка безопасности, P2-Г, 18.10.2026):
    # раньше чем за час он будил пассажира и открывал обеим сторонам живую точку от дома.
    early = client.post(f"/bookings/{bid}/driver-status", headers=drv["auth"], json={"status": "departed"})
    assert early.status_code == 409 and early.json()["detail"]["code"] == "departed_too_early", early.text
    assert early.json()["detail"]["departed_wait_sec"] > 0 and pushes == []
    assert _booking(bid).driver_phase == ""


def test_arriving_opens_exactly_thirty_minutes_before_departure(client, user_factory, clock, pushes):
    drv, pax, ride, bid = _trip(client, user_factory, "edge30", depart_in=timedelta(hours=2))
    pushes.clear()   # «Бронь подтверждена» — не про это
    opens = arriving_opens_at(Ride(depart_at=_depart_at(ride["id"])))
    clock(opens - timedelta(seconds=1))
    early = _arriving(client, drv, bid)
    assert early.status_code == 409 and early.json()["detail"]["arriving_wait_sec"] == 1, early.text
    clock(opens)
    ok = _arriving(client, drv, bid)
    assert ok.status_code == 200, ok.text
    assert _booking(bid).arriving_at == opens
    assert [t for uid, t in pushes if uid == pax["id"]] == ["Водитель подъезжает"]
    # Срок «Не пришёл» — от позднего: выезд (через 30 минут) + 15, а не «Подъезжаю» + 15.
    assert ok.json()["no_show_at"] == (_depart_at(ride["id"]) + NO_SHOW_WAIT).isoformat()
    assert ok.json()["arriving_wait_sec"] == 0


def test_one_rule_for_the_moment():
    """`no_show_available_at`: поздний из «выезд» и «Подъезжаю» + ожидание; с остановками без «Подъезжаю» — None."""
    depart = utcnow()
    plain = Ride(depart_at=depart, waypoints="")
    stops = Ride(depart_at=depart, waypoints=STOPS)
    assert no_show_available_at(plain, Booking(arriving_at=None)) == depart + NO_SHOW_WAIT
    assert no_show_available_at(plain, Booking(arriving_at=depart - timedelta(minutes=5))) == depart + NO_SHOW_WAIT
    assert no_show_available_at(plain, Booking(arriving_at=depart + timedelta(minutes=7))) == depart + timedelta(minutes=7) + NO_SHOW_WAIT
    assert no_show_available_at(stops, Booking(arriving_at=None)) is None
    assert no_show_available_at(stops, Booking(arriving_at=depart + timedelta(minutes=40))) == depart + timedelta(minutes=40) + NO_SHOW_WAIT
    # Без брони (старые вызовы) — как в PR182: от выезда.
    assert no_show_available_at(stops) == depart + NO_SHOW_WAIT
