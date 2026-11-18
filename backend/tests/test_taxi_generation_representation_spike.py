"""Test-only representation evidence, not a selected application protocol.

Run with --noconftest: no application import, model metadata or application DDL.
NUMERIC is not universally exact across dialects; TEXT requires explicit integer
arithmetic and canonical validation. Neither experiment implements recovery.
"""

import os
from pathlib import Path

# Reject arbitrary PG collection before importing database libraries or opening SQL.
_PG_URL = os.environ.get("DATABASE_URL", "")
_PG = _PG_URL.startswith("postgresql")
if _PG:
    _root = Path(os.environ.get("YULDASH_TEST_PG_DATA", ""))
    if (
        os.environ.get("YULDASH_TEST_PG_MODE") != "generation-representation"
        or not _root.is_absolute()
        or _root.name != "data"
        or _root.parent.parent != Path("/tmp")
        or not _root.parent.name.startswith("yuldash-revision-pg.")
    ):
        raise RuntimeError(
            "Dedicated disposable generation-representation runner required"
        )

from concurrent.futures import ThreadPoolExecutor  # noqa: E402
from decimal import Decimal  # noqa: E402
import re  # noqa: E402
import sqlite3  # noqa: E402
from threading import Event  # noqa: E402
from time import monotonic, sleep  # noqa: E402
from uuid import uuid4  # noqa: E402

import pytest  # noqa: E402
from sqlalchemy import (
    Column,
    Integer,
    MetaData,
    Numeric,
    Table,
    create_engine,
    select,
    text,
)  # noqa: E402
from sqlalchemy.engine import make_url  # noqa: E402
from sqlalchemy.exc import IntegrityError  # noqa: E402

MAX = 2**63 - 1
VALUES = [MAX - 1, MAX, MAX + 1, 2**53 + 1, 10**40 + 1]


def exact_nonnegative(value):
    """Experimental bounded Decimal decoder; never int() a fraction/special first."""
    if value is None:
        return None
    if not isinstance(value, Decimal) or not value.is_finite():
        raise ValueError("finite Decimal required")
    parts = value.as_tuple()
    if len(parts.digits) > 128 or abs(parts.exponent) > 128 or value.adjusted() >= 128:
        raise ValueError("experimental 128-digit budget exceeded")
    if value < 0 or value != value.to_integral_value():
        raise ValueError("nonnegative integral value required")
    return int(value)


def canonical_integer(value):
    if (
        not isinstance(value, str)
        or len(value) > 128
        or re.fullmatch(r"0|[1-9][0-9]*", value) is None
    ):
        raise ValueError("bounded canonical decimal string required")
    return int(value)


@pytest.mark.parametrize("value", VALUES)
def test_decimal_exact_without_float(value):
    assert exact_nonnegative(Decimal(value)) == value


@pytest.mark.parametrize(
    "value",
    [
        Decimal("1.5"),
        Decimal("-1"),
        Decimal("NaN"),
        Decimal("Infinity"),
        Decimal("-Infinity"),
        Decimal("1E1000000"),
        Decimal("1E-1000000"),
        1.0,
        True,
    ],
)
def test_decimal_rejects_before_integer_conversion(value):
    with pytest.raises(ValueError):
        exact_nonnegative(value)


def test_decimal_null_and_integral_scale():
    assert exact_nonnegative(None) is None
    assert exact_nonnegative(Decimal("1.00")) == 1


@pytest.mark.parametrize(
    "value",
    ["-1", "+1", "01", "00", " 1", "1 ", "1e3", "1.0", "NaN", "", "١", "1" * 129],
)
def test_text_canonical_validation(value):
    with pytest.raises(ValueError):
        canonical_integer(value)


@pytest.mark.parametrize("value", VALUES)
def test_sqlite_text_python_increment_and_stale_cas(tmp_path, value):
    with sqlite3.connect(tmp_path / "text.db") as c:
        c.execute("CREATE TABLE counter (revision TEXT, resume TEXT)")
        c.execute("INSERT INTO counter VALUES (?,?)", (str(value), str(value)))
        following = str(canonical_integer(str(value)) + 1)
        assert (
            c.execute(
                "UPDATE counter SET revision=?,resume=? WHERE revision=? AND resume=?",
                (following, following, str(value), str(value)),
            ).rowcount
            == 1
        )
        assert c.execute(
            "SELECT revision,resume,typeof(revision) FROM counter"
        ).fetchone() == (following, following, "text")
        assert (
            c.execute(
                "UPDATE counter SET revision='0' WHERE revision=?", (str(value),)
            ).rowcount
            == 0
        )
        assert canonical_integer("0") == 0


def test_sqlite_numeric_raw_and_orm_loss_are_diagnostics(tmp_path):
    path = tmp_path / "numeric.db"
    value = MAX + 1
    with sqlite3.connect(path) as c:
        c.execute("CREATE TABLE raw_counter (revision NUMERIC, resume NUMERIC)")
        c.execute("INSERT INTO raw_counter VALUES (?,?)", (str(value), str(value)))
        raw = c.execute(
            "SELECT revision,resume,typeof(revision) FROM raw_counter"
        ).fetchone()
        assert raw[2] == "real" and isinstance(raw[0], float)
        c.execute("UPDATE raw_counter SET revision=revision+1,resume=resume+1")
        result = c.execute("SELECT revision,resume FROM raw_counter").fetchone()
        assert result == raw[:2] and int(result[0]) != value + 1
    engine = create_engine(f"sqlite:///{path}")
    metadata = MetaData()
    counter = Table(
        "orm_counter",
        metadata,
        Column("id", Integer, primary_key=True),
        Column("revision", Numeric(100, 0, asdecimal=True)),
        Column("resume", Numeric(100, 0, asdecimal=True)),
    )
    try:
        metadata.create_all(engine)
        with engine.begin() as c:
            c.execute(
                counter.insert().values(
                    id=1, revision=Decimal(2**53 + 1), resume=Decimal(2**53 + 1)
                )
            )
            mapped = c.execute(select(counter.c.revision, counter.c.resume)).one()
            assert all(isinstance(v, Decimal) for v in mapped)
            assert mapped[0] == mapped[1] and mapped[0] != Decimal(2**53 + 1)
            stored = c.exec_driver_sql(
                "SELECT revision,typeof(revision) FROM orm_counter"
            ).one()
            assert stored[1] in ("integer", "real") and int(stored[0]) != 2**53 + 1
    finally:
        engine.dispose()


def test_sqlite_sql_plus_one_is_not_text_arithmetic(tmp_path):
    with sqlite3.connect(tmp_path / "bad_text.db") as c:
        c.execute("CREATE TABLE counter (revision TEXT,resume TEXT)")
        c.execute("INSERT INTO counter VALUES (?,?)", (str(MAX), str(MAX)))
        c.execute("UPDATE counter SET revision=revision+1,resume=resume+1")
        revision, resume = c.execute("SELECT revision,resume FROM counter").fetchone()
        assert revision == resume and revision != str(MAX + 1)
        with pytest.raises(ValueError):
            canonical_integer(revision)


def test_sqlite_two_connections_busy_then_exact_commit_and_rollback(tmp_path):
    path = tmp_path / "serialized.db"
    with sqlite3.connect(path) as setup:
        setup.execute("CREATE TABLE counter (revision TEXT,resume TEXT)")
        setup.execute("INSERT INTO counter VALUES (?,?)", (str(MAX), str(MAX)))
    c1 = sqlite3.connect(path, timeout=0.1, isolation_level=None)
    c2 = sqlite3.connect(path, timeout=0.1, isolation_level=None)
    try:
        c1.execute("BEGIN IMMEDIATE")
        c1.execute(
            "UPDATE counter SET revision=?,resume=?", (str(MAX + 1), str(MAX + 1))
        )
        assert c2.execute("SELECT revision,resume FROM counter").fetchone() == (
            str(MAX),
            str(MAX),
        )
        with pytest.raises(sqlite3.OperationalError) as error:
            c2.execute("BEGIN IMMEDIATE")
        assert error.value.sqlite_errorcode == sqlite3.SQLITE_BUSY
        c1.commit()
        c2.execute("BEGIN IMMEDIATE")
        old, pending = c2.execute("SELECT revision,resume FROM counter").fetchone()
        new = str(canonical_integer(old) + 1)
        assert old == pending == str(MAX + 1)
        assert (
            c2.execute(
                "UPDATE counter SET revision=?,resume=? WHERE revision=? AND resume=?",
                (new, new, old, pending),
            ).rowcount
            == 1
        )
        c2.commit()
        assert c1.execute("SELECT revision,resume FROM counter").fetchone() == (
            str(MAX + 2),
            str(MAX + 2),
        )
        c1.execute("BEGIN IMMEDIATE")
        c1.execute("UPDATE counter SET revision='0',resume='0'")
        c1.rollback()
        assert c2.execute("SELECT revision,resume FROM counter").fetchone() == (
            str(MAX + 2),
            str(MAX + 2),
        )
    finally:
        c1.rollback()
        c2.rollback()
        c1.close()
        c2.close()


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
            c.execute(
                text(
                    f'CREATE TABLE "{name}".counter (id INTEGER PRIMARY KEY,revision NUMERIC,resume NUMERIC)'
                )
            )
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


@pytest.mark.parametrize("value", VALUES)
def test_pg_exact_numeric_increment_both_columns_and_stale_cas(pg_schema, value):
    engine, schema = pg_schema
    table = f'"{schema}".counter'
    with engine.begin() as c:
        c.execute(
            text(f"INSERT INTO {table} VALUES (1,:n,:n),(2,NULL,NULL)"),
            {"n": Decimal(value)},
        )
        row = c.execute(
            text(
                f"UPDATE {table} SET revision=revision+1,resume=resume+1 WHERE id=1 RETURNING revision,resume"
            )
        ).one()
        assert all(isinstance(v, Decimal) for v in row)
        assert tuple(map(exact_nonnegative, row)) == (value + 1, value + 1)
        assert (
            c.execute(
                text(
                    f"UPDATE {table} SET revision=0 WHERE id=1 AND revision=:n AND resume=:n"
                ),
                {"n": Decimal(value)},
            ).rowcount
            == 0
        )
        assert c.execute(
            text(f"SELECT revision,resume FROM {table} WHERE id=2")
        ).one() == (None, None)
        assert (
            c.execute(
                text(
                    f"UPDATE {table} SET revision=1,resume=1 WHERE id=2 AND revision IS NULL AND resume IS NULL"
                )
            ).rowcount
            == 1
        )
        assert (
            c.execute(
                text(f"SELECT revision=resume FROM {table} WHERE id=1")
            ).scalar_one()
            is True
        )
        if value == MAX - 1:
            crossed = c.execute(
                text(
                    f"UPDATE {table} SET revision=revision+1,resume=resume+1 WHERE id=1 RETURNING revision,resume"
                )
            ).one()
            assert tuple(map(exact_nonnegative, crossed)) == (MAX + 1, MAX + 1)


@pytest.mark.parametrize("field", ["revision", "resume"])
@pytest.mark.parametrize("value", ["1.5", "-1", "NaN", "Infinity", "-Infinity"])
def test_pg_unconstrained_specials_vs_experimental_check(pg_schema, value, field):
    engine, schema = pg_schema
    definitions = []
    for name in ("revision", "resume"):
        condition = f"{name}::text NOT IN ('NaN','Infinity','-Infinity') AND {name}>=0 AND {name}=trunc({name})"
        definitions.append(
            f"{name} NUMERIC CONSTRAINT generation_{name}_integer CHECK ({condition})"
        )
    with engine.begin() as c:
        c.execute(text(f'CREATE TABLE "{schema}".guarded ({",".join(definitions)})'))
        # Explicit nullable policy plus positive controls prevent a reject-all CHECK.
        for valid in (None, Decimal(0), Decimal(10**40 + 1)):
            c.execute(
                text(f'INSERT INTO "{schema}".guarded VALUES (:v,:v)'), {"v": valid}
            )
        assert c.execute(
            text(
                f'SELECT revision,resume FROM "{schema}".guarded ORDER BY revision NULLS FIRST'
            )
        ).all() == [
            (None, None),
            (Decimal(0), Decimal(0)),
            (Decimal(10**40 + 1), Decimal(10**40 + 1)),
        ]
        # Driver may adapt NaN specially; explicit NUMERIC cast tests the database value.
        observed = c.execute(
            text("SELECT CAST(:v AS NUMERIC)"), {"v": value}
        ).scalar_one()
        assert isinstance(observed, Decimal)
        with pytest.raises(ValueError):
            exact_nonnegative(observed)
        c.execute(
            text(
                f'INSERT INTO "{schema}".counter VALUES (1,CAST(:v AS NUMERIC),CAST(:v AS NUMERIC))'
            ),
            {"v": value},
        )
        with pytest.raises(IntegrityError) as error, c.begin_nested():
            c.execute(
                text(
                    f'INSERT INTO "{schema}".guarded ({field}) VALUES (CAST(:v AS NUMERIC))'
                ),
                {"v": value},
            )
        assert error.value.orig.pgcode == "23514"
        assert error.value.orig.diag.constraint_name == f"generation_{field}_integer"


def test_pg_rollback_and_nonvisibility(pg_schema):
    engine, schema = pg_schema
    table = f'"{schema}".counter'
    with engine.begin() as c:
        c.execute(text(f"INSERT INTO {table} VALUES (1,:n,:n)"), {"n": Decimal(MAX)})
    with engine.connect() as writer, engine.connect() as reader:
        assert (
            writer.execute(text("SELECT pg_backend_pid()")).scalar_one()
            != reader.execute(text("SELECT pg_backend_pid()")).scalar_one()
        )
        writer.execute(text(f"UPDATE {table} SET revision=revision+1,resume=resume+1"))
        assert reader.execute(text(f"SELECT revision,resume FROM {table}")).one() == (
            Decimal(MAX),
            Decimal(MAX),
        )
        writer.rollback()
        assert reader.execute(text(f"SELECT revision,resume FROM {table}")).one() == (
            Decimal(MAX),
            Decimal(MAX),
        )


def test_pg_two_actual_commits_block_then_exact_n_plus_two(pg_schema):
    engine, schema = pg_schema
    table = f'"{schema}".counter'
    value = 10**40 + 1
    with engine.begin() as c:
        c.execute(text(f"INSERT INTO {table} VALUES (1,:n,:n)"), {"n": Decimal(value)})
    ready = Event()
    state = {}

    def second():
        with engine.connect() as c:
            c.execute(text("SET statement_timeout='8000ms'"))
            c.execute(text("SET lock_timeout='6000ms'"))
            c.commit()
            state["pid"] = c.execute(text("SELECT pg_backend_pid()")).scalar_one()
            ready.set()
            row = c.execute(
                text(
                    f"UPDATE {table} SET revision=revision+1,resume=resume+1 RETURNING revision,resume"
                )
            ).one()
            c.commit()
            return row

    with ThreadPoolExecutor(max_workers=1) as pool, engine.connect() as first:
        first_pid = first.execute(text("SELECT pg_backend_pid()")).scalar_one()
        first.execute(text(f"UPDATE {table} SET revision=revision+1,resume=resume+1"))
        future = pool.submit(second)
        try:
            assert ready.wait(3)
            assert state["pid"] != first_pid
            deadline = monotonic() + 3
            with engine.connect() as observer:
                while monotonic() < deadline:
                    blockers = observer.execute(
                        text("SELECT pg_blocking_pids(:pid)"), {"pid": state["pid"]}
                    ).scalar_one()
                    if first_pid in blockers:
                        break
                    sleep(0.01)
                else:
                    pytest.fail(
                        "second actual PG backend did not block on first writer"
                    )
            first.commit()
            assert tuple(map(exact_nonnegative, future.result(timeout=9))) == (
                value + 2,
                value + 2,
            )
        finally:
            first.rollback()
            # The executor context joins the worker after releasing our lock.
            # Server timeouts bound its SQL wait. Do not mask a primary assertion
            # with a second future.result() exception during cleanup.
    with engine.connect() as c:
        assert tuple(
            map(
                exact_nonnegative,
                c.execute(text(f"SELECT revision,resume FROM {table}")).one(),
            )
        ) == (value + 2, value + 2)
