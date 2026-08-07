"""Поездка считается состоявшейся ровно по одному правилу — во всех трёх местах
(независимая проверка аудита 2026-08-07).

Что нашёл независимый аудитор. Планку «завершить можно только начавшуюся поездку»
поставили на две двери из трёх. Третья — `POST /rides/{id}/complete`: она закрывает СРАЗУ
ВСЕ брони рейса и на время выезда не смотрела вовсе. Пять запросов — и поездка «состоялась»:

    POST /rides            {depart_at: 2030-01-01}   → 200
    POST /bookings         (второй аккаунт)          → 200
    POST /bookings/1/confirm                         → 200
    POST /rides/1/complete                           → 200  ← дверь была открыта
    GET  /drivers/1/public                           → trips_count: 1

Хуже того, сам бейдж считали ТРИ разные функции по трём разным правилам, и планка по
времени была только у одной — той, что рисует ленту. Витрина доверия и публичная карточка
считали любую закрытую бронь.

Почему это дороже, чем кажется. Публичную карточку водителя отдают **без входа**. Человек
открывает карточку незнакомого водителя, видит «5 поездок, рейтинг 5.0» и садится в машину.
Бейдж доверия и есть продукт «между своими» — ради него весь аудит и затевался.
"""
from datetime import timedelta

from sqlmodel import Session, select

from app.db import engine
from app.models import Booking, BookingStatus, UserRole
from app.safety_logic import completed_trips_for
from app.timeutil import utcnow

from test_api import just_left


def _future_ride_with_booking(client, user_factory, tag):
    """Рейс в 2030 году с подтверждённой бронью — заготовка накрутки."""
    drv = user_factory(f"{tag}Drv", role=UserRole.driver)
    pax = user_factory(f"{tag}Pax")
    ride = client.post("/rides", headers=drv["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай", "depart_at": "2030-01-01T10:00:00",
        "seats_total": 3, "price": 300, "comment": tag,
    }).json()
    bid = client.post("/bookings", headers=pax["auth"],
                      json={"ride_id": ride["id"], "seats": 1}).json()["id"]
    assert client.post(f"/bookings/{bid}/confirm", headers=drv["auth"]).status_code == 200
    return drv, pax, ride, bid


def test_third_door_is_closed(client, user_factory):
    """`/rides/{id}/complete` — та самая третья дверь."""
    drv, pax, ride, bid = _future_ride_with_booking(client, user_factory, "Third")

    r = client.post(f"/rides/{ride['id']}/complete", headers=drv["auth"])
    assert r.status_code == 409, f"рейс 2030 года завершён как состоявшийся: {r.text}"
    assert r.json()["detail"]["ba"], "ошибка обязана быть двуязычной"

    with Session(engine) as s:
        assert s.get(Booking, bid).status == BookingStatus.confirmed


def test_public_card_does_not_count_a_trip_that_never_departed(client, user_factory):
    """Публичную карточку отдают без входа — по ней решают, садиться ли в машину."""
    drv, pax, ride, bid = _future_ride_with_booking(client, user_factory, "Card")
    # Ставим бронь в done в обход API: моделируем любую другую дверь, которую могли забыть.
    with Session(engine) as s:
        b = s.get(Booking, bid)
        b.status = BookingStatus.done
        s.add(b)
        s.commit()

    pub = client.get(f"/drivers/{drv['id']}/public").json()
    assert pub["trips_count"] == 0, (
        f"поездка, которая ещё не выехала, посчитана в публичной карточке: {pub['trips_count']}"
    )


def test_trust_view_uses_the_same_rule(client, user_factory):
    """Витрина доверия считала по своему правилу — теперь по общему."""
    drv, pax, ride, bid = _future_ride_with_booking(client, user_factory, "Trust")
    with Session(engine) as s:
        b = s.get(Booking, bid)
        b.status = BookingStatus.done
        s.add(b)
        s.commit()
        assert completed_trips_for(s, drv["id"]) == 0, "витрина доверия считает невыехавшую поездку"
        assert completed_trips_for(s, pax["id"]) == 0


def test_all_three_places_agree_on_a_real_trip(client, user_factory):
    """Обратная сторона и главная мысль: на НАСТОЯЩЕЙ поездке все три места дают одно число."""
    drv = user_factory("RealDrv", role=UserRole.driver)
    pax = user_factory("RealPax")
    ride = client.post("/rides", headers=drv["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай", "depart_at": just_left(),
        "seats_total": 3, "price": 300, "comment": "Real",
    }).json()
    bid = client.post("/bookings", headers=pax["auth"],
                      json={"ride_id": ride["id"], "seats": 1}).json()["id"]
    assert client.post(f"/bookings/{bid}/confirm", headers=drv["auth"]).status_code == 200
    assert client.post(f"/rides/{ride['id']}/complete", headers=drv["auth"]).status_code == 200

    public_card = client.get(f"/drivers/{drv['id']}/public").json()["trips_count"]
    with Session(engine) as s:
        trust_view = completed_trips_for(s, drv["id"])

    assert public_card == 1, f"настоящая поездка не посчитана в карточке: {public_card}"
    assert trust_view == 1, f"настоящая поездка не посчитана в доверии: {trust_view}"
