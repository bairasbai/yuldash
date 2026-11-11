"""The new client paths require a wire key and run the existing receipt transaction."""

from datetime import timedelta
import json
from uuid import uuid4

import pytest
from sqlmodel import Session, select

from app import idempotency
from app.command_fingerprint import parse_body, wire_fingerprint
from app.db import engine
from app.models import Booking, CommandReceipt, Notification, Ride, TripShare, UserRole
from test_command_trip_transitions import _family_share, trip


PUBLISH = {"from_city": "Баймак", "to_city": "Сибай", "depart_at": "2030-01-02T10:00:00.120Z",
           "price": 300, "seats_total": 2, "recurrence": "weekly"}
BODYLESS = {"ride.cancel", "ride.complete", "booking.confirm", "booking.no_show"}


def _action(scope, trip):
    driver, passenger, ride, booking = trip
    ride_path = f"/rides/{ride}/commands/v1"
    booking_path = f"/bookings/{booking}/commands/v1"
    return {
        "ride.create": (driver, "/rides/commands/v1", None, PUBLISH),
        "ride.cancel": (driver, ride_path + "/cancel", ride, None),
        "ride.complete": (driver, ride_path + "/complete", ride, None),
        "booking.create": (passenger, "/bookings/commands/v1", ride, {"ride_id": ride, "seats": 1}),
        "booking.confirm": (driver, booking_path + "/confirm", booking, None),
        "booking.cancel": (passenger, booking_path + "/cancel", booking, {"reason": ""}),
        "booking.no_show": (driver, booking_path + "/no-show", booking, None),
        "booking.driver_status": (driver, booking_path + "/driver-status", booking, {"status": "arriving"}),
        "booking.trip_status": (passenger, booking_path + "/trip-status", booking, {"status": "arrived"}),
    }[scope]


def _body(body):
    return {"content": "null" if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")}


def _state():
    with Session(engine) as session:
        return (
            session.exec(select(Ride.id, Ride.status, Ride.seats_left, Ride.leg_free).order_by(Ride.id)).all(),
            session.exec(select(Booking.id, Booking.status, Booking.driver_phase, Booking.seats).order_by(Booking.id)).all(),
            session.exec(select(Notification.id).order_by(Notification.id)).all(),
            session.exec(select(CommandReceipt.id, CommandReceipt.state).order_by(CommandReceipt.id)).all(),
        )


@pytest.mark.parametrize("scope", [scope.value for scope in idempotency.Scope])
def test_every_v1_path_requires_wire_key_and_respects_a_prior_close(client, trip, scope):
    actor, path, target, body = _action(scope, trip)
    old = uuid4().hex
    key = "cmd1_" + uuid4().hex
    headers = {**actor["auth"], "Content-Type": "application/json"}
    before = _state()
    for extra in ({}, {"Idempotency-Key": old}):
        bad = client.post(path, headers={**headers, **extra}, **_body(body))
        assert bad.status_code == 422 and bad.json()["detail"]["code"] == "idempotency_key_invalid"
    assert _state() == before

    raw = _body(body)["content"]
    raw = raw.encode("utf-8") if isinstance(raw, str) else raw
    closed = client.post(f"/command-intents/{key}/close", headers=actor["auth"], json={
        "scope": scope, "target_id": target,
        "payload_hash": wire_fingerprint(scope, target, parse_body(raw)),
    })
    assert closed.status_code == 200 and closed.json()["state"] == "closed_no_effect", closed.text
    delayed = client.post(path, headers={**headers, "Idempotency-Key": key}, **_body(body))
    assert delayed.status_code == 409 and delayed.json()["detail"]["code"] == "idempotency_closed_no_effect", delayed.text
    after = _state()
    assert after[:-1] == before[:-1]
    with Session(engine) as session:
        receipt = session.exec(select(CommandReceipt).where(CommandReceipt.request_key == key)).one()
        assert after[-1] == [*before[-1], (receipt.id, "closed_no_effect")]
        assert (receipt.user_id, receipt.scope, receipt.target_id, receipt.payload_hash) == (
            actor["id"], scope, target, wire_fingerprint(scope, target, parse_body(raw)),
        )


@pytest.mark.parametrize("scope", sorted(BODYLESS))
@pytest.mark.parametrize("raw", ["{}", "false", "0", "[]"])
def test_bodyless_v1_rejects_non_null_json(client, trip, scope, raw):
    actor, path, _, _ = _action(scope, trip)
    before = _state()
    reply = client.post(path, headers={**actor["auth"], "Idempotency-Key": "cmd1_" + uuid4().hex,
                                      "Content-Type": "application/json"}, content=raw)
    assert reply.status_code == 422 and reply.json()["detail"]["code"] == "idempotency_payload_invalid"
    assert _state() == before


def test_versioned_series_replays_via_old_and_new_path_with_same_receipt(client, user_factory, monkeypatch):
    driver = user_factory("VersionedSeries", role=UserRole.driver)
    key = "cmd1_" + uuid4().hex
    headers = {**driver["auth"], "Idempotency-Key": key}
    first = client.post("/rides/commands/v1", headers=headers, json=PUBLISH)
    assert first.status_code == 200, first.text
    first_id = first.json()["id"]
    with Session(engine) as session:
        ride_ids = session.exec(select(Ride.id).where(Ride.driver_id == driver["id"]).order_by(Ride.id)).all()
        receipt = session.exec(select(CommandReceipt).where(CommandReceipt.request_key == key)).one()
        assert len(ride_ids) == 5
        assert receipt.ref_id == first_id
        later = receipt.created_at + timedelta(minutes=16)
    monkeypatch.setattr(idempotency, "utcnow", lambda: later)
    for path in ("/rides/commands/v1", "/rides"):
        again = client.post(path, headers=headers, json=PUBLISH)
        assert again.status_code == 200 and again.json()["id"] == first_id
    different = client.post(f"/rides/{first_id}/commands/v1/cancel", headers=headers)
    assert different.status_code == 409 and different.json()["detail"]["code"] == "idempotency_key_reused"
    with Session(engine) as session:
        assert session.exec(select(Ride.id).where(Ride.driver_id == driver["id"]).order_by(Ride.id)).all() == ride_ids
        assert session.exec(select(CommandReceipt.id).where(CommandReceipt.request_key == key)).one() == receipt.id


def test_bodyless_confirmation_accepts_explicit_null_and_no_body(client, trip):
    driver, _, _, booking = trip
    path = f"/bookings/{booking}/commands/v1/confirm"
    key = "cmd1_" + uuid4().hex
    headers = {**driver["auth"], "Idempotency-Key": key}
    confirmed = client.post(path, headers=headers)
    assert confirmed.status_code == 200 and confirmed.json()["status"] == "confirmed"
    repeat = client.post(path, headers={**headers, "Content-Type": "application/json"}, content="null")
    assert repeat.status_code == 200 and repeat.json()["id"] == booking
    with Session(engine) as session:
        receipt = session.exec(select(CommandReceipt).where(CommandReceipt.request_key == key)).one()
        assert receipt.ref_id == booking and receipt.state == "applied"


def test_v1_family_status_returns_only_safe_booking_to_its_passenger(client, trip, user_factory):
    driver, passenger, ride, booking = trip
    stranger = user_factory("VersionedStranger")
    share_id = _family_share(client, driver, passenger, ride, booking)
    path = f"/bookings/{booking}/commands/v1/trip-status"
    key = "cmd1_" + uuid4().hex
    for actor in (stranger, driver):
        refused = client.post(path, headers={**actor["auth"], "Idempotency-Key": key}, json={"status": "done"})
        assert refused.status_code == 403
    with Session(engine) as session:
        share = session.get(TripShare, share_id)
        assert share.last_status == "sat"
        assert session.exec(select(CommandReceipt).where(CommandReceipt.request_key == key)).first() is None
        token = share.token

    for _ in range(2):
        result = client.post(path, headers={**passenger["auth"], "Idempotency-Key": key}, json={"status": "done"})
        assert result.status_code == 200
        assert result.json()["id"] == booking and result.json()["status"] == "done"
        assert token and token not in result.text and "token" not in result.json()
    with Session(engine) as session:
        assert session.get(TripShare, share_id).last_status == "done"
        receipt = session.exec(select(CommandReceipt).where(CommandReceipt.request_key == key)).one()
        assert receipt.user_id == passenger["id"] and receipt.ref_id == booking
