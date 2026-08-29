"""Волна 195: в оффере водитель видит балл человека КАК ПАССАЖИРА.

Третья дверь того же класса (194 — рабочий рейтинг водителя, 186 — лестница курьера).
Перед тем как взять заказ, водитель видит карточку пассажира: рейтинг и число поездок.
Комментарий в коде так и говорит — «анонимный агрегат, чтобы решать по данным».

Число поездок там считалось правильно, по-пассажирски (завершённые заказы и брони, где
человек ехал). А рейтинг рядом — общий, вместе с оценками, которые он получил за рулём
собственной машины. Половина карточки про одну роль, половина про все сразу.
"""
from __future__ import annotations

import pytest
from sqlmodel import Session, select

from app import instant_service as isv
from app.db import engine
from app.models import (
    Booking, BookingStatus, DriverProfile, InstantOrder, InstantOrderStatus as S, Rating,
    Ride, UserRole,
)
from app.timeutil import utcnow

ORIG = (52.591, 58.317)
DEST = (52.716, 58.664)


def _ensure_profile(s: Session, user_id: int) -> None:
    if s.exec(select(DriverProfile).where(DriverProfile.user_id == user_id)).first() is None:
        s.add(DriverProfile(user_id=user_id))
        s.commit()


def _order(s: Session, driver_id: int, passenger_id: int) -> InstantOrder:
    _ensure_profile(s, driver_id)
    o = InstantOrder(passenger_id=passenger_id, driver_id=driver_id,
                     from_lat=ORIG[0], from_lng=ORIG[1], to_lat=DEST[0], to_lng=DEST[1],
                     status=S.done, price_estimate=200, price_final=200, done_at=utcnow())
    s.add(o)
    s.commit()
    s.refresh(o)
    return o


def _rate_as_passenger(s: Session, *, driver_id: int, passenger_id: int, stars: int) -> None:
    """Он ехал пассажиром — водитель поставил ему оценку."""
    o = _order(s, driver_id, passenger_id)
    s.add(Rating(order_id=o.id, rater_id=driver_id, ratee_id=passenger_id, stars=stars))
    s.commit()


def _rate_as_driver(s: Session, *, driver_id: int, passenger_id: int, stars: int) -> None:
    """Он был за рулём — пассажир поставил ему оценку."""
    o = _order(s, driver_id, passenger_id)
    s.add(Rating(order_id=o.id, rater_id=passenger_id, ratee_id=driver_id, stars=stars))
    s.commit()


def _rate_as_pooling_passenger(s: Session, *, driver_id: int, passenger_id: int, stars: int) -> None:
    """Он ехал попутчиком — водитель попутки поставил ему оценку."""
    _ensure_profile(s, driver_id)
    ride = Ride(driver_id=driver_id, from_city="Баймак", to_city="Сибай", seats=4,
                price=300, depart_at=utcnow())
    s.add(ride)
    s.commit()
    s.refresh(ride)
    b = Booking(ride_id=ride.id, passenger_id=passenger_id, seats=1, price=300,
                status=BookingStatus.done)
    s.add(b)
    s.commit()
    s.refresh(b)
    s.add(Rating(booking_id=b.id, rater_id=driver_id, ratee_id=passenger_id, stars=stars))
    s.commit()


def _rate_as_pooling_driver(s: Session, *, driver_id: int, passenger_id: int, stars: int) -> None:
    """Он вёз попутчиков — попутчик поставил ему оценку (та же бронь, другая сторона)."""
    _ensure_profile(s, driver_id)
    ride = Ride(driver_id=driver_id, from_city="Баймак", to_city="Сибай", seats=4,
                price=300, depart_at=utcnow())
    s.add(ride)
    s.commit()
    s.refresh(ride)
    b = Booking(ride_id=ride.id, passenger_id=passenger_id, seats=1, price=300,
                status=BookingStatus.done)
    s.add(b)
    s.commit()
    s.refresh(b)
    s.add(Rating(booking_id=b.id, rater_id=passenger_id, ratee_id=driver_id, stars=stars))
    s.commit()


def test_offer_card_ignores_ratings_he_got_behind_the_wheel(client, user_factory):
    """Гульнара — спокойная пассажирка: три пятёрки от водителей. Своя машина у неё
    старая, и как водителя попутки её оценили на единицы. Водитель, решающий, брать ли
    её ночной заказ, должен видеть, какая она ПАССАЖИРКА."""
    gulnara = user_factory("Gulnara")
    with Session(engine) as s:
        for i in range(3):                                   # ехала пассажиркой — пятёрки
            drv = user_factory(f"NicePaxDrv{i}", role=UserRole.driver)
            _rate_as_passenger(s, driver_id=drv["id"], passenger_id=gulnara["id"], stars=5)
        # Везла сама — единицы. Обе двери сразу: и такси, и попутка. Порознь дыру
        # видно не полностью — мутационный проход поймал: фильтр по попутке был не покрыт.
        for i in range(2):
            pax = user_factory(f"HerTaxiPax{i}")
            _rate_as_driver(s, driver_id=gulnara["id"], passenger_id=pax["id"], stars=1)
        for i in range(2):
            pax = user_factory(f"HerPoolPax{i}")
            _rate_as_pooling_driver(s, driver_id=gulnara["id"], passenger_id=pax["id"], stars=1)

        rating, trips = isv.passenger_stats(s, gulnara["id"])

    assert rating == pytest.approx(5.0), (
        f"водителю показали {rating} — в балл пассажира затекли оценки её вождения"
    )
    assert trips == 3, "поездок пассажиром — три (эта половина карточки и была верной)"


def test_offer_card_counts_pooling_and_taxi_passenger_ratings_together(client, user_factory):
    """Ехал и в такси, и в попутке — обе роли пассажирские, обе в один балл."""
    pax = user_factory("MixedPax")
    with Session(engine) as s:
        d1 = user_factory("MixDrvTaxi", role=UserRole.driver)
        d2 = user_factory("MixDrvPool", role=UserRole.driver)
        _rate_as_passenger(s, driver_id=d1["id"], passenger_id=pax["id"], stars=5)
        _rate_as_pooling_passenger(s, driver_id=d2["id"], passenger_id=pax["id"], stars=3)

        rating, trips = isv.passenger_stats(s, pax["id"])

    assert rating == pytest.approx(4.0)
    assert trips == 2


def test_offer_card_still_shows_a_difficult_passenger(client, user_factory):
    """Защита не сломана: кого водители оценивали плохо, тот и в карточке плохой."""
    pax = user_factory("HardPax")
    with Session(engine) as s:
        for i in range(3):
            drv = user_factory(f"HardDrv{i}", role=UserRole.driver)
            _rate_as_passenger(s, driver_id=drv["id"], passenger_id=pax["id"], stars=2)

        rating, trips = isv.passenger_stats(s, pax["id"])

    assert rating == pytest.approx(2.0)
    assert trips == 3


def test_offer_card_has_no_rating_before_the_first_ride(client, user_factory):
    """Новичок: оценок нет — показываем «нет данных», а не выдуманную пятёрку."""
    pax = user_factory("BrandNewPax")
    with Session(engine) as s:
        rating, trips = isv.passenger_stats(s, pax["id"])
    assert rating is None
    assert trips == 0
