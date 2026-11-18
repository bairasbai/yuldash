"""Schema compatibility only: revision is not yet rotated/enforced by writers."""
import importlib.util
from io import StringIO
from pathlib import Path

import pytest
from alembic.migration import MigrationContext
from alembic.operations import Operations
from sqlalchemy import BigInteger, create_engine, inspect, text
from sqlmodel import Session

from app.db import engine
from app.models import InstantOrder
from test_instant import _offered_order
from test_taxi_offer_token import fake_redis  # noqa: F401
from app import instant_service as isv


def _migration():
    path = Path(__file__).parents[1] / "alembic/versions/cl_instant_search_revision.py"
    spec = importlib.util.spec_from_file_location("search_revision_migration", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


@pytest.mark.parametrize("status", ["created", "searching", "offered", "accepted", "cancelled"])
def test_revision_migration_preserves_legacy_rows_without_backfill(tmp_path, status):
    migration = _migration()
    db = create_engine(f"sqlite:///{tmp_path / 'legacy.db'}")
    try:
        with db.begin() as connection:
            connection.execute(text("CREATE TABLE instantorder (id INTEGER PRIMARY KEY, status VARCHAR, offer_token VARCHAR(32))"))
            connection.execute(text("INSERT INTO instantorder VALUES (1, :status, :token)"), {"status": status, "token": "a" * 32})
            migration.op = Operations(MigrationContext.configure(connection))
            migration.upgrade()
            migration.upgrade()
            columns = {c["name"]: c for c in inspect(connection).get_columns("instantorder")}
            assert isinstance(columns["search_revision"]["type"], BigInteger)
            assert columns["search_revision"]["nullable"]
            assert columns["search_revision"]["default"] is None
            assert connection.execute(text("SELECT status,offer_token,search_revision FROM instantorder")).one() == (status, "a" * 32, None)
            connection.execute(text("INSERT INTO instantorder (id,status) VALUES (2, 'created')"))
            assert connection.execute(text("SELECT search_revision FROM instantorder WHERE id=2")).scalar_one() is None
            # Old writers remain structurally compatible but do NOT rotate revision.
            connection.execute(text("UPDATE instantorder SET search_revision=7 WHERE id=1"))
            connection.execute(text("UPDATE instantorder SET status='searching' WHERE id=1"))
            assert connection.execute(text("SELECT search_revision FROM instantorder WHERE id=1")).scalar_one() == 7
            migration.downgrade()
            assert "search_revision" not in {c["name"] for c in inspect(connection).get_columns("instantorder")}
            assert connection.execute(text("SELECT offer_token FROM instantorder WHERE id=1")).scalar_one() == "a" * 32
    finally:
        db.dispose()


def test_revision_ddl_is_bigint_and_nullable_on_postgresql_without_connecting():
    migration = _migration()
    output = StringIO()
    context = MigrationContext.configure(dialect_name="postgresql", opts={"as_sql": True, "output_buffer": output})
    migration.op = Operations(context)
    migration.upgrade()
    ddl = output.getvalue()
    assert "ADD COLUMN search_revision BIGINT" in ddl
    assert "NOT NULL" not in ddl
    assert "DEFAULT" not in ddl
    column = InstantOrder.__table__.c.search_revision
    assert isinstance(column.type, BigInteger)
    assert column.nullable and column.default is None and column.server_default is None


@pytest.mark.usefixtures("fake_redis")
def test_model_and_legacy_decline_work_without_revision_exposure_or_enforcement(client, user_factory):
    driver, _, payload = _offered_order(client, user_factory, isv._redis(), "RevisionBaseDriver", "RevisionBasePax")
    oid = payload["id"]
    assert "search_revision" not in payload
    with Session(engine) as session:
        order = session.get(InstantOrder, oid)
        assert order.search_revision is None
        order.search_revision = 2**40
        session.add(order)
        session.commit()
        session.refresh(order)
        assert order.search_revision == 2**40
    response = client.post(f"/instant/orders/{oid}/decline", headers=driver["auth"])
    assert response.status_code == 200
    assert "search_revision" not in response.json()
    with Session(engine) as session:
        assert session.get(InstantOrder, oid).search_revision == 2**40, "foundation does not pretend writers are integrated"
