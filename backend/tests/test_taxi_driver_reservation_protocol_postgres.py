"""TEST-ONLY cooperative User→Order protocol and concrete counterexamples.

No production reservation, eligibility, admission, auth or mixed-fleet guarantee.
Setup initialization commits before this wrapper; never enter holding Order first.
"""
from concurrent.futures import ThreadPoolExecutor
from threading import Event

import pytest
from sqlalchemy import event,text
from sqlmodel import Session

from app.db import engine
from app.models import DriverProfile,UserRole
from app.taxi_offer_publication_lab import publish_sql_offer_lab
from app.taxi_tried_history_write import OwnershipMismatch
from test_taxi_search_revision_postgres import _disposable_pg_before_client  # noqa: F401
from test_taxi_tried_history_gc_postgres import orders,_rows,_blocked  # noqa: F401
from test_taxi_tried_history_init_postgres import init_orders,_snapshot  # noqa: F401
from test_taxi_offer_publication_postgres import publications as _publication_fixture,_args  # noqa: F401

pytestmark=pytest.mark.skipif(engine.dialect.name!="postgresql",reason="requires guarded disposable PostgreSQL")


class Busy(RuntimeError):
    """Test-only protocol result, not an application error/API."""


class MissingDriver(RuntimeError):
    """Test-only missing User rejection, not driver eligibility."""


@pytest.fixture
def protocol(request,user_factory):
    ids,captures=request.getfixturevalue("_publication_fixture")
    driver=user_factory("ReservationProtocolSyntheticDriver",role=UserRole.driver)
    assert all(_snapshot(oid)["passenger_id"]!=driver["id"] for oid in ids)
    args={oid:_args(oid,captures[oid])|{"driver_id":driver["id"]} for oid in ids}
    return ids,captures,driver,args


def _busy(connection,driver_id,target):
    # A separate statement AFTER actual User wait and target Order lock. No expiry
    # filter: an offered order stays busy until another operation reconciles it.
    return connection.execute(text("""SELECT EXISTS (
        SELECT 1 FROM instantorder WHERE id != :target AND
        ((status='offered' AND current_offer_driver_id=:driver) OR
         (status IN ('accepted','arriving','onboard') AND driver_id=:driver)))"""),
        {"target":target,"driver":driver_id}).scalar_one()


def _cooperative(connection,args):
    """Counterfactual all-participant discipline; caller must not own Order first."""
    user=connection.execute(text('SELECT id FROM "user" WHERE id=:id FOR NO KEY UPDATE'),
                            {"id":args["driver_id"]}).scalar_one_or_none()
    if user is None:raise MissingDriver()
    connection.execute(text("SELECT id FROM instantorder WHERE id=:id FOR UPDATE"),
                       {"id":args["order_id"]}).scalar_one()
    if _busy(connection,args["driver_id"],args["order_id"]):raise Busy()
    return publish_sql_offer_lab(connection,**args)


def _read_user(uid):
    with engine.connect() as c:return dict(c.execute(text('SELECT * FROM "user" WHERE id=:id'),{"id":uid}).mappings().one())


def _capture_user_wait(ready,pids):
    def capture(conn,cursor,statement,params,context,many):
        if "FOR NO KEY UPDATE" in statement:
            pids["waiter"]=cursor.connection.get_backend_pid();ready.set()
    return capture


@pytest.mark.parametrize("winner_commit",[False,True])
def test_cooperative_read_committed_serializes_commit_and_rollback(protocol,winner_commit):
    ids,captures,driver,args=protocol;first,second=ids
    before=dict(_snapshot(second));before_rows=_rows(second);ready=Event();pids={};trace=[]
    def waiter():
        with engine.connect().execution_options(isolation_level="READ COMMITTED") as c:
            txn=c.begin()
            assert c.execute(text("SHOW transaction_isolation")).scalar_one()=="read committed"
            try:
                if winner_commit:
                    with pytest.raises(Busy):_cooperative(c,args[second])
                    txn.rollback()  # Explicit business-unit rollback on rejection.
                    return "busy"
                result=_cooperative(c,args[second]);txn.commit();return result
            finally:
                if txn.is_active:txn.rollback()
    with engine.connect().execution_options(isolation_level="READ COMMITTED") as holder:
        txn=holder.begin();pid=holder.execute(text("SELECT pg_backend_pid()")).scalar_one()
        def trace_sql(conn,cursor,statement,params,context,many):trace.append(statement)
        event.listen(holder,"before_cursor_execute",trace_sql)
        try:_cooperative(holder,args[first])
        finally:event.remove(holder,"before_cursor_execute",trace_sql)
        assert "FOR NO KEY UPDATE" in trace[0] and "FOR UPDATE" in trace[1]
        assert "SELECT EXISTS" in trace[2]
        capture=_capture_user_wait(ready,pids);event.listen(engine,"before_cursor_execute",capture)
        with ThreadPoolExecutor(max_workers=1) as pool:
            future=pool.submit(waiter)
            try:
                assert ready.wait(5);_blocked(pids["waiter"],pid)
                if winner_commit:txn.commit()
                else:txn.rollback()
                result=future.result(timeout=12)
            finally:
                if txn.is_active:txn.rollback()
                event.remove(engine,"before_cursor_execute",capture)
    if winner_commit:
        assert result=="busy" and dict(_snapshot(second))==before and _rows(second)==before_rows
        assert _snapshot(first)["status"]=="offered" and _rows(first)==[(captures[first].lifecycle,driver["id"])]
    else:
        assert result.id==second and _snapshot(first)["status"]=="searching" and _rows(first)==[]
        assert _snapshot(second)["status"]=="offered" and _rows(second)==[(captures[second].lifecycle,driver["id"])]


@pytest.mark.parametrize("status",["offered","accepted","arriving","onboard"])
def test_busy_states_include_expired_offer(protocol,status):
    ids,captures,driver,args=protocol;first,second=ids;before=dict(_snapshot(second))
    with engine.begin() as c:
        c.execute(text("UPDATE instantorder SET status=:s,driver_id=:did,current_offer_driver_id=:did,offer_expires_at=clock_timestamp()-interval '1 hour' WHERE id=:id"),{"s":status,"did":driver["id"],"id":first})
    with engine.connect() as c:
        txn=c.begin()
        with pytest.raises(Busy):_cooperative(c,args[second])
        txn.rollback()
    assert dict(_snapshot(second))==before and _rows(second)==[]


@pytest.mark.parametrize("status",["done","cancelled","expired"])
def test_terminal_other_order_does_not_reserve_driver(protocol,status):
    ids,captures,driver,args=protocol;first,second=ids
    with engine.begin() as c:c.execute(text("UPDATE instantorder SET status=:s,driver_id=:did,current_offer_driver_id=:did WHERE id=:id"),{"id":first,"s":status,"did":driver["id"]})
    with engine.begin() as c:result=_cooperative(c,args[second])
    assert result.id==second and _rows(second)==[(captures[second].lifecycle,driver["id"])]


def test_missing_user_and_target_exclusion_do_not_bypass_storage_guards(protocol):
    ids,captures,driver,args=protocol;oid=ids[0];before=dict(_snapshot(oid))
    with engine.connect() as c:
        txn=c.begin()
        with pytest.raises(MissingDriver):_cooperative(c,args[oid]|{"driver_id":2147483647})
        txn.rollback()
    assert dict(_snapshot(oid))==before and _rows(oid)==[]
    with engine.begin() as c:c.execute(text("UPDATE instantorder SET status='offered',current_offer_driver_id=:did WHERE id=:id"),{"id":oid,"did":driver["id"]})
    before=dict(_snapshot(oid))
    with engine.connect() as c:
        txn=c.begin();assert _busy(c,driver["id"],oid) is False
        with pytest.raises(OwnershipMismatch):_cooperative(c,args[oid])
        txn.rollback()
    assert dict(_snapshot(oid))==before and _rows(oid)==[]


def test_diagnostic_mixed_bypass_commits_while_user_lock_remains_held(protocol):
    """Known all-writer participation failure, not safe coexistence."""
    ids,captures,driver,args=protocol;first,second=ids
    def bypass():
        with engine.begin() as c:
            pid=c.execute(text("SELECT pg_backend_pid()")).scalar_one()
            result=publish_sql_offer_lab(c,**args[second])
        return pid,result  # Return only after actual COMMIT.
    with engine.connect() as holder:
        txn=holder.begin();pid=holder.execute(text("SELECT pg_backend_pid()")).scalar_one()
        _cooperative(holder,args[first])
        with ThreadPoolExecutor(max_workers=1) as pool:
            future=pool.submit(bypass)
            try:
                other_pid,result=future.result(timeout=8)  # Must finish BEFORE User unlock.
                assert other_pid!=pid and result.id==second
                assert _snapshot(first)["status"]=="searching", "holder still uncommitted"
                assert _snapshot(second)["status"]=="offered"
                assert _rows(second)==[(captures[second].lifecycle,driver["id"])]
                txn.commit()
            finally:
                if txn.is_active:txn.rollback()
    assert all(_snapshot(oid)["current_offer_driver_id"]==driver["id"] for oid in ids)
    assert all(_rows(oid)==[(captures[oid].lifecycle,driver["id"])] for oid in ids)


def test_diagnostic_repeatable_read_old_snapshot_allows_two_publications(protocol):
    """Exact observed hypothesis: User lock alone does not refresh RR busy snapshot."""
    ids,captures,driver,args=protocol;first,second=ids;before_user=_read_user(driver["id"])
    snapshotted,ready=Event(),Event();pids={}
    def waiter():
        with engine.connect().execution_options(isolation_level="REPEATABLE READ") as c:
            txn=c.begin()
            try:
                assert c.execute(text("SHOW transaction_isolation")).scalar_one()=="repeatable read"
                assert _busy(c,driver["id"],second) is False
                assert c.execute(text("SELECT count(*) FROM instanttriedexclusion WHERE order_id IN (:a,:b)"),{"a":first,"b":second}).scalar_one()==0
                snapshotted.set()
                record=_cooperative(c,args[second]);txn.commit();return record
            finally:
                if txn.is_active:txn.rollback()
    with engine.connect() as holder:
        txn=holder.begin();pid=holder.execute(text("SELECT pg_backend_pid()")).scalar_one()
        _cooperative(holder,args[first])
        capture=_capture_user_wait(ready,pids);event.listen(engine,"before_cursor_execute",capture)
        with ThreadPoolExecutor(max_workers=1) as pool:
            future=pool.submit(waiter)
            try:
                assert snapshotted.wait(5) and ready.wait(5);_blocked(pids["waiter"],pid)
                txn.commit();record=future.result(timeout=12)  # 40001 is NOT swallowed as success.
            finally:
                if txn.is_active:txn.rollback()
                event.remove(engine,"before_cursor_execute",capture)
    assert record.id==second and _read_user(driver["id"])==before_user
    assert all(_snapshot(oid)["status"]=="offered" and _snapshot(oid)["current_offer_driver_id"]==driver["id"] for oid in ids)
    assert all(_rows(oid)==[(captures[oid].lifecycle,driver["id"])] for oid in ids)


def test_diagnostic_profile_online_update_bypasses_user_reservation_lock(protocol):
    """One concrete mutable admission table, not an audit of all eligibility."""
    ids,captures,driver,args=protocol
    with Session(engine) as s:
        profile=DriverProfile(user_id=driver["id"],online=True);s.add(profile);s.commit();s.refresh(profile);profile_id=profile.id
    try:
        def update_online():
            with engine.begin() as c:
                pid=c.execute(text("SELECT pg_backend_pid()")).scalar_one()
                c.execute(text("UPDATE driverprofile SET online=false WHERE id=:id"),{"id":profile_id})
            return pid
        with engine.connect() as holder:
            txn=holder.begin();pid=holder.execute(text("SELECT pg_backend_pid()")).scalar_one()
            holder.execute(text('SELECT id FROM "user" WHERE id=:id FOR NO KEY UPDATE'),{"id":driver["id"]})
            with ThreadPoolExecutor(max_workers=1) as pool:
                future=pool.submit(update_online)
                try:
                    assert future.result(timeout=8)!=pid
                    with engine.connect() as observer:
                        assert observer.execute(text("SELECT online FROM driverprofile WHERE id=:id"),{"id":profile_id}).scalar_one() is False
                    txn.rollback()  # Only release after real committed readback.
                finally:
                    if txn.is_active:txn.rollback()
    finally:
        with engine.begin() as c:c.execute(text("DELETE FROM driverprofile WHERE id=:id AND user_id=:uid"),{"id":profile_id,"uid":driver["id"]})
