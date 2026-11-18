"""Synthetic SQLite stored-domain experiment, NOT an InstantOrder rebuild.

CHECK validates the post-affinity stored value. Deliberate precision-loss tests
show that a different valid number can pass; they do not protect old writers.
"""

from pathlib import Path
import sqlite3
import tempfile

import pytest
from sqlalchemy import (
    Integer,
    column,
    create_engine,
    event,
    select,
    table,
    text,
    update,
)
from sqlalchemy.exc import IntegrityError, StatementError
from sqlalchemy.pool import NullPool

from app.taxi_generation_adapter_lab import ExactGeneration

FIELDS = ("search_revision", "matching_resume_revision")
LIMIT = 10**128
TYPED = table(
    "stored_domain",
    column("id", Integer),
    *(column(n, ExactGeneration()) for n in FIELDS),
)


def _predicate(name):
    assert name in FIELDS
    return f"""{name} IS NULL OR (typeof({name})='text'
        AND length({name}) BETWEEN 1 AND 128
        AND length(CAST({name} AS BLOB))=length({name})
        AND {name} NOT GLOB '*[^0-9]*'
        AND ({name}='0' OR substr({name},1,1) BETWEEN '1' AND '9'))"""


@pytest.fixture
def store():
    root = Path(tempfile.mkdtemp(prefix="taxi-storage-check.", dir="/tmp"))
    assert root.parent == Path("/tmp") and not root.is_symlink()
    path = root / "synthetic.sqlite"
    engine = create_engine("sqlite:///" + str(path), poolclass=NullPool)
    try:
        with engine.begin() as c:
            assert Path(c.exec_driver_sql("PRAGMA database_list").all()[0][2]) == path
            c.exec_driver_sql(
                "CREATE TABLE stored_domain(id INTEGER PRIMARY KEY, "
                + ", ".join(
                    f"{n} TEXT CONSTRAINT ck_{n} CHECK({_predicate(n)})" for n in FIELDS
                )
                + ")"
            )
            c.exec_driver_sql("INSERT INTO stored_domain(id) VALUES(1)")
        yield engine
    finally:
        engine.dispose()
        for suffix in ("", "-wal", "-shm", "-journal"):
            Path(str(path) + suffix).unlink(missing_ok=True)
        root.rmdir()


def _snapshot(engine):
    with engine.connect() as c:
        return c.exec_driver_sql(
            "SELECT * FROM stored_domain ORDER BY id"
        ).all(), c.exec_driver_sql(
            "SELECT type,name,tbl_name,sql FROM sqlite_master ORDER BY type,name"
        ).all()


def _raw(c, name):
    assert name in FIELDS
    return c.exec_driver_sql(
        f"SELECT {name},typeof({name}),quote({name}),hex({name}),length({name}),length(CAST({name} AS BLOB)) FROM stored_domain WHERE id=1"
    ).one()


def _check_error(error, name):
    assert error.orig.sqlite_errorcode == sqlite3.SQLITE_CONSTRAINT_CHECK
    assert str(error.orig) == f"CHECK constraint failed: ck_{name}"


@pytest.mark.parametrize("name", FIELDS)
@pytest.mark.parametrize(
    "value", [None, 0, 2**53 + 1, 2**63 - 2, 2**63 - 1, 2**63, 10**40 + 1, LIMIT - 1]
)
def test_typed_exact_stored_value_returning_cas_and_rollback(store, name, value):
    col = TYPED.c[name]
    with store.connect() as c:
        got = c.execute(
            update(TYPED).where(TYPED.c.id == 1).values({name: value}).returning(col)
        ).scalar_one()
        assert got == value and (got is None or type(got) is int)
        c.commit()
        with store.connect() as observer:
            assert (
                observer.connection.driver_connection
                is not c.connection.driver_connection
            )
            raw = _raw(observer, name)
            assert raw[0] == (None if value is None else str(value))
            assert raw[1] == ("null" if value is None else "text")
            if value is not None:
                assert raw[3] == str(value).encode().hex().upper()
                assert raw[4] == raw[5] == len(str(value))
        predicate = col.is_(None) if value is None else col == value
        new = 1 if value == 0 else 0
        assert c.execute(
            update(TYPED)
            .where(TYPED.c.id == 1, predicate)
            .values({name: new})
            .returning(col)
        ).all() == [(new,)]
        assert (
            c.execute(
                update(TYPED)
                .where(TYPED.c.id == 1, predicate)
                .values({name: 7})
                .returning(col)
            ).all()
            == []
        )
        c.commit()
        before = _snapshot(store)
        c.execute(update(TYPED).where(TYPED.c.id == 1).values({name: 2}))
        with store.connect() as observer:
            assert _raw(observer, name)[0] == str(new)
        c.rollback()
        assert _snapshot(store) == before


BAD = [
    "",
    "00",
    "+0",
    "-0",
    "-1",
    str(LIMIT),
    "١٢",
    "１２",
    "12\x00",
    "1\x002",
    "9" * 128 + "\x00",
    " 1",
    "1 ",
    "1\n",
    "\t1",
    "1e2",
    "1.0",
    b"12",
    b"\x00",
]


@pytest.mark.parametrize("name", FIELDS)
@pytest.mark.parametrize("value", BAD)
def test_raw_invalid_has_named_check_failure_not_bind_error(store, name, value):
    before = _snapshot(store)
    with store.connect() as c:
        # Inspect original input, including bytes beyond NUL, before affinity.
        facts = c.execute(
            text(
                "SELECT typeof(:v),quote(:v),hex(:v),length(:v),length(CAST(:v AS BLOB))"
            ),
            {"v": value},
        ).one()
        assert (
            facts[2]
            == (value if isinstance(value, bytes) else value.encode()).hex().upper()
        )
        with pytest.raises(IntegrityError) as error:
            with c.begin_nested():
                c.execute(
                    text(f"UPDATE stored_domain SET {name}=:v WHERE id=1"), {"v": value}
                )
        _check_error(error.value, name)
        assert c.exec_driver_sql("SELECT 1").scalar_one() == 1
        c.rollback()
    assert _snapshot(store) == before


@pytest.mark.parametrize("name", FIELDS)
def test_signed_adapter_bind_is_allowed_but_storage_rejects(store, name):
    before = _snapshot(store)
    with store.connect() as c:
        with pytest.raises(IntegrityError) as error:
            with c.begin_nested():
                c.execute(update(TYPED).where(TYPED.c.id == 1).values({name: -1}))
        _check_error(error.value, name)
        c.rollback()
    assert _snapshot(store) == before


@pytest.mark.parametrize("name", FIELDS)
@pytest.mark.parametrize("value", [True, 1.5, LIMIT])
def test_invalid_typed_input_is_rejected_before_sql(store, name, value):
    before = _snapshot(store)
    calls = []

    def record(conn, cursor, statement, params, context, many):
        calls.append(statement)

    with store.connect() as c:
        event.listen(c, "before_cursor_execute", record)
        try:
            with pytest.raises(StatementError) as error:
                c.execute(update(TYPED).where(TYPED.c.id == 1).values({name: value}))
            assert type(error.value.orig) is ValueError
            assert str(error.value.orig) == "bounded exact integer required"
            assert calls == []
        finally:
            event.remove(c, "before_cursor_execute", record)
            c.rollback()
    assert _snapshot(store) == before


@pytest.mark.parametrize("invalid_field", FIELDS)
def test_two_column_update_is_atomic_on_one_bad_value(store, invalid_field):
    other = next(n for n in FIELDS if n != invalid_field)
    before = _snapshot(store)
    with store.connect() as c:
        with pytest.raises(IntegrityError) as error:
            with c.begin_nested():
                c.execute(
                    text(
                        f"UPDATE stored_domain SET {other}='12',{invalid_field}=:bad WHERE id=1"
                    ),
                    {"bad": str(LIMIT)},
                )
        _check_error(error.value, invalid_field)
        c.rollback()
    assert _snapshot(store) == before


def test_affinity_does_not_preserve_original_input_type_or_spelling(store):
    with store.begin() as c:
        # Integer input is converted to valid TEXT by column affinity.
        c.exec_driver_sql("UPDATE stored_domain SET search_revision=12 WHERE id=1")
        assert _raw(c, "search_revision")[:2] == ("12", "text")
        assert c.exec_driver_sql("SELECT typeof('1e2'),typeof(1e2)").one() == (
            "text",
            "real",
        )
        for expression in ("'1e2'", "1e2"):
            with pytest.raises(IntegrityError) as error:
                with c.begin_nested():
                    c.exec_driver_sql(
                        f"UPDATE stored_domain SET search_revision={expression} WHERE id=1"
                    )
            _check_error(error.value, "search_revision")
        c.exec_driver_sql(
            "UPDATE stored_domain SET search_revision=CAST(1e2 AS INTEGER) WHERE id=1"
        )
        assert _raw(c, "search_revision")[:2] == ("100", "text")


def test_text_order_is_not_numeric_order(store):
    with store.begin() as c:
        c.exec_driver_sql("UPDATE stored_domain SET search_revision='10' WHERE id=1")
        c.exec_driver_sql("INSERT INTO stored_domain(id,search_revision) VALUES(2,'2')")
    with store.connect() as c:
        values = (
            c.exec_driver_sql(
                "SELECT search_revision FROM stored_domain ORDER BY search_revision"
            )
            .scalars()
            .all()
        )
        assert values == ["10", "2"]
        assert sorted(map(int, values)) == [2, 10]


@pytest.mark.parametrize(
    "original,expression,wrong",
    [
        (
            9007199254740993,
            "CAST(CAST('9007199254740993' AS REAL) AS INTEGER)",
            9007199254740992,
        ),
        (
            9223372036854775808,
            "CAST('9223372036854775808' AS INTEGER)",
            9223372036854775807,
        ),
    ],
)
def test_known_gap_cast_saves_different_but_valid_canonical_integer(
    store, original, expression, wrong
):
    with store.begin() as c:
        c.exec_driver_sql(
            f"UPDATE stored_domain SET search_revision={expression} WHERE id=1"
        )
    with store.connect() as observer:
        raw = _raw(observer, "search_revision")
        assert raw[0] == str(wrong) and raw[1] == "text"
        assert (
            observer.execute(
                select(TYPED.c.search_revision).where(TYPED.c.id == 1)
            ).scalar_one()
            == wrong
        )
        assert (
            wrong != original
        )  # CHECK cannot recover precision already lost upstream.
