# -*- coding: utf-8 -*-
"""Сценарии такси — пробелы аудита 2026-07-26.

Истории из жизни райцентра, которые раньше заканчивались плохо:
- у подъезда две белые «Лады», сверить машину нечем (госномер не показывался);
- «Ленина 12» в селе — пять домов без табличек, а комментарий водителю написать негде;
- сын из Уфы вызывает такси маме в Баймаке, водитель звонит сыну;
- водитель жмёт «я на месте» из дома → пассажиру капает платное ожидание и штраф;
- забыл телефон в машине → связаться нечем;
- «нужен документ о поездке» → чека за такси не существует.
"""
from datetime import timedelta

import pytest
from sqlmodel import Session

from app import models as M
from app.db import engine
from app.models import InstantOrderStatus as S, UserRole
from app.timeutil import utcnow


@pytest.fixture(autouse=True)
def _quiet(monkeypatch):
    monkeypatch.setattr("app.instant_service.send_push", lambda *a, **k: None)


def _order(passenger_id, driver_id=None, status=S.done, **kw):
    base = dict(passenger_id=passenger_id, driver_id=driver_id, status=status,
                from_lat=52.59, from_lng=58.31, to_lat=52.60, to_lng=58.32,
                from_text="Баймак, Ленина 12", to_text="Сибай",
                price_estimate=300, price_final=300, done_at=utcnow())
    base.update(kw)
    with Session(engine) as s:
        o = M.InstantOrder(**base)
        s.add(o); s.commit(); s.refresh(o)
        return o.id


def _driver_with_car(user_factory, plate="Х123УЕ102"):
    drv = user_factory("ГапВодитель", role=UserRole.driver)
    with Session(engine) as s:
        prof = M.DriverProfile(user_id=drv["id"], car_make="Lada", car_model="Vesta",
                               car_plate=plate, rating=4.9)
        s.add(prof); s.commit()
    return drv


# ============== Госномер и «как найти» ==============
def test_order_payload_shows_plate_comment_entrance(client, user_factory):
    """У подъезда две белые «Лады» — теперь можно сверить номер. И водитель видит,
    как найти пассажира, ещё до того, как чат станет доступен."""
    from app.instant_service import order_payload
    drv = _driver_with_car(user_factory)
    pax = user_factory("ГапПассажир")
    oid = _order(pax["id"], drv["id"], status=S.accepted,
                 comment="за магазином, синие ворота", entrance="2 подъезд")
    with Session(engine) as s:
        o = s.get(M.InstantOrder, oid)
        as_pax = order_payload(s, o, s.get(M.User, pax["id"]))
        as_drv = order_payload(s, o, s.get(M.User, drv["id"]))
    assert as_pax["driver_plate"] == "Х123УЕ102"
    assert as_drv["comment"] == "за магазином, синие ворота"
    assert as_drv["entrance"] == "2 подъезд"


def test_order_for_another_person_shows_their_phone(client, user_factory):
    """Сын из Уфы вызывает такси маме в Баймаке: водитель должен звонить МАМЕ."""
    from app.instant_service import order_payload
    drv = _driver_with_car(user_factory, plate="А777АА102")
    son = user_factory("ГапСын")
    oid = _order(son["id"], drv["id"], status=S.accepted,
                 for_name="Мама", for_phone="+79990009988")
    with Session(engine) as s:
        as_drv = order_payload(s, s.get(M.InstantOrder, oid), s.get(M.User, drv["id"]))
    assert as_drv["passenger_name"] == "Мама"
    assert as_drv["passenger_phone"] == "+79990009988"
    assert as_drv["for_other"] is True


# ============== «Я на месте» с гео-проверкой ==============
def test_arrived_rejects_faraway_driver(client, user_factory):
    """Раньше кнопку жали из дома: пассажиру капало платное ожидание, потом штраф и страйк."""
    drv = _driver_with_car(user_factory, plate="В001ВВ102")
    pax = user_factory("ГапЖдун")
    oid = _order(pax["id"], drv["id"], status=S.accepted)
    far = client.post(f"/instant/orders/{oid}/arrived", headers=drv["auth"],
                      json={"lat": 54.73, "lng": 55.95})     # Уфа — 300+ км от точки подачи
    assert far.status_code == 409
    near = client.post(f"/instant/orders/{oid}/arrived", headers=drv["auth"],
                       json={"lat": 52.591, "lng": 58.311})  # рядом с подачей
    assert near.status_code == 200, near.text


def test_arrived_without_coords_still_works(client, user_factory):
    """Старый клиент координат не шлёт, GPS в селе пропадает — работу не ломаем."""
    drv = _driver_with_car(user_factory, plate="С002СС102")
    pax = user_factory("ГапБезГПС")
    oid = _order(pax["id"], drv["id"], status=S.accepted)
    assert client.post(f"/instant/orders/{oid}/arrived", headers=drv["auth"]).status_code == 200


# ============== Чек, наличные, забытые вещи ==============
def test_taxi_receipt(client, user_factory):
    """«Мне на работе нужен документ о поездке» — раньше дать было нечего."""
    drv = _driver_with_car(user_factory, plate="Е003ЕЕ102")
    pax = user_factory("ГапЧек")
    stranger = user_factory("ГапЧужой")
    oid = _order(pax["id"], drv["id"], payment_method="cash", paid=True)
    r = client.get(f"/instant/orders/{oid}/receipt", headers=pax["auth"])
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["amount"] == 300 and body["paid"] is True
    assert body["from_text"] and body["to_text"] and body["driver_name"]
    assert "phone" not in str(body), "телефоны в квитанцию не кладём"
    assert client.get(f"/instant/orders/{oid}/receipt", headers=stranger["auth"]).status_code == 403


def test_driver_can_confirm_cash(client, user_factory):
    """Пассажир вышел и закрыл приложение — раньше заказ навсегда оставался «не оплачен»."""
    drv = _driver_with_car(user_factory, plate="К004КК102")
    pax = user_factory("ГапНал")
    oid = _order(pax["id"], drv["id"])
    r = client.post(f"/instant/orders/{oid}/cash-received", headers=drv["auth"])
    assert r.status_code == 200 and r.json()["status"] == "paid"
    with Session(engine) as s:
        o = s.get(M.InstantOrder, oid)
        assert o.paid is True and o.payment_method == "cash"
    # Идемпотентно и только водитель заказа.
    assert client.post(f"/instant/orders/{oid}/cash-received", headers=drv["auth"]).json()["status"] == "already_paid"
    assert client.post(f"/instant/orders/{oid}/cash-received", headers=pax["auth"]).status_code == 403


def test_lost_item_reopens_chat(client, user_factory):
    """Забыл телефон на заднем сиденье: чат снова открыт на 48 часов (раньше — только чтение)."""
    drv = _driver_with_car(user_factory, plate="М005ММ102")
    pax = user_factory("ГапЗабыл")
    oid = _order(pax["id"], drv["id"])
    # До нажатия писать в чат нельзя — поездка завершена.
    assert client.post(f"/instant/orders/{oid}/messages", headers=pax["auth"],
                       json={"text": "забыл телефон"}).status_code == 409
    r = client.post(f"/instant/orders/{oid}/lost-item", headers=pax["auth"])
    assert r.status_code == 200, r.text
    with Session(engine) as s:
        assert s.get(M.InstantOrder, oid).lost_item_until > utcnow() + timedelta(hours=47)
    ok = client.post(f"/instant/orders/{oid}/messages", headers=pax["auth"],
                     json={"text": "забыл телефон на заднем сиденье"})
    assert ok.status_code == 200, ok.text
