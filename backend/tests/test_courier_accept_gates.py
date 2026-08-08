# -*- coding: utf-8 -*-
"""Гейты на входе в курьерский заказ: пауза по качеству и проверка открытого текста.

Аудит 2026-08-07.

1. Мягкая пауза по качеству (рейтинг просел → пара дней отдыха) стояла на «выйти на линию»
   и на «показать ленту заказов», но НЕ на самом приёме заказа. Витрина закрыта, дверь
   открыта: id заказа курьер уже видел в пуше или на открытом экране, и «взять» проходило.
   Пауза оставалась декорацией ровно для того, кто её заслужил.

2. Описание заказа курьера (2000 знаков) и список покупок в «купи и привези» уходили в
   ленту без единой проверки, хотя точно такое же поле у посылки «по пути» проверяется
   с 2026-08-06. Именно в платном режиме есть мотив написать «звони на другой номер,
   договоримся мимо приложения»: там комиссия, SOS, чек и разбор спора — всё, что теряется
   при уводе сделки. Проверка НЕ режет текст и НЕ роняет заказ, она только помечает.
"""
from datetime import timedelta

import pytest
from sqlmodel import Session

import app.antifraud as af
import app.routers.courier as courier_router
from app import models as M
from app.config import settings
from app.db import engine
from app.models import UserRole
from app.timeutil import utcnow
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
    monkeypatch.setattr("app.routers.parcels.notify_admin_telegram", lambda *a, **k: None)
    monkeypatch.setattr("app.routers.courier.notify_admin_telegram", lambda *a, **k: None)


def _make_courier(client, user_factory, name="ГейтКурьер"):
    admin = user_factory(name="ГейтАдмин", role=UserRole.admin)
    c = user_factory(name=name)
    aid = client.post("/courier/apply", headers=c["auth"],
                      json={"transport": "car", "selfie_url": upload_doc(client, c["auth"])}).json()["id"]
    assert client.post(f"/admin/courier-applications/{aid}/approve",
                       headers=admin["auth"]).status_code == 200
    assert client.post("/courier/online", headers=c["auth"],
                       json={"zone": "region"}).status_code == 200
    return c


def _pause(courier_id: int, days: int = 2):
    """Мягкая пауза, как её ставит лестница качества при просевшем рейтинге."""
    with Session(engine) as s:
        prof = s.exec(
            M.CourierProfile.__table__.select().where(M.CourierProfile.user_id == courier_id)
        ).first()
        assert prof is not None, "профиль курьера должен существовать после выхода на линию"
        p = s.get(M.CourierProfile, prof.id)
        p.paused_until = utcnow() + timedelta(days=days)
        p.online = False
        s.add(p)
        s.commit()


def _order(client, sender, **ov):
    body = {
        "from_city": "Акъяр", "to_city": "Сибай",
        "from_lat": 51.90, "from_lng": 58.20, "to_lat": 52.71, "to_lng": 58.66,
        "size": "small", "description": "Лекарство",
        "receiver_name": "Гөлнара", "receiver_phone": "+79990001177",
        "rules_accepted": True, "delivery_type": "courier", "urgency": "bypath",
    }
    body.update(ov)
    return client.post("/courier/orders", headers=sender["auth"], json=body)


# ============== 1. Пауза по качеству ==============
def test_paused_courier_cannot_take_an_order(client, user_factory):
    """Пауза закрывает не только витрину, но и дверь."""
    courier = _make_courier(client, user_factory)
    sender = user_factory(name="ГейтОтпр1")
    ro = _order(client, sender)
    assert ro.status_code == 200, ro.text
    pid = ro.json()["id"]
    _pause(courier["id"])

    # Витрина закрыта — это работало и раньше.
    assert client.get("/courier/available", headers=courier["auth"]).status_code == 403

    r = client.post(f"/parcels/{pid}/accept", headers=courier["auth"])
    assert r.status_code == 403, f"курьер на паузе взял заказ: {r.text}"
    detail = r.json()["detail"]
    assert isinstance(detail, dict) and detail.get("ru") and detail.get("ba"), detail
    with Session(engine) as s:
        assert s.get(M.ParcelDelivery, pid).courier_id is None


def test_pause_ends_and_courier_works_again(client, user_factory):
    """Пауза короткая и не наказание: истекла — человек снова в строю."""
    courier = _make_courier(client, user_factory, name="ГейтКурьер2")
    sender = user_factory(name="ГейтОтпр2")
    pid = _order(client, sender).json()["id"]
    _pause(courier["id"], days=-1)                 # вчерашняя пауза = никакой
    r = client.post(f"/parcels/{pid}/accept", headers=courier["auth"])
    assert r.status_code == 200, f"истёкшая пауза всё ещё мешает: {r.text}"


def test_pause_does_not_touch_bypath_help(client, user_factory):
    """Пауза — курьерская. Помочь «по пути» (бесплатно, по-соседски) она не запрещает:
    там нет ни комиссии, ни допуска курьера, и человек просто везёт мимо."""
    courier = _make_courier(client, user_factory, name="ГейтКурьер3")
    sender = user_factory(name="ГейтОтпр3")
    p = client.post("/parcels", headers=sender["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай", "size": "small",
        "receiver_name": "Айгүл", "receiver_phone": "+79990001188",
        "rules_accepted": True, "description": "документы",
    })
    assert p.status_code == 200, p.text
    _pause(courier["id"])
    assert client.post(f"/parcels/{p.json()['id']}/accept",
                       headers=courier["auth"]).status_code == 200


# ============== 2. Открытый текст заказа курьера ==============
def _watch(monkeypatch):
    """Считаем, какие тексты роутер отдал на проверку (как в test_open_text_moderated_everywhere)."""
    seen = []
    real = af.moderate_open_text

    def spy(text, user_id, **kw):
        seen.append(text or "")
        return real(text, user_id, **kw)

    monkeypatch.setattr(courier_router, "moderate_open_text", spy, raising=False)
    return seen


def test_courier_order_description_is_checked(client, user_factory, monkeypatch):
    """Объявление платного заказа читает каждый курьер района — оно должно проверяться
    так же, как описание посылки «по пути»."""
    sender = user_factory(name="ГейтОтпр4")
    seen = _watch(monkeypatch)
    r = _order(client, sender, description="лекарство, звони напрямую 89170000000")
    assert r.status_code == 200, r.text
    assert seen, "описание заказа курьера ушло в ленту без проверки"
    assert "89170000000" in seen[0]


def test_shopping_list_is_checked_too(client, user_factory, monkeypatch):
    """Список покупок «купи и привези» — то же открытое поле, только с другой стороны формы."""
    sender = user_factory(name="ГейтОтпр5")
    seen = _watch(monkeypatch)
    r = _order(client, sender, delivery_type="buy_bring", cod_amount_kop=90000,
               description="", shopping_list="хлеб, кефир, пиши на +7 917 000-00-00")
    assert r.status_code == 200, r.text
    assert any("917" in t for t in seen), f"список покупок никто не смотрит: {seen}"


def test_check_never_blocks_the_order(client, user_factory):
    """Главное правило модерации: метка — сигнал админу, а не наказание.
    Текст сохраняется как есть, заказ создаётся."""
    sender = user_factory(name="ГейтОтпр6")
    text = "лекарство, звони 89170000000"
    r = _order(client, sender, description=text)
    assert r.status_code == 200, f"проверка уронила заказ — так нельзя: {r.text}"
    assert r.json()["description"] == text, "текст порезали, а человека не предупредили"
