"""Private PG writer lab: caller-owned commit, savepoint recovery and actual locks.

This does not wire the primitive into any production matcher or enable SQL mode.
"""
from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta
from threading import Event
from time import monotonic, sleep

import pytest
from sqlalchemy import event, text
from sqlalchemy.exc import IntegrityError

from app.db import engine
from app.taxi_tried_history_write import OwnershipMismatch, remember_tried_history
from app.taxi_tried_history_gc import collect_tried_history_batch
from test_taxi_search_revision_postgres import _disposable_pg_before_client  # noqa: F401
from test_taxi_tried_history_gc_postgres import orders, _add, _rows, _owner, _blocked, A, B  # noqa: F401

pytestmark = pytest.mark.skipif(engine.dialect.name != "postgresql", reason="requires guarded disposable PostgreSQL")


@pytest.fixture
def write_orders(request):
    ids = request.getfixturevalue("orders")
    with engine.begin() as c:
        for oid in ids:
            c.execute(text("UPDATE instantorder SET status='searching',search_revision=11 WHERE id=:id"), {"id":oid})
    return ids


def _write(c, oid, **kwargs):
    args = dict(order_id=oid, expected_revision=11, expected_lifecycle=A,
                expected_status="searching", driver_id=7)
    args.update(kwargs)
    return remember_tried_history(c, **args)


def _expire(oid):
    with engine.begin() as c:
        c.execute(text("UPDATE instantorder SET tried_history_expires_at=clock_timestamp()-interval '1 hour' WHERE id=:id"), {"id":oid})


def test_future_duplicate_max_expiry_and_other_namespaces_preserved(write_orders):
    oid, other = write_orders
    _add(oid,A,[1]); _add(oid,B,[2]); _add(other,A,[3])
    with engine.begin() as c:
        c.execute(text("UPDATE instantorder SET tried_history_expires_at=clock_timestamp()+interval '3 hours' WHERE id=:id"), {"id":oid})
    before = _owner(oid)
    with engine.connect() as c:
        txn = c.begin()
        assert _write(c,oid) is None
        assert _rows(oid) == [(A,1),(B,2)], "helper must not commit caller transaction"
        assert _write(c,oid) is None
        txn.commit()
    assert _rows(oid) == [(A,1),(A,7),(B,2)] and _rows(other) == [(A,3)]
    assert _owner(oid) == before, "renew must not shorten an already later shared expiry"
    # Duplicate is still a renewal, not an early return.
    with engine.begin() as c:
        c.execute(text("UPDATE instantorder SET tried_history_expires_at=clock_timestamp()+interval '10 seconds' WHERE id=:id"), {"id":oid})
    before = _owner(oid)[2]
    with engine.begin() as c:
        _write(c,oid)
    assert _owner(oid)[2] > before + timedelta(minutes=59)
    assert _rows(oid) == [(A,1),(A,7),(B,2)]


def test_expired_reset_all_after_partial_gc_keeps_other_cycle_and_owner(write_orders):
    oid, other = write_orders
    _add(oid,A,range(601)); _add(oid,B,[900]); _add(other,A,[901]); _expire(oid)
    assert collect_tried_history_batch(engine,oid,2) == 2
    assert len(_rows(oid)) == 600
    with engine.begin() as c:
        _write(c,oid,driver_id=777)
    assert _rows(oid) == [(A,777),(B,900)] and _rows(other) == [(A,901)]
    assert _owner(oid)[0:2] == (1,A)


@pytest.mark.parametrize("field,value", [("search_revision",12),("tried_lifecycle",B),
    ("status","done"),("tried_history_version",None),("tried_history_version",0),
    ("tried_history_version",71),("tried_lifecycle",None),("tried_lifecycle","A"*32),
    ("tried_history_expires_at",None)])
def test_mismatched_or_invalid_owner_is_typed_exception_and_no_changes(write_orders, field, value):
    oid, other = write_orders
    _add(oid,A,[1])
    with engine.begin() as c:
        c.execute(text(f"UPDATE instantorder SET {field}=:value WHERE id=:id"), {"value":value,"id":oid})
    before = _owner(oid)
    with engine.begin() as c:
        c.execute(text("UPDATE instantorder SET comment='unrelated caller work' WHERE id=:id"), {"id":other})
        with pytest.raises(OwnershipMismatch): _write(c,oid)
        assert c.execute(text("SELECT comment FROM instantorder WHERE id=:id"), {"id":other}).scalar_one() == "unrelated caller work"
    assert _owner(oid) == before and _rows(oid) == [(A,1)]
    with engine.begin() as c:
        with pytest.raises(OwnershipMismatch): _write(c,2147483647)


@pytest.mark.parametrize("recipient", [7,8,None])
def test_offered_requires_actual_current_recipient(write_orders, recipient):
    oid, other = write_orders
    with engine.begin() as c:
        did = c.execute(text("SELECT passenger_id FROM instantorder WHERE id=:id"), {"id":oid}).scalar_one()
        if recipient == 8:
            # Real existing user, but not the driver ID passed to the primitive.
            offered_driver, expected_driver = did, did + 100000
        else:
            offered_driver, expected_driver = (did if recipient == 7 else None), did
        c.execute(text("UPDATE instantorder SET status='offered',current_offer_driver_id=:driver WHERE id=:id"), {"driver":offered_driver,"id":oid})
        if recipient == 7:
            assert _write(c,oid,expected_status="offered",driver_id=did) is None
        else:
            with pytest.raises(OwnershipMismatch): _write(c,oid,expected_status="offered",driver_id=expected_driver)
    assert _rows(oid) == ([(A,did)] if recipient == 7 else [])


@pytest.mark.parametrize("commit", [False,True])
def test_offered_update_and_history_are_same_caller_transaction(write_orders, commit):
    oid = write_orders[0]
    before = _owner(oid)
    with engine.connect() as c:
        txn = c.begin()
        did = c.execute(text("SELECT passenger_id FROM instantorder WHERE id=:id"), {"id":oid}).scalar_one()
        c.execute(text("UPDATE instantorder SET status='offered',current_offer_driver_id=:did WHERE id=:id"), {"id":oid,"did":did})
        _write(c,oid,expected_status="offered",driver_id=did)
        assert _rows(oid) == []
        if commit: txn.commit()
        else: txn.rollback()
    with engine.connect() as c:
        assert c.execute(text("SELECT status FROM instantorder WHERE id=:id"), {"id":oid}).scalar_one() == ("offered" if commit else "searching")
    assert _rows(oid) == ([(A,did)] if commit else [])
    if not commit: assert _owner(oid) == before


def test_real_deferred_commit_23503_rolls_back_offer_and_history(write_orders):
    oid = write_orders[0]
    with engine.connect() as c:
        c.execute(text("CREATE TEMP TABLE history_deferred_parent (id INTEGER PRIMARY KEY) ON COMMIT PRESERVE ROWS"))
        c.execute(text("CREATE TEMP TABLE history_deferred_probe (owner INTEGER REFERENCES history_deferred_parent(id) DEFERRABLE INITIALLY DEFERRED) ON COMMIT PRESERVE ROWS"))
        c.commit()  # Separate setup from the transaction whose COMMIT must fail.
        try:
            txn = c.begin()
            c.execute(text("INSERT INTO history_deferred_probe VALUES(-999)"))
            assert c.execute(text("SELECT count(*) FROM history_deferred_probe")).scalar_one() == 1
            did = c.execute(text("SELECT passenger_id FROM instantorder WHERE id=:id"), {"id":oid}).scalar_one()
            c.execute(text("UPDATE instantorder SET status='offered',current_offer_driver_id=:did WHERE id=:id"), {"id":oid,"did":did})
            _write(c,oid,expected_status="offered",driver_id=did)
            with pytest.raises(IntegrityError) as error: txn.commit()
            assert error.value.orig.pgcode == "23503"
            c.rollback()
            assert c.execute(text("SELECT count(*) FROM history_deferred_probe")).scalar_one() == 0
        finally:
            c.rollback()
            c.execute(text("DROP TABLE IF EXISTS pg_temp.history_deferred_probe"))
            c.execute(text("DROP TABLE IF EXISTS pg_temp.history_deferred_parent"))
            c.commit()
    assert _rows(oid) == []
    with engine.connect() as c:
        assert c.execute(text("SELECT status FROM instantorder WHERE id=:id"), {"id":oid}).scalar_one() == "searching"


def test_actual_commit_then_modeled_lost_ack_is_not_rolled_back(write_orders):
    """Synthetic post-commit exception, NOT a real network uncertainty test."""
    oid = write_orders[0]
    with engine.connect() as c:
        txn = c.begin()
        did = c.execute(text("SELECT passenger_id FROM instantorder WHERE id=:id"), {"id":oid}).scalar_one()
        c.execute(text("UPDATE instantorder SET status='offered',current_offer_driver_id=:did WHERE id=:id"), {"id":oid,"did":did})
        _write(c,oid,expected_status="offered",driver_id=did)
        with pytest.raises(RuntimeError, match="modeled"):
            txn.commit()
            raise RuntimeError("modeled lost acknowledgement AFTER actual commit")
        c.rollback()
    assert _rows(oid) == [(A,did)]
    with engine.connect() as c:
        assert c.execute(text("SELECT status FROM instantorder WHERE id=:id"), {"id":oid}).scalar_one() == "offered"


@pytest.mark.parametrize("stage", ["DELETE", "INSERT", "UPDATE"])
def test_fault_rolls_back_only_primitive_savepoint_and_preserves_caller(write_orders, stage):
    oid, other = write_orders
    _add(oid,A,[1]); _expire(oid)
    before = _owner(oid)
    def fail(conn,cursor,statement,parameters,context,many):
        target = "instantorder" if stage == "UPDATE" else "instanttriedexclusion"
        if statement.lstrip().upper().startswith(stage) and target in statement:
            raise RuntimeError("synthetic after primitive " + stage)
    with engine.connect() as c:
        txn = c.begin()
        c.execute(text("UPDATE instantorder SET comment='keep caller work' WHERE id=:id"), {"id":other})
        event.listen(c,"after_cursor_execute",fail)
        try:
            with pytest.raises(RuntimeError, match="synthetic after primitive"):_write(c,oid)
        finally: event.remove(c,"after_cursor_execute",fail)
        assert c.in_transaction()
        assert c.execute(text("SELECT comment FROM instantorder WHERE id=:id"), {"id":other}).scalar_one() == "keep caller work"
        assert _owner(oid) == before and _rows(oid) == [(A,1)]
        with engine.begin() as next_writer:
            caller_pid = c.execute(text("SELECT pg_backend_pid()")).scalar_one()
            assert next_writer.execute(text("SELECT pg_backend_pid()")).scalar_one() != caller_pid
            next_writer.execute(text("SELECT id FROM instantorder WHERE id=:id FOR UPDATE NOWAIT"), {"id":oid})
        # Catch-and-commit here tests isolation only; real business caller MUST rollback on error.
        txn.commit()
    with engine.connect() as c:
        assert c.execute(text("SELECT comment FROM instantorder WHERE id=:id"), {"id":other}).scalar_one() == "keep caller work"


def test_inactive_and_actual_autocommit_connections_rejected_before_sql(write_orders):
    oid = write_orders[0]
    for autocommit in (False,True):
        with engine.connect() as c:
            if autocommit:
                c.execution_options(isolation_level="AUTOCOMMIT")
                txn = c.begin()  # Logical transaction alone must not pass validation.
                assert c.connection.driver_connection.autocommit is True
            calls=[]
            def capture(*args): calls.append(args)
            event.listen(c,"before_cursor_execute",capture)
            try:
                with pytest.raises(ValueError): _write(c,oid)
                assert calls == []
            finally:
                event.remove(c,"before_cursor_execute",capture)
                if autocommit: txn.rollback()


@pytest.mark.parametrize("race", ["add", "duplicate", "revision", "lifecycle", "clock", "gc"])
def test_waiting_writer_or_gc_observes_locked_owner_and_postwait_clock(write_orders, race):
    oid = write_orders[0]
    _add(oid,A,[1]); _add(oid,B,[2])
    ready = Event(); pids = {}
    def capture(conn,cursor,statement,parameters,context,many):
        if "FOR UPDATE" in statement.upper():
            pids["waiting"] = cursor.connection.get_backend_pid(); ready.set()
    def waiting_writer():
        with engine.begin() as c:
            if race in ("revision", "lifecycle"):
                with pytest.raises(OwnershipMismatch): _write(c,oid,driver_id=8)
                return "mismatch"
            _write(c,oid,driver_id=7 if race == "duplicate" else 8)
            return "applied"
    with engine.connect() as holder:
        txn = holder.begin()
        holder_pid = holder.execute(text("SELECT pg_backend_pid()")).scalar_one()
        _write(holder,oid)
        if race == "revision":
            holder.execute(text("UPDATE instantorder SET search_revision=12 WHERE id=:id"), {"id":oid})
        elif race == "lifecycle":
            holder.execute(text("UPDATE instantorder SET tried_lifecycle=:l WHERE id=:id"), {"l":B,"id":oid})
        event.listen(engine,"before_cursor_execute",capture)
        with ThreadPoolExecutor(max_workers=1) as pool:
            future = pool.submit(collect_tried_history_batch,engine,oid) if race == "gc" else pool.submit(waiting_writer)
            try:
                assert ready.wait(5); _blocked(pids["waiting"],holder_pid)
                if race == "clock":
                    expiry = holder.execute(text("UPDATE instantorder SET tried_history_expires_at=(clock_timestamp() AT TIME ZONE 'UTC')+interval '250 milliseconds' WHERE id=:id RETURNING tried_history_expires_at"), {"id":oid}).scalar_one()
                    started = holder.execute(text("SELECT xact_start AT TIME ZONE 'UTC' FROM pg_stat_activity WHERE pid=:pid"), {"pid":pids["waiting"]}).scalar_one()
                    assert started < expiry
                    deadline = monotonic()+5
                    while monotonic()<deadline:
                        clock = holder.execute(text("SELECT clock_timestamp() AT TIME ZONE 'UTC'")).scalar_one()
                        if clock>=expiry: break
                        sleep(.02)
                    assert clock>=expiry
                txn.commit()
                result = future.result(timeout=12)
            finally:
                if txn.is_active: txn.rollback()
                event.remove(engine,"before_cursor_execute",capture)
    if race == "gc":
        assert result == 1 and _rows(oid) == [(A,1),(A,7)]
    elif race == "clock":
        assert result == "applied" and _rows(oid) == [(A,8),(B,2)]
    elif race in ("revision","lifecycle"):
        assert result == "mismatch" and _rows(oid) == [(A,1),(A,7),(B,2)]
    else:
        expected = [(A,1),(A,7),(B,2)] if race == "duplicate" else [(A,1),(A,7),(A,8),(B,2)]
        assert result == "applied" and _rows(oid) == expected


def test_gc_holds_lock_then_writer_resets_all_expired_remainder(write_orders):
    oid = write_orders[0]
    _add(oid,A,[1,2,3]); _expire(oid)
    held, release, waiting = Event(), Event(), Event(); pids = {}
    def hold_gc(conn,cursor,statement,parameters,context,many):
        if statement.lstrip().upper().startswith("DELETE") and "instanttriedexclusion" in statement and not held.is_set():
            pids["gc"] = cursor.connection.get_backend_pid(); held.set()
            assert release.wait(20)
    def writer():
        with engine.begin() as c:
            pids["writer"] = c.execute(text("SELECT pg_backend_pid()")).scalar_one()
            waiting.set()
            _write(c,oid,driver_id=8)
    event.listen(engine,"after_cursor_execute",hold_gc)
    try:
        with ThreadPoolExecutor(max_workers=2) as pool:
            gc = pool.submit(collect_tried_history_batch,engine,oid,1)
            try:
                assert held.wait(5)
                writing = pool.submit(writer)
                assert waiting.wait(5); _blocked(pids["writer"],pids["gc"])
            finally: release.set()
            assert gc.result(timeout=12) == 1
            writing.result(timeout=12)
    finally:
        release.set(); event.remove(engine,"after_cursor_execute",hold_gc)
    assert _rows(oid) == [(A,8)]


@pytest.mark.parametrize("flushed",[False,True])
def test_core_primitive_does_not_autoflush_or_commit_unrelated_orm_work(write_orders,flushed):
    from sqlmodel import Session
    from app.models import InstantOrder
    oid,other=write_orders
    with Session(engine) as caller:
        unrelated=caller.get(InstantOrder,other)
        before=unrelated.comment
        unrelated.comment="synthetic unrelated pending writer"
        if flushed:caller.flush()
        connection=caller.connection()
        assert _write(connection,oid) is None
        assert unrelated.comment=="synthetic unrelated pending writer"
        if not flushed:assert unrelated in caller.dirty
        with engine.connect() as observer:
            assert observer.execute(text("SELECT comment FROM instantorder WHERE id=:id"),{"id":other}).scalar_one()==before
        assert _rows(oid)==[]
        caller.commit()
    with Session(engine) as observer:
        assert observer.get(InstantOrder,other).comment=="synthetic unrelated pending writer"
    assert _rows(oid)==[(A,7)]
