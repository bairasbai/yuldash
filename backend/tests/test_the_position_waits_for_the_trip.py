"""Договорились — ещё не поехали: живая точка идёт, только когда поездка началась.

Проверка безопасности волны 1 (16.10.2026). Канал координат открывался сразу после
«подтвердить». Водитель открывал сообщение пассажира за три дня до выезда — приложение
включало передачу, и его точка (чаще всего дом) уходила пассажиру и, через ссылку
«поделиться», маме пассажира. Согласие на поездку — не согласие показывать, где ты живёшь.

Теперь точка пересылается и пишется в кэш для ссылки близкому, только когда пора
(`visibility.trip_is_live`): пассажир сел, водитель отметил «выехал/подъезжаю» или до выезда
меньше часа. Канал при этом не рвётся — экран поездки можно открыть заранее.

Проверяем по порядку, а не по молчанию (урок волны 180–181: молчащий канал неотличим от
закрытого): точка, отправленная до «выехал», не должна прийти раньше точки после «выехал».
"""
from __future__ import annotations

import json
from datetime import datetime, timedelta

import pytest

from app.models import Booking, BookingStatus, Ride, UserRole
from app.visibility import trip_is_live

from test_api import _ride


@pytest.fixture
def договорились(client, user_factory):
    """Бронь подтверждена, выезд далеко (2030 год) — поездка ещё не началась."""
    водитель = user_factory("ЗаранееВодитель", role=UserRole.driver)
    пассажир = user_factory("ЗаранееПассажирка")
    ride_id = _ride(client, водитель, seats=2)
    bid = client.post("/bookings", headers=пассажир["auth"], json={"ride_id": ride_id, "seats": 1}).json()["id"]
    assert client.post(f"/bookings/{bid}/confirm", headers=водитель["auth"]).status_code == 200
    return {"booking": bid, "водитель": водитель, "пассажир": пассажир}


def _первая_услышанная(client, д, отправитель: str, слушатель: str) -> int:
    """Отправитель шлёт точку ts=1, затем водитель «выехал», затем точку ts=2. Что пришло первым?"""
    путь = f"/ws/trip/{д['booking']}/location"
    with client.websocket_connect(путь) as ухо:
        ухо.send_text(json.dumps({"type": "auth", "token": д[слушатель]["token"]}))
        with client.websocket_connect(путь) as рот:
            рот.send_text(json.dumps({"type": "auth", "token": д[отправитель]["token"]}))
            рот.send_text(json.dumps({"type": "loc", "lat": 52.591, "lng": 58.317, "ts": 1}))
            r = client.post(f"/bookings/{д['booking']}/driver-status",
                            headers=д["водитель"]["auth"], json={"status": "departed"})
            assert r.status_code == 200, r.text
            рот.send_text(json.dumps({"type": "loc", "lat": 52.592, "lng": 58.318, "ts": 2}))
            return json.loads(ухо.receive_text())["ts"]


def test_точка_водителя_до_выезда_не_уходит_пассажиру(client, договорились):
    первая = _первая_услышанная(client, договорились, "водитель", "пассажир")
    assert первая == 2, (
        "точка водителя за годы до выезда дошла до пассажира: договорились — не значит поехали, "
        "а это чаще всего его дом")


def test_точка_пассажира_до_выезда_не_уходит_водителю(client, договорились):
    первая = _первая_услышанная(client, договорились, "пассажир", "водитель")
    assert первая == 2, "точка пассажирки до поездки дошла до водителя — это её дом, работа, сад"


# ── Само правило ──

def _бронь(status: BookingStatus, phase: str = "") -> Booking:
    return Booking(ride_id=1, passenger_id=1, seats=1, price=300, status=status, driver_phase=phase)


def _рейс(через: timedelta, now: datetime) -> Ride:
    return Ride(driver_id=2, from_city="Баймак", to_city="Сибай", depart_at=now + через,
                seats_total=3, seats_left=2, price=300)


NOW = datetime(2026, 10, 17, 3, 0)


@pytest.mark.parametrize("статус,фаза,через,пора", [
    (BookingStatus.pending, "", timedelta(minutes=10), False),          # не договорились
    (BookingStatus.confirmed, "", timedelta(days=3), False),            # договорились заранее
    (BookingStatus.confirmed, "", timedelta(minutes=59), True),         # меньше часа до выезда
    (BookingStatus.confirmed, "departed", timedelta(days=3), True),     # водитель выехал раньше
    (BookingStatus.confirmed, "arriving", timedelta(hours=5), True),
    (BookingStatus.onboard, "", timedelta(days=3), True),               # пассажир сел
    (BookingStatus.cancelled, "departed", timedelta(minutes=1), False), # отменили — никакой точки
    (BookingStatus.done, "", timedelta(minutes=-30), False),            # приехали — точка гаснет
])
def test_правило_поездки(статус, фаза, через, пора):
    assert trip_is_live(_бронь(статус, фаза), _рейс(через, NOW), now=NOW) is пора


def test_страница_близкого_не_пишет_едет_пока_не_поехали(client, договорились):
    """Ссылка маме за три дня до выезда: не «Машина едет», а «Поездка скоро начнётся»."""
    д = договорились
    c = client.post("/trusted-contacts", headers=д["пассажир"]["auth"],
                    json={"name": "Мама", "relation": "мама", "phone": "+79170000611"}).json()
    share = client.post(f"/bookings/{д['booking']}/share", headers=д["пассажир"]["auth"],
                        json={"contact_id": c["id"]}).json()

    до = client.get(f"/t/{share['token']}/state.json").json()
    assert до["status"] == "wait" and до["car"] is None, (
        f"за годы до выезда мама читает «{до['phase_text']['ru']}» — машины на карте нет и быть не может")

    r = client.post(f"/bookings/{д['booking']}/driver-status", headers=д["водитель"]["auth"],
                    json={"status": "departed"})
    assert r.status_code == 200, r.text
    после = client.get(f"/t/{share['token']}/state.json").json()
    assert после["status"] == "to_pickup", после
