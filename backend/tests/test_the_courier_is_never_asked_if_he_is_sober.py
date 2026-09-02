# -*- coding: utf-8 -*-
"""У курьера ни разу не спрашивают, здоров ли он и трезв (волна 213).

Перед сменой водитель такси подтверждает три пункта: «я здоров», «машина исправна»,
«я не пил». Проверка есть, экран есть, гейт есть — `guard_pretrip`. Зовут его **из одного
места**: `POST /instant/presence`, то есть с такси-линии.

Курьера не спрашивают никогда. На приёме курьерского заказа выстроена длинная очередь
проверок — одобрение, отдых, живой заказ такси, срок документов, пауза по качеству, долг
комиссии, — и в ней нет ровно одной: готовности к рейсу.

Тот же руль, та же ночная трасса Сибай–Акъяр, тот же встречный свет. Проект это уже решал
дважды и оба раза одинаково: волна 194 распространила на доставку лимит усталости, а правка
от 29.08 — срок документов. Довод был один и тот же: «руль не спрашивает, человек в машине
или коробка». Трезвость осталась последней проверкой, которую спрашивают только у такси.

Проба ловит поведением, а не текстом исходника: курьер, не подтвердивший готовность,
не должен получить заказ.
"""
from __future__ import annotations

import pytest
from sqlmodel import Session

from app import pretrip
from app.config import settings
from app.db import engine
from app.models import UserRole
from conftest import upload_doc


@pytest.fixture(autouse=True)
def _режимы_и_осмотр():
    """Оба режима включены, осмотр перед сменой обязателен.

    По умолчанию `pretrip_check_required` выключен — цифра в руках Александра. Тест
    включает его сам: он проверяет, что ПРИ включённом требовании оно доходит до курьера,
    а не то, включено ли оно сегодня на проде.
    """
    было = (settings.taxi_enabled, settings.courier_enabled, settings.pretrip_check_required)
    settings.taxi_enabled = settings.courier_enabled = True
    settings.pretrip_check_required = True
    yield
    (settings.taxi_enabled, settings.courier_enabled,
     settings.pretrip_check_required) = было


@pytest.fixture(autouse=True)
def _тихо(monkeypatch):
    monkeypatch.setattr("app.services.send_push", lambda *a, **k: None)
    monkeypatch.setattr("app.routers.parcels.notify_admin_telegram", lambda *a, **k: None)
    monkeypatch.setattr("app.routers.courier.notify_admin_telegram", lambda *a, **k: None)


def _курьер_на_линии(client, user_factory, имя="ОсмотрКурьер"):
    админ = user_factory(name="ОсмотрАдмин", role=UserRole.admin)
    c = user_factory(name=имя)
    aid = client.post("/courier/apply", headers=c["auth"],
                      json={"transport": "car",
                            "selfie_url": upload_doc(client, c["auth"])}).json()["id"]
    assert client.post(f"/admin/courier-applications/{aid}/approve",
                       headers=админ["auth"]).status_code == 200
    assert client.post("/courier/online", headers=c["auth"],
                       json={"zone": "region"}).status_code == 200
    return c


def _заказ(client, отправитель, **ov):
    body = {
        "from_city": "Акъяр", "to_city": "Сибай",
        "from_lat": 51.90, "from_lng": 58.20, "to_lat": 52.71, "to_lng": 58.66,
        "size": "small", "description": "Лекарство",
        "receiver_name": "Гөлнара", "receiver_phone": "+79990001177",
        "rules_accepted": True, "delivery_type": "courier", "urgency": "bypath",
    }
    body.update(ov)
    r = client.post("/courier/orders", headers=отправитель["auth"], json=body)
    assert r.status_code == 200, r.text
    return r.json()["id"]


def test_не_подтвердив_трезвость_курьер_получает_заказ(client, user_factory):
    """Главное: человек не сказал «я здоров, машина исправна, не пил» — и уже везёт коробку."""
    курьер = _курьер_на_линии(client, user_factory)
    отправитель = user_factory(name="ОсмотрОтпр")
    pid = _заказ(client, отправитель)

    with Session(engine) as s:
        assert not pretrip.is_confirmed(s, курьер["id"]), "тест слеп: осмотр уже подтверждён"

    взял = client.post(f"/parcels/{pid}/accept", headers=курьер["auth"])

    assert взял.status_code == 403, (
        f"курьер взял заказ (ответ {взял.status_code}), не подтвердив ни здоровье, ни "
        "исправность машины, ни трезвость. На такси-линии этот вопрос обязателен, а на "
        "ночной трассе Сибай–Акъяр с коробкой — не задаётся ни разу"
    )


def test_подтвердил_готовность_и_везёт(client, user_factory):
    """Обратная сторона: подтвердил три пункта — работай.

    Без этого «починкой» сошёл бы глухой отказ всем курьерам подряд.
    """
    курьер = _курьер_на_линии(client, user_factory, имя="ОсмотрКурьер2")
    отправитель = user_factory(name="ОсмотрОтпр2")
    pid = _заказ(client, отправитель)

    with Session(engine) as s:
        pretrip.confirm(s, курьер["id"], health_ok=True, car_ok=True, no_alcohol=True)

    взял = client.post(f"/parcels/{pid}/accept", headers=курьер["auth"])

    assert взял.status_code == 200, (
        f"курьер подтвердил готовность, а заказ всё равно не дали ({взял.text})"
    )


def test_без_требования_осмотра_курьера_не_трогаем(client, user_factory, monkeypatch):
    """Обратная сторона: выключенное требование остаётся выключенным для обоих режимов.

    Цифра в руках Александра — починка не должна включать проверку явочным порядком.
    """
    monkeypatch.setattr(settings, "pretrip_check_required", False)
    курьер = _курьер_на_линии(client, user_factory, имя="ОсмотрКурьер3")
    отправитель = user_factory(name="ОсмотрОтпр3")
    pid = _заказ(client, отправитель)

    взял = client.post(f"/parcels/{pid}/accept", headers=курьер["auth"])

    assert взял.status_code == 200, (
        f"осмотр выключен, а курьера всё равно не пустили ({взял.text})"
    )
