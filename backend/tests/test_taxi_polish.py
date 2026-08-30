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


# ============================ «Уже выхожу» до приезда ============================
def test_passenger_can_warn_driver_while_car_is_coming(client, user_factory, fake_redis):
    """CTA компактного экрана C работает в accepted, но не до принятия и не от водителя."""
    d, pax, offered = _offered_order(client, user_factory, fake_redis, "ComingDrv", "ComingPax")
    url = f"/instant/orders/{offered['id']}/im-coming"
    assert client.post(url, headers=pax["auth"]).status_code == 409

    accepted = client.post(f"/instant/orders/{offered['id']}/accept", headers=d["auth"])
    assert accepted.status_code == 200, accepted.text
    assert "driver_car_color" in accepted.json()
    assert client.post(url, headers=pax["auth"]).status_code == 200
    assert client.post(url, headers=d["auth"]).status_code == 403


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


# ============================ Рейтинг пассажира в оффере (B7a-4) ============================
def test_offer_payload_shows_passenger_rating_and_trips(client, user_factory, fake_redis):
    """После завершённой поездки и оценки водителем оффер показывает ★-агрегат и число
    поездок (такси-done + попутка-done). Телефона/имени в оффере по-прежнему нет."""
    from sqlmodel import Session

    from app.db import engine
    from app.models import Booking, BookingStatus
    from test_instant import _create_order, _heartbeat, ORIG

    d, pax, order = _accepted_order(client, user_factory, fake_redis, "RateDrv", "RatePax")
    oid = order["id"]
    client.post(f"/instant/orders/{oid}/arrived", headers=d["auth"])
    client.post(f"/instant/orders/{oid}/onboard", headers=d["auth"])
    assert client.post(f"/instant/orders/{oid}/done", headers=d["auth"]).json()["status"] == "done"
    assert client.post(f"/instant/orders/{oid}/rate", headers=d["auth"], json={"stars": 4}).status_code == 200

    # Завершённая бронь попутки того же пассажира — тоже считается поездкой.
    bdrv = user_factory("RateBookDrv", role=UserRole.driver)
    ride_id = _ride(client, bdrv, seats=1)
    bid = client.post("/bookings", headers=pax["auth"], json={"ride_id": ride_id, "seats": 1}).json()["id"]
    assert client.post(f"/bookings/{bid}/confirm", headers=bdrv["auth"]).status_code == 200
    with Session(engine) as s:
        b = s.get(Booking, bid)
        b.status = BookingStatus.done
        s.add(b)
        s.commit()

    # Новый заказ того же пассажира → оффер тому же водителю.
    _heartbeat(client, d, ORIG)
    order2 = _create_order(client, pax)
    assert order2["status"] == "offered"
    offer = client.get("/instant/driver/offer", headers=d["auth"]).json()["offer"]
    assert offer is not None and offer["id"] == order2["id"]
    assert offer["passenger_rating"] == 4.0
    assert offer["passenger_trips"] == 2          # 1 такси-done + 1 бронь-done
    # Приватность: до accept ни телефона, ни имени (агрегат — не персональные данные).
    assert offer["passenger_phone"] == "" and offer["passenger_name"] == ""


def test_offer_payload_passenger_rating_null_for_newbie(client, user_factory, fake_redis):
    """Новичок без оценок и поездок: passenger_rating = null, passenger_trips = 0 —
    клиент показывает честное «новичок» вместо выдуманной ★."""
    d, pax, order = _offered_order(client, user_factory, fake_redis, "NewbDrv", "NewbPax")
    offer = client.get("/instant/driver/offer", headers=d["auth"]).json()["offer"]
    assert offer is not None and offer["id"] == order["id"]
    assert offer["passenger_rating"] is None
    assert offer["passenger_trips"] == 0
    assert offer["passenger_phone"] == "" and offer["passenger_name"] == ""


def test_offer_payload_has_live_distance_and_eta_to_passenger(client, user_factory, fake_redis):
    """Подача в оффере относится к ЭТОМУ водителю, а не к машине, по которой считали цену."""
    from app.config import settings
    from test_instant import _create_order, _driver_online, _heartbeat, ORIG

    driver = _driver_online(client, user_factory, "PickupEtaDrv")
    # Около двух километров по прямой: внутри первого радиуса matcher, но не «у пассажира».
    _heartbeat(client, driver, (ORIG[0] + 0.018, ORIG[1]))
    passenger = user_factory("PickupEtaPax")
    order = _create_order(client, passenger)
    assert order["status"] == "offered"

    offer = client.get("/instant/driver/offer", headers=driver["auth"]).json()["offer"]
    assert offer is not None and offer["id"] == order["id"]
    assert offer["offer_pickup_km"] > 1.0
    expected_eta = max(1, round(offer["offer_pickup_km"] / settings.instant_avg_speed_kmh * 60))
    assert offer["offer_pickup_eta_min"] == expected_eta


def test_passenger_view_has_no_rating_computation(client, user_factory, fake_redis):
    """Витрина пассажира: агрегат «про себя» не считаем (лишние запросы) — схема стабильна
    (passenger_rating присутствует, но None/0)."""
    d, pax, order = _offered_order(client, user_factory, fake_redis, "SelfDrv", "SelfPax")
    mine = client.get(f"/instant/orders/{order['id']}", headers=pax["auth"]).json()
    assert mine["role"] == "passenger"
    assert mine["passenger_rating"] is None and mine["passenger_trips"] == 0
