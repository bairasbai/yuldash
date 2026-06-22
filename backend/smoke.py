"""Смоук-тест бэкенда без сервера/порта: гоняем весь сценарий через TestClient.
Запуск: backend/.venv ... python smoke.py  (из папки backend)
"""
from fastapi.testclient import TestClient

from app.main import app

with TestClient(app) as c:
    assert c.get("/health").json()["status"] == "ok"

    # вход по телефону + OTP (в dev код приходит в ответе)
    code = c.post("/auth/request-code", json={"phone": "+79270000001"}).json()["dev_code"]
    v = c.post("/auth/verify", json={"phone": "+79270000001", "code": code, "name": "Байрас"}).json()
    token = v["access_token"]
    h = {"Authorization": f"Bearer {token}"}
    print("user:", v["user"]["name"], "id", v["user"]["id"])

    # /me с токеном
    assert c.get("/me", headers=h).json()["phone"] == "+79270000001"

    # создать поездку
    ride = c.post("/rides", headers=h, json={
        "from_city": "Баймак", "to_city": "Сибай",
        "depart_at": "2026-06-23T17:30:00", "seats_total": 3, "price": 350, "category": "regular",
    }).json()
    print("ride:", ride["id"], ride["from_city"], "->", ride["to_city"], ride["price"], "RUB")

    # поиск
    found = c.get("/rides", params={"from_city": "Баймак", "to_city": "Сибай"}).json()
    print("search found:", len(found))
    assert len(found) >= 1

    # заявка пассажира + матчинг
    req = c.post("/requests", headers=h, json={
        "from_city": "Баймак", "to_city": "Сибай", "seats": 1, "category": "regular",
    }).json()
    matched = c.get("/match/rides", params={"request_id": req["id"]}).json()
    print("match:", len(matched))
    assert len(matched) >= 1

    # бронь + подтверждение
    bk = c.post("/bookings", headers=h, json={"ride_id": ride["id"], "seats": 1}).json()
    print("booking:", bk["id"], bk["status"], "boarding_code", bk["boarding_code"])
    bk2 = c.post(f"/bookings/{bk['id']}/confirm", headers=h).json()
    assert bk2["status"] == "confirmed"

    # водитель на линии
    dp = c.post("/driver/online", headers=h, json={"online": True}).json()
    assert dp["online"] is True

    # чат по брони
    c.post(f"/bookings/{bk['id']}/messages", headers=h, json={"text": "Буду у вокзала в 17:20"})
    msgs = c.get(f"/bookings/{bk['id']}/messages", headers=h).json()
    print("messages:", len(msgs))
    assert len(msgs) >= 1

    # семейный контроль: контакт → шаринг поездки → статус «сел»
    contact = c.post("/trusted-contacts", headers=h, json={"name": "Гульназ", "relation": "Сестра", "phone": "+79277778899"}).json()
    c.post(f"/bookings/{bk['id']}/share", headers=h, json={"contact_id": contact["id"]})
    shares = c.post(f"/bookings/{bk['id']}/trip-status", headers=h, json={"status": "sat"}).json()
    print("family share status:", shares[0]["last_status"])
    assert shares[0]["last_status"] == "sat"

    # безопасность: SOS
    sos = c.post("/sos", headers=h, json={"category": "medical", "note": "нужна помощь"}).json()
    print("sos:", sos["id"], sos["category"], sos["status"])
    assert sos["status"] == "open"

    print("SMOKE OK")
