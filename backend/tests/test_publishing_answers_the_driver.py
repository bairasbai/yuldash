"""Публикация поездки должна возвращать поездку, а не пустоту.

История. Ильдар публикует рейс «Баймак → Сибай». Сервер поездку создаёт, но в ответ
приложению уходит пустой JSON — ни номера поездки, ни времени, ни цены. Для водителя это
выглядит как сбой: экран не открывается, поездки «нет». Он жмёт «Опубликовать» ещё раз,
потом ещё. На сервере рейс всё это время есть.

Ловилось это не всегда, и потому дожило до аудита (2026-08-08, волна 78): пустой ответ
приходит ТОЛЬКО если кто-то подписан на это направление через «карауль маршрут». Есть
подписчик — рассылка пишет уведомления и сохраняет их, а после сохранения объект поездки
в памяти обесценивается, и сериализовать в ответ уже нечего. Нет подписчиков — рассылка
молчит, ничего не сохраняет, и ответ нормальный. На пустой базе и в старых тестах
подписчиков не было ни одного.

Чем ближе к людям, тем неприятнее: подписки заводят как раз на живых направлениях.
Чем популярнее маршрут, тем вероятнее, что публикация на нём выглядит сломанной.
"""
from __future__ import annotations

import pytest

from app.models import UserRole


@pytest.fixture
def quiet_push(monkeypatch):
    """Пуши не шлём — проверяем ответ водителю, а не доставку уведомлений."""
    import app.services as svc
    monkeypatch.setattr(svc, "send_push", lambda *a, **kw: None)


def _watch(client, who, kind: str):
    r = client.post("/route-watch", headers=who["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай", "direction": "forward", "watch_kind": kind,
    })
    assert r.status_code == 200, r.text


def test_ответ_на_публикацию_не_пустой_когда_маршрут_караулят(client, user_factory, quiet_push):
    _watch(client, user_factory("СторожМаршрута"), "rides")
    ildar = user_factory("ПубликуетИльдар", role=UserRole.driver)

    r = client.post("/rides", headers=ildar["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай", "depart_at": "2030-03-01T10:00:00",
        "seats_total": 3, "price": 300,
    })

    assert r.status_code == 200, r.text
    body = r.json()
    assert body.get("id"), f"водитель опубликовал рейс и получил пустой ответ: {body}"
    assert body.get("from_city") == "Баймак" and body.get("price") == 300, body


def test_ответ_на_заявку_не_пустой_когда_маршрут_караулят(client, user_factory, quiet_push):
    """Зеркальный случай: заявка пассажира тоже будит подписчиков и тоже обязана вернуться целой."""
    _watch(client, user_factory("СторожЗаявок", role=UserRole.driver), "requests")
    aygul = user_factory("ОставляетАйгуль")

    r = client.post("/requests", headers=aygul["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай", "depart_at": "2030-03-01T10:00:00", "seats": 1,
    })

    assert r.status_code == 200, r.text
    body = r.json()
    assert body.get("id"), f"человек оставил заявку и получил пустой ответ: {body}"
    assert body.get("from_city") == "Баймак", body
