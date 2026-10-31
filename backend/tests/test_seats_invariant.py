# -*- coding: utf-8 -*-
"""RS-02 А: инвариант снимка мест — `leg_free[i] = всего − Σ живых броней на участке i`
после ЛЮБОЙ цепочки операций (бронь, отмена, неявка, пауза, удаление аккаунта, правка).

Снимок — кэш, правда — живые брони. Этот тест ловит любую дверь, которая меняет брони
и забывает обновить снимок (или обновляет его не на том участке).
"""
from datetime import timedelta

from sqlmodel import Session

from app import ride_seats
from app.account import delete_user_account
from app.db import engine
from app.models import Ride, User, UserRole
from app.safety_logic import cancel_upcoming_after_suspension
from app.timeutil import utcnow

from test_segment_seats import _book, _mkride, _pax


def _check(rid):
    with Session(engine) as session:
        ride = session.get(Ride, rid)
        truth = ride_seats.occupancy_snapshot(session, ride)
        assert ride_seats.legs_free(ride) == truth, (
            f"снимок {ride_seats.legs_free(ride)} расходится с живыми бронями {truth}")
        assert ride.seats_left == max(0, min(truth))
        assert ride.seats_free_max == max(0, max(truth))


def test_leg_free_matches_live_bookings_after_any_chain(client, user_factory):
    driver = _pax(user_factory, "Inv driver", role=UserRole.driver)
    rid = _mkride(driver, seats=3)
    p1, p2 = _pax(user_factory, "Inv p1"), _pax(user_factory, "Inv p2")
    p3, p4 = _pax(user_factory, "Inv p3"), _pax(user_factory, "Inv p4")

    # 1. Брони на разных участках.
    b1 = _book(client, p1, rid, pickup_stop="Сибай", dropoff_stop="Ургаза")
    _book(client, p2, rid, pickup_stop="Ургаза", dropoff_stop="Акъяр", seats=2)
    _check(rid)
    # 2. Отмена.
    client.post(f"/bookings/{b1}/cancel", headers=p1["auth"], json={})
    _check(rid)
    # 3. Ещё бронь + пауза другого пассажира (выезд в будущем — его бронь снимается).
    b3 = _book(client, p3, rid, pickup_stop="Сибай", dropoff_stop="Бурибай")
    with Session(engine) as session:
        cancel_upcoming_after_suspension(session, p2["id"])
    _check(rid)
    # 4. Неявка.
    client.post(f"/bookings/{b3}/confirm", headers=driver["auth"])
    with Session(engine) as session:
        ride = session.get(Ride, rid)
        ride.depart_at = utcnow() - timedelta(days=1)
        session.add(ride)
        session.commit()
    client.post(f"/bookings/{b3}/no-show", headers=driver["auth"])
    _check(rid)
    # 5. Удаление аккаунта с живой бронью (выезд возвращаем в будущее — иначе не забронировать).
    with Session(engine) as session:
        ride = session.get(Ride, rid)
        ride.depart_at = utcnow() + timedelta(days=4)
        session.add(ride)
        session.commit()
    _book(client, p4, rid, pickup_stop="Бурибай", dropoff_stop="Акъяр")
    with Session(engine) as session:
        delete_user_account(session, session.get(User, p4["id"]))
        session.commit()
    _check(rid)
    # 6. Правка условий рейса (цена вниз) — версия растёт, снимок остаётся правдой.
    ok = client.patch(f"/rides/{rid}", headers=driver["auth"], json={"price": 500})
    assert ok.status_code == 200, ok.text
    _check(rid)
