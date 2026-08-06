"""Тесты API Юлдаша: ядро + закрытые дыры безопасности (регрессии не пройдут)."""
import base64
import os
import threading

import pytest

from app.models import UserRole


def _ride(client, drv, seats=3, **extra):
    """Общий помощник «опубликовать поездку». `extra` (например comment=…) нужен там,
    где тесту требуются ДВЕ разные поездки: байт-в-байт одинаковые схлопываются как
    двойной тап (гард 2026-08-06)."""
    r = client.post("/rides", headers=drv["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай",
        "depart_at": "2030-01-01T10:00:00", "seats_total": seats, "price": 300, **extra,
    })
    assert r.status_code == 200, r.text
    return r.json()["id"]


def test_health(client):
    assert client.get("/health").json()["status"] == "ok"


def test_version(client):
    body = client.get("/version").json()
    assert "version" in body and "env" in body


def test_health_reports_db(client):
    body = client.get("/health").json()
    assert body["status"] == "ok" and body["db"] == "ok"


def test_security_headers(client):
    h = client.get("/health").headers
    assert h.get("X-Content-Type-Options") == "nosniff"
    assert h.get("X-Frame-Options") == "DENY"


def test_strict_rate_limit_on_auth(client):
    """Строгий лимит на /auth/* отсекает перебор (анти-абуз перед запуском).
    Лимитер для тестов выключен глобально (conftest) — включаем локально."""
    from app.config import settings
    saved_en, saved_lim = settings.rate_limit_enabled, settings.rate_limit_auth_per_min
    settings.rate_limit_enabled = True
    settings.rate_limit_auth_per_min = 3
    try:
        codes = [client.post("/auth/vk-callback").status_code for _ in range(6)]
        assert 429 in codes, codes          # после 3 запросов — отбой
        assert codes[0] != 429               # первые проходят (до лимита)
    finally:
        settings.rate_limit_enabled = saved_en
        settings.rate_limit_auth_per_min = saved_lim


def test_api_v1_alias(client):
    """После разрезки монолита каждый роут доступен и на корне (живой клиент),
    и под /api/v1 (версионирование). Зеркало не должно разойтись."""
    assert client.get("/api/v1/health").json()["status"] == "ok"
    assert client.get("/api/v1/rides").status_code == 200
    # защищённый роут под префиксом так же требует токен (а не «не найдено»)
    assert client.get("/api/v1/me").status_code in (401, 403)


def test_me_requires_auth(client):
    assert client.get("/me").status_code in (401, 403)


def test_publish_and_list_rides(client, user_factory):
    drv = user_factory("Driver", role=UserRole.driver)
    _ride(client, drv)
    data = client.get("/rides").json()
    items = data if isinstance(data, list) else data.get("items", [])
    assert any(x["from_city"] == "Баймак" for x in items)


def test_overbooking_blocked(client, user_factory):
    drv = user_factory("Drv", role=UserRole.driver)
    rid = _ride(client, drv, seats=1)
    pax = user_factory("Pax")
    # больше мест, чем есть → отказ
    assert client.post("/bookings", headers=pax["auth"], json={"ride_id": rid, "seats": 2}).status_code == 400
    # ровно 1 место → ок
    assert client.post("/bookings", headers=pax["auth"], json={"ride_id": rid, "seats": 1}).status_code == 200
    # мест больше нет → отказ
    pax2 = user_factory("Pax2")
    assert client.post("/bookings", headers=pax2["auth"], json={"ride_id": rid, "seats": 1}).status_code == 400


def test_message_access_control(client, user_factory):
    drv = user_factory("D", role=UserRole.driver)
    rid = _ride(client, drv)
    pax = user_factory("P")
    bid = client.post("/bookings", headers=pax["auth"], json={"ride_id": rid, "seats": 1}).json()["id"]
    # участник — может
    assert client.get(f"/bookings/{bid}/messages", headers=pax["auth"]).status_code == 200
    # посторонний — нельзя (та же защита, что в WS)
    outsider = user_factory("Out")
    assert client.get(f"/bookings/{bid}/messages", headers=outsider["auth"]).status_code == 403


def test_block_prevents_booking(client, user_factory):
    drv = user_factory("BlkDrv", role=UserRole.driver)
    rid = _ride(client, drv)
    pax = user_factory("BlkPax")
    # водитель блокирует пассажира
    assert client.post("/blocks", headers=drv["auth"], json={"blocked_user_id": pax["id"]}).status_code in (200, 201)
    # пассажир не может забронировать
    assert client.post("/bookings", headers=pax["auth"], json={"ride_id": rid, "seats": 1}).status_code == 403


def test_secure_docs_requires_auth(client):
    assert client.get("/secure/docs/anything.jpg").status_code in (401, 403)


def test_push_register(client, user_factory):
    u = user_factory("Push")
    assert client.post("/push/register", headers=u["auth"], json={"token": "fake-token-123"}).status_code == 200


def test_push_register_idempotent_and_reassign(client, user_factory):
    """Регресс: повторная регистрация того же FCM-токена не падает (был UniqueViolation → 500),
    токен идемпотентно перепривязывается к текущему юзеру, дубля строки не возникает."""
    from sqlmodel import Session, select
    from app.db import engine
    from app.models import DeviceToken
    a = user_factory("PushDupA")
    b = user_factory("PushDupB")
    t = "dup-token-xyz"
    # тот же токен дважды одним юзером — оба 200, без 500 (главный кейс бага)
    assert client.post("/push/register", headers=a["auth"], json={"token": t}).status_code == 200
    assert client.post("/push/register", headers=a["auth"], json={"token": t}).status_code == 200
    # другой юзер шлёт тот же токен — устройство сменило владельца, перепривязка
    assert client.post("/push/register", headers=b["auth"], json={"token": t}).status_code == 200
    # ровно одна строка на токен, владелец — последний (B); дубля нет
    with Session(engine) as s:
        rows = s.exec(select(DeviceToken).where(DeviceToken.token == t)).all()
    assert len(rows) == 1
    assert rows[0].user_id == b["id"]


def test_ws_rejects_non_participant(client, user_factory):
    drv = user_factory("WsDrv", role=UserRole.driver)
    rid = _ride(client, drv)
    pax = user_factory("WsPax")
    bid = client.post("/bookings", headers=pax["auth"], json={"ride_id": rid, "seats": 1}).json()["id"]
    outsider = user_factory("WsOut")
    # посторонний к чужому чату по WS → соединение отклоняется
    import json
    import pytest
    from starlette.websockets import WebSocketDisconnect
    # Токен — первым сообщением (R4: ?token= в URL больше не принимаем).
    with pytest.raises((WebSocketDisconnect, Exception)):
        with client.websocket_connect(f"/ws/bookings/{bid}") as ws:
            ws.send_text(json.dumps({"type": "auth", "token": outsider["token"]}))
            ws.receive_text()


def test_ws_rejects_token_revoked_by_logout(client, user_factory):
    """Регресс V1: после logout старый токен НЕ открывает WS-чат (ревокация honored)."""
    drv = user_factory("WsRevDrv", role=UserRole.driver)
    rid = _ride(client, drv)
    pax = user_factory("WsRevPax")
    bid = client.post("/bookings", headers=pax["auth"], json={"ride_id": rid, "seats": 1}).json()["id"]
    # участник выходит со всех устройств → его access-токен отозван
    assert client.post("/auth/logout", headers=pax["auth"]).status_code == 200
    import json
    import pytest
    from starlette.websockets import WebSocketDisconnect
    with pytest.raises((WebSocketDisconnect, Exception)):
        with client.websocket_connect(f"/ws/bookings/{bid}") as ws:
            ws.send_text(json.dumps({"type": "auth", "token": pax["token"]}))
            ws.receive_text()


def test_ws_location_relay(client, user_factory):
    """E2E live-трекинг БЕЗ устройств: 2 WS-клиента (водитель+пассажир) в подтверждённой брони,
    каждый шлёт свою позицию → сервер ретранслирует ДРУГОМУ с правильным role. Формат ответа —
    тот, что парсит LocationSocket на клиенте ({type:loc,role,lat,lng,bearing,ts})."""
    import json
    import time
    drv = user_factory("RelayDrv", role=UserRole.driver)
    rid = _ride(client, drv, seats=1)
    pax = user_factory("RelayPax")
    bid = client.post("/bookings", headers=pax["auth"], json={"ride_id": rid, "seats": 1}).json()["id"]
    # Live-позиция разрешена только в активной поездке → подтверждаем бронь.
    assert client.post(f"/bookings/{bid}/confirm", headers=drv["auth"]).status_code == 200
    with client.websocket_connect(f"/ws/trip/{bid}/location") as ws_d:
        ws_d.send_text(json.dumps({"type": "auth", "token": drv["token"]}))
        with client.websocket_connect(f"/ws/trip/{bid}/location") as ws_p:
            ws_p.send_text(json.dumps({"type": "auth", "token": pax["token"]}))
            time.sleep(0.3)   # дать серверу зарегистрировать ОБА соединения до первого loc
            # водитель → пассажир
            ws_d.send_text(json.dumps({"type": "loc", "lat": 54.01, "lng": 58.02, "bearing": 90}))
            m = json.loads(ws_p.receive_text())
            assert m["type"] == "loc" and m["role"] == "driver"
            assert abs(m["lat"] - 54.01) < 1e-6 and abs(m["lng"] - 58.02) < 1e-6 and m["bearing"] == 90
            # пассажир → водитель
            ws_p.send_text(json.dumps({"type": "loc", "lat": 53.05, "lng": 59.06}))
            m2 = json.loads(ws_d.receive_text())
            assert m2["type"] == "loc" and m2["role"] == "passenger"
            assert abs(m2["lat"] - 53.05) < 1e-6 and abs(m2["lng"] - 59.06) < 1e-6


def test_ws_location_rejects_unconfirmed(client, user_factory):
    """Приватность: до подтверждения брони (pending) live-позиция НЕ ретранслируется (close 1008)."""
    import json
    import pytest
    from starlette.websockets import WebSocketDisconnect
    drv = user_factory("LocPendDrv", role=UserRole.driver)
    rid = _ride(client, drv, seats=1)
    pax = user_factory("LocPendPax")
    bid = client.post("/bookings", headers=pax["auth"], json={"ride_id": rid, "seats": 1}).json()["id"]
    # бронь pending (не подтверждена) → WS локаций должен отбить
    with pytest.raises((WebSocketDisconnect, Exception)):
        with client.websocket_connect(f"/ws/trip/{bid}/location") as ws:
            ws.send_text(json.dumps({"type": "auth", "token": pax["token"]}))
            ws.receive_text()


def test_app_review_submit_moderation_and_public(client, user_factory):
    u = user_factory("Гульназ")
    # слишком короткий — 400
    assert client.post("/reviews", headers=u["auth"], json={"stars": 5, "text": "ок"}).status_code == 400
    # валидный — сохраняется НЕопубликованным
    r = client.post("/reviews", headers=u["auth"], json={"stars": 5, "text": "Очень удобно ездить со своими!", "city": "Сибай"})
    assert r.status_code == 200, r.text
    assert r.json()["published"] is False
    review_id = r.json()["id"]
    # до модерации в public пусто
    assert client.get("/reviews/public").json() == []
    # обычный юзер не модерирует
    assert client.post(f"/admin/reviews/{review_id}/publish", headers=u["auth"], json={"published": True}).status_code == 403
    # админ публикует
    admin = user_factory("Админ", role=UserRole.admin)
    assert client.get("/admin/reviews/pending", headers=admin["auth"]).status_code == 200
    assert client.post(f"/admin/reviews/{review_id}/publish", headers=admin["auth"], json={"published": True}).status_code == 200
    pub = client.get("/reviews/public").json()
    assert len(pub) == 1 and pub[0]["name"] == "Гульназ" and pub[0]["city"] == "Сибай" and pub[0]["stars"] == 5
    # доступно и под версионным префиксом
    assert client.get("/api/v1/reviews/public").status_code == 200


def test_ads_lifecycle_founder_cap_and_public(client, user_factory):
    admin = user_factory("Админ", role=UserRole.admin)
    pax = user_factory("Юзер")
    # обычный юзер не в админ-рекламу
    assert client.get("/admin/ads", headers=pax["auth"]).status_code == 403
    # создать standard → draft, публично не видно
    r = client.post("/admin/ads", headers=admin["auth"], json={
        "partner_name": "Кафе", "title": "Чай", "text": "Горячий чай по дороге", "plan": "standard", "placements": "route", "erid": "X1"})
    assert r.status_code == 200, r.text
    aid = r.json()["id"]
    assert client.get("/ads").json() == []
    # опубликовать → видно с маркировкой
    assert client.post(f"/admin/ads/{aid}/status", headers=admin["auth"], json={"status": "active"}).status_code == 200
    pub = client.get("/ads").json()
    assert len(pub) == 1 and pub[0]["partner"] == "Кафе" and "route" in pub[0]["placements"]
    # фильтр по месту
    assert client.get("/ads?placement=profile").json() == []
    assert len(client.get("/ads?placement=route").json()) == 1
    # пауза → скрыт
    client.post(f"/admin/ads/{aid}/status", headers=admin["auth"], json={"status": "paused"})
    assert client.get("/ads").json() == []
    # событие + статистика (запись событий требует входа — анти-накрутка)
    assert client.post(f"/ads/{aid}/event", json={"type": "impression"}).status_code == 401  # без токена нельзя
    client.post(f"/ads/{aid}/event", headers=admin["auth"], json={"type": "impression"})
    client.post(f"/ads/{aid}/event", headers=admin["auth"], json={"type": "click"})
    st = client.get("/ads/stats", headers=admin["auth"]).json()
    assert st[str(aid)]["impressions"] == 1 and st[str(aid)]["clicks"] == 1
    # founder лимит 10
    last_founder = None
    for i in range(10):
        rr = client.post("/admin/ads", headers=admin["auth"], json={"partner_name": f"F{i}", "title": "t", "text": "founder partner", "plan": "founder", "erid": "e"})
        assert rr.status_code == 200, rr.text
        last_founder = rr.json()["id"]
    # 11-й founder → 400
    assert client.post("/admin/ads", headers=admin["auth"], json={"partner_name": "F11", "title": "t", "text": "founder partner", "plan": "founder", "erid": "e"}).status_code == 400
    # founder бессрочный (ends_at=null)
    assert client.get(f"/admin/ads", headers=admin["auth"]).json()["founder_used"] == 10
    # удалить (архив) один founder → слот освобождается → 11-й проходит
    client.delete(f"/admin/ads/{last_founder}", headers=admin["auth"])
    assert client.post("/admin/ads", headers=admin["auth"], json={"partner_name": "F11", "title": "t", "text": "founder partner", "plan": "founder", "erid": "e"}).status_code == 200


def test_ads_expiry_hidden(client, user_factory):
    admin = user_factory("Админ2", role=UserRole.admin)
    r = client.post("/admin/ads", headers=admin["auth"], json={
        "partner_name": "СТО", "title": "т", "text": "проверка авто", "plan": "standard", "placements": "ridesList", "erid": "Z",
        "ends_at": "2020-01-01T00:00:00"})
    aid = r.json()["id"]
    client.post(f"/admin/ads/{aid}/status", headers=admin["auth"], json={"status": "active"})
    # active, но срок истёк → в публичной выдаче нет
    assert all(a["id"] != str(aid) for a in client.get("/ads").json())


def test_voice_upload_multipart_and_b64(client, user_factory):
    """Загрузка голоса: новый multipart-путь И старый base64 (обратная совместимость) оба работают."""
    u = user_factory("Voicer")
    # multipart: поле file, ext выводится из имени note.m4a
    r = client.post("/voice", headers=u["auth"], files={"file": ("note.m4a", b"\x00\x01\x02audio-bytes", "audio/mp4")})
    assert r.status_code == 200, r.text
    assert "/voice/" in r.json()["url"] and r.json()["url"].endswith(".m4a")
    # base64-путь (старый установленный клиент) — не сломан
    b = base64.b64encode(b"old-client-audio").decode()
    r2 = client.post("/voice", headers=u["auth"], json={"audio_b64": b, "ext": "m4a"})
    assert r2.status_code == 200, r2.text
    # без входа — 401 (квота/анти-абуз на авторизованного)
    assert client.post("/voice", files={"file": ("x.m4a", b"x", "audio/mp4")}).status_code == 401


def test_boarding_code_participants_only(client, user_factory):
    """Код посадки виден ТОЛЬКО участникам брони (пассажир/водитель), постороннему — 403."""
    drv = user_factory("BcDrv", role=UserRole.driver)
    pax = user_factory("BcPax")
    outsider = user_factory("BcOut")
    rid = _ride(client, drv, seats=2)
    bid = client.post("/bookings", headers=pax["auth"], json={"ride_id": rid, "seats": 1}).json()["id"]
    rp = client.get(f"/bookings/{bid}/boarding-code", headers=pax["auth"])
    assert rp.status_code == 200 and len(rp.json()["code"]) >= 4   # пассажир видит код
    assert client.get(f"/bookings/{bid}/boarding-code", headers=drv["auth"]).status_code == 200  # водитель видит
    assert client.get(f"/bookings/{bid}/boarding-code", headers=outsider["auth"]).status_code == 403  # чужой — нет


@pytest.mark.skipif(
    not os.environ.get("DATABASE_URL", "").startswith("postgres"),
    reason="Гонка брони (FOR UPDATE) проверяется только на Postgres — SQLite игнорирует row-lock. "
           "Запуск: DATABASE_URL=postgresql://... pytest -k overbooking_concurrent",
)
def test_overbooking_concurrent(client, user_factory):
    """Под нагрузкой ровно ОДНА бронь занимает единственное место (FOR UPDATE в book()).
    На SQLite пропускается (row-lock no-op), на Postgres ловит регрессию овербукинга."""
    drv = user_factory("CcDrv", role=UserRole.driver)
    rid = _ride(client, drv, seats=1)
    paxs = [user_factory(f"CcPax{i}") for i in range(8)]
    results: list = []
    lock = threading.Lock()

    def attempt(p):
        code = client.post("/bookings", headers=p["auth"], json={"ride_id": rid, "seats": 1}).status_code
        with lock:
            results.append(code)

    threads = [threading.Thread(target=attempt, args=(p,)) for p in paxs]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    assert results.count(200) == 1, f"ожидалась ровно 1 успешная бронь, получили {results}"
    assert results.count(400) == 7   # остальным мест не хватило
