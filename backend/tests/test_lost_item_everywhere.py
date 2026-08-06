"""Забытая вещь: выход есть и у такси, и у попутки.

Аудит 2026-08-06. Эта дыра — наша собственная: закрыв чат попутки через сутки после поездки
(правило скопировали у такси), мы не скопировали спасательный выход. Телефон второй стороны
после поездки не виден, чат на чтение — значит забытый на заднем сиденье телефон было бы
не вернуть.

Правило файла: если чат закрывается, «забыл вещь» обязан открывать его снова — в обоих
сценариях, обеим сторонам, и только после завершения поездки.
"""
from datetime import timedelta

from sqlmodel import Session

from app.db import engine
from app.models import Booking, BookingStatus, Ride, RideStatus, UserRole
from app.timeutil import utcnow


def _done_ride_with_booking(driver_id: int, passenger_id: int, *, days_ago: int = 3):
    """Поездка, которая точно уехала давно → чат уже закрыт по общему правилу."""
    with Session(engine) as s:
        r = Ride(
            driver_id=driver_id, from_city="Сибай", to_city="Уфа",
            depart_at=utcnow() - timedelta(days=days_ago),
            seats_total=3, seats_left=2, price=500, status=RideStatus.done,
        )
        s.add(r)
        s.commit()
        s.refresh(r)
        b = Booking(ride_id=r.id, passenger_id=passenger_id, seats=1,
                    price=500, status=BookingStatus.done)
        s.add(b)
        s.commit()
        s.refresh(b)
        return r, b


def test_chat_is_closed_after_the_trip(client, user_factory):
    """Контроль: без «забыл вещь» переписка действительно закрыта — иначе тест ниже пустой."""
    drv = user_factory("LostDrv0", role=UserRole.driver)
    pax = user_factory("LostPax0")
    _r, b = _done_ride_with_booking(drv["id"], pax["id"])

    r = client.post(f"/bookings/{b.id}/messages", headers=pax["auth"], json={"text": "ау"})
    assert r.status_code == 409, f"чат должен быть закрыт, а он открыт: {r.status_code}"


def test_passenger_can_reopen_chat_for_a_lost_thing(client, user_factory):
    drv = user_factory("LostDrv1", role=UserRole.driver)
    pax = user_factory("LostPax1")
    _r, b = _done_ride_with_booking(drv["id"], pax["id"])

    ok = client.post(f"/bookings/{b.id}/lost-item", headers=pax["auth"])
    assert ok.status_code == 200, ok.text
    assert ok.json()["chat_open_until"]

    sent = client.post(f"/bookings/{b.id}/messages", headers=pax["auth"],
                       json={"text": "забыл телефон на заднем сиденье"})
    assert sent.status_code in (200, 201), f"чат не открылся: {sent.text}"


def test_driver_can_reopen_it_too(client, user_factory):
    """Водитель тоже находит вещи и ищет, чьи они."""
    drv = user_factory("LostDrv2", role=UserRole.driver)
    pax = user_factory("LostPax2")
    _r, b = _done_ride_with_booking(drv["id"], pax["id"])

    ok = client.post(f"/bookings/{b.id}/lost-item", headers=drv["auth"])
    assert ok.status_code == 200, ok.text
    sent = client.post(f"/bookings/{b.id}/messages", headers=drv["auth"],
                       json={"text": "нашёл перчатки, твои?"})
    assert sent.status_code in (200, 201), sent.text


def test_stranger_cannot_reopen_someone_elses_chat(client, user_factory):
    drv = user_factory("LostDrv3", role=UserRole.driver)
    pax = user_factory("LostPax3")
    stranger = user_factory("LostStranger")
    _r, b = _done_ride_with_booking(drv["id"], pax["id"])

    r = client.post(f"/bookings/{b.id}/lost-item", headers=stranger["auth"])
    assert r.status_code in (403, 404), f"посторонний открыл чужой чат: {r.status_code}"


def test_not_available_before_the_trip_is_over(client, user_factory):
    """До завершения поездки чат и так открыт — «забыл вещь» тут бессмыслен."""
    drv = user_factory("LostDrv4", role=UserRole.driver)
    pax = user_factory("LostPax4")
    with Session(engine) as s:
        r = Ride(driver_id=drv["id"], from_city="Сибай", to_city="Уфа",
                 depart_at=utcnow() + timedelta(days=1), seats_total=3, seats_left=2,
                 price=500, status=RideStatus.active)
        s.add(r); s.commit(); s.refresh(r)
        b = Booking(ride_id=r.id, passenger_id=pax["id"], seats=1, price=500,
                    status=BookingStatus.confirmed)
        s.add(b); s.commit(); s.refresh(b)
        bid = b.id

    resp = client.post(f"/bookings/{bid}/lost-item", headers=pax["auth"])
    assert resp.status_code == 409, f"открыли «забыл вещь» до конца поездки: {resp.status_code}"


def test_taxi_still_has_the_same_exit(client):
    """Симметрия: выход «забыл вещь» есть в обоих сценариях. Смотрим схему API —
    роутеры FastAPI подключаются лениво, и обход app.routes их не видит."""
    paths = client.get("/openapi.json").json()["paths"]
    assert "/instant/orders/{order_id}/lost-item" in paths, "у такси пропал выход «забыл вещь»"
    assert "/bookings/{booking_id}/lost-item" in paths, "у попутки нет выхода «забыл вещь»"
