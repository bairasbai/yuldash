"""Тесты API Юлдаша: ядро + закрытые дыры безопасности (регрессии не пройдут)."""
from app.models import UserRole


def _ride(client, drv, seats=3):
    r = client.post("/rides", headers=drv["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай",
        "depart_at": "2030-01-01T10:00:00", "seats_total": seats, "price": 300,
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


def test_ws_rejects_non_participant(client, user_factory):
    drv = user_factory("WsDrv", role=UserRole.driver)
    rid = _ride(client, drv)
    pax = user_factory("WsPax")
    bid = client.post("/bookings", headers=pax["auth"], json={"ride_id": rid, "seats": 1}).json()["id"]
    outsider = user_factory("WsOut")
    # посторонний к чужому чату по WS → соединение отклоняется
    import pytest
    from starlette.websockets import WebSocketDisconnect
    with pytest.raises((WebSocketDisconnect, Exception)):
        with client.websocket_connect(f"/ws/bookings/{bid}?token={outsider['token']}") as ws:
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
