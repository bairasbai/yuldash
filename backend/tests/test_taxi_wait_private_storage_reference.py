"""Standalone DTO/storage boundary, NOT application DTO or migration acceptance.

Run --noconftest. Non-table SQLModel fixtures never register application/global
mappings. sqlite3 owns a fresh file only. No nested serialization, HTTP, PG,
logging infrastructure, activation, rotation or legacy policy is tested here.
"""

import json
import sqlite3
from contextlib import contextmanager
from importlib.metadata import version

import pytest
from sqlmodel import Field, SQLModel

SENTINEL = "synthetic-private-wait-7ea981bc26d4"
OTHER = "synthetic-other-scope-83cb92e0"


@pytest.fixture
def dto_types():
    metadata = SQLModel.metadata
    before = dict(metadata.tables)

    class Ordinary(SQLModel):
        id: int
        name: str
        marker: str | None = None

    class Excluded(SQLModel):
        id: int
        name: str
        marker: str | None = Field(default=None, exclude=True)

    class Public(SQLModel):
        id: int
        name: str

    class Private(SQLModel):
        user_id: int
        domain: str
        marker: str | None = None

    try:
        assert SQLModel.metadata is metadata
        assert dict(metadata.tables) == before
        yield Ordinary, Excluded, Public, Private
    finally:
        assert SQLModel.metadata is metadata
        assert dict(metadata.tables) == before
        assert all(metadata.tables[key] is table for key, table in before.items())


def test_record_installed_framework_versions(record_property):
    # Evidence records this installation; no future-version compatibility claim.
    for distribution in ("sqlmodel", "pydantic", "sqlalchemy"):
        installed = version(distribution)
        assert installed
        record_property(distribution, installed)
        print(f"{distribution}={installed}")


@pytest.mark.parametrize("marker", [None, SENTINEL])
def test_ordinary_added_field_is_exported_even_when_null(dto_types, marker):
    Ordinary, _, _, _ = dto_types
    value = Ordinary(id=7, name="Fixture passenger", marker=marker)
    expected = {"id": 7, "name": "Fixture passenger", "marker": marker}
    assert value.model_dump() == expected
    assert json.loads(value.model_dump_json()) == expected
    assert value.__dict__["marker"] == marker
    if marker is not None:
        assert marker in repr(value)
        assert marker in str(value)


@pytest.mark.parametrize("marker", [None, SENTINEL])
def test_exclude_is_dump_boundary_not_repr_str_or_dictionary_confidentiality(
    dto_types, marker
):
    _, Excluded, _, _ = dto_types
    value = Excluded(id=7, name="Fixture passenger", marker=marker)
    expected = {"id": 7, "name": "Fixture passenger"}
    assert value.model_dump() == expected
    assert json.loads(value.model_dump_json()) == expected
    # Explicit serialization include does not negate the declared exclusion.
    assert value.model_dump(include={"marker"}) == {}
    assert value.__dict__["marker"] == marker
    if marker is not None:
        assert marker in repr(value)
        assert marker in str(value)
        assert marker not in value.model_dump_json()


@pytest.mark.parametrize("marker", [None, SENTINEL])
def test_separate_entity_reduces_public_dump_surface_not_private_row_exposure(
    dto_types, marker
):
    _, _, Public, Private = dto_types
    public = Public(id=7, name="Fixture passenger")
    private = Private(user_id=7, domain="instant_fixture", marker=marker)
    assert not hasattr(public, "marker")
    assert not hasattr(public, "scope")
    assert set(Public.model_fields) == {"id", "name"}
    assert public.model_dump() == {"id": 7, "name": "Fixture passenger"}
    assert json.loads(public.model_dump_json()) == public.model_dump()
    assert SENTINEL not in repr(public) + str(public) + repr(public.__dict__)
    assert private.model_dump() == {
        "user_id": 7,
        "domain": "instant_fixture",
        "marker": marker,
    }
    assert json.loads(private.model_dump_json()) == private.model_dump()
    assert private.__dict__["marker"] == marker
    if marker is not None:
        assert marker in repr(private) + str(private)
        assert marker in repr(private)
        assert marker in str(private)
    # No nested DTO or automatic relationship is present or claimed safe.


@pytest.fixture
def database(tmp_path):
    directory = tmp_path / "owned-private-storage-reference"
    directory.mkdir()
    path = directory / "private.sqlite"
    active = []

    @contextmanager
    def connect():
        connection = sqlite3.connect(path, isolation_level=None)
        active.append(connection)
        try:
            connection.execute("PRAGMA foreign_keys=ON")
            assert connection.execute("PRAGMA foreign_keys").fetchone() == (1,)
            yield connection
        finally:
            try:
                if connection.in_transaction:
                    connection.rollback()
            finally:
                connection.close()
                active.remove(connection)

    try:
        with connect() as connection:
            connection.execute(
                "CREATE TABLE public_user(id INTEGER PRIMARY KEY,name TEXT NOT NULL)"
            )
            connection.executemany(
                "INSERT INTO public_user VALUES(?,?)", [(7, "Owner"), (8, "Other")]
            )
        yield connect
    finally:
        assert not active
        for suffix in ("", "-wal", "-shm", "-journal"):
            candidate = directory / ("private.sqlite" + suffix)
            if candidate.exists():
                candidate.unlink()
        directory.rmdir()


def add_private_table(connection):
    connection.execute(
        "CREATE TABLE private_scope(user_id INTEGER NOT NULL,domain TEXT NOT NULL,"
        "marker VARCHAR(64),PRIMARY KEY(user_id,domain),"
        "FOREIGN KEY(user_id) REFERENCES public_user(id) ON DELETE CASCADE)"
    )


def users(connection):
    return connection.execute("SELECT id,name FROM public_user ORDER BY id").fetchall()


def private_rows(connection):
    return connection.execute(
        "SELECT user_id,domain,marker FROM private_scope ORDER BY user_id,domain"
    ).fetchall()


def guarded_drop_empty_fixture_table(connection):
    """Fixture-only destructive-action preflight; not a production downgrade.

    This closed fixture inventory has no virtual tables, attached schemas or
    untrusted DDL. Refuse any FK reference or view/trigger mentioning the table.
    The caller holds BEGIN IMMEDIATE throughout validation and DROP.
    """
    if not connection.in_transaction:
        raise RuntimeError("Caller must hold writer transaction")
    if connection.execute("SELECT count(*) FROM private_scope").fetchone() != (0,):
        raise ValueError("populated storage must not be dropped")
    for (name,) in connection.execute(
        "SELECT name FROM sqlite_master WHERE type='table'"
    ).fetchall():
        quoted = '"' + name.replace('"', '""') + '"'
        if any(
            row[2] == "private_scope"
            for row in connection.execute(f"PRAGMA foreign_key_list({quoted})")
        ):
            raise ValueError("dependent foreign key")
    dependencies = connection.execute(
        "SELECT name FROM sqlite_master WHERE type IN ('view','trigger') "
        "AND lower(sql) LIKE '%private_scope%'"
    ).fetchall()
    if dependencies:
        raise ValueError("dependent view or trigger")
    connection.execute("DROP TABLE private_scope")


def test_additive_table_preserves_populated_legacy_rows_and_old_insert(database):
    with database() as connection:
        before = users(connection)
        connection.execute("BEGIN IMMEDIATE")
        add_private_table(connection)
        assert users(connection) == before
        assert private_rows(connection) == []
        connection.execute("INSERT INTO public_user(id,name) VALUES(9,'Old writer')")
        connection.commit()
    with database() as observer:
        assert users(observer) == [(7, "Owner"), (8, "Other"), (9, "Old writer")]
        assert private_rows(observer) == []
        fields = observer.execute("PRAGMA table_info(private_scope)").fetchall()
        assert [(f[1], f[3], f[4]) for f in fields] == [
            ("user_id", 1, None),
            ("domain", 1, None),
            ("marker", 0, None),
        ]
        assert (
            observer.execute("PRAGMA foreign_key_list(private_scope)").fetchone()[6]
            == "CASCADE"
        )


def test_missing_private_read_never_initializes_and_explicit_query_exposes_sentinel(
    database, dto_types
):
    _, _, Public, _ = dto_types
    with database() as connection:
        add_private_table(connection)
        before = users(connection)
        assert (
            connection.execute(
                "SELECT marker FROM private_scope WHERE user_id=7 AND domain='instant_fixture'"
            ).fetchone()
            is None
        )
        assert private_rows(connection) == []
        connection.execute(
            "INSERT INTO private_scope VALUES(7,'instant_fixture',?)", (SENTINEL,)
        )
        raw = connection.execute(
            "SELECT marker FROM private_scope WHERE user_id=7"
        ).fetchone()[0]
        assert (
            raw == SENTINEL
        )  # A separate table is not encryption or SQL-log redaction.
        public = Public(id=7, name="Owner")
        assert SENTINEL not in public.model_dump_json()
        assert users(connection) == before


def test_exact_fk_and_unique_failures_leave_existing_rows_unchanged(database):
    with database() as connection:
        add_private_table(connection)
        connection.execute(
            "INSERT INTO private_scope VALUES(7,'instant_fixture',?)", (SENTINEL,)
        )
        before = private_rows(connection)
        connection.execute("BEGIN IMMEDIATE")
        with pytest.raises(sqlite3.IntegrityError) as foreign:
            connection.execute(
                "INSERT INTO private_scope VALUES(999,'instant_fixture',NULL)"
            )
        assert foreign.value.sqlite_errorcode == sqlite3.SQLITE_CONSTRAINT_FOREIGNKEY
        assert foreign.value.sqlite_errorname == "SQLITE_CONSTRAINT_FOREIGNKEY"
        with pytest.raises(sqlite3.IntegrityError) as duplicate:
            connection.execute(
                "INSERT INTO private_scope VALUES(7,'instant_fixture',NULL)"
            )
        assert duplicate.value.sqlite_errorcode == sqlite3.SQLITE_CONSTRAINT_PRIMARYKEY
        assert duplicate.value.sqlite_errorname == "SQLITE_CONSTRAINT_PRIMARYKEY"
        assert private_rows(connection) == before
        connection.rollback()
        assert private_rows(connection) == before


def test_owner_delete_cascade_rollback_then_commit_preserves_other_owner(database):
    with database() as connection:
        add_private_table(connection)
        connection.executemany(
            "INSERT INTO private_scope VALUES(?,?,?)",
            [(7, "instant_fixture", SENTINEL), (8, "instant_fixture", OTHER)],
        )
        before_users, before_rows = users(connection), private_rows(connection)
        connection.execute("BEGIN IMMEDIATE")
        connection.execute("DELETE FROM public_user WHERE id=7")
        assert users(connection) == [(8, "Other")]
        assert private_rows(connection) == [(8, "instant_fixture", OTHER)]
        with database() as observer:
            assert users(observer) == before_users
            assert private_rows(observer) == before_rows
        connection.rollback()
        assert users(connection) == before_users
        assert private_rows(connection) == before_rows
        connection.execute("BEGIN IMMEDIATE")
        connection.execute("DELETE FROM public_user WHERE id=7")
        connection.commit()
    with database() as observer:
        assert users(observer) == [(8, "Other")]
        assert private_rows(observer) == [(8, "instant_fixture", OTHER)]


def test_additive_ddl_and_unrelated_write_rollback_preserve_legacy(database):
    with database() as connection:
        connection.execute("BEGIN IMMEDIATE")
        add_private_table(connection)
        connection.execute("UPDATE public_user SET name='Uncommitted' WHERE id=7")
        connection.execute(
            "INSERT INTO private_scope VALUES(7,'instant_fixture',?)", (SENTINEL,)
        )
        connection.rollback()
    with database() as observer:
        assert users(observer) == [(7, "Owner"), (8, "Other")]
        assert (
            observer.execute(
                "SELECT name FROM sqlite_master WHERE name='private_scope'"
            ).fetchone()
            is None
        )


def test_only_empty_dependency_free_table_is_removed(database):
    with database() as connection:
        before = users(connection)
        add_private_table(connection)
        connection.execute("BEGIN IMMEDIATE")
        connection.execute(
            "INSERT INTO private_scope VALUES(7,'instant_fixture',?)", (SENTINEL,)
        )
        connection.rollback()
        assert private_rows(connection) == []
        connection.execute("BEGIN IMMEDIATE")
        guarded_drop_empty_fixture_table(connection)
        connection.commit()
        assert users(connection) == before
        assert (
            connection.execute(
                "SELECT name FROM sqlite_master WHERE name='private_scope'"
            ).fetchone()
            is None
        )


def test_populated_table_drop_is_rejected_not_executed(database):
    with database() as connection:
        add_private_table(connection)
        connection.execute(
            "INSERT INTO private_scope VALUES(7,'instant_fixture',?)", (SENTINEL,)
        )
        before = private_rows(connection)
        statements = []
        connection.set_trace_callback(statements.append)
        connection.execute("BEGIN IMMEDIATE")
        with pytest.raises(ValueError, match="populated storage"):
            guarded_drop_empty_fixture_table(connection)
        connection.rollback()
        connection.set_trace_callback(None)
        assert not any(sql.lstrip().upper().startswith("DROP") for sql in statements)
        assert private_rows(connection) == before
        assert users(connection) == [(7, "Owner"), (8, "Other")]


@pytest.mark.parametrize("dependency", ["foreign_key", "view", "trigger"])
def test_empty_table_with_dependencies_is_not_dropped(database, dependency):
    with database() as connection:
        add_private_table(connection)
        statements = {
            "foreign_key": "CREATE TABLE dependent(user_id INTEGER,domain TEXT,FOREIGN KEY(user_id,domain) REFERENCES private_scope(user_id,domain))",
            "view": "CREATE VIEW dependent AS SELECT marker FROM private_scope",
            "trigger": "CREATE TRIGGER dependent AFTER INSERT ON public_user BEGIN DELETE FROM private_scope WHERE user_id=new.id; END",
        }
        connection.execute(statements[dependency])
        connection.execute("BEGIN IMMEDIATE")
        with pytest.raises(ValueError, match="dependent"):
            guarded_drop_empty_fixture_table(connection)
        connection.rollback()
        assert private_rows(connection) == []
        assert connection.execute(
            "SELECT name FROM sqlite_master WHERE name='dependent'"
        ).fetchone() == ("dependent",)
