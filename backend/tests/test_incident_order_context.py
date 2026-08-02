# -*- coding: utf-8 -*-
"""Спор по ТАКСИ-заказу: контекст order_id в публичной ручке /incidents.

`create_incident` умел привязывать спор к такси-заказу с прошлого раунда, но публичная ручка
поле не принимала — единственным контекстом оставалась бронь попутки. То есть пожаловаться
на поездку в такси через «Справедливость» было технически НЕЛЬЗЯ: та же дыра, что была
у посылок (аудит 2026-07-26, «дверь есть, ключ есть, замка нет»).

Здесь же — анти-харассмент: привязаться к ЧУЖОЙ поездке нельзя, обвинить постороннего нельзя.
"""
import pytest
from sqlmodel import Session

from app import models as M
from app.db import engine
from app.models import InstantOrderStatus as S, UserRole
from app.timeutil import utcnow


@pytest.fixture(autouse=True)
def _quiet(monkeypatch):
    monkeypatch.setattr("app.routers.incidents.send_push", lambda *a, **k: None)
    monkeypatch.setattr("app.routers.incidents.notify_admin_telegram", lambda *a, **k: None)


def _order(passenger_id: int, driver_id: int) -> int:
    with Session(engine) as s:
        o = M.InstantOrder(
            passenger_id=passenger_id, driver_id=driver_id, status=S.done,
            from_lat=52.59, from_lng=58.31, to_lat=52.60, to_lng=58.32,
            from_text="Баймак", to_text="Сибай",
            price_estimate=300, price_final=300, done_at=utcnow(),
        )
        s.add(o); s.commit(); s.refresh(o)
        return o.id


def test_passenger_can_open_dispute_over_taxi_order(client, user_factory):
    pax = user_factory("Юлаусы")
    drv = user_factory("Водитель", role=UserRole.driver)
    oid = _order(pax["id"], drv["id"])
    r = client.post("/incidents", headers=pax["auth"], json={
        "respondent_id": drv["id"], "type": "rude",
        "description": "Нагрубил на весь салон", "order_id": oid,
    })
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["my_role"] == "reporter"
    assert body["reporter_role"] == "passenger"     # роль в поездке определена по заказу
    assert body["status"] == "awaiting_response"    # вторую сторону зовём объясниться
    assert "Баймак" in (body["booking_route"] or "")


def test_driver_can_open_dispute_over_taxi_order(client, user_factory):
    pax = user_factory("Юлаусы2")
    drv = user_factory("Водитель2", role=UserRole.driver)
    oid = _order(pax["id"], drv["id"])
    r = client.post("/incidents", headers=drv["auth"], json={
        "respondent_id": pax["id"], "type": "passenger_no_show",
        "description": "Ждал 15 минут, не вышел", "order_id": oid,
    })
    assert r.status_code == 200, r.text
    assert r.json()["reporter_role"] == "driver"


def test_stranger_cannot_attach_to_someone_elses_order(client, user_factory):
    """Анти-харассмент: чужая поездка — не повод для спора."""
    pax = user_factory("Юлаусы3")
    drv = user_factory("Водитель3", role=UserRole.driver)
    stranger = user_factory("Посторонний")
    oid = _order(pax["id"], drv["id"])
    r = client.post("/incidents", headers=stranger["auth"], json={
        "respondent_id": drv["id"], "type": "rude", "description": "…", "order_id": oid,
    })
    assert r.status_code == 403


def test_cannot_accuse_someone_outside_the_order(client, user_factory):
    pax = user_factory("Юлаусы4")
    drv = user_factory("Водитель4", role=UserRole.driver)
    outsider = user_factory("Не при делах")
    oid = _order(pax["id"], drv["id"])
    r = client.post("/incidents", headers=pax["auth"], json={
        "respondent_id": outsider["id"], "type": "rude", "description": "…", "order_id": oid,
    })
    assert r.status_code == 400


def test_dispute_without_any_context_still_rejected(client, user_factory):
    """Обычная жалоба обязана быть привязана к общей поездке/доставке — иначе можно
    завалить спорами любого, с кем не пересекался."""
    a = user_factory("A")
    b = user_factory("B")
    r = client.post("/incidents", headers=a["auth"], json={
        "respondent_id": b["id"], "type": "rude", "description": "…",
    })
    assert r.status_code == 400


def test_order_dispute_appears_in_my_incidents(client, user_factory):
    pax = user_factory("Юлаусы5")
    drv = user_factory("Водитель5", role=UserRole.driver)
    oid = _order(pax["id"], drv["id"])
    created = client.post("/incidents", headers=pax["auth"], json={
        "respondent_id": drv["id"], "type": "overcharge",
        "description": "Взял больше", "order_id": oid,
    }).json()
    mine = client.get("/incidents/mine", headers=pax["auth"]).json()
    assert any(i["id"] == created["id"] for i in mine)
    # Вторая сторона тоже видит спор — иначе «право объясниться» недостижимо.
    theirs = client.get("/incidents/mine", headers=drv["auth"]).json()
    assert any(i["id"] == created["id"] and i["my_role"] == "respondent" for i in theirs)
