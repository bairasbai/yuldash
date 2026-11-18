"""Uncalled pristine-created lab on guarded private PostgreSQL only."""
from concurrent.futures import ThreadPoolExecutor
from dataclasses import FrozenInstanceError
from datetime import timedelta
import re
from threading import Event

import pytest
from sqlalchemy import event,text
from sqlalchemy.exc import IntegrityError
from sqlmodel import Session

from app.db import engine
from app.models import InstantOrder
from app.taxi_tried_history_init import begin_first_sql_search
from app.taxi_tried_history_write import OwnershipMismatch,remember_tried_history
from app.taxi_tried_history_gc import collect_tried_history_batch
from test_taxi_search_revision_postgres import _disposable_pg_before_client  # noqa: F401
from test_taxi_tried_history_gc_postgres import orders,_rows,_add,_blocked,A  # noqa: F401

pytestmark=pytest.mark.skipif(engine.dialect.name!="postgresql",reason="requires guarded disposable PostgreSQL")


@pytest.fixture
def init_orders(request):
    ids=request.getfixturevalue("orders")
    with engine.begin() as c:
        for oid in ids:
            c.execute(text("UPDATE instantorder SET status='created',search_revision=NULL,tried_history_version=NULL,tried_lifecycle=NULL,tried_history_expires_at=NULL,search_round=0 WHERE id=:id"),{"id":oid})
    return ids


def _snapshot(oid):
    with engine.connect() as c:
        return c.execute(text("SELECT * FROM instantorder WHERE id=:id"),{"id":oid}).mappings().one_or_none()


@pytest.mark.parametrize("revision",[None,0,17,2**63-2])
def test_pristine_init_frozen_exact_fields_no_history_no_implicit_commit(init_orders,revision):
    oid=init_orders[0]
    with engine.begin() as c:c.execute(text("UPDATE instantorder SET search_revision=:r WHERE id=:id"),{"r":revision,"id":oid})
    before=dict(_snapshot(oid))
    with engine.connect() as c:
        txn=c.begin()
        result=begin_first_sql_search(c,oid,revision)
        assert result.id==oid and result.revision==(1 if revision is None else revision+1)
        assert re.fullmatch(r"[0-9a-f]{32}",result.lifecycle)
        assert result.expiry-result.searching_at==timedelta(hours=1)
        assert result.expiry.tzinfo is None and result.searching_at.tzinfo is None
        with pytest.raises(FrozenInstanceError):result.revision=999
        assert dict(_snapshot(oid))==before and _rows(oid)==[]
        txn.commit()
    after=dict(_snapshot(oid))
    changed={"status","search_revision","searching_at","search_round","tried_history_version","tried_lifecycle","tried_history_expires_at"}
    assert {k:v for k,v in after.items() if k not in changed}=={k:v for k,v in before.items() if k not in changed}
    assert after["status"]=="searching" and after["search_revision"]==result.revision
    assert after["searching_at"]==result.searching_at and after["tried_history_expires_at"]==result.expiry
    assert after["tried_lifecycle"]==result.lifecycle and after["tried_history_version"]==1 and after["search_round"]==0
    with engine.begin() as c:
        with pytest.raises(OwnershipMismatch):begin_first_sql_search(c,oid,result.revision)
    assert dict(_snapshot(oid))==after and _rows(oid)==[]


@pytest.mark.parametrize("field,value",[("status","searching"),("status","scheduled"),("status","offered"),
    ("status","accepted"),("status","arriving"),("status","onboard"),("status","done"),
    ("status","cancelled"),("status","expired"),("tried_history_version",0),("tried_history_version",1),
    ("tried_history_version",71),("tried_lifecycle",A),("tried_lifecycle",""),
    ("tried_history_expires_at","2030-01-02 03:04:05"),("offer_token",A),
    ("offer_expires_at","2030-01-02 03:04:05"),("search_round",1),("search_round",-1),
    ("search_revision",-1),("search_revision",2**63-1),("driver_id","owner"),("current_offer_driver_id","owner")])
def test_every_nonpristine_owner_is_typed_mismatch_without_mutation(init_orders,field,value):
    oid=init_orders[0]
    with engine.begin() as c:
        if value=="owner":value=c.execute(text("SELECT passenger_id FROM instantorder WHERE id=:id"),{"id":oid}).scalar_one()
        c.execute(text(f"UPDATE instantorder SET {field}=:value WHERE id=:id"),{"value":value,"id":oid})
    before=dict(_snapshot(oid))
    expected=2**63-1 if field=="search_revision" and value==2**63-1 else None
    with engine.begin() as c:
        with pytest.raises(OwnershipMismatch):begin_first_sql_search(c,oid,expected)
    assert dict(_snapshot(oid))==before and _rows(oid)==[]


@pytest.mark.parametrize("stored,expected",[(None,0),(0,None),(11,12)])
def test_revision_comparison_does_not_coalesce_or_adopt(init_orders,stored,expected):
    oid=init_orders[0]
    with engine.begin() as c:c.execute(text("UPDATE instantorder SET search_revision=:r WHERE id=:id"),{"r":stored,"id":oid})
    before=dict(_snapshot(oid))
    with engine.begin() as c:
        with pytest.raises(OwnershipMismatch):begin_first_sql_search(c,oid,expected)
        with pytest.raises(OwnershipMismatch):begin_first_sql_search(c,2147483647,None)
    assert dict(_snapshot(oid))==before


def test_any_history_rejects_even_null_mode_and_empty_current_cycle(init_orders):
    oid=init_orders[0];_add(oid,A,[7]);before=dict(_snapshot(oid))
    with engine.begin() as c:
        with pytest.raises(OwnershipMismatch):begin_first_sql_search(c,oid,None)
    assert dict(_snapshot(oid))==before and _rows(oid)==[(A,7)]


@pytest.mark.parametrize("fault",[False,True])
def test_rollback_or_update_fault_restores_init_and_preserves_unrelated_caller(init_orders,fault):
    oid,other=init_orders;before=dict(_snapshot(oid))
    def fail(conn,cursor,statement,parameters,context,many):
        if statement.lstrip().upper().startswith("UPDATE") and "instantorder" in statement:
            raise RuntimeError("synthetic after initializer UPDATE")
    with engine.connect() as c:
        txn=c.begin()
        c.execute(text("UPDATE instantorder SET comment='unrelated caller init' WHERE id=:id"),{"id":other})
        if fault:
            event.listen(c,"after_cursor_execute",fail)
            try:
                with pytest.raises(RuntimeError,match="synthetic after initializer"):begin_first_sql_search(c,oid,None)
            finally:event.remove(c,"after_cursor_execute",fail)
            assert c.execute(text("SELECT comment FROM instantorder WHERE id=:id"),{"id":other}).scalar_one()=="unrelated caller init"
            with engine.begin() as observer:
                observer.execute(text("SELECT id FROM instantorder WHERE id=:id FOR UPDATE NOWAIT"),{"id":oid})
            txn.commit()  # isolation assertion only: business callers must outer rollback on failure.
        else:
            begin_first_sql_search(c,oid,None);txn.rollback()
    assert dict(_snapshot(oid))==before and _rows(oid)==[]
    if fault:assert _snapshot(other)["comment"]=="unrelated caller init"


def test_inactive_and_actual_autocommit_rejected_before_sql(init_orders):
    for autocommit in (False,True):
        with engine.connect() as c:
            if autocommit:
                c.execution_options(isolation_level="AUTOCOMMIT");txn=c.begin()
                assert c.connection.driver_connection.autocommit is True
            calls=[]
            def capture(*args):calls.append(args)
            event.listen(c,"before_cursor_execute",capture)
            try:
                with pytest.raises(ValueError):begin_first_sql_search(c,init_orders[0],None)
                assert calls==[]
            finally:
                event.remove(c,"before_cursor_execute",capture)
                if autocommit:txn.rollback()


@pytest.mark.parametrize("outcome",["commit","rollback","deferred_failure","modeled_ack"])
def test_new_order_init_offer_and_write_share_one_business_transaction(init_orders,outcome):
    """Core composition model only, not actual matcher eligibility or publication."""
    with engine.connect() as c:
        passenger=c.execute(text("SELECT passenger_id FROM instantorder WHERE id=:id"),{"id":init_orders[0]}).scalar_one()
        c.commit()
        if outcome=="deferred_failure":
            c.execute(text("CREATE TEMP TABLE init_probe_parent(id INTEGER PRIMARY KEY) ON COMMIT PRESERVE ROWS"))
            c.execute(text("CREATE TEMP TABLE init_probe_child(owner INTEGER REFERENCES init_probe_parent(id) DEFERRABLE INITIALLY DEFERRED) ON COMMIT PRESERVE ROWS"))
            c.commit()
        try:
            txn=c.begin()
            oid=c.execute(InstantOrder.__table__.insert().values(passenger_id=passenger,status="created",
                from_lat=52.5,from_lng=58.3,to_lat=52.7,to_lng=58.6,price_estimate=300).returning(InstantOrder.id)).scalar_one()
            init_orders.append(oid)  # Shared fixture deletes only its own IDs if commit succeeds.
            result=begin_first_sql_search(c,oid,None)
            c.execute(text("UPDATE instantorder SET status='offered',current_offer_driver_id=:did WHERE id=:id"),{"did":passenger,"id":oid})
            remember_tried_history(c,oid,result.revision,result.lifecycle,"offered",passenger)
            assert _snapshot(oid) is None and _rows(oid)==[]
            if outcome=="deferred_failure":
                c.execute(text("INSERT INTO init_probe_child VALUES(-999)"))
                assert c.execute(text("SELECT count(*) FROM init_probe_child")).scalar_one()==1
                with pytest.raises(IntegrityError) as error:txn.commit()
                assert error.value.orig.pgcode=="23503";c.rollback()
                assert c.execute(text("SELECT count(*) FROM init_probe_child")).scalar_one()==0
            elif outcome=="rollback":txn.rollback()
            elif outcome=="modeled_ack":
                with pytest.raises(RuntimeError,match="modeled"):
                    txn.commit();raise RuntimeError("modeled ACK loss after actual commit")
                c.rollback()  # Cannot undo an actual committed business unit.
            else:txn.commit()
        finally:
            c.rollback()
            if outcome=="deferred_failure":
                c.execute(text("DROP TABLE IF EXISTS pg_temp.init_probe_child"))
                c.execute(text("DROP TABLE IF EXISTS pg_temp.init_probe_parent"));c.commit()
    if outcome in ("commit","modeled_ack"):
        after=_snapshot(oid)
        assert after["status"]=="offered" and after["search_revision"]==result.revision
        assert after["tried_lifecycle"]==result.lifecycle and _rows(oid)==[(result.lifecycle,passenger)]
    else:assert _snapshot(oid) is None and _rows(oid)==[]


@pytest.mark.parametrize("contender",["initializer","gc"])
def test_initializer_retains_owner_lock_until_caller_commit(init_orders,contender):
    oid=init_orders[0];ready=Event();pids={}
    def observe(conn,cursor,statement,parameters,context,many):
        if "FOR UPDATE" in statement.upper():
            pids["waiting"]=cursor.connection.get_backend_pid();ready.set()
    def second_init():
        with engine.begin() as c:
            with pytest.raises(OwnershipMismatch):begin_first_sql_search(c,oid,None)
            return "mismatch"
    with engine.connect() as holder:
        txn=holder.begin();pid=holder.execute(text("SELECT pg_backend_pid()")).scalar_one()
        result=begin_first_sql_search(holder,oid,None)
        remember_tried_history(holder,oid,result.revision,result.lifecycle,"searching",7)
        event.listen(engine,"before_cursor_execute",observe)
        with ThreadPoolExecutor(max_workers=1) as pool:
            future=pool.submit(second_init) if contender=="initializer" else pool.submit(collect_tried_history_batch,engine,oid)
            try:
                assert ready.wait(5);_blocked(pids["waiting"],pid)
                assert _snapshot(oid)["status"]=="created"
                txn.commit();answer=future.result(timeout=12)
            finally:
                if txn.is_active:txn.rollback()
                event.remove(engine,"before_cursor_execute",observe)
    assert answer==("mismatch" if contender=="initializer" else 0)
    assert _rows(oid)==[(result.lifecycle,7)] and _snapshot(oid)["tried_lifecycle"]==result.lifecycle


def test_initializer_clock_is_captured_after_real_owner_lock_wait(init_orders):
    oid=init_orders[0];ready=Event();pids={}
    def observe(conn,cursor,statement,parameters,context,many):
        if "FOR UPDATE" in statement.upper():
            pids["waiting"]=cursor.connection.get_backend_pid();ready.set()
    def initialize():
        with engine.begin() as c:return begin_first_sql_search(c,oid,None)
    with engine.connect() as holder:
        txn=holder.begin();pid=holder.execute(text("SELECT pg_backend_pid()")).scalar_one()
        holder.execute(text("SELECT id FROM instantorder WHERE id=:id FOR UPDATE"),{"id":oid})
        event.listen(engine,"before_cursor_execute",observe)
        with ThreadPoolExecutor(max_workers=1) as pool:
            future=pool.submit(initialize)
            try:
                assert ready.wait(5);_blocked(pids["waiting"],pid)
                started=holder.execute(text("SELECT xact_start AT TIME ZONE 'UTC' FROM pg_stat_activity WHERE pid=:pid"),{"pid":pids["waiting"]}).scalar_one()
                before_release=holder.execute(text("SELECT clock_timestamp() AT TIME ZONE 'UTC'")).scalar_one()
                assert started<before_release
                txn.commit();result=future.result(timeout=12)
            finally:
                if txn.is_active:txn.rollback()
                event.remove(engine,"before_cursor_execute",observe)
    assert result.searching_at>=before_release>started
    assert result.expiry==result.searching_at+timedelta(hours=1)


@pytest.mark.parametrize("flushed",[False,True])
def test_initializer_does_not_autoflush_or_commit_unrelated_orm_work(init_orders,flushed):
    oid,other=init_orders
    with Session(engine) as caller:
        unrelated=caller.get(InstantOrder,other);before=unrelated.comment
        unrelated.comment="synthetic pending initializer work"
        if flushed:caller.flush()
        result=begin_first_sql_search(caller.connection(),oid,None)
        if not flushed:assert unrelated in caller.dirty
        assert _snapshot(other)["comment"]==before
        assert _snapshot(oid)["status"]=="created"
        caller.commit()
    assert _snapshot(other)["comment"]=="synthetic pending initializer work"
    assert _snapshot(oid)["tried_lifecycle"]==result.lifecycle
