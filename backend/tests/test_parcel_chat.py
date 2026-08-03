# -*- coding: utf-8 -*-
"""Чат по посылке: отправитель ↔ назначенный курьер.

Зачем он вообще: до этого по доставке можно было только ПОЗВОНИТЬ. Половина вопросов —
одна фраза («оставь у соседей», «я на работе до шести», «звони, домофон не работает»):
звонок для такого избыточен и не оставляет следа, если потом спор.

Чат — ЗЕРКАЛО чата такси-заказа (test_taxi_polish2.py), поэтому и проверяем то же самое:
- участники: отправитель + НАЗНАЧЕННЫЙ курьер; пока курьера нет — чата нет (409), чужому 403;
- писать можно, пока посылка в работе (accepted / in_transit / returning);
- после вручения/возврата/отмены история читается, но не пишется (read-only) — по ней спор;
- блокировка между людьми уважается;
- пуш второй стороне уходит с data.type = "parcel_chat" (клиент кладёт его в канал
  «Сообщения», а не в «Поездки») и id посылки;
- удаление аккаунта и ретеншен-чистка не спотыкаются о новую FK-связь.
"""
import json
from datetime import timedelta

import pytest
from sqlmodel import Session, select
from starlette.websockets import WebSocketDisconnect

from app import cleanup
from app.db import engine
from app.models import Block, Message, ParcelDelivery, UserRole
from app.timeutil import utcnow

from test_parcels import _create_parcel


@pytest.fixture
def pushes(monkeypatch):
    """Перехватываем FCM: важно не «дошло ли», а ЧТО мы кладём в data (канал уведомления)."""
    sent: list = []
    monkeypatch.setattr("app.routers.chat.send_push",
                        lambda session, user_id, title, body, data=None, **kw:
                        sent.append({"user_id": user_id, "title": title, "body": body, "data": data}))
    return sent


def _accepted_parcel(client, user_factory, sender_name, courier_name):
    """Посылка, принятая курьером: это МИНИМУМ, с которого чат вообще существует."""
    sender = user_factory(name=sender_name)
    courier = user_factory(name=courier_name, role=UserRole.driver)
    pid = _create_parcel(client, sender, from_city="Сибай", to_city="Баймак").json()["id"]
    assert client.post(f"/parcels/{pid}/accept", headers=courier["auth"]).status_code == 200
    return sender, courier, pid


def _deliver(client, courier, pid):
    with Session(engine) as s:
        code = s.get(ParcelDelivery, pid).confirm_code
    assert client.post(f"/parcels/{pid}/status", headers=courier["auth"],
                       json={"status": "in_transit"}).status_code == 200
    assert client.post(f"/parcels/{pid}/status", headers=courier["auth"],
                       json={"status": "delivered", "code": code}).status_code == 200


# ============================ Основной сценарий ============================

def test_parcel_chat_rest_send_and_history(client, user_factory):
    """Отправитель и курьер переписываются по REST; история видна обоим и в хронологии."""
    sender, courier, pid = _accepted_parcel(client, user_factory, "ЧатОтпр", "ЧатКурьер")

    r = client.post(f"/parcels/{pid}/messages", headers=sender["auth"],
                    json={"text": "Оставь у соседей, я на работе до шести"})
    assert r.status_code == 200, r.text
    # Привязка ровно одна — к посылке (чат брони и чат такси-заказа не задеты).
    assert r.json()["parcel_id"] == pid
    assert r.json()["booking_id"] is None and r.json()["order_id"] is None

    r2 = client.post(f"/parcels/{pid}/messages", headers=courier["auth"],
                     json={"text": "Понял, буду через час"})
    assert r2.status_code == 200, r2.text

    for who in (sender, courier):
        hist = client.get(f"/parcels/{pid}/messages?limit=500", headers=who["auth"])
        assert hist.status_code == 200, hist.text
        assert [m["text"] for m in hist.json()] == [
            "Оставь у соседей, я на работе до шести", "Понял, буду через час",
        ]


# ============================ Доступ ============================

def test_parcel_chat_forbidden_for_outsider(client, user_factory):
    """Чужак (ни отправитель, ни курьер) — 403 и на чтение, и на запись."""
    sender, courier, pid = _accepted_parcel(client, user_factory, "ЧужОтпр", "ЧужКурьер")
    outsider = user_factory(name="ЧатЧужак")
    assert client.get(f"/parcels/{pid}/messages", headers=outsider["auth"]).status_code == 403
    assert client.post(f"/parcels/{pid}/messages", headers=outsider["auth"],
                       json={"text": "привет"}).status_code == 403


def test_parcel_chat_closed_before_courier_accepts(client, user_factory):
    """Пока курьера нет — чата нет: отправителю 409 (говорить не с кем), чужому по-прежнему 403."""
    sender = user_factory(name="РаноОтпр")
    stranger = user_factory(name="РаноЧужой", role=UserRole.driver)
    pid = _create_parcel(client, sender).json()["id"]

    assert client.post(f"/parcels/{pid}/messages", headers=sender["auth"],
                       json={"text": "рано"}).status_code == 409
    assert client.get(f"/parcels/{pid}/messages", headers=sender["auth"]).status_code == 409
    # Курьер, который заявку ещё НЕ взял, участником не является — телефоны и чат ему закрыты.
    assert client.get(f"/parcels/{pid}/messages", headers=stranger["auth"]).status_code == 403


def test_parcel_chat_respects_block(client, user_factory):
    """Заблокировал человека — переписка недоступна (как в чате брони и заказа)."""
    sender, courier, pid = _accepted_parcel(client, user_factory, "БлокОтпр", "БлокКурьер")
    with Session(engine) as s:
        s.add(Block(user_id=sender["id"], blocked_user_id=courier["id"]))
        s.commit()
    assert client.post(f"/parcels/{pid}/messages", headers=sender["auth"],
                       json={"text": "нельзя"}).status_code == 403
    assert client.post(f"/parcels/{pid}/messages", headers=courier["auth"],
                       json={"text": "тоже нельзя"}).status_code == 403


def test_parcel_chat_errors_are_bilingual(client, user_factory):
    """Отказы видит живой человек → они обязаны быть на двух языках (CLAUDE.md §3):
    detail = {ru, ba}, иначе башкир получает общую заглушку по HTTP-коду вместо объяснения."""
    sender = user_factory(name="ЯзыкОтпр")
    outsider = user_factory(name="ЯзыкЧужак")
    pid = _create_parcel(client, sender).json()["id"]

    early = client.get(f"/parcels/{pid}/messages", headers=sender["auth"])   # курьера ещё нет
    assert early.status_code == 409
    foreign = client.get(f"/parcels/{pid}/messages", headers=outsider["auth"])
    assert foreign.status_code == 403
    for r in (early, foreign):
        detail = r.json()["detail"]
        assert isinstance(detail, dict), detail
        assert detail.get("ru") and detail.get("ba"), detail


# ============================ Окно статусов ============================

def test_parcel_chat_open_while_returning(client, user_factory):
    """Курьер везёт посылку ОБРАТНО — договориться нужно как раз сейчас, чат открыт."""
    sender, courier, pid = _accepted_parcel(client, user_factory, "ВозврОтпр", "ВозврКурьер")
    assert client.post(f"/parcels/{pid}/return-start", headers=courier["auth"],
                       json={"reason": "Получателя нет дома"}).status_code == 200
    r = client.post(f"/parcels/{pid}/messages", headers=courier["auth"],
                    json={"text": "Везу обратно, буду к семи"})
    assert r.status_code == 200, r.text


def test_parcel_chat_read_only_after_delivery(client, user_factory):
    """После вручения история читается, но новые сообщения не принимаются — по ней разбирают спор."""
    sender, courier, pid = _accepted_parcel(client, user_factory, "ГотовоОтпр", "ГотовоКурьер")
    assert client.post(f"/parcels/{pid}/messages", headers=sender["auth"],
                       json={"text": "до вручения"}).status_code == 200
    _deliver(client, courier, pid)

    hist = client.get(f"/parcels/{pid}/messages", headers=courier["auth"])
    assert hist.status_code == 200, hist.text
    assert [m["text"] for m in hist.json()] == ["до вручения"]
    assert client.post(f"/parcels/{pid}/messages", headers=sender["auth"],
                       json={"text": "поздно"}).status_code == 409


def test_parcel_chat_read_only_after_cancel(client, user_factory):
    """Отменённая доставка: переписка остаётся доступной для чтения (это и есть доказательство)."""
    sender, courier, pid = _accepted_parcel(client, user_factory, "ОтменаОтпр", "ОтменаКурьер")
    assert client.post(f"/parcels/{pid}/messages", headers=courier["auth"],
                       json={"text": "Не открывают дверь"}).status_code == 200
    assert client.post(f"/parcels/{pid}/cancel", headers=sender["auth"]).status_code == 200

    hist = client.get(f"/parcels/{pid}/messages", headers=sender["auth"])
    assert hist.status_code == 200, hist.text
    assert [m["text"] for m in hist.json()] == ["Не открывают дверь"]
    assert client.post(f"/parcels/{pid}/messages", headers=sender["auth"],
                       json={"text": "поздно"}).status_code == 409


# ============================ Пуш второй стороне ============================

def test_parcel_chat_push_goes_to_messages_channel(client, user_factory, pushes):
    """Контракт с клиентом: data.type = "parcel_chat" + id посылки. Именно по нему Android
    кладёт уведомление в канал «Сообщения», а не в «Поездки» (иначе чат звенел бы у тех,
    кто заглушил поездки), и открывает переписку по этой доставке."""
    sender, courier, pid = _accepted_parcel(client, user_factory, "ПушОтпр", "ПушКурьер")

    pushes.clear()
    assert client.post(f"/parcels/{pid}/messages", headers=sender["auth"],
                       json={"text": "Домофон не работает"}).status_code == 200
    assert len(pushes) == 1, pushes
    assert pushes[0]["user_id"] == courier["id"]          # пуш ушёл ВТОРОЙ стороне
    assert pushes[0]["data"] == {"type": "parcel_chat", "id": pid}

    pushes.clear()
    assert client.post(f"/parcels/{pid}/messages", headers=courier["auth"],
                       json={"text": "Позвоню, как подъеду"}).status_code == 200
    assert len(pushes) == 1 and pushes[0]["user_id"] == sender["id"]
    assert pushes[0]["data"] == {"type": "parcel_chat", "id": pid}


# ============================ WebSocket ============================

def test_parcel_chat_ws_relays_between_participants(client, user_factory):
    """WS: сообщение курьера долетает отправителю живьём и сохраняется в историю."""
    sender, courier, pid = _accepted_parcel(client, user_factory, "ВсОтпр", "ВсКурьер")
    with client.websocket_connect(f"/ws/parcel/{pid}/chat") as sender_ws:
        sender_ws.send_text(json.dumps({"type": "auth", "token": sender["token"]}))
        with client.websocket_connect(f"/ws/parcel/{pid}/chat") as courier_ws:
            courier_ws.send_text(json.dumps({"type": "auth", "token": courier["token"]}))
            courier_ws.send_text("{битый json")   # не роняет соединение
            courier_ws.send_text(json.dumps({"type": "message", "text": "Я у синих ворот"}))
            msg = json.loads(sender_ws.receive_text())
            assert msg["type"] == "message" and msg["text"] == "Я у синих ворот"
    hist = client.get(f"/parcels/{pid}/messages", headers=sender["auth"]).json()
    assert [m["text"] for m in hist] == ["Я у синих ворот"]


def test_parcel_chat_ws_rejects_outsider(client, user_factory):
    """Чужак в WS чата посылки — Forbidden, канал закрывается."""
    sender, courier, pid = _accepted_parcel(client, user_factory, "ВсЧужОтпр", "ВсЧужКурьер")
    outsider = user_factory(name="ВсЧужак")
    with pytest.raises((WebSocketDisconnect, Exception)):
        with client.websocket_connect(f"/ws/parcel/{pid}/chat") as ws:
            ws.send_text(json.dumps({"type": "auth", "token": outsider["token"]}))
            ws.receive_text()


# ============================ Чужие чаты не задеты ============================

def test_booking_and_order_chats_intact(client, user_factory):
    """Регресс: у чата брони своя история, у чата посылки — своя, они не смешиваются."""
    from test_api import _ride

    sender, courier, pid = _accepted_parcel(client, user_factory, "МиксОтпр", "МиксКурьер")
    ride_id = _ride(client, courier)
    bid = client.post("/bookings", headers=sender["auth"],
                      json={"ride_id": ride_id, "seats": 1}).json()["id"]

    assert client.post(f"/bookings/{bid}/messages", headers=sender["auth"],
                       json={"text": "по попутке"}).status_code == 200
    assert client.post(f"/parcels/{pid}/messages", headers=sender["auth"],
                       json={"text": "по посылке"}).status_code == 200

    booking_hist = [m["text"] for m in client.get(f"/bookings/{bid}/messages",
                                                 headers=courier["auth"]).json()]
    parcel_hist = [m["text"] for m in client.get(f"/parcels/{pid}/messages",
                                                headers=courier["auth"]).json()]
    assert booking_hist == ["по попутке"]
    assert parcel_hist == ["по посылке"]


# ============================ Каскад удаления и ретеншен ============================

def test_delete_account_with_parcel_chat_does_not_break(client, user_factory):
    """Отправитель удаляет аккаунт, а в чате доставки есть сообщения ОБЕИХ сторон.

    Сообщение курьера ссылается на посылку, но не на удаляемого юзера — без явной чистки
    по parcel_id удаление падало бы по внешнему ключу (Postgres), и аккаунт становился бы
    неудаляемым. Это прямое требование 152-ФЗ, поэтому проверяем отдельно."""
    from app.account import delete_user_account
    from app.models import User

    sender, courier, pid = _accepted_parcel(client, user_factory, "УдалОтпр", "УдалКурьер")
    assert client.post(f"/parcels/{pid}/messages", headers=sender["auth"],
                       json={"text": "мой текст"}).status_code == 200
    assert client.post(f"/parcels/{pid}/messages", headers=courier["auth"],
                       json={"text": "текст курьера"}).status_code == 200

    with Session(engine) as s:
        delete_user_account(s, s.get(User, sender["id"]))

    with Session(engine) as s:
        assert s.get(User, sender["id"]) is None
        assert s.get(ParcelDelivery, pid) is None                       # посылка ушла с отправителем
        left = s.exec(select(Message).where(Message.parcel_id == pid)).all()
        assert left == [], "переписка по удалённой посылке не должна оставаться сиротой"
        assert s.get(User, courier["id"]) is not None                   # курьер цел


def test_retention_keeps_delivered_parcel_with_chat(client, user_factory):
    """Ретеншен: старую доставку С перепиской не сносим (по ней разбирают спор), без неё — сносим.

    Без NOT EXISTS-гарда на message.parcel_id чистка вообще падала бы на внешнем ключе —
    и ретеншен ломался бы навсегда, а не «пропускал одну строку»."""
    sender, courier, pid_chat = _accepted_parcel(client, user_factory, "ЧистОтпр", "ЧистКурьер")
    assert client.post(f"/parcels/{pid_chat}/messages", headers=sender["auth"],
                       json={"text": "свежая переписка"}).status_code == 200
    _deliver(client, courier, pid_chat)

    _, courier2, pid_plain = _accepted_parcel(client, user_factory, "ЧистОтпр2", "ЧистКурьер2")
    _deliver(client, courier2, pid_plain)

    old = utcnow() - timedelta(days=200)
    with Session(engine) as s:
        for p in (s.get(ParcelDelivery, pid_chat), s.get(ParcelDelivery, pid_plain)):
            p.created_at = old            # состарить: обе попадают в окно ретеншена
            p.commission_kop = 0          # комиссия закрыта — финансы не держат строку
            p.commission_paid = True
            s.add(p)
        s.commit()

    cleanup.main()   # реальная чистка

    with Session(engine) as s:
        assert s.get(ParcelDelivery, pid_chat) is not None, "доставку с перепиской чистить нельзя"
        assert s.exec(select(Message).where(Message.parcel_id == pid_chat)).all() != []
        assert s.get(ParcelDelivery, pid_plain) is None, "доставка без следов должна вычищаться"
