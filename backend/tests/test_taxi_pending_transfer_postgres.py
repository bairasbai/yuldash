"""Real owner-lock pending transfer; synthetic intent, no durable activation.

Controlled application clock proves call ordering, not database wall time.
Only transport/Redis fixture boundaries are synthetic; owner helpers, SQL,
transaction commits and PostgreSQL blocking are real.
"""

from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
from datetime import timedelta
from threading import Event, get_ident
from time import monotonic, sleep

import pytest
from sqlalchemy import event, text, update
from sqlalchemy.exc import DBAPIError
from sqlmodel import Session

from app import instant_service as isv
from app.db import engine
from app.models import InstantOrder
import test_taxi_pending_transfer_writers as writers
from test_taxi_search_revision_postgres import _disposable_pg_before_client  # noqa: F401

seeded = writers.seeded
pending_case = writers.pending_case
pytestmark = pytest.mark.skipif(
    engine.dialect.name != "postgresql", reason="requires guarded disposable PostgreSQL"
)


@contextmanager
def connection_session():
    with engine.connect() as connection:
        with Session(bind=connection, expire_on_commit=False) as session:
            # Transaction-local only: pooled connections keep their original settings.
            session.execute(text("SET LOCAL lock_timeout = '12s'"))
            session.execute(text("SET LOCAL statement_timeout = '15s'"))
            pid = session.execute(text("SELECT pg_backend_pid()")).scalar_one()
            yield session, pid


def wait_for_block(waiter_pid, holder_pid):
    deadline = monotonic() + 5
    with engine.connect().execution_options(isolation_level="AUTOCOMMIT") as observer:
        observer_pid = observer.execute(text("SELECT pg_backend_pid()")).scalar_one()
        assert len({observer_pid, waiter_pid, holder_pid}) == 3
        while monotonic() < deadline:
            blockers = observer.execute(
                text("SELECT pg_blocking_pids(:pid)"), {"pid": waiter_pid}
            ).scalar_one()
            if holder_pid in blockers:
                print(f"pending transfer holder={holder_pid} waiter={waiter_pid} observer={observer_pid}")
                return
            sleep(0.01)
    pytest.fail("actual PostgreSQL owner lock wait was not observed")


@pytest.mark.parametrize("operation", ("bump", "start", "advance", "reassign"))
@pytest.mark.parametrize("holder_outcome", ("commit", "rollback", "database_error"))
def test_pg_transfer_first_waiter_reads_fresh_pending_and_postlock_clock(
    pending_case, monkeypatch, operation, holder_outcome
):
    make, _, driver, now = pending_case
    before_clock = now
    after_clock = now + timedelta(seconds=5)
    initial_deadline = now + timedelta(minutes=2)
    changed_deadline = now + timedelta(minutes=3)
    oid, original = make(operation, matching_resume_next_at=initial_deadline)
    released = Event()
    waiter_ready = Event()
    waiter_info = {}
    calls = []

    def clock():
        value = after_clock if released.is_set() else before_clock
        calls.append((get_ident(), value))
        return value

    monkeypatch.setattr(isv, "utcnow", clock)

    def waiter():
        with connection_session() as (session, pid):
            # Explicit stale clean identity-map owner must not supply pending.
            order = session.get(InstantOrder, oid)
            assert order.search_revision == 41
            assert order.matching_resume_attempts == 3
            waiter_info.update(pid=pid, thread=get_ident())
            waiter_ready.set()
            writers.invoke(session, oid, operation, now, driver["id"])
            if operation == "bump":
                session.commit()
            return session.get(InstantOrder, oid).search_revision

    with connection_session() as (holder, holder_pid):
        order = holder.get(InstantOrder, oid)
        assert isv._bump_search_revision(holder, order) == 42
        assert tuple(getattr(order, key) for key in writers.PENDING) == (
            42, before_clock, 3
        )
        # Real pending update by the lock owner, not a mocked capture result.
        holder.execute(
            update(InstantOrder).where(InstantOrder.id == oid).values(
                matching_resume_next_at=changed_deadline, matching_resume_attempts=9
            )
        )
        assert writers.known.snapshot(oid) == original
        with ThreadPoolExecutor(max_workers=1) as pool:
            future = pool.submit(waiter)
            try:
                assert waiter_ready.wait(5), "waiter did not load its stale owner"
                assert waiter_info["pid"] != holder_pid
                wait_for_block(waiter_info["pid"], holder_pid)
                assert not future.done()
                assert not any(thread == waiter_info["thread"] for thread, _ in calls)
                # MVCC observer still sees the whole original tuple, not half-transfer.
                assert writers.known.snapshot(oid) == original
                released.set()
                if holder_outcome == "commit":
                    holder.commit()
                elif holder_outcome == "database_error":
                    with pytest.raises(DBAPIError) as error:
                        holder.execute(text("SELECT 1 / 0"))
                    assert error.value.orig.pgcode == "22012"
                    holder.rollback()
                else:
                    holder.rollback()
                expected_revision = 43 if holder_outcome == "commit" else 42
                assert future.result(timeout=20) == expected_revision
            finally:
                # Release our lock even if an assertion fails, then join the
                # bounded SQL worker before fixture teardown. Do not mask a
                # primary failure by awaiting its result again in finally.
                released.set()
                holder.rollback()
    after = writers.known.snapshot(oid)
    expected_attempts = 9 if holder_outcome == "commit" else 3
    assert after["search_revision"] == expected_revision
    assert writers.pending(after) == (expected_revision, after_clock, expected_attempts)
    waiter_clock = [value for thread, value in calls if thread == waiter_info["thread"]]
    assert waiter_clock and all(value == after_clock for value in waiter_clock)
    assert after["status"] == (original["status"] if operation == "bump" else "expired")


@pytest.mark.parametrize("operation", ("start", "advance"))
def test_pg_actual_cancel_first_blocks_pending_transfer_and_waiter_effects(
    pending_case, monkeypatch, operation
):
    make, passenger, driver, now = pending_case
    oid, original = make(operation)
    ready = Event()
    waiter_info = {}
    effects = []
    redis = isv._redis_override
    execute = redis.execute_command

    def redis_transport(*args, **kwargs):
        effects.append((get_ident(), "redis"))
        return execute(*args, **kwargs)

    monkeypatch.setattr(redis, "execute_command", redis_transport)
    monkeypatch.setattr(isv, "send_push", lambda *a, **kw: effects.append((get_ident(), "push")))

    def waiter():
        with connection_session() as (session, pid):
            order = session.get(InstantOrder, oid)  # Retain stale identity-map object.
            assert order.search_revision == original["search_revision"]
            waiter_info.update(pid=pid, thread=get_ident())
            ready.set()
            writers.invoke(session, oid, operation, now, driver["id"])

    with connection_session() as (holder, holder_pid):
        holder.execute(text("SELECT id FROM instantorder WHERE id=:id FOR UPDATE"), {"id": oid}).one()
        with ThreadPoolExecutor(max_workers=1) as pool:
            future = pool.submit(waiter)
            try:
                assert ready.wait(5)
                wait_for_block(waiter_info["pid"], holder_pid)
                assert not future.done()
                assert writers.known.snapshot(oid) == original
                # Actual service owns its commits; no commit or handler replacement.
                isv.cancel_order(holder, oid, isv.Actor.passenger, passenger["id"])
                future.result(timeout=20)
            finally:
                holder.rollback()
    after = writers.known.snapshot(oid)
    assert after["status"] == "cancelled"
    assert after["search_revision"] == original["search_revision"]
    assert writers.pending(after) == (None, None, None)
    assert not any(thread == waiter_info["thread"] for thread, _ in effects)
    # Fixed terminal disposition; this does not activate a producer or consumer.


@pytest.mark.parametrize("commit_transfer", (False, True))
def test_pg_transfer_first_actual_cancel_waits_then_clears_pending(
    pending_case, commit_transfer
):
    make, passenger, _, _ = pending_case
    oid, original = make("advance")
    ready = Event()
    waiter_info = {}

    def cancel_waiter():
        with connection_session() as (session, pid):
            waiter_info["pid"] = pid
            ready.set()
            return isv.cancel_order(session, oid, isv.Actor.passenger, passenger["id"]).status

    with connection_session() as (holder, holder_pid):
        order = holder.get(InstantOrder, oid)
        assert isv._bump_search_revision(holder, order) == 42
        with ThreadPoolExecutor(max_workers=1) as pool:
            future = pool.submit(cancel_waiter)
            try:
                assert ready.wait(5)
                wait_for_block(waiter_info["pid"], holder_pid)
                assert not future.done()
                assert writers.known.snapshot(oid) == original
                holder.commit() if commit_transfer else holder.rollback()
                assert future.result(timeout=20) == "cancelled"
            finally:
                holder.rollback()
    after = writers.known.snapshot(oid)
    expected = 42 if commit_transfer else 41
    assert after["status"] == "cancelled"
    assert after["search_revision"] == expected
    assert writers.pending(after) == (None, None, None)
    # The explicit cancellation, not transfer itself, clears this obligation.


@pytest.mark.parametrize("following", ("bump", "cancel"))
def test_pg_actual_advance_first_then_bump_or_cancel_observes_committed_transfer(
    pending_case, following
):
    make, passenger, driver, now = pending_case
    oid, original = make("advance")
    transfer_ready = Event()
    release_commit = Event()
    follower_ready = Event()
    follower_done = Event()
    info = {}

    def advance_holder():
        with connection_session() as (session, pid):
            info["holder"] = pid
            # This ordering control uses the real default expiration policy.
            session.expire_on_commit = True

            def before_commit(current):
                # Observe real transfer SQL before the real commit. This event
                # only coordinates threads: it neither replaces nor rejects commit.
                row = current.connection().execute(
                    text("SELECT search_revision, matching_resume_revision FROM instantorder WHERE id=:id"),
                    {"id": oid},
                ).one()
                assert tuple(row) == (42, 42)
                transfer_ready.set()
                assert release_commit.wait(20)

            def after_commit(current):
                # Pause the continuation until the competing owner committed.
                # Default commit expiration forces fresh owner state afterwards.
                assert follower_done.wait(20)

            event.listen(session, "before_commit", before_commit, once=True)
            event.listen(session, "after_commit", after_commit, once=True)
            writers.invoke(session, oid, "advance", now, driver["id"])

    def follower():
        try:
            with connection_session() as (session, pid):
                info["follower"] = pid
                follower_ready.set()
                if following == "cancel":
                    isv.cancel_order(session, oid, isv.Actor.passenger, passenger["id"])
                else:
                    writers.invoke(session, oid, "bump", now)
                    session.commit()
        finally:
            follower_done.set()

    with ThreadPoolExecutor(max_workers=2) as pool:
        holder_future = pool.submit(advance_holder)
        try:
            assert transfer_ready.wait(5)
            follower_future = pool.submit(follower)
            assert follower_ready.wait(5)
            wait_for_block(info["follower"], info["holder"])
            assert not follower_future.done()
            assert writers.known.snapshot(oid) == original
            release_commit.set()
            follower_future.result(timeout=20)
            holder_future.result(timeout=20)
        finally:
            release_commit.set()
            follower_done.set()
    after = writers.known.snapshot(oid)
    expected = 43 if following == "bump" else 42
    assert after["status"] == ("searching" if following == "bump" else "cancelled")
    assert after["search_revision"] == expected
    assert writers.pending(after) == (
        (expected, original["matching_resume_next_at"], 3)
        if following == "bump" else (None, None, None)
    )
