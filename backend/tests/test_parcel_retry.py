# -*- coding: utf-8 -*-
"""Вторая попытка вручения и код в SMS (разбор №2, 2026-08-03).

Сценарий «получателя нет дома» имел ровно один исход — везти коробку обратно. Между Сибаем и
Акъяром это 60 км в один конец, и всё потому, что человек вышел в магазин. Курьер терял полдня,
отправитель — доставку. Теперь неудачная попытка фиксируется, посылка остаётся у курьера, и он
заезжает позже; возврат остаётся отдельной осознанной кнопкой.

Отдельно: код вручения теперь приходит получателю тем же SMS, что и ссылка отслеживания.
Раньше отправитель диктовал шесть цифр отдельным звонком — лишний шаг ровно там, где человек
и так волнуется.
"""
import pytest
from sqlmodel import Session

from app.config import settings
from app.db import engine
from app.models import ParcelDelivery

from test_courier_c2 import _make_courier


@pytest.fixture(autouse=True)
def _courier_on():
    """Режим курьера живёт за флагом — для этих тестов включаем (как в test_courier.py)."""
    prev = settings.courier_enabled
    settings.courier_enabled = True
    yield
    settings.courier_enabled = prev


def _order(client, sender, **over):
    body = {
        "from_city": "Сибай", "to_city": "Акъяр", "size": "small",
        "description": "документы", "receiver_name": "Айгуль",
        "receiver_phone": "+79990010001", "rules_accepted": True,
    }
    body.update(over)
    r = client.post("/parcels", headers=sender["auth"], json=body)
    assert r.status_code == 200, r.text
    return r.json()


def _in_transit(client, sender, courier, **over):
    p = _order(client, sender, **over)
    assert client.post(f"/parcels/{p['id']}/accept", headers=courier["auth"]).status_code == 200
    assert client.post(f"/parcels/{p['id']}/status", headers=courier["auth"],
                       json={"status": "in_transit"}).status_code == 200
    return p


def test_failed_attempt_keeps_the_parcel_moving(client, user_factory):
    """Главное: посылка НЕ разворачивается. Курьер отметил, что не застал, и везёт дальше."""
    courier = _make_courier(client, user_factory)
    sender = user_factory(name="ПопыткаОтпр")
    p = _in_transit(client, sender, courier)
    r = client.post(f"/parcels/{p['id']}/attempt-failed", headers=courier["auth"],
                    json={"reason": "никого нет дома"})
    assert r.status_code == 200, r.text
    with Session(engine) as s:
        row = s.get(ParcelDelivery, p["id"])
    assert row.status == "in_transit", "статус меняться не должен — это не возврат"
    assert row.delivery_attempts == 1


def test_attempts_add_up(client, user_factory):
    """Счётчик — это и мера терпения курьера, и аргумент в споре «он даже не приезжал»."""
    courier = _make_courier(client, user_factory)
    sender = user_factory(name="ПопыткаДважды")
    p = _in_transit(client, sender, courier, receiver_phone="+79990010002")
    for _ in range(2):
        assert client.post(f"/parcels/{p['id']}/attempt-failed", headers=courier["auth"]).status_code == 200
    with Session(engine) as s:
        assert s.get(ParcelDelivery, p["id"]).delivery_attempts == 2


def test_return_is_still_available_after_attempts(client, user_factory):
    """Не вышло и со второго раза — никого не заставляем ездить бесконечно."""
    courier = _make_courier(client, user_factory)
    sender = user_factory(name="ПопыткаПотомВозврат")
    p = _in_transit(client, sender, courier, receiver_phone="+79990010003")
    client.post(f"/parcels/{p['id']}/attempt-failed", headers=courier["auth"])
    r = client.post(f"/parcels/{p['id']}/return-start", headers=courier["auth"],
                    json={"reason": "не выходит на связь"})
    assert r.status_code == 200, r.text
    with Session(engine) as s:
        assert s.get(ParcelDelivery, p["id"]).status == "returning"


def test_only_the_assigned_courier_can_mark_an_attempt(client, user_factory):
    courier = _make_courier(client, user_factory)
    stranger = _make_courier(client, user_factory, name="ЧужойКурьер")
    sender = user_factory(name="ПопыткаЧужой")
    p = _in_transit(client, sender, courier, receiver_phone="+79990010004")
    assert client.post(f"/parcels/{p['id']}/attempt-failed",
                       headers=stranger["auth"]).status_code == 404


def test_attempt_before_pickup_is_rejected(client, user_factory):
    """Пока посылка не в пути, «не застал получателя» — бессмыслица."""
    courier = _make_courier(client, user_factory)
    sender = user_factory(name="ПопыткаРано")
    p = _order(client, sender, receiver_phone="+79990010005")
    client.post(f"/parcels/{p['id']}/accept", headers=courier["auth"])
    assert client.post(f"/parcels/{p['id']}/attempt-failed",
                       headers=courier["auth"]).status_code == 409


def test_tracking_sms_carries_the_confirm_code(client, user_factory, monkeypatch):
    """Код должен доехать до получателя сам — иначе отправитель диктует шесть цифр по телефону."""
    sent = []
    import app.routers.family as fam
    monkeypatch.setattr(fam, "send_text", lambda phone, text: sent.append((phone, text)))
    sender = user_factory(name="СмсОтпр")
    p = _order(client, sender, receiver_phone="+79990010006")
    r = client.post(f"/parcels/{p['id']}/track-link", headers=sender["auth"])
    assert r.status_code == 200, r.text
    assert sent, "SMS получателю должно уйти"
    assert p["confirm_code"] in sent[0][1], sent[0][1]
