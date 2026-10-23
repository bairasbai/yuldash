"""«Заблокировать» закрывало переписку и не трогало карту (волна 159).

Бронь подтверждена, выезд ещё не начался — женщина ждёт дома. Водитель ведёт себя навязчиво,
она жмёт «Заблокировать». Чат ему закрывается мгновенно, 403. А её точка продолжает уходить
к нему на карту: сторож живого канала спрашивал только «поездка ещё едет?», про блокировку
в этом файле не было ни слова — при том что в чате она проверяется на каждой двери.

«Заблокировать» — единственная кнопка, которой человек закрывается от человека. Она обещала
больше, чем делала, и обещала это в самой уязвимой ситуации: женщина одна, дома, адрес открыт.

Та же болезнь у смены исполнителя. Курьер взял посылку, открыл окно, потом снялся («заболел»).
Посылку берёт другой курьер, отправитель пишет уже ему — «ключ под ковриком, свекровь глухая,
стучите громко» — и это прилетает в окно ПЕРВОГО курьера, оставшееся открытым. Его точка при
этом уходит отправителю как «где сейчас моя посылка». REST-историю снятому курьеру закрыли
волной раньше, живой канал не закрыл никто.

Теперь оба сторожа спрашивают одно и то же: я всё ещё участник этой поездки, и меня не закрыли.
"""
from __future__ import annotations

import json
import time
from contextlib import contextmanager

import anyio
import pytest
from sqlmodel import Session

from app import ws_guard
from app.db import engine
from app.models import ParcelDelivery, UserRole
from app.routers.chat import _parcel_chat_key
from app.routers.location import PARCEL_LOC_BASE
from app.services import manager

from test_api import _ride, soon

pytestmark = pytest.mark.timeout(60)


def _подтверждённая_бронь(client, user_factory, метка: str):
    водитель = user_factory(метка + "Водитель", role=UserRole.driver)
    пассажирка = user_factory(метка + "Пассажирка")
    ride_id = _ride(client, водитель, seats=1, depart_at=soon())   # полный и скоро — ленту не теснит
    bid = client.post("/bookings", headers=пассажирка["auth"],
                      json={"ride_id": ride_id, "seats": 1}).json()["id"]
    client.post(f"/bookings/{bid}/confirm", headers=водитель["auth"])
    # Поездка идёт: без «выехал» сервер не пересылает точку по брони за годы до выезда
    # (`trip_is_live`, 16.10.2026) — и проверка «блокировка закрывает карту» прошла бы впустую.
    # «Выехал» принимается за час до выезда (P2-Г) — отсюда выезд через 20 минут.
    r = client.post(f"/bookings/{bid}/driver-status", headers=водитель["auth"], json={"status": "departed"})
    assert r.status_code == 200, r.text
    return водитель, пассажирка, bid


@contextmanager
def _окно(client, адрес: str, токен: str):
    """Открытое окно канала. Закрытый сервером сокет — нормальный исход, а не ошибка теста."""
    вход = client.websocket_connect(адрес)
    сокет = вход.__enter__()
    сокет.send_text(json.dumps({"type": "auth", "token": токен}))
    try:
        yield сокет
    finally:
        try:
            вход.__exit__(None, None, None)
        except Exception:
            pass          # сервер уже закрыл канал — этого мы и добивались


def _дошло(окно, кадр: dict) -> bool:
    """Отправить кадр и сказать, дошёл ли он до второго окна. Закрытый канал — не дошёл."""
    try:
        окно.send_text(json.dumps(кадр))
        return True
    except Exception:
        return False


# Ниже — ожидания по условию, а не по часам (приёмка PR157: тест про снятого курьера падал).
# Причина была не в «поспать 0,2 с при стороже 0,3 с», а в гонке на пороге: `_окно` возвращается,
# как только ключ входа ОТПРАВЛЕН, а проверяет его сервер уже в своём потоке. Тест сразу снимал
# курьера в базе — и если эта правка успевала раньше проверки, сервер честно не пускал снятого
# курьера («Forbidden»), сторож не запускался вовсе. Тестовый клиент закрытия со стороны сервера
# не замечает: пинги «уходили» в мёртвое окно, а чтение у отправителя ждало кадра, которого
# не будет, — до таймаута в 60 с. Даже без нагрузки так кончались 7 прогонов из 40 (17.10.2026).

def _впущен(client, ключ: int) -> None:
    """Дождаться, пока сервер проверит ключ входа и впустит окно в раздачу (`manager.register`)."""
    async def ждать() -> None:
        with anyio.fail_after(10):
            while not manager.active_connections.get(ключ):
                await anyio.sleep(0.01)

    try:
        client.portal.call(ждать)
    except TimeoutError:
        pytest.fail("сервер за 10 с так и не впустил окно в канал — дальше проверять нечего")


def _приём(окно, секунд: float = 10.0):
    """Следующее событие окна или None, если за `секунд` ничего не пришло.

    Обычное `receive()` ждёт вечно: пропавший кадр превращал тест в зависание до таймаута, а не
    в понятное падение. Очередь клиента читаем напрямую, как `test_rest_chat_local_delivery.py`.
    """
    async def ждать():
        with anyio.move_on_after(секунд):
            return await окно._send_rx.receive()
        return None

    return окно.portal.call(ждать)


_ЗАКРЫЛ_СТОРОЖ = {"type": "websocket.close", "code": 1008, "reason": "Access ended"}


def test_блокировка_закрывает_и_карту(client, user_factory, monkeypatch):
    """Главное: закрылась от человека — значит и от его карты тоже."""
    monkeypatch.setattr(ws_guard, "RECHECK_SEC", 0.3)
    водитель, пассажирка, bid = _подтверждённая_бронь(client, user_factory, "Блок")
    дошло = None

    with _окно(client, f"/ws/trip/{bid}/location", водитель["token"]) as окно_водителя:
        with _окно(client, f"/ws/trip/{bid}/location", пассажирка["token"]) as окно_пассажирки:
            client.post("/blocks", headers=пассажирка["auth"],
                        json={"blocked_user_id": водитель["id"]})
            письмо = client.post(f"/bookings/{bid}/messages", headers=водитель["auth"],
                                 json={"text": "ты где?"})
            assert письмо.status_code == 403, "чат должен был закрыться — это работало и раньше"
            time.sleep(1.2)                     # сторож канала успевает проснуться

            if _дошло(окно_пассажирки, {"type": "loc", "lat": 52.5921, "lng": 58.4437}):
                try:
                    дошло = json.loads(окно_водителя.receive_text())
                except Exception:
                    дошло = None

    assert дошло is None, (
        f"женщина заблокировала водителя, чат ему закрылся, а её точка ушла к нему на карту: "
        f"{дошло}. Она одна, дома, адрес открыт — и кнопка, которой она закрылась, не сработала"
    )


def test_обычная_поездка_карту_не_теряет(client, user_factory, monkeypatch):
    """Обратная сторона: пока никто никого не блокировал, попутчики видят друг друга."""
    monkeypatch.setattr(ws_guard, "RECHECK_SEC", 0.3)
    водитель, пассажирка, bid = _подтверждённая_бронь(client, user_factory, "Мирная")
    кадр = None

    with _окно(client, f"/ws/trip/{bid}/location", водитель["token"]) as окно_водителя:
        with _окно(client, f"/ws/trip/{bid}/location", пассажирка["token"]) as окно_пассажирки:
            time.sleep(1.2)                     # сторож просыпался — и не должен был мешать
            if _дошло(окно_пассажирки, {"type": "loc", "lat": 52.5921, "lng": 58.4437}):
                кадр = json.loads(окно_водителя.receive_text())

    assert кадр is not None and кадр.get("lat") == 52.5921, (
        f"в обычной поездке водитель перестал видеть, где пассажирка: {кадр}. Перестраховались "
        "и сломали то, ради чего канал существует"
    )


def _курьер(client, user_factory, метка: str):
    from app.models import CourierApplication, CourierProfile
    from sqlmodel import select
    from app.timeutil import utcnow
    человек = user_factory(метка, role=UserRole.driver)
    with Session(engine) as s:
        заявка = s.exec(select(CourierApplication).where(
            CourierApplication.user_id == человек["id"])).first()
        if заявка is None:
            заявка = CourierApplication(user_id=человек["id"])
        заявка.status = "approved"
        заявка.reviewed_at = utcnow()
        s.add(заявка)
        if s.exec(select(CourierProfile).where(
                CourierProfile.user_id == человек["id"])).first() is None:
            s.add(CourierProfile(user_id=человек["id"]))
        s.commit()
    return человек


def test_снятый_курьер_уходит_с_карты_доставки(client, user_factory, monkeypatch):
    """Точка постороннего не должна показываться отправителю как «где моя посылка»."""
    monkeypatch.setattr(ws_guard, "RECHECK_SEC", 0.3)
    первый = _курьер(client, user_factory, "СнятыйКурьер")
    второй = _курьер(client, user_factory, "НовыйКурьер")
    отправитель = user_factory("ОтправительПосылки")
    with Session(engine) as s:
        p = ParcelDelivery(sender_id=отправитель["id"], courier_id=первый["id"],
                           status="in_transit", delivery_type="courier",
                           from_city="Акъяр", to_city="Сибай")
        s.add(p)
        s.commit()
        s.refresh(p)
        pid = p.id
    дошло = None
    ящик_курьера = -(PARCEL_LOC_BASE + pid * 2)          # куда сервер шлёт точки курьеру
    ящик_отправителя = -(PARCEL_LOC_BASE + pid * 2 + 1)  # и отправителю (см. `parcel_location`)

    with _окно(client, f"/ws/parcel/{pid}/location", первый["token"]) as окно_снятого:
        _впущен(client, ящик_курьера)           # курьер в канале — сторож запущен, теперь снимаем
        with Session(engine) as s:              # поддержка сняла курьера, посылку взял другой
            p = s.get(ParcelDelivery, pid)
            p.courier_id = второй["id"]
            s.add(p)
            s.commit()
        закрытие = _приём(окно_снятого)         # сторож просыпается и закрывает окно
        assert закрытие == _ЗАКРЫЛ_СТОРОЖ, f"сторож не закрыл окно снятого курьера: {закрытие}"

        with _окно(client, f"/ws/parcel/{pid}/location", отправитель["token"]) as окно_отправителя:
            _впущен(client, ящик_отправителя)
            # Когда сторож закрыл окно, обработчик канала уже ждал следующий кадр — и примет ровно
            # один. Это отдельная дыра в один кадр (docs/tasks.md); раньше её тратили пинги цикла
            # ожидания, теперь — этот пустой кадр. Проверяем то же: дальше точки не идут.
            _дошло(окно_снятого, {"type": "ping"})
            if _дошло(окно_снятого, {"type": "loc", "lat": 52.1111, "lng": 58.2222}):
                кадр = _приём(окно_отправителя, 2.0)
                дошло = json.loads(кадр["text"]) if кадр and кадр.get("text") else None

    assert дошло is None, (
        f"снятый курьер остался на карте чужой доставки, и отправитель следит за машиной "
        f"постороннего человека как за своей посылкой: {дошло}"
    )


def test_снятый_курьер_не_слышит_переписку(client, user_factory, monkeypatch):
    """Отправитель диктует новому курьеру, где ключ, — старый слышать этого не должен."""
    monkeypatch.setattr(ws_guard, "RECHECK_SEC", 0.3)
    первый = _курьер(client, user_factory, "СнятыйСлушатель")
    второй = _курьер(client, user_factory, "НовыйСлушатель")
    отправитель = user_factory("ОтправительЧата")
    with Session(engine) as s:
        p = ParcelDelivery(sender_id=отправитель["id"], courier_id=первый["id"],
                           status="in_transit", delivery_type="courier",
                           from_city="Акъяр", to_city="Сибай")
        s.add(p)
        s.commit()
        s.refresh(p)
        pid = p.id
    услышал = None
    ящик = _parcel_chat_key(pid)                # у чата посылки один ящик на обе стороны
    тайна = "Ключ под ковриком, свекровь глухая — стучите громко, дом 5 кв 3"

    with _окно(client, f"/ws/parcel/{pid}/chat", первый["token"]) as окно_снятого:
        # Та же гонка на пороге, что у карты: без этого ожидания в части прогонов сервер не пускал
        # курьера вовсе, и тест проходил вхолостую — сторожа он при этом не проверял.
        _впущен(client, ящик)
        with Session(engine) as s:              # курьера сняли, посылку взял другой
            p = s.get(ParcelDelivery, pid)
            p.courier_id = второй["id"]
            s.add(p)
            s.commit()
        закрытие = _приём(окно_снятого)         # сторож просыпается и закрывает окно
        assert закрытие == _ЗАКРЫЛ_СТОРОЖ, f"сторож не закрыл окно снятого курьера: {закрытие}"

        with _окно(client, f"/ws/parcel/{pid}/chat", отправитель["token"]) as окно_отправителя:
            _впущен(client, ящик)               # сторож вычеркнул курьера: в ящике только отправитель
            окно_отправителя.send_text(json.dumps({"type": "message", "text": тайна}))
            # Контроль: сервер принял сообщение и разослал его (отправителю оно возвращается).
            # Без этой проверки «не услышал» проходило бы вхолостую — например, если сервер
            # отклонил само сообщение (приёмка PR165 так и проверила: тест оставался зелёным).
            эхо = _приём(окно_отправителя)
            эхо_текст = json.loads(эхо["text"]).get("text") if эхо and эхо.get("text") else None
            assert эхо_текст == тайна, f"сообщение отправителя не разослано — проверять нечего: {эхо}"
            кадр = _приём(окно_снятого, 2.0)
            услышал = json.loads(кадр["text"]).get("text", "") if кадр and кадр.get("text") else None

    assert not услышал, (
        f"снятый курьер слышит переписку отправителя с НОВЫМ курьером: {услышал!r}. "
        "Историю ему закрыли, а живое окно продолжало принимать всё, что пишут дальше"
    )
