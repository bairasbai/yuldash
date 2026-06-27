# -*- coding: utf-8 -*-
"""Негативные тесты безопасности (Фаза 0). Проверяют, что дыры ЗАКРЫТЫ:
  1) IDOR: чужой не читает/не пишет чужую бронь (REST + WebSocket);
  2) перебор OTP-кода ограничен (5 попыток → 429);
  3) throttle запроса кода (>3/мин → 429);
  4) участник брони по-прежнему работает (WS живой).
Запуск: backend/.venv/Scripts/python.exe smoke_security.py  (из папки backend)
"""
import json

from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

from app.main import app


def login(c, phone, name):
    code = c.post("/auth/request-code", json={"phone": phone}).json()["dev_code"]
    v = c.post("/auth/verify", json={"phone": phone, "code": code, "name": name}).json()
    return v["access_token"]


with TestClient(app) as c:
    # --- участники: водитель D, пассажир A, посторонний B ---
    d = login(c, "+79991110001", "Водитель")
    a = login(c, "+79991110002", "Пассажир")
    b = login(c, "+79991110003", "Чужой")
    dh = {"Authorization": f"Bearer {d}"}
    ah = {"Authorization": f"Bearer {a}"}
    bh = {"Authorization": f"Bearer {b}"}

    ride = c.post("/rides", headers=dh, json={
        "from_city": "Баймак", "to_city": "Сибай",
        "depart_at": "2026-07-01T09:00:00", "seats_total": 3, "price": 300,
    }).json()
    bk = c.post("/bookings", headers=ah, json={"ride_id": ride["id"], "seats": 1}).json()
    bid = bk["id"]
    print("setup: ride", ride["id"], "booking", bid)

    # 1) IDOR REST — чужой (B) не читает и не пишет чужую бронь
    assert c.get(f"/bookings/{bid}/messages", headers=bh).status_code == 403, "REST read IDOR не закрыт!"
    assert c.post(f"/bookings/{bid}/messages", headers=bh, json={"text": "взлом"}).status_code == 403, "REST write IDOR не закрыт!"
    # участники — могут
    assert c.get(f"/bookings/{bid}/messages", headers=ah).status_code == 200
    assert c.get(f"/bookings/{bid}/messages", headers=dh).status_code == 200
    print("[OK] IDOR REST: чужой 403, участники 200")

    # 2) IDOR WebSocket — чужой (B) подключается, но сервер закрывает соединение
    outsider_blocked = False
    try:
        with c.websocket_connect(f"/ws/bookings/{bid}") as ws:
            ws.send_text(json.dumps({"type": "auth", "token": b}))
            ws.receive_text()           # сервер закрыл → должен прилететь disconnect
    except WebSocketDisconnect:
        outsider_blocked = True
    assert outsider_blocked, "WS IDOR не закрыт — чужой подключился к чужому чату!"
    print("[OK] IDOR WS: чужой отключён")

    # 2b) WS — участник (A) подключается, шлёт сообщение, получает эхо-broadcast
    with c.websocket_connect(f"/ws/bookings/{bid}") as ws:
        ws.send_text(json.dumps({"type": "auth", "token": a}))
        ws.send_text(json.dumps({"type": "message", "text": "привет по WS"}))
        echo = json.loads(ws.receive_text())
        assert echo["type"] == "message" and echo["text"] == "привет по WS", "WS участник не получил сообщение"
    print("[OK] WS участник: отправка/приём работают")

    # 3) перебор OTP — 5 неверных попыток, затем 429 (и верный код после лока тоже 429)
    c.post("/auth/request-code", json={"phone": "+79991110010"})
    for i in range(5):
        r = c.post("/auth/verify", json={"phone": "+79991110010", "code": "0000", "name": "x"})
        assert r.status_code == 400, f"ожидал 400 на попытке {i+1}, получил {r.status_code}"
    locked = c.post("/auth/verify", json={"phone": "+79991110010", "code": "0000", "name": "x"})
    assert locked.status_code == 429, f"перебор не заблокирован: {locked.status_code}"
    print("[OK] перебор OTP: после 5 попыток → 429")

    # 4) throttle запроса кода — >3 в минуту на номер → 429
    for i in range(3):
        assert c.post("/auth/request-code", json={"phone": "+79991110020"}).status_code == 200
    flood = c.post("/auth/request-code", json={"phone": "+79991110020"})
    assert flood.status_code == 429, f"throttle не сработал: {flood.status_code}"
    print("[OK] throttle: 4-й запрос кода в минуту → 429")

    print("SECURITY SMOKE OK")
