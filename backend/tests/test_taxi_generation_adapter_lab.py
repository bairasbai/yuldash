"""Unconnected adapter tests; dedicated PG mode uses no application bootstrap."""

import os
from pathlib import Path

# Reject arbitrary PG collection before importing database libraries or opening SQL.
_PG_URL = os.environ.get("DATABASE_URL", "")
_PG = _PG_URL.startswith("postgresql")
if _PG:
    _root = Path(os.environ.get("YULDASH_TEST_PG_DATA", ""))
    if (
        os.environ.get("YULDASH_TEST_PG_MODE") != "generation-adapter"
        or not _root.is_absolute()
        or _root.name != "data"
        or _root.parent.parent != Path("/tmp")
        or not _root.parent.name.startswith("yuldash-revision-pg.")
    ):
        raise RuntimeError("Dedicated disposable generation-adapter runner required")


from concurrent.futures import ThreadPoolExecutor
from decimal import Decimal
from threading import Event
from time import monotonic, sleep
from uuid import uuid4

import pytest
from sqlalchemy import Column, MetaData, create_engine, select, text, update
from sqlalchemy.engine import make_url
from sqlalchemy.exc import OperationalError, StatementError
from sqlmodel import SQLModel, Field, Session

from app.taxi_generation_adapter_lab import (
    ExactGeneration,
    decode_decimal,
    decode_text,
    exact_next,
    validate_integer,
)

MAX = 2**63 - 1
VALUES = [
    None,
    -2,
    -1,
    0,
    2**53 - 1,
    2**53 + 1,
    MAX - 1,
    MAX,
    MAX + 1,
    10**40 - 1,
    10**40 + 1,
]


class LabBase(SQLModel):
    metadata = MetaData()


class Counter(LabBase, table=True):
    __tablename__ = "adapter_counter"
    id: int = Field(primary_key=True)
    revision: int | None = Field(
        default=None, sa_column=Column(ExactGeneration(), nullable=True)
    )
    resume: int | None = Field(
        default=None, sa_column=Column(ExactGeneration(), nullable=True)
    )


@pytest.fixture
def pg_schema():
    if not _PG:
        pytest.skip("dedicated disposable PostgreSQL mode required")
    url = make_url(_PG_URL)
    assert url.drivername == "postgresql+psycopg2" and url.host == "127.0.0.1"
    assert url.database == "yuldash_revision_fixture" and url.username == "yuldash_test"
    assert url.port and not url.query
    engine = create_engine(url, hide_parameters=True)
    name = "generation_" + uuid4().hex
    created = False
    try:
        with engine.begin() as c:
            assert (
                Path(c.execute(text("SHOW data_directory")).scalar_one()).resolve()
                == _root.resolve()
            )
            assert (
                c.execute(text("SELECT current_database()")).scalar_one()
                == "yuldash_revision_fixture"
            )
            assert (
                str(c.execute(text("SELECT inet_server_addr()")).scalar_one())
                == "127.0.0.1"
            )
            assert c.execute(text("SELECT inet_server_port()")).scalar_one() == url.port
            c.execute(text(f'CREATE SCHEMA "{name}"'))
            created = True
        yield engine, name
    finally:
        try:
            if created:
                with engine.begin() as c:
                    c.execute(text(f'DROP SCHEMA IF EXISTS "{name}" CASCADE'))
                with engine.connect() as c:
                    assert (
                        c.execute(
                            text(
                                "SELECT count(*) FROM pg_namespace WHERE nspname=:name"
                            ),
                            {"name": name},
                        ).scalar_one()
                        == 0
                    )
        finally:
            engine.dispose()


@pytest.fixture(params=["sqlite", "postgresql"])
def store(request, tmp_path):
    if request.param == "postgresql":
        engine, schema = request.getfixturevalue("pg_schema")
        engine = engine.execution_options(schema_translate_map={None: schema})
    else:
        engine = create_engine(
            "sqlite:///" + str(tmp_path / "adapter.db"), connect_args={"timeout": 0.1}
        )
    Counter.metadata.create_all(engine)
    try:
        yield engine
    finally:
        if request.param == "sqlite":
            engine.dispose()


@pytest.mark.parametrize("value", VALUES)
def test_roundtrip_orm_flush_refresh_returning_and_stale_identity(store, value):
    table = Counter.__table__
    with Session(store) as session:
        row = Counter(id=1, revision=value, resume=value)
        session.add(row)
        session.flush()
        session.expire(row)
        assert row.revision == row.resume == value
        session.commit()
        session.refresh(row)
        assert row.revision == row.resume == value
        predicate = (
            table.c.revision.is_(None) if value is None else table.c.revision == value
        )
        assert session.execute(select(table.c.id).where(predicate)).scalar_one() == 1
        session.rollback()
        # Hold a loaded identity while a separate connection commits a new value.
        assert row.revision == value
        session.commit()  # Release the read transaction, retaining identity below.
    with Session(store, expire_on_commit=False) as stale:
        loaded = stale.get(Counter, 1)
        stale.commit()
        new = exact_next(value)
        with store.begin() as independent:
            result = independent.execute(
                update(table)
                .where(table.c.id == 1, predicate)
                .values(revision=new, resume=new)
                .returning(table.c.revision, table.c.resume)
            ).one()
            assert result == (new, new)
        assert loaded.revision == loaded.resume == value
        stale.refresh(loaded)
        assert loaded.revision == loaded.resume == new
        loaded.revision = exact_next(new)
        loaded.resume = exact_next(new)
        stale.flush()
        stale.expire(loaded)
        assert loaded.revision == loaded.resume == exact_next(new)
        stale.rollback()
        assert loaded.revision == loaded.resume == new
    with store.connect() as c:
        assert c.execute(select(table.c.revision, table.c.resume)).one() == (new, new)
        assert (
            c.execute(
                update(table).where(predicate).values(revision=0, resume=0)
            ).rowcount
            == 0
        )
        if store.dialect.name == "sqlite":
            assert c.exec_driver_sql(
                "SELECT revision,resume,typeof(revision),typeof(resume) FROM adapter_counter"
            ).one() == (str(new), str(new), "text", "text")
        else:
            schema = store.get_execution_options()["schema_translate_map"][None]
            raw = c.exec_driver_sql(
                f'SELECT revision,resume,pg_typeof(revision)::text,pg_typeof(resume)::text FROM "{schema}".adapter_counter'
            ).one()
            assert raw == (Decimal(new), Decimal(new), "numeric", "numeric")


@pytest.mark.parametrize(
    "value", [True, False, 1.0, "1", Decimal(1), 10**128, -(10**128)]
)
def test_invalid_domain(value):
    with pytest.raises(ValueError):
        validate_integer(value)
    with pytest.raises(ValueError):
        exact_next(value)


@pytest.mark.parametrize(
    "value", ["-0", "+0", "01", "-01", " 1", "1 ", "1e3", "1.0", "", "９", "1" * 129]
)
def test_noncanonical_text(value):
    with pytest.raises(ValueError):
        decode_text(value)


@pytest.mark.parametrize(
    "value",
    [
        "NaN",
        "sNaN",
        "Infinity",
        "-Infinity",
        "1.5",
        "1E1000000",
        "1E-1000000",
        "1" * 129,
    ],
)
def test_invalid_decimal(value):
    with pytest.raises(ValueError):
        decode_decimal(Decimal(value))


@pytest.mark.parametrize("value", VALUES)
def test_decoders_signed_and_next(value):
    assert decode_decimal(None if value is None else Decimal(value)) == value
    assert decode_text(None if value is None else str(value)) == value
    assert exact_next(value) == (1 if value is None else value + 1)


def test_sqlite_order_and_arithmetic_are_not_numeric(tmp_path):
    engine = create_engine("sqlite:///" + str(tmp_path / "negative.db"))
    try:
        Counter.metadata.create_all(engine)
        with engine.begin() as c:
            c.execute(
                Counter.__table__.insert(),
                [
                    {"id": 1, "revision": 2, "resume": 2},
                    {"id": 2, "revision": 10, "resume": 10},
                ],
            )
            assert c.execute(
                select(Counter.revision).order_by(Counter.revision)
            ).scalars().all() == [10, 2]
            c.execute(update(Counter).values(revision=MAX, resume=MAX))
            c.exec_driver_sql(
                "UPDATE adapter_counter SET revision=revision+1,resume=resume+1"
            )
            raw = c.exec_driver_sql(
                "SELECT revision,resume FROM adapter_counter LIMIT 1"
            ).one()
            assert raw[0] == raw[1] != str(MAX + 1)
            with pytest.raises(ValueError):
                c.execute(select(Counter.revision)).all()
    finally:
        engine.dispose()


def test_sqlite_lock_before_read_busy_commit_and_rollback(tmp_path):
    engine = create_engine(
        "sqlite:///" + str(tmp_path / "locks.db"), connect_args={"timeout": 0.1}
    )
    table = Counter.__table__
    try:
        Counter.metadata.create_all(engine)
        with engine.begin() as c:
            c.execute(table.insert().values(id=1, revision=MAX, resume=MAX))
        with engine.connect() as a, engine.connect() as b:
            a.exec_driver_sql("BEGIN IMMEDIATE")
            old = a.execute(select(table.c.revision)).scalar_one()
            a.execute(
                update(table)
                .where(table.c.revision == old)
                .values(revision=exact_next(old), resume=exact_next(old))
            )
            assert b.execute(select(table.c.revision)).scalar_one() == MAX
            b.rollback()
            with pytest.raises(OperationalError) as error:
                b.exec_driver_sql("BEGIN IMMEDIATE")
            assert error.value.orig.sqlite_errorcode == 5
            b.rollback()
            a.commit()
            b.exec_driver_sql("BEGIN IMMEDIATE")
            old = b.execute(select(table.c.revision)).scalar_one()
            assert old == MAX + 1
            b.execute(
                update(table)
                .where(table.c.revision == old)
                .values(revision=exact_next(old), resume=exact_next(old))
            )
            b.commit()
            a.exec_driver_sql("BEGIN IMMEDIATE")
            a.execute(update(table).values(revision=0, resume=0))
            a.rollback()
            assert b.execute(select(table.c.revision, table.c.resume)).one() == (
                MAX + 2,
                MAX + 2,
            )
    finally:
        engine.dispose()


@pytest.mark.parametrize("field", ["revision", "resume"])
@pytest.mark.parametrize("bad", [True, 1.5, "1", 10**128])
def test_bind_rejection_before_driver(store, field, bad):
    with store.connect() as c:
        with pytest.raises(StatementError) as error:
            c.execute(Counter.__table__.insert().values(id=1, **{field: bad}))
        assert isinstance(error.value.orig, ValueError)
        c.rollback()
        assert c.execute(select(Counter.id)).all() == []


@pytest.mark.parametrize("bad", ["1.5", "NaN", "Infinity", "-Infinity"])
@pytest.mark.parametrize("field", ["revision", "resume"])
def test_pg_raw_sql_bypasses_adapter_but_read_rejects(pg_schema, bad, field):
    engine, schema = pg_schema
    mapped = engine.execution_options(schema_translate_map={None: schema})
    Counter.metadata.create_all(mapped)
    with mapped.begin() as c:
        c.execute(
            text(
                f'INSERT INTO "{schema}".adapter_counter(id,{field}) VALUES (1,CAST(:bad AS NUMERIC))'
            ),
            {"bad": bad},
        )
    with mapped.connect() as c:
        raw = c.execute(
            text(f'SELECT {field} FROM "{schema}".adapter_counter')
        ).scalar_one()
        assert isinstance(raw, Decimal)
        with pytest.raises(ValueError):
            c.execute(select(Counter.__table__.c[field])).all()


def test_pg_locked_python_increment_stale_cas_and_rollback(pg_schema):
    engine, schema = pg_schema
    engine = engine.execution_options(schema_translate_map={None: schema})
    table = Counter.__table__
    Counter.metadata.create_all(engine)
    initial = 10**40 + 1
    with engine.begin() as c:
        c.execute(table.insert().values(id=1, revision=initial, resume=initial))
    ready = Event()
    worker_pid = []
    with engine.connect() as first, ThreadPoolExecutor(max_workers=1) as pool:
        first_pid = first.execute(text("SELECT pg_backend_pid()")).scalar_one()
        old = first.execute(select(table.c.revision).with_for_update()).scalar_one()
        first.execute(
            update(table)
            .where(table.c.revision == old, table.c.resume == old)
            .values(revision=exact_next(old), resume=exact_next(old))
        )

        def second_writer():
            with engine.begin() as c:
                c.execute(text("SET LOCAL statement_timeout='8s'"))
                c.execute(text("SET LOCAL lock_timeout='6s'"))
                worker_pid.append(
                    c.execute(text("SELECT pg_backend_pid()")).scalar_one()
                )
                ready.set()
                fresh = c.execute(
                    select(table.c.revision).with_for_update()
                ).scalar_one()
                assert fresh == initial + 1
                assert (
                    c.execute(
                        update(table)
                        .where(table.c.revision == initial, table.c.resume == initial)
                        .values(revision=0, resume=0)
                    ).rowcount
                    == 0
                )
                result = c.execute(
                    update(table)
                    .where(table.c.revision == fresh, table.c.resume == fresh)
                    .values(revision=exact_next(fresh), resume=exact_next(fresh))
                    .returning(table.c.revision, table.c.resume)
                ).one()
            return result

        future = pool.submit(second_writer)
        try:
            assert ready.wait(3)
            assert worker_pid[0] != first_pid
            with engine.connect() as observer:
                assert observer.execute(
                    select(table.c.revision, table.c.resume)
                ).one() == (initial, initial)
                deadline = monotonic() + 3
                while monotonic() < deadline:
                    blocked = observer.execute(
                        text("SELECT pg_blocking_pids(:pid)"), {"pid": worker_pid[0]}
                    ).scalar_one()
                    if first_pid in blocked:
                        break
                    sleep(0.01)
                assert first_pid in blocked
            first.commit()
            assert future.result(timeout=9) == (initial + 2, initial + 2)
        finally:
            first.rollback()  # Release lock before the executor joins on any failure.
    with engine.connect() as c:
        assert c.execute(select(table.c.revision, table.c.resume)).one() == (
            initial + 2,
            initial + 2,
        )
        c.execute(update(table).values(revision=0, resume=0))
        c.rollback()
    with engine.connect() as c:
        assert c.execute(select(table.c.revision, table.c.resume)).one() == (
            initial + 2,
            initial + 2,
        )


def test_budget_edges_and_overflow_are_explicit():
    largest = 10**128 - 1
    for value in [largest, -largest]:
        assert validate_integer(value) == value
        assert decode_text(str(value)) == value
        assert decode_decimal(Decimal(value)) == value
    with pytest.raises(ValueError):
        exact_next(largest)
    assert decode_decimal(Decimal("-0")) == 0  # NUMERIC has no canonical text contract.
    assert decode_decimal(Decimal("1.000")) == 1
    assert decode_decimal(Decimal("0E128")) == 0
    with pytest.raises(ValueError):
        decode_decimal(Decimal("0E129"))


def test_unconnected_metadata_is_local():
    assert set(Counter.metadata.tables) == {"adapter_counter"}
    assert Counter.metadata is not SQLModel.metadata
