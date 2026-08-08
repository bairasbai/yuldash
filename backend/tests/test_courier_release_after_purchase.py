# -*- coding: utf-8 -*-
"""Курьер не может молча уйти после того, как купил товар на свои (аудит 2026-08-03).

«Купи и привези»: курьер тратит СВОИ деньги (до 5000 ₽), везёт товар, получатель возвращает
сумму при вручении. Отправителю отмену на этом шаге запретили давно. А курьеру — нет: он мог
нажать «сняться», заказ уходил в общий список, и нигде не оставалось записи, что ему должны
за товар. Деньги просто терялись.

Теперь: сняться нельзя, путь — спор (там есть разбор человеком и поле компенсации). Пока товар
не куплен, право сняться никуда не делось: заболел, замело дорогу — обычная зимняя история.
"""
import pytest

from app.config import settings
from app.models import UserRole
from conftest import upload_doc


@pytest.fixture(autouse=True)
def _courier_on():
    prev = settings.courier_enabled
    settings.courier_enabled = True
    yield
    settings.courier_enabled = prev


def _make_courier(client, user_factory, name="Курьер"):
    admin = user_factory(name=f"Админ{name}", role=UserRole.admin)
    c = user_factory(name=name)
    aid = client.post("/courier/apply", headers=c["auth"],
                      json={"transport": "car", "selfie_url": upload_doc(client, c["auth"])}).json()["id"]
    assert client.post(f"/admin/courier-applications/{aid}/approve",
                       headers=admin["auth"]).status_code == 200
    assert client.post("/courier/online", headers=c["auth"],
                       json={"zone": "region"}).status_code == 200
    return c


def _buy_bring_order(client, sender):
    r = client.post("/courier/orders", headers=sender["auth"], json={
        "from_city": "Уфа", "to_city": "Стерлитамак",
        "from_lat": 54.735, "from_lng": 55.958, "to_lat": 53.630, "to_lng": 55.950,
        "size": "small", "description": "Лекарство", "receiver_name": "Айгүл",
        "receiver_phone": "+79990001155", "rules_accepted": True,
        "delivery_type": "buy_bring", "urgency": "bypath",
        "cod_amount_kop": 200_000, "declared_value_kop": 250_000,
    })
    assert r.status_code == 200, r.text
    return r.json()["id"]


def test_release_blocked_after_goods_bought(client, user_factory):
    """Товар куплен → «сняться» отвечает 409 и объясняет, что делать (двуязычно)."""
    sender = _make_courier(client, user_factory, name="ОтправительBB")
    courier = _make_courier(client, user_factory, name="КурьерBB")
    pid = _buy_bring_order(client, sender)
    assert client.post(f"/parcels/{pid}/accept", headers=courier["auth"]).status_code == 200
    # Курьер сходил в магазин и потратил свои 1800 ₽.
    assert client.post(f"/courier/orders/{pid}/goods-cost", headers=courier["auth"],
                       json={"actual_kop": 180_000}).status_code == 200

    r = client.post(f"/parcels/{pid}/release", headers=courier["auth"])
    assert r.status_code == 409, r.text
    detail = r.json()["detail"]
    assert detail["ru"] and detail["ba"] and detail["ru"] != detail["ba"]
    assert "спор" in detail["ru"].lower(), "человеку надо сказать, куда идти за деньгами"

    # Заказ остался за курьером — он всё ещё сторона сделки, товар у него.
    carrying = client.get("/parcels/carrying", headers=courier["auth"]).json()
    assert any(p["id"] == pid for p in carrying)


def test_release_still_allowed_before_purchase(client, user_factory):
    """Товар ещё не куплен → право сняться сохранилось (замело дорогу — это норма)."""
    sender = _make_courier(client, user_factory, name="ОтправительBB2")
    courier = _make_courier(client, user_factory, name="КурьерBB2")
    pid = _buy_bring_order(client, sender)
    assert client.post(f"/parcels/{pid}/accept", headers=courier["auth"]).status_code == 200

    r = client.post(f"/parcels/{pid}/release", headers=courier["auth"],
                    json={"reason": "Замело дорогу"})
    assert r.status_code == 200, r.text
    assert r.json()["status"] == "created"


def test_dispute_is_reachable_after_purchase(client, user_factory):
    """Обещанный выход рабочий: после закупки курьер действительно может открыть спор."""
    sender = _make_courier(client, user_factory, name="ОтправительBB3")
    courier = _make_courier(client, user_factory, name="КурьерBB3")
    pid = _buy_bring_order(client, sender)
    assert client.post(f"/parcels/{pid}/accept", headers=courier["auth"]).status_code == 200
    assert client.post(f"/courier/orders/{pid}/goods-cost", headers=courier["auth"],
                       json={"actual_kop": 180_000}).status_code == 200

    r = client.post(f"/parcels/{pid}/dispute", headers=courier["auth"],
                    json={"reason": "Получатель не отвечает, товар куплен на свои"})
    assert r.status_code == 200, r.text
