"""Diagnostic evidence of known late-effect limitations, NOT safety regressions.

PASS means the specified harmful outcome was observed. These tests intentionally
assert lost tried history / stale delivery; they do not claim runtime protection.
Only a synthetic Redis timeout/late execution and transport queue are modelled:
no real Redis network timing, mobile delivery or PostgreSQL concurrency is proved.
"""
from copy import deepcopy

import pytest
from redis.exceptions import TimeoutError as RedisTimeoutError
from sqlmodel import Session, select

from app import instant_service as isv
from app.db import engine
from app.models import DriverCancel, InstantOrder, InstantOrderStatus as S, UserRole
from test_taxi_status_never_resurrects import fake_redis  # noqa: F401
from test_taxi_accept_cleanup_generation import _offer_fixture, _cleanup_accept_orders  # noqa: F401


@pytest.fixture
def late_effect_setup(user_factory, monkeypatch):
    oid, first_driver = _offer_fixture(user_factory)
    next_driver = user_factory("LateEffectNextDriver", role=UserRole.driver)
    offers = []
    # Actual cancellation/reassign/matcher UPDATE and SADD stay in use.
    monkeypatch.setattr(isv, "candidates", lambda *args, **kwargs: [next_driver["id"]])
    monkeypatch.setattr(isv, "_push_offer", lambda session, order, did: offers.append((order.search_revision, did)))
    monkeypatch.setattr("app.services.push_notification", lambda *args, **kwargs: None)
    return oid, first_driver, next_driver, offers


def _accept(oid, driver):
    with Session(engine) as session:
        accepted = isv.transition(session, oid, isv.Actor.driver, S.accepted, driver["id"], idempotent=False)
        assert accepted.status == S.accepted and accepted.search_revision == 20
        assert accepted.driver_id == driver["id"]


def _cancel_reassign(oid, first_driver, next_driver, offers):
    with Session(engine) as session:
        offered = isv.cancel_order(session, oid, isv.Actor.driver, first_driver["id"], "car_problem")
        assert offered.status == S.offered and offered.search_revision == 21
        assert offered.current_offer_driver_id == next_driver["id"]
        assert offered.reassigns == 1
        events = session.exec(select(DriverCancel).where(DriverCancel.order_id == oid)).all()
        assert len(events) == 1 and events[0].driver_id == first_driver["id"]
    assert offers == [(21, next_driver["id"])]
    redis = isv._redis()
    assert redis.sismember(isv._tried_key(oid), first_driver["id"])
    assert redis.sismember(isv._tried_key(oid), next_driver["id"])


@pytest.mark.usefixtures("fake_redis")
def test_known_boundary_late_timed_out_delete_erases_reassigned_tried(client, late_effect_setup, monkeypatch):
    """A timeout is not proof Redis cancelled the command: a late DELETE is harmful."""
    oid, first_driver, next_driver, offers = late_effect_setup
    redis = isv._redis()
    key = isv._tried_key(oid)
    real_delete, queued_deletes, trace = redis.delete, [], []
    def queue_then_timeout(*keys):
        assert keys == (key,), "only the test's order may be queued for deletion"
        queued_deletes.append(keys)
        trace.append("delete_timeout")
        raise RedisTimeoutError("synthetic command timed out before delayed execution")
    monkeypatch.setattr(redis, "delete", queue_then_timeout)
    monkeypatch.setattr(isv, "push_bilingual", lambda *args, **kwargs: None)
    try:
        _accept(oid, first_driver)
        trace.append("accept_completed")
        assert queued_deletes == [(key,)]
        _cancel_reassign(oid, first_driver, next_driver, offers)
        trace.append("new_offer_and_tried_committed")
        # Execute the exact previously queued command, not a new cleanup request.
        assert real_delete(*queued_deletes.pop()) == 1
        trace.append("old_delete_executed_late")
        assert not redis.exists(key), "diagnostic must observe deletion of the new generation's history"
        with Session(engine) as observer:
            saved = observer.get(InstantOrder, oid)
            assert saved.status == S.offered and saved.search_revision == 21
            assert saved.current_offer_driver_id == next_driver["id"]
        assert trace == ["delete_timeout", "accept_completed", "new_offer_and_tried_committed", "old_delete_executed_late"]
        print("KNOWN LIMITATION observed: timed-out old DELETE erased reassigned tried history")
    finally:
        real_delete(key)


@pytest.mark.usefixtures("fake_redis")
def test_known_boundary_queued_accepted_delivery_arrives_after_reassign(client, late_effect_setup, monkeypatch):
    """A valid accepted checkpoint does not make later transport delivery current."""
    oid, first_driver, next_driver, offers = late_effect_setup
    queued, delivered, trace = [], [], []
    def queue_transport(session, user_id, title_ru, title_ba, body_ru, body_ba, **kwargs):
        # _notify_transition is real. Capture its actual bilingual text + payload.
        queued.append((user_id, title_ru, title_ba, body_ru, body_ba, deepcopy(kwargs["data"])))
        trace.append("accepted_queued")
    monkeypatch.setattr(isv, "push_bilingual", queue_transport)
    key = isv._tried_key(oid)
    try:
        _accept(oid, first_driver)
        assert len(queued) == 1
        payload = queued[0][-1]
        assert payload == {"type": "instant_status", "order_id": str(oid), "status": "accepted"}
        assert queued[0][1:3] == ("Водитель найден 🚗", "Водитель табылды 🚗")
        _cancel_reassign(oid, first_driver, next_driver, offers)
        trace.append("new_offer_committed")
        delivered.append(queued.pop(0))
        trace.append("old_accepted_delivered")
        with Session(engine) as observer:
            saved = observer.get(InstantOrder, oid)
            assert saved.status == S.offered and saved.search_revision == 21
            assert saved.driver_id is None and saved.current_offer_driver_id == next_driver["id"]
        assert delivered[0][-1]["status"] == "accepted", "diagnostic must observe stale accepted transport data"
        assert trace == ["accepted_queued", "new_offer_committed", "old_accepted_delivered"]
        print("KNOWN LIMITATION observed: accepted notification delivered after cancellation/reassign")
    finally:
        isv._redis().delete(key)
