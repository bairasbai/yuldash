# -*- coding: utf-8 -*-
"""Закрытая доставка остаётся закрытой, а «вручил» нельзя объявить с любого места.

Аудит 2026-08-07. В графе статусов доставки был обратный ход:

- курьер не застал получателя, привёз коробку обратно отправителю и нажал «вернул»
  (`returned`, комиссию за возврат мы честно не берём). Через час он жал «доставлено»
  и вводил код, который отправитель когда-то диктовал получателю. Заказ воскресал:
  комиссия начислялась заново И сразу считалась ОПЛАЧЕННОЙ (при возврате ставится
  `commission_paid=True`, и при воскрешении её никто не сбрасывал). В отчёте админа эти
  деньги попадали в строку «собрано» — выдуманная выручка. Отправитель, у которого посылка
  уже дома, получал пуш «Посылка доставлена ✅»;
- «доставлено» проходило прямо из `accepted`: курьер закрывал доставку, ни разу не отметив
  «забрал». Фото «взял целой» снимается именно на переходе accepted→in_transit — без него
  первой границы ответственности не существует, и спор «оно уже было битым» опирается
  только на слово;
- «доставлено» проходило и из `returning`: курьер объявил возврат отправителю и всё равно
  закрывал заказ как вручённый получателю.
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
    """Пуши/телеграм глушим — проверяем состояние заказа, а не доставку уведомлений."""
    monkeypatch.setattr("app.services.send_push", lambda *a, **k: None)
    monkeypatch.setattr("app.routers.parcels.notify_admin_telegram", lambda *a, **k: None)
    monkeypatch.setattr("app.routers.courier.notify_admin_telegram", lambda *a, **k: None)


def _make_courier(client, user_factory, name="ФиналКурьер"):
    admin = user_factory(name="ФиналАдмин", role=UserRole.admin)
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
        "receiver_name": "Гөлнара", "receiver_phone": "+79990007711",
        "rules_accepted": True, "delivery_type": "courier", "urgency": "bypath",
    }
    body.update(ov)
    r = client.post("/courier/orders", headers=sender["auth"], json=body)
    assert r.status_code == 200, r.text
    return r.json()


def _parcel(pid) -> M.ParcelDelivery:
    with Session(engine) as s:
        return s.get(M.ParcelDelivery, pid)


def _bilingual(r):
    """4xx пользователю — всегда {ru, ba}: человек читает на своём языке."""
    detail = r.json()["detail"]
    assert isinstance(detail, dict) and detail.get("ru") and detail.get("ba"), detail


# ============== Возврат закрывает доставку навсегда ==============
def test_returned_parcel_cannot_be_delivered(client, user_factory):
    """Посылка вернулась к отправителю — «вручил получателю» после этого невозможно.

    Иначе платформа рисует себе комиссию за услугу, которой не было, и сразу помечает её
    оплаченной: деньги, которых никто не платил, попадают в отчёт как собранные.
    """
    courier = _make_courier(client, user_factory)
    sender = user_factory(name="ФиналОтпр1")
    order = _order(client, sender)
    pid, code = order["id"], order["confirm_code"]
    assert client.post(f"/parcels/{pid}/accept", headers=courier["auth"]).status_code == 200
    assert client.post(f"/parcels/{pid}/status", headers=courier["auth"],
                       json={"status": "in_transit"}).status_code == 200
    assert client.post(f"/parcels/{pid}/return-start", headers=courier["auth"],
                       json={"reason": "получателя нет дома"}).status_code == 200
    assert client.post(f"/parcels/{pid}/return-done", headers=courier["auth"]).status_code == 200
    assert _parcel(pid).status == "returned"

    r = client.post(f"/parcels/{pid}/status", headers=courier["auth"],
                    json={"status": "delivered", "code": code})
    assert r.status_code == 409, f"закрытую возвратом доставку объявили вручённой: {r.text}"
    _bilingual(r)

    p = _parcel(pid)
    assert p.status == "returned", "статус отмотали назад"
    assert p.delivered_at is None, "у возвращённой посылки появилось время вручения"
    assert p.commission_kop == 0, "за несостоявшуюся доставку начислили комиссию"
    # Кабинет курьера: ни доставки, ни заработка, ни «оплаченной» комиссии не прибавилось.
    st = client.get("/courier/me", headers=courier["auth"]).json()["statement"]
    assert st["delivered_count"] == 0 and st["commission_earned_kop"] == 0, st
    assert st["commission_paid_kop"] == 0, "платформа записала себе деньги, которых не платили"


def test_returning_parcel_cannot_be_delivered(client, user_factory):
    """Курьер объявил возврат — значит он едет к ОТПРАВИТЕЛЮ. Закрыть заказ «вручил
    получателю» из этого состояния нельзя: одно из двух событий выдумано."""
    courier = _make_courier(client, user_factory, name="ФиналКурьер2")
    sender = user_factory(name="ФиналОтпр2")
    order = _order(client, sender)
    pid, code = order["id"], order["confirm_code"]
    client.post(f"/parcels/{pid}/accept", headers=courier["auth"])
    client.post(f"/parcels/{pid}/status", headers=courier["auth"], json={"status": "in_transit"})
    assert client.post(f"/parcels/{pid}/return-start", headers=courier["auth"],
                       json={"reason": "не выходит на связь"}).status_code == 200

    r = client.post(f"/parcels/{pid}/status", headers=courier["auth"],
                    json={"status": "delivered", "code": code})
    assert r.status_code == 409, f"из «везу обратно» закрыли как вручённую: {r.text}"
    _bilingual(r)
    p = _parcel(pid)
    assert p.status == "returning" and p.delivered_at is None


# ============== «Вручил» только после «забрал» ==============
def test_delivered_requires_pickup_first(client, user_factory):
    """Нельзя вручить то, что не забирал: без перехода «забрал» нет фото «взял целой»,
    то есть нет первой границы ответственности в возможном споре."""
    courier = _make_courier(client, user_factory, name="ФиналКурьер3")
    sender = user_factory(name="ФиналОтпр3")
    order = _order(client, sender)
    pid, code = order["id"], order["confirm_code"]
    assert client.post(f"/parcels/{pid}/accept", headers=courier["auth"]).status_code == 200

    r = client.post(f"/parcels/{pid}/status", headers=courier["auth"],
                    json={"status": "delivered", "code": code})
    assert r.status_code == 409, f"доставку закрыли, не забрав посылку: {r.text}"
    _bilingual(r)
    p = _parcel(pid)
    assert p.status == "accepted" and p.delivered_at is None
    assert (p.commission_kop or 0) >= 0 and not p.commission_paid


def test_normal_delivery_still_works(client, user_factory):
    """Честный путь accepted → in_transit → delivered не пострадал."""
    courier = _make_courier(client, user_factory, name="ФиналКурьер4")
    sender = user_factory(name="ФиналОтпр4")
    order = _order(client, sender)
    pid, code = order["id"], order["confirm_code"]
    client.post(f"/parcels/{pid}/accept", headers=courier["auth"])
    assert client.post(f"/parcels/{pid}/status", headers=courier["auth"],
                       json={"status": "in_transit"}).status_code == 200
    r = client.post(f"/parcels/{pid}/status", headers=courier["auth"],
                    json={"status": "delivered", "code": code})
    assert r.status_code == 200, r.text
    p = _parcel(pid)
    assert p.status == "delivered" and p.delivered_at is not None
    assert p.commission_kop > 0 and not p.commission_paid, "комиссия начислена, но НЕ оплачена"
