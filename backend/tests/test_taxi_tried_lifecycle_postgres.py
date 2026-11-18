"""Disposable PG: lifecycle NULL-boundary and tokenized cleanup row locks.

A delayed accepted push after releasing the checkpoint is outside this test's
contract. Unexpected cleanup failure verifies lock release, not Redis atomicity.
"""
from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta
from threading import Barrier, Event, Lock
from time import monotonic, sleep

import pytest
from sqlalchemy import text
from sqlmodel import Session

from app import instant_service as isv
from app.db import engine
from app.models import DriverCancel, InstantOrder, InstantOrderStatus as S, UserRole
from app.timeutil import utcnow
from test_taxi_status_never_resurrects import _order, fake_redis  # noqa: F401
from test_taxi_search_revision_postgres import (
    _connection, _distinct, _disposable_pg_before_client,  # noqa: F401
)
from test_taxi_accept_cleanup_generation import _cleanup_accept_orders  # noqa: F401
from sqlmodel import select

pytestmark = [pytest.mark.skipif(engine.dialect.name != "postgresql", reason="requires disposable PostgreSQL"),
              pytest.mark.usefixtures("fake_redis")]


@pytest.mark.parametrize("unexpected_cleanup_error", [False, True])
def test_pg_tokenized_accept_cleanup_blocks_reassign_and_releases_on_error(client, user_factory, monkeypatch, unexpected_cleanup_error):
    monkeypatch.setattr(isv.settings, "taxi_tried_lifecycle_enabled", False)
    lifecycle = "a" * 32
    passenger = user_factory("PGLifecycleCleanupPassenger")
    old_driver = user_factory("PGAcceptOldDriver", role=UserRole.driver)
    new_driver = user_factory("PGAcceptNewDriver", role=UserRole.driver)
    oid = _order(passenger["id"], S.offered, search_revision=80,
                 current_offer_driver_id=old_driver["id"], offer_token="c" * 32, tried_lifecycle=lifecycle,
                 offer_expires_at=utcnow() + timedelta(minutes=1))
    redis = isv._redis()
    key = isv._tried_key(oid, lifecycle)
    redis.sadd(key, old_driver["id"])
    cleanup_entered, cancel_entered, release_cleanup = Event(), Event(), Event()
    pids, lock, offers = {}, Lock(), []
    real_cleanup = isv._cleanup_tried
    monkeypatch.setattr(isv, "push_bilingual", lambda *args, **kwargs: None)
    monkeypatch.setattr("app.services.push_notification", lambda *args, **kwargs: None)
    monkeypatch.setattr(isv, "_push_offer", lambda session, order, did: offers.append((order.search_revision, did)))
    monkeypatch.setattr(isv, "candidates", lambda *args, **kwargs: [new_driver["id"]])

    def held_cleanup(order_id, captured_lifecycle=None):
        assert captured_lifecycle == lifecycle
        assert order_id == oid
        cleanup_entered.set()
        assert release_cleanup.wait(20)
        if unexpected_cleanup_error:
            raise RuntimeError("synthetic unexpected accept cleanup failure")
        real_cleanup(order_id, captured_lifecycle)
    monkeypatch.setattr(isv, "_cleanup_tried", held_cleanup)

    def accept_worker():
        with _connection() as (session, pid):
            with lock:
                pids["accept"] = pid
            if unexpected_cleanup_error:
                with pytest.raises(RuntimeError, match="synthetic unexpected accept cleanup failure"):
                    isv.transition(session, oid, isv.Actor.driver, S.accepted, old_driver["id"], idempotent=False)
            else:
                result = isv.transition(session, oid, isv.Actor.driver, S.accepted, old_driver["id"], idempotent=False)
                assert result.id == oid
            return pid

    def cancel_worker():
        assert cleanup_entered.wait(8)
        with _connection() as (session, pid):
            with lock:
                pids["cancel"] = pid
            current = session.get(InstantOrder, oid)
            assert current.status == S.accepted and current.driver_id == old_driver["id"]
            cancel_entered.set()
            result = isv.cancel_order(session, oid, isv.Actor.driver, old_driver["id"], "car_problem")
            assert result.status == S.offered and result.search_revision == 81
            assert result.current_offer_driver_id == new_driver["id"] and result.reassigns == 1
            return pid

    try:
        with ThreadPoolExecutor(max_workers=2) as pool:
            futures = [pool.submit(accept_worker), pool.submit(cancel_worker)]
            try:
                assert cancel_entered.wait(8)
                deadline, blocked = monotonic() + 6, False
                with engine.connect() as observer:
                    while monotonic() < deadline:
                        with lock:
                            accept_pid, cancel_pid = pids["accept"], pids["cancel"]
                        blockers = observer.execute(text("SELECT pg_blocking_pids(:pid)"), {"pid": cancel_pid}).scalar_one()
                        if accept_pid in blockers:
                            blocked = True
                            break
                        sleep(0.01)
                assert blocked, "actual cancel must wait for accept cleanup row lock"
                assert not offers
            finally:
                release_cleanup.set()
            results = [future.result(timeout=20) for future in futures]
        _distinct(results)
        assert offers == [(81, new_driver["id"])]
        with Session(engine) as observer:
            saved = observer.get(InstantOrder, oid)
            assert saved.status == S.offered and saved.search_revision == 81
            assert saved.tried_lifecycle and saved.tried_lifecycle != lifecycle
            new_key = isv._tried_key(oid, saved.tried_lifecycle)
            assert redis.sismember(new_key, old_driver["id"]) and redis.sismember(new_key, new_driver["id"])
            assert not redis.exists(isv._tried_key(oid))
            # Even a later repeat of the old cleanup cannot touch the new lifecycle.
            real_cleanup(oid, lifecycle)
            assert redis.sismember(new_key, new_driver["id"])
            cancellations = observer.exec(select(DriverCancel).where(DriverCancel.order_id == oid)).all()
            assert len(cancellations) == 1 and cancellations[0].driver_id == old_driver["id"]
    finally:
        release_cleanup.set()
        keys = list(redis.scan_iter(f"instant:order:{oid}:tried*"))
        if keys:
            redis.delete(*keys)


def test_pg_two_null_new_cycle_callers_initialize_once_and_preserve_lifecycle(client, user_factory, monkeypatch):
    """Second stale created caller waits, then sees searching and keeps first L."""
    monkeypatch.setattr(isv.settings, "taxi_tried_lifecycle_enabled", True)
    passenger = user_factory("PGLifecycleNullBoundary")
    oid = _order(passenger["id"], S.created, search_revision=90)
    loaded = Barrier(2)
    held, second_entered, release = Event(), Event(), Event()
    pids, assigned, lock = {}, [], Lock()
    monkeypatch.setattr(isv, "try_offer_next", lambda session, order, *args, **kwargs: order)

    def first_worker():
        with _connection() as (session, pid):
            order = session.get(InstantOrder, oid)
            assert order.status == S.created and order.tried_lifecycle is None
            with lock:
                pids["first"] = pid
            real_commit = session.commit
            def held_commit():
                current = session.exec(select(InstantOrder.tried_lifecycle).where(InstantOrder.id == oid)).one()
                assert current is not None
                assigned.append(current)
                held.set()
                assert release.wait(20)
                real_commit()
            monkeypatch.setattr(session, "commit", held_commit)
            loaded.wait(timeout=8)
            result = isv.start_matching(session, order)
            return pid, result.tried_lifecycle

    def second_worker():
        with _connection() as (session, pid):
            order = session.get(InstantOrder, oid)
            assert order.status == S.created and order.tried_lifecycle is None
            with lock:
                pids["second"] = pid
            loaded.wait(timeout=8)
            assert held.wait(8)
            second_entered.set()
            result = isv.start_matching(session, order)
            return pid, result.tried_lifecycle

    with ThreadPoolExecutor(max_workers=2) as pool:
        futures = [pool.submit(first_worker), pool.submit(second_worker)]
        try:
            assert second_entered.wait(8)
            deadline, blocked = monotonic() + 6, False
            with engine.connect() as observer:
                while monotonic() < deadline:
                    with lock:
                        first_pid, second_pid = pids["first"], pids["second"]
                    blockers = observer.execute(text("SELECT pg_blocking_pids(:pid)"), {"pid": second_pid}).scalar_one()
                    if first_pid in blockers:
                        blocked = True
                        break
                    sleep(0.01)
            assert blocked, "second real start must wait for the first lifecycle transaction"
        finally:
            release.set()
        results = [future.result(timeout=20) for future in futures]
    _distinct([row[0] for row in results])
    assert len(assigned) == 1 and len(assigned[0]) == 32
    assert all(row[1] == assigned[0] for row in results)
    with Session(engine) as observer:
        saved = observer.get(InstantOrder, oid)
        assert saved.status == S.searching and saved.search_revision == 92
        assert saved.tried_lifecycle == assigned[0]
