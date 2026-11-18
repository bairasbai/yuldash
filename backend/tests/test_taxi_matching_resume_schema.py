"""Inactive nullable resume metadata only: no durable continuation is implemented."""

import importlib.util
from datetime import datetime
from io import StringIO
from pathlib import Path

import pytest
from alembic.migration import MigrationContext
from alembic.operations import Operations
from alembic.script import ScriptDirectory
from sqlalchemy import BigInteger, DateTime, Integer, create_engine, inspect, text
from sqlalchemy.dialects import postgresql, sqlite
from sqlmodel import Session

from app import instant_service as isv
from app.db import engine
from app.models import InstantOrder
from test_instant import _offered_order
from test_taxi_offer_token import fake_redis  # noqa: F401
from test_taxi_tried_lifecycle_base import _cleanup_lifecycle_orders  # noqa: F401

FIELDS = (
    "matching_resume_revision",
    "matching_resume_next_at",
    "matching_resume_attempts",
)


def _migration():
    path = Path(__file__).parents[1] / "alembic/versions/co_instant_matching_resume.py"
    spec = importlib.util.spec_from_file_location("matching_resume_migration", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    assert (
        module.revision == "co_instant_matching_resume"
        and module.down_revision == "cn_instant_tried_history"
    )
    return module


def _resume_schema_contract(c, status):
    """Own old populated schema, including existing child history, not app migration."""
    c.execute(
        text(
            "CREATE TABLE instantorder(id INTEGER PRIMARY KEY,status VARCHAR(32),offer_token VARCHAR(32),search_revision BIGINT,tried_lifecycle VARCHAR(32),tried_history_version INTEGER,tried_history_expires_at TIMESTAMP)"
        )
    )
    c.execute(
        text(
            "CREATE TABLE instanttriedexclusion(order_id INTEGER NOT NULL REFERENCES instantorder(id) ON DELETE CASCADE,lifecycle VARCHAR(32) NOT NULL,driver_id INTEGER NOT NULL,PRIMARY KEY(order_id,lifecycle,driver_id))"
        )
    )
    c.execute(
        text(
            "INSERT INTO instantorder VALUES(1,:status,:token,1099511627776,:cycle,1,'2030-01-02 03:04:05')"
        ),
        {"status": status, "token": "a" * 32, "cycle": "b" * 32},
    )
    c.execute(
        text("INSERT INTO instanttriedexclusion VALUES(1,:cycle,99)"),
        {"cycle": "b" * 32},
    )
    original = c.execute(text("SELECT * FROM instantorder ORDER BY id")).all()
    history = c.execute(text("SELECT * FROM instanttriedexclusion")).all()
    indexes = inspect(c).get_indexes("instantorder")
    checks = inspect(c).get_check_constraints("instantorder")
    migration = _migration()
    migration.op = Operations(MigrationContext.configure(c))
    migration.upgrade()
    columns = {col["name"]: col for col in inspect(c).get_columns("instantorder")}
    assert isinstance(columns[FIELDS[0]]["type"], BigInteger)
    assert (
        isinstance(columns[FIELDS[1]]["type"], DateTime)
        and columns[FIELDS[1]]["type"].timezone is False
    )
    assert isinstance(columns[FIELDS[2]]["type"], Integer) and not isinstance(
        columns[FIELDS[2]]["type"], BigInteger
    )
    assert all(
        columns[name]["nullable"] and columns[name]["default"] is None
        for name in FIELDS
    )
    assert c.execute(
        text(
            "SELECT matching_resume_revision,matching_resume_next_at,matching_resume_attempts FROM instantorder"
        )
    ).one() == (None, None, None)
    c.execute(
        text(
            "UPDATE instantorder SET matching_resume_revision=1099511627777,matching_resume_next_at='2031-02-03 04:05:06',matching_resume_attempts=-7 WHERE id=1"
        )
    )
    sentinel = c.execute(
        text(
            "SELECT matching_resume_revision,matching_resume_next_at,matching_resume_attempts FROM instantorder WHERE id=1"
        )
    ).one()
    migration.upgrade()  # Bootstrap no-op only, not schema drift repair.
    assert (
        c.execute(
            text(
                "SELECT matching_resume_revision,matching_resume_next_at,matching_resume_attempts FROM instantorder WHERE id=1"
            )
        ).one()
        == sentinel
    )
    c.execute(text("INSERT INTO instantorder(id,status) VALUES(2,'created')"))
    assert c.execute(
        text(
            "SELECT matching_resume_revision,matching_resume_next_at,matching_resume_attempts FROM instantorder WHERE id=2"
        )
    ).one() == (None, None, None)
    assert (
        inspect(c).get_indexes("instantorder") == indexes
        and inspect(c).get_check_constraints("instantorder") == checks
    )
    prior = c.execute(
        text(
            "SELECT id,status,offer_token,search_revision,tried_lifecycle,tried_history_version,tried_history_expires_at FROM instantorder ORDER BY id"
        )
    ).all()
    assert (
        prior[0] == original[0]
        and c.execute(text("SELECT * FROM instanttriedexclusion")).all() == history
    )
    migration.downgrade()
    assert not set(FIELDS).intersection(
        col["name"] for col in inspect(c).get_columns("instantorder")
    )
    assert c.execute(text("SELECT * FROM instantorder ORDER BY id")).all() == prior
    assert c.execute(text("SELECT * FROM instanttriedexclusion")).all() == history


@pytest.mark.parametrize(
    "status",
    ["created", "searching", "offered", "accepted", "done", "cancelled", "expired"],
)
def test_resume_sqlite_upgrade_nullable_no_backfill_and_downgrade_preserves_history(
    tmp_path, status
):
    db = create_engine(f"sqlite:///{tmp_path / 'resume.db'}")
    try:
        with db.connect() as c:
            c.execute(text("PRAGMA foreign_keys=ON"))
            c.commit()
            with c.begin():
                _resume_schema_contract(c, status)
    finally:
        db.dispose()


def test_resume_metadata_exact_types_no_defaults_indexes_or_checks():
    table = InstantOrder.__table__
    expected = ("BIGINT", "TIMESTAMP WITHOUT TIME ZONE", "INTEGER")
    for name, storage in zip(FIELDS, expected):
        column = table.c[name]
        assert column.type.compile(dialect=postgresql.dialect()) == storage
        assert column.type.compile(dialect=sqlite.dialect()) == (
            "DATETIME" if name == FIELDS[1] else storage
        )
        assert (
            column.nullable and column.default is None and column.server_default is None
        )
        assert not column.index and not column.unique
        assert (
            InstantOrder.model_fields[name].default is None
            and getattr(InstantOrder(passenger_id=1), name) is None
        )
        assert all(name not in str(index.expressions) for index in table.indexes)
        assert all(
            name not in str(getattr(constraint, "sqltext", ""))
            for constraint in table.constraints
        )
    assert table.c.matching_resume_next_at.type.timezone is False


def test_resume_offline_pg_sql_and_single_migration_head():
    migration = _migration()
    out = StringIO()
    migration.op = Operations(
        MigrationContext.configure(
            dialect_name="postgresql", opts={"as_sql": True, "output_buffer": out}
        )
    )
    migration.upgrade()
    ddl = out.getvalue()
    for name, storage in zip(
        FIELDS, ("BIGINT", "TIMESTAMP WITHOUT TIME ZONE", "INTEGER")
    ):
        assert f"ADD COLUMN {name} {storage}" in ddl
    assert all(
        forbidden not in ddl.upper()
        for forbidden in ("DEFAULT", "NOT NULL", "CREATE INDEX", "CHECK", "UPDATE ")
    )
    scripts = ScriptDirectory(str(Path(__file__).parents[1] / "alembic"))
    assert scripts.get_heads() == [migration.revision]


@pytest.mark.parametrize("sentinel", [False, True])
@pytest.mark.usefixtures("fake_redis")
def test_real_http_omits_resume_fields_and_does_not_activate_them(
    client, user_factory, monkeypatch, sentinel
):
    driver, passenger, offered = _offered_order(
        client,
        user_factory,
        isv._redis(),
        "ResumeSchemaDriver",
        "ResumeSchemaPassenger",
    )
    oid = offered["id"]
    expected = (
        (2**40, datetime(2031, 2, 3, 4, 5, 6), -7) if sentinel else (None, None, None)
    )
    with Session(engine) as session:
        order = session.get(InstantOrder, oid)
        assert tuple(getattr(order, name) for name in FIELDS) == (None, None, None), (
            "existing real matcher did not initialize resume"
        )
        for name, value in zip(FIELDS, expected):
            setattr(order, name, value)
        session.add(order)
        session.commit()
        original = (
            order.status,
            order.current_offer_driver_id,
            order.offer_token,
            order.search_revision,
            order.offer_expires_at,
        )

    def unexpected(*args, **kwargs):
        raise AssertionError("schema sentinel must not start matching")

    monkeypatch.setattr(isv, "candidates", unexpected)
    for path, actor in (
        (f"/instant/orders/{oid}", passenger),
        ("/instant/driver/offer", driver),
    ):
        response = client.get(path, headers=actor["auth"])
        assert response.status_code == 200, response.text
        assert all(name not in response.text for name in FIELDS)
    assert all(name not in offered for name in FIELDS)
    with Session(engine) as session:
        order = session.get(InstantOrder, oid)
        assert tuple(getattr(order, name) for name in FIELDS) == expected
        assert (
            order.status,
            order.current_offer_driver_id,
            order.offer_token,
            order.search_revision,
            order.offer_expires_at,
        ) == original
