# -*- coding: utf-8 -*-
"""Что именно везём: вес, тип груза, «хрупкое» (разбор №2, 2026-08-03).

Раньше был только размер. Он отвечает на «влезет ли», а курьер решает по другим вопросам:
унесу ли (коробка 40×40 — это и подушка на 3 кг, и картошка на 40), возьмусь ли (лекарство и
рассада ехали одной строкой описания), как положить (банка мёда рядом с домкратом — и в споре
обе стороны честно правы).

Ключевое требование: всё это курьер должен видеть ДО принятия заказа, в том числе в ОТКРЫТОМ
списке. Это условия заказа, а не персональные данные — в отличие от телефона и адреса, которые
открываются только принявшему. Если спрятать вес до принятия, поле бесполезно: решение
принимается раньше.
"""
import pytest
from sqlmodel import Session

from app.config import settings
from app.db import engine
from app.models import ParcelDelivery, UserRole


def _create(client, sender, **over):
    body = {
        "from_city": "Баймак", "to_city": "Сибай", "size": "medium",
        "description": "гостинцы", "receiver_name": "Гөлнара",
        "receiver_phone": "+79990009901", "rules_accepted": True,
    }
    body.update(over)
    return client.post("/parcels", headers=sender["auth"], json=body)


def test_cargo_fields_are_saved(client, user_factory):
    sender = user_factory(name="ГрузОтпр")
    r = _create(client, sender, weight_kg=12.5, cargo_type="medicine", fragile=True)
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["weight_kg"] == 12.5
    assert body["cargo_type"] == "medicine"
    assert body["fragile"] is True
    with Session(engine) as s:
        row = s.get(ParcelDelivery, body["id"])
    assert row.weight_kg == 12.5 and row.cargo_type == "medicine" and row.fragile is True


def test_old_client_without_cargo_fields_still_works(client, user_factory):
    """Поля опциональны: телефон обновится не у всех, а посылки отправляют уже сегодня."""
    sender = user_factory(name="ГрузСтарый")
    r = _create(client, sender, receiver_phone="+79990009902")
    assert r.status_code == 200, r.text
    assert r.json()["weight_kg"] == 0.0
    assert r.json()["cargo_type"] == ""
    assert r.json()["fragile"] is False


def test_unknown_cargo_type_becomes_other(client, user_factory):
    """Незнакомая категория не должна ронять заявку — посылка важнее ярлыка."""
    sender = user_factory(name="ГрузЧудной")
    r = _create(client, sender, cargo_type="бетон", receiver_phone="+79990009903")
    assert r.status_code == 200, r.text
    assert r.json()["cargo_type"] == "other"


def test_absurd_weight_is_rejected(client, user_factory):
    """Сто кг — граница «посылки между своими». Выше это грузоперевозка: другой транспорт,
    другая ответственность, и курьер на легковой туда ехать не должен."""
    sender = user_factory(name="ГрузТяжёлый")
    assert _create(client, sender, weight_kg=250, receiver_phone="+79990009904").status_code == 422
    assert _create(client, sender, weight_kg=-1, receiver_phone="+79990009905").status_code == 422


def test_courier_sees_weight_before_taking_the_order(client, user_factory):
    """Главное: вес виден в ОТКРЫТОМ списке. Спрятать его до принятия — значит сделать поле
    бесполезным, потому что решение «берусь / не берусь» принимается раньше."""
    sender = user_factory(name="ГрузОткрытый")
    created = _create(client, sender, weight_kg=40, fragile=True, cargo_type="food",
                      receiver_phone="+79990009906").json()
    courier = user_factory(name="ГрузКурьер", role=UserRole.driver)
    rows = client.get("/parcels/available", headers=courier["auth"]).json()
    mine = [x for x in (rows if isinstance(rows, list) else rows.get("items", []))
            if x["id"] == created["id"]]
    assert mine, "заявка должна быть в открытом списке"
    card = mine[0]
    assert card["weight_kg"] == 40
    assert card["fragile"] is True
    assert card["cargo_type"] == "food"
    # А телефон и адрес там по-прежнему НЕ показываем — правило приватности не поехало.
    assert "receiver_phone" not in card
    assert not card.get("to_address")


def test_courier_order_takes_cargo_fields_too(client, user_factory, monkeypatch):
    """Два режима доставки должны говорить на одном языке, иначе форма «по пути» и форма
    курьера начнут расходиться при первой же правке."""
    monkeypatch.setattr(settings, "courier_enabled", True)
    sender = user_factory(name="ГрузКурьерРежим")
    r = client.post("/courier/orders", headers=sender["auth"], json={
        "from_city": "Акъяр", "to_city": "Сибай", "size": "small",
        "description": "лекарство", "receiver_name": "Айгуль",
        "receiver_phone": "+79990009907", "rules_accepted": True,
        "delivery_type": "courier", "urgency": "bypath",
        "from_lat": 51.90, "from_lng": 58.20, "to_lat": 52.71, "to_lng": 58.66,
        "weight_kg": 2.5, "cargo_type": "medicine", "fragile": True,
    })
    assert r.status_code == 200, r.text
    assert r.json()["weight_kg"] == 2.5
    assert r.json()["cargo_type"] == "medicine"
    assert r.json()["fragile"] is True
