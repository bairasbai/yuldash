# -*- coding: utf-8 -*-
"""Телефон второй стороны не остаётся открытым навсегда (аудит 2026-08-07).

Витрина заказа раскрывала контакты по статусу: `accepted/arriving/onboard/done`. Первые три
проходят, а `done` заказ остаётся `done` вечно — значит водитель ВЕЧНО видел имя и телефон
пассажирки, которую вёз год назад, просто открыв старый заказ.

Приложение при этом само утверждает обратное: ручка «забыл вещь в машине» объясняет, что
«телефон второй стороны виден только пока заказ активен», и ради этого открывает чат ещё на
48 часов. То есть задумано было именно окно — в коде его не оказалось.

Почему это важно именно в Юлдаше. Приложение «между своими» в райцентре, где все друг друга
знают: тут номер женщины, которую подвезли ночью, — это не строчка в базе, а её жизнь.
152-ФЗ требует того же: персональные данные хранятся и показываются ровно столько, сколько
нужно для цели, ради которой их собрали.

Окно — те же 48 часов, что у «забытой вещи», плюс продление, пока она заявлена: вернуть
телефон с заднего сиденья надо, а держать номер открытым вечно — нет.
"""
from datetime import timedelta

import pytest
from sqlmodel import Session

from app import instant_service as isv
from app.db import engine
from app.models import DriverProfile, InstantOrder, InstantOrderStatus as S, User, UserRole
from app.timeutil import utcnow

ORIG = (52.591, 58.317)
DEST = (52.716, 58.664)


@pytest.fixture(autouse=True)
def _quiet(monkeypatch):
    monkeypatch.setattr("app.instant_service.send_push", lambda *a, **k: None)


@pytest.fixture
def finished(client, user_factory):
    """Завершённая поездка с настоящими телефонами обеих сторон."""
    pax = user_factory("ТелефонПассажир")
    drv = user_factory("ТелефонВодитель", role=UserRole.driver)
    pax_phone = f"+7999111{pax['id']:04d}"
    drv_phone = f"+7999222{drv['id']:04d}"
    with Session(engine) as s:
        s.get(User, pax["id"]).phone = pax_phone
        s.get(User, drv["id"]).phone = drv_phone
        s.add(DriverProfile(user_id=drv["id"], car_make="Lada", car_model="Vesta",
                            car_plate="У555УУ102", rating=4.9))
        o = InstantOrder(passenger_id=pax["id"], driver_id=drv["id"], status=S.done,
                         from_lat=ORIG[0], from_lng=ORIG[1], to_lat=DEST[0], to_lng=DEST[1],
                         from_text="Баймак", to_text="Сибай",
                         price_estimate=300, price_final=300, done_at=utcnow())
        s.add(o)
        s.commit()
        s.refresh(o)
        return {"id": o.id, "pax": pax, "drv": drv,
                "pax_phone": pax_phone, "drv_phone": drv_phone}


def _shift_done(order_id: int, days: int):
    with Session(engine) as s:
        o = s.get(InstantOrder, order_id)
        o.done_at = utcnow() - timedelta(days=days)
        s.add(o)
        s.commit()


def test_phones_close_after_the_window(client, finished):
    """Поездка была год назад — номера пассажирки в старом заказе быть не должно."""
    _shift_done(finished["id"], days=365)

    as_drv = client.get(f"/instant/orders/{finished['id']}", headers=finished["drv"]["auth"])
    as_pax = client.get(f"/instant/orders/{finished['id']}", headers=finished["pax"]["auth"])

    assert as_drv.status_code == 200 and as_pax.status_code == 200
    assert finished["pax_phone"] not in as_drv.text, "водитель видит телефон пассажира спустя год"
    assert finished["drv_phone"] not in as_pax.text, "пассажир видит телефон водителя спустя год"
    # Сама поездка из истории не пропадает — закрываются только контакты.
    assert as_pax.json()["status"] == "done" and as_pax.json()["price_final"] == 300


def test_phones_stay_right_after_the_trip(client, finished):
    """Сразу после поездки связаться надо: «забыл сумку», «не рассчитались» — телефон на месте."""
    r = client.get(f"/instant/orders/{finished['id']}", headers=finished["drv"]["auth"])
    assert r.status_code == 200
    assert r.json()["passenger_phone"] == finished["pax_phone"]
    p = client.get(f"/instant/orders/{finished['id']}", headers=finished["pax"]["auth"])
    assert p.json()["driver_phone"] == finished["drv_phone"]


def test_lost_item_reopens_the_phone_too(client, finished):
    """Заявил забытую вещь по старой поездке — контакты снова открыты, пока идёт поиск вещи."""
    _shift_done(finished["id"], days=30)
    with Session(engine) as s:
        o = s.get(InstantOrder, finished["id"])
        o.lost_item_until = utcnow() + timedelta(hours=48)
        s.add(o)
        s.commit()

    r = client.get(f"/instant/orders/{finished['id']}", headers=finished["drv"]["auth"])
    assert r.json()["passenger_phone"] == finished["pax_phone"]


def test_phones_open_during_the_trip(client, user_factory):
    """Живая поездка — контакты открыты, как и были."""
    pax = user_factory("ТелефонЖивойПас")
    drv = user_factory("ТелефонЖивойВод", role=UserRole.driver)
    phone = f"+7999333{pax['id']:04d}"
    with Session(engine) as s:
        s.get(User, pax["id"]).phone = phone
        o = InstantOrder(passenger_id=pax["id"], driver_id=drv["id"], status=S.onboard,
                         from_lat=ORIG[0], from_lng=ORIG[1], to_lat=DEST[0], to_lng=DEST[1],
                         from_text="Баймак", to_text="Сибай", price_estimate=300)
        s.add(o)
        s.commit()
        s.refresh(o)
        oid = o.id

    r = client.get(f"/instant/orders/{oid}", headers=drv["auth"])
    assert r.json()["passenger_phone"] == phone


def test_order_for_another_person_closes_too(client, user_factory):
    """Заказ «для мамы»: её телефон живёт в самом заказе — он закрывается по тому же окну."""
    son = user_factory("ТелефонСын")
    drv = user_factory("ТелефонВодитель2", role=UserRole.driver)
    with Session(engine) as s:
        o = InstantOrder(passenger_id=son["id"], driver_id=drv["id"], status=S.done,
                         from_lat=ORIG[0], from_lng=ORIG[1], to_lat=DEST[0], to_lng=DEST[1],
                         from_text="Баймак", to_text="Сибай", price_estimate=300, price_final=300,
                         for_name="Мама", for_phone="+79990009988",
                         done_at=utcnow() - timedelta(days=365))
        s.add(o)
        s.commit()
        s.refresh(o)
        oid = o.id

    r = client.get(f"/instant/orders/{oid}", headers=drv["auth"])
    assert "+79990009988" not in r.text, "телефон мамы виден водителю спустя год"
