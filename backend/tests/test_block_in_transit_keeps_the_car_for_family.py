"""Блокировка «в пути» не отнимает машину у близких и у SOS (решение Александра 17.10.2026).

История. Пассажирка уже в машине («в пути»). Водитель ведёт себя тревожно — она жмёт
«Заблокировать». До этой правки блокировка закрывала канал координат обеим сторонам, и вместе
с точкой для неё замирала последняя точка машины — та, что близкие видят по ссылке
«Поделиться поездкой» и что уходит в SOS. Близкие теряли машину ровно тогда, когда тревожно
(docs/decisions.md, блок WAVE1-TAILS-20261017, «Открыто — решает Александр»).

Теперь, если один из двоих заблокировал другого, а пассажирка уже в машине:
* точка машины по-прежнему пишется — для ссылки близким и для SOS;
* ни один из двоих её больше не получает: пассажирке канал закрывается («Access ended»),
  водителю точка пассажирки не доходит;
* то же, если заблокировал водитель: пассажирка всё равно сидит в этой машине.

До посадки («подтверждена», ещё не села) блокировка закрывает канал как раньше. Конец поездки
(завершение, отмена, «Завершить рейс») гасит запись как раньше.

Ожидание здесь — только «пока условие не выполнится» с запасом по времени, без голого sleep:
соседний набор (`test_blocking_closes_the_map_too.py`) показал, что фиксированные паузы мигают.
"""
from __future__ import annotations

import json
import time
from contextlib import contextmanager
from datetime import timedelta

import fakeredis
import pytest
from sqlmodel import Session
from starlette.websockets import WebSocketDisconnect

from app import instant_service as isv
from app import trip_wake, ws_guard
from app.db import engine
from app.livepos import livepos_get
from app.models import Booking, BookingStatus, UserRole
from app.routers import location
from app.services import manager
from app.timeutil import utcnow

pytestmark = pytest.mark.timeout(60)

ДОЛГОТА = 58.317
# Соседние точки — в 11 м друг от друга: анти-телепорт (`TrackGuard`, 200 км/ч) их пропускает.
ШИРОТА = {1: 52.5911, 2: 52.5912, 3: 52.5913, 4: 52.5914}
_номер = {"n": 0}


@pytest.fixture
def redis_живой():
    """Последняя точка машины (ссылка близким, SOS) живёт в Redis — подставляем ненастоящий."""
    r = fakeredis.FakeStrictRedis(decode_responses=True)
    isv._redis_override = r
    yield r
    isv._redis_override = None


@pytest.fixture
def смс(monkeypatch):
    """Что ушло бы близким в SOS. Остальные SMS (ссылка, «сел в машину») глушим."""
    ушло: list[str] = []
    monkeypatch.setattr("app.routers.safety._send_sos_sms", lambda phones, text: ушло.append(text))
    monkeypatch.setattr("app.routers.safety.notify_admin_telegram", lambda *a, **kw: True)
    monkeypatch.setattr("app.routers.family.send_text", lambda phone, text: None)
    return ушло


def _едем(client, user_factory, тег: str, *, села: bool = True):
    """Водитель, пассажирка, подтверждённая бронь и ссылка маме.

    `села=True` — пассажирка нажала «Я сел» (бронь «в пути»). `села=False` — водитель только
    «Выехал»: поездка идёт, точка уже нужна, но пассажирка ещё не в машине.
    """
    _номер["n"] += 1
    водитель = user_factory(f"Блок в пути {тег} водитель", role=UserRole.driver)
    пассажирка = user_factory(f"Блок в пути {тег} пассажирка")
    рейс = client.post("/rides", headers=водитель["auth"], json={
        "from_city": "Сибай", "to_city": "Уфа",
        "depart_at": (utcnow() + timedelta(minutes=10)).isoformat() + "+00:00",
        "seats_total": 3, "price": 500}).json()
    bid = client.post("/bookings", headers=пассажирка["auth"],
                      json={"ride_id": рейс["id"], "seats": 1}).json()["id"]
    assert client.post(f"/bookings/{bid}/confirm", headers=водитель["auth"]).status_code == 200
    контакт = client.post("/trusted-contacts", headers=пассажирка["auth"], json={
        "name": "Мама", "relation": "мама", "phone": f"+7917555{_номер['n']:04d}"}).json()
    ссылка = client.post(f"/bookings/{bid}/share", headers=пассажирка["auth"],
                         json={"contact_id": контакт["id"]})
    assert ссылка.status_code == 200, ссылка.text
    if села:
        r = client.post(f"/bookings/{bid}/trip-status", headers=пассажирка["auth"], json={"status": "sat"})
    else:
        r = client.post(f"/bookings/{bid}/driver-status", headers=водитель["auth"], json={"status": "departed"})
    assert r.status_code == 200, r.text
    with Session(engine) as s:
        ожидали = BookingStatus.onboard if села else BookingStatus.confirmed
        assert s.get(Booking, bid).status == ожидали, "контроль: бронь не в том статусе"
    return водитель, пассажирка, рейс, bid, ссылка.json()["token"]


@contextmanager
def _окно(client, путь: str):
    """Окно канала. Закрытый сервером сокет при выходе — не ошибка теста: проверка — внутри."""
    вход = client.websocket_connect(путь)
    сокет = вход.__enter__()
    try:
        yield сокет
    finally:
        try:
            вход.__exit__(None, None, None)
        except Exception:  # noqa: BLE001 — сервер уже закрыл канал
            pass


def _вход(человек: dict) -> str:
    return json.dumps({"type": "auth", "token": человек["token"]})


def _кадр(i: int) -> str:
    return json.dumps({"type": "loc", "lat": ШИРОТА[i], "lng": ДОЛГОТА, "ts": i})


def _записана(bid: int, i: int, срок: float = 3.0) -> bool:
    """Дождаться, пока точка `i` окажется в кэше для ссылки близким и SOS. Нет за `срок` — False."""
    до = time.monotonic() + срок
    while time.monotonic() < до:
        поз = livepos_get("booking", bid)
        if поз and abs(поз["lat"] - ШИРОТА[i]) < 1e-9:
            return True
        time.sleep(0.02)
    return False


def _всё_до_закрытия(окно) -> tuple[list, WebSocketDisconnect]:
    """Всё, что пришло в окно, пока сервер его не закрыл, и само закрытие."""
    пришло = []
    while True:
        try:
            пришло.append(json.loads(окно.receive_text()))
        except WebSocketDisconnect as закрытие:
            return пришло, закрытие


def _машина_у_мамы(client, токен: str):
    st = client.get(f"/t/{токен}/state.json")
    assert st.status_code == 200, st.text
    return st.json()


def _sos_без_своего_места(client, кто: dict, bid: int, смс: list) -> str:
    """SOS из поездки, когда GPS телефона не схватился: место берётся у машины."""
    r = client.post("/sos", headers=кто["auth"], json={"category": "other", "booking_id": bid, "note": "тревожно"})
    assert r.status_code == 200, r.text
    assert смс, "SOS не дошёл до близких вовсе"
    return смс[-1]


def _водитель_слушает(bid: int) -> bool:
    """Записан ли кто-нибудь на приём точки пассажирки (её шлют в «ящик водителя» −bid·2)."""
    return bool(manager.active_connections.get(-bid * 2))


# ── Пассажирка «в пути» блокирует водителя ──

def test_пассажирка_в_пути_блокирует_водителя_мама_и_sos_видят_машину_а_она_нет(
        client, user_factory, redis_живой, смс):
    водитель, пассажирка, _рейс, bid, токен = _едем(client, user_factory, "она")
    путь = location_path(bid)
    with _окно(client, путь) as ухо:
        ухо.send_text(_вход(пассажирка))
        with _окно(client, путь) as рот:
            рот.send_text(_вход(водитель))
            рот.send_text(_кадр(1))
            assert json.loads(ухо.receive_text())["ts"] == 1, "контроль: поездка идёт, точка доходит"

            assert client.post("/blocks", headers=пассажирка["auth"],
                               json={"blocked_user_id": водитель["id"]}).status_code == 200

            рот.send_text(_кадр(2))
            assert _записана(bid, 2), (
                "пассажирка в машине заблокировала водителя — и последняя точка машины для мамы и SOS "
                "замерла: канал водителя закрылся вместе с её каналом")
            assert not _водитель_слушает(bid), "водитель после блокировки всё ещё принимает её точку"

            # Её канал: ничего не пришло после блокировки, и он закрыт с причиной сторожа.
            ухо.send_text(_кадр(1))
            пришло, закрытие = _всё_до_закрытия(ухо)
            assert not пришло, f"она заблокировала водителя, а его точка дошла до неё: {пришло}"
            assert закрытие.code == 1008 and закрытие.reason == "Access ended"

            # Канал водителя жив: следующая точка тоже пишется — мама видит, где машина сейчас.
            рот.send_text(_кадр(3))
            assert _записана(bid, 3), "после блокировки записалась одна точка, дальше машина замерла"

            мама = _машина_у_мамы(client, токен)
            assert мама["status"] == "onboard"
            assert мама["car"] is not None and abs(мама["car"]["lat"] - ШИРОТА[3]) < 1e-9, (
                f"мама по ссылке не видит, где машина сейчас: {мама}")
            текст = _sos_без_своего_места(client, пассажирка, bid, смс)
            assert f"pt={ДОЛГОТА},{ШИРОТА[3]}" in текст, (
                f"SOS ушёл без свежего места машины — родные не знают, куда ехать: {текст}")

    with Session(engine) as s:
        assert s.get(Booking, bid).status == BookingStatus.onboard, "блокировка бронь не меняет"


# ── Водитель блокирует пассажирку «в пути» ──

def test_водитель_блокирует_пассажирку_в_пути_её_мама_и_sos_видят_машину(
        client, user_factory, redis_живой, смс):
    """Пассажирка физически сидит в этой машине — её близким машина нужна так же."""
    водитель, пассажирка, _рейс, bid, токен = _едем(client, user_factory, "он")
    путь = location_path(bid)
    with _окно(client, путь) as ухо:
        ухо.send_text(_вход(пассажирка))
        with _окно(client, путь) as рот:
            рот.send_text(_вход(водитель))
            рот.send_text(_кадр(1))
            assert json.loads(ухо.receive_text())["ts"] == 1, "контроль: поездка идёт, точка доходит"

            assert client.post("/blocks", headers=водитель["auth"],
                               json={"blocked_user_id": пассажирка["id"]}).status_code == 200

            рот.send_text(_кадр(2))
            assert _записана(bid, 2), (
                "водитель заблокировал пассажирку, которая сидит у него в машине, — и её мама "
                "потеряла машину на карте")
            assert not _водитель_слушает(bid), "водитель заблокировал её, но всё ещё принимает её точку"

            ухо.send_text(_кадр(1))
            пришло, закрытие = _всё_до_закрытия(ухо)
            assert not пришло, f"водитель её заблокировал, а его точка дошла до неё: {пришло}"
            assert закрытие.code == 1008 and закрытие.reason == "Access ended"

            рот.send_text(_кадр(3))
            assert _записана(bid, 3)
            мама = _машина_у_мамы(client, токен)
            assert мама["car"] is not None and abs(мама["car"]["lat"] - ШИРОТА[3]) < 1e-9, мама
            текст = _sos_без_своего_места(client, пассажирка, bid, смс)
            assert f"pt={ДОЛГОТА},{ШИРОТА[3]}" in текст, текст


# ── До посадки — как раньше ──

@pytest.mark.parametrize("кто_блокирует", ["пассажирка", "водитель"])
def test_блокировка_до_посадки_закрывает_канал_как_раньше(
        client, user_factory, redis_живой, смс, кто_блокирует):
    """Водитель «Выехал», но пассажирка ещё не села: её близким машина не нужна — она дома.
    Блокировка закрывает канал водителя сразу, как и до этой правки, и точка больше не пишется."""
    водитель, пассажирка, _рейс, bid, _токен = _едем(client, user_factory, f"до-{кто_блокирует}", села=False)
    путь = location_path(bid)
    with _окно(client, путь) as ухо:
        ухо.send_text(_вход(пассажирка))
        with _окно(client, путь) as рот:
            рот.send_text(_вход(водитель))
            рот.send_text(_кадр(1))
            assert json.loads(ухо.receive_text())["ts"] == 1, "контроль: поездка идёт, точка доходит"
            assert _записана(bid, 1)

            блокирует, кого = (пассажирка, водитель) if кто_блокирует == "пассажирка" else (водитель, пассажирка)
            assert client.post("/blocks", headers=блокирует["auth"],
                               json={"blocked_user_id": кого["id"]}).status_code == 200

            рот.send_text(_кадр(2))
            _пришло_водителю, закрытие = _всё_до_закрытия(рот)
            assert закрытие.code == 1008 and закрытие.reason == "Access ended", (
                "до посадки блокировка должна закрывать канал водителя, как раньше")
            поз = livepos_get("booking", bid)
            assert поз and abs(поз["lat"] - ШИРОТА[1]) < 1e-9, (
                f"до посадки точка водителя после блокировки всё равно записалась: {поз}")

            ухо.send_text(_кадр(1))
            пришло, закрытие = _всё_до_закрытия(ухо)
            assert not пришло and закрытие.reason == "Access ended", пришло


# ── Конец поездки после блокировки — запись гаснет, как раньше ──

_КОНЕЦ = {
    "водитель_завершил": lambda c, в, п, рейс, bid: c.post(
        f"/bookings/{bid}/driver-status", headers=в["auth"], json={"status": "done"}),
    "пассажирка_завершила": lambda c, в, п, рейс, bid: c.post(
        f"/bookings/{bid}/trip-status", headers=п["auth"], json={"status": "done"}),
    "водитель_отменил": lambda c, в, п, рейс, bid: c.post(
        f"/bookings/{bid}/cancel", headers=в["auth"], json={"reason": "other"}),
    "рейс_завершён": lambda c, в, п, рейс, bid: c.post(f"/rides/{рейс['id']}/complete", headers=в["auth"]),
}


@pytest.mark.parametrize("как", list(_КОНЕЦ))
def test_конец_поездки_после_блокировки_гасит_запись(client, user_factory, redis_живой, смс, как):
    водитель, пассажирка, рейс, bid, токен = _едем(client, user_factory, f"конец-{как}")
    путь = location_path(bid)
    with _окно(client, путь) as рот:
        рот.send_text(_вход(водитель))
        рот.send_text(_кадр(1))
        assert _записана(bid, 1), "контроль: поездка идёт, точка пишется"
        assert client.post("/blocks", headers=пассажирка["auth"],
                           json={"blocked_user_id": водитель["id"]}).status_code == 200
        рот.send_text(_кадр(2))
        assert _записана(bid, 2), "после блокировки «в пути» точка для мамы и SOS должна писаться"

        r = _КОНЕЦ[как](client, водитель, пассажирка, рейс, bid)
        assert r.status_code == 200, r.text

        рот.send_text(_кадр(3))
        _пришло, закрытие = _всё_до_закрытия(рот)
    assert закрытие.code == 1008 and закрытие.reason == "Trip ended", (
        f"поездка кончилась ({как}), а канал водителя закрылся не так, как в конце поездки: {закрытие.reason}")
    поз = livepos_get("booking", bid)
    assert поз and abs(поз["lat"] - ШИРОТА[2]) < 1e-9, (
        f"поездка кончилась ({как}), а точка водителя всё ещё пишется: {поз}")
    мама = _машина_у_мамы(client, токен)
    assert мама["status"] == "finished" and мама["car"] is None, мама


# ── Толчок потерялся — сторож всё равно не отнимает машину у мамы ──

def test_без_толчка_сторож_переводит_водителя_в_запись_а_не_закрывает(
        client, user_factory, redis_живой, смс, monkeypatch):
    """Толчок блокировки дошёл не до того процесса (чужой воркер без Redis). Канал узнаёт о
    блокировке от сторожа (≤ 30 с): канал пассажирки он закрывает, а канал водителя «в пути» —
    нет, только снимает с приёма и перестаёт пересылать."""
    monkeypatch.setattr(ws_guard, "RECHECK_SEC", 0.05)
    водитель, пассажирка, _рейс, bid, токен = _едем(client, user_factory, "сторож")
    путь = location_path(bid)
    with _окно(client, путь) as ухо:
        ухо.send_text(_вход(пассажирка))
        with _окно(client, путь) as рот:
            рот.send_text(_вход(водитель))
            рот.send_text(_кадр(1))
            assert json.loads(ухо.receive_text())["ts"] == 1, "контроль: поездка идёт, точка доходит"

            monkeypatch.setattr(trip_wake, "poke", lambda bid: None)   # толчок потерялся
            assert client.post("/blocks", headers=пассажирка["auth"],
                               json={"blocked_user_id": водитель["id"]}).status_code == 200

            до = time.monotonic() + 10
            while _водитель_слушает(bid) and time.monotonic() < до:
                time.sleep(0.02)
            assert not _водитель_слушает(bid), "сторож так и не снял водителя с приёма её точки"

            # Две точки, а не одна: кадр, которого цикл канала уже ждал, сервер дочитывает и после
            # закрытия сторожем — канал умирает на следующем. Жив ли канал, показывает вторая точка.
            for i in (2, 3):
                try:
                    рот.send_text(_кадр(i))
                except Exception:  # noqa: BLE001 — канал уже закрыт сервером: точка не уйдёт
                    pass
                assert _записана(bid, i), (
                    f"сторож закрыл канал водителя «в пути»: точка {i} не записана — мама и SOS "
                    "потеряли машину")
            пришло, закрытие = _всё_до_закрытия(ухо)
            assert not пришло, f"после блокировки точка водителя дошла до неё: {пришло}"
            assert закрытие.code == 1008 and закрытие.reason == "Access ended"
            assert _машина_у_мамы(client, токен)["car"]["lat"] == pytest.approx(ШИРОТА[3])


# ── Водитель переподключился после блокировки (туннель, перезапуск приложения) ──

def test_водитель_переподключился_после_блокировки_точка_для_мамы_пишется(
        client, user_factory, redis_живой, смс):
    водитель, пассажирка, _рейс, bid, токен = _едем(client, user_factory, "снова")
    assert client.post("/blocks", headers=пассажирка["auth"],
                       json={"blocked_user_id": водитель["id"]}).status_code == 200
    with _окно(client, location_path(bid)) as рот:
        рот.send_text(_вход(водитель))
        рот.send_text(_кадр(1))
        assert _записана(bid, 1), (
            "водитель переподключился после блокировки (туннель) — и мама больше не видит машину")
        assert not _водитель_слушает(bid)
        рот.send_text(_кадр(2))
        assert _записана(bid, 2)
    assert _машина_у_мамы(client, токен)["car"]["lat"] == pytest.approx(ШИРОТА[2])


# ── Решение канала напрямую: кому что ──

def test_решение_канала_водителю_только_запись_пассажирке_закрыто(client, user_factory, смс):
    водитель, пассажирка, _рейс, bid, _токен = _едем(client, user_factory, "решение")
    assert client.post("/blocks", headers=пассажирка["auth"],
                       json={"blocked_user_id": водитель["id"]}).status_code == 200

    водителю = location._Пора(bid, я=водитель["id"])
    assert водителю.наступила() is True and водителю.только_запись is True
    assert водителю.кончилась is False

    ей = location._Пора(bid, я=пассажирка["id"])
    assert ей.наступила() is False and ей.кончилась is True and ей.причина == "Access ended"

    безымянное = location._Пора(bid)     # без «кто я» — как раньше: блокировка закрывает
    assert безымянное.наступила() is False and безымянное.причина == "Access ended"

    assert client.post(f"/bookings/{bid}/driver-status", headers=водитель["auth"],
                       json={"status": "done"}).status_code == 200
    assert водителю.наступила() is False and водителю.кончилась is True
    assert водителю.причина == "Trip ended"


def location_path(bid: int) -> str:
    return f"/ws/trip/{bid}/location"
