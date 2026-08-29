"""Волна 196: детектор накрутки доверия смотрел только на попутку.

Юлдаш — приложение «между своими», и доверие тут и есть продукт: по рейтингу и числу
поездок женщина решает, садиться ли в машину. Накрутить это вдвоём дёшево — поездка
переводится в «завершена» на доверии, без сверки. Ровно для этого и написан
`app/collusion.py`: он ищет пары, которые гоняют друг другу оценки и поездки.

Искал он их только в попутке. Такси и доставка, где механика ровно та же, были для него
невидимы: оценки за такси не имеют `booking_id`, а счётчик поездок читал только брони.
Ноль тестов на весь модуль — угол взят по покрытию.
"""
from __future__ import annotations

from sqlmodel import Session

from app import collusion
from app.db import engine
from app.models import (
    Booking, BookingStatus, InstantOrder, InstantOrderStatus as S, ParcelDelivery, Rating,
    Ride, UserRole,
)
from app.timeutil import utcnow

ORIG = (52.591, 58.317)
DEST = (52.716, 58.664)


def _taxi_ride(s: Session, driver_id: int, passenger_id: int) -> InstantOrder:
    o = InstantOrder(passenger_id=passenger_id, driver_id=driver_id,
                     from_lat=ORIG[0], from_lng=ORIG[1], to_lat=DEST[0], to_lng=DEST[1],
                     status=S.done, price_estimate=200, price_final=200, done_at=utcnow())
    s.add(o)
    s.commit()
    s.refresh(o)
    return o


def _pool_ride(s: Session, driver_id: int, passenger_id: int) -> Booking:
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
    return b


def _parcel(s: Session, sender_id: int, courier_id: int) -> ParcelDelivery:
    p = ParcelDelivery(sender_id=sender_id, courier_id=courier_id,
                       from_city="Баймак", to_city="Сибай", status="delivered")
    s.add(p)
    s.commit()
    s.refresh(p)
    return p


def _signals_for(suspects: list, a: int, b: int) -> list:
    pair = sorted((a, b))
    for sus in suspects:
        if sus["pair"] == pair:
            return sus["signals"]
    return []


def test_pooling_farming_is_seen(client, user_factory):
    """Опора: в попутке детектор работал и раньше — с него и сверяем остальные двери."""
    a = user_factory("PoolFarmA", role=UserRole.driver)
    b = user_factory("PoolFarmB", role=UserRole.driver)
    with Session(engine) as s:
        for _ in range(4):
            ab = _pool_ride(s, a["id"], b["id"])          # A везёт B
            ba = _pool_ride(s, b["id"], a["id"])          # B везёт A
            s.add(Rating(booking_id=ab.id, rater_id=b["id"], ratee_id=a["id"], stars=5))
            s.add(Rating(booking_id=ba.id, rater_id=a["id"], ratee_id=b["id"], stars=5))
            s.commit()
        signals = _signals_for(collusion.find_suspects(s), a["id"], b["id"])

    assert any(x.startswith("mutual_5star") for x in signals), signals
    assert any(x.startswith("pair_trips") for x in signals), signals


def test_taxi_farming_is_seen_too(client, user_factory):
    """Те же двое, та же накрутка — но через ТАКСИ. Механика одна, значит и сигнал один."""
    a = user_factory("TaxiFarmA", role=UserRole.driver)
    b = user_factory("TaxiFarmB", role=UserRole.driver)
    with Session(engine) as s:
        for _ in range(4):
            ab = _taxi_ride(s, a["id"], b["id"])          # A везёт B
            ba = _taxi_ride(s, b["id"], a["id"])          # B везёт A
            s.add(Rating(order_id=ab.id, rater_id=b["id"], ratee_id=a["id"], stars=5))
            s.add(Rating(order_id=ba.id, rater_id=a["id"], ratee_id=b["id"], stars=5))
            s.commit()
        signals = _signals_for(collusion.find_suspects(s), a["id"], b["id"])

    assert any(x.startswith("mutual_5star") for x in signals), (
        f"взаимные пятёрки за такси детектор не увидел: {signals}"
    )
    assert any(x.startswith("pair_trips") for x in signals), (
        f"восемь поездок такси между теми же двумя детектор не посчитал: {signals}"
    )


def test_parcel_farming_is_seen_too(client, user_factory):
    """И через доставку: отправитель и курьер меняются ролями и оценками."""
    a = user_factory("ParcelFarmA", role=UserRole.driver)
    b = user_factory("ParcelFarmB", role=UserRole.driver)
    with Session(engine) as s:
        for _ in range(4):
            ab = _parcel(s, sender_id=b["id"], courier_id=a["id"])   # A везёт посылку B
            ba = _parcel(s, sender_id=a["id"], courier_id=b["id"])   # B везёт посылку A
            s.add(Rating(parcel_id=ab.id, rater_id=b["id"], ratee_id=a["id"], stars=5))
            s.add(Rating(parcel_id=ba.id, rater_id=a["id"], ratee_id=b["id"], stars=5))
            s.commit()
        signals = _signals_for(collusion.find_suspects(s), a["id"], b["id"])

    assert any(x.startswith("mutual_5star") for x in signals), (
        f"взаимные пятёрки за доставку детектор не увидел: {signals}"
    )
    assert any(x.startswith("pair_trips") for x in signals), (
        f"восемь доставок между теми же двумя детектор не посчитал: {signals}"
    )


def test_mixed_services_count_towards_one_pair(client, user_factory):
    """Умный накрутчик разложит по трём сервисам поровну, чтобы нигде не набрать порог.
    Пара одна — и считать её надо целиком."""
    a = user_factory("MixFarmA", role=UserRole.driver)
    b = user_factory("MixFarmB", role=UserRole.driver)
    with Session(engine) as s:
        ab1 = _pool_ride(s, a["id"], b["id"])
        ba1 = _pool_ride(s, b["id"], a["id"])
        ab2 = _taxi_ride(s, a["id"], b["id"])
        ba2 = _taxi_ride(s, b["id"], a["id"])
        s.add(Rating(booking_id=ab1.id, rater_id=b["id"], ratee_id=a["id"], stars=5))
        s.add(Rating(booking_id=ba1.id, rater_id=a["id"], ratee_id=b["id"], stars=5))
        s.add(Rating(order_id=ab2.id, rater_id=b["id"], ratee_id=a["id"], stars=5))
        s.add(Rating(order_id=ba2.id, rater_id=a["id"], ratee_id=b["id"], stars=5))
        s.commit()
        signals = _signals_for(collusion.find_suspects(s), a["id"], b["id"])

    assert any(x.startswith("mutual_5star") for x in signals), (
        f"по две пятёрки в каждом сервисе — вместе это четыре, порог взят: {signals}"
    )


def test_pair_counts_both_directions_as_one_pair(client, user_factory):
    """Возили друг друга по очереди: трижды он её, трижды она его. Порог — четыре поездки
    между парой, и брать его надо СУММОЙ: пара одна, кто в этот раз за рулём — неважно."""
    a = user_factory("BothWaysA", role=UserRole.driver)
    b = user_factory("BothWaysB", role=UserRole.driver)
    with Session(engine) as s:
        for _ in range(3):
            _taxi_ride(s, a["id"], b["id"])
            _taxi_ride(s, b["id"], a["id"])
        signals = _signals_for(collusion.find_suspects(s), a["id"], b["id"])

    assert any(x.startswith("pair_trips") for x in signals), (
        f"шесть поездок пары посчитали как две разные тройки: {signals}"
    )


def test_one_five_star_each_way_is_not_collusion(client, user_factory):
    """Сосед подвёз соседа, сосед подвёз соседа в ответ, оба поставили пятёрку.
    Это обычная жизнь села, а не сговор: порог — две поездки в каждую сторону."""
    a = user_factory("NeighbourA", role=UserRole.driver)
    b = user_factory("NeighbourB", role=UserRole.driver)
    with Session(engine) as s:
        ab = _taxi_ride(s, a["id"], b["id"])
        ba = _taxi_ride(s, b["id"], a["id"])
        s.add(Rating(order_id=ab.id, rater_id=b["id"], ratee_id=a["id"], stars=5))
        s.add(Rating(order_id=ba.id, rater_id=a["id"], ratee_id=b["id"], stars=5))
        s.commit()
        signals = _signals_for(collusion.find_suspects(s), a["id"], b["id"])

    assert not any(x.startswith("mutual_5star") for x in signals), (
        f"по одной пятёрке в каждую сторону — это не накрутка: {signals}"
    )


def test_one_sided_five_stars_are_not_collusion(client, user_factory):
    """Постоянный пассажир хвалит своего водителя, водитель его не оценивает.
    Сигнал называется ВЗАИМНЫЕ пятёрки — односторонних тут быть не должно."""
    drv = user_factory("OneSidedDrv", role=UserRole.driver)
    pax = user_factory("OneSidedPax")
    with Session(engine) as s:
        for _ in range(3):
            o = _taxi_ride(s, drv["id"], pax["id"])
            s.add(Rating(order_id=o.id, rater_id=pax["id"], ratee_id=drv["id"], stars=5))
            s.commit()
        signals = _signals_for(collusion.find_suspects(s), drv["id"], pax["id"])

    assert not any(x.startswith("mutual_5star") for x in signals), (
        f"хвалил один, а сговором записали обоих: {signals}"
    )


def test_honest_neighbours_are_not_flagged(client, user_factory):
    """Защита от ложного срабатывания: одна поездка и одна пятёрка — это не сговор."""
    a = user_factory("HonestA", role=UserRole.driver)
    b = user_factory("HonestB")
    with Session(engine) as s:
        o = _taxi_ride(s, a["id"], b["id"])
        s.add(Rating(order_id=o.id, rater_id=b["id"], ratee_id=a["id"], stars=5))
        s.commit()
        signals = _signals_for(collusion.find_suspects(s), a["id"], b["id"])

    assert signals == [], f"честную пару пометили как сговор: {signals}"
