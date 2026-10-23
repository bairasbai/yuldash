"""Канал координат принимает только настоящие точки Земли (приёмка безопасности PR186, 23.10.2026).

`json.loads` пропускает `NaN`/`Infinity`, а `isinstance(float)` их не отличает. Одна такая «точка»
водителя ложилась в кэш, выключала анти-телепорт до конца соединения (якорь NaN: сравнения ложны)
и роняла ссылку близких у всех броней рейса — NaN не сериализуется в JSON (500 на `/t/{token}`).
Порог — на входе всех трёх каналов: попутка, такси, посылка.
"""
from __future__ import annotations

import inspect
import json
from datetime import timedelta

import pytest

from app.routers import location

from test_courier_c2 import _accept, _courier_on, _make_courier, _order  # noqa: F401 — фикстура
from test_instant import fake_redis  # noqa: F401 — фикстура
from test_pickup_stop_arriving import SIBAY, _trip, redis_on  # noqa: F401 — фикстура
from test_taxi_polish import _accepted_order

pytestmark = pytest.mark.timeout(30)

NAN = float("nan")


@pytest.mark.parametrize("lat,lng", [
    (NAN, 58.6), (52.7, NAN), (float("inf"), 58.6), (52.7, float("-inf")),
    (90.0001, 58.6), (-90.5, 58.6), (52.7, 180.5), (52.7, -181),
    (True, 58.6), (52.7, False), ("52.7", 58.6), (None, 58.6),
])
def test_not_a_point(lat, lng):
    assert location._is_point(lat, lng) is False


@pytest.mark.parametrize("lat,lng", [(52.716, 58.664), (90, 180), (-90, -180), (0, 0), (54, 55.9)])
def test_a_point(lat, lng):
    assert location._is_point(lat, lng) is True


def test_every_channel_uses_the_gate():
    """Сторож: все три канала (бронь, такси, посылка) проверяют кадр одним правилом."""
    src = inspect.getsource(location)
    assert src.count("if not _is_point(lat, lng):") == 3
    assert "isinstance(lat, (int, float)) or not isinstance(lng" not in src


def test_booking_channel_drops_nan_before_relay(client, user_factory, redis_on):
    """Попутка: кадр NaN водителя не уходит пассажиру и не ложится в кэш — первым придёт настоящий."""
    drv, (pax,), _ride, (bid,) = _trip(client, user_factory, "nanb", places=("Сибай",),
                                       depart_in=timedelta(minutes=20))
    with client.websocket_connect(f"/ws/trip/{bid}/location") as ear:
        ear.send_text(json.dumps({"type": "auth", "token": pax["token"]}))
        with client.websocket_connect(f"/ws/trip/{bid}/location") as mouth:
            mouth.send_text(json.dumps({"type": "auth", "token": drv["token"]}))
            mouth.send_text(json.dumps({"type": "loc", "lat": NAN, "lng": NAN, "ts": 1}))
            mouth.send_text(json.dumps({"type": "loc", "lat": SIBAY[0], "lng": 99999, "ts": 2}))
            mouth.send_text(json.dumps({"type": "loc", "lat": SIBAY[0], "lng": SIBAY[1], "ts": 3}))
            got = json.loads(ear.receive_text())
    assert got["ts"] == 3 and got["lat"] == SIBAY[0], got


def test_taxi_channel_drops_nan_before_relay(client, user_factory, fake_redis):
    d, pax, order = _accepted_order(client, user_factory, fake_redis, "NanTaxiDrv", "NanTaxiPax")
    with client.websocket_connect(f"/ws/instant/{order['id']}/location") as pax_ws:
        pax_ws.send_text(json.dumps({"type": "auth", "token": pax["token"]}))
        with client.websocket_connect(f"/ws/instant/{order['id']}/location") as drv_ws:
            drv_ws.send_text(json.dumps({"type": "auth", "token": d["token"]}))
            drv_ws.send_text(json.dumps({"type": "loc", "lat": NAN, "lng": 58.33, "ts": 1}))
            drv_ws.send_text(json.dumps({"type": "loc", "lat": 52.61, "lng": 58.33, "ts": 2}))
            msg = json.loads(pax_ws.receive_text())
    assert msg["ts"] == 2 and msg["lat"] == 52.61, msg


def test_parcel_channel_drops_nan_before_relay(client, user_factory):
    courier = _make_courier(client, user_factory, name="NanКурьер")
    sender = user_factory(name="NanОтпр")
    pid = _order(client, sender).json()["id"]
    assert _accept(client, courier, pid).status_code == 200
    with client.websocket_connect(f"/ws/parcel/{pid}/location") as courier_ws:
        courier_ws.send_text(json.dumps({"type": "auth", "token": courier["token"]}))
        with client.websocket_connect(f"/ws/parcel/{pid}/location") as sender_ws:
            sender_ws.send_text(json.dumps({"type": "auth", "token": sender["token"]}))
            courier_ws.send_text(json.dumps({"type": "loc", "lat": 54.05, "lng": float("inf"), "ts": 1}))
            courier_ws.send_text(json.dumps({"type": "loc", "lat": 54.05, "lng": 55.96, "ts": 2}))
            msg = json.loads(sender_ws.receive_text())
    assert msg["ts"] == 2 and msg["lng"] == 55.96, msg
