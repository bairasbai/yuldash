"""Single-candidate lab under actual private PG locks, not matcher integration."""
from concurrent.futures import ThreadPoolExecutor
from dataclasses import FrozenInstanceError
from threading import Event
from time import monotonic,sleep

import pytest
from sqlalchemy import event,text
from sqlalchemy.exc import DBAPIError,IntegrityError
from sqlmodel import Session

from app.db import engine
from app.models import InstantOrder
from app.taxi_tried_history_reader import inspect_tried_candidate
from app.taxi_tried_history_write import OwnershipMismatch,remember_tried_history
from app.taxi_tried_history_init import begin_first_sql_search
from test_taxi_search_revision_postgres import _disposable_pg_before_client  # noqa: F401
from test_taxi_tried_history_gc_postgres import orders,_add,_rows,_blocked,A,B  # noqa: F401
from test_taxi_tried_history_write_postgres import write_orders as _write_orders_fixture,_expire  # noqa: F401
from test_taxi_tried_history_init_postgres import init_orders  # noqa: F401

pytestmark=pytest.mark.skipif(engine.dialect.name!="postgresql",reason="requires guarded disposable PostgreSQL")


@pytest.fixture
def reader_orders(request):
    return request.getfixturevalue("_write_orders_fixture")


def _probe(c,oid,**kwargs):
    args=dict(order_id=oid,expected_revision=11,expected_lifecycle=A,driver_id=7);args.update(kwargs)
    return inspect_tried_candidate(c,**args)


def _snapshot(oid):
    with engine.connect() as c:return dict(c.execute(text("SELECT * FROM instantorder WHERE id=:id"),{"id":oid}).mappings().one())


@pytest.mark.parametrize("state,expected",[("present",True),("missing",False),("expired",False),("old",False),("other",False)])
def test_single_candidate_boolean_readonly_exact_namespace(reader_orders,state,expected):
    oid,other=reader_orders
    _add(other,A,[7]);_add(oid,B,[7])
    if state in ("present","expired"):_add(oid,A,[7])
    if state=="expired":_expire(oid)
    before=_snapshot(oid);rows=_rows(oid)
    with engine.begin() as c:
        result=_probe(c,oid)
        assert result.excluded is expected and result.id==oid and result.driver_id==7
        assert result.revision==11 and result.lifecycle==A and result.expiry==before["tried_history_expires_at"]
        assert result.observed_at.tzinfo is None
        with pytest.raises(FrozenInstanceError):result.excluded=True
    assert _snapshot(oid)==before and _rows(oid)==rows


def test_large_history_uses_boolean_exists_not_full_history_fetch(reader_orders):
    oid=reader_orders[0]
    with engine.begin() as c:
        c.execute(text("INSERT INTO instanttriedexclusion SELECT :id,:cycle,generate_series(1,2000)"),{"id":oid,"cycle":A})
    statements=[]
    def capture(conn,cursor,statement,params,context,many):statements.append(statement)
    with engine.begin() as c:
        event.listen(c,"before_cursor_execute",capture)
        try:assert _probe(c,oid,driver_id=1999).excluded is True
        finally:event.remove(c,"before_cursor_execute",capture)
    history_queries=[s for s in statements if "instanttriedexclusion" in s]
    assert len(history_queries)==1 and "EXISTS" in history_queries[0].upper()
    assert len(_rows(oid))==2000


@pytest.mark.parametrize("field,value",[("status","offered"),("status","done"),("search_revision",12),
    ("search_revision",None),("tried_lifecycle",B),("tried_lifecycle",None),("tried_lifecycle","A"*32),
    ("tried_history_version",None),("tried_history_version",0),("tried_history_version",71),
    ("tried_history_expires_at",None)])
def test_invalid_owner_raises_not_empty_history(reader_orders,field,value):
    oid=reader_orders[0];_add(oid,A,[7])
    with engine.begin() as c:c.execute(text(f"UPDATE instantorder SET {field}=:value WHERE id=:id"),{"id":oid,"value":value})
    before=_snapshot(oid)
    with engine.begin() as c:
        with pytest.raises(OwnershipMismatch):_probe(c,oid)
        with pytest.raises(OwnershipMismatch):_probe(c,2147483647)
    assert _snapshot(oid)==before and _rows(oid)==[(A,7)]


@pytest.mark.parametrize("change",["renew","revision","lifecycle","clock"])
def test_waiting_reader_sees_fresh_owner_or_propagates_mismatch(reader_orders,change):
    oid=reader_orders[0];_expire(oid);ready=Event();pids={}
    def capture(conn,cursor,statement,params,context,many):
        if "FOR UPDATE" in statement.upper():pids["reader"]=cursor.connection.get_backend_pid();ready.set()
    def read():
        with engine.begin() as c:
            if change in ("revision","lifecycle"):
                with pytest.raises(OwnershipMismatch):_probe(c,oid)
                return None
            return _probe(c,oid)
    with engine.connect() as holder:
        txn=holder.begin();pid=holder.execute(text("SELECT pg_backend_pid()")).scalar_one()
        remember_tried_history(holder,oid,11,A,"searching",7)
        if change=="revision":holder.execute(text("UPDATE instantorder SET search_revision=12 WHERE id=:id"),{"id":oid})
        if change=="lifecycle":holder.execute(text("UPDATE instantorder SET tried_lifecycle=:l WHERE id=:id"),{"id":oid,"l":B})
        event.listen(engine,"before_cursor_execute",capture)
        with ThreadPoolExecutor(max_workers=1) as pool:
            future=pool.submit(read)
            try:
                assert ready.wait(5);_blocked(pids["reader"],pid)
                if change=="clock":
                    expiry=holder.execute(text("UPDATE instantorder SET tried_history_expires_at=(clock_timestamp() AT TIME ZONE 'UTC')+interval '250 milliseconds' WHERE id=:id RETURNING tried_history_expires_at"),{"id":oid}).scalar_one()
                    started=holder.execute(text("SELECT xact_start AT TIME ZONE 'UTC' FROM pg_stat_activity WHERE pid=:pid"),{"pid":pids["reader"]}).scalar_one();assert started<expiry
                    deadline=monotonic()+5
                    while monotonic()<deadline:
                        clock=holder.execute(text("SELECT clock_timestamp() AT TIME ZONE 'UTC'")).scalar_one()
                        if clock>=expiry:break
                        sleep(.02)
                    assert clock>=expiry
                txn.commit();result=future.result(timeout=12)
            finally:
                if txn.is_active:txn.rollback()
                event.remove(engine,"before_cursor_execute",capture)
    if change in ("renew","clock"):
        assert result.excluded is (change=="renew")
        assert result.expiry==_snapshot(oid)["tried_history_expires_at"]
    assert _rows(oid)==[(A,7)]


def test_successful_reader_keeps_lock_until_outer_commit_and_false_is_not_reservation(reader_orders):
    oid=reader_orders[0];started=Event();pids={}
    def write():
        with engine.begin() as c:
            pids["writer"]=c.execute(text("SELECT pg_backend_pid()")).scalar_one();started.set()
            remember_tried_history(c,oid,11,A,"searching",7)
    with engine.connect() as holder:
        txn=holder.begin();pid=holder.execute(text("SELECT pg_backend_pid()")).scalar_one()
        assert _probe(holder,oid).excluded is False
        with ThreadPoolExecutor(max_workers=1) as pool:
            future=pool.submit(write)
            try:
                assert started.wait(5);_blocked(pids["writer"],pid)
                txn.commit();future.result(timeout=12)
            finally:
                if txn.is_active:txn.rollback()
    with engine.begin() as c:assert _probe(c,oid).excluded is True
    # Different unused candidate remains False across independent committed reads.
    for _ in range(2):
        with engine.begin() as c:assert _probe(c,oid,driver_id=8).excluded is False
    assert _rows(oid)==[(A,7)]


@pytest.mark.parametrize("preexisting_lock",[False,True])
def test_actual_query_error_propagates_and_only_new_savepoint_lock_is_released(reader_orders,preexisting_lock):
    oid=reader_orders[0]
    with engine.connect() as c:
        txn=c.begin();pid=c.execute(text("SELECT pg_backend_pid()")).scalar_one()
        if preexisting_lock:c.execute(text("SELECT id FROM instantorder WHERE id=:id FOR UPDATE"),{"id":oid})
        def error(conn,cursor,statement,params,context,many):
            if "EXISTS" in statement.upper() and "instanttriedexclusion" in statement:
                return "SELECT 1/0", ()  # Actual PG error, handled by SQLAlchemy normally.
            return statement,params
        event.listen(c,"before_cursor_execute",error,retval=True)
        try:
            with pytest.raises(DBAPIError) as caught:_probe(c,oid)
            assert caught.value.orig.pgcode=="22012"
        finally:event.remove(c,"before_cursor_execute",error)
        assert c.in_transaction() and c.execute(text("SELECT 1")).scalar_one()==1
        with engine.connect() as observer:
            assert observer.execute(text("SELECT pg_backend_pid()")).scalar_one()!=pid
            if preexisting_lock:
                with pytest.raises(DBAPIError) as locked:
                    observer.execute(text("SELECT id FROM instantorder WHERE id=:id FOR UPDATE NOWAIT"),{"id":oid})
                assert locked.value.orig.pgcode=="55P03";observer.rollback()
            else:
                observer.execute(text("SELECT id FROM instantorder WHERE id=:id FOR UPDATE NOWAIT"),{"id":oid});observer.rollback()
        txn.rollback()
    assert _rows(oid)==[]


def test_repeatable_read_serialization_failure_is_not_reported_as_false(reader_orders):
    oid=reader_orders[0]
    with engine.connect().execution_options(isolation_level="REPEATABLE READ") as reader:
        txn=reader.begin();pid=reader.execute(text("SELECT pg_backend_pid()")).scalar_one()
        reader.execute(text("SELECT search_revision FROM instantorder WHERE id=:id"),{"id":oid}).one()
        with engine.begin() as writer:
            assert writer.execute(text("SELECT pg_backend_pid()")).scalar_one()!=pid
            writer.execute(text("UPDATE instantorder SET tried_history_expires_at=tried_history_expires_at+interval '1 hour' WHERE id=:id"),{"id":oid})
        with pytest.raises(DBAPIError) as caught:_probe(reader,oid)
        assert caught.value.orig.pgcode=="40001"
        txn.rollback()
    assert _rows(oid)==[]


@pytest.mark.parametrize("flushed",[False,True])
def test_reader_never_autoflushes_or_commits_unrelated_orm_work(reader_orders,flushed):
    oid,other=reader_orders
    with Session(engine) as caller:
        unrelated=caller.get(InstantOrder,other);before=unrelated.comment
        unrelated.comment="synthetic pending reader work"
        if flushed:caller.flush()
        assert _probe(caller.connection(),oid).excluded is False
        if not flushed:assert unrelated in caller.dirty
        assert _snapshot(other)["comment"]==before
        caller.commit()
    assert _snapshot(other)["comment"]=="synthetic pending reader work"


@pytest.mark.parametrize("outcome",["commit","rollback","deferred_failure"])
def test_actual_init_probe_offer_write_composition_same_outer_transaction(request,outcome):
    ids=request.getfixturevalue("init_orders");oid=ids[0];before=_snapshot(oid)
    with engine.connect() as c:
        if outcome=="deferred_failure":
            c.execute(text("CREATE TEMP TABLE reader_probe_parent(id INTEGER PRIMARY KEY) ON COMMIT PRESERVE ROWS"))
            c.execute(text("CREATE TEMP TABLE reader_probe_child(owner INTEGER REFERENCES reader_probe_parent(id) DEFERRABLE INITIALLY DEFERRED) ON COMMIT PRESERVE ROWS"));c.commit()
        try:
            txn=c.begin();result=begin_first_sql_search(c,oid,None)
            did=before["passenger_id"]
            observation=inspect_tried_candidate(c,oid,result.revision,result.lifecycle,did)
            assert observation.excluded is False
            c.execute(text("UPDATE instantorder SET status='offered',current_offer_driver_id=:did WHERE id=:id"),{"id":oid,"did":did})
            remember_tried_history(c,oid,result.revision,result.lifecycle,"offered",did)
            assert _snapshot(oid)==before and _rows(oid)==[]
            if outcome=="deferred_failure":
                c.execute(text("INSERT INTO reader_probe_child VALUES(-999)"))
                assert c.execute(text("SELECT count(*) FROM reader_probe_child")).scalar_one()==1
                with pytest.raises(IntegrityError) as caught:txn.commit()
                assert caught.value.orig.pgcode=="23503";c.rollback()
            elif outcome=="rollback":txn.rollback()
            else:txn.commit()
        finally:
            c.rollback()
            if outcome=="deferred_failure":
                c.execute(text("DROP TABLE IF EXISTS pg_temp.reader_probe_child"))
                c.execute(text("DROP TABLE IF EXISTS pg_temp.reader_probe_parent"));c.commit()
    if outcome=="commit":
        assert _rows(oid)==[(result.lifecycle,did)]
        # Explicit test-only next-round state; not production integration.
        with engine.begin() as c:
            c.execute(text("UPDATE instantorder SET status='searching',search_revision=search_revision+1,current_offer_driver_id=NULL WHERE id=:id"),{"id":oid})
            assert inspect_tried_candidate(c,oid,result.revision+1,result.lifecycle,did).excluded is True
    else:assert _snapshot(oid)==before and _rows(oid)==[]


def test_inactive_and_actual_autocommit_rejected_before_sql(reader_orders):
    for autocommit in (False,True):
        with engine.connect() as c:
            if autocommit:
                c.execution_options(isolation_level="AUTOCOMMIT");txn=c.begin()
                assert c.connection.driver_connection.autocommit is True
            calls=[]
            def capture(*args):calls.append(args)
            event.listen(c,"before_cursor_execute",capture)
            try:
                with pytest.raises(ValueError):_probe(c,reader_orders[0])
                assert calls==[]
            finally:
                event.remove(c,"before_cursor_execute",capture)
                if autocommit:txn.rollback()



def test_expired_gc_then_reader_remains_false_without_renewal(reader_orders):
    from app.taxi_tried_history_gc import collect_tried_history_batch
    oid=reader_orders[0];_add(oid,A,[7]);_expire(oid)
    before=_snapshot(oid)
    assert collect_tried_history_batch(engine,oid)==1
    with engine.begin() as c:assert _probe(c,oid).excluded is False
    assert _snapshot(oid)==before and _rows(oid)==[]
