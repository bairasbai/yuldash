# -*- coding: utf-8 -*-
"""RS-02 А: гонка за место на участке — на настоящих замках PostgreSQL.

Пересекающиеся участки (Сибай→Ургаза и Сибай→Бурибай делят первое звено) за последнее
место — ровно одна 200. Непересекающиеся (Сибай→Ургаза и Бурибай→Акъяр) — обе 200.
На SQLite замков нет — детерминированные варианты той же гонки живут в
test_last_seat_race.py и test_booking_multiseat_retry.py; здесь — настоящий FOR UPDATE
на строке поездки и CAS по снимку (ride_seats.take).
"""
from __future__ import annotations

import threading

import pytest
from sqlmodel import Session, select

from app.db import engine
from app.models import Booking, BookingStatus, Ride, UserRole
from test_segment_seats import _mkride, _pax

pytestmark = [
    pytest.mark.skipif(engine.dialect.name != "postgresql", reason="гонка на замках — только PostgreSQL"),
    pytest.mark.timeout(60),
]


def _book_together(client, rid, bookings):
    """Все жмут «Забронировать» в один момент (барьер); у каждого — свои поля участка."""
    codes: list[int] = []
    lock = threading.Lock()
    barrier = threading.Barrier(len(bookings))

    def book(p, fields):
        barrier.wait(timeout=10)
        r = client.post("/bookings", headers=p["auth"], json={"ride_id": rid, "seats": 1, **fields})
        with lock:
            codes.append(r.status_code)

    threads = [threading.Thread(target=book, args=(p, f)) for p, f in bookings]
    for t in threads:
        t.start()
    for t in threads:
        t.join(timeout=20)
        assert not t.is_alive(), "booking worker did not finish"
    assert len(codes) == len(bookings)
    return codes


def _state(rid):
    with Session(engine) as s:
        r = s.get(Ride, rid)
        live = s.exec(select(Booking).where(Booking.ride_id == rid,
                                            Booking.status.in_((BookingStatus.pending, BookingStatus.confirmed, BookingStatus.onboard)))).all()
        return r.leg_free, len(live)


def test_overlapping_segments_share_one_last_seat_pg(client, user_factory):
    driver = _pax(user_factory, "PG гонка водитель", role=UserRole.driver)
    rid = _mkride(driver, seats=1)   # одно место на всех
    pair = [_pax(user_factory, "PG гонка p1"), _pax(user_factory, "PG гонка p2")]
    # Оба хотят первое звено (Сибай→Ургаза и Сибай→Бурибай пересекаются на нём).
    codes = _book_together(client, rid, [(p, {"pickup_stop": "Сибай", "dropoff_stop": "Ургаза"}) for p in pair])
    assert codes.count(200) == 1, codes
    leg_free, live = _state(rid)
    assert leg_free == "0,1,1" and live == 1


def test_disjoint_segments_both_fit_pg(client, user_factory):
    driver = _pax(user_factory, "PG гонка водитель", role=UserRole.driver)
    rid = _mkride(driver, seats=1)
    pair = [_pax(user_factory, "PG гонка p1"), _pax(user_factory, "PG гонка p2")]
    # Начало → первая остановка И вторая остановка → конец — в один момент, звенья не пересекаются.
    codes = _book_together(client, rid, [
        (pair[0], {"pickup_stop": "Сибай", "dropoff_stop": "Ургаза"}),
        (pair[1], {"pickup_stop": "Бурибай", "dropoff_stop": "Акъяр"}),
    ])
    assert codes == [200, 200], codes
    leg_free, live = _state(rid)
    assert leg_free == "0,1,0" and live == 2
