# -*- coding: utf-8 -*-
"""Аналитика обещает «без личности» — проверяем, что так и есть.

Аудит 2026-08-12, волна 45. У таблицы событий в описании написано прямо: «БЕЗ ЛИЧНОСТИ:
ни user_id, ни телефона, ни имени, ни точных координат». Фильтр вырезал ключи с опасными
ИМЕНАМИ (`phone`, `address`, `lat`) — и этого мало: личное приезжает в ЗНАЧЕНИИ под невинным
именем.

Проба показала, что в базу дословно легло:

    {"screen": "заказ для +7 999 123-45-67", "comment": "марат@example.com"}

Ручка открыта без входа (веб шлёт события до логина), то есть положить туда можно что угодно.
Теперь значения проходят тот же скраб, что чистит логи и Sentry: телефон, почта, токен,
координаты маскируются. Плюс строка режется коротко — аналитика это ярлыки экранов,
а не свободный текст.
"""
import json

from sqlmodel import Session, select

from app.db import engine
from app.models import AnalyticsEvent
from app.routers.events import _MAX_STR, sanitize_props


def _last_context(client, **body) -> dict:
    payload = {"event": "probe_event", "client_id": "test-analytics", **body}
    assert client.post("/events", json=payload).status_code == 204
    with Session(engine) as s:
        row = s.exec(select(AnalyticsEvent).order_by(AnalyticsEvent.id.desc())).first()
    return json.loads(row.context_json or "{}")


def test_телефон_в_значении_не_доезжает_до_базы(client):
    """Ключ невинный («экран»), а внутри телефон — маскируем."""
    ctx = _last_context(client, screen="заказ для +7 999 123-45-67")
    assert "999" not in json.dumps(ctx, ensure_ascii=False), ctx
    assert "<телефон>" in json.dumps(ctx, ensure_ascii=False), ctx


def test_почта_в_значении_не_доезжает_до_базы(client):
    """Почта — тоже личное, и пишут её обычно в свободном поле."""
    ctx = _last_context(client, comment="марат@example.com")
    assert "@example.com" not in json.dumps(ctx, ensure_ascii=False), ctx


def test_опасные_ключи_вырезаются_как_раньше(client):
    """Регресс на прежнюю защиту: ключ с опасным именем не сохраняется вовсе."""
    ctx = _last_context(client, phone="+79991234567", address="Баймак, Ленина 1")
    assert "phone" not in ctx and "address" not in ctx, ctx


def test_длинный_текст_в_аналитику_не_помещается():
    """Свободный текст в телеметрии — почти всегда чужая жизнь. Режем коротко."""
    props = sanitize_props({"screen": "я" * 500})
    assert len(props["screen"]) <= _MAX_STR


def test_обычная_телеметрия_работает(client):
    """Защита не смеет ломать метрики: ярлык экрана и язык доезжают как есть."""
    ctx = _last_context(client, screen="map", lang="ba")
    assert ctx.get("screen") == "map" and ctx.get("lang") == "ba", ctx
