"""Действия RS-05 после бронирования. Реальные HTTP, запись и повтор."""

from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta
from threading import Event
from uuid import uuid4

import httpx
import pytest
from sqlmodel import Session, select

from app import idempotency
from app.config import settings
from app.db import engine
from app.models import Booking, BookingStatus, Notification, Ride, TripShare, TrustedContact, UserRole
from app.routers import bookings, family
from app.timeutil import utcnow
from test_last_seat_race import _ride, _seats_left


@pytest.fixture
def trip(client, user_factory):
    driver = user_factory("TransitionDriver", role=UserRole.driver)
    passenger = user_factory("TransitionPassenger")
    ride_id = _ride(client, driver, seats=4)
    booking_id = _make_booking(client, passenger, ride_id)
    return driver, passenger, ride_id, booking_id


def _make_booking(client, passenger, ride_id):
    reply = client.post("/bookings", headers=passenger["auth"], json={"ride_id": ride_id, "seats": 1})
    assert reply.status_code == 200, reply.text
    return reply.json()["id"]


def _post(client, user, path, key, body=None):
    options = {"json": body} if body is not None else {}
    return client.post(path, headers={**user["auth"], "Idempotency-Key": key}, **options)


def _intent(client, user, key):
    reply = client.get(f"/command-intents/{key}", headers=user["auth"])
    assert reply.status_code == 200, reply.text
    return reply.json()


def _lose_reply(client, monkeypatch, path, send):
    original = client._transport.handle_request

    def drop(request):
        response = original(request)
        if request.method == "POST" and request.url.path == path:
            assert response.status_code == 200
            response.close()
            raise httpx.ReadError("reply lost after commit", request=request)
        return response

    with monkeypatch.context() as patch:
        patch.setattr(client._transport, "handle_request", drop)
        with pytest.raises(httpx.ReadError, match="after commit"):
            send()


def _notice_count(booking_id, title):
    with Session(engine) as session:
        return len(session.exec(select(Notification).where(
            Notification.ref_id == booking_id, Notification.title_ru == title,
        )).all())


def _departure(ride_id, depart_at):
    with Session(engine) as session:
        session.get(Ride, ride_id).depart_at = depart_at
        session.commit()


def _confirm(client, driver, booking_id):
    reply = client.post(f"/bookings/{booking_id}/confirm", headers=driver["auth"])
    assert reply.status_code == 200, reply.text


def _family_share(client, driver, passenger, ride_id, booking_id):
    _confirm(client, driver, booking_id)
    _departure(ride_id, utcnow() - timedelta(minutes=25))
    with Session(engine) as session:
        booking = session.get(Booking, booking_id)
        booking.status = BookingStatus.onboard
        booking.onboard_at = utcnow() - timedelta(minutes=10)
        contact = TrustedContact(user_id=passenger["id"], name="Тестовый близкий", phone="+79990001051")
        session.add(contact)
        session.flush()
        share = TripShare(booking_id=booking_id, contact_id=contact.id, token=uuid4().hex, last_status="sat")
        session.add(share)
        session.flush()
        share_id = share.id
        session.commit()
    return share_id


def test_lost_confirm_reply_then_cancellation_returns_current_status(client, trip, monkeypatch, user_factory):
    driver, passenger, ride_id, booking_id = trip
    path = f"/bookings/{booking_id}/confirm"
    key = uuid4().hex
    outsider = user_factory("TransitionOutsider")
    assert _post(client, outsider, path, key).status_code == 403
    assert _intent(client, outsider, key)["state"] == "unseen"
    _lose_reply(client, monkeypatch, path, lambda: _post(client, driver, path, key))
    assert _intent(client, driver, key)["current"]["status"] == "confirmed"
    assert _notice_count(booking_id, "Бронь подтверждена") == 1
    assert client.post(f"/bookings/{booking_id}/cancel", headers=passenger["auth"]).status_code == 200

    replay = _post(client, driver, path, key)
    assert replay.status_code == 200
    assert replay.json()["status"] == "cancelled" and replay.json()["id"] == booking_id
    assert replay.json()["boarding_code"] == "" and replay.json()["pickup_lat"] is None
    assert _notice_count(booking_id, "Бронь подтверждена") == 1 and _seats_left(ride_id) == 4


def test_cancel_preview_changes_at_threshold_and_requires_fresh_intention(client, trip, monkeypatch, user_factory):
    driver, passenger, ride_id, booking_id = trip
    _confirm(client, driver, booking_id)
    departure = utcnow() + timedelta(hours=3)
    _departure(ride_id, departure)
    threshold = departure - timedelta(minutes=settings.safety_late_cancel_before_depart_min)
    preview_path = f"/bookings/{booking_id}/cancel-preview"
    path = f"/bookings/{booking_id}/cancel"
    outsider = user_factory("PreviewOutsider")
    assert client.get(preview_path, headers=outsider["auth"]).status_code == 403
    monkeypatch.setattr(bookings, "utcnow", lambda: threshold - timedelta(seconds=1))
    early = client.get(preview_path, headers=passenger["auth"]).json()
    assert early["consequence"] == "early"
    monkeypatch.setattr(bookings, "utcnow", lambda: threshold)
    rejected_key, accepted_key = uuid4().hex, uuid4().hex
    payload = {"reason": "changed_mind", "expected_terms_key": early["terms_key"]}
    stale = _post(client, passenger, path, rejected_key, payload)
    assert stale.status_code == 409 and stale.json()["detail"]["code"] == "cancel_terms_changed"
    fresh = stale.json()["detail"]["preview"]
    assert fresh["consequence"] == "late" and fresh["terms_key"] != early["terms_key"]
    assert _intent(client, passenger, rejected_key)["state"] == "rejected"
    assert _seats_left(ride_id) == 3 and _notice_count(booking_id, "Бронь отменена") == 0

    revised = {**payload, "expected_terms_key": fresh["terms_key"]}
    assert _post(client, passenger, path, rejected_key, revised).json()["detail"]["code"] == "idempotency_key_reused"
    _lose_reply(client, monkeypatch, path, lambda: _post(client, passenger, path, accepted_key, revised))
    replay = _post(client, passenger, path, accepted_key, revised)
    assert replay.status_code == 200 and replay.json()["status"] == "cancelled"
    assert replay.json()["cancelled_at"] == threshold.isoformat()
    assert _seats_left(ride_id) == 4 and _notice_count(booking_id, "Бронь отменена") == 1
    replayed_refusal = _post(client, passenger, path, rejected_key, payload).json()["detail"]
    assert replayed_refusal["code"] == "cancel_terms_changed"
    assert replayed_refusal["preview"]["consequence"] == "terminal"
    terminal_key = uuid4().hex
    terminal = _post(client, passenger, path, terminal_key, payload)
    assert terminal.status_code == 409 and terminal.json()["detail"]["preview"]["consequence"] == "terminal"
    assert _intent(client, passenger, terminal_key)["state"] == "rejected"


def test_no_show_after_deadline_uses_new_key_and_releases_once(client, trip, monkeypatch):
    driver, _, ride_id, booking_id = trip
    _confirm(client, driver, booking_id)
    now = utcnow()
    _departure(ride_id, now)
    monkeypatch.setattr(bookings, "utcnow", lambda: now)
    path = f"/bookings/{booking_id}/no-show"
    early_key, applied_key = uuid4().hex, uuid4().hex
    early = _post(client, driver, path, early_key)
    assert early.status_code == 409 and early.json()["detail"]["code"] == "no_show_too_early"
    monkeypatch.setattr(bookings, "utcnow", lambda: now + bookings.NO_SHOW_WAIT + timedelta(seconds=1))
    assert _post(client, driver, path, early_key).json()["detail"]["code"] == "no_show_too_early"
    _lose_reply(client, monkeypatch, path, lambda: _post(client, driver, path, applied_key))
    replay = _post(client, driver, path, applied_key)
    assert replay.status_code == 200 and replay.json()["no_show"]
    assert _seats_left(ride_id) == 4 and _notice_count(booking_id, "Отмечена неявка") == 1


@pytest.mark.parametrize(("action", "status"), [("cancel", "cancelled"), ("complete", "done")])
def test_ride_terminal_command_commits_cascade_and_sends_once(
        client, trip, user_factory, monkeypatch, action, status):
    driver, _, ride_id, confirmed_id = trip
    second = user_factory("TransitionPending")
    pending_id = _make_booking(client, second, ride_id)
    _confirm(client, driver, confirmed_id)
    _departure(ride_id, utcnow() - timedelta(minutes=20))
    with Session(engine) as session:
        ride = session.get(Ride, ride_id)
        ride.waypoints = "ул. Ленина 5"  # Старый формат до проверки населённых пунктов.
        ride.stop_prices_json = "[100]"
        session.commit()
    key, path = uuid4().hex, f"/rides/{ride_id}/{action}"
    _lose_reply(client, monkeypatch, path, lambda: _post(client, driver, path, key))
    current = _intent(client, driver, key)["current"]
    assert current["status"] == status and current["waypoints"] == "" and current["stop_prices"] == []
    replay = _post(client, driver, path, key).json()
    assert replay["status"] == status and replay["waypoints"] == "" and replay["stop_prices"] == []
    assert _post(client, driver, path, uuid4().hex).json()["status"] == status
    with Session(engine) as session:
        assert session.get(Booking, confirmed_id).status.value == status
        assert session.get(Booking, pending_id).status == BookingStatus.cancelled
    assert _notice_count(confirmed_id, "Поездка отменена" if action == "cancel" else "Поездка завершена") == 1
    assert _notice_count(pending_id, "Поездка отменена" if action == "cancel" else "Бронь не подтвердили") == 1


def test_driver_phases_and_lost_boarding_reply_after_undo(client, trip, monkeypatch):
    driver, passenger, ride_id, booking_id = trip
    _confirm(client, driver, booking_id)
    _departure(ride_id, utcnow() - timedelta(minutes=5))
    path = f"/bookings/{booking_id}/driver-status"
    for phase, title in (("departed", "Водитель выехал"), ("arriving", "Водитель подъезжает")):
        key = uuid4().hex
        payload = {"status": phase}
        assert _post(client, driver, path, key, payload).json()["driver_phase"] == phase
        assert _post(client, driver, path, key, payload).json()["driver_phase"] == phase
        assert _notice_count(booking_id, title) == 1
    boarded = uuid4().hex
    _lose_reply(client, monkeypatch, path, lambda: _post(client, driver, path, boarded, {"status": "boarded"}))
    assert _intent(client, driver, boarded)["current"]["status"] == "onboard"
    request = client.post(f"/bookings/{booking_id}/boarded/undo", headers=driver["auth"])
    assert request.status_code == 200, request.text
    undo = client.post(f"/bookings/{booking_id}/boarded/undo/respond", headers=passenger["auth"], json={
        "request_id": request.json()["boarding_undo_request_id"], "confirm": True,
    })
    assert undo.status_code == 200, undo.text
    replay = _post(client, driver, path, boarded, {"status": "boarded"})
    assert replay.status_code == 200 and replay.json()["status"] == "confirmed"
    assert replay.json()["onboard_at"] is None and _notice_count(booking_id, "Водитель отметил посадку") == 1
    done = uuid4().hex
    _lose_reply(client, monkeypatch, path, lambda: _post(client, driver, path, done, {"status": "done"}))
    assert _post(client, driver, path, done, {"status": "done"}).json()["status"] == "done"
    assert _notice_count(booking_id, "Поездка завершена") == 1


@pytest.mark.parametrize(("action", "title"), [("confirm", "Бронь подтверждена"),
                                               ("driver-status", "Водитель подъезжает")])
def test_cancel_before_deferred_notice_suppresses_stale_push(client, trip, monkeypatch, action, title):
    driver, passenger, ride_id, booking_id = trip
    if action == "driver-status":
        _confirm(client, driver, booking_id)
        _departure(ride_id, utcnow() - timedelta(minutes=5))
    path, key = f"/bookings/{booking_id}/{action}", uuid4().hex
    payload = {"status": "arriving"} if action == "driver-status" else None
    real_push = bookings.push_notification
    entered, release = Event(), Event()

    def delayed_notice(*args, **kwargs):
        if args[3] == title:
            entered.set()
            assert release.wait(timeout=12)
            assert kwargs.get("record_if") is not None
        return real_push(*args, **kwargs)

    monkeypatch.setattr(bookings, "push_notification", delayed_notice)
    with ThreadPoolExecutor(max_workers=1) as pool:
        pending = pool.submit(_post, client, driver, path, key, payload)
        try:
            assert entered.wait(timeout=8)
            assert _intent(client, driver, key)["state"] == "applied"
            cancelled = client.post(f"/bookings/{booking_id}/cancel", headers=passenger["auth"])
            assert cancelled.status_code == 200, cancelled.text
        finally:
            release.set()
        assert pending.result(timeout=8).status_code == 200
    assert _notice_count(booking_id, title) == 0
    assert _post(client, driver, path, key, payload).json()["status"] == "cancelled"


def test_family_done_and_shares_rollback_with_receipt_then_send_once(client, trip, monkeypatch):
    driver, passenger, ride_id, booking_id = trip
    share_id = _family_share(client, driver, passenger, ride_id, booking_id)
    path, key = f"/bookings/{booking_id}/trip-status", uuid4().hex
    sent = []
    real_save = idempotency._save

    def record_sms(*args):
        with Session(engine) as observer:
            assert observer.get(Booking, booking_id).status == BookingStatus.done
            assert observer.get(TripShare, share_id).last_status == "done"
        assert _intent(client, passenger, key)["state"] == "applied"
        sent.append("done")

    def fail_before_commit(session, *args, **kwargs):
        real_save(session, *args, **kwargs)
        assert session.get(TripShare, share_id).last_status == "done"
        raise RuntimeError("receipt not committed")

    monkeypatch.setattr(family, "send_text", record_sms)
    with monkeypatch.context() as patch:
        patch.setattr(idempotency, "_save", fail_before_commit)
        with pytest.raises(RuntimeError, match="receipt not committed"):
            _post(client, passenger, path, key, {"status": "done"})
    assert sent == [] and _intent(client, passenger, key)["state"] == "unseen"
    with Session(engine) as session:
        assert session.get(Booking, booking_id).status == BookingStatus.onboard
        assert session.get(TripShare, share_id).last_status == "sat"

    _lose_reply(client, monkeypatch, path, lambda: _post(client, passenger, path, key, {"status": "done"}))
    response = _post(client, passenger, path, key, {"status": "done"})
    assert response.status_code == 200 and response.json()["status"] == "done"
    assert "token" not in response.json() and sent == ["done"]
    assert _post(client, passenger, path, uuid4().hex, {"status": "done"}).status_code == 200
    assert sent == ["done"]


def test_cancel_wins_before_deferred_arrived_sms_and_old_key_does_not_send(client, trip, monkeypatch):
    driver, passenger, ride_id, booking_id = trip
    share_id = _family_share(client, driver, passenger, ride_id, booking_id)
    path, key = f"/bookings/{booking_id}/trip-status", uuid4().hex
    entered, release = Event(), Event()
    real_send = family._send_committed_family_status
    sent = []
    monkeypatch.setattr(family, "send_text", lambda phone, message: sent.append(message))

    def delayed_send(*args):
        entered.set()
        assert release.wait(timeout=12)
        real_send(*args)

    monkeypatch.setattr(family, "_send_committed_family_status", delayed_send)
    with ThreadPoolExecutor(max_workers=1) as pool:
        pending = pool.submit(_post, client, passenger, path, key, {"status": "arrived"})
        try:
            assert entered.wait(timeout=8)
            with Session(engine) as session:
                assert session.get(TripShare, share_id).last_status == "arrived"
            assert _intent(client, passenger, key)["state"] == "applied"
            cancelled = _post(client, driver, f"/bookings/{booking_id}/cancel", uuid4().hex)
            assert cancelled.status_code == 200, cancelled.text
        finally:
            release.set()
        assert pending.result(timeout=8).status_code == 200
    assert len(sent) == 1 and "отменили в пути" in sent[0]
    assert _post(client, passenger, path, key, {"status": "arrived"}).json()["status"] == "cancelled"
    assert len(sent) == 1
    with Session(engine) as session:
        assert session.get(TripShare, share_id).last_status == "cancelled_onboard"


def test_in_progress_arrival_sms_precedes_cancel_sms(client, trip, monkeypatch):
    driver, passenger, ride_id, booking_id = trip
    _family_share(client, driver, passenger, ride_id, booking_id)
    arrival_started, release, cancel_committed = Event(), Event(), Event()
    sent = []
    real_cancel = bookings._notify_booking_cancelled

    def gateway(phone, text):
        if "доехал до места" in text:
            arrival_started.set()
            assert release.wait(timeout=12)
            sent.append("arrived")
        elif "отменили в пути" in text:
            sent.append("cancelled")

    def cancel_after_commit(*args):
        cancel_committed.set()
        return real_cancel(*args)

    monkeypatch.setattr(family, "send_text", gateway)
    monkeypatch.setattr(bookings, "_notify_booking_cancelled", cancel_after_commit)
    path, key = f"/bookings/{booking_id}/trip-status", uuid4().hex
    with ThreadPoolExecutor(max_workers=2) as pool:
        arrived = pool.submit(_post, client, passenger, path, key, {"status": "arrived"})
        try:
            assert arrival_started.wait(timeout=8)
            cancelled = pool.submit(_post, client, driver, f"/bookings/{booking_id}/cancel", uuid4().hex)
            assert cancel_committed.wait(timeout=8)
            assert sent == []
        finally:
            release.set()
        assert arrived.result(timeout=8).status_code == 200
        assert cancelled.result(timeout=8).status_code == 200
    assert sent == ["arrived", "cancelled"]
    assert _post(client, passenger, path, key, {"status": "arrived"}).json()["status"] == "cancelled"


@pytest.mark.skipif(engine.dialect.name != "sqlite", reason="SQLite releases the writer before each SMS")
def test_revoked_contact_id_reused_during_sms_does_not_receive_message(client, trip, user_factory, monkeypatch):
    driver, passenger, ride_id, booking_id = trip
    _family_share(client, driver, passenger, ride_id, booking_id)
    outsider = user_factory("ReusedContactOwner")
    with Session(engine) as session:
        contact = TrustedContact(user_id=passenger["id"], name="Второй контакт", phone="+79990001052")
        session.add(contact)
        session.flush()
        share = TripShare(booking_id=booking_id, contact_id=contact.id, token=uuid4().hex, last_status="sat")
        session.add(share)
        session.flush()
        old_contact_id, old_share_id = contact.id, share.id
        session.commit()

    first_started, release = Event(), Event()
    sent = []

    def gateway(phone, message):
        sent.append(phone)
        if len(sent) == 1:
            first_started.set()
            assert release.wait(timeout=10)

    monkeypatch.setattr(family, "send_text", gateway)
    path, key = f"/bookings/{booking_id}/trip-status", uuid4().hex
    with ThreadPoolExecutor(max_workers=1) as pool:
        pending = pool.submit(_post, client, passenger, path, key, {"status": "arrived"})
        try:
            assert first_started.wait(timeout=8)
            with Session(engine) as session:
                session.delete(session.get(TripShare, old_share_id))
                session.flush()
                session.delete(session.get(TrustedContact, old_contact_id))
                session.flush()
                session.add(TrustedContact(id=old_contact_id, user_id=outsider["id"],
                                           name="Чужой контакт", phone="+79990001053"))
                session.commit()
        finally:
            release.set()
        assert pending.result(timeout=8).status_code == 200
    assert sent == ["+79990001051"]
