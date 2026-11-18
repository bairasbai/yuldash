"""Actual code compatibility diagnostics, only in a dedicated fresh PG cluster.

Temporary candidate DDL on disposable app tables is NOT a production migration.
Exceptions below are observed failures, not implemented HTTP409/next-candidate retry.
"""
from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta
import os
from threading import Event
from types import SimpleNamespace
from uuid import uuid4

import pytest
from sqlalchemy import text
from sqlalchemy.exc import IntegrityError
from sqlmodel import Session

from app import instant_service as isv
from app.db import engine
from app.models import InstantOrder,InstantOrderStatus as S,UserRole,SafetyProfile
from app.taxi_tried_history_init import begin_first_sql_search
from app.taxi_offer_publication_lab import publish_sql_offer_lab
from app.timeutil import utcnow
from test_taxi_search_revision_postgres import _disposable_pg_before_client  # noqa: F401
from test_taxi_status_never_resurrects import _order,fake_redis  # noqa: F401
from test_taxi_tried_history_gc_postgres import _blocked,_rows

pytestmark=pytest.mark.skipif(engine.dialect.name!="postgresql",reason="requires dedicated disposable PostgreSQL mode")
CASE="CASE WHEN status='offered' THEN current_offer_driver_id WHEN status IN ('accepted','arriving','onboard') THEN driver_id ELSE NULL END"


@pytest.fixture(scope="session",autouse=True)
def _dedicated_guard(request):
    if engine.dialect.name!="postgresql":return
    request.getfixturevalue("_disposable_pg_before_client")
    assert os.environ.get("YULDASH_TEST_PG_MODE")=="driver-slot-app-compat", "dedicated fresh runner mode required before DDL/lifespan"


@pytest.fixture
def own_orders(client):
    ids=[]
    def create(passenger,status,**kwargs):
        oid=_order(passenger["id"],status,**kwargs);ids.append(oid);return oid
    yield create
    with engine.begin() as c:
        for oid in ids:
            c.execute(text("DELETE FROM instanttriedexclusion WHERE order_id=:id"),{"id":oid})
            c.execute(text("DELETE FROM instantorder WHERE id=:id"),{"id":oid})


@pytest.fixture
def candidate(own_orders,request):
    request.getfixturevalue("_dedicated_guard")
    index="compat_slot_"+uuid4().hex
    check="compat_shape_"+uuid4().hex
    def install_check():
        with engine.begin() as c:
            c.execute(text(f'''ALTER TABLE public.instantorder ADD CONSTRAINT "{check}" CHECK (
                (status='offered' AND current_offer_driver_id IS NOT NULL AND driver_id IS NULL)
                OR (status IN ('accepted','arriving','onboard') AND driver_id IS NOT NULL AND current_offer_driver_id IS NULL)
                OR status NOT IN ('offered','accepted','arriving','onboard'))'''))
            definition=c.execute(text("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname=:name AND conrelid='public.instantorder'::regclass"),{"name":check}).scalar_one()
            assert "CHECK" in definition and "current_offer_driver_id IS NOT NULL" in definition
    try:
        with engine.begin() as c:
            c.execute(text(f'CREATE UNIQUE INDEX "{index}" ON public.instantorder (({CASE}))'))
            definition=c.execute(text("SELECT pg_get_indexdef(indexrelid),indisunique,indisvalid FROM pg_index JOIN pg_class ON pg_class.oid=indexrelid WHERE relname=:name"),{"name":index}).one()
            assert definition[1:] == (True,True)
            assert "CASE" in definition[0] and "current_offer_driver_id" in definition[0] and "driver_id" in definition[0]
            assert all(status in definition[0] for status in ("offered","accepted","arriving","onboard"))
        yield own_orders,index,check,install_check
    finally:
        # Dependence on own_orders guarantees DDL is removed BEFORE own data cleanup.
        # All worker contexts are joined and caller transactions rolled back first.
        with engine.begin() as c:
            c.execute(text(f'ALTER TABLE public.instantorder DROP CONSTRAINT IF EXISTS "{check}"'))
            c.execute(text(f'DROP INDEX IF EXISTS public."{index}"'))
        with engine.connect() as c:
            assert c.execute(text("SELECT count(*) FROM pg_class WHERE relname=:name"),{"name":index}).scalar_one()==0
            assert c.execute(text("SELECT count(*) FROM pg_constraint WHERE conname=:name"),{"name":check}).scalar_one()==0


@pytest.fixture
def own_safety_profiles():
    ids=[]
    def create(uid):
        with Session(engine) as session:
            row=SafetyProfile(user_id=uid,warnings=2,standing="good")
            session.add(row);session.commit();session.refresh(row);ids.append(row.id)
            return row.id
    yield create
    with engine.begin() as c:
        for sid in ids:c.execute(text("DELETE FROM safetyprofile WHERE id=:id"),{"id":sid})


def _snapshot(oid):
    with engine.connect() as c:return dict(c.execute(text("SELECT * FROM instantorder WHERE id=:id"),{"id":oid}).mappings().one())


def _error(error,code,name):
    assert error.value.orig.pgcode==code and error.value.orig.diag.constraint_name==name


@pytest.mark.parametrize("winner_commit",[False,True])
def test_actual_storage_publication_conflict_and_rollback(candidate,user_factory,winner_commit):
    create,index,check,install=candidate
    passenger=user_factory("CompatStoragePassenger");driver=user_factory("CompatStorageDriver",role=UserRole.driver)
    ids=[create(passenger,S.created) for _ in range(2)];captures={}
    with engine.begin() as c:
        for oid in ids:captures[oid]=begin_first_sql_search(c,oid,None)
    def args(oid):
        cap=captures[oid]
        return dict(order_id=oid,expected_revision=cap.revision,expected_lifecycle=cap.lifecycle,
            expected_searching_at=cap.searching_at,expected_search_round=0,expected_offer_token=None,
            driver_id=driver["id"],offer_ttl_seconds=30,max_offers=5)
    first,second=ids;before=_snapshot(second);ready=Event();pids={}
    def waiter():
        with engine.connect() as c:
            txn=c.begin();pids["waiter"]=c.execute(text("SELECT pg_backend_pid()")).scalar_one();ready.set()
            try:
                if winner_commit:
                    with pytest.raises(IntegrityError) as caught:publish_sql_offer_lab(c,**args(second))
                    _error(caught,"23505",index)
                    assert dict(c.execute(text("SELECT * FROM instantorder WHERE id=:id"),{"id":second}).mappings().one())==before
                    assert c.execute(text("SELECT count(*) FROM instanttriedexclusion WHERE order_id=:id"),{"id":second}).scalar_one()==0
                    txn.rollback();return "conflict"
                publish_sql_offer_lab(c,**args(second));txn.commit();return "won"
            finally:
                if txn.is_active:txn.rollback()
    with engine.connect() as holder:
        txn=holder.begin();pid=holder.execute(text("SELECT pg_backend_pid()")).scalar_one()
        publish_sql_offer_lab(holder,**args(first))
        with ThreadPoolExecutor(max_workers=1) as pool:
            future=pool.submit(waiter)
            try:
                assert ready.wait(5);_blocked(pids["waiter"],pid)
                if winner_commit:txn.commit()
                else:txn.rollback()
                assert future.result(timeout=12)==("conflict" if winner_commit else "won")
            finally:
                if txn.is_active:txn.rollback()
    winner=first if winner_commit else second;loser=second if winner_commit else first
    assert _snapshot(winner)["status"]=="offered" and _rows(winner)==[(captures[winner].lifecycle,driver["id"])]
    assert _snapshot(loser)["status"]=="searching" and _rows(loser)==[]
    if winner_commit:assert _snapshot(second)==before


@pytest.mark.usefixtures("fake_redis")
def test_actual_legacy_matcher_unique_conflict_precedes_redis_exec_and_dispatch(candidate,user_factory,monkeypatch):
    create,index,check,install=candidate
    passenger=user_factory("CompatLegacyPassenger");driver=user_factory("CompatLegacyDriver",role=UserRole.driver)
    create(passenger,S.offered,current_offer_driver_id=driver["id"],offer_token="a"*32,offer_expires_at=utcnow()+timedelta(minutes=1))
    oid=create(passenger,S.searching,search_revision=20,searching_at=utcnow(),search_round=0)
    redis=isv._redis();key=isv._tried_key(oid);before=_snapshot(oid);before_tried=redis.smembers(key)
    exec_calls=[];pushes=[];candidate_calls=[]
    original_pipeline=redis.pipeline
    def pipeline(*args,**kwargs):
        pipe=original_pipeline(*args,**kwargs);execute=pipe.execute
        def counted(*a,**k):exec_calls.append(True);return execute(*a,**k)
        pipe.execute=counted;return pipe
    monkeypatch.setattr(redis,"pipeline",pipeline)
    def forced(*args,**kwargs):candidate_calls.append(True);return [driver["id"]]
    monkeypatch.setattr(isv,"candidates",forced)  # Explicit stale-candidate TOCTOU model, not admission.
    monkeypatch.setattr(isv,"send_push",lambda *a,**k:pushes.append("offer"))
    monkeypatch.setattr("app.services.send_push",lambda *a,**k:pushes.append("status"))
    with Session(engine) as session:
        with pytest.raises(IntegrityError) as caught:isv.try_offer_next(session,session.get(InstantOrder,oid))
        _error(caught,"23505",index)
        session.rollback()  # Actual failed Session must be released, not reported as a handled409.
        assert session.get(InstantOrder,oid).status==S.searching
    assert candidate_calls==[True] and exec_calls==[] and pushes==[]
    assert redis.smembers(key)==before_tried and _snapshot(oid)==before and _rows(oid)==[]
    redis.delete(key)


@pytest.mark.parametrize("strict",[False,True])
def test_actual_account_delete_http_compatibility(candidate,user_factory,client,monkeypatch,strict,own_safety_profiles):
    create,index,check,install=candidate
    passenger=user_factory("CompatDeletePassenger");driver=user_factory("CompatDeleteDriver",role=UserRole.driver)
    oid=create(passenger,S.offered,current_offer_driver_id=driver["id"],offer_token="b"*32,offer_expires_at=utcnow()+timedelta(minutes=1))
    uid=driver["id"];before=_snapshot(oid)
    safety_id=own_safety_profiles(uid)
    with engine.connect() as c:
        applications=c.execute(text("SELECT count(*) FROM taxiapplication WHERE user_id=:id"),{"id":uid}).scalar_one()
        assert applications==1
        media_before=c.execute(text('SELECT u.avatar_url,t.selfie_url,t.permit_photo_url,t.osago_url,t.criminal_record_url FROM "user" u JOIN taxiapplication t ON t.user_id=u.id WHERE u.id=:id'),{"id":uid}).one()
        # User.avatar_url defaults to ""; the four TaxiApplication media fields
        # are Optional[str] default None. Preserve that exact shape, no coalescing.
        assert tuple(media_before)==("",None,None,None,None)
        safety_before=c.execute(text("SELECT id,user_id,warnings,standing FROM safetyprofile WHERE id=:id"),{"id":safety_id}).one()
        assert safety_before==(safety_id,uid,2,"good")
    external=[]
    monkeypatch.setattr("app.account._safe_unlink_media",lambda url:external.append(("unlink",url)))
    def storage():
        external.append("storage")
        return SimpleNamespace(iter_owned=lambda *args:[],delete=lambda *args:external.append("delete"))
    monkeypatch.setattr("app.account.get_storage",storage)
    monkeypatch.setattr(isv,"presence_offline",lambda *args:external.append("presence"))
    if strict:install()
    unauthorized=client.post("/me/delete")
    assert unauthorized.status_code==401 and _snapshot(oid)==before and external==[]
    if strict:
        # Actual server exception propagated by TestClient, NOT an observed HTTP500/409.
        with pytest.raises(IntegrityError) as caught:client.post("/me/delete",headers=driver["auth"])
        _error(caught,"23514",check)
        assert external==[] and _snapshot(oid)==before
        with engine.connect() as c:
            assert c.execute(text('SELECT count(*) FROM "user" WHERE id=:id'),{"id":uid}).scalar_one()==1
            assert c.execute(text("SELECT count(*) FROM taxiapplication WHERE user_id=:id"),{"id":uid}).scalar_one()==applications
            # SafetyProfile DELETE actually precedes the failing offered-clear UPDATE.
            assert c.execute(text("SELECT id,user_id,warnings,standing FROM safetyprofile WHERE id=:id"),{"id":safety_id}).one()==safety_before
        assert client.get("/me",headers=driver["auth"]).status_code==200
    else:
        response=client.post("/me/delete",headers=driver["auth"])
        assert response.status_code==200 and response.json()=={"ok":True}
        assert client.get("/me",headers=driver["auth"]).status_code==401
        with engine.connect() as c:
            assert c.execute(text('SELECT count(*) FROM "user" WHERE id=:id'),{"id":uid}).scalar_one()==0
            assert c.execute(text("SELECT count(*) FROM taxiapplication WHERE user_id=:id"),{"id":uid}).scalar_one()==0
            assert c.execute(text("SELECT count(*) FROM safetyprofile WHERE id=:id"),{"id":safety_id}).scalar_one()==0
        assert _snapshot(oid)["status"]=="offered" and _snapshot(oid)["current_offer_driver_id"] is None
        assert external==[("unlink",url) for url in media_before]+["storage","presence"]
