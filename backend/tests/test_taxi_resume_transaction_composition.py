"""TEST-ONLY transaction models, not account/worker/matcher integration.

Interruptions are modeled after/before caller commit, not process/network tests.
No helper below is imported by production code or claims a lease/reservation.
"""

import os
from pathlib import Path

PG_URL = os.environ.get("DATABASE_URL", "")
PG = PG_URL.startswith("postgresql")
ROOT = Path(os.environ.get("YULDASH_TEST_PG_DATA", ""))
if PG and (
    os.environ.get("YULDASH_TEST_PG_MODE") != "resume-composition"
    or not ROOT.is_absolute()
    or ROOT.name != "data"
    or ROOT.parent.parent != Path("/tmp")
    or not ROOT.parent.name.startswith("yuldash-revision-pg.")
):
    raise RuntimeError("Dedicated disposable resume-composition runner required")

from concurrent.futures import ThreadPoolExecutor
from dataclasses import fields, replace
from datetime import datetime, timedelta, timezone
from threading import Event
from time import monotonic, sleep
from uuid import uuid4

import pytest
from sqlalchemy import (
    Column,
    DateTime,
    Integer,
    MetaData,
    String,
    Table,
    create_engine,
    select,
    text,
)
from sqlalchemy.engine import make_url
from sqlalchemy.exc import OperationalError

from app.taxi_generation_adapter_lab import ExactGeneration, exact_next
from app.taxi_resume_policy_lab import (
    OwnerSnapshot,
    Pending,
    RetryPolicy,
    evaluate_resume_claim,
)

POLICY = RetryPolicy(30, 120)


class CasLost(RuntimeError):
    pass


def _values(owner):
    result = {
        f.name: getattr(owner, f.name) for f in fields(owner) if f.name != "pending"
    }
    result.update(
        pending_revision=owner.pending.revision,
        pending_next_at=owner.pending.next_at,
        pending_attempts=owner.pending.attempts,
    )
    return result


def _snapshot(row):
    data = dict(row)
    pending = Pending(
        data.pop("pending_revision"),
        data.pop("pending_next_at"),
        data.pop("pending_attempts"),
    )
    return OwnerSnapshot(**data, pending=pending)


def _begin(connection):
    if connection.dialect.name == "sqlite":
        connection.exec_driver_sql("BEGIN IMMEDIATE")
    else:
        connection.begin()


def _check_transaction(connection):
    """Check active transaction, NOT enforce arbitrary SQLite caller lock mode.

    BEGIN IMMEDIATE before first read is a caller precondition. Every model
    caller uses _begin; the BUSY contender test proves its writer lock before
    any fresh read. raw.in_transaction alone cannot prove IMMEDIATE mode.
    """
    assert connection.in_transaction()
    raw = connection.connection.driver_connection
    if connection.dialect.name == "postgresql":
        assert raw.autocommit is False
        assert connection.get_isolation_level() == "READ COMMITTED"
    else:
        assert raw.in_transaction


def _clock(connection):
    if connection.dialect.name == "postgresql":
        return (
            connection.execute(text("SELECT clock_timestamp()"))
            .scalar_one()
            .astimezone(timezone.utc)
            .replace(tzinfo=None)
        )
    return datetime.now(timezone.utc).replace(tzinfo=None)


def _fresh(connection, table, oid, lock=False):
    statement = select(table).where(table.c.id == oid)
    if lock:
        statement = statement.with_for_update()
    return _snapshot(connection.execute(statement).mappings().one())


def _cas(connection, table, expected, changes):
    statement = table.update()
    for name, value in _values(expected).items():
        statement = statement.where(table.c[name].is_not_distinct_from(value))
    count = connection.execute(statement.values(**changes)).rowcount
    if count != 1:
        raise CasLost("exact full owner and pending CAS did not affect one row")


def _claim(connection, table, captured):
    _check_transaction(connection)
    current = _fresh(connection, table, captured.id, lock=True)
    now = _clock(connection)  # Deliberately after the actual row-lock wait.
    result = evaluate_resume_claim(captured, current, now, POLICY)
    if result.outcome == "due":
        _cas(
            connection,
            table,
            current,
            {
                "pending_next_at": result.replacement_pending.next_at,
                "pending_attempts": result.replacement_pending.attempts,
            },
        )
    return result, now


def _transition(connection, table, captured, action):
    """Explicit test-local writer model; NOT application transition handlers.

    The offered action models provenance retention ONLY. It deliberately does
    not increment search_round or model matching-budget/admission semantics.
    """
    _check_transaction(connection)
    current = _fresh(connection, table, captured.id, lock=True)
    now = _clock(connection)
    if current != captured:
        raise CasLost("stale modeled writer")
    result = evaluate_resume_claim(current, current, now, POLICY)
    if result.outcome in {"invalid", "obsolete"} or current.pending.revision is None:
        raise ValueError("invalid modeled writer ownership")
    if action in {"transfer", "advance"}:
        if (action == "transfer" and current.status != "searching") or (
            action == "advance" and current.status != "offered"
        ):
            raise ValueError("invalid modeled writer source status")
        generation = exact_next(current.search_revision)
        changes = {
            "search_revision": generation,
            "pending_revision": generation,
            "pending_next_at": min(current.pending.next_at, now),
        }
        if action == "advance":
            changes.update(
                status="searching", current_offer_driver_id=None, offer_expires_at=None
            )
    elif action == "offered":
        assert current.status == "searching"
        changes = dict(
            status="offered",
            current_offer_driver_id=7,
            offer_token="b" * 32,
            offered_at=now,
            offer_expires_at=now + timedelta(seconds=30),
        )
    elif action == "terminal":
        changes = dict(
            status="cancelled",
            current_offer_driver_id=None,
            offer_expires_at=None,
            pending_revision=None,
            pending_next_at=None,
            pending_attempts=None,
        )
    else:
        raise ValueError("unknown modeled writer action")
    _cas(connection, table, current, changes)
    return _fresh(connection, table, current.id)


@pytest.fixture(params=["sqlite", "postgresql"])
def store(request, tmp_path):
    schema = None
    if request.param == "postgresql":
        if not PG:
            pytest.skip("dedicated disposable PostgreSQL mode required")
        url = make_url(PG_URL)
        assert url.drivername == "postgresql+psycopg2" and url.host == "127.0.0.1"
        assert (
            url.database == "yuldash_revision_fixture"
            and url.username == "yuldash_test"
        )
        assert url.port and not url.query
        engine = create_engine(
            url, hide_parameters=True, isolation_level="READ COMMITTED"
        )
        with engine.connect() as c:
            assert (
                Path(c.execute(text("SHOW data_directory")).scalar_one()).resolve()
                == ROOT.resolve()
            )
            assert (
                c.execute(text("SELECT current_database()")).scalar_one()
                == url.database
            )
            assert (
                str(c.execute(text("SELECT inet_server_addr()")).scalar_one())
                == url.host
            )
            assert c.execute(text("SELECT inet_server_port()")).scalar_one() == url.port
        schema = "resume_" + uuid4().hex
    else:
        engine = create_engine(
            "sqlite:///" + str(tmp_path / "resume.db"), connect_args={"timeout": 0.1}
        )
    metadata = MetaData(schema=schema)
    table = Table(
        "owner",
        metadata,
        Column("id", Integer, primary_key=True),
        Column("status", String, nullable=False),
        Column("search_revision", ExactGeneration()),
        Column("tried_lifecycle", String),
        Column("searching_at", DateTime),
        Column("search_round", Integer, nullable=False),
        Column("driver_id", Integer),
        Column("current_offer_driver_id", Integer),
        Column("offer_token", String),
        Column("offered_at", DateTime),
        Column("offer_expires_at", DateTime),
        Column("pending_revision", ExactGeneration()),
        Column("pending_next_at", DateTime),
        Column("pending_attempts", Integer),
    )
    notes = Table("unrelated", metadata, Column("id", Integer, primary_key=True))
    created = False
    try:
        if schema:
            with engine.begin() as c:
                c.execute(text(f'CREATE SCHEMA "{schema}"'))
            created = True
        metadata.create_all(engine)
        now = datetime.now(timezone.utc).replace(tzinfo=None) - timedelta(minutes=2)
        initial = OwnerSnapshot(
            1,
            "searching",
            10**40 + 1,
            None,
            now,
            2,
            None,
            None,
            "a" * 32,
            now,
            None,
            Pending(10**40 + 1, now, 0),
        )
        with engine.begin() as c:
            c.execute(table.insert().values(**_values(initial)))
        yield engine, table, notes, initial
    finally:
        try:
            if created:
                with engine.begin() as c:
                    c.execute(text(f'DROP SCHEMA "{schema}" CASCADE'))
            elif not schema:
                metadata.drop_all(engine)
        finally:
            engine.dispose()


def _observe(engine, table, caller=None):
    with engine.connect() as c:
        if caller is not None:
            assert (
                c.connection.driver_connection
                is not caller.connection.driver_connection
            )
        return _fresh(c, table, 1)


@pytest.mark.parametrize("commit", [False, True])
def test_claim_caller_commit_or_modeled_interruption(store, commit):
    engine, table, notes, initial = store
    with engine.connect() as c:
        _begin(c)
        c.execute(notes.insert().values(id=1))
        result, now = _claim(c, table, initial)
        assert result.outcome == "due"
        assert _observe(engine, table, c) == initial
        if commit:
            c.commit()  # Model interruption AFTER this real commit, no compensation.
        else:
            c.rollback()  # Model interruption BEFORE commit, no durable claim.
        observed = _observe(engine, table, c)
        assert observed == (
            replace(initial, pending=result.replacement_pending) if commit else initial
        )
        with engine.connect() as observer:
            assert list(observer.execute(select(notes.c.id)).scalars()) == (
                [1] if commit else []
            )
        _begin(c)
        again, _ = _claim(c, table, observed)
        assert again.outcome == ("not_due" if commit else "due")
        c.rollback()


@pytest.mark.parametrize("action", ["transfer", "terminal", "offered"])
@pytest.mark.parametrize("commit", [False, True])
def test_writer_models_commit_rollback_and_stale_owner(store, action, commit):
    engine, table, notes, initial = store
    with engine.connect() as c:
        _begin(c)
        c.execute(notes.insert().values(id=2))
        changed = _transition(c, table, initial, action)
        assert _observe(engine, table, c) == initial
        c.commit() if commit else c.rollback()
        assert _observe(engine, table, c) == (changed if commit else initial)
        _begin(c)
        result, _ = _claim(c, table, initial)
        assert result.outcome == ("obsolete" if commit else "due")
        c.rollback()
        with engine.connect() as observer:
            assert list(observer.execute(select(notes.c.id)).scalars()) == (
                [2] if commit else []
            )


def test_offered_provenance_then_modeled_advance(store):
    engine, table, _, initial = store
    with engine.connect() as c:
        _begin(c)
        offered = _transition(c, table, initial, "offered")
        assert offered.pending == initial.pending
        assert offered.search_round == initial.search_round  # Provenance-only model.
        c.commit()
        _begin(c)
        assert _claim(c, table, offered)[0].reason == "offered_retained"
        advanced = _transition(c, table, offered, "advance")
        assert (
            advanced.search_revision
            == advanced.pending.revision
            == exact_next(initial.search_revision)
        )
        assert advanced.pending.attempts == initial.pending.attempts
        assert advanced.offer_token == offered.offer_token
        c.commit()
        _begin(c)
        assert _claim(c, table, advanced)[0].outcome == "due"
        c.rollback()


STALE = {
    "status": "created",
    "search_revision": 10**40 + 2,
    "tried_lifecycle": "c" * 32,
    "searching_at": datetime(2020, 1, 1),
    "search_round": 3,
    "driver_id": 8,
    "current_offer_driver_id": 9,
    "offer_token": None,
    "offered_at": datetime(2020, 1, 2),
    "offer_expires_at": datetime(2030, 1, 1),
    "pending_revision": 10**40 + 2,
    "pending_next_at": datetime(2030, 1, 1),
    "pending_attempts": 1,
}


@pytest.mark.parametrize("field", list(STALE) + ["id"])
def test_actual_sql_cas_checks_each_field_independent_of_policy(store, field):
    engine, table, _, initial = store
    with engine.connect() as c:
        _begin(c)
        c.execute(
            table.update()
            .where(table.c.id == 1)
            .values(**{field: 2 if field == "id" else STALE[field]})
        )
        with pytest.raises(CasLost):
            _cas(c, table, initial, {"pending_attempts": 7})
        c.rollback()
    assert _observe(engine, table) == initial


def test_actual_trigger_zero_row_claim_is_not_success(store):
    engine, table, _, initial = store
    with engine.begin() as c:
        if table.schema:
            s = table.schema
            c.execute(
                text(
                    f'CREATE FUNCTION "{s}".skip_update() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RETURN NULL; END $$'
                )
            )
            c.execute(
                text(
                    f'CREATE TRIGGER skip_claim BEFORE UPDATE ON "{s}".owner FOR EACH ROW EXECUTE FUNCTION "{s}".skip_update()'
                )
            )
        else:
            c.execute(
                text(
                    "CREATE TRIGGER skip_claim BEFORE UPDATE ON owner BEGIN SELECT RAISE(IGNORE); END"
                )
            )
    with engine.connect() as c:
        _begin(c)
        with pytest.raises(CasLost):
            _claim(c, table, initial)
        assert c.in_transaction()  # Mandatory caller rollback, not helper rollback.
        c.rollback()
    assert _observe(engine, table) == initial


def test_transfer_budget_failure_is_before_write_and_requires_caller_rollback(store):
    engine, table, notes, initial = store
    maximum = 10**128 - 1
    boundary = replace(
        initial,
        search_revision=maximum,
        pending=replace(initial.pending, revision=maximum),
    )
    with engine.begin() as c:
        c.execute(table.update().values(**_values(boundary)))
    with engine.connect() as c:
        _begin(c)
        c.execute(notes.insert().values(id=4))
        with pytest.raises(ValueError):
            _transition(c, table, boundary, "transfer")
        assert c.in_transaction() and _fresh(c, table, 1) == boundary
        c.rollback()
    assert _observe(engine, table) == boundary
    with engine.connect() as c:
        assert list(c.execute(select(notes.c.id))) == []


def test_sqlite_begin_immediate_blocks_second_writer(store):
    engine, table, _, initial = store
    if engine.dialect.name != "sqlite":
        pytest.skip("SQLite lock model only")
    with engine.connect() as first, engine.connect() as second:
        _begin(first)
        assert (
            first.connection.driver_connection
            is not second.connection.driver_connection
        )
        with pytest.raises(OperationalError, match="locked"):
            _begin(second)
        second.rollback()
        first.rollback()
        _begin(second)
        assert _claim(second, table, initial)[0].outcome == "due"
        second.rollback()


def _blocked(engine, waiter, holder):
    deadline = monotonic() + 3
    while monotonic() < deadline:
        with engine.connect() as c:
            assert c.execute(text("SELECT pg_backend_pid()")).scalar_one() not in {
                waiter,
                holder,
            }
            if (
                holder
                in c.execute(
                    text("SELECT pg_blocking_pids(:pid)"), {"pid": waiter}
                ).scalar_one()
            ):
                return
        sleep(0.01)
    pytest.fail("actual PostgreSQL blocking not observed")


@pytest.mark.parametrize("action", ["claim", "transfer", "terminal"])
def test_pg_wait_stale_owner_and_postlock_clock(store, action):
    engine, table, _, initial = store
    if engine.dialect.name != "postgresql":
        pytest.skip("actual PostgreSQL concurrency only")
    started = Event()
    worker_info = {}
    with engine.connect() as holder:
        _begin(holder)
        holder_pid = holder.execute(text("SELECT pg_backend_pid()")).scalar_one()
        _fresh(holder, table, 1, lock=True)

        def waiter():
            with engine.connect() as c:
                _begin(c)
                worker_info["pid"] = c.execute(
                    text("SELECT pg_backend_pid()")
                ).scalar_one()
                worker_info["transaction_time"] = c.execute(
                    text("SELECT transaction_timestamp()")
                ).scalar_one()
                started.set()
                try:
                    result, clock = _claim(c, table, initial)
                    c.commit()
                    return result, clock
                finally:
                    if c.in_transaction():
                        c.rollback()

        with ThreadPoolExecutor(max_workers=1) as pool:
            future = pool.submit(waiter)
            try:
                assert started.wait(2)
                assert worker_info["pid"] != holder_pid
                _blocked(engine, worker_info["pid"], holder_pid)
                boundary = holder.execute(text("SELECT clock_timestamp()")).scalar_one()
                assert worker_info["transaction_time"] < boundary
                if action == "claim":
                    expected, _ = _claim(holder, table, initial)
                    after = replace(initial, pending=expected.replacement_pending)
                else:
                    after = _transition(holder, table, initial, action)
                holder.commit()
                result, observed_clock = future.result(timeout=5)
                assert observed_clock >= boundary.astimezone(timezone.utc).replace(
                    tzinfo=None
                )
                assert result.outcome == "obsolete"
                assert _observe(engine, table) == after
            finally:
                if holder.in_transaction():
                    holder.rollback()
                # Holder released before executor joins, even on assertion failure.


def test_actual_elapsed_cooldown_allows_fresh_second_claim(store, monkeypatch):
    engine, table, _, initial = store
    monkeypatch.setitem(globals(), "POLICY", RetryPolicy(1, 1))
    with engine.connect() as c:
        _begin(c)
        result, _ = _claim(c, table, initial)
        c.commit()
        committed = _observe(engine, table, c)
        assert committed.pending == result.replacement_pending
        _begin(c)
        assert _claim(c, table, committed)[0].reason == "cooldown"
        c.rollback()
        # Actual elapsed clock, not a forged completion or monkeypatched clock.
        deadline = monotonic() + 3
        while True:
            _begin(c)
            now = _clock(c)
            c.rollback()
            if now >= committed.pending.next_at:
                break
            assert monotonic() < deadline
            sleep(0.01)
        _begin(c)
        second, _ = _claim(c, table, committed)
        assert second.outcome == "due"
        assert second.replacement_pending.attempts == 2
        c.commit()
        assert _observe(engine, table, c).pending == second.replacement_pending


def test_modeled_advance_rollback_preserves_offered_provenance(store):
    engine, table, _, initial = store
    with engine.connect() as c:
        _begin(c)
        offered = _transition(c, table, initial, "offered")
        c.commit()
        _begin(c)
        changed = _transition(c, table, offered, "advance")
        assert changed.status == "searching"
        assert _observe(engine, table, c) == offered
        c.rollback()
        assert _observe(engine, table, c) == offered
