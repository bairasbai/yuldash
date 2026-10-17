"""Конец сделки гасит канал координат сразу, а «в пути» пассажир отменить не может (приёмка PR155, 17.10.2026).

Две находки проверяющего PR155, обе про сервер:

* **Пассажир «в пути» отменял бронь прямым запросом** (200, `cancelled`). В приложении и на сайте
  кнопки нет, но сервер не запрещал: место возвращалось в продажу, пока человек сидит в машине,
  а у брони пропадали чат, «поделиться с близким» и SOS. Теперь 409 с понятным текстом.
  Водителю «в пути» отмена по-прежнему открыта (кроме «не пришёл» — решение 14.10).
* **После отмены точка водителя ещё до 30 с уходила пассажиру отменённой брони** — канал закрывал
  только сторож по часам. Теперь отмена, неявка и снятие поездки толкают канал
  (`trip_wake.poke`), и ближайший кадр закрывает его с «Trip ended».
"""
from __future__ import annotations

import json
from datetime import timedelta

import pytest
from sqlmodel import Session
from starlette.websockets import WebSocketDisconnect

from app import trip_wake
from app.db import engine
from app.models import Booking, BookingStatus, Ride, UserRole
from app.routers import location
from app.timeutil import utcnow

pytestmark = pytest.mark.timeout(30)


def _поездка(client, user_factory, тег: str, мест: int = 3):
    водитель = user_factory(f"Конец {тег} водитель", role=UserRole.driver)
    ride = client.post("/rides", headers=водитель["auth"], json={
        "from_city": "Сибай", "to_city": "Уфа",
        "depart_at": (utcnow() + timedelta(minutes=10)).isoformat() + "+00:00",
        "seats_total": мест, "price": 500}).json()
    return водитель, ride


def _бронь(client, user_factory, водитель, ride, тег: str):
    пассажир = user_factory(f"Конец {тег} пассажир")
    bid = client.post("/bookings", headers=пассажир["auth"], json={"ride_id": ride["id"], "seats": 1}).json()["id"]
    assert client.post(f"/bookings/{bid}/confirm", headers=водитель["auth"]).status_code == 200
    return пассажир, bid


def _кадр(i: int) -> str:
    return json.dumps({"type": "loc", "lat": 52.591 + i * 1e-5, "lng": 58.317, "ts": i})


def _вход(человек: dict) -> str:
    return json.dumps({"type": "auth", "token": человек["token"]})


# ── Пассажир «в пути» ──

def test_пассажир_в_пути_отменить_не_может_а_место_не_уходит_в_продажу(client, user_factory):
    водитель, ride = _поездка(client, user_factory, "сел")
    пассажир, bid = _бронь(client, user_factory, водитель, ride, "сел")
    assert client.post(f"/bookings/{bid}/trip-status", headers=пассажир["auth"],
                       json={"status": "sat"}).status_code == 200
    with Session(engine) as s:
        мест_было = s.get(Ride, ride["id"]).seats_left

    r = client.post(f"/bookings/{bid}/cancel", headers=пассажир["auth"], json={"reason": "plans_changed"})
    assert r.status_code == 409, r.text
    detail = r.json()["detail"]
    assert "уже в машине" in detail["ru"] and "SOS" in detail["ru"]
    assert detail["ba"].strip() and detail["ba"] != detail["ru"]
    assert detail["code"] == "onboard_no_cancel"
    with Session(engine) as s:
        assert s.get(Booking, bid).status == BookingStatus.onboard
        assert s.get(Ride, ride["id"]).seats_left == мест_было, "место ушло в продажу, а человек в машине"


def test_водителю_в_пути_отмена_с_другой_причиной_открыта_как_раньше(client, user_factory):
    """Решение 14.10 запрещает водителю только «не пришёл» по «в пути»; остальное не трогаем."""
    водитель, ride = _поездка(client, user_factory, "водитель")
    пассажир, bid = _бронь(client, user_factory, водитель, ride, "водитель")
    assert client.post(f"/bookings/{bid}/trip-status", headers=пассажир["auth"],
                       json={"status": "sat"}).status_code == 200
    r = client.post(f"/bookings/{bid}/cancel", headers=водитель["auth"], json={"reason": "other"})
    assert r.status_code == 200 and r.json()["status"] == "cancelled", r.text


def test_подтверждённую_пассажир_по_прежнему_отменяет(client, user_factory):
    водитель, ride = _поездка(client, user_factory, "до")
    пассажир, bid = _бронь(client, user_factory, водитель, ride, "до")
    r = client.post(f"/bookings/{bid}/cancel", headers=пассажир["auth"], json={"reason": "plans_changed"})
    assert r.status_code == 200 and r.json()["status"] == "cancelled", r.text


# ── Канал координат закрывается сразу ──

def test_отмена_брони_гасит_точку_водителя_со_следующего_кадра(client, user_factory):
    водитель, ride = _поездка(client, user_factory, "канал")
    пассажир, bid = _бронь(client, user_factory, водитель, ride, "канал")
    assert client.post(f"/bookings/{bid}/driver-status", headers=водитель["auth"],
                       json={"status": "departed"}).status_code == 200
    путь = f"/ws/trip/{bid}/location"
    with client.websocket_connect(путь) as ухо:
        ухо.send_text(_вход(пассажир))
        with client.websocket_connect(путь) as рот:
            рот.send_text(_вход(водитель))
            рот.send_text(_кадр(1))
            assert json.loads(ухо.receive_text())["ts"] == 1, "контроль: поездка идёт, точка доходит"

            r = client.post(f"/bookings/{bid}/cancel", headers=пассажир["auth"], json={"reason": "plans_changed"})
            assert r.status_code == 200, r.text

            рот.send_text(_кадр(2))
            with pytest.raises(WebSocketDisconnect) as закрыли:
                рот.receive_text()
    assert закрыли.value.code == 1008 and закрыли.value.reason == "Trip ended", (
        "бронь отменена, а канал водителя ещё открыт — точка ушла бы пассажиру отменённой брони")


def test_толчок_по_идущей_поездке_без_отмены_ничего_не_закрывает(client, user_factory):
    """«Подъезжаю» тоже толкает канал — поездка продолжается, точка идёт."""
    водитель, ride = _поездка(client, user_factory, "подъезжаю")
    _пассажир, bid = _бронь(client, user_factory, водитель, ride, "подъезжаю")
    assert client.post(f"/bookings/{bid}/driver-status", headers=водитель["auth"],
                       json={"status": "departed"}).status_code == 200
    решение = location._Пора(bid)
    assert решение.наступила() is True
    trip_wake.poke(bid)
    assert решение.наступила() is True and решение.кончилась is False


def test_отмена_без_толчка_ловит_прежний_сторож_а_не_этот_путь(client, user_factory):
    """Потерянный толчок (чужой воркер без Redis) — прежнее поведение: решение «да» держится,
    канал закрывают сторож и счётчик кадров (≤ 30 с). Не хуже, чем было до этой правки."""
    водитель, ride = _поездка(client, user_factory, "безтолчка")
    _пассажир, bid = _бронь(client, user_factory, водитель, ride, "безтолчка")
    assert client.post(f"/bookings/{bid}/driver-status", headers=водитель["auth"],
                       json={"status": "departed"}).status_code == 200
    решение = location._Пора(bid)
    assert решение.наступила() is True
    with Session(engine) as s:
        b = s.get(Booking, bid)
        b.status = BookingStatus.cancelled
        s.add(b)
        s.commit()
    assert решение.наступила() is True and решение.кончилась is False
    trip_wake.poke(bid)
    assert решение.наступила() is False and решение.кончилась is True


def test_отмена_неявка_и_снятие_поездки_толкают_каналы(client, user_factory, monkeypatch):
    толкнули: list[int] = []
    настоящий = trip_wake.poke
    monkeypatch.setattr(trip_wake, "poke", lambda bid: (толкнули.append(bid), настоящий(bid)))

    водитель, ride = _поездка(client, user_factory, "толчки", мест=3)
    п1, b1 = _бронь(client, user_factory, водитель, ride, "толчки-1")
    _п2, b2 = _бронь(client, user_factory, водитель, ride, "толчки-2")
    _п3, b3 = _бронь(client, user_factory, водитель, ride, "толчки-3")

    assert client.post(f"/bookings/{b1}/cancel", headers=п1["auth"], json={"reason": ""}).status_code == 200
    assert b1 in толкнули, "отмена пассажиром не толкнула канал"
    assert client.post(f"/bookings/{b2}/no-show", headers=водитель["auth"]).status_code == 200
    assert b2 in толкнули, "неявка не толкнула канал"
    assert client.post(f"/rides/{ride['id']}/cancel", headers=водитель["auth"]).status_code == 200
    assert b3 in толкнули, "снятие поездки не толкнуло каналы её броней"
