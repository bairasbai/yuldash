# -*- coding: utf-8 -*-
"""Новый курьер не получает переписку прежнего.

Аудит 2026-08-07. Доступ к чату доставки давался по ТЕКУЩЕМУ курьеру, а сообщения
выбирались по всей заявке, без отсечки. Значит человек, взявший заявку сегодня, одним
запросом читал всё, что отправитель писал прошлому курьеру: где лежит ключ, что получатель —
пожилая женщина и живёт одна, во сколько её лучше застать. Это гораздо больше, чем мы вообще
собирались отдавать курьеру: в самой заявке таких полей нет.

Правило: курьер видит переписку СВОЕЙ смены — с момента, как он принял доставку.
У отправителя история остаётся полной: по ней он разбирает спор и помнит, кому что говорил.
"""
import pytest
from sqlmodel import Session

from app import models as M
from app.config import settings
from app.db import engine
from app.models import UserRole
from conftest import upload_doc


@pytest.fixture(autouse=True)
def _courier_on():
    prev = settings.courier_enabled
    settings.courier_enabled = True
    yield
    settings.courier_enabled = prev


@pytest.fixture(autouse=True)
def _quiet(monkeypatch):
    monkeypatch.setattr("app.services.send_push", lambda *a, **k: None)
    monkeypatch.setattr("app.routers.chat.send_push", lambda *a, **k: None)
    monkeypatch.setattr("app.routers.parcels.notify_admin_telegram", lambda *a, **k: None)
    monkeypatch.setattr("app.routers.courier.notify_admin_telegram", lambda *a, **k: None)


def _make_courier(client, user_factory, name="ЧатКурьер"):
    admin = user_factory(name="ЧатАдмин", role=UserRole.admin)
    c = user_factory(name=name)
    aid = client.post("/courier/apply", headers=c["auth"],
                      json={"transport": "car", "selfie_url": upload_doc(client, c["auth"])}).json()["id"]
    assert client.post(f"/admin/courier-applications/{aid}/approve",
                       headers=admin["auth"]).status_code == 200
    assert client.post("/courier/online", headers=c["auth"],
                       json={"zone": "region"}).status_code == 200
    return c


def _order(client, sender, **ov):
    body = {
        "from_city": "Акъяр", "to_city": "Сибай",
        "from_lat": 51.90, "from_lng": 58.20, "to_lat": 52.71, "to_lng": 58.66,
        "size": "small", "description": "Лекарство",
        "receiver_name": "Гөлнара", "receiver_phone": "+79990009933",
        "rules_accepted": True, "delivery_type": "courier", "urgency": "bypath",
    }
    body.update(ov)
    r = client.post("/courier/orders", headers=sender["auth"], json=body)
    assert r.status_code == 200, r.text
    return r.json()


def _say(client, who, pid, text):
    r = client.post(f"/parcels/{pid}/messages", headers=who["auth"], json={"text": text})
    assert r.status_code == 200, r.text
    return r.json()


def _texts(client, who, pid):
    r = client.get(f"/parcels/{pid}/messages", headers=who["auth"])
    assert r.status_code == 200, r.text
    return [m["text"] for m in r.json()]


SECRET = "Ключ под ковриком, дом 5 кв 3, соседка Гульнара"


def test_new_courier_does_not_read_the_old_conversation(client, user_factory):
    """Курьер №1 снялся, заявку взял курьер №2 — старая переписка ему не видна."""
    first = _make_courier(client, user_factory, name="ЧатКурьер1")
    sender = user_factory(name="ЧатОтпр1")
    pid = _order(client, sender)["id"]
    assert client.post(f"/parcels/{pid}/accept", headers=first["auth"]).status_code == 200
    _say(client, sender, pid, SECRET)
    _say(client, first, pid, "Понял, заеду после шести")
    assert client.post(f"/parcels/{pid}/release", headers=first["auth"],
                       json={"reason": "заболел"}).status_code == 200

    second = _make_courier(client, user_factory, name="ЧатКурьер2")
    assert client.post(f"/parcels/{pid}/accept", headers=second["auth"]).status_code == 200
    seen = _texts(client, second, pid)
    assert SECRET not in seen, "новый курьер прочитал переписку с прежним"
    assert seen == [], f"новому курьеру видна чужая смена: {seen}"

    # Своя смена видна полностью — иначе чат для него бесполезен.
    _say(client, sender, pid, "Заберите в обед, пожалуйста")
    assert _texts(client, second, pid) == ["Заберите в обед, пожалуйста"]

    # У отправителя история целая: он помнит, кому что говорил, и это его аргумент в споре.
    mine = _texts(client, sender, pid)
    assert SECRET in mine and "Заберите в обед, пожалуйста" in mine


def test_courier_after_admin_release_also_starts_clean(client, user_factory):
    """Тот же результат, когда курьера снял админ (пропал, трубку не берёт)."""
    admin = user_factory(name="ЧатАдмин2", role=UserRole.admin)
    first = _make_courier(client, user_factory, name="ЧатКурьер3")
    sender = user_factory(name="ЧатОтпр2")
    pid = _order(client, sender)["id"]
    client.post(f"/parcels/{pid}/accept", headers=first["auth"])
    _say(client, sender, pid, SECRET)
    assert client.post(f"/admin/parcels/{pid}/release-courier", headers=admin["auth"],
                       json={"reason": "курьер не отвечает"}).status_code == 200

    second = _make_courier(client, user_factory, name="ЧатКурьер4")
    assert client.post(f"/parcels/{pid}/accept", headers=second["auth"]).status_code == 200
    assert SECRET not in _texts(client, second, pid)


def test_single_courier_sees_the_whole_delivery(client, user_factory):
    """Обычный случай (курьер один) не пострадал: он видит всю переписку по доставке,
    в том числе после вручения — чат остаётся на чтение."""
    courier = _make_courier(client, user_factory, name="ЧатКурьер5")
    sender = user_factory(name="ЧатОтпр3")
    order = _order(client, sender)
    pid, code = order["id"], order["confirm_code"]
    client.post(f"/parcels/{pid}/accept", headers=courier["auth"])
    _say(client, sender, pid, "Домофон не работает, позвоните")
    _say(client, courier, pid, "Хорошо")
    client.post(f"/parcels/{pid}/status", headers=courier["auth"], json={"status": "in_transit"})
    _say(client, courier, pid, "Подъезжаю")
    assert client.post(f"/parcels/{pid}/status", headers=courier["auth"],
                       json={"status": "delivered", "code": code}).status_code == 200
    assert _texts(client, courier, pid) == ["Домофон не работает, позвоните", "Хорошо", "Подъезжаю"]


def test_outsider_still_has_no_access(client, user_factory):
    """Посторонний в чат доставки не попадает (проверка на месте, её не сдвинули)."""
    courier = _make_courier(client, user_factory, name="ЧатКурьер6")
    sender = user_factory(name="ЧатОтпр4")
    stranger = user_factory(name="ЧатЧужой")
    pid = _order(client, sender)["id"]
    client.post(f"/parcels/{pid}/accept", headers=courier["auth"])
    _say(client, sender, pid, "секрет")
    assert client.get(f"/parcels/{pid}/messages", headers=stranger["auth"]).status_code == 403
    with Session(engine) as s:                      # заявка цела, дело не в удалении
        assert s.get(M.ParcelDelivery, pid) is not None
