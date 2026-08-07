"""Пуш о сообщении должен вести в саму переписку — и в правильную.

Аудит 2026-08-06, продолжение охоты. Уведомление «Марат: подъезжаю» несло с собой тип
и номер, но открыть по ним было нечего: приложение эти данные клало в интент и никто их
не читал. Человек жал по уведомлению и оказывался на карте, а чат искал руками.

Вторая половина той же дыры — на сервере. Чат попутки и чат такси-заказа слали ОДИН
и тот же тип «chat», хотя номер внутри значил разное: в одном случае бронь, в другом заказ.
Пока тапом никуда не переходили, разницы не было. С переходом приложение открыло бы бронь
с номером заказа — то есть чужую поездку или пустоту.

Правило файла: у каждого чата свой тип, и в пуше есть номер, по которому его можно найти.
"""
from datetime import timedelta

from sqlmodel import Session

import app.routers.chat as chat_router
from app.db import engine
from app.models import (
    Booking, BookingStatus, InstantOrder, InstantOrderStatus, ParcelDelivery, Ride, RideStatus, UserRole,
)
from app.timeutil import utcnow


def _capture_push(monkeypatch):
    """Что именно уходит на телефон: (title, body, data)."""
    sent = []
    monkeypatch.setattr(
        chat_router, "send_push",
        lambda session, uid, title, body, data=None, **kw: sent.append((uid, title, body, data)),
    )
    return sent


def _live_booking(driver_id: int, passenger_id: int):
    with Session(engine) as s:
        ride = Ride(driver_id=driver_id, from_city="Баймак", to_city="Сибай",
                    depart_at=utcnow() + timedelta(hours=1),
                    seats_total=3, seats_left=2, price=300, status=RideStatus.active)
        s.add(ride)
        s.commit()
        s.refresh(ride)
        b = Booking(ride_id=ride.id, passenger_id=passenger_id, seats=1,
                    price=300, status=BookingStatus.confirmed)
        s.add(b)
        s.commit()
        s.refresh(b)
        return b


def _live_order(driver_id: int, passenger_id: int):
    with Session(engine) as s:
        o = InstantOrder(passenger_id=passenger_id, driver_id=driver_id,
                         status=InstantOrderStatus.accepted,
                         from_lat=52.59, from_lng=58.31, to_lat=52.72, to_lng=58.66,
                         from_text="Баймак", to_text="Сибай")
        s.add(o)
        s.commit()
        s.refresh(o)
        return o


def test_taxi_chat_push_has_its_own_type(client, user_factory, monkeypatch):
    drv = user_factory("PushChatDrv", role=UserRole.driver)
    pax = user_factory("PushChatPax")
    order = _live_order(drv["id"], pax["id"])
    sent = _capture_push(monkeypatch)

    r = client.post(f"/instant/orders/{order.id}/messages", headers=pax["auth"],
                    json={"text": "я у синих ворот"})
    assert r.status_code in (200, 201), r.text

    assert sent, "водителю не ушёл пуш о сообщении"
    data = sent[0][3] or {}
    assert data.get("type") == "order_chat", f"тип чата такси перепутан с попуткой: {data}"
    assert int(data.get("id")) == order.id, f"в пуше не тот номер: {data}"


def test_rideshare_chat_push_carries_the_booking(client, user_factory, monkeypatch):
    """У попутки тип остаётся прежним — но номер обязан быть номером БРОНИ."""
    drv = user_factory("PushChatDrv2", role=UserRole.driver)
    pax = user_factory("PushChatPax2")
    b = _live_booking(drv["id"], pax["id"])
    sent = []
    monkeypatch.setattr(
        chat_router, "push_notification",
        lambda *a, **kw: sent.append(kw.get("data") or {}),
    )

    r = client.post(f"/bookings/{b.id}/messages", headers=pax["auth"], json={"text": "выхожу"})
    assert r.status_code in (200, 201), r.text

    assert sent, "второй стороне не ушло уведомление о сообщении"
    assert sent[0].get("type") == "chat", f"тип чата попутки изменился: {sent[0]}"
    assert int(sent[0].get("id")) == b.id, f"в пуше не номер брони: {sent[0]}"


def test_parcel_chat_push_stays_separate(client, user_factory, monkeypatch):
    """Контроль третьего чата: у посылки свой тип и свой номер — схлопывать нельзя."""
    sender = user_factory("PushChatSender")
    courier = user_factory("PushChatCourier", role=UserRole.driver)
    with Session(engine) as s:
        p = ParcelDelivery(sender_id=sender["id"], courier_id=courier["id"], status="accepted",
                           from_city="Баймак", to_city="Сибай", size="medium",
                           receiver_name="Айгуль", receiver_phone="+79170000201")
        s.add(p)
        s.commit()
        s.refresh(p)
    sent = _capture_push(monkeypatch)

    r = client.post(f"/parcels/{p.id}/messages", headers=sender["auth"],
                    json={"text": "положил в пакет"})
    assert r.status_code in (200, 201), r.text

    assert sent, "курьеру не ушёл пуш о сообщении"
    data = sent[0][3] or {}
    assert data.get("type") == "parcel_chat", f"чат посылки схлопнулся с другим: {data}"
    assert int(data.get("id")) == p.id, f"в пуше не номер посылки: {data}"
