"""Создание многоместной брони: повтор, потеря ответа и два одновременных запроса.

Переиспользуем публикацию и счётчик из теста последнего места. Настоящие HTTP/SQL;
внешний push заменён приёмником. SQLite и PostgreSQL запускаются отдельно.
"""
from concurrent.futures import ThreadPoolExecutor
from threading import Barrier, Event

import httpx
import pytest
from sqlalchemy import event
from sqlmodel import Session, select

from app.db import engine
from app.models import Booking, BookingStatus, Notification, UserRole
from app.routers import bookings as bookings_mod
from test_last_seat_race import _ride, _seats_left


def _snapshot(ride_id):
    with Session(engine) as session:
        rows = session.exec(select(Booking).where(Booking.ride_id == ride_id)).all()
        notes = session.exec(select(Notification).where(
            Notification.ref_kind == "booking", Notification.ref_id.in_([b.id for b in rows]),
            Notification.title_ru == "Новая бронь",
        )).all()
        return [(b.id, b.seats, b.price, b.status) for b in rows], len(notes)


@pytest.mark.parametrize("lost_reply", [False, True])
def test_same_multiseat_retry_keeps_one_booking_and_notification(
        client, user_factory, monkeypatch, lost_reply):
    driver = user_factory("MultiRetryDriver", role=UserRole.driver)
    passenger = user_factory("MultiRetryPassenger")
    ride_id = _ride(client, driver, seats=4)
    sent = []
    monkeypatch.setattr("app.services.send_push", lambda *args, **kwargs: sent.append(args))
    payload = {"ride_id": ride_id, "seats": 3}
    real = client._transport.handle_request

    def drop_reply(request):
        reply = real(request)
        if request.url.path == "/bookings" and request.method == "POST":
            assert reply.status_code == 200
            reply.close()
            raise httpx.ReadError("Synthetic booking reply lost after commit", request=request)
        return reply

    if lost_reply:
        monkeypatch.setattr(client._transport, "handle_request", drop_reply)
        with pytest.raises(httpx.ReadError, match="after commit"):
            client.post("/bookings", headers=passenger["auth"], json=payload)
        monkeypatch.setattr(client._transport, "handle_request", real)
    else:
        first = client.post("/bookings", headers=passenger["auth"], json=payload)
        assert first.status_code == 200, first.text
    before = _snapshot(ride_id)
    assert len(before[0]) == 1 and before[1] == 1 and len(sent) == 1
    reply = client.post("/bookings", headers=passenger["auth"], json=payload)
    assert reply.status_code == 200, reply.text
    assert reply.json()["id"] == before[0][0][0]
    assert reply.json()["seats"] == 3 and reply.json()["price"] == 900
    assert _snapshot(ride_id) == before and len(sent) == 1
    assert _seats_left(ride_id) == 1


@pytest.mark.parametrize("status", [BookingStatus.pending, BookingStatus.confirmed, BookingStatus.onboard])
@pytest.mark.parametrize("retry_seats", [1, 3])
def test_different_seats_retry_conflicts_without_changing_existing(
        client, user_factory, status, retry_seats):
    driver = user_factory("MultiConflictDriver", role=UserRole.driver)
    passenger = user_factory("MultiConflictPassenger")
    ride_id = _ride(client, driver, seats=4)
    first = client.post("/bookings", headers=passenger["auth"], json={"ride_id": ride_id, "seats": 2})
    assert first.status_code == 200, first.text
    with Session(engine) as session:
        booking = session.get(Booking, first.json()["id"])
        booking.status = status
        session.add(booking)
        session.commit()
    before = _snapshot(ride_id)
    reply = client.post("/bookings", headers=passenger["auth"], json={"ride_id": ride_id, "seats": retry_seats})
    assert reply.status_code == 409, reply.text
    assert reply.json()["detail"]["code"] == "booking_seats_conflict"
    assert reply.json()["detail"]["ru"] and reply.json()["detail"]["ba"]
    assert _snapshot(ride_id) == before and _seats_left(ride_id) == 2


@pytest.mark.parametrize("second_seats", [2, 3])
def test_simultaneous_same_passenger_multiseat_retry_has_one_booking(client, user_factory, second_seats):
    driver = user_factory("MultiConcurrentDriver", role=UserRole.driver)
    passenger = user_factory("MultiConcurrentPassenger")
    ride_id = _ride(client, driver, seats=4)
    start = Barrier(2)
    before_update = Barrier(2)

    def hold_sqlite_reservation(conn, cursor, statement, parameters, context, executemany):
        # PG сериализует чтение через Ride FOR UPDATE. На SQLite оба запроса уже
        # прочли отсутствие прежней брони, затем одновременно пытаются занять места.
        if engine.dialect.name == "sqlite" and statement.startswith("UPDATE ride SET seats_left="):
            before_update.wait(timeout=10)

    def book(seats):
        start.wait(timeout=10)
        return client.post("/bookings", headers=passenger["auth"], json={"ride_id": ride_id, "seats": seats})

    event.listen(engine, "before_cursor_execute", hold_sqlite_reservation)
    try:
        with ThreadPoolExecutor(max_workers=2) as pool:
            futures = [pool.submit(book, seats) for seats in (2, second_seats)]
            replies = [f.result(timeout=20) for f in futures]
    finally:
        event.remove(engine, "before_cursor_execute", hold_sqlite_reservation)
    if second_seats == 2:
        assert [r.status_code for r in replies] == [200, 200], [r.text for r in replies]
        assert replies[0].json()["id"] == replies[1].json()["id"]
    else:
        assert sorted(r.status_code for r in replies) == [200, 409], [r.text for r in replies]
        conflict = next(r for r in replies if r.status_code == 409)
        assert conflict.json()["detail"]["code"] == "booking_seats_conflict"
    rows, notes = _snapshot(ride_id)
    assert len(rows) == 1 and notes == 1 and _seats_left(ride_id) == 4 - rows[0][1]


@pytest.mark.skipif(engine.dialect.name != "sqlite", reason="SQLite stale existing-read; PG locks Ride before it")
def test_front_seat_retry_after_concurrent_commit_returns_own_booking(client, user_factory, monkeypatch):
    driver = user_factory("MultiFrontRetryDriver", role=UserRole.driver)
    passenger = user_factory("MultiFrontRetryPassenger")
    ride_id = _ride(client, driver, seats=4)
    ready, release = Event(), Event()
    original = bookings_mod._front_seat_is_taken

    def hold_first_front_check(session, rid):
        if not ready.is_set():
            ready.set()
            assert release.wait(timeout=10), "front-seat check was not released"
        return original(session, rid)

    monkeypatch.setattr(bookings_mod, "_front_seat_is_taken", hold_first_front_check)
    payload = {"ride_id": ride_id, "seats": 2, "front_seat": True}
    with ThreadPoolExecutor(max_workers=1) as pool:
        held = pool.submit(client.post, "/bookings", headers=passenger["auth"], json=payload)
        try:
            assert ready.wait(timeout=10), "first request did not pass existing-read"
            committed = client.post("/bookings", headers=passenger["auth"], json=payload)
            assert committed.status_code == 200, committed.text
        finally:
            release.set()
        reply = held.result(timeout=20)
    assert reply.status_code == 200, reply.text
    assert reply.json()["id"] == committed.json()["id"]
    assert len(_snapshot(ride_id)[0]) == 1 and _snapshot(ride_id)[1] == 1
    assert _seats_left(ride_id) == 2
