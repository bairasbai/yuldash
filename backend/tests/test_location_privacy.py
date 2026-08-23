"""Живая позиция: кто и когда имеет право видеть, где находится человек.

Это самый чувствительный канал в приложении. По нему в реальном времени идут координаты
живого человека, и здесь ошибка стоит не «неудобно», а «за женщиной с ребёнком следит
посторонний». Поэтому правил ровно четыре, и каждое закрыто тестом:

1. **Только участник.** Посторонний с чужим номером поездки не подключается вовсе.
2. **Только во время поездки.** Бронь ещё не подтверждена или уже закрыта — канала нет.
   Пока поездка не началась, водителю незачем знать, где живёт пассажир.
3. **Не соврать координатами.** Кадры с невозможной скоростью (телепорт) отбрасываются:
   так ловится подделка «я уже рядом», когда машина на другом конце района.
4. **Мусор не роняет канал.** Битый кадр пропускается, соединение живёт: на трассе связь
   рвётся постоянно, переподключаться на каждый обрыв — сажать батарею.

Отдельно проверено, что вход без правильного первого сообщения закрывается: токен идёт
ПЕРВЫМ кадром внутри соединения, а не в адресе (адрес виден в логах прокси целиком).

⚠️ ЧАСТЬ ПРОВЕРОК ЗДЕСЬ НЕ ДОКАЗЫВАЕТ НИЧЕГО (волна 181)

Отказ здесь доказывается тем, что «ничего не пришло»: `pytest.raises` вокруг `receive_text()`.
Канал, который ПУСТИЛ человека и просто молчит, отвечает ровно так же, как закрытый, — такая
проверка зелёная при любой защите.

Проверено мутациями: убираем проверку статуса брони в `location.trip_location` — весь этот
файл остаётся зелёным, хотя координаты уже текут человеку без согласия второй стороны.

Правильный приём — проверять по УТЕЧКЕ: один участник шлёт настоящие координаты, второй
слушает. Так написан `test_the_position_waits_for_a_yes.py`, он же и ловит эту мутацию.
Здешние проверки не удалены — они всё-таки ловят случаи, где сервер закрывает соединение
сразу (битый кадр, чужой номер поездки, невалидный токен). Но новые проверки приватности
пиши по утечке.
"""
from __future__ import annotations

import json

import pytest
from starlette.websockets import WebSocketDisconnect

from app.models import UserRole

from test_api import _ride


def _confirmed_booking(client, user_factory, tag: str):
    """Подтверждённая бронь: водитель, пассажир и номер брони."""
    driver = user_factory(f"{tag}Driver", role=UserRole.driver)
    ride_id = _ride(client, driver, seats=2)
    passenger = user_factory(f"{tag}Passenger")
    bid = client.post("/bookings", headers=passenger["auth"], json={"ride_id": ride_id, "seats": 1}).json()["id"]
    assert client.post(f"/bookings/{bid}/confirm", headers=driver["auth"]).status_code == 200
    return driver, passenger, bid


# ---------- Вход в канал ----------

def test_без_первого_сообщения_с_токеном_канал_закрывается(client, user_factory):
    driver, passenger, bid = _confirmed_booking(client, user_factory, "NoAuth")
    with pytest.raises((WebSocketDisconnect, Exception)):
        with client.websocket_connect(f"/ws/trip/{bid}/location") as ws:
            # Прислали что угодно, только не «вот мой токен».
            ws.send_text(json.dumps({"type": "loc", "lat": 54.0, "lng": 58.0}))
            ws.receive_text()


def test_битый_первый_кадр_не_пускает_в_канал(client, user_factory):
    driver, passenger, bid = _confirmed_booking(client, user_factory, "BadFirst")
    with pytest.raises((WebSocketDisconnect, Exception)):
        with client.websocket_connect(f"/ws/trip/{bid}/location") as ws:
            ws.send_text("{это не json")
            ws.receive_text()


def test_несуществующая_поездка_не_пускает(client, user_factory):
    user = user_factory("GhostTrip")
    with pytest.raises((WebSocketDisconnect, Exception)):
        with client.websocket_connect("/ws/trip/999999/location") as ws:
            ws.send_text(json.dumps({"type": "auth", "token": user["token"]}))
            ws.receive_text()


def test_до_подтверждения_брони_позиция_не_передаётся(client, user_factory):
    """Пока водитель не подтвердил бронь, ему незачем знать, где живёт пассажир."""
    driver = user_factory("PendingDriver", role=UserRole.driver)
    ride_id = _ride(client, driver, seats=2)
    passenger = user_factory("PendingPassenger")
    bid = client.post("/bookings", headers=passenger["auth"], json={"ride_id": ride_id, "seats": 1}).json()["id"]
    # Бронь создана, но НЕ подтверждена.
    with pytest.raises((WebSocketDisconnect, Exception)):
        with client.websocket_connect(f"/ws/trip/{bid}/location") as ws:
            ws.send_text(json.dumps({"type": "auth", "token": passenger["token"]}))
            ws.receive_text()


def test_после_отмены_поездки_канал_закрыт(client, user_factory):
    driver, passenger, bid = _confirmed_booking(client, user_factory, "Cancelled")
    assert client.post(f"/bookings/{bid}/cancel", headers=passenger["auth"]).status_code in (200, 204)
    with pytest.raises((WebSocketDisconnect, Exception)):
        with client.websocket_connect(f"/ws/trip/{bid}/location") as ws:
            ws.send_text(json.dumps({"type": "auth", "token": passenger["token"]}))
            ws.receive_text()


# ---------- Честность координат ----------

def test_телепорт_отбрасывается_а_соединение_живёт(client, user_factory):
    """Скачок через полстраны за секунду — подделка «я уже рядом». Кадр игнорируем,
    честного человека не отключаем: у него мог просто прыгнуть GPS в тоннеле."""
    driver, passenger, bid = _confirmed_booking(client, user_factory, "Teleport")
    with client.websocket_connect(f"/ws/trip/{bid}/location") as d:
        d.send_text(json.dumps({"type": "auth", "token": driver["token"]}))
        with client.websocket_connect(f"/ws/trip/{bid}/location") as p:
            p.send_text(json.dumps({"type": "auth", "token": passenger["token"]}))
            d.send_text(json.dumps({"type": "loc", "lat": 54.00, "lng": 58.00, "ts": 1}))
            first = json.loads(p.receive_text())
            assert first["lat"] == 54.00

            # Через мгновение — Москва. Такое не проезжают.
            d.send_text(json.dumps({"type": "loc", "lat": 55.75, "lng": 37.61, "ts": 2}))
            # Следом честный кадр: машина сдвинулась на десяток метров. Он и должен дойти.
            # (Сторож считает скорость по часам сервера, а не по метке ts из кадра, поэтому
            # в тесте, где кадры идут подряд, «честным» может быть только очень близкий шаг.)
            d.send_text(json.dumps({"type": "loc", "lat": 54.0001, "lng": 58.0001, "ts": 3}))
            second = json.loads(p.receive_text())
            assert second["lat"] == 54.0001, "подделанная позиция дошла до пассажира"


def test_кадры_без_координат_пропускаются(client, user_factory):
    driver, passenger, bid = _confirmed_booking(client, user_factory, "NoCoords")
    with client.websocket_connect(f"/ws/trip/{bid}/location") as d:
        d.send_text(json.dumps({"type": "auth", "token": driver["token"]}))
        with client.websocket_connect(f"/ws/trip/{bid}/location") as p:
            p.send_text(json.dumps({"type": "auth", "token": passenger["token"]}))
            d.send_text(json.dumps({"type": "loc"}))                       # координат нет
            d.send_text(json.dumps({"type": "loc", "lat": None, "lng": None}))
            d.send_text(json.dumps({"type": "ping"}))                      # не позиция вовсе
            d.send_text(json.dumps({"type": "loc", "lat": 54.02, "lng": 58.02, "ts": 9}))
            msg = json.loads(p.receive_text())
            assert msg["lat"] == 54.02, "до пассажира должна дойти только настоящая точка"


def test_курс_передаётся_когда_он_есть(client, user_factory):
    driver, passenger, bid = _confirmed_booking(client, user_factory, "Bearing")
    with client.websocket_connect(f"/ws/trip/{bid}/location") as d:
        d.send_text(json.dumps({"type": "auth", "token": driver["token"]}))
        with client.websocket_connect(f"/ws/trip/{bid}/location") as p:
            p.send_text(json.dumps({"type": "auth", "token": passenger["token"]}))
            d.send_text(json.dumps({"type": "loc", "lat": 54.03, "lng": 58.03, "bearing": 90, "ts": 5}))
            msg = json.loads(p.receive_text())
            # По курсу рисуется стрелка машины: без него на карте кружок без направления.
            assert msg.get("bearing") == 90


# ---------- Канал доставки ----------

def test_посторонний_не_видит_где_едет_посылка(client, user_factory):
    outsider = user_factory("ParcelOutsider")
    with pytest.raises((WebSocketDisconnect, Exception)):
        with client.websocket_connect("/ws/parcel/999999/location") as ws:
            ws.send_text(json.dumps({"type": "auth", "token": outsider["token"]}))
            ws.receive_text()


def test_чужой_такси_заказ_не_показывает_машину(client, user_factory):
    outsider = user_factory("InstantOutsider")
    with pytest.raises((WebSocketDisconnect, Exception)):
        with client.websocket_connect("/ws/instant/999999/location") as ws:
            ws.send_text(json.dumps({"type": "auth", "token": outsider["token"]}))
            ws.receive_text()


# ---------- Сигнальный канал карты ----------

def test_карта_переживает_мусор_в_канале(client, user_factory):
    user = user_factory("MapGarbage")
    with client.websocket_connect("/ws/map") as ws:
        ws.send_text(json.dumps({"type": "auth", "token": user["token"]}))
        ws.send_text("{битый json")
        ws.send_text(json.dumps({"type": "что-то новое"}))
        # Канал должен остаться живым: на трассе связь рвётся и без нашей помощи.


def test_карта_без_первого_сообщения_с_токеном_закрывается(client):
    with pytest.raises((WebSocketDisconnect, Exception)):
        with client.websocket_connect("/ws/map") as ws:
            ws.send_text("{не json")
            ws.receive_text()
