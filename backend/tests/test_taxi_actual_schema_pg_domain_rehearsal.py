"""Phase 2 TEST-ONLY NUMERIC/adapter boundary and guarded downgrade.

Actual cloned app schema, test-local Core mapping only. No production ORM,
HTTP, SQLite, runtime policy or migration is changed. A signed adapter value
is not permission for a negative runtime generation. Pending rejection is not
second-column range validation. Sequence values are not rollback guarantees.
"""

from copy import deepcopy
from decimal import Decimal
import json
from uuid import uuid4

import pytest
from sqlalchemy import Integer, column, event, select, table, text, update
from sqlalchemy.exc import DataError, StatementError

from app.taxi_generation_adapter_lab import ExactGeneration, exact_next
import test_taxi_actual_schema_pg_type_rehearsal as phase1

# Explicitly import ONLY the two required fixtures, not phase1 test functions.
source = phase1.source
database = phase1.database

MAX = 2**63 - 1
LIMIT = 10**128
FIELDS = phase1.REVISION_COLUMNS
TYPED = table(
    "instantorder",
    column("id", Integer),
    column("search_revision", ExactGeneration()),
    column("matching_resume_revision", ExactGeneration()),
)


class DowngradeRejected(RuntimeError):
    """Test-local protocol gate with exact attribution, not HTTP behavior."""


def _validate_raw(value):
    """Pure downgrade validator, separate from the earlier pending gate."""
    if value is None:
        return None
    if not isinstance(value, Decimal) or not value.is_finite():
        raise DowngradeRejected("nonfinite")
    if value != value.to_integral_value():
        raise DowngradeRejected("fraction")
    if value < 0:
        raise DowngradeRejected("negative_protocol")
    if value > MAX:
        raise DowngradeRejected("bigint_range")
    return int(value)


def _bigint_catalog(catalog):
    expected = deepcopy(catalog)
    for col in expected["columns"]:
        if col["relname"] == "instantorder" and col["attname"] in FIELDS:
            assert col["type"] == "numeric"
            col["type"] = "bigint"
    return expected


def _downgrade(c, expected_catalog, fail_after=None):
    assert c.in_transaction() and c.connection.driver_connection.autocommit is False
    assert (
        c.exec_driver_sql("SHOW transaction_isolation").scalar_one() == "read committed"
    )
    c.exec_driver_sql("LOCK TABLE instantorder IN ACCESS EXCLUSIVE MODE")
    catalog = phase1._catalog(c)
    if catalog != expected_catalog or catalog["triggers"] or catalog["views"]:
        raise DowngradeRejected("catalog")
    cols = {
        col["attname"]: col
        for col in catalog["columns"]
        if col["relname"] == "instantorder"
    }
    for name in FIELDS:
        if (
            cols[name]["type"] != "numeric"
            or cols[name]["attnotnull"]
            or cols[name]["default"] is not None
        ):
            raise DowngradeRejected("shape")
    # Deliberately earlier than range checks. No silent clearing of sentinels.
    if c.exec_driver_sql("""SELECT EXISTS(SELECT 1 FROM instantorder WHERE
        matching_resume_revision IS NOT NULL OR matching_resume_next_at IS NOT NULL
        OR matching_resume_attempts IS NOT NULL)""").scalar_one():
        raise DowngradeRejected("pending")
    for row in c.exec_driver_sql(
        "SELECT search_revision,matching_resume_revision FROM instantorder"
    ):
        _validate_raw(row[0])
        _validate_raw(row[1])  # Only NULL is reachable here; not nonNULL range proof.
    before = phase1._data(c)
    for number, name in enumerate(FIELDS, 1):
        c.exec_driver_sql(
            f"ALTER TABLE instantorder ALTER COLUMN {phase1._quote(name)} TYPE BIGINT USING {phase1._quote(name)}::bigint"
        )
        if fail_after == number:
            raise phase1.InjectedRollback(f"after downgrade {number}")
    assert phase1._catalog(c) == _bigint_catalog(catalog)
    assert phase1._data(c) == before


@pytest.fixture
def numeric(database):
    engine, source_name = database
    original = phase1._read(engine)
    with engine.connect() as c:
        tx = phase1._begin(c)
        phase1._upgrade(c, original[0])
        tx.commit()
    assert phase1._read(engine) == (phase1._numeric_catalog(original[0]), original[1])
    yield engine, source_name, original


def _oid(c):
    return c.exec_driver_sql("SELECT min(id) FROM instantorder").scalar_one()


def _raw(c, name, oid):
    assert name in FIELDS
    return c.execute(
        text(f"SELECT {name}, {name}::text FROM instantorder WHERE id=:id"), {"id": oid}
    ).one()


def _facts(row):
    value, sql_text = row
    if value is None:
        return {"python_type": "NoneType", "sql_text": sql_text}
    assert isinstance(value, Decimal)
    return {
        "python_type": type(value).__name__,
        "repr": repr(value),
        "sql_text": sql_text,
        "tuple": tuple(value.as_tuple()),
        "finite": value.is_finite(),
        "integral": value == value.to_integral_value() if value.is_finite() else None,
    }


def _raw_write(c, name, value):
    assert name in FIELDS
    c.execute(
        text(f"UPDATE instantorder SET {name}=CAST(:value AS numeric) WHERE id=:id"),
        {"value": value, "id": _oid(c)},
    )


@pytest.mark.parametrize("name", FIELDS)
@pytest.mark.parametrize(
    "value", [None, 0, -1, 2**53 + 1, MAX - 1, MAX, MAX + 1, LIMIT - 1]
)
def test_actual_adapter_bind_returning_cas_and_rollback(numeric, name, value):
    engine, _, _ = numeric
    col = TYPED.c[name]
    with engine.connect() as c:
        oid = _oid(c)
        got = c.execute(
            update(TYPED).where(TYPED.c.id == oid).values({name: value}).returning(col)
        ).scalar_one()
        assert got == value and (got is None or type(got) is int)
        c.commit()
        with engine.connect() as observer:
            assert (
                observer.connection.driver_connection
                is not c.connection.driver_connection
            )
            raw, _ = _raw(observer, name, oid)
            assert (
                raw is None
                if value is None
                else isinstance(raw, Decimal) and raw == Decimal(value)
            )
        assert c.execute(select(col).where(TYPED.c.id == oid)).scalar_one() == value
        predicate = col.is_(None) if value is None else col == value
        replacement = 1 if value == 0 else 0
        winner = c.execute(
            update(TYPED)
            .where(TYPED.c.id == oid, predicate)
            .values({name: replacement})
            .returning(col)
        ).all()
        assert winner == [(replacement,)]
        assert (
            c.execute(
                update(TYPED)
                .where(TYPED.c.id == oid, predicate)
                .values({name: 7})
                .returning(col)
            ).all()
            == []
        )
        c.commit()
        assert (
            c.execute(select(col).where(TYPED.c.id == oid)).scalar_one() == replacement
        )
        c.rollback()
        before = phase1._read(engine)
        c.execute(
            update(TYPED).where(TYPED.c.id == oid).values({name: 2}).returning(col)
        ).all()
        c.rollback()
        assert phase1._read(engine) == before


@pytest.mark.parametrize("name", FIELDS)
@pytest.mark.parametrize("invalid", [True, 1.5, Decimal(1), LIMIT])
def test_invalid_adapter_bind_is_valueerror_before_sql(numeric, name, invalid):
    engine, _, _ = numeric
    before = phase1._read(engine)
    calls = []

    def record(conn, cursor, statement, params, context, executemany):
        calls.append(statement)

    with engine.connect() as c:
        oid = _oid(c)
        event.listen(c, "before_cursor_execute", record)
        try:
            with pytest.raises(StatementError) as error:
                c.execute(
                    update(TYPED).where(TYPED.c.id == oid).values({name: invalid})
                )
            assert type(error.value.orig) is ValueError
            assert str(error.value.orig) == "bounded exact integer required"
            assert calls == []
        finally:
            event.remove(c, "before_cursor_execute", record)
            c.rollback()
    assert phase1._read(engine) == before


def test_exact_next_has_explicit_finite_lab_boundary():
    with pytest.raises(ValueError, match="^bounded exact integer required$"):
        exact_next(LIMIT - 1)


RAW_CASES = [
    (str(LIMIT), "decimal budget exceeded"),
    ("1.25", "integral Decimal required"),
    ("NaN", "finite Decimal required"),
    ("Infinity", "finite Decimal required"),
    ("-Infinity", "finite Decimal required"),
    ("1." + "0" * 129, "decimal budget exceeded"),
    ("1." + "0" * 256, "decimal budget exceeded"),
    ("0E-256", "decimal budget exceeded"),
    ("1E128", "decimal budget exceeded"),
    ("1E256", "decimal budget exceeded"),
    ("1.000", None),
]


@pytest.mark.parametrize("name", FIELDS)
@pytest.mark.parametrize("spelling,reason", RAW_CASES)
def test_committed_raw_domain_and_real_decoder_boundary(
    numeric, source, name, spelling, reason
):
    engine, _, _ = numeric
    assert len(spelling) <= 258  # Fixed bounded probes, never unbounded scale.
    with engine.begin() as c:
        _raw_write(c, name, spelling)
        oid = _oid(c)
    with engine.connect() as reader, engine.connect() as observer:
        assert (
            reader.connection.driver_connection
            is not observer.connection.driver_connection
        )
        facts = _facts(_raw(observer, name, oid))
        path = source[3] / f"domain-{uuid4().hex}.json"
        path.write_text(
            json.dumps(
                {
                    "field": name,
                    "input": spelling,
                    "observed": facts,
                    "expected_decoder_error": reason,
                },
                default=str,
                sort_keys=True,
            )
        )
        print(f"raw domain evidence: {path}")
        if reason:
            with pytest.raises(ValueError) as error:
                reader.execute(
                    select(TYPED.c[name]).where(TYPED.c.id == oid)
                ).scalar_one()
            assert str(error.value) == reason
        else:
            assert (
                reader.execute(
                    select(TYPED.c[name]).where(TYPED.c.id == oid)
                ).scalar_one()
                == 1
            )
        reader.rollback()
    # The read error cannot undo the previously committed raw write.
    with engine.connect() as fresh:
        assert _facts(_raw(fresh, name, oid)) == facts


@pytest.mark.parametrize("name", FIELDS)
@pytest.mark.parametrize(
    "spelling,reason",
    [
        ("0", None),
        (str(MAX), None),
        (str(MAX + 1), "bigint_range"),
        ("-1", "negative_protocol"),
        (str(-(2**63) - 1), "negative_protocol"),
        ("0.5", "fraction"),
        ("NaN", "nonfinite"),
        ("Infinity", "nonfinite"),
    ],
)
def test_direct_validator_on_each_actual_column_not_pending_gate(
    numeric, name, spelling, reason
):
    engine, _, _ = numeric
    with engine.begin() as c:
        _raw_write(c, name, spelling)
    with engine.connect() as observer:
        value, _ = _raw(observer, name, _oid(observer))
        if reason:
            with pytest.raises(DowngradeRejected) as error:
                _validate_raw(value)
            assert str(error.value) == reason
        else:
            assert _validate_raw(value) == int(spelling)
    # This is explicitly a direct validator control, not orchestrator acceptance.


def test_eligible_downgrade_commits_preserving_source_manifest_and_children(numeric):
    engine, source_name, original = numeric
    full_before = phase1.baseline._manifest(engine)
    before = phase1._read(engine)
    with engine.connect() as c:
        tx = phase1._begin(c)
        _downgrade(c, before[0])
        tx.commit()
    assert phase1._read(engine) == original
    expected = deepcopy(full_before)
    for col in expected["tables"]["instantorder"]["columns"]:
        if col["name"] in FIELDS:
            assert col["type"] == "NUMERIC"
            col["type"] = "BIGINT"
    assert phase1.baseline._manifest(engine) == expected
    phase1._enforcement(engine, source_name)
    assert phase1._read(engine) == original


@pytest.mark.parametrize("fail_after", [1, 2])
def test_downgrade_failure_restores_both_numeric_types_and_caller_write(
    numeric, fail_after
):
    engine, _, _ = numeric
    before = phase1._read(engine)
    with engine.connect() as c:
        tx = phase1._begin(c)
        c.exec_driver_sql(
            "UPDATE public.user SET name='uncommitted downgrade sentinel'"
        )
        with pytest.raises(
            phase1.InjectedRollback, match=f"^after downgrade {fail_after}$"
        ):
            _downgrade(c, before[0], fail_after)
        tx.rollback()
    assert phase1._read(engine) == before


@pytest.mark.parametrize(
    "spelling,reason",
    [
        (str(MAX + 1), "bigint_range"),
        (str(LIMIT - 1), "bigint_range"),
        ("-1", "negative_protocol"),
        (str(-(2**63) - 1), "negative_protocol"),
        ("1.5", "fraction"),
        ("NaN", "nonfinite"),
        ("Infinity", "nonfinite"),
        ("-Infinity", "nonfinite"),
    ],
)
def test_downgrade_rejects_search_domain_before_any_cast(numeric, spelling, reason):
    engine, _, _ = numeric
    with engine.begin() as c:
        _raw_write(c, "search_revision", spelling)
    before = phase1._read(engine)
    with engine.connect() as c:
        tx = phase1._begin(c)
        with pytest.raises(DowngradeRejected) as error:
            _downgrade(c, before[0])
        assert str(error.value) == reason
        tx.rollback()
    assert phase1._read(engine) == before


@pytest.mark.parametrize(
    "assignment",
    [
        "matching_resume_revision=0",
        "matching_resume_revision='NaN'::numeric",
        f"matching_resume_revision={MAX + 1}",
        "matching_resume_next_at=CURRENT_TIMESTAMP",
        "matching_resume_attempts=0",
        "matching_resume_revision=0,matching_resume_next_at=CURRENT_TIMESTAMP,matching_resume_attempts=0",
    ],
)
def test_pending_gate_precedes_even_invalid_second_column_range(numeric, assignment):
    engine, _, _ = numeric
    with engine.begin() as c:
        c.exec_driver_sql(
            f"UPDATE instantorder SET {assignment} WHERE id=(SELECT min(id) FROM instantorder)"
        )
    before = phase1._read(engine)
    with engine.connect() as c:
        tx = phase1._begin(c)
        with pytest.raises(DowngradeRejected, match="^pending$"):
            _downgrade(c, before[0])
        tx.rollback()
    assert phase1._read(engine) == before


@pytest.mark.parametrize(
    "ddl",
    [
        "ALTER TABLE instantorder ALTER COLUMN search_revision SET DEFAULT 0",
        "CREATE VIEW domain_rehearsal_view AS SELECT search_revision FROM instantorder",
    ],
)
def test_downgrade_rejects_catalog_drift(numeric, ddl):
    engine, _, _ = numeric
    expected = phase1._read(engine)[0]
    with engine.begin() as c:
        c.exec_driver_sql(ddl)
    before = phase1._read(engine)
    with engine.connect() as c:
        tx = phase1._begin(c)
        with pytest.raises(DowngradeRejected, match="^catalog$"):
            _downgrade(c, expected)
        tx.rollback()
    assert phase1._read(engine) == before


@pytest.mark.parametrize(
    "bad,reason",
    [
        (f"search_revision={MAX + 1}", "bigint_range"),
        ("matching_resume_revision=0", "pending"),
    ],
)
@pytest.mark.parametrize("commit_writer", [False, True])
def test_waiting_downgrade_rereads_writer_commit_or_rollback(
    numeric, bad, reason, commit_writer
):
    engine, _, original = numeric
    before = phase1._read(engine)
    with engine.connect() as holder:
        tx = phase1._begin(holder)
        pid = holder.exec_driver_sql("SELECT pg_backend_pid()").scalar_one()
        holder.exec_driver_sql(
            f"UPDATE instantorder SET {bad} WHERE id=(SELECT min(id) FROM instantorder)"
        )

        def action(c):
            try:
                _downgrade(c, before[0])
                return "downgraded"
            except DowngradeRejected as error:
                assert str(error) == reason
                return reason

        with phase1._worker(engine, action) as (waiter, future):
            try:
                phase1._blocked(engine, waiter, pid)
            finally:
                if commit_writer:
                    tx.commit()
                else:
                    tx.rollback()
            assert future.result(timeout=8) == (
                reason if commit_writer else "downgraded"
            )
    if not commit_writer:
        assert phase1._read(engine) == original
    else:
        expected = deepcopy(before[1])
        field, value = bad.split("=")
        min(expected["instantorder"], key=lambda row: row["id"])[field] = int(value)
        assert phase1._read(engine) == (before[0], expected)


@pytest.mark.parametrize("value,expected", [(MAX, "committed"), (MAX + 1, "22003")])
def test_downgrade_first_blocks_writer_then_distinguishes_bigint_overflow(
    numeric, value, expected
):
    engine, _, original = numeric
    before = phase1._read(engine)
    with engine.connect() as holder:
        tx = phase1._begin(holder)
        pid = holder.exec_driver_sql("SELECT pg_backend_pid()").scalar_one()
        _downgrade(holder, before[0])

        def action(c):
            try:
                # Savepoint leaves the worker outer transaction usable on overflow.
                with c.begin_nested():
                    c.execute(
                        text(
                            "UPDATE instantorder SET search_revision=:value WHERE id=(SELECT min(id) FROM instantorder)"
                        ),
                        {"value": value},
                    )
                return "committed"
            except DataError as error:
                assert error.orig.pgcode == "22003"
                return error.orig.pgcode

        with phase1._worker(engine, action) as (waiter, future):
            try:
                phase1._blocked(engine, waiter, pid)
                assert not future.done()
            finally:
                tx.commit()
            assert future.result(timeout=8) == expected
    expected_data = deepcopy(original[1])
    if expected == "committed":
        min(expected_data["instantorder"], key=lambda row: row["id"])[
            "search_revision"
        ] = value
    assert phase1._read(engine) == (original[0], expected_data)
