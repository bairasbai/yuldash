"""Выход из аккаунта не доходил до уже открытого окна.

Кнопка «выйти со всех устройств» — единственное, что человек может сделать, если телефон
украли или отобрали. Она честно гасит ключи входа: новые запросы отбиваются. А вот уже
открытые живые каналы её не замечали (аудит 2026-08-08, волна 141).

Почему. Во всех живых каналах — чат брони, чат такси, чат посылки и трансляция координат —
право перепроверялось раз в пятнадцать ОТПРАВЛЕННЫХ сообщений. Для того, кто пишет, это
работало. Для того, кто ТОЛЬКО СЛУШАЕТ, не работало никогда: он ничего не отправляет, счётчик
стоит на нуле, перепроверка не наступает.

Проверено пробой на живой геолокации: пассажирка подключилась к поездке, вышла из аккаунта —
и продолжила получать координаты водителя. Ключ отозван, поток координат идёт.

Человеческая цена. Телефон в чужих руках, женщина нажимает «выйти со всех устройств» и думает,
что закрыла доступ. На деле тот, у кого телефон, продолжает видеть на карте, где сейчас едет
водитель, и читать переписку в открытом окне.

**Что теперь.** У каждого живого канала есть сторож: раз в полминуты, независимо от того, шлёт
человек что-нибудь или молчит, проверяется — ключ жив и разговор ещё идёт. Нет — соединение
закрывается. Один сторож на все каналы, чтобы правило не разъехалось снова.
"""
from __future__ import annotations

import json
import time
from pathlib import Path

import pytest
from sqlmodel import Session
from starlette.websockets import WebSocketDisconnect

from app import ws_guard
from app.db import engine
from app.models import Booking, BookingStatus, UserRole
from app.services import manager

from test_api import _ride

ROUTERS = Path(__file__).resolve().parents[1] / "app" / "routers"

# Жёсткий предел на каждую историю. Здесь он не украшение: если сторож перестанет закрывать
# канал, ожидание разрыва повиснет НАВСЕГДА и прогон не упадёт, а зависнет — это хуже красного
# теста, потому что выглядит как «всё ещё считается».
pytestmark = pytest.mark.timeout(30)


@pytest.fixture
def быстрый_сторож(monkeypatch):
    """В жизни сторож просыпается раз в полминуты — в тесте ускоряем, логика та же."""
    monkeypatch.setattr(ws_guard, "RECHECK_SEC", 0.3)


@pytest.fixture
def поездка(client, user_factory):
    водитель = user_factory("ГеоВодитель", role=UserRole.driver)
    пассажирка = user_factory("ГеоПассажирка")
    ride_id = _ride(client, водитель, comment="живая поездка")
    bid = client.post("/bookings", headers=пассажирка["auth"],
                      json={"ride_id": ride_id, "seats": 1}).json()["id"]
    client.post(f"/bookings/{bid}/confirm", headers=водитель["auth"])
    return водитель, пассажирка, bid


def _подписок(bid: int) -> int:
    """Сколько окон сейчас слушают координаты по этой поездке."""
    return (len(manager.active_connections.get(-bid * 2, []))
            + len(manager.active_connections.get(-bid * 2 - 1, [])))


def test_выход_из_аккаунта_обрывает_слежку_за_водителем(client, поездка, быстрый_сторож):
    """Главное: «выйти со всех устройств» должно закрывать и уже открытую карту."""
    водитель, пассажирка, bid = поездка

    with client.websocket_connect(f"/ws/trip/{bid}/location") as ws_в:
        ws_в.send_text(json.dumps({"type": "auth", "token": водитель["token"]}))
        with client.websocket_connect(f"/ws/trip/{bid}/location") as ws_п:
            ws_п.send_text(json.dumps({"type": "auth", "token": пассажирка["token"]}))
            time.sleep(0.2)
            ws_в.send_text(json.dumps({"type": "loc", "lat": 54.01, "lng": 58.02}))
            assert json.loads(ws_п.receive_text())["lat"] == pytest.approx(54.01), (
                "в живой поездке координаты не доходят — сломали основную работу"
            )

            client.post("/auth/logout", headers=пассажирка["auth"])
            time.sleep(1.0)                       # сторож просыпается

            with pytest.raises(WebSocketDisconnect):
                ws_п.receive_text()


def test_пока_поездка_идёт_канал_живёт(client, поездка, быстрый_сторож):
    """Обратная сторона: сторож не должен обрывать честных людей посреди дороги."""
    водитель, пассажирка, bid = поездка

    with client.websocket_connect(f"/ws/trip/{bid}/location") as ws_в:
        ws_в.send_text(json.dumps({"type": "auth", "token": водитель["token"]}))
        with client.websocket_connect(f"/ws/trip/{bid}/location") as ws_п:
            ws_п.send_text(json.dumps({"type": "auth", "token": пассажирка["token"]}))
            time.sleep(1.2)                       # сторож проснулся не один раз
            ws_в.send_text(json.dumps({"type": "loc", "lat": 54.0102, "lng": 58.0201}))

            кадр = json.loads(ws_п.receive_text())
            assert кадр["lat"] == pytest.approx(54.0102), (
                "сторож оборвал живую поездку: пассажирка перестала видеть машину по дороге"
            )


def test_после_поездки_канал_закрывается_сам(client, поездка, быстрый_сторож):
    """Слушатель не должен оставаться подписанным на чужие координаты после высадки."""
    водитель, пассажирка, bid = поездка

    with client.websocket_connect(f"/ws/trip/{bid}/location") as ws_в:
        ws_в.send_text(json.dumps({"type": "auth", "token": водитель["token"]}))
        with client.websocket_connect(f"/ws/trip/{bid}/location") as ws_п:
            ws_п.send_text(json.dumps({"type": "auth", "token": пассажирка["token"]}))
            time.sleep(0.2)
            assert _подписок(bid) == 2, "оба окна должны быть подписаны в живой поездке"

            with Session(engine) as s:            # доехали, водитель закрыл поездку
                b = s.get(Booking, bid)
                b.status = BookingStatus.done
                s.add(b)
                s.commit()
            time.sleep(2.5)          # сторож просыпается и закрывает канал

            осталось = _подписок(bid)
            assert осталось == 0, (
                f"после высадки осталось подписок: {осталось}. Человек продолжает получать "
                "координаты чужой машины, хотя поездка закончилась"
            )


def test_выход_из_аккаунта_обрывает_и_чат(client, поездка, быстрый_сторож):
    """Та же дыра была во всех чатах: открытое окно переписки её не замечало."""
    водитель, пассажирка, bid = поездка

    with client.websocket_connect(f"/ws/bookings/{bid}") as ws:
        ws.send_text(json.dumps({"type": "auth", "token": пассажирка["token"]}))
        time.sleep(0.2)
        client.post("/auth/logout", headers=пассажирка["auth"])
        time.sleep(1.0)

        with pytest.raises(WebSocketDisconnect):
            ws.receive_text()


def test_у_каждого_живого_канала_есть_сторож():
    """Сторож на класс: правило должно стоять во ВСЕХ каналах, а не в том, что чинили.

    Ищем по признаку — регистрация соединения в общем менеджере. Раньше проверка права была
    скопирована в несколько мест по-разному и одинаково не работала для слушателя; следующий
    канал скопирует её так же, если не держать это тестом.
    """
    без_сторожа = []
    for файл in ("location.py", "chat.py"):
        строки = (ROUTERS / файл).read_text(encoding="utf-8").splitlines()
        for i, ln in enumerate(строки):
            if "manager.register(" not in ln:
                continue
            if "MAP_FEED_KEY" in ln:
                continue      # общий фид карты: обезличенные пины, личных данных там нет
            окно = "\n".join(строки[i:i + 12])
            if "watch_ws_access" not in окно:
                без_сторожа.append(f"{файл}:{i + 1}")

    assert not без_сторожа, (
        f"живые каналы без сторожа права: {без_сторожа}. У молчащего слушателя право "
        "не перепроверяется никогда — выход из аккаунта его не отключит"
    )


def test_сторож_снимается_вместе_с_каналом():
    """Иначе на каждое закрытое соединение остаётся вечная фоновая задача — утечка."""
    for файл in ("location.py", "chat.py"):
        src = (ROUTERS / файл).read_text(encoding="utf-8")
        assert src.count("watch_ws_access(") == src.count("страж.cancel()"), (
            f"{файл}: число сторожей не совпадает с числом их отмен — задачи будут копиться "
            "после каждого разрыва связи"
        )
