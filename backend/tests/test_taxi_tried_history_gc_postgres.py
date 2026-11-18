"""Uncalled GC lab: real owner locks in the runner's disposable PostgreSQL only."""
from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta
from threading import Event
from time import monotonic, sleep

import pytest
from sqlalchemy import event, text
from sqlmodel import Session

from app.db import engine
from app.models import InstantOrder, InstantOrderStatus as S
from app.taxi_tried_history_gc import collect_tried_history_batch
from app.timeutil import utcnow
from test_taxi_search_revision_postgres import _disposable_pg_before_client  # noqa: F401
from test_taxi_status_never_resurrects import _order

pytestmark = pytest.mark.skipif(engine.dialect.name != "postgresql", reason="requires guarded disposable PostgreSQL")
A, B = "a" * 32, "b" * 32


@pytest.fixture
def orders(client, user_factory):
    passenger = user_factory("HistoryGCLabSynthetic")
    ids = [_order(passenger["id"], S.done, tried_history_version=1,
                  tried_lifecycle=A, tried_history_expires_at=utcnow() + timedelta(hours=1)) for _ in range(2)]
    yield ids
    with engine.begin() as connection:
        for oid in ids:
            connection.execute(text("DELETE FROM instanttriedexclusion WHERE order_id=:id"), {"id": oid})
            connection.execute(text("DELETE FROM instantorder WHERE id=:id"), {"id": oid})


def _add(oid, cycle, drivers):
    with engine.begin() as connection:
        for did in drivers:
            connection.execute(text("INSERT INTO instanttriedexclusion VALUES(:oid,:cycle,:did)"), {"oid": oid, "cycle": cycle, "did": did})


def _rows(oid):
    with engine.connect() as connection:
        return connection.execute(text("SELECT lifecycle,driver_id FROM instanttriedexclusion WHERE order_id=:id ORDER BY lifecycle,driver_id"), {"id": oid}).all()


def _owner(oid):
    with engine.connect() as connection:
        return connection.execute(text("SELECT tried_history_version,tried_lifecycle,tried_history_expires_at FROM instantorder WHERE id=:id"), {"id": oid}).one_or_none()


def _blocked(waiter, blocker):
    deadline = monotonic() + 8
    with engine.connect() as observer:
        observer_pid = observer.execute(text("SELECT pg_backend_pid()")).scalar_one()
        assert len({waiter, blocker, observer_pid}) == 3
        while monotonic() < deadline:
            if blocker in observer.execute(text("SELECT pg_blocking_pids(:pid)"), {"pid": waiter}).scalar_one():
                return
            sleep(.02)
    pytest.fail("actual pg_blocking_pids did not confirm owner lock contention")


def test_future_active_old_cycles_and_other_order_bounded_counts(orders):
    oid, other = orders
    _add(oid, A, [1, 2]); _add(oid, B, [3, 4, 5]); _add(other, B, [9])
    before = _owner(oid)
    assert collect_tried_history_batch(engine, oid, 2) == 2
    assert _rows(oid) == [(A, 1), (A, 2), (B, 5)]
    assert collect_tried_history_batch(engine, oid, 2) == 1
    assert collect_tried_history_batch(engine, oid, 2) == 0
    assert _rows(oid) == [(A, 1), (A, 2)] and _rows(other) == [(B, 9)]
    assert _owner(oid) == before


def test_expired_current_partial_batches_leave_remainder(orders):
    oid = orders[0]
    _add(oid, A, range(5))
    with engine.begin() as c:
        c.execute(text("UPDATE instantorder SET tried_history_expires_at=clock_timestamp()-interval '1 second' WHERE id=:id"), {"id": oid})
    before = _owner(oid)
    assert [collect_tried_history_batch(engine, oid, 2) for _ in range(4)] == [2, 2, 1, 0]
    assert _rows(oid) == [] and _owner(oid) == before


@pytest.mark.parametrize("version,cycle,expiry", [(None,A,True),(0,A,True),(-1,A,True),(71,A,True),
    (1,None,True),(1,"",True),(1,"A"*32,True),(1,"g"*32,True),(1,"a"*31,True),(1,A,False)])
def test_invalid_owner_noop(orders, version, cycle, expiry):
    oid = orders[0]
    _add(oid, B, [1])
    with engine.begin() as c:
        c.execute(text("UPDATE instantorder SET tried_history_version=:v,tried_lifecycle=:l,tried_history_expires_at=:e WHERE id=:id"),
                  {"v": version,"l":cycle,"e":utcnow()-timedelta(hours=1) if expiry else None,"id":oid})
    before = _owner(oid)
    assert collect_tried_history_batch(engine, oid) == 0
    assert _rows(oid) == [(B,1)] and _owner(oid) == before
    assert collect_tried_history_batch(engine, -1) == 0


@pytest.mark.parametrize("change", ["renew", "rotate", "expire_while_waiting", "delete_parent"])
def test_owner_writer_blocks_gc_and_fresh_state_clock_after_wait(orders, change):
    oid = orders[0]
    _add(oid, A, [1]); _add(oid, B, [2])
    if change == "renew":
        with engine.begin() as setup:
            setup.execute(text("UPDATE instantorder SET tried_history_expires_at=clock_timestamp()-interval '1 hour' WHERE id=:id"), {"id":oid})
    entered = Event(); pids = {}
    def capture(conn, cursor, statement, parameters, context, many):
        if "FOR UPDATE" in statement.upper():
            pids["gc"] = cursor.connection.get_backend_pid(); entered.set()
    with engine.connect() as holder:
        txn = holder.begin()
        holder_pid = holder.execute(text("SELECT pg_backend_pid()")).scalar_one()
        holder.execute(text("SELECT id FROM instantorder WHERE id=:id FOR UPDATE"), {"id":oid})
        if change == "renew":
            holder.execute(text("UPDATE instantorder SET tried_history_expires_at=clock_timestamp()+interval '1 hour' WHERE id=:id"), {"id":oid})
        elif change == "rotate":
            holder.execute(text("UPDATE instantorder SET tried_lifecycle=:cycle WHERE id=:id"), {"id":oid,"cycle":B})
        elif change == "delete_parent":
            holder.execute(text("DELETE FROM instantorder WHERE id=:id"), {"id":oid})
        event.listen(engine, "before_cursor_execute", capture)
        with ThreadPoolExecutor(max_workers=1) as pool:
            future = pool.submit(collect_tried_history_batch, engine, oid)
            try:
                assert entered.wait(5); _blocked(pids["gc"], holder_pid)
                if change == "expire_while_waiting":
                    # Only now, after proven blocking, set a future expiry. GC's
                    # transaction is already running, so transaction_timestamp()
                    # would be stale and preserve members incorrectly.
                    expiry = holder.execute(text(
                        "UPDATE instantorder SET tried_history_expires_at="
                        "(clock_timestamp() AT TIME ZONE 'UTC')+interval '250 milliseconds' "
                        "WHERE id=:id RETURNING tried_history_expires_at"
                    ), {"id": oid}).scalar_one()
                    started = holder.execute(text(
                        "SELECT xact_start AT TIME ZONE 'UTC' FROM pg_stat_activity WHERE pid=:pid"
                    ), {"pid": pids["gc"]}).scalar_one()
                    assert started is not None and started < expiry
                    deadline = monotonic() + 5
                    while monotonic() < deadline:
                        clock = holder.execute(text("SELECT clock_timestamp() AT TIME ZONE 'UTC'")).scalar_one()
                        if clock >= expiry:
                            break
                        sleep(.02)
                    assert clock >= expiry, "release only after the stored expiry elapsed"
                txn.commit()
                count = future.result(timeout=12)
            finally:
                if txn.is_active: txn.rollback()
                event.remove(engine, "before_cursor_execute", capture)
    expected = {"renew": (1, [(A,1)]), "rotate": (1, [(B,2)]), "expire_while_waiting": (2, []), "delete_parent": (0, [])}
    assert (count, _rows(oid)) == expected[change]


@pytest.mark.parametrize("fault", [False, True])
def test_gc_lock_blocks_writer_and_delete_error_rolls_back_and_releases(orders, fault):
    oid = orders[0]
    _add(oid, B, [1])
    held, release, writer_entered = Event(), Event(), Event()
    pids = {}
    def after_delete(conn, cursor, statement, parameters, context, many):
        if statement.lstrip().upper().startswith("DELETE") and "instanttriedexclusion" in statement:
            pids["gc"] = cursor.connection.get_backend_pid()
            held.set()
            assert release.wait(20)
            if fault:
                raise RuntimeError("synthetic failure after history DELETE")
    def writer():
        with engine.begin() as c:
            pids["writer"] = c.execute(text("SELECT pg_backend_pid()")).scalar_one()
            writer_entered.set()
            c.execute(text("SELECT id FROM instantorder WHERE id=:id FOR UPDATE"), {"id":oid})
            c.execute(text("INSERT INTO instanttriedexclusion VALUES(:id,:cycle,7)"), {"id":oid,"cycle":A})
            c.execute(text("UPDATE instantorder SET tried_history_expires_at=clock_timestamp()+interval '2 hours' WHERE id=:id"), {"id":oid})
    event.listen(engine, "after_cursor_execute", after_delete)
    try:
        with ThreadPoolExecutor(max_workers=2) as pool:
            gc = pool.submit(collect_tried_history_batch, engine, oid)
            try:
                assert held.wait(5)
                writing = pool.submit(writer)
                assert writer_entered.wait(5)
                _blocked(pids["writer"], pids["gc"])
            finally:
                release.set()
            if fault:
                with pytest.raises(RuntimeError, match="synthetic failure"):
                    gc.result(timeout=12)
            else:
                assert gc.result(timeout=12) == 1
            writing.result(timeout=12)
    finally:
        release.set()
        event.remove(engine, "after_cursor_execute", after_delete)
    assert _rows(oid) == ([(A,7),(B,1)] if fault else [(A,7)])


@pytest.mark.parametrize("flushed", [False, True])
def test_independent_gc_preserves_unrelated_pending_and_flushed_caller(orders, flushed):
    oid, other = orders
    _add(oid, B, [1])
    helper_pids = []
    def observe(conn, cursor, statement, parameters, context, many):
        if "FOR UPDATE" in statement.upper():
            helper_pids.append(cursor.connection.get_backend_pid())
    with Session(engine) as caller:
        caller_pid = caller.execute(text("SELECT pg_backend_pid()")).scalar_one()
        unrelated = caller.get(InstantOrder, other)
        unrelated.comment = "synthetic pending GC caller"
        caller.add(unrelated)
        if flushed: caller.flush()
        event.listen(engine, "before_cursor_execute", observe)
        try:
            assert collect_tried_history_batch(engine, oid) == 1
        finally:
            event.remove(engine, "before_cursor_execute", observe)
        assert helper_pids and all(pid != caller_pid for pid in helper_pids)
        assert unrelated.comment == "synthetic pending GC caller"
        caller.commit()
    with Session(engine) as observer:
        assert observer.get(InstantOrder, other).comment == "synthetic pending GC caller"
