# -*- coding: utf-8 -*-
"""Курьер не исчезает вместе с посылкой.

Аудит 2026-08-07. Снятие с заказа («не смогу везти») разрешалось и ПОСЛЕ того, как курьер
физически забрал коробку. Заявка возвращалась в открытую ленту, `courier_id` обнулялся —
и след «кто вёз» стирался полностью:

- лекарство едет в машине человека, которого в заказе больше нет;
- другой курьер берёт заявку и приезжает к отправителю за посылкой, которой там уже нет;
- отправитель не может открыть спор (спорить не с кем — курьер не назначен) и не может
  написать в чат (у заявки снова статус «ищем курьера», переписка закрыта).

Фоновый воркер тот же случай обрабатывает наоборот и объясняет почему: из `in_transit`
возвращать в ленту нечего, а `courier_id` — единственное, по чему потом можно открыть спор
и найти посылку. Ручная кнопка делала прямо противоположное.

Выход из «посылка у меня, везти не могу» остаётся: «везу обратно» (return-start) и спор.
"""
import pytest
from sqlmodel import Session

from app import models as M
from app.config import settings
from app.db import engine
from app.models import UserRole


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


def _make_courier(client, user_factory, name="КоробкаКурьер"):
    admin = user_factory(name="КоробкаАдмин", role=UserRole.admin)
    c = user_factory(name=name)
    aid = client.post("/courier/apply", headers=c["auth"],
                      json={"transport": "car", "selfie_url": "secure/docs/s.jpg"}).json()["id"]
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
        "receiver_name": "Гөлнара", "receiver_phone": "+79990008822",
        "rules_accepted": True, "delivery_type": "courier", "urgency": "bypath",
    }
    body.update(ov)
    r = client.post("/courier/orders", headers=sender["auth"], json=body)
    assert r.status_code == 200, r.text
    return r.json()


def _parcel(pid) -> M.ParcelDelivery:
    with Session(engine) as s:
        return s.get(M.ParcelDelivery, pid)


def test_courier_cannot_release_after_taking_the_parcel(client, user_factory):
    """Коробка уже в машине курьера — «снимаюсь» больше не проходит.

    Проверяем не только отказ, но и то, ради чего он: заявка не уходит в ленту, курьер
    остаётся назначенным, спор и переписка работают.
    """
    courier = _make_courier(client, user_factory)
    sender = user_factory(name="КоробкаОтпр1")
    pid = _order(client, sender)["id"]
    assert client.post(f"/parcels/{pid}/accept", headers=courier["auth"]).status_code == 200
    assert client.post(f"/parcels/{pid}/status", headers=courier["auth"],
                       json={"status": "in_transit"}).status_code == 200

    r = client.post(f"/parcels/{pid}/release", headers=courier["auth"],
                    json={"reason": "сломался"})
    assert r.status_code == 409, f"курьер снялся с посылкой на руках: {r.text}"
    detail = r.json()["detail"]
    assert isinstance(detail, dict) and detail.get("ru") and detail.get("ba"), detail

    p = _parcel(pid)
    assert p.status == "in_transit", "заявка ушла из «в пути»"
    assert p.courier_id == courier["id"], "след «кто вёз» стёрт — посылку потом не найти"

    # Заявки нет в открытой ленте: другой курьер не поедет за коробкой, которой там нет.
    other = _make_courier(client, user_factory, name="КоробкаКурьер2")
    avail = client.get("/courier/available", headers=other["auth"])
    assert avail.status_code == 200, avail.text
    assert not any(x["id"] == pid for x in avail.json()), "посылка снова в ленте, а она в машине"

    # Отправителю есть с кем разбираться и куда написать.
    assert client.get(f"/parcels/{pid}/messages", headers=sender["auth"]).status_code == 200
    d = client.post(f"/parcels/{pid}/dispute", headers=sender["auth"],
                    json={"reason": "курьер увёз посылку и пропал", "type": "parcel_lost"})
    assert d.status_code == 200, f"спор недоступен, хотя посылка у курьера: {d.text}"


def test_carrying_courier_can_still_return_the_parcel(client, user_factory):
    """Выход из «везти не могу» никуда не делся: посылку везут обратно отправителю."""
    courier = _make_courier(client, user_factory, name="КоробкаКурьер3")
    sender = user_factory(name="КоробкаОтпр2")
    pid = _order(client, sender)["id"]
    client.post(f"/parcels/{pid}/accept", headers=courier["auth"])
    client.post(f"/parcels/{pid}/status", headers=courier["auth"], json={"status": "in_transit"})
    assert client.post(f"/parcels/{pid}/return-start", headers=courier["auth"],
                       json={"reason": "сломалась машина"}).status_code == 200
    assert _parcel(pid).status == "returning"
    assert client.post(f"/parcels/{pid}/return-done", headers=courier["auth"]).status_code == 200
    assert _parcel(pid).status == "returned"


def test_release_before_pickup_still_works(client, user_factory):
    """До того, как курьер забрал коробку, снятие остаётся честным и бесплатным:
    замело дорогу, заболел — посылка возвращается в ленту, её берёт другой."""
    courier = _make_courier(client, user_factory, name="КоробкаКурьер4")
    sender = user_factory(name="КоробкаОтпр3")
    pid = _order(client, sender)["id"]
    assert client.post(f"/parcels/{pid}/accept", headers=courier["auth"]).status_code == 200
    r = client.post(f"/parcels/{pid}/release", headers=courier["auth"],
                    json={"reason": "дорогу замело"})
    assert r.status_code == 200, r.text
    p = _parcel(pid)
    assert p.status == "created" and p.courier_id is None
