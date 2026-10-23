"""Канал координат не спрашивает базу «пора ли уже?» на каждый кадр (хвост приёмки PR146, 17.10.2026).

Подтверждённая бронь за несколько часов до выезда — ещё договорённость, а не поездка: точку никому
не пересылаем (`visibility.trip_is_live`). Экран поездки при этом открыт, кадры идут раз в 5–10 секунд,
и каждый кадр ходил в базу за ответом «ещё нет» — два запроса на кадр у каждого открытого экрана,
часами. Теперь «ещё нет» запоминается на соединение (`location.PRE_TRIP_RECHECK_SEC`, 30 с).

Что держим, и это не одно и то же:

* **Число запросов не растёт с числом кадров.** Считаем запросы, а не время (образец —
  `test_no_query_per_item._Counter`).
* **Правило показа прежнее.** Запомненное «нет» может только опоздать, но не показать раньше
  времени; «да» берётся лишь из свежего чтения базы. Отмену и прочие правила `trip_is_live`
  по-прежнему решает он, а не запоминание.
* **«Выехал» действует сразу.** Без этого пассажир получил бы пуш «Водитель выехал», открыл карту
  и до 30 секунд смотрел на пустое место. Ручка водителя толкает канал (`trip_wake.poke`), и
  следующий же кадр идёт в базу, не дожидаясь срока.
* **Потерянный толчок — это задержка, а не утечка.** Через срок перепроверка идёт сама.
"""
from __future__ import annotations

import json
import time

import pytest
from sqlmodel import Session

from app import trip_wake
from app.db import engine
from app.models import Booking, BookingStatus, UserRole
from app.routers import location

from test_api import _ride, trip_starts, wait_gate
from test_no_query_per_item import _Counter

# Если толчок «Выехал» потеряется, слушатель будет вечно ждать точку. Отметка работает с тех пор,
# как pytest-timeout стоит в requirements-dev.txt: красный тест через минуту, а не зависший CI.
pytestmark = pytest.mark.timeout(30)


@pytest.fixture
def договорились(client, user_factory):
    """Бронь подтверждена, выезд далеко (2030 год) — поездка ещё не началась."""
    водитель = user_factory("КэшВодитель", role=UserRole.driver)
    пассажир = user_factory("КэшПассажирка")
    ride_id = _ride(client, водитель, seats=2)
    bid = client.post("/bookings", headers=пассажир["auth"], json={"ride_id": ride_id, "seats": 1}).json()["id"]
    assert client.post(f"/bookings/{bid}/confirm", headers=водитель["auth"]).status_code == 200
    return {"booking": bid, "водитель": водитель, "пассажир": пассажир}


def _кадр(i: int) -> str:
    return json.dumps({"type": "loc", "lat": 52.591 + i * 1e-5, "lng": 58.317, "ts": i})


def _авторизация(человек: dict) -> str:
    return json.dumps({"type": "auth", "token": человек["token"]})


class _Часы:
    """Подставные монотонные часы канала: двигаем руками, чтобы не ждать 30 секунд."""

    def __init__(self) -> None:
        self.t = 1000.0

    def __call__(self) -> float:
        return self.t


@pytest.fixture
def часы(monkeypatch) -> _Часы:
    c = _Часы()
    monkeypatch.setattr(location, "_monotonic", c)
    return c


def _выехал_без_толчка(booking_id: int) -> None:
    """Состояние в базе сменилось, а толчка не было — как потерянный или чужой воркер без Redis."""
    with Session(engine) as s:
        b = s.get(Booking, booking_id)
        b.driver_phase = "departed"
        s.add(b)
        s.commit()


# ── Через настоящий сокет ──

def _запросов_к_брони(client, д, кадров: int) -> int:
    """Сколько раз за соединение с `кадров` кадрами сходили в таблицу броней (включая вход)."""
    with _Counter(table="booking") as c:
        with client.websocket_connect(f"/ws/trip/{д['booking']}/location") as ws:
            ws.send_text(_авторизация(д["водитель"]))
            for i in range(кадров):
                ws.send_text(_кадр(i))
    return c.n


def test_число_запросов_не_растёт_вместе_с_числом_кадров(client, договорились):
    """Один кадр и четырнадцать до выезда стоят базе одинаково.

    Четырнадцать, а не больше: на пятнадцатом кадре отдельная, давняя перепроверка токена
    (`msgs % 15`) честно идёт в базу — она к этому тесту не относится.
    """
    один = _запросов_к_брони(client, договорились, 1)
    много = _запросов_к_брони(client, договорились, 14)

    assert много == один, (
        f"на 14 кадров ушло {много} запросов к броням, на один — {один}: база снова спрашивается "
        "на каждый кадр, хотя поездка не началась"
    )


def test_начало_поездки_действует_со_следующего_кадра_даже_внутри_окна(client, договорились, gate_seen):
    """Окно «ещё нет» разогрето тремя кадрами; наступил час до выезда и канал толкнули; ближайшая точка
    идёт к пассажиру. Раньше здесь жали «Выехал» за годы до выезда — с 18.10.2026 это 409 (P2-Г):
    «Выехал» принимается за час до выезда, когда точка и так открыта по времени."""
    д = договорились
    путь = f"/ws/trip/{д['booking']}/location"
    with client.websocket_connect(путь) as ухо:
        ухо.send_text(_авторизация(д["пассажир"]))
        with client.websocket_connect(путь) as рот:
            рот.send_text(_авторизация(д["водитель"]))
            до = gate_seen["n"]
            for i in (1, 2, 3):
                рот.send_text(_кадр(i))
            wait_gate(gate_seen, до)         # первый кадр разобран: окно «ещё нет» разогрето
            trip_starts(д["booking"])        # выезд через 10 минут + толчок канала
            r = client.post(f"/bookings/{д['booking']}/driver-status",
                            headers=д["водитель"]["auth"], json={"status": "departed"})
            assert r.status_code == 200, r.text
            рот.send_text(_кадр(4))

            первая = json.loads(ухо.receive_text())["ts"]

    assert первая == 4, (
        f"пришла точка {первая}: до начала поездки ничего не должно доходить (кадры 1–3), а после — "
        "первая же точка (4). Если здесь зависание — толчок потерялся"
    )


# ── Само решение, без сокета ──

def test_внутри_окна_база_не_читается_а_после_срока_читается(договорились, часы):
    bid = договорились["booking"]
    решение = location._Пора(bid)

    with _Counter(table="booking") as c:
        результаты = [решение.наступила() for _ in range(14)]
    assert not any(результаты)
    assert c.n == 1, f"14 вопросов подряд стоили {c.n} запросов к броням, а должны один"

    часы.t += location.PRE_TRIP_RECHECK_SEC - 1
    with _Counter(table="booking") as c:
        assert решение.наступила() is False
    assert c.n == 0, "за секунду до конца окна база уже читается"

    часы.t += 2
    with _Counter(table="booking") as c:
        assert решение.наступила() is False
    assert c.n == 1, "окно кончилось, а базу не перепроверили"


def test_потерянный_толчок_это_задержка_до_срока_а_не_вечное_нет(договорились, часы):
    """Состояние сменилось без толчка: внутри окна точка ещё не идёт, после окна — идёт."""
    bid = договорились["booking"]
    решение = location._Пора(bid)
    assert решение.наступила() is False

    _выехал_без_толчка(bid)
    часы.t += location.PRE_TRIP_RECHECK_SEC - 1
    assert решение.наступила() is False, "без толчка внутри окна запомненное «нет» держится"

    часы.t += 2
    assert решение.наступила() is True, "после окна канал не заметил, что водитель выехал"


def test_толчок_будит_перепроверку_внутри_окна(договорились, часы):
    bid = договорились["booking"]
    решение = location._Пора(bid)
    assert решение.наступила() is False

    _выехал_без_толчка(bid)
    trip_wake.poke(bid)

    assert решение.наступила() is True, "«Выехал» не подействовал сразу: канал ждёт конца окна"


def test_толчок_сам_ничего_не_открывает_решает_база(договорились, часы):
    """Толкнуть можно и без перемен: точка не пойдёт, пока `trip_is_live` говорит «нет»."""
    bid = договорились["booking"]
    решение = location._Пора(bid)
    assert решение.наступила() is False

    trip_wake.poke(bid)

    assert решение.наступила() is False, "толчок открыл точку за годы до выезда — решать должна база"


def test_отменённая_бронь_не_открывается_ни_толчком_ни_сроком(client, договорились, часы):
    """«Выехал» и отмена: отменили — точки нет, сколько ни толкай и сколько ни жди."""
    д = договорились
    решение = location._Пора(д["booking"])
    assert решение.наступила() is False

    trip_starts(д["booking"])        # «Выехал» принимается за час до выезда (P2-Г) — переносим выезд
    assert client.post(f"/bookings/{д['booking']}/driver-status", headers=д["водитель"]["auth"],
                       json={"status": "departed"}).status_code == 200
    r = client.post(f"/bookings/{д['booking']}/cancel", headers=д["пассажир"]["auth"])
    assert r.status_code in (200, 204), r.text[:150]
    with Session(engine) as s:
        assert s.get(Booking, д["booking"]).status == BookingStatus.cancelled

    assert решение.наступила() is False, "отменённая бронь открылась после толчка «Выехал»"
    часы.t += location.PRE_TRIP_RECHECK_SEC + 1
    assert решение.наступила() is False, "отменённая бронь открылась после окна"


def test_началась_значит_началась_и_база_больше_не_читается(договорились, часы):
    bid = договорились["booking"]
    решение = location._Пора(bid)
    _выехал_без_толчка(bid)
    assert решение.наступила() is True

    with _Counter(table="booking") as c:
        for _ in range(14):
            assert решение.наступила() is True
    assert c.n == 0, "после начала поездки база всё ещё читается на каждый кадр"


# ── Толчок: память процесса, Redis, сбои ──

def test_толчок_виден_после_себя_и_не_виден_до():
    trip_wake.poke(777_001)

    assert trip_wake.poked_since(777_001, 0.001) is True
    assert trip_wake.poked_since(777_001, time.time() + 60) is False, "толчок виден из будущего"
    assert trip_wake.poked_since(777_002, 0.001) is False, "толчок одной брони виден у другой"


def test_толчок_доходит_до_другого_воркера_через_Redis(monkeypatch):
    """Другой воркер не видит память этого процесса — у него только Redis."""
    import fakeredis
    from app import instant_service as isv

    monkeypatch.setattr(isv, "_redis_override", fakeredis.FakeStrictRedis(decode_responses=True))
    trip_wake.poke(777_003)
    trip_wake._local.clear()          # «это другой процесс»: своей памяти о толчке у него нет

    assert trip_wake.poked_since(777_003, 0.001) is True


def test_сбой_Redis_не_роняет_ни_толчок_ни_вопрос(monkeypatch):
    from app import instant_service as isv

    class _Сломан:
        def set(self, *a, **k):
            raise ConnectionError("redis лежит")

        def get(self, *a, **k):
            raise ConnectionError("redis лежит")

    monkeypatch.setattr(isv, "_redis_override", _Сломан())

    trip_wake.poke(777_004)                                      # не бросает
    assert trip_wake.poked_since(777_004, 0.001) is True         # свой процесс всё равно знает
    assert trip_wake.poked_since(777_005, 0.001) is False        # чужую бронь — нет, и без исключения
