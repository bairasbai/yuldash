"""Заявка человека на паузе не должна звать водителя впустую.

История. На Айгуль пожаловались, идёт разбор — ей закрыт приём откликов (§2, волна 9).
Заявка «Баймак → Сибай» при этом продолжала висеть в витрине «заявки рядом» как живая.
Водитель Тимур её видел, откликался, предлагал цену — и не получал ответа: принять отклик
Айгуль не может, пока идёт разбор. Тимур считал, что человек передумал или игнорирует.

Лента заявок для водителя (`/requests/feed`) это понимала и такие заявки прятала. Соседняя
витрина «заявки рядом» (`/requests/near`) — нет: там стояла только блокировка (аудит
2026-08-08, волна 72). Две двери к одним и тем же данным, разные наборы правил.

Обратная сторона обязательна: честная заявка должна остаться видна в обеих витринах, иначе
«починка» превращается в пустую ленту. И свою заявку автор видит всегда — иначе решит,
что она пропала, создаст новую и упрётся в потолок открытых заявок.
"""
from __future__ import annotations

from datetime import timedelta

from app.db import get_session
from app.models import SafetyProfile, UserRole
from app.timeutil import utcnow


def _request(client, who) -> int:
    r = client.post("/requests", headers=who["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай",
        "depart_at": "2030-01-01T10:00:00", "seats": 1,
    })
    assert r.status_code == 200, r.text
    return r.json()["id"]


def _pause(user_id: int, days: int = 7):
    s = next(get_session())
    s.add(SafetyProfile(user_id=user_id, suspended_until=utcnow() + timedelta(days=days)))
    s.commit()


def _ids(client, who, door: str) -> list[int]:
    r = client.get(door, headers=who["auth"])
    assert r.status_code == 200, r.text
    body = r.json()
    items = body if isinstance(body, list) else body.get("items", [])
    return [x["id"] for x in items]


def test_обе_витрины_заявок_знают_про_паузу(client, user_factory):
    aygul = user_factory("ЗаявкаАйгуль")
    honest = user_factory("ЗаявкаЗулейха")
    timur = user_factory("ЗаявкаТимур", role=UserRole.driver)

    paused_req = _request(client, aygul)
    honest_req = _request(client, honest)
    _pause(aygul["id"])

    for door in ("/requests/near", "/requests/feed"):
        ids = _ids(client, timur, door)
        assert paused_req not in ids, f"{door}: водителя зовут к человеку, который не может ответить"
        assert honest_req in ids, f"{door}: витрина опустела — обычная заявка тоже пропала"


def test_свою_заявку_автор_видит_и_на_паузе(client, user_factory):
    """Иначе человек решит, что заявка потерялась, и создаст новую — а потолок открытых
    заявок у него общий, и он упрётся в него на ровном месте."""
    aygul = user_factory("СвояЗаявкаАйгуль")
    req_id = _request(client, aygul)
    _pause(aygul["id"])

    assert req_id in _ids(client, aygul, "/requests/near"), "своя заявка пропала у автора"
