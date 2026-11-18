"""Private disposable PG only; own transaction-scoped schema, no public migration."""
from uuid import uuid4

import pytest
from sqlalchemy import Column, Integer, MetaData, Table, inspect, text

from app import models
from app.db import engine
from test_taxi_search_revision_postgres import _disposable_pg_before_client  # noqa: F401
from test_taxi_tried_history_schema import _schema_contract

pytestmark = pytest.mark.skipif(engine.dialect.name != "postgresql", reason="requires guarded disposable PostgreSQL")


@pytest.fixture
def own_schema(request):
    request.getfixturevalue("_disposable_pg_before_client")
    name = "history_schema_" + uuid4().hex
    with engine.connect() as connection:
        transaction = connection.begin()
        try:
            connection.execute(text(f'CREATE SCHEMA "{name}"'))
            connection.execute(text(f'SET LOCAL search_path TO "{name}"'))
            assert connection.execute(text("SELECT current_schema()")).scalar_one() == name
            yield connection
        finally:
            # DDL and test rows roll back together, even after a failed assertion.
            transaction.rollback()
    with engine.connect() as observer:
        assert observer.execute(text("SELECT count(*) FROM pg_namespace WHERE nspname=:name"), {"name": name}).scalar_one() == 0


def test_pg_real_migration_history_pk_fk_rollback_and_downgrade(own_schema):
    _schema_contract(own_schema, "offered")


def test_pg_model_table_parent_cascade_and_driver_has_no_foreign_key(own_schema):
    metadata = MetaData()
    Table("instantorder", metadata, Column("id", Integer, primary_key=True))
    child = models.InstantTriedExclusion.__table__.to_metadata(metadata)
    metadata.create_all(own_schema)
    assert inspect(own_schema).get_pk_constraint(child.name)["constrained_columns"] == ["order_id", "lifecycle", "driver_id"]
    fks = inspect(own_schema).get_foreign_keys(child.name)
    assert len(fks) == 1 and fks[0]["options"]["ondelete"] == "CASCADE"
    own_schema.execute(text("INSERT INTO instantorder VALUES(1)"))
    own_schema.execute(child.insert().values(order_id=1, lifecycle="a" * 32, driver_id=987654))
    with own_schema.begin_nested() as savepoint:
        own_schema.execute(text("DELETE FROM instantorder WHERE id=1"))
        assert own_schema.execute(text("SELECT count(*) FROM instanttriedexclusion")).scalar_one() == 0
        savepoint.rollback()
    assert own_schema.execute(text("SELECT count(*) FROM instanttriedexclusion")).scalar_one() == 1
    own_schema.execute(text("DELETE FROM instantorder WHERE id=1"))
    assert own_schema.execute(text("SELECT count(*) FROM instanttriedexclusion")).scalar_one() == 0
