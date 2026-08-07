"""Бейдж «N поездок» должен стоить поездки, а не трёх запросов.

Бейдж доверия — это и есть продукт «между своими»: по нему человек решает, садиться ли в
машину. А заработать его можно было так: `POST /rides` (хоть на 2030 год) → `POST /bookings`
→ `POST /bookings/{id}/driver-status {"status":"done"}`. Три запроса, ни метра пути. Сто
повторов с одного второго аккаунта — «100 поездок» на карточке в ленте.

Две планки, обе нужны:
  * завершить можно только НАЧАВШУЮСЯ поездку (иначе «сделано» до выезда — это просто ложь);
  * в бейдж от ОДНОГО попутчика идёт не больше TRIPS_PAIR_CAP поездок за окно — тот же приём,
    что защищает средний рейтинг от накрутки парой аккаунтов (services.RATING_PAIR_CAP).
Вторая планка страхует первую: закрыть бронь можно не только этой ручкой (завершение всей
поездки водителем, «доехал» пассажиром), а бейдж считается в одном месте.
"""
import uuid
from datetime import timedelta

from sqlmodel import Session, select

from app.db import engine
from app.models import Booking, BookingStatus, Ride, RideStatus, User, UserRole
from app.security import make_token
from app.services import TRIPS_PAIR_CAP, driver_trips_agg
from app.timeutil import utcnow

_n = {"i": 0}


def mk_user(name="Кеше", role=UserRole.passenger):
    _n["i"] += 1
    i = _n["i"]
    with Session(engine) as s:
        u = User(phone=f"real-badge-{i}", name=name, verified=True, role=role,
                 telegram_id=f"badge{i}")
        s.add(u)
        s.commit()
        s.refresh(u)
        return {"id": u.id, "token": make_token(u.id),
                "auth": {"Authorization": f"Bearer {make_token(u.id)}"}}


def _city():
    return "Город" + uuid.uuid4().hex[:8]


def _publish(client, drv, frm, to, depart="2030-01-01T10:00:00"):
    r = client.post("/rides", headers=drv["auth"], json={
        "from_city": frm, "to_city": to, "depart_at": depart, "seats_total": 3, "price": 300,
    })
    assert r.status_code == 200, r.text
    return r.json()


def _finished_trip(driver_id: int, passenger_id: int, days_ago: int = 1) -> int:
    """Состоявшаяся поездка: выехала в прошлом, бронь закрыта. Кладём прямо в БД —
    так же, как её закрыла бы ночная чистка или завершение поездки водителем."""
    with Session(engine) as s:
        ride = Ride(driver_id=driver_id, from_city=_city(), to_city=_city(),
                    depart_at=utcnow() - timedelta(days=days_ago), seats_total=3, seats_left=2,
                    price=300, status=RideStatus.active)
        s.add(ride)
        s.commit()
        s.refresh(ride)
        s.add(Booking(ride_id=ride.id, passenger_id=passenger_id, seats=1, price=300,
                      status=BookingStatus.done))
        s.commit()
        return ride.id


def _badge(driver_id: int) -> int:
    with Session(engine) as s:
        return driver_trips_agg(s, {driver_id}).get(driver_id, 0)


# --------------------------- планка 1: поездка должна начаться ---------------------------

def test_future_ride_cannot_be_finished(client):
    """«Завершить» поездку, которая ещё не выехала, нельзя — это не поездка, а три запроса."""
    drv = mk_user("БейджВод", role=UserRole.driver)
    pax = mk_user("БейджПас")
    ride = _publish(client, drv, _city(), _city())          # 2030 год
    booking = client.post("/bookings", headers=pax["auth"],
                          json={"ride_id": ride["id"], "seats": 1}).json()

    r = client.post(f"/bookings/{booking['id']}/driver-status",
                    headers=drv["auth"], json={"status": "done"})
    assert r.status_code == 409, r.text
    assert r.json()["detail"]["ba"]                         # текст на двух языках
    with Session(engine) as s:
        assert s.get(Booking, booking["id"]).status == BookingStatus.pending


def test_departed_ride_can_be_finished(client):
    """Обратная сторона: поездка выехала — водитель закрывает бронь как раньше."""
    drv = mk_user("ВышедВод", role=UserRole.driver)
    pax = mk_user("ВышедПас")
    frm, to = _city(), _city()
    ride = _publish(client, drv, frm, to)
    booking = client.post("/bookings", headers=pax["auth"],
                          json={"ride_id": ride["id"], "seats": 1}).json()
    with Session(engine) as s:                              # время выезда наступило
        r = s.get(Ride, ride["id"])
        r.depart_at = utcnow() - timedelta(minutes=5)
        s.add(r)
        s.commit()

    r = client.post(f"/bookings/{booking['id']}/driver-status",
                    headers=drv["auth"], json={"status": "done"})
    assert r.status_code == 200, r.text
    with Session(engine) as s:
        assert s.get(Booking, booking["id"]).status == BookingStatus.done


# --------------------------- планка 2: бейдж считает честное ---------------------------

def test_badge_ignores_rides_that_never_departed(client):
    """Брони, закрытые на будущих поездках (другими путями — завершение поездки, «доехал»),
    в бейдж не идут: поездки не было."""
    drv = mk_user("ФейкВод", role=UserRole.driver)
    pax = mk_user("ФейкПас")
    with Session(engine) as s:
        for _ in range(3):
            ride = Ride(driver_id=drv["id"], from_city=_city(), to_city=_city(),
                        depart_at=utcnow() + timedelta(days=900), seats_total=3, seats_left=2,
                        price=300, status=RideStatus.active)
            s.add(ride)
            s.commit()
            s.refresh(ride)
            s.add(Booking(ride_id=ride.id, passenger_id=pax["id"], seats=1, price=300,
                          status=BookingStatus.done))
            s.commit()

    assert _badge(drv["id"]) == 0


def test_badge_counts_real_trips(client):
    """Страховка «не всё обнулили»: состоявшиеся поездки считаются как раньше."""
    drv = mk_user("ЧестВод", role=UserRole.driver)
    p1, p2 = mk_user("ЧестП1"), mk_user("ЧестП2")
    _finished_trip(drv["id"], p1["id"], days_ago=3)
    _finished_trip(drv["id"], p2["id"], days_ago=2)
    assert _badge(drv["id"]) == 2


def test_badge_counts_shared_ride_once(client):
    """Двое попутчиков в одной машине — это одна поездка, а не две (было и остаётся)."""
    drv = mk_user("ОбщВод", role=UserRole.driver)
    p1, p2 = mk_user("ОбщП1"), mk_user("ОбщП2")
    ride_id = _finished_trip(drv["id"], p1["id"])
    with Session(engine) as s:
        s.add(Booking(ride_id=ride_id, passenger_id=p2["id"], seats=1, price=300,
                      status=BookingStatus.done))
        s.commit()
    assert _badge(drv["id"]) == 1


def test_pair_cannot_farm_the_badge(client):
    """Два аккаунта катают друг друга по кругу — бейдж упирается в кап пары."""
    drv = mk_user("ПараВод", role=UserRole.driver)
    pax = mk_user("ПараПас")
    for i in range(TRIPS_PAIR_CAP + 5):
        _finished_trip(drv["id"], pax["id"], days_ago=1)
    assert _badge(drv["id"]) == TRIPS_PAIR_CAP


def test_pair_cap_does_not_punish_many_passengers(client):
    """Кап — на ПАРУ, а не на водителя: с разными попутчиками бейдж растёт свободно."""
    drv = mk_user("МногоВод", role=UserRole.driver)
    for i in range(TRIPS_PAIR_CAP + 5):
        _finished_trip(drv["id"], mk_user(f"МногоП{i}")["id"], days_ago=1)
    assert _badge(drv["id"]) == TRIPS_PAIR_CAP + 5


def test_pair_cap_is_a_window_not_a_lifetime(client):
    """Постоянный попутчик за годы — не накрутка: окно скользящее, старые поездки не запирают."""
    drv = mk_user("ОкноВод", role=UserRole.driver)
    pax = mk_user("ОкноПас")
    for i in range(TRIPS_PAIR_CAP):
        _finished_trip(drv["id"], pax["id"], days_ago=400 + i)     # прошлый год
    for i in range(TRIPS_PAIR_CAP):
        _finished_trip(drv["id"], pax["id"], days_ago=1)           # на этой неделе
    assert _badge(drv["id"]) == TRIPS_PAIR_CAP * 2
