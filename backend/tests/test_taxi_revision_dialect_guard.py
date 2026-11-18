"""Distinct eligibility controls; no schema/model/budget cutover.

Numeric parity uses the actual BIGINT app schema and real update audit trigger.
Canonical TEXT coverage below is a separate synthetic SQLite table, not actual
ORM mapping evidence; that is provided by the isolated actual-ORM suite.
"""

from pathlib import Path
import re
import tempfile

import pytest
from fastapi import HTTPException
from sqlalchemy import (
    BigInteger,
    String,
    create_engine,
    event,
    func,
    select,
    text,
    update,
)
from sqlalchemy.dialects import postgresql, sqlite
from sqlalchemy.pool import NullPool
from sqlalchemy.sql import visitors
from sqlalchemy.sql.elements import Cast
from sqlmodel import Session

from app import instant_service as service
from app.models import InstantOrder
import test_taxi_locked_revision_bump as previous

sqlite_audit = previous.sqlite_audit
MAX = 2**63 - 1


def _old_numeric_predicate():
    # Literal prior implementation, not a patch of runtime or SQL execution.
    revision = func.coalesce(InstantOrder.search_revision, 0)
    return (revision >= 0) & (revision < MAX)


NUMERIC_CASES = [
    (None, "null"),
    (0, "integer"),
    (2, "integer"),
    (9, "integer"),
    (10, "integer"),
    (100, "integer"),
    (MAX - 1, "integer"),
    (MAX, "integer"),
    (-1, "integer"),
    (0.5, "real"),
    (-0.5, "real"),
    (float(MAX - 1), "real"),
    (1e100, "real"),
    ("12.5", "real"),
    ("0002", "integer"),
    (b"12", "blob"),
    ("bad", "text"),
    ("１２", "text"),
    ("1\x00", "integer"),
]


@pytest.mark.parametrize("input_value,physical_type", NUMERIC_CASES)
def test_actual_bigint_storage_numeric_parity_and_audit(
    sqlite_audit, input_value, physical_type
):
    c, oid, audit = sqlite_audit
    if input_value == "1\x00":
        assert c.execute(
            text("SELECT typeof(:v),hex(:v)"), {"v": input_value}
        ).one() == ("text", "3100")
    c.execute(
        text("UPDATE instantorder SET search_revision=:value WHERE id=:id"),
        {"value": input_value, "id": oid},
    )
    c.execute(text(f'DELETE FROM "{audit}"'))
    c.commit()
    stored = c.execute(
        text(
            "SELECT search_revision,typeof(search_revision),hex(search_revision) FROM instantorder WHERE id=:id"
        ),
        {"id": oid},
    ).one()
    assert stored[1] == physical_type  # Affinity can change the original spelling/type.
    if input_value == "1\x00":
        # Actual BIGINT affinity consumes this numeric prefix. The distinct
        # synthetic TEXT test retains NUL and must reject it without conversion.
        assert tuple(stored) == (1, "integer", "31")
    old = c.execute(
        select(_old_numeric_predicate()).where(InstantOrder.id == oid)
    ).scalar_one()
    new = c.execute(
        select(service._can_increment_search_revision("sqlite")).where(
            InstantOrder.id == oid
        )
    ).scalar_one()
    assert new == old
    expected = stored[0] is None or (
        type(stored[0]) in (int, float) and 0 <= stored[0] < MAX
    )
    assert bool(new) == expected
    c.rollback()
    for guard in (
        _old_numeric_predicate(),
        service._can_increment_search_revision("sqlite"),
    ):
        result = c.execute(
            update(InstantOrder)
            .where(InstantOrder.id == oid, guard)
            .values(search_revision=InstantOrder.search_revision)
            .returning(InstantOrder.id)
        )
        try:
            assert result.all() == ([(oid,)] if expected else [])
        finally:
            result.close()
        assert len(previous._events(c, audit)) == int(expected)
        c.rollback()
        assert previous._events(c, audit) == []
        c.rollback()
    # REAL remains lock-eligible, but actual fresh Python int-only validation
    # rejects it after the real no-op. This is not permission to increment REAL.
    with Session(bind=c) as session:
        order = session.get(InstantOrder, oid)
        valid = stored[0] is None or (type(stored[0]) is int and 0 <= stored[0] < MAX)
        if valid:
            assert service._bump_search_revision(session, order) == (
                1 if stored[0] is None else stored[0] + 1
            )
            assert len(previous._events(c, audit)) == 2
        else:
            with pytest.raises(HTTPException) as error:
                service._bump_search_revision(session, order)
            assert error.value.status_code == 409
            assert len(previous._events(c, audit)) == int(expected)
        session.rollback()
    assert previous._events(c, audit) == []
    assert (
        c.execute(
            text(
                "SELECT search_revision,typeof(search_revision),hex(search_revision) FROM instantorder WHERE id=:id"
            ),
            {"id": oid},
        ).one()
        == stored
    )
    c.rollback()


@pytest.fixture
def synthetic_text():
    root = Path(tempfile.mkdtemp(prefix="taxi-guard-text.", dir="/tmp"))
    path = root / "predicate.sqlite"
    engine = create_engine("sqlite:///" + str(path), poolclass=NullPool)

    @event.listens_for(engine, "connect")
    def reverse_order(connection, record):
        connection.create_collation("REVERSE", lambda a, b: (a < b) - (a > b))

    try:
        with engine.begin() as c:
            assert Path(c.exec_driver_sql("PRAGMA database_list").all()[0][2]) == path
            # No app mapper mutation. BINARY must override this physical collation.
            c.exec_driver_sql(
                "CREATE TABLE instantorder(id INTEGER PRIMARY KEY, search_revision TEXT COLLATE REVERSE)"
            )
            c.exec_driver_sql("INSERT INTO instantorder(id) VALUES(1)")
        yield engine
    finally:
        engine.dispose()
        for suffix in ("", "-wal", "-shm", "-journal"):
            Path(str(path) + suffix).unlink(missing_ok=True)
        root.rmdir()


TEXT_CASES = [
    (None, True),
    ("0", True),
    ("2", True),
    ("9", True),
    ("10", True),
    ("100", True),
    ("9" * 18, True),
    ("1" + "0" * 18, True),
    (str(MAX - 1), True),
    (str(MAX), False),
    (str(MAX + 1), False),
    ("1" + "0" * 19, False),
    ("9" * 128, False),
    ("9" * 129, False),
    ("", False),
    ("-0", False),
    ("00", False),
    ("01", False),
    ("+0", False),
    ("-1", False),
    (" 1", False),
    ("1 ", False),
    ("1\n", False),
    ("1e2", False),
    ("1.0", False),
    ("１２", False),
    ("١٢", False),
    ("1\x00", False),
    (str(MAX - 1) + "\x00", False),
    (b"12", False),
]


@pytest.mark.parametrize("value,eligible", TEXT_CASES)
def test_synthetic_text_canonical_bound_uses_actual_guard_and_binary(
    synthetic_text, value, eligible
):
    with synthetic_text.begin() as c:
        c.execute(
            text("UPDATE instantorder SET search_revision=:v WHERE id=1"), {"v": value}
        )
        raw = c.exec_driver_sql(
            "SELECT search_revision,typeof(search_revision),length(search_revision),length(CAST(search_revision AS BLOB)),hex(search_revision) FROM instantorder"
        ).one()
        assert raw[1] == (
            "null" if value is None else "blob" if isinstance(value, bytes) else "text"
        )
        if value is not None:
            assert (
                raw[4]
                == (value if isinstance(value, bytes) else value.encode()).hex().upper()
            )
        actual = c.execute(
            select(service._can_increment_search_revision("sqlite")).where(
                InstantOrder.id == 1
            )
        ).scalar_one()
        assert bool(actual) == eligible
        if value == str(MAX - 1):
            # Distinguishing control: default reverse collation gives wrong range.
            assert (
                c.execute(
                    text("SELECT search_revision < :maximum FROM instantorder"),
                    {"maximum": str(MAX)},
                ).scalar_one()
                == 0
            )
            assert actual is True


def test_compiled_bound_types_are_exact_and_text_has_only_blob_cast():
    expression = service._can_increment_search_revision("sqlite")
    compiled = expression.compile(dialect=sqlite.dialect())
    binds = list({id(value): value for value in compiled.binds.values()}.values())
    numeric = [b for b in binds if type(b.value) is int and b.value == MAX]
    textual = [b for b in binds if type(b.value) is str and b.value == str(MAX)]
    assert len(numeric) == len(textual) == 1
    assert isinstance(numeric[0].type, BigInteger)
    assert isinstance(textual[0].type, String)
    for bind, expected in ((numeric[0], MAX), (textual[0], str(MAX))):
        processor = bind.type.bind_processor(sqlite.dialect())
        processed = processor(bind.value) if processor else bind.value
        assert type(processed) is type(expected) and processed == expected
    assert any(
        type(b.value) is int and b.value == 0 and isinstance(b.type, BigInteger)
        for b in binds
    )
    casts = [node for node in visitors.iterate(expression) if isinstance(node, Cast)]
    assert (
        len(casts) == 1
        and str(casts[0].type.compile(dialect=sqlite.dialect())) == "BLOB"
    )
    sql = str(compiled)
    assert 'COLLATE "BINARY"' in sql
    assert not re.search(r"\bAS\s+(INTEGER|REAL|NUMERIC|FLOAT)\b", sql, re.I)
    assert " + " not in sql and " - " not in sql and " * " not in sql


@pytest.mark.parametrize("dialect_name", ["postgresql", "unknown"])
def test_non_sqlite_keeps_literal_previous_numeric_predicate(dialect_name):
    old = _old_numeric_predicate().compile(dialect=postgresql.dialect())
    new = service._can_increment_search_revision(dialect_name).compile(
        dialect=postgresql.dialect()
    )
    assert str(new) == str(old) and new.params == old.params
