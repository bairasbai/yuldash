# -*- coding: utf-8 -*-
"""Пуши по посылке ведут человека НА ЭКРАН посылки, а не «просто в приложение».

Было: уведомления «Курьер найден» и «Посылка доставлена» уходили без data-payload. Клиент по
тапу открывал приложение и всё — отправитель узнавал статус, только если сам догадывался
переключить вкладку. Android умеет открывать «Посылки», когда в data приходит `type`,
начинающийся на `parcel`.

Здесь проверяем контракт, на который опирается клиент: на каждом шаге жизни доставки обе
стороны получают уведомление, а в data лежит тип «parcel*» и id посылки.
"""
import pytest
from sqlmodel import Session, select

from app.config import settings
from app.db import engine
from app.models import Notification, ParcelDelivery, UserRole
from conftest import upload_doc


@pytest.fixture(autouse=True)
def _courier_on():
    prev = settings.courier_enabled
    settings.courier_enabled = True
    yield
    settings.courier_enabled = prev


@pytest.fixture
def pushes(monkeypatch):
    """Перехватываем FCM: важно не «дошло ли», а ЧТО мы кладём в data."""
    sent: list = []

    def fake_send_push(session, user_id, title, body, data=None, data_only=False):
        sent.append({"user_id": user_id, "title": title, "body": body, "data": data})

    monkeypatch.setattr("app.services.send_push", fake_send_push)
    return sent


def _make_courier(client, user_factory, name):
    admin = user_factory(name=f"Админ{name}", role=UserRole.admin)
    c = user_factory(name=name)
    aid = client.post("/courier/apply", headers=c["auth"],
                      json={"transport": "car", "selfie_url": upload_doc(client, c["auth"])}).json()["id"]
    assert client.post(f"/admin/courier-applications/{aid}/approve",
                       headers=admin["auth"]).status_code == 200
    assert client.post("/courier/online", headers=c["auth"],
                       json={"zone": "region"}).status_code == 200
    return c


def _order(client, sender):
    r = client.post("/courier/orders", headers=sender["auth"], json={
        "from_city": "Уфа", "to_city": "Стерлитамак",
        "from_lat": 54.735, "from_lng": 55.958, "to_lat": 53.630, "to_lng": 55.950,
        "size": "small", "description": "Документы", "receiver_name": "Айгүл",
        "receiver_phone": "+79990001166", "rules_accepted": True,
        "delivery_type": "courier", "urgency": "bypath",
    })
    assert r.status_code == 200, r.text
    return r.json()["id"]


def _parcel_pushes(sent, user_id):
    return [p for p in sent if p["user_id"] == user_id and p["data"]]


def _assert_routes_to_parcel(push, parcel_id):
    data = push["data"]
    assert data, f"пуш без data не ведёт никуда: {push['title']}"
    assert str(data.get("type", "")).startswith("parcel"), data
    assert str(data.get("id")) == str(parcel_id), data


def test_full_parcel_lifecycle_pushes_carry_routing_data(client, user_factory, pushes):
    """Курьер найден → забрал → вручил: отправитель на каждом шаге получает пуш с маршрутом."""
    sender = _make_courier(client, user_factory, "ОтправительПуш")
    courier = _make_courier(client, user_factory, "КурьерПуш")
    pid = _order(client, sender)

    pushes.clear()
    assert client.post(f"/parcels/{pid}/accept", headers=courier["auth"]).status_code == 200
    found = _parcel_pushes(pushes, sender["id"])
    assert found, "«Курьер найден» обязан дойти до отправителя"
    _assert_routes_to_parcel(found[-1], pid)

    pushes.clear()
    assert client.post(f"/parcels/{pid}/status", headers=courier["auth"],
                       json={"status": "in_transit"}).status_code == 200
    moved = _parcel_pushes(pushes, sender["id"])
    assert moved, "«курьер забрал и повёз» раньше не сообщалось вообще"
    _assert_routes_to_parcel(moved[-1], pid)

    with Session(engine) as s:
        code = s.get(ParcelDelivery, pid).confirm_code
    pushes.clear()
    assert client.post(f"/parcels/{pid}/status", headers=courier["auth"],
                       json={"status": "delivered", "code": code}).status_code == 200
    done = _parcel_pushes(pushes, sender["id"])
    assert done, "«доставлено» обязано дойти до отправителя"
    _assert_routes_to_parcel(done[-1], pid)


def test_release_and_cancel_pushes_carry_routing_data(client, user_factory, pushes):
    """Ветка «что-то пошло не так»: снятие курьера и отмена отправителем — тоже с маршрутом."""
    sender = _make_courier(client, user_factory, "ОтправительОтмена")
    courier = _make_courier(client, user_factory, "КурьерОтмена")

    pid = _order(client, sender)
    assert client.post(f"/parcels/{pid}/accept", headers=courier["auth"]).status_code == 200
    pushes.clear()
    assert client.post(f"/parcels/{pid}/release", headers=courier["auth"],
                       json={"reason": "Сломалась машина"}).status_code == 200
    released = _parcel_pushes(pushes, sender["id"])
    assert released, "отправителю обязаны сказать, что курьер снялся"
    _assert_routes_to_parcel(released[-1], pid)

    pid2 = _order(client, sender)
    assert client.post(f"/parcels/{pid2}/accept", headers=courier["auth"]).status_code == 200
    pushes.clear()
    assert client.post(f"/parcels/{pid2}/cancel", headers=sender["auth"]).status_code == 200
    cancelled = _parcel_pushes(pushes, courier["id"])
    assert cancelled, "курьеру обязаны сказать об отмене — он уже выехал"
    _assert_routes_to_parcel(cancelled[-1], pid2)


def test_return_pushes_carry_routing_data(client, user_factory, pushes):
    """Возврат: «везут обратно» и «вернулась» — обе новости с маршрутом на экран посылки."""
    sender = _make_courier(client, user_factory, "ОтправительВозвратПуш")
    courier = _make_courier(client, user_factory, "КурьерВозвратПуш")
    pid = _order(client, sender)
    assert client.post(f"/parcels/{pid}/accept", headers=courier["auth"]).status_code == 200

    pushes.clear()
    assert client.post(f"/parcels/{pid}/return-start", headers=courier["auth"],
                       json={"reason": "Получателя нет дома"}).status_code == 200
    back = _parcel_pushes(pushes, sender["id"])
    assert back
    _assert_routes_to_parcel(back[-1], pid)

    pushes.clear()
    assert client.post(f"/parcels/{pid}/return-done", headers=courier["auth"]).status_code == 200
    returned = _parcel_pushes(pushes, sender["id"])
    assert returned
    _assert_routes_to_parcel(returned[-1], pid)


def test_new_parcel_broadcast_routes_couriers_to_the_screen(client, user_factory, pushes):
    """Рассылка «новая доставка рядом» тоже ведёт на экран, а не в пустоту."""
    sender = _make_courier(client, user_factory, "ОтправительРассылка")
    courier = _make_courier(client, user_factory, "КурьерНаЛинии")

    pushes.clear()
    pid = _order(client, sender)
    offered = _parcel_pushes(pushes, courier["id"])
    assert offered, "курьер на линии должен узнать о новой заявке"
    _assert_routes_to_parcel(offered[-1], pid)


def test_notification_feed_stays_bilingual(client, user_factory, pushes):
    """Пуш пушем, но запись в Центре уведомлений обязана остаться на двух языках."""
    sender = _make_courier(client, user_factory, "ОтправительЛента")
    courier = _make_courier(client, user_factory, "КурьерЛента")
    pid = _order(client, sender)
    assert client.post(f"/parcels/{pid}/accept", headers=courier["auth"]).status_code == 200

    with Session(engine) as s:
        notes = s.exec(select(Notification).where(Notification.ref_id == pid,
                                                  Notification.user_id == sender["id"])).all()
    assert notes, "событие должно попасть и в ленту, не только в пуш"
    assert all(n.title_ru and n.title_ba and n.body_ru and n.body_ba for n in notes)
