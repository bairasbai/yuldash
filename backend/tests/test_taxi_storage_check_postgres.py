"""TEST-ONLY validated CHECK subset on isolated actual PostgreSQL clones.

The subset is narrower than the signed adapter: stored scale must be zero.
No application migration/model/writer, activation or production policy change.
"""

from copy import deepcopy
from decimal import Decimal

import pytest
from sqlalchemy import event, inspect, select, text, update
from sqlalchemy.exc import IntegrityError, StatementError

import test_taxi_actual_schema_pg_type_rehearsal as phase1
import test_taxi_actual_schema_pg_domain_rehearsal as phase2

source = phase1.source
database = phase1.database
FIELDS = phase1.REVISION_COLUMNS
LIMIT = 10**128
CHECKS = {
    "search_revision": "ck_instant_search_revision_domain",
    "matching_resume_revision": "ck_instant_resume_revision_domain",
}


class StoragePreflightRejected(RuntimeError):
    pass


def _predicate(name):
    assert name in FIELDS
    return f"""{name} IS NULL OR (
        {name}::text NOT IN ('NaN','Infinity','-Infinity')
        AND {name}=trunc({name}) AND {name}>=0
        AND {name}<1e128::numeric AND scale({name})=0)"""


def _definitions(c):
    """Server-canonical reference syntax, not a substitute for enforcement tests.

    A transaction-local TEMP reference table is dropped before real ADDs. This
    avoids guessing server deparser formatting while checking exact definitions.
    """
    name = "storage_domain_reference"
    c.exec_driver_sql(
        "CREATE TEMP TABLE "
        + name
        + " ("
        + ",".join(
            f"{field} NUMERIC CONSTRAINT {CHECKS[field]} CHECK({_predicate(field)})"
            for field in FIELDS
        )
        + ") ON COMMIT DROP"
    )
    try:
        rows = (
            c.execute(
                text("""SELECT conname,pg_get_constraintdef(oid) AS definition,convalidated
            FROM pg_constraint WHERE conrelid='pg_temp.storage_domain_reference'::regclass
            ORDER BY conname""")
            )
            .mappings()
            .all()
        )
        assert len(rows) == 2 and all(row["convalidated"] for row in rows)
        schema = c.exec_driver_sql(
            "SELECT nspname FROM pg_namespace WHERE oid=pg_my_temp_schema()"
        ).scalar_one()
        reflected = inspect(c).get_check_constraints(name, schema=schema)
        return {row["conname"]: row["definition"] for row in rows}, reflected
    finally:
        c.exec_driver_sql("DROP TABLE pg_temp.storage_domain_reference")


def _expected_catalog(before, definitions):
    expected = deepcopy(before)
    expected["constraints"].extend(
        {
            "relname": "instantorder",
            "conname": name,
            "contype": "c",
            "definition": definition,
        }
        for name, definition in definitions.items()
    )
    expected["constraints"].sort(key=lambda row: (row["relname"], row["conname"]))
    return expected


def _install(c, expected_catalog, fail_after=None):
    assert c.in_transaction() and c.connection.driver_connection.autocommit is False
    assert (
        c.exec_driver_sql("SHOW transaction_isolation").scalar_one() == "read committed"
    )
    c.exec_driver_sql("LOCK TABLE instantorder IN ACCESS EXCLUSIVE MODE")
    catalog = phase1._catalog(c)
    if catalog != expected_catalog or catalog["triggers"] or catalog["views"]:
        raise StoragePreflightRejected("catalog")
    columns = {
        r["attname"]: r for r in catalog["columns"] if r["relname"] == "instantorder"
    }
    if any(
        columns[n]["type"] != "numeric"
        or columns[n]["attnotnull"]
        or columns[n]["default"] is not None
        for n in FIELDS
    ):
        raise StoragePreflightRejected("shape")
    if c.exec_driver_sql("""SELECT EXISTS(SELECT 1 FROM instantorder WHERE matching_resume_revision IS NOT NULL
        OR matching_resume_next_at IS NOT NULL OR matching_resume_attempts IS NOT NULL)""").scalar_one():
        raise StoragePreflightRejected("pending")
    for name in FIELDS:
        if c.exec_driver_sql(
            f"SELECT EXISTS(SELECT 1 FROM instantorder WHERE ({_predicate(name)}) IS NOT TRUE)"
        ).scalar_one():
            raise StoragePreflightRejected("stored_domain")
    before = phase1._data(c)
    definitions, reflected = _definitions(c)
    for number, name in enumerate(FIELDS, 1):
        c.exec_driver_sql(
            f"ALTER TABLE instantorder ADD CONSTRAINT {CHECKS[name]} CHECK({_predicate(name)})"
        )
        if number == fail_after:
            raise phase1.InjectedRollback(f"after CHECK {number}")
    actual = (
        c.execute(
            text("""SELECT conname,pg_get_constraintdef(oid) AS definition,convalidated
        FROM pg_constraint WHERE conrelid='instantorder'::regclass AND contype='c' ORDER BY conname""")
        )
        .mappings()
        .all()
    )
    assert {r["conname"]: r["definition"] for r in actual} == definitions
    assert all(r["convalidated"] for r in actual)
    assert phase1._catalog(c) == _expected_catalog(catalog, definitions)
    assert phase1._data(c) == before
    return definitions, reflected


@pytest.fixture
def numeric(database):
    engine, source_name = database
    before = phase1._read(engine)
    with engine.connect() as c:
        tx = phase1._begin(c)
        phase1._upgrade(c, before[0])
        tx.commit()
    return engine, source_name


@pytest.fixture
def checked(numeric):
    engine, source_name = numeric
    before = phase1._read(engine)
    with engine.connect() as c:
        tx = phase1._begin(c)
        _install(c, before[0])
        tx.commit()
    return engine, source_name


def _error(error, name):
    assert error.orig.pgcode == "23514"
    assert error.orig.diag.constraint_name == CHECKS[name]


def test_two_validated_checks_preserve_exact_source_manifest_and_enforcement(numeric):
    engine, source_name = numeric
    before = phase1._read(engine)
    manifest = phase1.baseline._manifest(engine)
    with engine.connect() as c:
        tx = phase1._begin(c)
        definitions, reflected = _install(c, before[0])
        tx.commit()
    expected = deepcopy(manifest)
    expected["tables"]["instantorder"]["checks"] = sorted(
        expected["tables"]["instantorder"]["checks"] + reflected,
        key=lambda row: row["name"],
    )
    expected["definitions"]["constraints"].extend(
        {
            "table_name": "instantorder",
            "conname": name,
            "contype": "c",
            "definition": definition,
        }
        for name, definition in definitions.items()
    )
    expected["definitions"]["constraints"].sort(
        key=lambda row: (row["table_name"], row["conname"])
    )
    assert phase1.baseline._manifest(engine) == expected
    assert phase1._read(engine) == (
        _expected_catalog(before[0], definitions),
        before[1],
    )
    phase1._enforcement(engine, source_name)
    assert phase1._read(engine) == (
        _expected_catalog(before[0], definitions),
        before[1],
    )


@pytest.mark.parametrize("name", FIELDS)
@pytest.mark.parametrize(
    "value", [None, 0, 2**53 + 1, 2**63 - 2, 2**63 - 1, 2**63, 10**40 + 1, LIMIT - 1]
)
def test_typed_bind_result_returning_equality_and_raw_observer(checked, name, value):
    engine, _ = checked
    col = phase2.TYPED.c[name]
    with engine.connect() as c:
        oid = phase2._oid(c)
        got = c.execute(
            update(phase2.TYPED)
            .where(phase2.TYPED.c.id == oid)
            .values({name: value})
            .returning(col)
        ).scalar_one()
        assert got == value and (got is None or type(got) is int)
        c.commit()
        with engine.connect() as observer:
            assert (
                observer.connection.driver_connection
                is not c.connection.driver_connection
            )
            raw, _ = phase2._raw(observer, name, oid)
            assert (
                raw is None
                if value is None
                else isinstance(raw, Decimal) and raw == Decimal(value)
            )
            assert observer.execute(
                text(f"SELECT scale({name}) FROM instantorder WHERE id=:id"),
                {"id": oid},
            ).scalar_one() == (None if value is None else 0)
        predicate = col.is_(None) if value is None else col == value
        replacement = 1 if value == 0 else 0
        assert c.execute(
            update(phase2.TYPED)
            .where(phase2.TYPED.c.id == oid, predicate)
            .values({name: replacement})
            .returning(col)
        ).all() == [(replacement,)]
        assert (
            c.execute(
                update(phase2.TYPED)
                .where(phase2.TYPED.c.id == oid, predicate)
                .values({name: 7})
                .returning(col)
            ).all()
            == []
        )
        c.commit()
        assert (
            c.execute(select(col).where(phase2.TYPED.c.id == oid)).scalar_one()
            == replacement
        )
        c.rollback()
        before = phase1._read(engine)
        c.execute(
            update(phase2.TYPED).where(phase2.TYPED.c.id == oid).values({name: 3})
        )
        c.rollback()
        assert phase1._read(engine) == before


RAW_BAD = [
    "-1",
    "1.25",
    "NaN",
    "Infinity",
    "-Infinity",
    str(LIMIT),
    "1.000",
    "1." + "0" * 256,
    "0E-256",
]


@pytest.mark.parametrize("name", FIELDS)
@pytest.mark.parametrize("value", RAW_BAD)
def test_raw_invalid_is_true_named_check_failure(checked, name, value):
    engine, _ = checked
    before = phase1._read(engine)
    with engine.connect() as c:
        raw, scale = c.execute(
            text("SELECT CAST(:v AS numeric),scale(CAST(:v AS numeric))"), {"v": value}
        ).one()
        assert isinstance(raw, Decimal)
        if value == "1.000":
            assert raw == 1 and scale == 3
        # Observe the composite predicate on special values; no evaluation-order claim.
        # Any SQL error FAILS rather than proving CHECK rejection.
        assert (
            c.execute(
                text(
                    f"SELECT ({_predicate(name)}) IS NOT TRUE FROM (SELECT CAST(:v AS numeric) AS {name}) x"
                ),
                {"v": value},
            ).scalar_one()
            is True
        )
        with pytest.raises(IntegrityError) as error:
            with c.begin_nested():
                phase2._raw_write(c, name, value)
        _error(error.value, name)
        assert c.exec_driver_sql("SELECT 1").scalar_one() == 1
        c.rollback()
    assert phase1._read(engine) == before


@pytest.mark.parametrize("name", FIELDS)
def test_signed_adapter_negative_reaches_database_check(checked, name):
    engine, _ = checked
    before = phase1._read(engine)
    with engine.connect() as c:
        oid = phase2._oid(c)
        with pytest.raises(IntegrityError) as error:
            with c.begin_nested():
                c.execute(
                    update(phase2.TYPED)
                    .where(phase2.TYPED.c.id == oid)
                    .values({name: -1})
                )
        _error(error.value, name)
        c.rollback()
    assert phase1._read(engine) == before


@pytest.mark.parametrize("name", FIELDS)
@pytest.mark.parametrize("value", [True, 1.5, LIMIT])
def test_invalid_typed_bind_fails_before_sql(checked, name, value):
    engine, _ = checked
    before = phase1._read(engine)
    calls = []

    def record(conn, cursor, statement, params, context, many):
        calls.append(statement)

    with engine.connect() as c:
        oid = phase2._oid(c)
        event.listen(c, "before_cursor_execute", record)
        try:
            with pytest.raises(StatementError) as error:
                c.execute(
                    update(phase2.TYPED)
                    .where(phase2.TYPED.c.id == oid)
                    .values({name: value})
                )
            assert type(error.value.orig) is ValueError
            assert str(error.value.orig) == "bounded exact integer required"
            assert calls == []
        finally:
            event.remove(c, "before_cursor_execute", record)
            c.rollback()
    assert phase1._read(engine) == before


@pytest.mark.parametrize("bad_field", FIELDS)
def test_combined_update_and_raw_max_increment_fail_atomically(checked, bad_field):
    engine, _ = checked
    other = next(n for n in FIELDS if n != bad_field)
    before = phase1._read(engine)
    with engine.connect() as c:
        oid = phase2._oid(c)
        with pytest.raises(IntegrityError) as error:
            with c.begin_nested():
                c.execute(
                    text(
                        f"UPDATE instantorder SET {other}=12,{bad_field}=CAST(:v AS numeric) WHERE id=:id"
                    ),
                    {"v": str(LIMIT), "id": oid},
                )
        _error(error.value, bad_field)
        c.rollback()
    assert phase1._read(engine) == before
    with engine.begin() as c:
        phase2._raw_write(c, bad_field, str(LIMIT - 1))
    maximum = phase1._read(engine)
    with engine.connect() as c:
        with pytest.raises(IntegrityError) as error:
            with c.begin_nested():
                c.execute(
                    text(
                        f"UPDATE instantorder SET {bad_field}={bad_field}+1 WHERE id=:id"
                    ),
                    {"id": oid},
                )
        _error(error.value, bad_field)
        c.rollback()
    assert phase1._read(engine) == maximum


@pytest.mark.parametrize("number", [1, 2])
def test_injected_check_add_failure_rolls_back_both_catalog_and_caller_data(
    numeric, number
):
    engine, _ = numeric
    before = phase1._read(engine)
    with engine.connect() as c:
        tx = phase1._begin(c)
        c.exec_driver_sql("UPDATE public.user SET name='uncommitted CHECK sentinel'")
        with pytest.raises(phase1.InjectedRollback, match=f"^after CHECK {number}$"):
            _install(c, before[0], number)
        tx.rollback()
    assert phase1._read(engine) == before


@pytest.mark.parametrize("value", RAW_BAD)
def test_invalid_existing_search_value_rejected_before_any_add(numeric, value):
    engine, _ = numeric
    with engine.begin() as c:
        phase2._raw_write(c, "search_revision", value)
    before = phase1._read(engine)
    with engine.connect() as c:
        tx = phase1._begin(c)
        with pytest.raises(StoragePreflightRejected, match="^stored_domain$"):
            _install(c, before[0])
        tx.rollback()
    assert phase1._read(engine) == before


@pytest.mark.parametrize(
    "assignment",
    [
        "matching_resume_revision=0",
        "matching_resume_next_at=CURRENT_TIMESTAMP",
        "matching_resume_attempts=0",
    ],
)
def test_pending_inactive_gate_not_second_column_range_proof(numeric, assignment):
    engine, _ = numeric
    with engine.begin() as c:
        c.exec_driver_sql(
            f"UPDATE instantorder SET {assignment} WHERE id=(SELECT min(id) FROM instantorder)"
        )
    before = phase1._read(engine)
    with engine.connect() as c:
        tx = phase1._begin(c)
        with pytest.raises(StoragePreflightRejected, match="^pending$"):
            _install(c, before[0])
        tx.rollback()
    assert phase1._read(engine) == before


@pytest.mark.parametrize(
    "ddl",
    [
        "ALTER TABLE instantorder ALTER COLUMN search_revision SET DEFAULT 0",
        "CREATE VIEW storage_check_view AS SELECT search_revision FROM instantorder",
    ],
)
def test_catalog_drift_is_rejected_not_repaired(numeric, ddl):
    engine, _ = numeric
    expected = phase1._read(engine)[0]
    with engine.begin() as c:
        c.exec_driver_sql(ddl)
    before = phase1._read(engine)
    with engine.connect() as c:
        tx = phase1._begin(c)
        with pytest.raises(StoragePreflightRejected, match="^catalog$"):
            _install(c, expected)
        tx.rollback()
    assert phase1._read(engine) == before
