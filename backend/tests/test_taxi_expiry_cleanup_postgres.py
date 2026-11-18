"""Disposable PostgreSQL: expiry cleanup holds restart out until lock release.

Unexpected callback failures test DB lock release, not distributed Redis atomicity.
"""
from concurrent.futures import ThreadPoolExecutor
from threading import Event, Lock
from time import monotonic, sleep

import pytest
from sqlalchemy import text
from sqlmodel import Session

from app import instant_service as isv
from app.db import engine
from app.models import InstantOrder, InstantOrderStatus as S, UserRole
from test_taxi_status_never_resurrects import _order, fake_redis  # noqa: F401
from test_taxi_search_revision_postgres import (
    _connection, _distinct, _disposable_pg_before_client,  # noqa: F401
)
from test_taxi_expiry_cleanup_generation import _cleanup_expiry_orders  # noqa: F401

pytestmark = [pytest.mark.skipif(engine.dialect.name != "postgresql", reason="requires disposable PostgreSQL"),
              pytest.mark.usefixtures("fake_redis")]


@pytest.mark.parametrize("unexpected_cleanup_error", [False, True])
def test_pg_expiry_cleanup_blocks_restart_and_releases_on_error(client, user_factory, monkeypatch, unexpected_cleanup_error):
    passenger = user_factory("PGExpiryCleanupPassenger")
    old_driver = user_factory("PGExpiryOldDriver", role=UserRole.driver)
    new_driver = user_factory("PGExpiryNewDriver", role=UserRole.driver)
    oid = _order(passenger["id"], S.searching, search_revision=80)
    redis = isv._redis()
    key = isv._tried_key(oid)
    redis.sadd(key, old_driver["id"])
    cleanup_entered, restart_entered, release_cleanup = Event(), Event(), Event()
    pids, lock, offers = {}, Lock(), []
    real_cleanup = isv._cleanup_tried
    monkeypatch.setattr(isv, "push_bilingual", lambda *args, **kwargs: None)
    monkeypatch.setattr(isv, "_push_offer", lambda session, order, did: offers.append((order.search_revision, did)))
    monkeypatch.setattr(isv, "candidates", lambda redis, session, order, **kwargs:
                        [new_driver["id"]] if order.search_revision == 81 else [])

    def held_cleanup(order_id, lifecycle=None):
        assert order_id == oid
        cleanup_entered.set()
        assert release_cleanup.wait(10), "coordinator must release cleanup"
        if unexpected_cleanup_error:
            raise RuntimeError("synthetic unexpected cleanup callback failure")
        real_cleanup(order_id, lifecycle)
    monkeypatch.setattr(isv, "_cleanup_tried", held_cleanup)

    def expiry_worker():
        with _connection() as (session, pid):
            with lock:
                pids["expiry"] = pid
            if unexpected_cleanup_error:
                with pytest.raises(RuntimeError, match="synthetic unexpected cleanup callback failure"):
                    isv.try_offer_next(session, session.get(InstantOrder, oid))
            else:
                result = isv.try_offer_next(session, session.get(InstantOrder, oid))
                assert result.id == oid
            return pid

    def restart_worker():
        assert cleanup_entered.wait(8)
        with _connection() as (session, pid):
            with lock:
                pids["restart"] = pid
            order = session.get(InstantOrder, oid)
            assert order.status == S.expired and order.search_revision == 80
            restart_entered.set()
            result = isv.start_matching(session, order)
            assert result.status == S.offered and result.search_revision == 81
            assert result.current_offer_driver_id == new_driver["id"]
            return pid

    try:
        with ThreadPoolExecutor(max_workers=2) as pool:
            futures = [pool.submit(expiry_worker), pool.submit(restart_worker)]
            try:
                assert restart_entered.wait(8)
                deadline, blocked = monotonic() + 6, False
                with engine.connect() as observer:
                    while monotonic() < deadline:
                        with lock:
                            expiry_pid, restart_pid = pids["expiry"], pids["restart"]
                        blockers = observer.execute(text("SELECT pg_blocking_pids(:pid)"), {"pid": restart_pid}).scalar_one()
                        if expiry_pid in blockers:
                            blocked = True
                            break
                        sleep(0.01)
                assert blocked, "restart must really wait for expiry cleanup row lock"
                assert not offers, "restart cannot publish while prior cleanup owns row lock"
            finally:
                release_cleanup.set()
            results = [future.result(timeout=20) for future in futures]
        _distinct(results)
        assert offers == [(81, new_driver["id"])]
        assert redis.sismember(key, new_driver["id"]), "new SADD must survive prior expiry cleanup"
        assert bool(redis.sismember(key, old_driver["id"])) == unexpected_cleanup_error
        with Session(engine) as observer:
            saved = observer.get(InstantOrder, oid)
            assert saved.status == S.offered and saved.search_revision == 81
    finally:
        release_cleanup.set()
        redis.delete(key)
