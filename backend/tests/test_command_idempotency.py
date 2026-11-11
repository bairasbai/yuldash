"""Сохранённое намерение создания брони. HTTP и реальные транзакции обоих движков."""

from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta
from hashlib import sha256
from threading import Barrier, Event

import httpx
import pytest
from sqlmodel import Session, select

from app import idempotency
from app.db import engine
from app.models import Block, Booking, CommandReceipt, Ride, SafetyProfile, TextFlag, UserRole
from app.routers import bookings, rides
from app.timeutil import utcnow
from test_booking_multiseat_retry import _snapshot
from test_last_seat_race import _ride, _seats_left


KEY = "2feff2d8-5afb-4cf8-b306-b09f0e0a8a17"


@pytest.fixture
def case(client, user_factory):
    driver = user_factory("CommandDriver", role=UserRole.driver)
    passenger = user_factory("CommandPassenger")
    ride_id = _ride(client, driver, seats=4)
    return driver, passenger, ride_id


def _post(client, passenger, payload, key=KEY):
    return client.post("/bookings", headers={**passenger["auth"], "Idempotency-Key": key}, json=payload)


def _get(client, passenger, key=KEY):
    return client.get(f"/command-intents/{key}", headers=passenger["auth"])


def _close_body(payload):
    body = bookings.BookIn.model_validate(payload)
    return {
        "scope": idempotency.Scope.BOOKING_CREATE.value,
        "target_id": body.ride_id,
        "payload_hash": idempotency.fingerprint(idempotency.Scope.BOOKING_CREATE, body.ride_id, body),
    }


def _close(client, passenger, payload):
    return client.post(f"/command-intents/{KEY}/close", headers=passenger["auth"], json=_close_body(payload))


def _receipt(session, passenger):
    return session.exec(select(CommandReceipt).where(
        CommandReceipt.user_id == passenger["id"], CommandReceipt.request_key == KEY,
    )).one_or_none()


def test_lost_reply_then_driver_declines_returns_same_booking(client, case, monkeypatch):
    driver, passenger, ride_id = case
    payload = {"ride_id": ride_id, "seats": 2, "meeting": {"note": "У знакомого входа"}}
    real_transport = client._transport.handle_request

    def drop_reply(request):
        reply = real_transport(request)
        if request.url.path == "/bookings" and request.method == "POST":
            assert reply.status_code == 200
            with Session(engine) as reader:
                receipt = _receipt(reader, passenger)
                assert receipt.state == "applied"
                assert reader.get(Booking, receipt.ref_id).seats == 2
            reply.close()
            raise httpx.ReadError("after commit", request=request)
        return reply

    with monkeypatch.context() as patch:
        patch.setattr(client._transport, "handle_request", drop_reply)
        with pytest.raises(httpx.ReadError, match="after commit"):
            _post(client, passenger, payload)

    rows, notices = _snapshot(ride_id)
    assert len(rows) == notices == 1 and _seats_left(ride_id) == 2
    booking_id = rows[0][0]
    refusal = client.post(f"/bookings/{booking_id}/cancel", headers=driver["auth"])
    assert refusal.status_code == 200, refusal.text

    reply = _post(client, passenger, payload)
    assert reply.status_code == 200, reply.text
    booking = reply.json()
    assert booking["id"] == booking_id and booking["status"] == "cancelled" and booking["declined"]
    assert booking["pickup_note"] == booking["boarding_code"] == ""
    assert booking["pickup_lat"] is None and booking["meeting_proposal"] is None
    assert _seats_left(ride_id) == 4 and len(_snapshot(ride_id)[0]) == 1
    view = _get(client, passenger).json()
    assert view["state"] == "applied" and view["ref_id"] == booking_id
    assert view["current"]["status"] == "cancelled"
    assert _get(client, driver).json() == dict(
        state="unseen", scope=None, ref_kind=None, ref_id=None, result_code=None, current=None,
    )


def test_close_first_reserves_no_seat_and_other_account_cannot_see_receipt(client, case):
    driver, passenger, ride_id = case
    payload = {"ride_id": ride_id, "seats": 1}
    assert _get(client, passenger).json()["state"] == "unseen"
    closed = _close(client, passenger, payload)
    assert closed.status_code == 200 and closed.json()["state"] == "closed_no_effect"
    assert _close(client, passenger, payload).json() == closed.json()
    assert _close(client, passenger, {**payload, "seats": 2}).json()["detail"]["code"] == "idempotency_key_reused"
    assert _post(client, passenger, payload).json()["detail"]["code"] == "idempotency_closed_no_effect"
    assert _get(client, driver).json()["state"] == "unseen"
    assert _snapshot(ride_id) == ([], 0) and _seats_left(ride_id) == 4


def _wire_close(client, actor, key, scope, target, canonical_body):
    target_json = "null" if target is None else str(target)
    envelope = f'{{"body":{canonical_body},"scope":"{scope}","target_id":{target_json}}}'
    return client.post(f"/command-intents/{key}/close", headers=actor["auth"], json={
        "scope": scope, "target_id": target, "payload_hash": sha256(envelope.encode()).hexdigest(),
    })


def test_wire_close_matches_client_hash_and_rejects_changed_input(client, case):
    _, passenger, ride_id = case
    key = f"cmd1_{KEY}"
    canonical = f'{{"meeting":{{"lat":54,"lon":56,"note":"У входа"}},"ride_id":{ride_id},"seats":2}}'
    closed = _wire_close(client, passenger, key, "booking.create", ride_id, canonical)
    assert closed.status_code == 200 and closed.json()["state"] == "closed_no_effect", closed.text
    headers = {**passenger["auth"], "Idempotency-Key": key, "Content-Type": "application/json"}
    wire = f'{{"seats":2,"ride_id":{ride_id},"meeting":{{"note":"У входа","lon":5.6e1,"lat":54.0}}}}'
    matching = client.post("/bookings", headers=headers, content=wire.encode())
    assert matching.status_code == 409
    assert matching.json()["detail"]["code"] == "idempotency_closed_no_effect"
    changed = client.post("/bookings", headers=headers, content=wire.replace("54.0", "54.1").encode())
    assert changed.status_code == 409 and changed.json()["detail"]["code"] == "idempotency_key_reused"
    assert _snapshot(ride_id) == ([], 0) and _seats_left(ride_id) == 4


def test_wire_publication_close_preserves_original_date_text(client, user_factory):
    driver = user_factory("WirePublication", role=UserRole.driver)
    key = f"cmd1_{KEY}"
    canonical = ('{"depart_at":"2030-01-01T10:00:00.120Z","from_city":"Баймак",'
                 '"price":300,"seats_total":3,"to_city":"Сибай"}')
    closed = _wire_close(client, driver, key, "ride.create", None, canonical)
    assert closed.status_code == 200 and closed.json()["state"] == "closed_no_effect", closed.text
    headers = {**driver["auth"], "Idempotency-Key": key, "Content-Type": "application/json"}
    matching = client.post("/rides", headers=headers, content=canonical.encode())
    assert matching.status_code == 409 and matching.json()["detail"]["code"] == "idempotency_closed_no_effect"
    changed = client.post("/rides", headers=headers, content=canonical.replace(".120Z", ".120000Z").encode())
    assert changed.status_code == 409 and changed.json()["detail"]["code"] == "idempotency_key_reused"
    with Session(engine) as reader:
        assert reader.exec(select(Ride).where(Ride.driver_id == driver["id"])).first() is None


def test_wire_cancel_close_distinguishes_empty_object_from_no_body(client, case):
    _, passenger, ride_id = case
    booking = _post(client, passenger, {"ride_id": ride_id})
    assert booking.status_code == 200, booking.text
    booking_id = booking.json()["id"]
    key = f"cmd1_{KEY}"
    closed = _wire_close(client, passenger, key, "booking.cancel", booking_id, "null")
    assert closed.status_code == 200 and closed.json()["state"] == "closed_no_effect", closed.text
    headers = {**passenger["auth"], "Idempotency-Key": key}
    path = f"/bookings/{booking_id}/cancel"
    no_body = client.post(path, headers=headers)
    assert no_body.status_code == 409 and no_body.json()["detail"]["code"] == "idempotency_closed_no_effect"
    explicit_null = client.post(path, headers={**headers, "Content-Type": "application/json"}, content="null")
    assert explicit_null.status_code == 409 and explicit_null.json()["detail"]["code"] == "idempotency_closed_no_effect"
    empty_object = client.post(path, headers=headers, json={})
    assert empty_object.status_code == 409 and empty_object.json()["detail"]["code"] == "idempotency_key_reused"
    assert _seats_left(ride_id) == 3


@pytest.mark.parametrize("invalid", [
    '"seats":1,"seats":2',
    '"extra":NaN',
    r'"extra":"\ud800"',
    '"extra":' + "[" * 33 + "0" + "]" * 33,
])
def test_invalid_prefixed_body_creates_no_receipt_or_booking(client, case, invalid):
    _, passenger, ride_id = case
    key = f"cmd1_{KEY}"
    response = client.post("/bookings", headers={
        **passenger["auth"], "Idempotency-Key": key, "Content-Type": "application/json",
    }, content=f'{{"ride_id":{ride_id},{invalid}}}')
    assert response.status_code == 422
    assert response.json()["detail"]["code"] == "idempotency_payload_invalid"
    assert _get(client, passenger, key).json()["state"] == "unseen"
    assert _snapshot(ride_id) == ([], 0) and _seats_left(ride_id) == 4


def test_failure_writing_receipt_rolls_back_reservation_and_booking(client, case, monkeypatch):
    _, passenger, ride_id = case
    payload = {"ride_id": ride_id, "seats": 2}
    real_save = idempotency._save

    def fail_after_flush(session, *args, **kwargs):
        real_save(session, *args, **kwargs)
        assert session.exec(select(Booking).where(Booking.ride_id == ride_id)).one().seats == 2
        raise RuntimeError("forced failure before commit")

    with monkeypatch.context() as patch:
        patch.setattr(idempotency, "_save", fail_after_flush)
        with pytest.raises(RuntimeError, match="before commit"):
            _post(client, passenger, payload)

    assert _get(client, passenger).json()["state"] == "unseen"
    assert _snapshot(ride_id) == ([], 0) and _seats_left(ride_id) == 4
    reply = _post(client, passenger, payload)
    assert reply.status_code == 200, reply.text
    assert _seats_left(ride_id) == 2 and len(_snapshot(ride_id)[0]) == 1


def test_accidental_commit_in_mutator_cannot_escape_receipt_transaction(client, case, monkeypatch):
    _, passenger, ride_id = case
    real = bookings._book

    def accidental_commit(body, user, session, **kwargs):
        result = real(body, user, session, **kwargs)
        session.commit()
        return result

    monkeypatch.setattr(bookings, "_book", accidental_commit)
    with pytest.raises(RuntimeError, match="attempted to commit"):
        _post(client, passenger, {"ride_id": ride_id, "seats": 1})
    assert _get(client, passenger).json()["state"] == "unseen"
    assert _snapshot(ride_id) == ([], 0) and _seats_left(ride_id) == 4


def test_rejected_terms_are_saved_and_transient_rate_limit_is_not(client, case, monkeypatch):
    _, passenger, ride_id = case
    payload = {"ride_id": ride_id, "seats": 1, "expected_terms_version": 999}
    assert _post(client, passenger, payload, key="bad key").json()["detail"]["code"] == "idempotency_key_invalid"
    for _ in range(2):
        rejected = _post(client, passenger, payload)
        assert rejected.status_code == 409 and rejected.json()["detail"]["code"] == "ride_terms_changed"
    result = _get(client, passenger).json()
    assert result["state"] == "rejected" and result["current"] is None and result["ref_id"] is None
    assert _post(client, passenger, {"ride_id": ride_id, "seats": 1}).json()["detail"]["code"] == "idempotency_key_reused"
    assert _snapshot(ride_id) == ([], 0)

    retry_key = KEY + "-retry"
    new_payload = {"ride_id": ride_id, "seats": 1}
    with monkeypatch.context() as patch:
        patch.setattr(bookings.settings, "flood_create_per_minute", 0)
        assert _post(client, passenger, new_payload, key=retry_key).status_code == 429
    assert _get(client, passenger, key=retry_key).json()["state"] == "unseen"
    assert _post(client, passenger, new_payload, key=retry_key).status_code == 200


def test_front_seat_refusal_cannot_take_seat_that_later_opens(client, case, user_factory):
    driver, passenger, ride_id = case
    other = user_factory("CommandFrontSeat")
    payload = {"ride_id": ride_id, "seats": 1, "front_seat": True}
    first = client.post("/bookings", headers=other["auth"], json=payload)
    assert first.status_code == 200, first.text
    blocked = _post(client, passenger, payload)
    assert blocked.status_code == 409 and blocked.json()["detail"]["code"] == "front_seat_taken"
    assert client.post(f"/bookings/{first.json()['id']}/cancel", headers=driver["auth"]).status_code == 200
    assert _seats_left(ride_id) == 4
    assert _post(client, passenger, payload).json()["detail"]["code"] == "front_seat_taken"
    assert _get(client, passenger).json()["state"] == "rejected"
    assert len(_snapshot(ride_id)[0]) == 1 and _seats_left(ride_id) == 4


def test_replay_survives_pause_but_honors_block_and_expiration(client, case, monkeypatch):
    driver, passenger, ride_id = case
    payload = {"ride_id": ride_id, "seats": 1}
    first = _post(client, passenger, payload)
    assert first.status_code == 200, first.text
    booking_id = first.json()["id"]

    with Session(engine) as session:
        created = _receipt(session, passenger).created_at
        session.add(SafetyProfile(user_id=passenger["id"], suspended_until=utcnow() + timedelta(days=1)))
        session.commit()
    assert _post(client, passenger, payload).json()["id"] == booking_id
    assert _post(client, passenger, payload, key=KEY + "-new").status_code == 403
    with Session(engine) as session:
        block = Block(user_id=driver["id"], blocked_user_id=passenger["id"])
        session.add(block)
        session.commit()
        assert _post(client, passenger, payload).status_code == 403
        assert _get(client, passenger).status_code == 403
        session.delete(block)
        session.commit()

    with monkeypatch.context() as patch:
        patch.setattr(idempotency, "utcnow", lambda: created + timedelta(hours=24) - timedelta(seconds=1))
        assert _post(client, passenger, payload).json()["id"] == booking_id
        patch.setattr(idempotency, "utcnow", lambda: created + timedelta(hours=24))
        assert _post(client, passenger, payload).json()["detail"]["code"] == "idempotency_key_expired"
        patch.setattr(idempotency, "utcnow", lambda: created + timedelta(hours=49))
        assert _post(client, passenger, payload).json()["detail"]["code"] == "idempotency_key_expired"
        assert _get(client, passenger).json()["ref_id"] == booking_id
    assert len(_snapshot(ride_id)[0]) == 1 and _seats_left(ride_id) == 3


def test_moderation_and_notification_run_only_for_new_booking(client, case, monkeypatch):
    _, passenger, ride_id = case
    calls = []
    real_moderate = bookings.moderate_text
    real_notify = bookings._notify_new_booking

    def classify(*args, **kwargs):
        kind = real_moderate(*args, **kwargs)
        assert kind
        calls.append("moderate")
        return kind

    def notify(*args, **kwargs):
        calls.append("notify")
        real_notify(*args, **kwargs)

    monkeypatch.setattr(bookings, "moderate_text", classify)
    monkeypatch.setattr(bookings, "_notify_new_booking", notify)
    payload = {"ride_id": ride_id, "seats": 1, "meeting": {"note": "назови код для входа"}}
    first = _post(client, passenger, payload)
    assert first.status_code == 200, first.text
    assert _post(client, passenger, payload).json()["id"] == first.json()["id"]
    with Session(engine) as session:
        flags = session.exec(select(TextFlag).where(TextFlag.user_id == passenger["id"])).all()
        assert len(flags) == 1 and flags[0].ref_id == first.json()["id"]
    assert calls == ["moderate", "notify"] and _snapshot(ride_id)[1] == 1


def test_notification_failure_after_commit_does_not_reopen_booking(client, case, monkeypatch):
    _, passenger, ride_id = case
    payload = {"ride_id": ride_id, "seats": 1}
    attempts = []

    def fail_notification(*args):
        attempts.append("send")
        raise RuntimeError("notification failed after commit")

    monkeypatch.setattr(bookings, "_notify_new_booking", fail_notification)
    first = _post(client, passenger, payload)
    assert first.status_code == 200, first.text
    result = _get(client, passenger).json()
    assert result["state"] == "applied" and result["ref_id"] == first.json()["id"]
    assert _post(client, passenger, payload).json()["id"] == result["ref_id"]
    assert attempts == ["send"] and _seats_left(ride_id) == 3


def test_deleted_booking_keeps_receipt_and_does_not_return_reused_id(client, case):
    _, passenger, ride_id = case
    payload = {"ride_id": ride_id, "seats": 1}
    first = _post(client, passenger, payload)
    assert first.status_code == 200, first.text
    booking_id = first.json()["id"]
    with Session(engine) as session:
        original = session.get(Booking, booking_id)
        created_at = original.created_at
        session.delete(original)
        session.commit()
    view = _get(client, passenger).json()
    assert view["state"] == "applied" and view["ref_id"] == booking_id and view["current"] is None
    assert view["result_code"] == "idempotency_result_gone"
    with Session(engine) as session:
        # Явно воспроизводим повтор номера и на PostgreSQL, где sequence сам его не выдаёт.
        session.add(Booking(id=booking_id, ride_id=ride_id, passenger_id=passenger["id"],
                            created_at=created_at + timedelta(seconds=1)))
        session.commit()
    assert _get(client, passenger).json() == view
    reply = _post(client, passenger, payload)
    assert reply.status_code == 409 and reply.json()["detail"]["code"] == "idempotency_result_gone"
    assert len(_snapshot(ride_id)[0]) == 1


def test_repeat_publication_after_short_dedupe_window_keeps_original_series(client, user_factory, monkeypatch):
    driver = user_factory("CommandSeries", role=UserRole.driver)
    payload = {"from_city": "Баймак", "to_city": "Сибай", "depart_at": "2030-01-01T10:00:00",
               "price": 300, "seats_total": 3, "recurrence": "weekly"}
    headers = {**driver["auth"], "Idempotency-Key": KEY}
    sent = []
    monkeypatch.setattr(rides, "notify_route_watchers", lambda session, ride: sent.append(ride.id))
    real_transport = client._transport.handle_request

    def drop_reply(request):
        response = real_transport(request)
        if request.url.path == "/rides" and request.method == "POST":
            assert response.status_code == 200
            response.close()
            raise httpx.ReadError("after commit", request=request)
        return response

    with monkeypatch.context() as patch:
        patch.setattr(client._transport, "handle_request", drop_reply)
        with pytest.raises(httpx.ReadError, match="after commit"):
            client.post("/rides", headers=headers, json=payload)
    with Session(engine) as session:
        series = session.exec(select(Ride).where(Ride.driver_id == driver["id"])).all()
        assert len(series) == 5
        receipt = _receipt(session, driver)
        first_id = receipt.ref_id
        later = receipt.created_at + timedelta(minutes=16)

    monkeypatch.setattr(rides, "utcnow", lambda: later)
    monkeypatch.setattr(idempotency, "utcnow", lambda: later)
    repeat = client.post("/rides", headers=headers, json=payload)
    assert repeat.status_code == 200 and repeat.json()["id"] == first_id
    reused = client.post("/rides", headers=headers, json={**payload, "price": 400})
    assert reused.status_code == 409 and reused.json()["detail"]["code"] == "idempotency_key_reused"
    with Session(engine) as session:
        series = session.exec(select(Ride).where(Ride.driver_id == driver["id"])).all()
        assert len(series) == 5
    assert sent == [first_id]


def test_slow_geocoding_does_not_block_close_and_publication_rechecks_key(client, user_factory, monkeypatch):
    driver = user_factory("CommandDelayedRide", role=UserRole.driver)
    payload = {"from_city": "Баймак", "to_city": "Сибай", "depart_at": "2030-01-01T10:00:00",
               "price": 300, "seats_total": 3}
    headers = {**driver["auth"], "Idempotency-Key": KEY}
    scope = idempotency.Scope.RIDE_CREATE
    body = rides.RideIn.model_validate(payload)
    close_body = {"scope": scope.value, "payload_hash": idempotency.fingerprint(scope, None, body)}
    started = Event()
    release = Event()

    def geocode(city):
        started.set()
        assert release.wait(timeout=10)
        return None

    monkeypatch.setattr(rides, "geocode_city", geocode)
    with ThreadPoolExecutor(max_workers=2) as pool:
        pending = pool.submit(client.post, "/rides", headers=headers, json=payload)
        try:
            assert started.wait(timeout=8)
            close = pool.submit(client.post, f"/command-intents/{KEY}/close",
                                headers=driver["auth"], json=close_body).result(timeout=4)
            assert close.status_code == 200 and close.json()["state"] == "closed_no_effect"
        finally:
            release.set()
        reply = pending.result(timeout=8)
    assert reply.status_code == 409 and reply.json()["detail"]["code"] == "idempotency_closed_no_effect"
    with Session(engine) as session:
        assert session.exec(select(Ride).where(Ride.driver_id == driver["id"])).all() == []


def test_close_and_eight_booking_writers_converge_on_one_receipt(client, case):
    _, passenger, ride_id = case
    payload = {"ride_id": ride_id, "seats": 1}
    barrier = Barrier(9)

    def participant(index):
        barrier.wait(timeout=10)
        return _close(client, passenger, payload) if index == 8 else _post(client, passenger, payload)

    with ThreadPoolExecutor(max_workers=9) as pool:
        *writes, close = list(pool.map(participant, range(9)))
    assert close.status_code == 200, close.text
    assert _get(client, passenger).json() == close.json()
    rows, notices = _snapshot(ride_id)
    if close.json()["state"] == "applied":
        assert len(rows) == notices == 1 and _seats_left(ride_id) == 3
        assert all(res.status_code == 200 and res.json()["id"] == rows[0][0] for res in writes)
    else:
        assert close.json()["state"] == "closed_no_effect"
        assert not rows and notices == 0 and _seats_left(ride_id) == 4
        assert all(res.status_code == 409 and res.json()["detail"]["code"] == "idempotency_closed_no_effect"
                   for res in writes)
