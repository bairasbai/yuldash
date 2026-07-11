"""B7b «Такси-полировка: связь и контроль»: чат такси-заказа (REST + WS, окно статусов,
read-only после завершения, booking-чат цел), SOS/шаринг заказа, пульс-панель админа,
напоминание о чеке самозанятого (дедуп 1/сутки)."""
import json

import pytest
from starlette.websockets import WebSocketDisconnect

from app.models import UserRole

from test_api import _ride
from test_instant import _offered_order, fake_redis  # noqa: F401 — fixture реэкспорт
from test_taxi_polish import _accepted_order


# ============================ Чат такси-заказа (B7b-1) ============================
def test_order_chat_rest_send_and_history(client, user_factory, fake_redis):
    """Участники активного заказа переписываются по REST; история видна обоим."""
    d, pax, order = _accepted_order(client, user_factory, fake_redis, "ChDrv", "ChPax")
    oid = order["id"]
    r = client.post(f"/instant/orders/{oid}/messages", headers=pax["auth"], json={"text": "Я у подъезда 3"})
    assert r.status_code == 200, r.text
    assert r.json()["order_id"] == oid and r.json()["booking_id"] is None
    r = client.post(f"/instant/orders/{oid}/messages", headers=d["auth"], json={"text": "Еду, 2 минуты"})
    assert r.status_code == 200, r.text
    for who in (pax, d):
        hist = client.get(f"/instant/orders/{oid}/messages", headers=who["auth"])
        assert hist.status_code == 200
        texts = [m["text"] for m in hist.json()]
        assert texts == ["Я у подъезда 3", "Еду, 2 минуты"]


def test_order_chat_forbidden_for_outsider(client, user_factory, fake_redis):
    """Чужак (не участник заказа) — 403 и на чтение, и на запись."""
    d, pax, order = _accepted_order(client, user_factory, fake_redis, "ChOutDrv", "ChOutPax")
    outsider = user_factory("ChOutsider")
    oid = order["id"]
    assert client.get(f"/instant/orders/{oid}/messages", headers=outsider["auth"]).status_code == 403
    assert client.post(f"/instant/orders/{oid}/messages", headers=outsider["auth"],
                       json={"text": "hi"}).status_code == 403


def test_order_chat_closed_before_accept(client, user_factory, fake_redis):
    """До accept чата нет: пассажиру 409 (водитель ещё не назначен), кандидату с оффером 403."""
    d, pax, order = _offered_order(client, user_factory, fake_redis, "ChEarlyDrv", "ChEarlyPax")
    oid = order["id"]
    assert client.post(f"/instant/orders/{oid}/messages", headers=pax["auth"],
                       json={"text": "рано"}).status_code == 409
    assert client.get(f"/instant/orders/{oid}/messages", headers=pax["auth"]).status_code == 409
    # Кандидат с оффером участником ещё НЕ является (телефоны/чат до accept закрыты).
    assert client.get(f"/instant/orders/{oid}/messages", headers=d["auth"]).status_code == 403


def test_order_chat_read_only_after_done(client, user_factory, fake_redis):
    """После done история читается, но новые сообщения не принимаются (read-only)."""
    d, pax, order = _accepted_order(client, user_factory, fake_redis, "ChDoneDrv", "ChDonePax")
    oid = order["id"]
    assert client.post(f"/instant/orders/{oid}/messages", headers=pax["auth"],
                       json={"text": "до завершения"}).status_code == 200
    for step in ("arrived", "onboard", "done"):
        assert client.post(f"/instant/orders/{oid}/{step}", headers=d["auth"]).status_code == 200
    hist = client.get(f"/instant/orders/{oid}/messages", headers=d["auth"])
    assert hist.status_code == 200 and [m["text"] for m in hist.json()] == ["до завершения"]
    assert client.post(f"/instant/orders/{oid}/messages", headers=pax["auth"],
                       json={"text": "поздно"}).status_code == 409


def test_order_chat_ws_relays_between_participants(client, user_factory, fake_redis):
    """WS чата заказа: сообщение водителя долетает пассажиру живьём (и сохраняется в историю)."""
    d, pax, order = _accepted_order(client, user_factory, fake_redis, "ChWsDrv", "ChWsPax")
    oid = order["id"]
    with client.websocket_connect(f"/ws/instant/{oid}/chat") as pax_ws:
        pax_ws.send_text(json.dumps({"type": "auth", "token": pax["token"]}))
        with client.websocket_connect(f"/ws/instant/{oid}/chat") as drv_ws:
            drv_ws.send_text(json.dumps({"type": "auth", "token": d["token"]}))
            drv_ws.send_text("{битый json")   # не роняет соединение
            drv_ws.send_text(json.dumps({"type": "message", "text": "Выхожу к машине?"}))
            msg = json.loads(pax_ws.receive_text())
            assert msg["type"] == "message" and msg["text"] == "Выхожу к машине?"
    hist = client.get(f"/instant/orders/{oid}/messages", headers=pax["auth"]).json()
    assert [m["text"] for m in hist] == ["Выхожу к машине?"]


def test_order_chat_ws_rejects_outsider(client, user_factory, fake_redis):
    """Чужак в WS чата заказа — Forbidden, канал закрывается."""
    d, pax, order = _accepted_order(client, user_factory, fake_redis, "ChWsOutD", "ChWsOutP")
    outsider = user_factory("ChWsOutsider")
    with pytest.raises((WebSocketDisconnect, Exception)):
        with client.websocket_connect(f"/ws/instant/{order['id']}/chat") as ws:
            ws.send_text(json.dumps({"type": "auth", "token": outsider["token"]}))
            ws.receive_text()


def test_booking_chat_intact_alongside_order_chat(client, user_factory, fake_redis):
    """Booking-чат не сломан: сообщение по брони живёт отдельно от чата заказа того же юзера."""
    d, pax, order = _accepted_order(client, user_factory, fake_redis, "ChBothDrv", "ChBothPax")
    # Параллельная бронь попутки той же пары.
    ride_id = _ride(client, d)
    bid = client.post("/bookings", headers=pax["auth"], json={"ride_id": ride_id, "seats": 1}).json()["id"]
    r = client.post(f"/bookings/{bid}/messages", headers=pax["auth"], json={"text": "по попутке"})
    assert r.status_code == 200 and r.json()["booking_id"] == bid and r.json()["order_id"] is None
    r = client.post(f"/instant/orders/{order['id']}/messages", headers=pax["auth"], json={"text": "по такси"})
    assert r.status_code == 200
    booking_hist = [m["text"] for m in client.get(f"/bookings/{bid}/messages", headers=d["auth"]).json()]
    order_hist = [m["text"] for m in client.get(f"/instant/orders/{order['id']}/messages", headers=d["auth"]).json()]
    assert booking_hist == ["по попутке"]
    assert order_hist == ["по такси"]
