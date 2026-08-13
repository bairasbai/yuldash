"""Данные людей, которых нет в приложении, показываем скупее всего.

У Юлдаша есть две категории людей, которые в приложении не зарегистрированы, ничего нам не
разрешали и убрать свои данные не могут:

  • ПОЛУЧАТЕЛЬ ПОСЫЛКИ — его имя, телефон и адрес вписал отправитель;
  • ВЗРОСЛЫЙ, отвечающий за поездку подростка, — его имя и телефон вписал тот, кто бронировал.

Про телефоны это понимали: телефон получателя открыт только принявшему курьеру, телефон
взрослого — только водителю. Но правило применили наполовину.

  1. Имя получателя висело в открытой ленте заявок, где его видит ЛЮБОЙ курьер района.
     Курьеру, чтобы решить «берусь ли», нужны маршрут, размер, вес и оплата — фамилия
     адресата в этом решении не участвует.

  2. Контакты взрослого водитель видел и ПОСЛЕ ОТМЕНЫ брони. Правило «сделки нет — контактов
     нет» действовало для телефона самого пассажира и было забыто для телефона мамы: можно
     было забронировать, отменить через минуту и оставить себе номер.

Проверено запросами (аудит 2026-08-12, волна 53).
"""
from __future__ import annotations

import json
from datetime import timedelta

from app.config import settings
from app.models import UserRole
from app.timeutil import utcnow
from conftest import upload_doc

RECEIVER_NAME = "Гөлнара Ахметова"
RECEIVER_PHONE = "+79995550001"
GUARDIAN_NAME = "Мама Гульнара"
GUARDIAN_PHONE = "+79995550002"
TO_ADDRESS = "ул. Мира 5, кв. 12"


def _seen(payload, needle: str) -> bool:
    """Ищем саму строку в ответе целиком — а не поле по имени: так ловится и копия в другом ключе."""
    return needle in json.dumps(payload, ensure_ascii=False)


def _courier(client, user_factory, tag):
    u = user_factory(tag, role=UserRole.passenger)
    selfie = upload_doc(client, u["auth"])
    assert client.post("/courier/apply", headers=u["auth"], json={
        "transport": "car", "full_name": "Курьер Курьеров", "car_plate": "А111АА102",
        "selfie_url": selfie, "rules_accepted": True,
    }).status_code == 200
    return u


def _parcel(client, sender) -> int:
    r = client.post("/parcels", headers=sender["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай", "size": "small",
        "description": "документы", "receiver_name": RECEIVER_NAME,
        "receiver_phone": RECEIVER_PHONE, "rules_accepted": True, "to_address": TO_ADDRESS,
    })
    assert r.status_code == 200, r.text
    return r.json()["id"]


def _booking_with_minor(client, user_factory, tag):
    drv = user_factory(f"{tag}Drv", role=UserRole.driver)
    pax = user_factory(f"{tag}Pax", role=UserRole.passenger)
    depart = (utcnow() + timedelta(hours=settings.local_tz_offset_hours, days=1)).replace(
        microsecond=0).isoformat()
    ride = client.post("/rides", headers=drv["auth"], json={
        "from_city": "Сибай", "to_city": "Уфа", "seats_total": 4, "price": 500,
        "depart_at": depart,
    }).json()
    b = client.post("/bookings", headers=pax["auth"], json={
        "ride_id": ride["id"], "seats": 1, "minor_passenger": True,
        "minor_guardian_name": GUARDIAN_NAME, "minor_guardian_phone": GUARDIAN_PHONE,
    })
    assert b.status_code == 200, b.text
    return drv, pax, b.json()["id"]


def test_имя_получателя_не_висит_в_открытой_ленте(client, user_factory):
    sender = user_factory("StrangerSender", role=UserRole.passenger)
    cour = _courier(client, user_factory, "StrangerCourier")
    _parcel(client, sender)

    feed = client.get("/parcels/available", headers=cour["auth"]).json()
    assert not _seen(feed, RECEIVER_NAME)
    assert not _seen(feed, RECEIVER_PHONE)
    assert not _seen(feed, TO_ADDRESS)


def test_принявший_курьер_получает_всё_чтобы_довезти(client, user_factory):
    """Страховка от перестраховки: взялся — значит должен знать, кому, куда и как дозвониться."""
    sender = user_factory("StrangerSender2", role=UserRole.passenger)
    cour = _courier(client, user_factory, "StrangerCourier2")
    pid = _parcel(client, sender)
    assert client.post(f"/parcels/{pid}/accept", headers=cour["auth"]).status_code == 200

    carrying = client.get("/parcels/carrying", headers=cour["auth"]).json()
    assert _seen(carrying, RECEIVER_NAME)
    assert _seen(carrying, RECEIVER_PHONE)
    assert _seen(carrying, TO_ADDRESS)


def test_отправитель_видит_свою_же_заявку_целиком(client, user_factory):
    """Это его собственные данные — он их и вписывал."""
    sender = user_factory("StrangerSender3", role=UserRole.passenger)
    _parcel(client, sender)
    mine = client.get("/parcels/mine", headers=sender["auth"]).json()
    assert _seen(mine, RECEIVER_NAME)
    assert _seen(mine, RECEIVER_PHONE)


def test_контакты_взрослого_водителю_до_поездки_нужны(client, user_factory):
    """Контроль: пока бронь жива, водитель видит, кому звонить — на этом он и решает,
    берёт ли ответственность за подростка."""
    drv, _pax, bid = _booking_with_minor(client, user_factory, "MinorLive")
    d = client.get(f"/bookings/{bid}/details", headers=drv["auth"]).json()
    assert _seen(d, GUARDIAN_PHONE)
    assert _seen(d, GUARDIAN_NAME)


def test_отменил_бронь_и_номер_мамы_у_водителя_не_остался(client, user_factory):
    drv, pax, bid = _booking_with_minor(client, user_factory, "MinorCancel")
    assert client.post(f"/bookings/{bid}/cancel", headers=pax["auth"]).status_code == 200

    d = client.get(f"/bookings/{bid}/details", headers=drv["auth"]).json()
    assert not _seen(d, GUARDIAN_PHONE)
    assert not _seen(d, GUARDIAN_NAME)


def test_попутчику_контакты_взрослого_не_показываем(client, user_factory):
    """Даже своя же бронь: пассажиру эти поля не отдаём — он их вписывал, но в ответе
    их не должно быть, чтобы копия чужого номера не разъезжалась по экранам."""
    _drv, pax, bid = _booking_with_minor(client, user_factory, "MinorPax")
    d = client.get(f"/bookings/{bid}/details", headers=pax["auth"]).json()
    assert not _seen(d, GUARDIAN_PHONE)
