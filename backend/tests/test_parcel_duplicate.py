# -*- coding: utf-8 -*-
"""Двойной тап не должен создавать вторую посылку (разбор №2, 2026-08-03).

У такси серверный гард был с самого начала, у доставки — нет: всё держалось на том, что кнопка
в приложении гаснет на время запроса. Этого недостаточно — сеть в районе рвётся, клиент
повторяет запрос сам, и человек получает две одинаковые заявки: обе висят у курьеров, за обе
он платит, а разбирать это потом некому.

Запретить «одну активную на человека», как у такси, нельзя: отправить три посылки разным людям
в один день — обычное дело. Поэтому дедуп по содержимому в коротком окне: тот же отправитель,
маршрут, получатель и размер за минуту — это один и тот же тап.
"""
import pytest
from sqlmodel import Session, func, select

from app.config import settings
from app.db import engine
from app.models import ParcelDelivery


@pytest.fixture
def courier_mode_on():
    """Заказ курьера живёт за флагом (пока «скоро запустится») — включаем точечно."""
    prev = settings.courier_enabled
    settings.courier_enabled = True
    yield
    settings.courier_enabled = prev


def _payload(**over):
    base = {
        "from_city": "Баймак", "to_city": "Сибай", "size": "small",
        "description": "лекарство", "receiver_name": "Гөлнара",
        "receiver_phone": "+79990008801", "rules_accepted": True,
    }
    base.update(over)
    return base


def _count(sender_id: int) -> int:
    with Session(engine) as s:
        return s.exec(select(func.count()).select_from(ParcelDelivery)
                      .where(ParcelDelivery.sender_id == sender_id)).one()


def test_double_tap_returns_the_same_parcel(client, user_factory):
    sender = user_factory(name="ДублеТап")
    first = client.post("/parcels", headers=sender["auth"], json=_payload())
    assert first.status_code == 200, first.text
    second = client.post("/parcels", headers=sender["auth"], json=_payload())
    assert second.status_code == 200, second.text
    assert second.json()["id"] == first.json()["id"], "второй тап обязан вернуть ту же заявку"
    assert _count(sender["id"]) == 1


def test_different_recipient_is_a_real_second_parcel(client, user_factory):
    """Гард не должен мешать жизни: две посылки разным людям — норма, а не дубль."""
    sender = user_factory(name="ДвеРазные")
    a = client.post("/parcels", headers=sender["auth"], json=_payload(receiver_phone="+79990008802"))
    b = client.post("/parcels", headers=sender["auth"],
                    json=_payload(receiver_phone="+79990008803", receiver_name="Азат"))
    assert a.status_code == 200 and b.status_code == 200
    assert a.json()["id"] != b.json()["id"]
    assert _count(sender["id"]) == 2


def test_different_route_is_a_real_second_parcel(client, user_factory):
    sender = user_factory(name="ДваМаршрута")
    a = client.post("/parcels", headers=sender["auth"], json=_payload())
    b = client.post("/parcels", headers=sender["auth"], json=_payload(to_city="Акъяр"))
    assert a.status_code == 200 and b.status_code == 200
    assert a.json()["id"] != b.json()["id"]


def test_taken_parcel_does_not_block_a_new_one(client, user_factory):
    """Дедуп смотрит только на ещё не взятые заявки: посылку забрал курьер — можно слать вторую
    такую же, это уже точно не двойной тап."""
    sender = user_factory(name="ПослеЗабора")
    first = client.post("/parcels", headers=sender["auth"], json=_payload(receiver_phone="+79990008804"))
    pid = first.json()["id"]
    with Session(engine) as s:                       # имитируем «курьер принял»
        p = s.get(ParcelDelivery, pid)
        p.status = "accepted"
        s.add(p)
        s.commit()
    second = client.post("/parcels", headers=sender["auth"], json=_payload(receiver_phone="+79990008804"))
    assert second.status_code == 200
    assert second.json()["id"] != pid


def test_courier_order_double_tap_is_deduped(client, user_factory, courier_mode_on):
    """У заказа курьера дубль дороже: комиссия и выкуп товара считаются на каждую заявку."""
    sender = user_factory(name="ДублеКурьер")
    body = {
        "from_city": "Акъяр", "to_city": "Сибай", "size": "small",
        "description": "документы", "receiver_name": "Айгуль",
        "receiver_phone": "+79990008810", "rules_accepted": True,
        "delivery_type": "courier", "urgency": "bypath",
        "from_lat": 51.90, "from_lng": 58.20, "to_lat": 52.71, "to_lng": 58.66,
    }
    a = client.post("/courier/orders", headers=sender["auth"], json=body)
    b = client.post("/courier/orders", headers=sender["auth"], json=body)
    assert a.status_code == 200, a.text
    assert b.status_code == 200, b.text
    assert a.json()["id"] == b.json()["id"]
    assert _count(sender["id"]) == 1
