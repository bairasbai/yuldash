# -*- coding: utf-8 -*-
"""Смоук-тест бэкенда без сервера/порта: гоняем весь сценарий через TestClient.
Запуск: backend/.venv ... python smoke.py  (из папки backend)
"""
from fastapi.testclient import TestClient

from app.main import app

with TestClient(app) as c:
    assert c.get("/health").json()["status"] == "ok"

    # вход водителя по телефону + OTP (в dev код приходит в ответе)
    code = c.post("/auth/request-code", json={"phone": "+79270000001"}).json()["dev_code"]
    v = c.post("/auth/verify", json={"phone": "+79270000001", "code": code, "name": "Байрас"}).json()
    token = v["access_token"]
    h = {"Authorization": f"Bearer {token}"}
    print("driver:", v["user"]["name"], "id", v["user"]["id"])

    # вход пассажира (бронирует чужие поездки — свои бронировать нельзя)
    pcode = c.post("/auth/request-code", json={"phone": "+79270000002"}).json()["dev_code"]
    pv = c.post("/auth/verify", json={"phone": "+79270000002", "code": pcode, "name": "Гульназ"}).json()
    ph = {"Authorization": f"Bearer {pv['access_token']}"}
    print("passenger:", pv["user"]["name"], "id", pv["user"]["id"])

    # /me с токеном
    assert c.get("/me", headers=h).json()["phone"] == "+79270000001"
    assert c.get("/me", headers=ph).json()["phone"] == "+79270000002"

    # водитель создаёт поездку
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
    req = c.post("/requests", headers=ph, json={
        "from_city": "Баймак", "to_city": "Сибай", "seats": 1, "category": "regular",
    }).json()
    matched = c.get("/match/rides", headers=ph, params={"request_id": req["id"]}).json()
    print("match:", len(matched))
    assert len(matched) >= 1

    # пассажир бронирует, водитель подтверждает
    bk = c.post("/bookings", headers=ph, json={"ride_id": ride["id"], "seats": 1}).json()
    print("booking:", bk["id"], bk["status"], "boarding_code", bk["boarding_code"])
    bk2 = c.post(f"/bookings/{bk['id']}/confirm", headers=h).json()
    assert bk2["status"] == "confirmed"

    # водитель на линии
    dp = c.post("/driver/online", headers=h, json={"online": True}).json()
    assert dp["online"] is True

    # чат по брони
    c.post(f"/bookings/{bk['id']}/messages", headers=ph, json={"text": "Буду у вокзала в 17:20"})
    msgs = c.get(f"/bookings/{bk['id']}/messages", headers=h).json()
    print("messages:", len(msgs))
    assert len(msgs) >= 1

    # семейный контроль: контакт пассажира -> шаринг поездки -> статус «сел»
    contact = c.post("/trusted-contacts", headers=ph, json={
        "name": "Гульназ", "relation": "Сестра", "phone": "+79277778899",
    }).json()
    c.post(f"/bookings/{bk['id']}/share", headers=ph, json={"contact_id": contact["id"]})
    shares = c.post(f"/bookings/{bk['id']}/trip-status", headers=ph, json={"status": "sat"}).json()
    print("family share status:", shares[0]["last_status"])
    assert shares[0]["last_status"] == "sat"

    # безопасность: SOS от пассажира
    sos = c.post("/sos", headers=ph, json={"category": "medical", "note": "нужна помощь"}).json()
    print("sos:", sos["id"], sos["category"], sos["status"])
    assert sos["status"] == "open"

    # премиум-предпочтения поездки round-trip (питомцы / детское кресло / только женщины / багаж)
    pride = c.post("/rides", headers=h, json={
        "from_city": "Сибай", "to_city": "Уфа",
        "depart_at": "2026-06-24T08:00:00", "seats_total": 3, "price": 700,
        "pets_allowed": True, "child_seat": True, "women_only": True, "baggage": True,
    }).json()
    assert pride["pets_allowed"] and pride["child_seat"] and pride["women_only"] and pride["baggage"]
    print("prefs ride:", pride["id"], "pets/child/women/baggage =",
          pride["pets_allowed"], pride["child_seat"], pride["women_only"], pride["baggage"])
    wonly = c.get("/rides", params={"from_city": "Сибай", "to_city": "Уфа", "women_only": True}).json()
    assert len(wonly) >= 1 and wonly[0]["women_only"] is True and wonly[0]["child_seat"] is True
    print("women_only filter found:", len(wonly), "child_seat shown in card:", wonly[0]["child_seat"])

    # проверка водителя: профиль авто -> фото -> отправка на проверку -> статус pending
    c.post("/driver/profile", headers=h, json={
        "car_make": "Kia", "car_model": "Rio", "car_color": "Белый", "car_plate": "А123ВС102", "seats": 4,
    })
    lic = c.post("/upload/photo", headers=h, json={"photo_b64": "aGVsbG8=", "ext": "jpg"}).json()["url"]
    carp = c.post("/upload/photo", headers=h, json={"photo_b64": "d29ybGQ=", "ext": "jpg"}).json()["url"]
    vr = c.post("/driver/verify", headers=h, json={"license_url": lic, "car_photo_url": carp}).json()
    assert vr["docs_status"] == "pending"
    st = c.get("/driver/status", headers=h).json()
    print("driver verify:", st["docs_status"], "car:", st["car_make"], st["car_model"], st["car_plate"])
    assert st["car_make"] == "Kia" and st["docs_status"] == "pending"

    # модерация админом: подтверждаем водителя -> verified=True
    admin_code = c.post("/auth/request-code", json={"phone": "+79270000777"}).json()["dev_code"]
    av = c.post("/auth/verify", json={"phone": "+79270000777", "code": admin_code, "name": "Админ"}).json()
    # делаем этого пользователя админом напрямую в БД (в проде роль выдаётся вручную)
    from sqlmodel import Session as _S, select as _sel
    from app.db import engine as _eng
    from app.models import User as _U, UserRole as _R
    with _S(_eng) as _s:
        _au = _s.exec(_sel(_U).where(_U.phone == "+79270000777")).first()
        assert _au is not None
        _au.role = _R.admin
        _s.add(_au)
        _s.commit()
    ah = {"Authorization": f"Bearer {av['access_token']}"}
    driver_id = v["user"]["id"]
    mod = c.post(f"/admin/drivers/{driver_id}/moderate", headers=ah, json={"approve": True}).json()
    print("moderate:", mod)
    assert mod["verified"] is True and mod["docs_status"] == "verified"

    print("SMOKE OK")