"""Блокировка «в пути» не отнимает машину у близких и у SOS (решение Александра 17.10.2026).

История. Пассажирка уже в машине («в пути»). Водитель ведёт себя тревожно — она жмёт
«Заблокировать». До этой правки блокировка закрывала канал координат обеим сторонам, и вместе
с точкой для неё замирала последняя точка машины — та, что близкие видят по ссылке
«Поделиться поездкой» и что уходит в SOS. Близкие теряли машину ровно тогда, когда тревожно
(docs/decisions.md, блок WAVE1-TAILS-20261017, «Открыто — решает Александр»).

Теперь, если один из двоих заблокировал другого ПОСЛЕ посадки (водитель отметил «Сел»):
* точка машины по-прежнему пишется — для ссылки близким и для SOS;
* по живому каналу ни один из двоих её больше не получает: пассажирке канал закрывается
  («Access ended»), водителю точка пассажирки не доходит;
* то же, если заблокировал водитель: пассажирка всё равно сидит в этой машине;
* честно о том, что остаётся видно (вопрос Q16, по умолчанию лида — вариант «а»): пассажирка,
  которую заблокировал водитель, до конца поездки видит машину по СВОЕЙ ссылке близким и в ответе
  на свой SOS. Пока она в машине, место машины — это её собственное место.

Блокировка, поставленная ДО посадки, «в пути» не оживает (независимая проверка PR170, P1):
пассажирка заблокировала водителя накануне, потом он отметил «Сел» — канал закрыт, точка не пишется.
Свою блокировку водитель знает, поэтому «Сел» по заблокированному им пассажиру сервер не принимает
(409, P2-2) — иначе её ответная блокировка открыла бы ей его машину. Его блокировка до посадки на уже
едущей брони (старые брони, «Сел» до выкладки) тоже держит канал закрытым.
Старую посадку без времени (`onboard_at` пуст, старый пассажирский «Я сел») сравниваем с самым
ранним моментом, когда тот «Я сел» вообще был возможен (за 30 минут до выезда). Конец поездки
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
from app.models import Block, Booking, BookingStatus, Ride, UserRole
from app.routers import location
from app.services import manager
from app.timeutil import utcnow

from boarding_helpers import board

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

    `села=True` — водитель отметил «Сел» (бронь «в пути», PR174), и окно его «Отменить» уже прошло:
    посадка была раньше любой блокировки, которую тест поставит дальше. `села=False` — водитель
    только «Выехал»: поездка идёт, точка уже нужна, но пассажирка ещё не в машине.
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
        board(client, водитель, bid)
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


# ── P1 (независимая проверка PR170): блокировка ДО посадки «в пути» не оживает ──

def _блок(client, кто: dict, кого: dict) -> None:
    assert client.post("/blocks", headers=кто["auth"],
                       json={"blocked_user_id": кого["id"]}).status_code == 200


def _блок_поставлен(кто: dict, кого: dict, когда) -> None:
    """Подвинуть время блокировки: через API «накануне» не поставить."""
    from sqlmodel import select
    with Session(engine) as s:
        блок = s.exec(select(Block).where(Block.user_id == кто["id"], Block.blocked_user_id == кого["id"])).one()
        блок.created_at = когда
        s.add(блок)
        s.commit()


def _старая_посадка(bid: int) -> None:
    """«В пути» без времени — так ставил старый пассажирский «Я сел» до PR174."""
    with Session(engine) as s:
        b = s.get(Booking, bid)
        b.status = BookingStatus.onboard
        b.onboard_at = None
        s.add(b)
        s.commit()


def _выезд(рейс: dict):
    with Session(engine) as s:
        return s.get(Ride, рейс["id"]).depart_at


def _водитель_закрыт_и_не_пишет(client, водитель: dict, bid: int, токен: str, что: str) -> None:
    """Сначала решение канала (быстрый и внятный отказ), потом настоящий сокет: водитель открыл
    поездку и шлёт точку — канал закрывается «Access ended», в кэш для ссылки ничего не пишется."""
    решение = location._Пора(bid, я=водитель["id"])
    assert решение.наступила() is False and решение.только_запись is False, (
        f"{что}: блокировка до посадки ожила «в пути» — точка водителя снова пишется для ссылки, "
        "и заблокированная следит за ним по своей ссылке близким")
    assert решение.причина == "Access ended"
    with _окно(client, location_path(bid)) as рот:
        рот.send_text(_вход(водитель))
        рот.send_text(_кадр(1))
        _пришло, закрытие = _всё_до_закрытия(рот)
    assert закрытие.code == 1008 and закрытие.reason == "Access ended", закрытие.reason
    assert livepos_get("booking", bid) is None, f"{что}: точка водителя записалась для ссылки"
    assert _машина_у_мамы(client, токен)["car"] is None, f"{что}: по ссылке видна машина"


def test_она_заблокировала_накануне_сел_проходит_а_канал_закрыт(client, user_factory, redis_живой, смс):
    """Она заблокировала водителя накануне, бронь не отменили. Его «Сел» сервер принимает — иначе
    отказ выдал бы водителю её блокировку. Канал при этом закрыт: «только запись» — для блокировки
    ПОСЛЕ посадки, а эта была до."""
    водитель, пассажирка, _рейс, bid, токен = _едем(client, user_factory, "накануне-она", села=False)
    _блок(client, пассажирка, водитель)
    r = client.post(f"/bookings/{bid}/driver-status", headers=водитель["auth"], json={"status": "boarded"})
    assert r.status_code == 200, f"«Сел» отказан из-за ЕЁ блокировки — так водитель узнал бы о ней: {r.text}"
    with Session(engine) as s:
        assert s.get(Booking, bid).status == BookingStatus.onboard, "контроль: «Сел» прошёл"
    _водитель_закрыт_и_не_пишет(client, водитель, bid, токен, "она блокировала до «Сел»")


# ── P2-2 (повторная проверка PR170): водитель не отметит «Сел» тому, кого сам заблокировал ──

def test_водитель_заблокировал_сел_не_отметить_и_её_ответная_блокировка_машину_не_открывает(
        client, user_factory, redis_живой, смс):
    """Проба M ревьюера. Раньше: он заблокировал, отметил «Сел» по её карточке (промах или нарочно),
    она блокирует в ответ — самая свежая блокировка уже после посадки, и она видит его машину по своей
    ссылке, не садясь. Теперь его «Сел» — 409 с понятным текстом: свою блокировку он знает."""
    водитель, пассажирка, _рейс, bid, токен = _едем(client, user_factory, "сел-по-своему-блоку", села=False)
    _блок(client, водитель, пассажирка)
    r = client.post(f"/bookings/{bid}/driver-status", headers=водитель["auth"], json={"status": "boarded"})
    assert r.status_code == 409, r.text
    detail = r.json()["detail"]
    assert detail["code"] == "driver_blocked_passenger"
    assert detail["ru"] == ("Этот попутчик у тебя в блоке — посадку не отметить. "
                            "Если всё же едет с тобой, сначала разблокируй.")
    assert detail["ba"].strip() and detail["ba"] != detail["ru"]
    with Session(engine) as s:
        b = s.get(Booking, bid)
        assert b.status == BookingStatus.confirmed and b.onboard_at is None, "бронь сдвинулась после отказа"

    _блок(client, пассажирка, водитель)                 # её ответная блокировка
    _водитель_закрыт_и_не_пишет(client, водитель, bid, токен, "проба M: его блок, отказанный «Сел», её блок")


@pytest.mark.parametrize("посадка", ["старая_без_времени", "сел_до_выкладки"])
def test_его_блокировка_до_посадки_на_уже_едущей_брони_её_ответная_не_открывает_машину(
        client, user_factory, redis_живой, смс, посадка):
    """Брони, где «в пути» и его блокировка до посадки уже есть: старый «Я сел» до PR174 или «Сел»,
    отмеченный до выкладки этой правки. Её ответная блокировка — самая свежая, но канал закрыт."""
    водитель, пассажирка, рейс, bid, токен = _едем(client, user_factory, f"М-{посадка}", села=False)
    if посадка == "старая_без_времени":
        _старая_посадка(bid)
        _блок(client, водитель, пассажирка)
        _блок_поставлен(водитель, пассажирка, _выезд(рейс) - timedelta(hours=12))
    else:
        board(client, водитель, bid, settled=False)
        _блок(client, водитель, пассажирка)
        with Session(engine) as s:
            сел = s.get(Booking, bid).onboard_at
        _блок_поставлен(водитель, пассажирка, сел - timedelta(minutes=10))
    _блок(client, пассажирка, водитель)
    _водитель_закрыт_и_не_пишет(client, водитель, bid, токен, f"его блок до посадки ({посадка}) + её ответный")


def test_старая_посадка_без_времени_и_блокировка_накануне_канал_закрыт(client, user_factory, redis_живой, смс):
    """Посадка до PR174 (время не записано). Старый «Я сел» был возможен не раньше чем за 30 минут
    до выезда, значит блокировка за 12 часов до выезда точно была раньше посадки — не оживает."""
    водитель, пассажирка, рейс, bid, токен = _едем(client, user_factory, "старая-накануне", села=False)
    _блок(client, водитель, пассажирка)
    _блок_поставлен(водитель, пассажирка, _выезд(рейс) - timedelta(hours=12))
    _старая_посадка(bid)
    _водитель_закрыт_и_не_пишет(client, водитель, bid, токен, "старая посадка, блокировка накануне")


def test_старая_посадка_без_времени_и_блокировка_в_дороге_точка_пишется(client, user_factory, redis_живой, смс):
    """Посадка до PR174, блокировка уже в окне поездки: кто был раньше — не узнать. Решаем в пользу
    близких: считаем, что села до блокировки (такие брони есть только на стыке выкладки)."""
    водитель, пассажирка, _рейс, bid, токен = _едем(client, user_factory, "старая-дорога", села=False)
    _старая_посадка(bid)
    _блок(client, пассажирка, водитель)
    решение = location._Пора(bid, я=водитель["id"])
    assert решение.наступила() is True and решение.только_запись is True
    with _окно(client, location_path(bid)) as рот:
        рот.send_text(_вход(водитель))
        рот.send_text(_кадр(1))
        assert _записана(bid, 1), "старая посадка, блокировка в дороге: мама потеряла машину"
    assert _машина_у_мамы(client, токен)["car"]["lat"] == pytest.approx(ШИРОТА[1])


def test_решает_самая_свежая_блокировка_пары(client, user_factory, redis_живой, смс):
    """Она заблокировала его накануне, но всё же поехала; уже в машине он заблокировал её. Решает
    самая свежая блокировка пары (решение лида): его — после посадки, значит «только запись», и её
    близкие машину не теряют."""
    водитель, пассажирка, _рейс, bid, токен = _едем(client, user_factory, "свежая", села=False)
    _блок(client, пассажирка, водитель)
    board(client, водитель, bid, settled=False)
    _блок(client, водитель, пассажирка)
    решение = location._Пора(bid, я=водитель["id"])
    assert решение.наступила() is True and решение.только_запись is True
    with _окно(client, location_path(bid)) as рот:
        рот.send_text(_вход(водитель))
        рот.send_text(_кадр(1))
        assert _записана(bid, 1)
    assert _машина_у_мамы(client, токен)["car"] is not None


@pytest.mark.parametrize("сдвиг_мкс,пишем", [(0, False), (1, True)])
def test_совпадение_времени_блокировки_и_посадки_закрывает_канал(client, user_factory, redis_живой, смс,
                                                                  сдвиг_мкс, пишем):
    """Строго «блокировка ПОСЛЕ посадки»: в одну и ту же микросекунду — канал закрыт (безопасная
    сторона), на микросекунду позже — «только запись». Держит «<», а не «<=»."""
    водитель, пассажирка, _рейс, bid, _токен = _едем(client, user_factory, f"равно-{сдвиг_мкс}", села=False)
    board(client, водитель, bid, settled=False)
    _блок(client, пассажирка, водитель)
    with Session(engine) as s:
        сел = s.get(Booking, bid).onboard_at
    _блок_поставлен(пассажирка, водитель, сел + timedelta(microseconds=сдвиг_мкс))
    решение = location._Пора(bid, я=водитель["id"])
    assert решение.наступила() is пишем and решение.только_запись is пишем, (
        f"блокировка через {сдвиг_мкс} мкс после «Сел»: ждали {'запись' if пишем else 'закрыто'}")


# ── Толчки потерялись: сторож тоже не оживляет блокировку до посадки (проба G3b ревьюера) ──

def test_без_толчков_сторож_не_оживляет_блокировку_до_посадки(client, user_factory, redis_живой, смс, monkeypatch):
    """Толчки не дошли (чужой воркер без Redis), канал водителя уже писал точку. Она блокирует его
    до посадки, потом он отмечает «Сел», и о блокировке канал узнаёт только от сторожа. Сторож
    обязан применить то же правило времени, что и толчок: блокировка до посадки — канал закрыт.

    Чтобы сторож не проснулся между блокировкой и «Сел» (тогда закрыл бы канал по «ещё не в пути»,
    и проверка прошла бы и со старым правилом), его вердикт придержан «воротами» до «Сел»."""
    monkeypatch.setattr(ws_guard, "RECHECK_SEC", 0.05)
    водитель, пассажирка, _рейс, bid, _токен = _едем(client, user_factory, "сторож-накануне", села=False)
    настоящий = location._канал_мой
    ворота = {"открыты": False}
    monkeypatch.setattr(location, "_канал_мой",
                        lambda *a, **kw: настоящий(*a, **kw) if ворота["открыты"] else True)
    with _окно(client, location_path(bid)) as рот:
        рот.send_text(_вход(водитель))
        рот.send_text(_кадр(1))
        assert _записана(bid, 1), "контроль: поездка идёт, точка пишется"
        monkeypatch.setattr(trip_wake, "poke", lambda bid: None)     # толчки потерялись
        _блок(client, пассажирка, водитель)
        board(client, водитель, bid, settled=False)                   # «Сел» — после её блокировки
        ворота["открыты"] = True                                       # сторож видит уже «в пути»
        до = time.monotonic() + 10
        while _водитель_слушает(bid) and time.monotonic() < до:
            time.sleep(0.02)
        assert not _водитель_слушает(bid), "сторож так и не проснулся"
        for i in (2, 3):
            try:
                рот.send_text(_кадр(i))
            except Exception:  # noqa: BLE001 — канал уже закрыт сервером
                pass
        # Кадр, которого цикл уже ждал, сервер дочитывает и после закрытия (кадр 2) — дальше ничего.
        assert not _записана(bid, 3, срок=2.0), (
            "сторож оставил канал «только запись» для блокировки до посадки — точка снова пишется "
            "для ссылки, и заблокировавшая видит машину, не садясь")


# ── Q16, вариант «а» (по умолчанию лида, пока Александр не ответил) ──

def test_Q16_заблокированная_водителем_видит_машину_по_своей_ссылке_и_в_sos_до_конца_поездки(
        client, user_factory, redis_живой, смс):
    """Водитель заблокировал пассажирку «в пути». По живому каналу она машину больше не видит, но
    её ссылка близким (она может открыть её сама) и ответ на её SOS показывают место машины до
    конца поездки. Это принято сознательно: пока она в машине, место машины — её собственное место.
    Тест закрепляет это, чтобы смена поведения была решением, а не случайностью."""
    водитель, пассажирка, _рейс, bid, токен = _едем(client, user_factory, "q16")
    with _окно(client, location_path(bid)) as рот:
        рот.send_text(_вход(водитель))
        рот.send_text(_кадр(1))
        assert _записана(bid, 1)
        _блок(client, водитель, пассажирка)
        рот.send_text(_кадр(2))
        assert _записана(bid, 2)

        assert _машина_у_мамы(client, токен)["car"]["lat"] == pytest.approx(ШИРОТА[2]), (
            "по её же ссылке близким машины не видно — это уже не вариант «а» вопроса Q16")
        r = client.post("/sos", headers=пассажирка["auth"],
                        json={"category": "other", "booking_id": bid, "note": "тревожно"})
        assert r.status_code == 200, r.text
        assert f"pt={ДОЛГОТА},{ШИРОТА[2]}" in r.json()["note"], (
            f"в ответе на её SOS нет места машины — это уже не вариант «а» вопроса Q16: {r.json()['note']}")

        assert client.post(f"/bookings/{bid}/driver-status", headers=водитель["auth"],
                           json={"status": "done"}).status_code == 200
        рот.send_text(_кадр(3))
        _пришло, закрытие = _всё_до_закрытия(рот)
    assert закрытие.reason == "Trip ended"
    мама = _машина_у_мамы(client, токен)
    assert мама["status"] == "finished" and мама["car"] is None, f"после конца поездки машина видна: {мама}"


def location_path(bid: int) -> str:
    return f"/ws/trip/{bid}/location"
