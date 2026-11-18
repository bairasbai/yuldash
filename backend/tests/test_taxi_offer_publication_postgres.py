"""Private PG storage finalizer lab. Success does NOT authorize a driver or push."""
from concurrent.futures import ThreadPoolExecutor
from dataclasses import FrozenInstanceError
from datetime import timedelta
from threading import Barrier,Event
from time import monotonic,sleep

import psycopg2
import pytest
from sqlalchemy import event,text
from sqlalchemy.exc import DBAPIError,IntegrityError
from sqlmodel import Session

from app import instant_service as isv
from app.db import engine
from app.models import InstantOrder
from app.taxi_offer_publication_lab import publish_sql_offer_lab,CandidateExcluded,OfferLimitReached
from app.taxi_tried_history_write import OwnershipMismatch
from app.taxi_tried_history_init import begin_first_sql_search
from app.taxi_tried_history_gc import collect_tried_history_batch
from test_taxi_search_revision_postgres import _disposable_pg_before_client  # noqa: F401
from test_taxi_tried_history_gc_postgres import orders,_add,_rows,_blocked,A  # noqa: F401
from test_taxi_tried_history_init_postgres import init_orders,_snapshot  # noqa: F401

pytestmark=pytest.mark.skipif(engine.dialect.name!="postgresql",reason="requires guarded disposable PostgreSQL")


@pytest.fixture
def publications(request):
    ids=request.getfixturevalue("init_orders")
    captures={}
    with engine.begin() as c:
        for oid in ids:captures[oid]=begin_first_sql_search(c,oid,None)
    return ids,captures


def _args(oid,capture):
    return dict(order_id=oid,expected_revision=capture.revision,expected_lifecycle=capture.lifecycle,
        expected_searching_at=capture.searching_at,expected_search_round=0,expected_offer_token=None,
        driver_id=_snapshot(oid)["passenger_id"],offer_ttl_seconds=30,max_offers=5)


def _publish(c,oid,capture,**changes):return publish_sql_offer_lab(c,**(_args(oid,capture)|changes))


@pytest.mark.parametrize("commit",[False,True])
def test_actual_init_publication_and_history_only_visible_on_outer_commit(publications,commit):
    ids,captures=publications;oid=ids[0];before=dict(_snapshot(oid));cap=captures[oid]
    with engine.connect() as c:
        txn=c.begin();record=_publish(c,oid,cap)
        assert record.id==oid and record.driver_id==before["passenger_id"]
        assert record.revision==cap.revision and record.lifecycle==cap.lifecycle
        assert record.offer_expires_at-record.offered_at==timedelta(seconds=30)
        assert record.search_round==1 and len(record.token)==32
        with pytest.raises(FrozenInstanceError):record.driver_id=0
        with Session(engine) as s:
            with pytest.raises(TypeError):isv._push_offer(s,record)
        assert dict(_snapshot(oid))==before and _rows(oid)==[]
        written=c.execute(text("SELECT tried_history_expires_at FROM instantorder WHERE id=:id"),{"id":oid}).scalar_one()
        assert record.history_expiry==written
        if commit:txn.commit()
        else:txn.rollback()
    after=dict(_snapshot(oid))
    if commit:
        assert after["status"]=="offered" and after["offer_token"]==record.token
        assert after["current_offer_driver_id"]==record.driver_id and after["offer_expires_at"]==record.offer_expires_at
        assert after["offered_at"]==record.offered_at and after["search_round"]==1
        allowed={"status","offer_token","current_offer_driver_id","offer_expires_at","offered_at","search_round","tried_history_expires_at"}
        assert {k:v for k,v in after.items() if k not in allowed}=={k:v for k,v in before.items() if k not in allowed}
        assert _rows(oid)==[(cap.lifecycle,record.driver_id)]
    else:assert after==before and _rows(oid)==[]


@pytest.mark.parametrize("field,value",[("status","cancelled"),("search_revision",999),("tried_lifecycle",A),
    ("tried_history_version",None),("tried_history_version",71),("tried_history_expires_at",None),
    ("searching_at",None),("searching_at","2030-01-02 03:04:05"),("search_round",1),
    ("offer_token",A),("driver_id","owner"),("current_offer_driver_id","owner"),("offer_expires_at","2030-01-02 03:04:05")])
def test_exact_tuple_and_clean_assignment_guards(publications,field,value):
    ids,captures=publications;oid=ids[0]
    if value=="owner":value=_snapshot(oid)["passenger_id"]
    with engine.begin() as c:c.execute(text(f"UPDATE instantorder SET {field}=:v WHERE id=:id"),{"id":oid,"v":value})
    before=dict(_snapshot(oid))
    with engine.begin() as c:
        with pytest.raises(OwnershipMismatch):_publish(c,oid,captures[oid])
    assert dict(_snapshot(oid))==before and _rows(oid)==[]


def test_previous_declined_token_and_maximum_valid_round(publications):
    ids,captures=publications;oid=ids[0]
    with engine.begin() as c:
        c.execute(text("UPDATE instantorder SET offer_token=:t,search_round=2147483646 WHERE id=:id"),{"id":oid,"t":A})
        record=_publish(c,oid,captures[oid],expected_offer_token=A,expected_search_round=2147483646,max_offers=2147483647)
    assert record.search_round==2147483647 and record.token!=A


@pytest.mark.parametrize("reason",["excluded","limit","overflow"])
def test_typed_policy_rejections_without_partial_changes(publications,reason):
    ids,captures=publications;oid=ids[0];cap=captures[oid];did=_snapshot(oid)["passenger_id"]
    if reason=="excluded":_add(oid,cap.lifecycle,[did])
    if reason=="limit":
        with engine.begin() as c:c.execute(text("UPDATE instantorder SET search_round=5 WHERE id=:id"),{"id":oid})
    before=dict(_snapshot(oid));rows=_rows(oid)
    expected={"excluded":CandidateExcluded,"limit":OfferLimitReached,"overflow":ValueError}[reason]
    changes={"expected_search_round":5} if reason=="limit" else ({"offer_ttl_seconds":10**30} if reason=="overflow" else {})
    with engine.begin() as c:
        with pytest.raises(expected):_publish(c,oid,cap,**changes)
    assert dict(_snapshot(oid))==before and _rows(oid)==rows


def test_expired_partial_gc_resets_all_but_old_cycle_and_other_order(publications):
    ids,captures=publications;oid,other=ids;cap=captures[oid];did=_snapshot(oid)["passenger_id"]
    _add(oid,cap.lifecycle,range(1,602));_add(oid,A,[777]);_add(other,captures[other].lifecycle,[did])
    with engine.begin() as c:c.execute(text("UPDATE instantorder SET tried_history_expires_at=clock_timestamp()-interval '1 hour' WHERE id=:id"),{"id":oid})
    assert collect_tried_history_batch(engine,oid,2)==2
    # GC ordering may include the old cycle; recreate it explicitly if removed.
    with engine.begin() as c:c.execute(text("INSERT INTO instanttriedexclusion VALUES(:id,:l,777) ON CONFLICT DO NOTHING"),{"id":oid,"l":A})
    with engine.begin() as c:_publish(c,oid,cap)
    assert set(_rows(oid))=={(cap.lifecycle,did),(A,777)}
    assert _rows(other)==[(captures[other].lifecycle,did)]


@pytest.mark.parametrize("stage",["offer","insert","renew"])
def test_actual_sql_failure_restores_outer_helper_savepoint(publications,stage):
    ids,captures=publications;oid,other=ids;before=dict(_snapshot(oid));seen=[]
    def fault(conn,cursor,statement,params,context,many):
        upper=statement.lstrip().upper()
        hit=(stage=="offer" and upper.startswith("UPDATE") and "status" in statement
             or stage=="insert" and upper.startswith("INSERT INTO instanttriedexclusion".upper())
             or stage=="renew" and upper.startswith("UPDATE") and "status" not in statement and "tried_history_expires_at" in statement)
        if hit:
            seen.append(stage);cursor.execute("SELECT 1/0")
    with engine.connect() as c:
        txn=c.begin();c.execute(text("UPDATE instantorder SET comment='unrelated finalizer caller' WHERE id=:id"),{"id":other})
        event.listen(c,"after_cursor_execute",fault)
        try:
            with pytest.raises((DBAPIError,psycopg2.Error)) as caught:_publish(c,oid,captures[oid])
            error=getattr(caught.value,"orig",caught.value);assert error.pgcode=="22012"
        finally:event.remove(c,"after_cursor_execute",fault)
        assert seen==[stage]
        assert c.execute(text("SELECT comment FROM instantorder WHERE id=:id"),{"id":other}).scalar_one()=="unrelated finalizer caller"
        assert dict(_snapshot(oid))==before and _rows(oid)==[]
        with engine.begin() as observer:observer.execute(text("SELECT id FROM instantorder WHERE id=:id FOR UPDATE NOWAIT"),{"id":oid})
        txn.rollback()


@pytest.mark.parametrize("change",["winner","cancel","revision","lifecycle","round","time","token","clock"])
def test_waiting_publication_rechecks_full_tuple_and_clock(publications,change):
    ids,captures=publications;oid=ids[0];cap=captures[oid];ready=Event();pids={}
    def observe(conn,cursor,statement,params,context,many):
        if "FOR UPDATE" in statement.upper():pids["waiting"]=cursor.connection.get_backend_pid();ready.set()
    def contender():
        with engine.begin() as c:
            if change=="clock":return _publish(c,oid,cap)
            with pytest.raises(OwnershipMismatch):_publish(c,oid,cap)
            return None
    with engine.connect() as holder:
        txn=holder.begin();pid=holder.execute(text("SELECT pg_backend_pid()")).scalar_one()
        holder.execute(text("SELECT id FROM instantorder WHERE id=:id FOR UPDATE"),{"id":oid})
        if change=="winner":_publish(holder,oid,cap)
        else:
            changes={"cancel":"status='cancelled'","revision":"search_revision=search_revision+1",
                "lifecycle":f"tried_lifecycle='{A}'","round":"search_round=search_round+1",
                "time":"searching_at=searching_at+interval '1 second'","token":f"offer_token='{A}'"}
            if change in changes:holder.execute(text(f"UPDATE instantorder SET {changes[change]} WHERE id=:id"),{"id":oid})
            if change=="clock":holder.execute(text("INSERT INTO instanttriedexclusion VALUES(:id,:l,:did)"),{"id":oid,"l":cap.lifecycle,"did":_snapshot(oid)["passenger_id"]})
        event.listen(engine,"before_cursor_execute",observe)
        with ThreadPoolExecutor(max_workers=1) as pool:
            future=pool.submit(contender)
            try:
                assert ready.wait(5);_blocked(pids["waiting"],pid)
                if change=="clock":
                    expiry=holder.execute(text("UPDATE instantorder SET tried_history_expires_at=(clock_timestamp() AT TIME ZONE 'UTC')+interval '250 milliseconds' WHERE id=:id RETURNING tried_history_expires_at"),{"id":oid}).scalar_one()
                    started=holder.execute(text("SELECT xact_start AT TIME ZONE 'UTC' FROM pg_stat_activity WHERE pid=:pid"),{"pid":pids["waiting"]}).scalar_one();assert started<expiry
                    deadline=monotonic()+5
                    while monotonic()<deadline:
                        clock=holder.execute(text("SELECT clock_timestamp() AT TIME ZONE 'UTC'")).scalar_one()
                        if clock>=expiry:break
                        sleep(.02)
                    assert clock>=expiry
                txn.commit();record=future.result(timeout=12)
            finally:
                if txn.is_active:txn.rollback()
                event.remove(engine,"before_cursor_execute",observe)
    if change=="clock":assert record.offered_at>=expiry and len(_rows(oid))==1
    elif change=="winner":assert _snapshot(oid)["status"]=="offered" and len(_rows(oid))==1
    else:assert _rows(oid)==[]


def test_diagnostic_two_orders_same_driver_are_not_reserved(publications):
    """Known limitation: storage owner locks do not prevent cross-order double offers."""
    ids,captures=publications;did=_snapshot(ids[0])["passenger_id"];barrier=Barrier(2)
    def publish(oid):
        with engine.begin() as c:
            pid=c.execute(text("SELECT pg_backend_pid()")).scalar_one()
            result=_publish(c,oid,captures[oid],driver_id=did)
            barrier.wait(timeout=10)
            return pid,result
    with ThreadPoolExecutor(max_workers=2) as pool:
        futures=[pool.submit(publish,oid) for oid in ids]
        results=[f.result(timeout=15) for f in futures]
    assert len({pid for pid,_ in results})==2
    assert all(_snapshot(oid)["status"]=="offered" and _snapshot(oid)["current_offer_driver_id"]==did for oid in ids)
    assert all(_rows(oid)==[(captures[oid].lifecycle,did)] for oid in ids)


@pytest.mark.parametrize("outcome",["deferred_failure","modeled_ack"])
def test_outer_commit_failure_vs_modeled_lost_ack(publications,outcome):
    ids,captures=publications;oid=ids[0];before=dict(_snapshot(oid))
    with engine.connect() as c:
        if outcome=="deferred_failure":
            c.execute(text("CREATE TEMP TABLE publication_parent(id INTEGER PRIMARY KEY) ON COMMIT PRESERVE ROWS"))
            c.execute(text("CREATE TEMP TABLE publication_child(owner INTEGER REFERENCES publication_parent(id) DEFERRABLE INITIALLY DEFERRED) ON COMMIT PRESERVE ROWS"));c.commit()
        try:
            txn=c.begin();record=_publish(c,oid,captures[oid])
            if outcome=="deferred_failure":
                c.execute(text("INSERT INTO publication_child VALUES(-999)"))
                assert c.execute(text("SELECT count(*) FROM publication_child")).scalar_one()==1
                with pytest.raises(IntegrityError) as error:txn.commit()
                assert error.value.orig.pgcode=="23503";c.rollback()
            else:
                with pytest.raises(RuntimeError,match="modeled"):
                    txn.commit();raise RuntimeError("modeled ACK loss AFTER actual commit")
                c.rollback()
        finally:
            c.rollback()
            if outcome=="deferred_failure":
                c.execute(text("DROP TABLE IF EXISTS pg_temp.publication_child"));c.execute(text("DROP TABLE IF EXISTS pg_temp.publication_parent"));c.commit()
    if outcome=="deferred_failure":assert dict(_snapshot(oid))==before and _rows(oid)==[]
    else:assert _snapshot(oid)["offer_token"]==record.token and len(_rows(oid))==1


def test_repeatable_read_serialization_error_propagates(publications):
    ids,captures=publications;oid=ids[0]
    with engine.connect().execution_options(isolation_level="REPEATABLE READ") as reader:
        txn=reader.begin();pid=reader.execute(text("SELECT pg_backend_pid()")).scalar_one()
        reader.execute(text("SELECT status FROM instantorder WHERE id=:id"),{"id":oid}).one()
        with engine.begin() as writer:
            assert writer.execute(text("SELECT pg_backend_pid()")).scalar_one()!=pid
            writer.execute(text("UPDATE instantorder SET tried_history_expires_at=tried_history_expires_at+interval '1 hour' WHERE id=:id"),{"id":oid})
        with pytest.raises(DBAPIError) as caught:_publish(reader,oid,captures[oid])
        assert caught.value.orig.pgcode=="40001";txn.rollback()
    assert _rows(oid)==[] and _snapshot(oid)["status"]=="searching"


@pytest.mark.parametrize("flushed",[False,True])
def test_unrelated_orm_pending_flushed_not_autoflushed_or_committed(publications,flushed):
    ids,captures=publications;oid,other=ids
    with Session(engine) as caller:
        unrelated=caller.get(InstantOrder,other);before=unrelated.comment
        unrelated.comment="synthetic publication caller"
        if flushed:caller.flush()
        _publish(caller.connection(),oid,captures[oid])
        if not flushed:assert unrelated in caller.dirty
        assert _snapshot(other)["comment"]==before and _snapshot(oid)["status"]=="searching"
        caller.commit()
    assert _snapshot(other)["comment"]=="synthetic publication caller"


def test_actual_autocommit_and_inactive_connection_rejected_before_sql(publications):
    ids,captures=publications;args=_args(ids[0],captures[ids[0]])
    for autocommit in (False,True):
        with engine.connect() as c:
            if autocommit:
                c.execution_options(isolation_level="AUTOCOMMIT");txn=c.begin()
                assert c.connection.driver_connection.autocommit is True
            calls=[]
            def capture(*args):calls.append(args)
            event.listen(c,"before_cursor_execute",capture)
            try:
                with pytest.raises(ValueError):publish_sql_offer_lab(c,**args)
                assert calls==[]
            finally:
                event.remove(c,"before_cursor_execute",capture)
                if autocommit:txn.rollback()


def test_init_and_finalizer_same_connection_without_redis_or_notification(request,monkeypatch):
    """Actual lab composition only; external admission/push are deliberately absent."""
    ids=request.getfixturevalue("init_orders");oid=ids[0];before=dict(_snapshot(oid))
    def forbidden(*args,**kwargs):raise AssertionError("storage primitive must not touch Redis or notification")
    monkeypatch.setattr(isv,"_redis",forbidden)
    monkeypatch.setattr(isv,"send_push",forbidden)
    monkeypatch.setattr("app.services.send_push",forbidden)
    monkeypatch.setattr("app.services.push_notification",forbidden)
    with engine.connect() as c:
        txn=c.begin();cap=begin_first_sql_search(c,oid,None)
        record=_publish(c,oid,cap)
        assert dict(_snapshot(oid))==before and _rows(oid)==[]
        txn.commit()
    assert _snapshot(oid)["status"]=="offered"
    assert _snapshot(oid)["search_revision"]==cap.revision==record.revision
    assert _rows(oid)==[(cap.lifecycle,record.driver_id)]
