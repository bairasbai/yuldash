"""Real PG revision races. Run ONLY via a guarded disposable-cluster runner.

These tests use distinct server connections, bounded synchronization and real
service helpers. Redis and notification transports are synthetic, not production.
The runner must validate DATABASE_URL before pytest imports conftest/init_db.
"""
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
import os
from pathlib import Path
from threading import Barrier, Event, Lock
from time import monotonic, sleep

import pytest
from sqlalchemy import text
from sqlmodel import Session

from app import instant_service as isv
from app.db import engine
from app.models import InstantOrder, InstantOrderStatus as S, UserRole
from app.timeutil import utcnow
from test_taxi_status_never_resurrects import _order, fake_redis  # noqa: F401
from test_taxi_route_offer_ordering import _apply

pytestmark = [pytest.mark.skipif(engine.dialect.name != "postgresql", reason="requires disposable PostgreSQL"),
              pytest.mark.usefixtures("fake_redis")]


@pytest.fixture(scope="session", autouse=True)
def _disposable_pg_before_client():
    # Autouse session fixture runs before this module requests the client/lifespan.
    # Never let an arbitrary externally supplied DATABASE_URL reach init_db here.
    if engine.dialect.name != "postgresql":
        return
    expected_text = os.environ.get("YULDASH_TEST_PG_DATA", "")
    assert expected_text, "use the disposable revision PG runner"
    expected = Path(expected_text).resolve()
    assert Path(expected_text).is_absolute()
    assert expected.name == "data"
    assert expected.parent.parent == Path("/tmp")
    assert expected.parent.name.startswith("yuldash-revision-pg.")
    assert engine.url.host == "127.0.0.1"
    assert engine.url.port and engine.url.username == "yuldash_test"
    assert not engine.url.query, "URL overrides are forbidden"
    with engine.connect() as connection:
        actual = connection.execute(text("SHOW data_directory")).scalar_one()
        assert Path(actual).resolve() == expected, "database is not the runner's disposable cluster"
        assert connection.execute(text("SELECT current_database()")).scalar_one() == "yuldash_revision_fixture"
        assert str(connection.execute(text("SELECT inet_server_addr()")).scalar_one()) == "127.0.0.1"
        assert connection.execute(text("SELECT inet_server_port()")).scalar_one() == engine.url.port


@contextmanager
def _connection():
    # Bind to an explicit connection: commits cannot silently change backend PID.
    with engine.connect() as connection:
        connection.execute(text("SET lock_timeout = '12s'"))
        connection.execute(text("SET statement_timeout = '15s'"))
        connection.commit()
        with Session(bind=connection) as session:
            pid = session.execute(text("SELECT pg_backend_pid()")).scalar_one()
            yield session, pid


def _distinct(pids):
    assert len(pids) == len(set(pids)) == 2, f"expected two PG backends: {pids}"
    print(f"taxi revision PostgreSQL backend pids={pids}")


def _effects(monkeypatch):
    effects, lock = [], Lock()
    def record(kind):
        def callback(*args, **kwargs):
            with lock:
                effects.append(kind)
        return callback
    monkeypatch.setattr(isv, "_push_offer", record("offer"))
    monkeypatch.setattr(isv, "push_bilingual", record("push"))
    monkeypatch.setattr(isv, "_cleanup_tried", record("cleanup"))
    monkeypatch.setattr(isv._redis(), "sadd", record("tried"))
    return effects


def test_pg_simultaneous_start_increments_have_no_lost_update(client, user_factory, monkeypatch):
    pax = user_factory("PGRevisionStarts")
    oid = _order(pax["id"], S.searching, search_revision=20)
    barrier = Barrier(2)
    monkeypatch.setattr(isv, "try_offer_next", lambda session, order, *args, **kwargs: order)
    def worker():
        with _connection() as (session, pid):
            order = session.get(InstantOrder, oid)
            assert order.search_revision == 20
            barrier.wait(timeout=8)
            isv.start_matching(session, order)
            return pid
    with ThreadPoolExecutor(max_workers=2) as pool:
        futures = [pool.submit(worker) for _ in range(2)]
        pids = [future.result(timeout=20) for future in futures]
    _distinct(pids)
    with Session(engine) as observer:
        assert observer.get(InstantOrder, oid).search_revision == 22


def test_pg_two_null_initializers_only_winner_looks_up(client, user_factory, monkeypatch):
    pax = user_factory("PGRevisionNull")
    oid = _order(pax["id"], S.searching, search_revision=None)
    barrier, loser_finished = Barrier(2), Event()
    lookups, lock = [], Lock()
    effects = _effects(monkeypatch)
    class StopWinner(Exception):
        pass
    def candidates(*args, **kwargs):
        with lock:
            lookups.append(True)
        assert loser_finished.wait(8), "loser must return while winner's lookup is held"
        raise StopWinner
    monkeypatch.setattr(isv, "candidates", candidates)
    def worker():
        with _connection() as (session, pid):
            order = session.get(InstantOrder, oid)
            assert order.search_revision is None
            barrier.wait(timeout=8)
            try:
                result = isv.try_offer_next(session, order)
            except StopWinner:
                return pid, "winner"
            assert result.status == S.searching and result.search_revision == 1
            loser_finished.set()
            return pid, "loser"
    with ThreadPoolExecutor(max_workers=2) as pool:
        futures = [pool.submit(worker) for _ in range(2)]
        results = [future.result(timeout=20) for future in futures]
    _distinct([row[0] for row in results])
    assert sorted(row[1] for row in results) == ["loser", "winner"]
    assert len(lookups) == 1 and effects == []
    with Session(engine) as observer:
        assert observer.get(InstantOrder, oid).search_revision == 1


@pytest.mark.parametrize("has_candidate", [True, False])
def test_pg_old_lookup_loses_same_clock_restart(client, user_factory, monkeypatch, has_candidate):
    pax = user_factory("PGRevisionABA")
    driver = user_factory("PGRevisionABADriver", role=UserRole.driver)
    fixed = utcnow()
    monkeypatch.setattr(isv, "utcnow", lambda: fixed)
    oid = _order(pax["id"], S.searching, search_revision=30, searching_at=fixed, search_round=0)
    lookup_entered, restart_committed = Event(), Event()
    effects = _effects(monkeypatch)
    matcher = isv.try_offer_next
    monkeypatch.setattr(isv, "try_offer_next", lambda session, order, *args, **kwargs: order)
    def candidates(*args, **kwargs):
        lookup_entered.set()
        assert restart_committed.wait(8)
        return [driver["id"]] if has_candidate else []
    monkeypatch.setattr(isv, "candidates", candidates)
    def old_worker():
        with _connection() as (session, pid):
            result = matcher(session, session.get(InstantOrder, oid))
            assert result.status == S.searching and result.search_revision == 31
            return pid
    def restart_worker():
        assert lookup_entered.wait(8)
        with _connection() as (session, pid):
            result = isv.start_matching(session, session.get(InstantOrder, oid))
            assert result.search_revision == 31
            assert (result.searching_at, result.search_round, result.offer_token) == (fixed, 0, None)
            restart_committed.set()
            return pid
    with ThreadPoolExecutor(max_workers=2) as pool:
        futures = [pool.submit(old_worker), pool.submit(restart_worker)]
        pids = [future.result(timeout=20) for future in futures]
    _distinct(pids)
    assert effects == []


@pytest.mark.parametrize("kind", ["destination", "waypoints", "fallback"])
@pytest.mark.parametrize("has_candidate", [True, False])
def test_pg_criteria_row_lock_serializes_stale_matcher(client, user_factory, monkeypatch, kind, has_candidate):
    pax = user_factory("PGRevisionCriteria")
    driver = user_factory("PGRevisionCriteriaDriver", role=UserRole.driver)
    oid = _order(pax["id"], S.searching, category="comfort", search_revision=40,
                 ride_price=500, price_estimate=500)
    matcher_loaded, mutation_locked, release_mutation = Event(), Event(), Event()
    pids, shared_lock = {}, Lock()
    effects = _effects(monkeypatch)
    matcher = isv.try_offer_next
    monkeypatch.setattr(isv, "try_offer_next", lambda session, order, *args, **kwargs: order)
    monkeypatch.setattr(isv.settings, "car_class_min_drivers", 0)
    monkeypatch.setattr(isv, "_category_price", lambda *args: 100)
    real_reclamp = isv.promo_ride.reclamp
    def hold_reclamp(session, order, **kwargs):
        assert order.search_revision == 41
        mutation_locked.set()
        assert release_mutation.wait(10), "test must observe actual PG lock before releasing writer"
        return real_reclamp(session, order, **kwargs)
    monkeypatch.setattr(isv.promo_ride, "reclamp", hold_reclamp)
    def candidates(*args, **kwargs):
        matcher_loaded.set()
        assert mutation_locked.wait(8)
        return [driver["id"]] if has_candidate else []
    monkeypatch.setattr(isv, "candidates", candidates)
    def old_worker():
        with _connection() as (session, pid):
            with shared_lock:
                pids["matcher"] = pid
            result = matcher(session, session.get(InstantOrder, oid))
            assert result.status == S.searching and result.search_revision == 41
            return pid
    def mutation_worker():
        assert matcher_loaded.wait(8)
        with _connection() as (session, pid):
            with shared_lock:
                pids["writer"] = pid
            order = session.get(InstantOrder, oid)
            if kind == "fallback":
                isv.add_fallback_category(session, order, "standard")
            else:
                _apply(session, order, kind)
            return pid
    with ThreadPoolExecutor(max_workers=2) as pool:
        futures = [pool.submit(old_worker), pool.submit(mutation_worker)]
        try:
            assert mutation_locked.wait(8)
            deadline, blocked = monotonic() + 6, False
            with engine.connect() as observer:
                while monotonic() < deadline:
                    with shared_lock:
                        matcher_pid, writer_pid = pids["matcher"], pids["writer"]
                    blockers = observer.execute(text("SELECT pg_blocking_pids(:pid)"), {"pid": matcher_pid}).scalar_one()
                    if writer_pid in blockers:
                        blocked = True
                        break
                    sleep(0.01)
            assert blocked, "must prove stale matcher really waited for criteria writer's PG row lock"
        finally:
            release_mutation.set()
        results = [future.result(timeout=20) for future in futures]
    _distinct(results)
    assert effects == []
    with Session(engine) as observer:
        saved = observer.get(InstantOrder, oid)
        assert saved.status == S.searching and saved.search_revision == 41
        if kind == "fallback":
            assert saved.ride_price == 100 and "standard" in isv.order_categories(saved)
        elif kind == "destination":
            assert saved.to_text == "Новое место"
        else:
            assert "Новая остановка" in saved.waypoints_json
