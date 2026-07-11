"""B7a «Такси-полировка»: live-трек такси-заказа (/ws/instant/{id}/location),
рейтинг пассажира в оффере, приватность оффера. Трек брони (попутка) не затронут —
проверяем оба канала бок о бок."""
import json

import pytest
from starlette.websockets import WebSocketDisconnect

from app.models import UserRole

from test_api import _ride
from test_instant import _offered_order, fake_redis  # noqa: F401 — fixture реэкспорт


def _accepted_order(client, user_factory, fake_redis, dname, pname):
    d, pax, order = _offered_order(client, user_factory, fake_redis, dname, pname)
    r = client.post(f"/instant/orders/{order['id']}/accept", headers=d["auth"])
    assert r.status_code == 200, r.text
    return d, pax, r.json()


# ============================ WS live-трек такси-заказа ============================
def test_instant_location_relays_driver_to_passenger(client, user_factory, fake_redis):
    """Водитель шлёт позицию → пассажир получает кадр с role=driver; битые кадры игнорируются."""
    d, pax, order = _accepted_order(client, user_factory, fake_redis, "WsTrkDrv", "WsTrkPax")
    oid = order["id"]
    with client.websocket_connect(f"/ws/instant/{oid}/location") as pax_ws:
        pax_ws.send_text(json.dumps({"type": "auth", "token": pax["token"]}))
        with client.websocket_connect(f"/ws/instant/{oid}/location") as drv_ws:
            drv_ws.send_text(json.dumps({"type": "auth", "token": d["token"]}))
            drv_ws.send_text("{bad-json")                                            # не роняет
            drv_ws.send_text(json.dumps({"type": "loc", "lat": "bad", "lng": 58.3}))  # мусор — игнор
            drv_ws.send_text(json.dumps({"type": "loc", "lat": 52.61, "lng": 58.33, "bearing": 90, "ts": 7}))
            msg = json.loads(pax_ws.receive_text())
            assert msg["type"] == "loc" and msg["role"] == "driver"
            assert msg["lat"] == 52.61 and msg["lng"] == 58.33
            assert msg["bearing"] == 90 and msg["ts"] == 7


def test_instant_location_rejects_outsider(client, user_factory, fake_redis):
    """Чужак (не пассажир и не назначенный водитель) — Forbidden, канал закрывается."""
    d, pax, order = _accepted_order(client, user_factory, fake_redis, "WsOutDrv", "WsOutPax")
    outsider = user_factory("WsOutsider")
    with pytest.raises((WebSocketDisconnect, Exception)):
        with client.websocket_connect(f"/ws/instant/{order['id']}/location") as ws:
            ws.send_text(json.dumps({"type": "auth", "token": outsider["token"]}))
            ws.receive_text()


def test_instant_location_rejects_before_accept(client, user_factory, fake_redis):
    """До accept заказ не активен: live-гео не течёт даже участнику (приватность как у телефона)."""
    d, pax, order = _offered_order(client, user_factory, fake_redis, "WsEarlyDrv", "WsEarlyPax")
    with pytest.raises((WebSocketDisconnect, Exception)):
        with client.websocket_connect(f"/ws/instant/{order['id']}/location") as ws:
            ws.send_text(json.dumps({"type": "auth", "token": pax["token"]}))
            ws.receive_text()


def test_instant_location_rejects_invalid_token(client, user_factory, fake_redis):
    d, pax, order = _accepted_order(client, user_factory, fake_redis, "WsTokDrv", "WsTokPax")
    with pytest.raises((WebSocketDisconnect, Exception)):
        with client.websocket_connect(f"/ws/instant/{order['id']}/location") as ws:
            ws.send_text(json.dumps({"type": "auth", "token": "bad-token"}))
            ws.receive_text()


def test_booking_track_intact_alongside_instant(client, user_factory, fake_redis):
    """Трек брони (попутка) работает как раньше, когда рядом открыт такси-канал:
    namespace ключей разный, кадры не перетекают между каналами."""
    d, pax, order = _accepted_order(client, user_factory, fake_redis, "WsMixTaxiDrv", "WsMixTaxiPax")
    bdrv = user_factory("WsMixBookDrv", role=UserRole.driver)
    ride_id = _ride(client, bdrv, seats=1)
    bpax = user_factory("WsMixBookPax")
    booking_id = client.post("/bookings", headers=bpax["auth"], json={"ride_id": ride_id, "seats": 1}).json()["id"]
    assert client.post(f"/bookings/{booking_id}/confirm", headers=bdrv["auth"]).status_code == 200

    with client.websocket_connect(f"/ws/instant/{order['id']}/location") as taxi_pax_ws:
        taxi_pax_ws.send_text(json.dumps({"type": "auth", "token": pax["token"]}))
        with client.websocket_connect(f"/ws/trip/{booking_id}/location") as book_pax_ws:
            book_pax_ws.send_text(json.dumps({"type": "auth", "token": bpax["token"]}))
            with client.websocket_connect(f"/ws/trip/{booking_id}/location") as book_drv_ws:
                book_drv_ws.send_text(json.dumps({"type": "auth", "token": bdrv["token"]}))
                # Кадр в трек брони доходит пассажиру брони (такси-канал рядом не мешает).
                book_drv_ws.send_text(json.dumps({"type": "loc", "lat": 54.01, "lng": 58.02, "ts": 1}))
                msg = json.loads(book_pax_ws.receive_text())
                assert msg["role"] == "driver" and msg["lat"] == 54.01
        # Такси-пассажиру этот кадр НЕ пришёл: шлём эталонный кадр по такси-каналу
        # и убеждаемся, что ПЕРВОЕ полученное сообщение — именно он (очередь пуста).
        with client.websocket_connect(f"/ws/instant/{order['id']}/location") as taxi_drv_ws:
            taxi_drv_ws.send_text(json.dumps({"type": "auth", "token": d["token"]}))
            taxi_drv_ws.send_text(json.dumps({"type": "loc", "lat": 52.62, "lng": 58.34, "ts": 2}))
            first = json.loads(taxi_pax_ws.receive_text())
            assert first["lat"] == 52.62 and first["ts"] == 2
