"""Кому открыта моя поездка — видно, и закрыть можно в любой момент.

Аудит 2026-08-06, продолжение охоты. Отозвать доступ к живой поездке технически было можно
и раньше, но список выданных ссылок жил ТОЛЬКО в памяти экрана: у такси сервер отдавал его
по запросу, у попутки такой ручки не существовало вовсе. Значит, поделился с человеком,
свернул приложение — и отзывать стало нечего, хотя ссылка на твоё живое местоположение
продолжала работать до конца поездки. Кнопка «отозвать» есть, а нажать её не на чем.

Правило файла: у попутки тот же набор, что у такси — показать, кому открыто, и закрыть.
Список свой у каждого: чужие ссылки в него не попадают.
"""
from datetime import timedelta

from sqlmodel import Session

from app.db import engine
from app.models import Booking, BookingStatus, Ride, RideStatus, UserRole
from app.timeutil import utcnow


def _trip(driver_id: int, passenger_id: int):
    with Session(engine) as s:
        ride = Ride(driver_id=driver_id, from_city="Баймак", to_city="Сибай",
                    depart_at=utcnow() + timedelta(hours=2),
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


def _contact(client, user, name="Мама", phone="+79170000101"):
    r = client.post("/trusted-contacts", headers=user["auth"],
                    json={"name": name, "relation": "мама", "phone": phone})
    assert r.status_code in (200, 201), r.text
    return r.json()["id"]


def test_passenger_sees_whom_the_trip_is_open_to(client, user_factory):
    drv = user_factory("ShareDrv1", role=UserRole.driver)
    pax = user_factory("SharePax1")
    b = _trip(drv["id"], pax["id"])
    cid = _contact(client, pax)

    shared = client.post(f"/bookings/{b.id}/share", headers=pax["auth"], json={"contact_id": cid})
    assert shared.status_code == 200, shared.text

    listed = client.get(f"/bookings/{b.id}/shares", headers=pax["auth"])
    assert listed.status_code == 200, listed.text
    items = listed.json()
    assert len(items) == 1, f"список открытых ссылок пуст — отзывать будет нечего: {items}"
    assert items[0]["contact_id"] == cid
    assert items[0]["token"], "без токена ссылку не показать человеку"


def test_revoked_link_disappears_from_the_list(client, user_factory):
    """Отозвал — значит закрыто. Иначе человек думает, что закрыл, а его видно дальше."""
    drv = user_factory("ShareDrv2", role=UserRole.driver)
    pax = user_factory("SharePax2")
    b = _trip(drv["id"], pax["id"])
    cid = _contact(client, pax, name="Брат", phone="+79170000102")

    share_id = client.post(f"/bookings/{b.id}/share", headers=pax["auth"],
                           json={"contact_id": cid}).json()["id"]
    gone = client.delete(f"/bookings/{b.id}/share/{share_id}", headers=pax["auth"])
    assert gone.status_code == 200, gone.text

    left = client.get(f"/bookings/{b.id}/shares", headers=pax["auth"]).json()
    assert left == [], f"отозванная ссылка осталась в списке: {left}"


def test_the_list_is_only_mine(client, user_factory):
    """Водитель и посторонний не должны видеть, кому пассажир открыл поездку."""
    drv = user_factory("ShareDrv3", role=UserRole.driver)
    pax = user_factory("SharePax3")
    stranger = user_factory("ShareStranger")
    b = _trip(drv["id"], pax["id"])
    cid = _contact(client, pax, name="Сестра", phone="+79170000103")
    client.post(f"/bookings/{b.id}/share", headers=pax["auth"], json={"contact_id": cid})

    assert client.get(f"/bookings/{b.id}/shares", headers=drv["auth"]).status_code == 403
    assert client.get(f"/bookings/{b.id}/shares", headers=stranger["auth"]).status_code in (403, 404)


def test_empty_when_nothing_is_shared(client, user_factory):
    """Пусто — это пусто, а не ошибка: экран должен показать выбор контакта, а не сбой."""
    drv = user_factory("ShareDrv4", role=UserRole.driver)
    pax = user_factory("SharePax4")
    b = _trip(drv["id"], pax["id"])

    r = client.get(f"/bookings/{b.id}/shares", headers=pax["auth"])
    assert r.status_code == 200 and r.json() == []
