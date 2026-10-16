"""Выключатель посылок закрывает и «заявки»: посылку не протащить через «ищу попутку».

Приёмка волны 1 (16.10.2026). Замок посылок стоял на публикации поездки и на брони
(`guard_delivery_ride` в rides.py и bookings.py). Но у заявки «ищу попутку» тоже есть
категория — «посылка» и «груз», — а приём отклика сам создаёт поездку этой категории с
подтверждённой бронью. При выключенных посылках дверь оставалась открытой: пассажир просил
отвезти посылку, водитель откликался, пассажир принимал — и посылка ехала мимо выключателя.

Правило то же, что у остальных дверей: закрыты только НОВЫЕ (создать, откликнуться, принять)
с двуязычным «скоро». Обычные заявки на попутку работают как раньше.
"""
from __future__ import annotations

from datetime import timedelta

import pytest
from sqlmodel import Session

from app.config import settings
from app.db import engine
from app.models import RideCategory, RideRequest, UserRole
from app.timeutil import utcnow


@pytest.fixture
def посылки_выключены(monkeypatch):
    monkeypatch.setattr(settings, "parcels_enabled", False)


def _request_body(category: str) -> dict:
    when = (utcnow() + timedelta(days=1)).replace(microsecond=0).isoformat()
    return {"from_city": "Баймак", "to_city": "Сибай", "desired_at": when, "seats": 1,
            "category": category}


@pytest.mark.parametrize("category", ["parcel", "cargo"])
def test_заявку_на_посылку_не_создать(client, user_factory, посылки_выключены, category):
    pax = user_factory(f"ReqGate{category}")
    r = client.post("/requests", headers=pax["auth"], json=_request_body(category))
    assert r.status_code == 403, f"заявка «{category}» создалась при выключенных посылках: {r.text}"


def test_обычная_заявка_на_попутку_работает(client, user_factory, посылки_выключены):
    pax = user_factory("ReqGateRegular")
    r = client.post("/requests", headers=pax["auth"], json=_request_body("regular"))
    assert r.status_code == 200, f"выключатель посылок закрыл обычную попутку: {r.text}"


def _old_parcel_request(passenger_id: int) -> int:
    """Заявка на посылку, созданная ДО выключения (или в обход) — живёт в базе."""
    with Session(engine) as s:
        req = RideRequest(passenger_id=passenger_id, from_city="Баймак", to_city="Сибай",
                          desired_at=utcnow() + timedelta(days=1), seats=1,
                          category=RideCategory.parcel, status="active")
        s.add(req)
        s.commit()
        s.refresh(req)
        return req.id


def test_на_старую_заявку_посылки_не_откликнуться(client, user_factory, посылки_выключены):
    pax = user_factory("ReqGateOldPax")
    drv = user_factory("ReqGateOldDrv", role=UserRole.driver)
    rid = _old_parcel_request(pax["id"])
    r = client.post(f"/requests/{rid}/respond", headers=drv["auth"], json={"price": 300})
    assert r.status_code == 403, f"водитель откликнулся на посылку при выключенных посылках: {r.text}"


def test_старый_отклик_на_посылку_не_принять(client, user_factory, monkeypatch):
    """Отклик успел прийти, пока посылки были включены; выключили — принять уже нельзя."""
    pax = user_factory("ReqGateAccPax")
    drv = user_factory("ReqGateAccDrv", role=UserRole.driver)
    rid = _old_parcel_request(pax["id"])
    r = client.post(f"/requests/{rid}/respond", headers=drv["auth"], json={"price": 300})
    assert r.status_code == 200, r.text
    resp_id = r.json()["id"]

    monkeypatch.setattr(settings, "parcels_enabled", False)
    r = client.post(f"/responses/{resp_id}/accept", headers=pax["auth"])
    assert r.status_code == 403, f"посылка поехала мимо выключателя через приём отклика: {r.text}"


def test_старые_заявки_посылок_не_висят_в_ленте_водителя(client, user_factory, посылки_выключены):
    """Откликнуться на них уже нельзя (403) — значит, и показывать их водителю незачем.

    Приёмка волны 1, P2: водитель видел в ленте «посылку», жал «Откликнуться» и получал «скоро».
    Обычная заявка рядом с ней — на месте.
    """
    pax = user_factory("ReqFeedPax")
    drv = user_factory("ReqFeedDrv", role=UserRole.driver)
    посылка = _old_parcel_request(pax["id"])
    обычная = client.post("/requests", headers=pax["auth"], json=_request_body("regular")).json()["id"]

    лента = {r["id"] for r in client.get("/requests/feed", headers=drv["auth"]).json()}
    рядом = client.get("/requests/near", headers=drv["auth"]).json()
    рядом_ids = {r["id"] for r in (рядом if isinstance(рядом, list) else рядом.get("items", []))}

    assert посылка not in лента and посылка not in рядом_ids, "в ленте висит посылка, на которую нельзя откликнуться"
    assert обычная in лента and обычная in рядом_ids, "выключатель посылок спрятал обычную заявку"
