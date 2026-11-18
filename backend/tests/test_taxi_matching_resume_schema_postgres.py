"""Resume migration on a transaction-scoped private schema, guarded disposable PG."""

from uuid import uuid4

import pytest
from sqlalchemy import text

from app.db import engine
from test_taxi_search_revision_postgres import _disposable_pg_before_client  # noqa: F401
from test_taxi_matching_resume_schema import _resume_schema_contract

pytestmark = pytest.mark.skipif(
    engine.dialect.name != "postgresql", reason="requires guarded disposable PostgreSQL"
)


@pytest.fixture
def resume_schema(request):
    request.getfixturevalue("_disposable_pg_before_client")
    name = "resume_schema_" + uuid4().hex
    with engine.connect() as c:
        txn = c.begin()
        try:
            c.execute(text(f'CREATE SCHEMA "{name}"'))
            c.execute(text(f'SET LOCAL search_path TO "{name}"'))
            assert c.execute(text("SELECT current_schema()")).scalar_one() == name
            yield c
        finally:
            txn.rollback()
    with engine.connect() as c:
        assert (
            c.execute(
                text("SELECT count(*) FROM pg_namespace WHERE nspname=:name"),
                {"name": name},
            ).scalar_one()
            == 0
        )


@pytest.mark.parametrize("status", ["searching", "offered", "done"])
def test_pg_resume_migration_retains_old_rows_and_child_history(resume_schema, status):
    _resume_schema_contract(resume_schema, status)


def test_pg_create_all_model_columns_match_inactive_resume_contract(client):
    from sqlalchemy import inspect, BigInteger, DateTime, Integer

    columns = {
        col["name"]: col
        for col in inspect(engine).get_columns("instantorder", schema="public")
    }
    assert isinstance(columns["matching_resume_revision"]["type"], BigInteger)
    assert isinstance(columns["matching_resume_next_at"]["type"], DateTime)
    assert columns["matching_resume_next_at"]["type"].timezone is False
    assert isinstance(columns["matching_resume_attempts"]["type"], Integer)
    for name in (
        "matching_resume_revision",
        "matching_resume_next_at",
        "matching_resume_attempts",
    ):
        assert columns[name]["nullable"] and columns[name]["default"] is None
        assert all(
            name not in (index["column_names"] or [])
            for index in inspect(engine).get_indexes("instantorder", schema="public")
        )
