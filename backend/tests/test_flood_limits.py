"""Потолки на поток однотипных действий одного человека.

Что было до этих потолков (проверено запросами к живому серверу 2026-08-06): один
пользователь подряд опубликовал 80 поездок, создал 80 заявок, отправил 300 сообщений
в один чат и завёл 60 посылок. Ни одного отказа.

Почему это дыра, а не мелочь. В приложении «между своими» на небольшой район:
  • лента поездок и заявок забивается одним человеком — реальных объявлений не видно;
  • каждая заявка будит пушем водителей, которые караулят это направление;
  • каждое сообщение — пуш собеседнику. Триста сообщений ночью это травля кнопкой
    «отправить», и модерация текста здесь бессильна: каждое по отдельности безобидно.

Лимитер запросов на IP от этого не спасал: у него потолок 300 запросов в минуту — он
защищает сервер от перегрузки, а не человека от очереди пушей.

Проверяется и обратная сторона: потолки не должны мешать нормальной жизни. Обычная
публикация, обычная переписка, отмена и повторная публикация — всё работает.
"""
from __future__ import annotations

from datetime import timedelta

import pytest

from app.config import settings
from app.models import UserRole
from app.timeutil import utcnow

from test_api import _ride


def _local(delta: timedelta) -> str:
    """Время как его шлёт приложение: местное (Уфа = UTC+5), без пояса."""
    return (utcnow() + timedelta(hours=settings.local_tz_offset_hours) + delta).replace(
        microsecond=0).isoformat()


def _publish(client, driver, minutes: int):
    return client.post("/rides", headers=driver["auth"], json={
        "from_city": "Сибай", "to_city": "Уфа", "seats": 3, "price": 500,
        "depart_at": _local(timedelta(days=1, minutes=minutes)),
    })


def _chat(client, user_factory, tag):
    driver = user_factory(f"{tag}Drv", role=UserRole.driver)
    ride_id = _ride(client, driver, seats=2)
    pax = user_factory(f"{tag}Pax")
    bid = client.post("/bookings", headers=pax["auth"],
                      json={"ride_id": ride_id, "seats": 1}).json()["id"]
    assert client.post(f"/bookings/{bid}/confirm", headers=driver["auth"]).status_code == 200
    return driver, pax, bid


# ---------- Поездки ----------

def test_нельзя_забить_ленту_поездками(client, user_factory):
    cap = settings.flood_active_rides_max
    driver = user_factory("FloodRidesDrv", role=UserRole.driver)
    for i in range(cap):
        r = _publish(client, driver, i)
        assert r.status_code == 200, (
            f"{i + 1}-я публикация из разрешённых {cap} не прошла: {r.status_code} {r.text[:150]}"
        )
    over = _publish(client, driver, cap + 1)
    assert over.status_code == 429, (
        f"после {cap} активных поездок публикация продолжается — один человек забьёт "
        f"ленту района: {over.status_code} {over.text[:150]}"
    )


def test_отмена_поездки_освобождает_место(client, user_factory):
    """Потолок считает ОДНОВРЕМЕННО активные, а не «сколько создал за всю жизнь». Иначе он
    наказывал бы того, кто много лет честно возит людей."""
    cap = settings.flood_active_rides_max
    driver = user_factory("FloodRidesFree", role=UserRole.driver)
    first_id = None
    for i in range(cap):
        r = _publish(client, driver, i)
        assert r.status_code == 200, f"публикация {i + 1} не прошла: {r.text[:150]}"
        if first_id is None:
            first_id = r.json()["id"]
    assert _publish(client, driver, cap + 1).status_code == 429, "потолок не сработал"

    cancelled = client.post(f"/rides/{first_id}/cancel", headers=driver["auth"])
    assert cancelled.status_code == 200, f"отмена не прошла: {cancelled.status_code} {cancelled.text[:150]}"
    again = _publish(client, driver, cap + 2)
    assert again.status_code == 200, (
        f"освободил место, а публиковать всё равно нельзя: {again.status_code} {again.text[:150]}"
    )


# ---------- Заявки ----------

def test_нельзя_забить_ленту_заявками(client, user_factory):
    cap = settings.flood_active_requests_max
    pax = user_factory("FloodReqPax")
    # Заявки РАЗНЫЕ (комментарий свой у каждой). С 2026-08-06 повтор байт в байт схлопывается
    # как двойной тап — и потолок таким спамом просто не достать. Настоящий флудер и шлёт разное:
    # проверяем именно его, а не то, чего в жизни не бывает.
    for i in range(cap):
        r = client.post("/requests", headers=pax["auth"], json={
            "from_city": "Баймак", "to_city": "Сибай", "seats": 1, "comment": f"заявка {i}",
        })
        assert r.status_code == 200, f"заявка {i + 1} из разрешённых {cap} не прошла: {r.text[:150]}"
    over = client.post("/requests", headers=pax["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай", "seats": 1, "comment": "ещё одна",
    })
    assert over.status_code == 429, (
        f"заявки создаются без потолка, а каждая будит пушем водителей направления: "
        f"{over.status_code} {over.text[:150]}"
    )


# ---------- Чат ----------

def test_нельзя_завалить_человека_сообщениями(client, user_factory):
    """Главный тест файла: каждое сообщение = пуш на телефон собеседника."""
    cap = settings.flood_chat_per_min
    driver, pax, bid = _chat(client, user_factory, "FloodChat")
    for i in range(cap):
        r = client.post(f"/bookings/{bid}/messages", headers=pax["auth"], json={"text": f"привет {i}"})
        assert r.status_code == 200, f"сообщение {i + 1} из разрешённых {cap} не прошло: {r.text[:150]}"
    over = client.post(f"/bookings/{bid}/messages", headers=pax["auth"], json={"text": "и ещё"})
    assert over.status_code == 429, (
        f"сообщения идут без потолка — это очередь пушей на чужой телефон: "
        f"{over.status_code} {over.text[:150]}"
    )


def test_потолок_сообщений_у_каждого_свой(client, user_factory):
    """Один заспамил — второй не должен онеметь. Считаем по отправителю, не по чату."""
    cap = settings.flood_chat_per_min
    driver, pax, bid = _chat(client, user_factory, "FloodChatPair")
    for i in range(cap):
        assert client.post(f"/bookings/{bid}/messages", headers=pax["auth"],
                           json={"text": f"спам {i}"}).status_code == 200
    assert client.post(f"/bookings/{bid}/messages", headers=pax["auth"],
                       json={"text": "ещё"}).status_code == 429

    other = client.post(f"/bookings/{bid}/messages", headers=driver["auth"], json={"text": "я отвечаю"})
    assert other.status_code == 200, (
        f"второй участник не может ответить из-за чужого флуда: {other.status_code} {other.text[:150]}"
    )


def test_обычная_переписка_потолком_не_задета(client, user_factory):
    """Контроль здравого смысла: живой диалог — это единицы сообщений, а не тридцать."""
    driver, pax, bid = _chat(client, user_factory, "NormalChat")
    for text in ("Привет!", "Во сколько выезжаем?", "Буду у школы", "Спасибо"):
        r = client.post(f"/bookings/{bid}/messages", headers=pax["auth"], json={"text": text})
        assert r.status_code == 200, f"обычное сообщение не прошло: {r.status_code} {r.text[:150]}"


# ---------- Посылки ----------

def test_нельзя_забить_ленту_посылками(client, user_factory, monkeypatch):
    monkeypatch.setattr(settings, "courier_enabled", True, raising=False)
    cap = settings.flood_active_parcels_max
    sender = user_factory("FloodParcelSender")

    def create(i):
        return client.post("/parcels", headers=sender["auth"], json={
            "from_city": "Баймак", "to_city": "Сибай", "size": "small",
            "description": f"посылка {i}", "receiver_name": "Гөлнара",
            "receiver_phone": "+79990009903", "rules_accepted": True,
        })

    for i in range(cap):
        r = create(i)
        assert r.status_code == 200, f"посылка {i + 1} из разрешённых {cap} не прошла: {r.text[:150]}"
    over = create(cap + 1)
    assert over.status_code == 429, (
        f"посылки создаются без потолка — лента курьеров тонет: {over.status_code} {over.text[:150]}"
    )


# ---------- Тексты отказа ----------

@pytest.mark.parametrize("kind", ["rides", "requests", "chat"])
def test_отказ_объясняет_что_делать_и_на_двух_языках(client, user_factory, kind):
    """429 — не «ты плохой», а «остынь и продолжи». И обязательно на двух языках:
    башкироязычный человек не должен упереться в русскую ошибку."""
    if kind == "rides":
        driver = user_factory("FloodMsgRides", role=UserRole.driver)
        for i in range(settings.flood_active_rides_max):
            assert _publish(client, driver, i).status_code == 200
        r = _publish(client, driver, 999)
    elif kind == "requests":
        pax = user_factory("FloodMsgReq")
        # Разные заявки: одинаковые схлопываются как двойной тап (2026-08-06) и до потолка
        # не доводят. Флудер шлёт разное — его и проверяем.
        def _body(i):
            return {"from_city": "Баймак", "to_city": "Сибай", "seats": 1, "comment": f"№{i}"}
        for i in range(settings.flood_active_requests_max):
            assert client.post("/requests", headers=pax["auth"], json=_body(i)).status_code == 200
        r = client.post("/requests", headers=pax["auth"], json=_body(999))
    else:
        driver, pax, bid = _chat(client, user_factory, "FloodMsgChat")
        for i in range(settings.flood_chat_per_min):
            assert client.post(f"/bookings/{bid}/messages", headers=pax["auth"],
                               json={"text": str(i)}).status_code == 200
        r = client.post(f"/bookings/{bid}/messages", headers=pax["auth"], json={"text": "ещё"})

    assert r.status_code == 429
    detail = r.json().get("detail")
    assert isinstance(detail, dict), f"текст отказа не двуязычный: {detail}"
    assert detail.get("ru") and detail.get("ba"), f"пустой язык в отказе: {detail}"


# ---------- Живое соединение (чат по сокету) ----------

def test_сокет_не_обходит_потолок_и_не_молчит(client, user_factory):
    """Сообщения уходят двумя путями: обычным запросом и по живому соединению. Потолок только
    в первом обходился бы в один тап.

    И вторая половина, не менее важная: при отказе сервер обязан СКАЗАТЬ об этом. Приложение
    рисует своё сообщение на экране сразу, до ответа, и заменяет его настоящим, когда оно
    вернётся эхом. Если сервер молча выбросит сообщение, эхо не придёт никогда — и человек
    будет уверен, что отправил, хотя не отправил."""
    import json

    cap = settings.flood_chat_per_min
    driver, pax, bid = _chat(client, user_factory, "FloodWs")

    # Выбираем весь потолок обычным путём — дальше сокету принимать уже нечего.
    for i in range(cap):
        assert client.post(f"/bookings/{bid}/messages", headers=pax["auth"],
                           json={"text": f"добор {i}"}).status_code == 200

    with client.websocket_connect(f"/ws/bookings/{bid}") as ws:
        ws.send_text(json.dumps({"type": "auth", "token": pax["token"]}))
        ws.send_text(json.dumps({"type": "message", "text": "через сокет", "temp_id": -7}))
        frame = json.loads(ws.receive_text())

    assert frame.get("type") == "rejected", (
        f"сокет принял сообщение сверх потолка или промолчал: {frame}"
    )
    assert frame.get("temp_id") == -7, (
        f"в отказе нет номера сообщения — экран не поймёт, какое пометить недоставленным: {frame}"
    )

    # И сообщение действительно не сохранилось.
    history = client.get(f"/bookings/{bid}/messages", headers=pax["auth"]).json()
    items = history.get("items", history) if isinstance(history, dict) else history
    assert all("через сокет" != str(m.get("text", "")) for m in items), (
        "сообщение сверх потолка всё-таки сохранилось"
    )


def test_контроль_обычное_сообщение_по_сокету_доходит(client, user_factory):
    """Контрольный случай: без потолка тот же путь обязан работать. Иначе проверка выше
    зеленела бы просто потому, что по сокету ничего не отправляется в принципе."""
    import json

    driver, pax, bid = _chat(client, user_factory, "FloodWsOk")
    with client.websocket_connect(f"/ws/bookings/{bid}") as ws:
        ws.send_text(json.dumps({"type": "auth", "token": pax["token"]}))
        ws.send_text(json.dumps({"type": "message", "text": "обычное по сокету", "temp_id": -3}))
        frame = json.loads(ws.receive_text())

    assert frame.get("type") == "message", f"обычное сообщение по сокету не прошло: {frame}"
    assert frame.get("text") == "обычное по сокету", frame
