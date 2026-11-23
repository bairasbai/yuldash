"""Inactive caller-transaction witness; no route endpoint owns this protocol yet."""
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
from datetime import timedelta
from threading import Event, get_ident
from uuid import uuid4

import pytest
from sqlalchemy import delete, event, select, update
from sqlalchemy.exc import OperationalError
from sqlmodel import Session

from app import taxi_route_edit_outcome as out
from app.db import engine
from app.models import InstantOrder, TaxiRouteEditOutcome
from app.timeutil import utcnow

TABLE = TaxiRouteEditOutcome.__table__
ORDER = InstantOrder.__table__


@pytest.fixture
def data(user_factory):
    owners = [user_factory("OutcomeOwner")["id"], user_factory("OutcomeOther")["id"]]
    with Session(engine) as s:
        orders = [InstantOrder(passenger_id=owners[i]) for i in (0, 0, 1)]
        s.add_all(orders)
        s.flush()
        order_ids = [row.id for row in orders]
        s.commit()
    now = utcnow()
    params = dict(owner_id=owners[0], order_id=order_ids[0], attempt_id=str(uuid4()),
                  binding=b"\x01" * 32, now=now)
    try:
        yield params, owners, order_ids
    finally:
        with engine.begin() as conn:
            conn.execute(delete(ORDER).where(ORDER.c.id.in_(order_ids)))


def _stage(conn, params, *, expires=None):
    return out.stage_committed(conn, **params, expires_at=expires or params["now"] + timedelta(minutes=3))


def _move(conn, order_id):
    conn.execute(update(ORDER).where(ORDER.c.id == order_id).values(to_text="synthetic changed route"))


@contextmanager
def _outer():
    with engine.begin() as conn:
        if conn.dialect.name == "sqlite":
            conn.exec_driver_sql("BEGIN")
        yield conn


def test_exact_duplicate_is_one_row_and_binding_conflict_unknown(data):
    params, _, _ = data
    with _outer() as conn:
        assert _stage(conn, params) is out.StageResult.STAGED
        _move(conn, params["order_id"])
        assert out.committed_evidence(engine, **params) is out.CommittedEvidence.UNKNOWN
    assert out.committed_evidence(engine, **params) is out.CommittedEvidence.COMMITTED
    with _outer() as conn:
        assert _stage(conn, params, expires=params["now"] + timedelta(days=90)) is out.StageResult.EXACT
        assert _stage(conn, params | {"binding": b"\x02" * 32}) is out.StageResult.UNKNOWN
        expiry, = conn.execute(select(TABLE.c.expires_at).where(TABLE.c.attempt_id == params["attempt_id"])).scalars().all()
        assert expiry == params["now"] + timedelta(minutes=3)
    assert out.committed_evidence(engine, **(params | {"binding": b"\x02" * 32})) is out.CommittedEvidence.UNKNOWN


def test_cross_order_and_owner_do_not_replay_or_grant_evidence(data):
    params, owners, ids = data
    with _outer() as conn:
        assert _stage(conn, params) is out.StageResult.STAGED
        _move(conn, ids[0])
    for probe in ({"owner_id": owners[1]}, {"order_id": ids[1]}, {"owner_id": owners[1], "order_id": ids[2]}):
        assert out.committed_evidence(engine, **(params | probe)) is out.CommittedEvidence.UNKNOWN
    with _outer() as conn:
        assert _stage(conn, params | {"owner_id": owners[1]}) is out.StageResult.UNKNOWN
        assert _stage(conn, params | {"order_id": ids[2]}) is out.StageResult.UNKNOWN
        # A genuinely new attempt scope can independently use the same key.
        for owner, order_id in ((owners[0], ids[1]), (owners[1], ids[2])):
            assert _stage(conn, params | {"owner_id": owner, "order_id": order_id}) is out.StageResult.STAGED
            _move(conn, order_id)
    assert out.committed_evidence(engine, **params) is out.CommittedEvidence.COMMITTED


def test_rollback_removes_both_receipt_and_paired_route_mutation(data):
    params, _, ids = data
    with pytest.raises(RuntimeError, match="simulated comparator refusal"):
        with _outer() as conn:
            assert _stage(conn, params) is out.StageResult.STAGED
            _move(conn, ids[0])
            raise RuntimeError("simulated comparator refusal")
    assert out.committed_evidence(engine, **params) is out.CommittedEvidence.UNKNOWN
    with _outer() as conn:
        assert conn.scalar(select(ORDER.c.to_text).where(ORDER.c.id == ids[0])) == ""
        assert _stage(conn, params) is out.StageResult.STAGED
        _move(conn, ids[0])
    assert out.committed_evidence(engine, **params) is out.CommittedEvidence.COMMITTED


def test_expiry_and_bounded_purge_are_unknown_not_negative_history(data):
    params, _, _ = data
    expired = params | {"now": params["now"] - timedelta(hours=1)}
    second = expired | {"attempt_id": str(uuid4())}
    with _outer() as conn:
        assert _stage(conn, expired) is out.StageResult.STAGED
        assert _stage(conn, second) is out.StageResult.STAGED
        assert _stage(conn, params | {"attempt_id": str(uuid4())}) is out.StageResult.STAGED
    with _outer() as conn:
        assert _stage(conn, params) is out.StageResult.UNKNOWN  # not renewed
        assert out.purge_expired(conn, cutoff=params["now"], limit=1) == 1
        assert out.purge_expired(conn, cutoff=params["now"], limit=1) == 1
    for key in (expired["attempt_id"], second["attempt_id"]):
        assert out.committed_evidence(engine, **(params | {"attempt_id": key})) is out.CommittedEvidence.UNKNOWN


def test_actual_order_cascade_and_internal_model_no_raw_fields(data):
    params, _, ids = data
    with _outer() as conn:
        assert _stage(conn, params) is out.StageResult.STAGED
        _move(conn, ids[0])
    with Session(engine) as s:
        row = s.get(TaxiRouteEditOutcome, (params["owner_id"], ids[0], params["attempt_id"]))
        assert row is not None and row.model_dump() == {}
        assert params["attempt_id"] not in repr(row)
    with engine.begin() as conn:
        conn.execute(delete(ORDER).where(ORDER.c.id == ids[0]))
        assert conn.scalar(select(TABLE.c.binding).where(TABLE.c.order_id == ids[0])) is None


def test_missing_transaction_unsafe_autocommit_and_read_error_do_not_classify(data):
    params, _, _ = data
    with engine.connect() as conn:
        with pytest.raises(out.OutcomeUnavailable, match="caller transaction"):
            _stage(conn, params)
    with engine.connect().execution_options(isolation_level="AUTOCOMMIT") as conn:
        with conn.begin(), pytest.raises(out.OutcomeUnavailable, match="caller transaction"):
            _stage(conn, params)
    assert out.committed_evidence(engine, **(params | {"binding": b"bad"})) is out.CommittedEvidence.UNKNOWN

    def fail_read(conn, cursor, statement, parameters, context, executemany):
        if "SELECT taxirouteeditoutcome.binding" in statement:
            raise OperationalError(None, None, RuntimeError("synthetic read uncertainty"))

    event.listen(engine, "before_cursor_execute", fail_read)
    try:
        assert out.committed_evidence(engine, **params) is out.CommittedEvidence.UNKNOWN
    finally:
        event.remove(engine, "before_cursor_execute", fail_read)


def test_competing_connection_cannot_stage_second_write_when_first_commits(data):
    params, _, ids = data
    attempted = Event()
    contender = {}

    def witness(conn, cursor, statement, parameters, context, executemany):
        if (get_ident() == contender.get("thread")
                and statement.startswith("INSERT INTO taxirouteeditoutcome")):
            attempted.set()

    def other():
        contender["thread"] = get_ident()
        with _outer() as conn:
            return _stage(conn, params)

    event.listen(engine, "before_cursor_execute", witness)
    try:
        with ThreadPoolExecutor(max_workers=1) as pool:
            with _outer() as winner:
                assert _stage(winner, params) is out.StageResult.STAGED
                _move(winner, ids[0])
                future = pool.submit(other)
                assert attempted.wait(5), "independent write never reached the database"
                assert not future.done(), "a second transaction acquired an uncommitted identity"
                assert out.committed_evidence(engine, **params) is out.CommittedEvidence.UNKNOWN
            assert future.result(timeout=10) is out.StageResult.EXACT
    finally:
        event.remove(engine, "before_cursor_execute", witness)
    assert out.committed_evidence(engine, **params) is out.CommittedEvidence.COMMITTED


@pytest.mark.parametrize("column", ("binding", "expires_at"))
def test_sqlite_dynamic_storage_corruption_is_not_committed_evidence(data, column):
    if engine.dialect.name != "sqlite":
        pytest.skip("SQLite storage classes can bypass declared column type")
    params, _, _ = data
    with _outer() as conn:
        assert _stage(conn, params) is out.StageResult.STAGED
        # The DB length guard alone cannot exclude length-32 *TEXT* in SQLite.
        value = "x" * 32 if column == "binding" else "malformed future timestamp"
        conn.exec_driver_sql(f"UPDATE taxirouteeditoutcome SET {column} = ? WHERE order_id = ?",
                             (value, params["order_id"]))
    assert out.committed_evidence(engine, **params) is out.CommittedEvidence.UNKNOWN


def test_sqlite_rejects_legacy_root_savepoint_before_it_can_commit(data):
    if engine.dialect.name != "sqlite":
        pytest.skip("SQLite legacy SAVEPOINT can commit without a physical root")
    params, _, ids = data
    with engine.connect() as conn:
        outer = conn.begin()
        raw = conn.connection.driver_connection
        assert not raw.in_transaction
        # RELEASE would commit this outermost physical SAVEPOINT despite the logical root.
        with conn.begin_nested() as nested:
            assert raw.in_transaction and conn.in_nested_transaction()
            with pytest.raises(out.OutcomeUnavailable, match="caller transaction"):
                _stage(conn, params)
            nested.commit()
        outer.rollback()
    assert out.committed_evidence(engine, **params) is out.CommittedEvidence.UNKNOWN
    with engine.connect() as conn:
        assert conn.scalar(select(ORDER.c.to_text).where(ORDER.c.id == ids[0])) == ""


def test_sqlite_rejects_fk_disabled_and_nonphysical_root(data):
    if engine.dialect.name != "sqlite":
        pytest.skip("SQLite physical transaction/FK PRAGMA")
    params, _, _ = data
    with engine.connect() as conn:
        conn.exec_driver_sql("PRAGMA foreign_keys=OFF")
        conn.commit()
        try:
            with conn.begin():
                assert conn.exec_driver_sql("PRAGMA foreign_keys").scalar_one() == 0
                conn.exec_driver_sql("BEGIN IMMEDIATE")
                with pytest.raises(out.OutcomeUnavailable, match="foreign keys"):
                    _stage(conn, params)
        finally:
            conn.exec_driver_sql("PRAGMA foreign_keys=ON")
            assert conn.exec_driver_sql("PRAGMA foreign_keys").scalar_one() == 1
            conn.commit()
    with engine.begin() as conn:
        assert not conn.connection.driver_connection.in_transaction
        with pytest.raises(out.OutcomeUnavailable, match="caller transaction"):
            _stage(conn, params)
    assert out.committed_evidence(engine, **params) is out.CommittedEvidence.UNKNOWN
