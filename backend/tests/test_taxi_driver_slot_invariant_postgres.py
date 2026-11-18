"""Raw SQL fixture experiment, NOT an app migration, reservation API or admission.

Only nonce-schema synthetic rows. Cross-mode occupancy, safe rollout, deadlocks,
account deletion integration and eligibility remain outside this experiment.
"""
from concurrent.futures import ThreadPoolExecutor
from threading import Event
from time import monotonic,sleep
from uuid import uuid4

import pytest
from sqlalchemy import text
from sqlalchemy.exc import IntegrityError

from app.db import engine
from test_taxi_search_revision_postgres import _disposable_pg_before_client  # noqa: F401
from test_taxi_tried_history_gc_postgres import _blocked

pytestmark=pytest.mark.skipif(engine.dialect.name!="postgresql",reason="requires guarded disposable PostgreSQL")
STATUSES="'created','searching','scheduled','offered','accepted','arriving','onboard','done','cancelled','expired'"
CASE="CASE WHEN status='offered' THEN current_offer_driver_id WHEN status IN ('accepted','arriving','onboard') THEN driver_id ELSE NULL END"


@pytest.fixture
def slot_schema(request):
    # This session guard verifies DB name, data_directory, loopback host/port and
    # trusted runner marker BEFORE any CREATE. No production schema is touched.
    request.getfixturevalue("_disposable_pg_before_client")
    name="driver_slot_"+uuid4().hex
    assert name.startswith("driver_slot_") and all(ch in "0123456789abcdef" for ch in name[12:])
    schema=f'"{name}"'
    tables={kind:f'{schema}."{kind}"' for kind in ("slots","strict_slots","loose_slots","duplicates")}
    try:
        with engine.begin() as c:
            c.execute(text(f"CREATE SCHEMA {schema}"))
            for kind,table in tables.items():
                checks="" if kind=="loose_slots" else f", CONSTRAINT {kind}_status CHECK (status IN ({STATUSES}))"
                if kind=="strict_slots":
                    checks+=""", CONSTRAINT strict_slot_shape CHECK (
                        (status='offered' AND current_offer_driver_id IS NOT NULL AND driver_id IS NULL)
                        OR (status IN ('accepted','arriving','onboard') AND driver_id IS NOT NULL AND current_offer_driver_id IS NULL)
                        OR status IN ('created','searching','scheduled','done','cancelled','expired'))"""
                c.execute(text(f"CREATE TABLE {table} (id INTEGER PRIMARY KEY,status TEXT NOT NULL,driver_id INTEGER,current_offer_driver_id INTEGER,offer_expires_at TIMESTAMP WITHOUT TIME ZONE{checks})"))
                if kind!="duplicates":
                    index={"slots":"driver_slot_unique","strict_slots":"strict_slot_unique","loose_slots":"loose_slot_unique"}[kind]
                    c.execute(text(f'CREATE UNIQUE INDEX "{index}" ON {table} (({CASE}))'))
        yield tables
    finally:
        # Tests finish/rollback/join every worker before returning. A separate
        # healthy connection performs cleanup; errors are deliberately not hidden.
        with engine.begin() as cleanup:cleanup.execute(text(f"DROP SCHEMA IF EXISTS {schema} CASCADE"))
        with engine.connect() as observer:
            assert observer.execute(text("SELECT count(*) FROM pg_namespace WHERE nspname=:name"),{"name":name}).scalar_one()==0


def _write(c,table,row,status,driver=7,operation="insert"):
    values=dict(id=row,status=status,driver=driver if status in ("accepted","arriving","onboard") else None,
                recipient=driver if status=="offered" else None)
    if operation=="insert":
        c.execute(text(f"INSERT INTO {table}(id,status,driver_id,current_offer_driver_id) VALUES(:id,:status,:driver,:recipient)"),values)
    else:
        result=c.execute(text(f"UPDATE {table} SET status=:status,driver_id=:driver,current_offer_driver_id=:recipient WHERE id=:id"),values)
        assert result.rowcount==1


def _rows(table):
    with engine.connect() as c:return c.execute(text(f"SELECT id,status,driver_id,current_offer_driver_id FROM {table} ORDER BY id")).all()


def _error(error,code,constraint):
    assert error.value.orig.pgcode==code
    assert error.value.orig.diag.constraint_name==constraint


@pytest.mark.parametrize("isolation",["READ COMMITTED","REPEATABLE READ"])
@pytest.mark.parametrize("pair",[("offered","offered"),("offered","accepted"),("accepted","offered"),("accepted","onboard")])
@pytest.mark.parametrize("winner_commit",[False,True])
@pytest.mark.parametrize("operation",["insert","update"])
def test_cross_row_occupancy_conflict_waits_for_commit_or_rollback(slot_schema,isolation,pair,winner_commit,operation):
    table=slot_schema["slots"];ready=Event();pids={}
    if operation=="update":
        with engine.begin() as c:
            _write(c,table,1,"created");_write(c,table,2,"created")
    def contender():
        with engine.connect().execution_options(isolation_level=isolation) as c:
            txn=c.begin();pids["waiter"]=c.execute(text("SELECT pg_backend_pid()")).scalar_one()
            # Actual table read establishes RR snapshot before winner commit.
            initial=c.execute(text(f"SELECT id,status FROM {table} ORDER BY id")).all()
            assert initial==([] if operation=="insert" else [(1,"created"),(2,"created")])
            ready.set()
            try:
                if winner_commit:
                    with pytest.raises(IntegrityError) as caught:_write(c,table,2,pair[1],operation=operation)
                    _error(caught,"23505","driver_slot_unique");txn.rollback();return "conflict"
                _write(c,table,2,pair[1],operation=operation);txn.commit();return "won"
            finally:
                if txn.is_active:txn.rollback()
    with engine.connect() as holder:
        txn=holder.begin();pid=holder.execute(text("SELECT pg_backend_pid()")).scalar_one()
        _write(holder,table,1,pair[0],operation=operation)
        with ThreadPoolExecutor(max_workers=1) as pool:
            future=pool.submit(contender)
            try:
                assert ready.wait(5);_blocked(pids["waiter"],pid)
                if winner_commit:txn.commit()
                else:txn.rollback()
                assert future.result(timeout=12)==("conflict" if winner_commit else "won")
            finally:
                if txn.is_active:txn.rollback()
    rows=_rows(table)
    winner_id=1 if winner_commit else 2;winner_status=pair[0] if winner_commit else pair[1]
    occupied=[row for row in rows if row[1] in ("offered","accepted","arriving","onboard")]
    assert occupied==[(winner_id,winner_status,7 if winner_status!="offered" else None,7 if winner_status=="offered" else None)]
    if operation=="update":assert next(row for row in rows if row[0]!=(winner_id))[1:]==("created",None,None)


@pytest.mark.parametrize("release",[False,True])
@pytest.mark.parametrize("commit",[False,True])
def test_same_row_handoff_or_terminal_release_has_no_intermediate_slot_gap(slot_schema,release,commit):
    table=slot_schema["slots"]
    with engine.begin() as c:_write(c,table,1,"offered")
    ready=Event();pids={};second_wins=release and commit
    def contender():
        with engine.connect() as c:
            txn=c.begin();pids["waiter"]=c.execute(text("SELECT pg_backend_pid()")).scalar_one();ready.set()
            try:
                if second_wins:_write(c,table,2,"offered");txn.commit();return
                with pytest.raises(IntegrityError) as caught:_write(c,table,2,"offered")
                _error(caught,"23505","driver_slot_unique");txn.rollback()
            finally:
                if txn.is_active:txn.rollback()
    with engine.connect() as holder:
        txn=holder.begin();pid=holder.execute(text("SELECT pg_backend_pid()")).scalar_one()
        _write(holder,table,1,"done" if release else "accepted",operation="update")
        with ThreadPoolExecutor(max_workers=1) as pool:
            future=pool.submit(contender)
            try:
                assert ready.wait(5)
                # Offered→accepted preserves exactly the same unique key. PG may
                # reject immediately from the old committed key instead of waiting.
                if release:
                    _blocked(pids["waiter"],pid)
                else:
                    # Prove the attempt really ran while handoff was uncommitted:
                    # either exact conflict already returned or actual PG blocking.
                    deadline=monotonic()+1
                    while not future.done() and monotonic()<deadline:sleep(.01)
                    if future.done():future.result()
                    else:_blocked(pids["waiter"],pid)
                if commit:txn.commit()
                else:txn.rollback()
                future.result(timeout=12)
            finally:
                if txn.is_active:txn.rollback()
    rows=_rows(table)
    assert rows[0][1]==("done" if release and commit else "accepted" if commit else "offered")
    assert len(rows)==(2 if second_wins else 1)


def test_distinct_driver_terminal_history_and_expired_offer_controls(slot_schema):
    table=slot_schema["slots"]
    with engine.begin() as c:
        _write(c,table,1,"offered",7);_write(c,table,2,"accepted",8)
        for row,status in enumerate(("done","cancelled","expired"),3):
            c.execute(text(f"INSERT INTO {table}(id,status,driver_id,current_offer_driver_id) VALUES(:id,:s,7,7)"),{"id":row,"s":status})
        c.execute(text(f"UPDATE {table} SET offer_expires_at=clock_timestamp()-interval '1 hour' WHERE id=1"))
    with engine.connect() as c:
        with pytest.raises(IntegrityError) as caught:_write(c,table,9,"offered",7)
        _error(caught,"23505","driver_slot_unique");c.rollback()
    assert len(_rows(table))==5


def test_preexisting_duplicates_prevent_index_creation_without_dedup(slot_schema):
    table=slot_schema["duplicates"]
    with engine.begin() as c:_write(c,table,1,"offered");_write(c,table,2,"accepted")
    before=_rows(table)
    with engine.connect() as c:
        with pytest.raises(IntegrityError) as caught:
            c.execute(text(f'CREATE UNIQUE INDEX "duplicate_slot_unique" ON {table} (({CASE}))'))
        _error(caught,"23505","duplicate_slot_unique");c.rollback()
    assert _rows(table)==before


def test_index_only_null_keys_and_unknown_status_are_explicit_loopholes(slot_schema):
    table=slot_schema["loose_slots"]
    with engine.begin() as c:
        for row,status in enumerate(("offered","offered","accepted","onboard"),1):
            c.execute(text(f"INSERT INTO {table}(id,status) VALUES(:id,:s)"),{"id":row,"s":status})
        for row in (5,6):
            c.execute(text(f"INSERT INTO {table}(id,status,driver_id,current_offer_driver_id) VALUES(:id,'unknown',7,7)"),{"id":row})
    assert len(_rows(table))==6  # NOT a guarantee of valid row shape/admission.


@pytest.mark.parametrize("status,driver,recipient",[("offered",None,None),("offered",7,7),
    ("accepted",None,None),("arriving",7,7),("onboard",None,7)])
def test_shape_check_rejects_malformed_rows_without_null_three_valued_escape(slot_schema,status,driver,recipient):
    table=slot_schema["strict_slots"]
    with engine.connect() as c:
        with pytest.raises(IntegrityError) as caught:
            c.execute(text(f"INSERT INTO {table}(id,status,driver_id,current_offer_driver_id) VALUES(1,:s,:d,:r)"),{"s":status,"d":driver,"r":recipient})
        _error(caught,"23514","strict_slot_shape");c.rollback()
    assert _rows(table)==[]


def test_explicit_status_check_and_not_null(slot_schema):
    table=slot_schema["strict_slots"]
    with engine.connect() as c:
        # Isolate the status constraint: strict_slots would also violate shape,
        # and PostgreSQL need not report the status constraint first.
        status_only=slot_schema["slots"]
        with pytest.raises(IntegrityError) as caught:c.execute(text(f"INSERT INTO {status_only}(id,status) VALUES(1,'unknown')"))
        _error(caught,"23514","slots_status");c.rollback()
        with pytest.raises(IntegrityError) as caught:c.execute(text(f"INSERT INTO {table}(id,status) VALUES(2,NULL)"))
        assert caught.value.orig.pgcode=="23502" and caught.value.orig.diag.column_name=="status";c.rollback()
    with engine.begin() as c:
        for row,status in enumerate(("created","searching","scheduled","offered","accepted","arriving","onboard","done","cancelled","expired"),1):
            _write(c,table,row,status,driver=100+row)
    assert len(_rows(table))==10


def test_account_delete_shaped_clear_is_compatibility_blocker_not_api_test(slot_schema):
    table=slot_schema["strict_slots"]
    with engine.begin() as c:_write(c,table,1,"offered")
    before=_rows(table)
    with engine.connect() as c:
        with pytest.raises(IntegrityError) as caught:
            c.execute(text(f"UPDATE {table} SET current_offer_driver_id=NULL WHERE current_offer_driver_id=7"))
        _error(caught,"23514","strict_slot_shape");c.rollback()
    assert _rows(table)==before
