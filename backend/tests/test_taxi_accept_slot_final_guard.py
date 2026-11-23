"""Real SQLite ordinary taxi assignment fence; no sealed accept."""
from concurrent.futures import ThreadPoolExecutor
from threading import Event

import pytest
from sqlalchemy import delete, select
from sqlmodel import Session

from app import instant_service as isv
from app.db import engine
from app.models import InstantOrder, InstantOrderStatus as S
from app.timeutil import utcnow
from test_instant import _offered_order, fake_redis  # noqa: F401 - fixture export
from test_taxi_offer_card_accept_v2 import _protected
from test_taxi_route_write_races import _route

_ORDER = InstantOrder.__table__
_ACTIVE = (S.accepted, S.arriving, S.onboard)


def _active(driver):
    with engine.connect() as conn:
        return set(conn.scalars(select(_ORDER.c.id).where(
            _ORDER.c.driver_id == driver, _ORDER.c.status.in_(_ACTIVE),
        )))


@pytest.fixture
def two_offers(client, user_factory, request, monkeypatch):
    if engine.dialect.name != "sqlite":
        pytest.skip("SQLite development DB/legacy mode; PostgreSQL row locks are separate")
    redis = request.getfixturevalue("fake_redis")
    driver, _, first = _offered_order(client, user_factory, redis, "SlotWitnessDriver", "SlotWitnessOwnerA")
    other = user_factory("SlotWitnessOwnerB")
    # Seed a competing valid pre-existing offer only, not an assignment.
    # Production creates A; independent DB fixture models A/B being offered
    # to one driver by independent prior candidate selections.
    with Session(engine) as s:
        existing = s.get(InstantOrder, first["id"])
        captured = {name: getattr(existing, name) for name in isv._ACCEPT_OFFER_FIELDS
                    if name not in ("id", "passenger_id")}
        second = InstantOrder(**captured, passenger_id=other["id"], created_at=existing.created_at)
        s.add(second)
        s.flush()
        second_id = second.id
        second_tag = isv.offer_card_precondition(
            isv.capture_offer_for_accept(second), second.price_final, driver["id"], utcnow(),
        )
        assert second_tag
        s.commit()
    # Suppress only post-commit external effects, never standing checks or DB.
    for notification in ("_notify_transition", "_notify_pickup_fee"):
        monkeypatch.setattr(isv, notification, lambda *_, **__: None)
    try:
        yield driver, first["id"], second_id, second_tag
    finally:
        with engine.begin() as conn:
            conn.execute(delete(_ORDER).where(_ORDER.c.id.in_((first["id"], second_id))))


def _legacy(client, oid, driver):
    return client.post(_route(oid, "accept"), headers=driver["auth"])


def test_sequential_ordinary_accept_already_refuses_second_active_taxi(client, two_offers):
    driver, a, b, tag = two_offers
    assert _active(driver["id"]) == set()
    assert _legacy(client, a, driver).status_code == 200
    assert _protected(client, b, driver, tag).status_code == 409
    assert _active(driver["id"]) == {a}


@pytest.mark.parametrize("late_endpoint", ("legacy", "v2"))
def test_late_ordinary_accept_cannot_commit_a_second_active_taxi(client, two_offers, monkeypatch, late_endpoint):
    driver, a, b, tag = two_offers
    assert _active(driver["id"]) == set()
    late_has_read, allow_late = Event(), Event()
    snapshots = []
    real_pause = isv.driver_pause_until
    effects = []

    def capture(notice, oid):
        effects.append((notice, oid))

    monkeypatch.setattr(isv, "_cleanup_tried", lambda oid: capture("cleanup", oid))
    monkeypatch.setattr(isv, "_notify_transition", lambda s, order, target: capture("transition", order.id))
    monkeypatch.setattr(isv, "_notify_pickup_fee", lambda s, order, prior: capture("pickup", order.id))
    # Model a computed nonempty late pickup projection at the existing boundary.
    # It must not be written along with a lost assignment; no postcommit effects.
    real_pickup = isv._settle_pickup_fee
    projected = []

    def pickup(s, order, uid):
        if order.id == b:
            projected.append(uid)
            return {"pickup_fee_kop": 12300, "pickup_pending": False}
        return real_pickup(s, order, uid)

    monkeypatch.setattr(isv, "_settle_pickup_fee", pickup)
    with engine.connect() as conn:
        before = conn.execute(select(_ORDER).where(_ORDER.c.id == b)).one()

    def after_actual_busy_read(s, uid, *args, **kwargs):
        result = real_pause(s, uid, *args, **kwargs)
        if uid == driver["id"] and not late_has_read.is_set():
            raw = s.connection().connection.driver_connection
            snapshots.append((raw, raw.in_transaction, _active(uid)))
            late_has_read.set()
            assert allow_late.wait(6)
        return result

    monkeypatch.setattr(isv, "driver_pause_until", after_actual_busy_read)
    with ThreadPoolExecutor(max_workers=1) as pool:
        if late_endpoint == "legacy":
            late = pool.submit(_legacy, client, b, driver)
        else:
            late = pool.submit(_protected, client, b, driver, tag)
        try:
            assert late_has_read.wait(5)
            with engine.connect() as conn:
                assert conn.connection.driver_connection is not snapshots[0][0]
            assert snapshots[0][1:] == (False, set())
            early = _legacy(client, a, driver)
            assert early.status_code == 200, early.text
            assert _active(driver["id"]) == {a}
        finally:
            allow_late.set()
        actual = late.result(timeout=8)
    assert actual.status_code == 409, actual.text
    assert actual.json()["detail"]["code"] == "taxi_offer_changed"
    assert _active(driver["id"]) == {a}
    assert projected == [driver["id"]]
    assert effects == [("cleanup", a), ("transition", a), ("pickup", a)]
    with engine.connect() as conn:
        assert conn.execute(select(_ORDER).where(_ORDER.c.id == b)).one() == before
