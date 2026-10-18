"""Конец сделки гасит канал координат сразу, а «в пути» пассажир отменить не может (приёмка PR155, 17.10.2026).

Две находки проверяющего PR155, обе про сервер:

* **Пассажир «в пути» отменял бронь прямым запросом** (200, `cancelled`). В приложении и на сайте
  кнопки нет, но сервер не запрещал: место возвращалось в продажу, пока человек сидит в машине,
  а у брони пропадали чат, «поделиться с близким» и SOS. Теперь 409 с понятным текстом.
  Водителю «в пути» отмена по-прежнему открыта (кроме «не пришёл» — решение 14.10).
* **После отмены точка водителя ещё до 30 с уходила пассажиру отменённой брони** — канал закрывал
  только сторож по часам. Теперь отмена, неявка и снятие поездки толкают канал
  (`trip_wake.poke`), и ближайший кадр закрывает его с «Trip ended».

Приёмка PR157 добавила остальные пути, где сделка кончается: «Завершить» у водителя и пассажира,
«Завершить рейс», пауза по разбору и «Заблокировать» (бронь жива, но канал закрывается с
причиной сторожа «Access ended»). И таймаут клиента Redis: его зовут на каждом кадре.
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
from app.routers.bookings import NO_SHOW_WAIT
from app.timeutil import utcnow
from boarding_helpers import settle_boarding

pytestmark = pytest.mark.timeout(30)


def _поездка(client, user_factory, тег: str, мест: int = 3, выезд: timedelta = timedelta(minutes=10)):
    водитель = user_factory(f"Конец {тег} водитель", role=UserRole.driver)
    ride = client.post("/rides", headers=водитель["auth"], json={
        "from_city": "Сибай", "to_city": "Уфа",
        "depart_at": (utcnow() + выезд).isoformat() + "+00:00",
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
    # Посадку отмечает водитель («Сел», решение 17.10.2026).
    assert client.post(f"/bookings/{bid}/driver-status", headers=водитель["auth"],
                       json={"status": "boarded"}).status_code == 200
    with Session(engine) as s:
        мест_было = s.get(Ride, ride["id"]).seats_left

    r = client.post(f"/bookings/{bid}/cancel", headers=пассажир["auth"], json={"reason": "plans_changed"})
    assert r.status_code == 409, r.text
    detail = r.json()["detail"]
    # Отказ видит прежде всего тот, кто НЕ садился, — текст ведёт к разбору (приёмка PR174).
    assert "отметил посадку" in detail["ru"] and "сообщи о проблеме" in detail["ru"]
    assert detail["ba"].strip() and detail["ba"] != detail["ru"]
    assert detail["code"] == "onboard_no_cancel"
    with Session(engine) as s:
        assert s.get(Booking, bid).status == BookingStatus.onboard
        assert s.get(Ride, ride["id"]).seats_left == мест_было, "место ушло в продажу, а человек в машине"


def test_водителю_в_пути_отмена_с_другой_причиной_открыта_как_раньше(client, user_factory):
    """Решение 14.10 запрещает водителю только «не пришёл» по «в пути»; остальное не трогаем."""
    водитель, ride = _поездка(client, user_factory, "водитель")
    пассажир, bid = _бронь(client, user_factory, водитель, ride, "водитель")
    # Посадку отмечает водитель («Сел», решение 17.10.2026).
    assert client.post(f"/bookings/{bid}/driver-status", headers=водитель["auth"],
                       json={"status": "boarded"}).status_code == 200
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

    # Выехал давно: «Не пришёл» доступна только после выезда + ожидание (решение 18.10.2026).
    водитель, ride = _поездка(client, user_factory, "толчки", мест=3, выезд=-(NO_SHOW_WAIT + timedelta(minutes=1)))
    п1, b1 = _бронь(client, user_factory, водитель, ride, "толчки-1")
    _п2, b2 = _бронь(client, user_factory, водитель, ride, "толчки-2")
    _п3, b3 = _бронь(client, user_factory, водитель, ride, "толчки-3")

    assert client.post(f"/bookings/{b1}/cancel", headers=п1["auth"], json={"reason": ""}).status_code == 200
    assert b1 in толкнули, "отмена пассажиром не толкнула канал"
    assert client.post(f"/bookings/{b2}/no-show", headers=водитель["auth"]).status_code == 200
    assert b2 in толкнули, "неявка не толкнула канал"
    assert client.post(f"/rides/{ride['id']}/cancel", headers=водитель["auth"]).status_code == 200
    assert b3 in толкнули, "снятие поездки не толкнуло каналы её броней"


# ── Приёмка PR157: остальные пути, где сделка кончается ──

def _идёт(client, водитель, bid) -> location._Пора:
    """Поездка началась («Выехал»), решение канала — «да»."""
    assert client.post(f"/bookings/{bid}/driver-status", headers=водитель["auth"],
                       json={"status": "departed"}).status_code == 200
    решение = location._Пора(bid)
    assert решение.наступила() is True
    return решение


def test_завершение_водителем_пассажиром_и_всего_рейса_гасит_канал_сразу(client, user_factory):
    """«Завершить» у водителя, «Завершил» у пассажира и «Завершить рейс» — тоже конец сделки:
    без толчка точка шла бы ещё до 30 с (до сторожа)."""
    водитель, ride = _поездка(client, user_factory, "завершение", мест=3)
    _п1, b1 = _бронь(client, user_factory, водитель, ride, "завершение-1")
    п2, b2 = _бронь(client, user_factory, водитель, ride, "завершение-2")
    _п3, b3 = _бронь(client, user_factory, водитель, ride, "завершение-3")
    р1, р2, р3 = (_идёт(client, водитель, b) for b in (b1, b2, b3))

    assert client.post(f"/bookings/{b1}/driver-status", headers=водитель["auth"],
                       json={"status": "done"}).status_code == 200
    assert р1.наступила() is False and р1.кончилась is True, "«Завершить» у водителя не погасил канал"

    # Пассажир завершает только после «Сел» водителя (решения 17.10.2026).
    assert client.post(f"/bookings/{b2}/driver-status", headers=водитель["auth"],
                       json={"status": "boarded"}).status_code == 200
    assert р2.наступила() is True, "отметка «Сел» не должна гасить канал — поездка идёт"
    settle_boarding(b2)   # пассажир завершает после окна «Отменить» водителя (приёмка PR174)
    assert client.post(f"/bookings/{b2}/trip-status", headers=п2["auth"],
                       json={"status": "done"}).status_code == 200
    assert р2.наступила() is False and р2.кончилась is True, "«Завершил» у пассажира не погасил канал"

    assert client.post(f"/rides/{ride['id']}/complete", headers=водитель["auth"]).status_code == 200
    assert р3.наступила() is False and р3.кончилась is True, "«Завершить рейс» не погасил канал"


def test_пауза_по_разбору_гасит_канал_водителя_сразу(client, user_factory):
    """Водитель, которого разбор поставил на паузу, не должен и полминуты видеть точку пассажирки."""
    from app.safety_logic import cancel_upcoming_after_suspension

    водитель, ride = _поездка(client, user_factory, "пауза")
    _пассажир, bid = _бронь(client, user_factory, водитель, ride, "пауза")
    решение = _идёт(client, водитель, bid)
    with Session(engine) as s:
        assert cancel_upcoming_after_suspension(s, водитель["id"]) >= 1
    assert решение.наступила() is False and решение.кончилась is True


def test_блокировка_гасит_канал_сразу_с_причиной_как_у_сторожа(client, user_factory):
    """Бронь жива, но пассажирка нажала «Заблокировать»: канал закрывается на ближайшем кадре,
    а не через сторож (≤ 30 с), и с той же причиной, что у сторожа."""
    водитель, ride = _поездка(client, user_factory, "блок")
    пассажир, bid = _бронь(client, user_factory, водитель, ride, "блок")
    assert client.post(f"/bookings/{bid}/driver-status", headers=водитель["auth"],
                       json={"status": "departed"}).status_code == 200
    путь = f"/ws/trip/{bid}/location"
    with client.websocket_connect(путь) as ухо:
        ухо.send_text(_вход(водитель))
        with client.websocket_connect(путь) as рот:
            рот.send_text(_вход(пассажир))
            рот.send_text(_кадр(1))
            assert json.loads(ухо.receive_text())["ts"] == 1, "контроль: поездка идёт, точка доходит"

            assert client.post("/blocks", headers=пассажир["auth"],
                               json={"blocked_user_id": водитель["id"]}).status_code == 200

            рот.send_text(_кадр(2))
            with pytest.raises(WebSocketDisconnect) as закрыли:
                рот.receive_text()
    assert закрыли.value.code == 1008 and закрыли.value.reason == "Access ended"
    with Session(engine) as s:
        assert s.get(Booking, bid).status == BookingStatus.confirmed, "блокировка бронь не меняет"


def test_блокировка_до_начала_поездки_тоже_закрывает_канал(client, user_factory):
    """Экран поездки открыт заранее (выезд послезавтра), точка ещё не идёт: блокировка закрывает
    и такой канал — иначе после «Выехал» точка ушла бы заблокированному до сторожа."""
    водитель = user_factory("Конец блокрано водитель", role=UserRole.driver)
    ride = client.post("/rides", headers=водитель["auth"], json={
        "from_city": "Сибай", "to_city": "Уфа",
        "depart_at": (utcnow() + timedelta(days=2)).isoformat() + "+00:00",
        "seats_total": 3, "price": 500}).json()
    пассажир, bid = _бронь(client, user_factory, водитель, ride, "блокрано")
    решение = location._Пора(bid)
    assert решение.наступила() is False and решение.кончилась is False, "контроль: ещё не пора"
    assert client.post("/blocks", headers=водитель["auth"],
                       json={"blocked_user_id": пассажир["id"]}).status_code == 200
    assert решение.наступила() is False and решение.кончилась is True
    assert решение.причина == "Access ended"


def test_я_сел_между_первым_чтением_и_замком_всё_равно_409(client, user_factory, monkeypatch):
    """Отказ стоит ПОСЛЕ перечитывания под замком: «Я сел», пришедший между первым чтением брони
    и замком, не даёт отменить по устаревшему «подтверждена» (проверяющий PR157)."""
    from app.routers import bookings as ручки

    водитель, ride = _поездка(client, user_factory, "гонка")
    пассажир, bid = _бронь(client, user_factory, водитель, ride, "гонка")
    настоящая = ручки.booking_and_ride_for_user

    def устаревшая(session, booking_id, user):
        b, r = настоящая(session, booking_id, user)
        assert b.status == BookingStatus.confirmed
        with Session(engine) as s2:   # «Я сел» коммитится, пока ручка держит старое чтение
            x = s2.get(Booking, booking_id)
            x.status = BookingStatus.onboard
            s2.add(x)
            s2.commit()
        return b, r

    monkeypatch.setattr(ручки, "booking_and_ride_for_user", устаревшая)
    with Session(engine) as s:
        мест = s.get(Ride, ride["id"]).seats_left
    r = client.post(f"/bookings/{bid}/cancel", headers=пассажир["auth"], json={"reason": "plans_changed"})
    assert r.status_code == 409 and r.json()["detail"]["code"] == "onboard_no_cancel", r.text
    with Session(engine) as s:
        assert s.get(Booking, bid).status == BookingStatus.onboard
        assert s.get(Ride, ride["id"]).seats_left == мест


def test_кончившаяся_поездка_назад_не_оживает(client, user_factory):
    """Решение «канал закрыть» не откатывается, даже если бронь в базе снова «живая» и пришёл толчок."""
    водитель, ride = _поездка(client, user_factory, "липко")
    пассажир, bid = _бронь(client, user_factory, водитель, ride, "липко")
    решение = _идёт(client, водитель, bid)
    assert client.post(f"/bookings/{bid}/cancel", headers=пассажир["auth"], json={"reason": ""}).status_code == 200
    assert решение.наступила() is False and решение.кончилась is True
    with Session(engine) as s:
        b = s.get(Booking, bid)
        b.status = BookingStatus.confirmed
        s.add(b)
        s.commit()
    trip_wake.poke(bid)
    assert решение.наступила() is False and решение.кончилась is True


def test_клиент_redis_не_ждёт_вечно(monkeypatch):
    """Синхронный клиент зовут и из живых каналов на каждом кадре: без таймаута зависший Redis
    замораживал бы весь процесс со всеми каналами (проверяющий PR157)."""
    import redis

    from app import services
    from app.config import settings

    взяли: dict = {}
    monkeypatch.setattr(redis, "from_url", lambda url, **kw: (взяли.update(kw), object())[1])
    monkeypatch.setattr(settings, "redis_url", "redis://127.0.0.1:1/0")
    monkeypatch.setattr(services, "_cache", None)
    monkeypatch.setattr(services, "_cache_tried", False)
    assert services._cache_client() is not None
    assert 0 < взяли.get("socket_timeout", 0) <= 2
    assert 0 < взяли.get("socket_connect_timeout", 0) <= 2
