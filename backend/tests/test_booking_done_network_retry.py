"""Повтор «Завершить» после потерянного ответа не будит пассажира второй раз.

HTTP проходит настоящий ASGI/SQL-стек. В одном тесте транспорт TestClient отбрасывает
уже полученный успешный ответ, остальные запросы/записи/уведомления настоящие.
Внешний FCM заменён приёмником; PostgreSQL-гонки используют отдельные HTTP-сессии.
"""
from concurrent.futures import ThreadPoolExecutor
from threading import Barrier, Event

import httpx
import pytest
from sqlmodel import Session, select

from app.config import settings
from app.db import engine
from app.models import Booking, BookingStatus, Notification, Ride, UserRole
from app.routers import bookings as bookings_mod
from test_flows import _book, _publish, just_left


def _prepared(client, user_factory, *, state="confirmed", future=False):
    driver = user_factory("NetworkDoneDriver", role=UserRole.driver)
    passenger = user_factory("NetworkDonePassenger")
    extra = {} if future else {"depart_at": just_left()}
    ride = _publish(client, driver, **extra)
    booking = _book(client, passenger, ride["id"])
    if state in {"confirmed", "onboard"}:
        reply = client.post(f"/bookings/{booking['id']}/confirm", headers=driver["auth"])
        assert reply.status_code == 200, reply.text
    if state == "onboard":
        reply = _done(client, driver, booking["id"], status="boarded")
        assert reply.status_code == 200, reply.text
    return driver, passenger, ride, booking


def _done(client, driver, booking_id, *, status="done"):
    return client.post(f"/bookings/{booking_id}/driver-status", headers=driver["auth"],
                       json={"status": status})


def _state(booking_id):
    with Session(engine) as session:
        booking = session.get(Booking, booking_id)
        ride = session.get(Ride, booking.ride_id)
        return {"status": booking.status, "done_at": booking.done_at,
                "driver_phase": booking.driver_phase, "cancelled_by": booking.cancelled_by,
                "cancel_reason": booking.cancel_reason, "seats_left": ride.seats_left}


def _done_notes(booking_id):
    with Session(engine) as session:
        return session.exec(select(Notification.id).where(
            Notification.ref_kind == "booking", Notification.ref_id == booking_id,
            Notification.title_ru == "Поездка завершена",
        )).all()


def _capture_push(monkeypatch):
    sent = []
    monkeypatch.setattr(settings, "quiet_hours_to", 0)
    monkeypatch.setattr("app.services.send_push", lambda s, uid, title, body, data=None:
                        sent.append((uid, title, data)))
    return sent


@pytest.mark.parametrize("state", ["pending", "confirmed", "onboard"])
def test_lost_done_reply_retry_keeps_one_transition_and_one_notification(
        client, user_factory, monkeypatch, state):
    driver, passenger, _, booking = _prepared(client, user_factory, state=state)
    sent = _capture_push(monkeypatch)
    real_transport = client._transport.handle_request
    dropped = False

    def lose_first_reply(request):
        nonlocal dropped
        reply = real_transport(request)
        if not dropped and request.url.path == f"/bookings/{booking['id']}/driver-status":
            dropped = True
            assert reply.status_code == 200
            reply.close()
            raise httpx.ReadError("Synthetic reply lost after server commit", request=request)
        return reply

    monkeypatch.setattr(client._transport, "handle_request", lose_first_reply)
    with pytest.raises(httpx.ReadError, match="after server commit"):
        _done(client, driver, booking["id"])
    committed = _state(booking["id"])
    assert dropped and committed["status"] == BookingStatus.done
    assert committed["done_at"] is not None
    assert len(_done_notes(booking["id"])) == 1
    reply = _done(client, driver, booking["id"])
    assert reply.status_code == 200, reply.text
    assert reply.json() == {"ok": True, "status": "done"}
    assert _state(booking["id"]) == committed
    assert len(_done_notes(booking["id"])) == 1
    assert sent == [(passenger["id"], "Поездка завершена",
                     {"type": "booking", "id": str(booking["id"])})]


@pytest.mark.parametrize("cancel_by", ["passenger", "driver"])
def test_late_done_after_cancel_is_refused_without_false_done_or_notification(
        client, user_factory, monkeypatch, cancel_by):
    driver, passenger, _, booking = _prepared(client, user_factory)
    actor = passenger if cancel_by == "passenger" else driver
    cancelled = client.post(f"/bookings/{booking['id']}/cancel", headers=actor["auth"],
                            json={"reason": "changed_mind"})
    assert cancelled.status_code == 200, cancelled.text
    before = _state(booking["id"])
    assert before["status"] == BookingStatus.cancelled and before["done_at"] is None
    sent = _capture_push(monkeypatch)
    for _ in range(2):
        reply = _done(client, driver, booking["id"])
        assert reply.status_code == 409, reply.text
        assert reply.json()["detail"]["ru"] and reply.json()["detail"]["ba"]
    assert _state(booking["id"]) == before
    assert _done_notes(booking["id"]) == []
    assert sent == []


@pytest.mark.parametrize("actor_kind", ["passenger", "stranger"])
def test_driver_done_does_not_grant_another_role_mutation_rights(
        client, user_factory, monkeypatch, actor_kind):
    _, passenger, _, booking = _prepared(client, user_factory)
    actor = passenger if actor_kind == "passenger" else user_factory("NetworkDoneStranger")
    before = _state(booking["id"])
    sent = _capture_push(monkeypatch)
    reply = _done(client, actor, booking["id"])
    assert reply.status_code == 403, reply.text
    assert _state(booking["id"]) == before
    assert _done_notes(booking["id"]) == [] and sent == []


def test_driver_done_before_departure_window_stays_rejected_on_retry(
        client, user_factory, monkeypatch):
    driver, _, _, booking = _prepared(client, user_factory, future=True)
    before = _state(booking["id"])
    sent = _capture_push(monkeypatch)
    for _ in range(2):
        reply = _done(client, driver, booking["id"])
        assert reply.status_code == 409, reply.text
    assert _state(booking["id"]) == before
    assert _done_notes(booking["id"]) == [] and sent == []


@pytest.mark.skipif(engine.dialect.name != "postgresql", reason="Real PostgreSQL concurrent writers required")
def test_concurrent_preloaded_done_requests_commit_one_transition_and_notification(
        client, user_factory, monkeypatch):
    driver, passenger, _, booking = _prepared(client, user_factory)
    sent = _capture_push(monkeypatch)
    ready = Barrier(2)
    original = bookings_mod.booking_and_ride_for_user

    def both_read_before_write(session, booking_id, user):
        result = original(session, booking_id, user)
        if booking_id == booking["id"] and user.id == driver["id"]:
            ready.wait(timeout=10)
        return result

    monkeypatch.setattr(bookings_mod, "booking_and_ride_for_user", both_read_before_write)
    with ThreadPoolExecutor(max_workers=2) as pool:
        pending = [pool.submit(_done, client, driver, booking["id"]) for _ in range(2)]
        replies = [future.result(timeout=20) for future in pending]
    assert [reply.status_code for reply in replies] == [200, 200]
    assert all(reply.json() == {"ok": True, "status": "done"} for reply in replies)
    assert _state(booking["id"])["status"] == BookingStatus.done
    assert len(_done_notes(booking["id"])) == 1
    assert sent == [(passenger["id"], "Поездка завершена",
                     {"type": "booking", "id": str(booking["id"])})]


@pytest.mark.skipif(engine.dialect.name != "postgresql", reason="Real PostgreSQL stale-reader race required")
def test_done_that_read_before_cancel_cannot_restore_cancelled_booking(
        client, user_factory, monkeypatch):
    driver, passenger, _, booking = _prepared(client, user_factory)
    sent = _capture_push(monkeypatch)
    read = Event()
    release = Event()
    original = bookings_mod.booking_and_ride_for_user

    def hold_preloaded_done(session, booking_id, user):
        result = original(session, booking_id, user)
        if booking_id == booking["id"] and user.id == driver["id"]:
            read.set()
            assert release.wait(timeout=10), "held done request was not released"
        return result

    monkeypatch.setattr(bookings_mod, "booking_and_ride_for_user", hold_preloaded_done)
    with ThreadPoolExecutor(max_workers=1) as pool:
        future = pool.submit(_done, client, driver, booking["id"])
        try:
            assert read.wait(timeout=10), "done request did not preload the booking"
            cancelled = client.post(f"/bookings/{booking['id']}/cancel", headers=passenger["auth"],
                                    json={"reason": "changed_mind"})
            assert cancelled.status_code == 200, cancelled.text
            before = _state(booking["id"])
        finally:
            release.set()
        reply = future.result(timeout=20)
    assert reply.status_code == 409, reply.text
    assert before["status"] == BookingStatus.cancelled
    assert _state(booking["id"]) == before
    assert _done_notes(booking["id"]) == []
    assert not any(title == "Поездка завершена" for _, title, _ in sent)
