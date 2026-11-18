"""Inactive SQL history foundation; no runtime exclusion or delivery guarantee."""
import importlib.util
from datetime import datetime
from io import StringIO
from pathlib import Path

import pytest
from alembic.migration import MigrationContext
from alembic.operations import Operations
from sqlalchemy import DateTime, Integer, String, create_engine, inspect, text
from sqlalchemy.dialects import postgresql, sqlite
from sqlalchemy.exc import IntegrityError
from sqlmodel import Session, select

from app import instant_service as isv, models
from app.db import engine
from app.models import InstantOrder
from test_instant import _offered_order
from test_taxi_offer_token import fake_redis  # noqa: F401
from test_taxi_tried_lifecycle_base import _cleanup_lifecycle_orders  # noqa: F401


def _migration():
    path = Path(__file__).parents[1] / "alembic/versions/cn_instant_tried_history.py"
    spec = importlib.util.spec_from_file_location("history_schema_migration", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    assert module.revision == "cn_instant_tried_history"
    assert module.down_revision == "cm_instant_tried_lifecycle"
    return module


def _schema_contract(connection, status):
    """Real migration on minimal old schema; caller owns disposable transaction."""
    connection.execute(text('CREATE TABLE "user" (id INTEGER PRIMARY KEY)'))
    connection.execute(text("CREATE TABLE instantorder (id INTEGER PRIMARY KEY, status VARCHAR(32), offer_token VARCHAR(32), search_revision BIGINT, tried_lifecycle VARCHAR(32))"))
    connection.execute(text("INSERT INTO instantorder VALUES (1,:status,:token,1099511627776,:cycle)"),
                       {"status": status, "token": "a" * 32, "cycle": "b" * 32})
    migration = _migration()
    migration.op = Operations(MigrationContext.configure(connection))
    migration.upgrade()
    columns = {c["name"]: c for c in inspect(connection).get_columns("instantorder")}
    for name in ("tried_history_version", "tried_history_expires_at"):
        assert columns[name]["nullable"] and columns[name]["default"] is None
    assert str(columns["tried_history_version"]["type"]) == "INTEGER"
    assert isinstance(columns["tried_history_expires_at"]["type"], DateTime)
    assert columns["tried_history_expires_at"]["type"].timezone is False
    child_columns = inspect(connection).get_columns("instanttriedexclusion")
    child_types = {c["name"]: c["type"] for c in child_columns}
    assert isinstance(child_types["lifecycle"], String) and child_types["lifecycle"].length == 32
    assert isinstance(child_types["order_id"], Integer)
    assert isinstance(child_types["driver_id"], Integer)
    assert {c["name"] for c in child_columns} == {"order_id", "lifecycle", "driver_id"}
    assert all(not c["nullable"] and c["default"] is None for c in child_columns)
    assert inspect(connection).get_pk_constraint("instanttriedexclusion")["constrained_columns"] == ["order_id", "lifecycle", "driver_id"]
    fks = inspect(connection).get_foreign_keys("instanttriedexclusion")
    assert len(fks) == 1 and fks[0]["constrained_columns"] == ["order_id"]
    assert fks[0]["referred_table"] == "instantorder" and fks[0]["options"]["ondelete"] == "CASCADE"
    assert not inspect(connection).get_indexes("instanttriedexclusion")
    assert connection.execute(text("SELECT tried_history_version,tried_history_expires_at FROM instantorder")).one() == (None, None)
    connection.execute(text("UPDATE instantorder SET tried_history_version=71,tried_history_expires_at='2030-01-02 03:04:05'"))
    migration.upgrade()  # Bootstrap skip only, not repair of malformed existing schema.
    assert connection.execute(text("SELECT tried_history_version FROM instantorder")).scalar_one() == 71
    connection.execute(text("INSERT INTO instantorder(id,status) VALUES(2,'created')"))
    assert connection.execute(text("SELECT tried_history_version,tried_history_expires_at FROM instantorder WHERE id=2")).one() == (None, None)
    connection.execute(text('INSERT INTO "user"(id) VALUES(9)'))
    insert = text("INSERT INTO instanttriedexclusion(order_id,lifecycle,driver_id) VALUES(:oid,:cycle,9)")
    for cycle in ("b" * 32, "c" * 32):
        connection.execute(insert, {"oid": 1, "cycle": cycle})
    for oid, cycle in ((1, "b" * 32), (999, "b" * 32), (1, None)):
        with pytest.raises(IntegrityError):
            with connection.begin_nested():
                connection.execute(insert, {"oid": oid, "cycle": cycle})
    with connection.begin_nested() as savepoint:
        connection.execute(insert, {"oid": 2, "cycle": "d" * 32})
        savepoint.rollback()
    assert connection.execute(text("SELECT count(*) FROM instanttriedexclusion")).scalar_one() == 2
    connection.execute(text('DELETE FROM "user" WHERE id=9'))
    assert connection.execute(text("SELECT count(*) FROM instanttriedexclusion")).scalar_one() == 2
    with connection.begin_nested() as savepoint:
        connection.execute(text("DELETE FROM instantorder WHERE id=1"))
        assert connection.execute(text("SELECT count(*) FROM instanttriedexclusion")).scalar_one() == 0
        savepoint.rollback()
    assert connection.execute(text("SELECT count(*) FROM instanttriedexclusion")).scalar_one() == 2
    before = connection.execute(text("SELECT id,status,offer_token,search_revision,tried_lifecycle FROM instantorder ORDER BY id")).all()
    # Preserve nonempty child through downgrade: it must drop child before columns.
    migration.downgrade()
    assert not inspect(connection).has_table("instanttriedexclusion")
    assert not {"tried_history_version", "tried_history_expires_at"}.intersection(c["name"] for c in inspect(connection).get_columns("instantorder"))
    assert connection.execute(text("SELECT id,status,offer_token,search_revision,tried_lifecycle FROM instantorder ORDER BY id")).all() == before
    migration.upgrade()
    for cycle in ("b" * 32, "c" * 32):
        connection.execute(insert, {"oid": 1, "cycle": cycle})
    connection.execute(text("DELETE FROM instantorder WHERE id=1"))
    assert connection.execute(text("SELECT count(*) FROM instanttriedexclusion")).scalar_one() == 0


@pytest.mark.parametrize("status", ["created", "searching", "offered", "accepted", "done", "cancelled"])
def test_sqlite_history_upgrade_constraints_rollback_and_downgrade(tmp_path, status):
    db = create_engine(f"sqlite:///{tmp_path / 'history.db'}")
    try:
        with db.connect() as connection:
            connection.execute(text("PRAGMA foreign_keys=ON"))
            connection.commit()
            with connection.begin():
                _schema_contract(connection, status)
    finally:
        db.dispose()


def test_history_metadata_and_offline_pg_ddl():
    table = models.InstantTriedExclusion.__table__
    assert set(table.c.keys()) == {"order_id", "lifecycle", "driver_id"}
    assert [c.name for c in table.primary_key.columns] == ["order_id", "lifecycle", "driver_id"]
    assert all(not c.nullable and c.default is None and c.server_default is None for c in table.c)
    assert not table.indexes
    assert len(table.foreign_keys) == 1
    fk = next(iter(table.foreign_keys))
    assert fk.parent.name == "order_id" and fk.target_fullname == "instantorder.id" and fk.ondelete == "CASCADE"
    for dialect in (postgresql.dialect(), sqlite.dialect()):
        assert table.c.lifecycle.type.compile(dialect=dialect) == "VARCHAR(32)"
        assert InstantOrder.__table__.c.tried_history_version.type.compile(dialect=dialect) == "INTEGER"
    for name in ("tried_history_version", "tried_history_expires_at"):
        col = InstantOrder.__table__.c[name]
        assert col.nullable and col.default is None and col.server_default is None and not col.index
        assert getattr(InstantOrder(passenger_id=1), name) is None
    expiry_type = InstantOrder.__table__.c.tried_history_expires_at.type
    assert not expiry_type.timezone
    assert expiry_type.compile(dialect=postgresql.dialect()) == "TIMESTAMP WITHOUT TIME ZONE"
    assert expiry_type.compile(dialect=sqlite.dialect()) == "DATETIME"
    migration, output = _migration(), StringIO()
    migration.op = Operations(MigrationContext.configure(dialect_name="postgresql", opts={"as_sql": True, "output_buffer": output}))
    migration.upgrade()
    ddl = output.getvalue()
    assert "ADD COLUMN tried_history_version INTEGER" in ddl
    assert "ADD COLUMN tried_history_expires_at TIMESTAMP WITHOUT TIME ZONE" in ddl
    assert "PRIMARY KEY (order_id, lifecycle, driver_id)" in ddl
    assert "REFERENCES instantorder (id) ON DELETE CASCADE" in ddl
    assert "DEFAULT" not in ddl and "UPDATE " not in ddl and "CREATE INDEX" not in ddl


@pytest.mark.parametrize("version,expiry", [(None, None), (71, datetime(2030, 1, 2, 3, 4, 5))])
@pytest.mark.usefixtures("fake_redis")
def test_http_history_is_unexposed_and_inactive(client, user_factory, version, expiry):
    driver, passenger, offered = _offered_order(client, user_factory, isv._redis(), "HistorySchemaDriver", "HistorySchemaPassenger")
    oid = offered["id"]
    with Session(engine) as session:
        order = session.get(InstantOrder, oid)
        assert order.tried_history_version is None and order.tried_history_expires_at is None
        order.tried_history_version, order.tried_history_expires_at = version, expiry
        session.add(order)
        session.commit()
    responses = [client.get(f"/instant/orders/{oid}", headers=passenger["auth"]),
                 client.get("/instant/driver/offer", headers=driver["auth"]),
                 client.post(f"/instant/orders/{oid}/decline", headers=driver["auth"], json={"reason": "too_far"})]
    for response in responses:
        assert response.status_code == 200, response.text
        assert "tried_history_version" not in response.text and "tried_history_expires_at" not in response.text
    assert "tried_history_version" not in offered and "tried_history_expires_at" not in offered
    with Session(engine) as session:
        order = session.get(InstantOrder, oid)
        assert (order.tried_history_version, order.tried_history_expires_at) == (version, expiry)
        assert not session.exec(select(models.InstantTriedExclusion).where(models.InstantTriedExclusion.order_id == oid)).all()
