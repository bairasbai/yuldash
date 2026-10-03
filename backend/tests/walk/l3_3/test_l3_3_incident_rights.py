# -*- coding: utf-8 -*-
"""Права на карточку спора: видят только стороны и админ (БРИФ: «человек видит только свои
обращения»). Отдельного именованного теста на GET /incidents/{id} для постороннего в наборе
не нашлось — другие тесты проверяют соседние ручки (respond/withdraw), эта проверяет саму
точку чтения."""
from __future__ import annotations

from app.models import UserRole

from test_api import _ride


def _чужой_спор(client, user_factory):
    водитель = user_factory("ПраваВодитель")
    пассажирка = user_factory("ПраваПассажирка")
    посторонний = user_factory("ПраваПосторонний")
    ride_id = _ride(client, водитель, comment="права на карточку")
    bid = client.post("/bookings", headers=пассажирка["auth"],
                      json={"ride_id": ride_id, "seats": 1}).json()["id"]
    client.post(f"/bookings/{bid}/confirm", headers=водитель["auth"])
    iid = client.post("/incidents", headers=пассажирка["auth"], json={
        "respondent_id": водитель["id"], "type": "rude", "booking_id": bid,
        "description": "грубил",
    }).json()["id"]
    return водитель, пассажирка, посторонний, iid


def test_посторонний_не_видит_чужой_спор(client, user_factory):
    _, _, посторонний, iid = _чужой_спор(client, user_factory)
    r = client.get(f"/incidents/{iid}", headers=посторонний["auth"])
    assert r.status_code == 403, (
        f"посторонний человек получил доступ к чужому спору: {r.status_code} {r.text[:150]}"
    )


def test_стороны_спора_видят_карточку(client, user_factory):
    водитель, пассажирка, _, iid = _чужой_спор(client, user_factory)
    assert client.get(f"/incidents/{iid}", headers=пассажирка["auth"]).status_code == 200
    assert client.get(f"/incidents/{iid}", headers=водитель["auth"]).status_code == 200


def test_админ_видит_любой_спор(client, user_factory):
    _, _, _, iid = _чужой_спор(client, user_factory)
    админ = user_factory("ПраваАдмин", role=UserRole.admin)
    assert client.get(f"/incidents/{iid}", headers=админ["auth"]).status_code == 200


def test_разбор_доступен_только_админу(client, user_factory):
    """`_require_admin` — ворота на ВСЕ /admin/incidents/* ручки: не-админ не решает чужие
    споры, даже зная id (сама роль REST, а не просто маскировка в UI)."""
    водитель, пассажирка, _, iid = _чужой_спор(client, user_factory)
    r = client.post(f"/admin/incidents/{iid}/resolve", headers=пассажирка["auth"],
                    json={"resolution": "strike", "fault": "respondent"})
    assert r.status_code == 403, f"не-админ смог решить чужой спор: {r.status_code} {r.text[:150]}"
