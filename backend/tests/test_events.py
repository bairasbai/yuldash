"""Анонимная продуктовая аналитика: приём /events (204, вырезание чувствительных ключей,
битый payload не падает) + /admin/events/summary (только админ, счётчики по event)."""
import json

from sqlmodel import Session, select

from app.db import engine
from app.models import AnalyticsEvent, UserRole
from app.routers.events import sanitize_props


def _last_event(event_name: str) -> AnalyticsEvent:
    with Session(engine) as s:
        return s.exec(
            select(AnalyticsEvent).where(AnalyticsEvent.event == event_name)
            .order_by(AnalyticsEvent.id.desc())
        ).first()


def test_ingest_returns_204_and_stores(client):
    r = client.post("/events", json={
        "event": "web_open", "client_id": "cid-abc", "ts": 1700000000000,
        "lang": "ba", "role": "passenger", "standalone": True,
    })
    assert r.status_code == 204
    assert r.content == b""
    row = _last_event("web_open")
    assert row is not None
    assert row.client_id == "cid-abc"
    assert row.ts == 1700000000000
    props = json.loads(row.context_json)
    # безопасные props сохранены
    assert props["lang"] == "ba"
    assert props["role"] == "passenger"
    assert props["standalone"] is True
    # служебные ключи не продублированы в context
    assert "event" not in props and "client_id" not in props and "ts" not in props


def test_ingest_strips_sensitive_keys(client):
    r = client.post("/events", json={
        "event": "sensitive_probe", "client_id": "cid",
        "phone": "+79990001122", "user_name": "Айгуль", "lat": 54.7, "lng": 55.9,
        "access_token": "secret123", "email": "a@b.ru", "home_address": "ул. Ленина 1",
        "otp": "0000", "session_id": "s1", "user_id": 42,
        "screen": "role_pick", "step": 3,   # безопасные — остаются
    })
    assert r.status_code == 204
    row = _last_event("sensitive_probe")
    props = json.loads(row.context_json)
    # ничего чувствительного не просочилось
    for bad in ("phone", "user_name", "lat", "lng", "access_token", "email",
                "home_address", "otp", "session_id", "user_id"):
        assert bad not in props, f"чувствительный ключ {bad} не вырезан"
    # безопасные — на месте
    assert props["screen"] == "role_pick"
    assert props["step"] == 3


def test_sanitize_truncates_long_string_and_drops_nested():
    props = sanitize_props({
        "event": "x", "long": "a" * 500, "nested": {"secret": 1}, "list": [1, 2, 3],
        "flag": False, "n": 7,
    })
    assert len(props["long"]) == 128          # строка обрезана до 128
    assert "nested" not in props and "list" not in props  # вложенное отброшено
    assert props["flag"] is False and props["n"] == 7


def test_broken_payload_does_not_crash(client):
    # не-JSON тело
    r = client.post("/events", data="not-json-at-all",
                    headers={"Content-Type": "application/json"})
    assert r.status_code == 204
    # JSON-массив вместо объекта
    assert client.post("/events", json=[1, 2, 3]).status_code == 204
    # без event — молча пропускаем
    assert client.post("/events", json={"client_id": "c"}).status_code == 204


def test_summary_admin_only(client, user_factory):
    # копим пару событий
    client.post("/events", json={"event": "funnel_a", "client_id": "c1"})
    client.post("/events", json={"event": "funnel_a", "client_id": "c2"})
    client.post("/events", json={"event": "funnel_b", "client_id": "c3"})

    pax = user_factory("SumPax")
    r = client.get("/admin/events/summary?days=7", headers=pax["auth"])
    assert r.status_code == 403          # обычному пользователю нельзя

    admin = user_factory("Boss", role=UserRole.admin)
    r = client.get("/admin/events/summary?days=7", headers=admin["auth"])
    assert r.status_code == 200, r.text
    body = r.json()
    counts = {e["event"]: e["count"] for e in body["events"]}
    assert counts.get("funnel_a", 0) >= 2
    assert counts.get("funnel_b", 0) >= 1
    assert body["total"] >= 3
    assert body["days"] == 7


def test_summary_requires_auth(client):
    # без токена — не 200 (защищённый маршрут)
    assert client.get("/admin/events/summary").status_code in (401, 403)
