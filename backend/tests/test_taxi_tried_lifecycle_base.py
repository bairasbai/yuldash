"""Additive lifecycle schema only: no generation, rotation or Redis guarantee."""
import importlib.util
from io import StringIO
from pathlib import Path

import pytest
from alembic.migration import MigrationContext
from alembic.operations import Operations
from sqlalchemy import String, create_engine, inspect, text
from sqlalchemy.dialects import postgresql, sqlite
from sqlmodel import Session, select

from app import instant_service as isv
from app.db import engine
from app.models import InstantOrder, OfferDecline
from test_instant import _offered_order
from test_taxi_offer_token import fake_redis  # noqa: F401


@pytest.fixture(autouse=True)
def _cleanup_lifecycle_orders(client):
    with Session(engine) as session:
        existing = set(session.exec(select(InstantOrder.id)).all())
    yield
    with Session(engine) as session:
        for order in session.exec(select(InstantOrder)).all():
            if order.id not in existing:
                for decline in session.exec(select(OfferDecline).where(OfferDecline.order_id == order.id)).all():
                    session.delete(decline)
                session.flush()
                session.delete(order)
        session.commit()


def _migration():
    path = Path(__file__).parents[1] / "alembic/versions/cm_instant_tried_lifecycle.py"
    spec = importlib.util.spec_from_file_location("tried_lifecycle_migration", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    assert module.revision == "cm_instant_tried_lifecycle"
    assert module.down_revision == "cl_instant_search_revision"
    return module


@pytest.mark.parametrize("status", ["created", "searching", "offered", "accepted", "cancelled"])
def test_lifecycle_upgrade_no_backfill_and_downgrade_preserves_other_fields(tmp_path, status):
    migration = _migration()
    db = create_engine(f"sqlite:///{tmp_path / 'lifecycle.db'}")
    token, lifecycle = "a" * 32, "b" * 32
    try:
        with db.begin() as connection:
            connection.execute(text("CREATE TABLE instantorder (id INTEGER PRIMARY KEY, status VARCHAR, offer_token VARCHAR(32), search_revision BIGINT)"))
            connection.execute(text("INSERT INTO instantorder VALUES (1,:status,:token,1099511627776)"), {"status": status, "token": token})
            migration.op = Operations(MigrationContext.configure(connection))
            migration.upgrade()
            columns = {c["name"]: c for c in inspect(connection).get_columns("instantorder")}
            column = columns["tried_lifecycle"]
            assert isinstance(column["type"], String) and column["type"].length == 32
            assert column["nullable"] and column["default"] is None
            row = connection.execute(text("SELECT status,offer_token,search_revision,tried_lifecycle FROM instantorder WHERE id=1")).one()
            assert row == (status, token, 2**40, None)
            connection.execute(text("UPDATE instantorder SET tried_lifecycle=:lifecycle WHERE id=1"), {"lifecycle": lifecycle})
            migration.upgrade()  # Idempotent existing-column no-op, not drift repair.
            assert connection.execute(text("SELECT tried_lifecycle FROM instantorder WHERE id=1")).scalar_one() == lifecycle
            connection.execute(text("INSERT INTO instantorder (id,status,offer_token,search_revision) VALUES (2,'created',:token,7)"), {"token": token})
            assert connection.execute(text("SELECT tried_lifecycle FROM instantorder WHERE id=2")).scalar_one() is None
            connection.execute(text("UPDATE instantorder SET status='searching',search_revision=search_revision+1 WHERE id=1"))
            assert connection.execute(text("SELECT tried_lifecycle FROM instantorder WHERE id=1")).scalar_one() == lifecycle
            before = connection.execute(text("SELECT id,status,offer_token,search_revision FROM instantorder ORDER BY id")).all()
            migration.downgrade()
            assert "tried_lifecycle" not in {c["name"] for c in inspect(connection).get_columns("instantorder")}
            assert connection.execute(text("SELECT id,status,offer_token,search_revision FROM instantorder ORDER BY id")).all() == before
    finally:
        db.dispose()


def test_lifecycle_offline_pg_ddl_and_model_are_nullable_string32_without_default():
    migration = _migration()
    output = StringIO()
    migration.op = Operations(MigrationContext.configure(dialect_name="postgresql", opts={"as_sql": True, "output_buffer": output}))
    migration.upgrade()
    ddl = output.getvalue()
    assert "ADD COLUMN tried_lifecycle VARCHAR(32)" in ddl
    assert "NOT NULL" not in ddl and "DEFAULT" not in ddl
    assert "UPDATE " not in ddl.upper() and "CREATE INDEX" not in ddl.upper()
    column = InstantOrder.__table__.c.tried_lifecycle
    # SQLModel wraps String in AutoString; inspect effective dialect storage,
    # not wrapper inheritance, without relaxing the required VARCHAR(32).
    for dialect in (postgresql.dialect(), sqlite.dialect()):
        effective = column.type.dialect_impl(dialect)
        storage = getattr(effective, "impl", effective)
        assert isinstance(storage, String) and storage.length == 32
        assert column.type.compile(dialect=dialect) == "VARCHAR(32)"
    assert column.nullable and column.default is None and column.server_default is None
    assert not column.index and not column.unique
    assert InstantOrder.model_fields["tried_lifecycle"].default is None
    assert InstantOrder(passenger_id=1).tried_lifecycle is None


@pytest.mark.parametrize("stored_lifecycle", [None, "c" * 32])
@pytest.mark.usefixtures("fake_redis")
def test_legacy_http_does_not_expose_initialize_or_rotate_lifecycle(client, user_factory, stored_lifecycle):
    driver, passenger, offered = _offered_order(client, user_factory, isv._redis(), "LifecycleBaseDriver", "LifecycleBasePassenger")
    oid = offered["id"]
    assert "tried_lifecycle" not in offered
    with Session(engine) as session:
        order = session.get(InstantOrder, oid)
        assert order.tried_lifecycle is None, "schema-only must not initialize during matching"
        order.tried_lifecycle = stored_lifecycle
        session.add(order)
        session.commit()
        session.refresh(order)
        assert order.tried_lifecycle == stored_lifecycle
    for path, headers in [(f"/instant/orders/{oid}", passenger["auth"]), ("/instant/driver/offer", driver["auth"])]:
        response = client.get(path, headers=headers)
        assert response.status_code == 200, response.text
        assert "tried_lifecycle" not in response.text
        if stored_lifecycle:
            assert stored_lifecycle not in response.text
    response = client.post(f"/instant/orders/{oid}/decline", headers=driver["auth"], json={"reason": "too_far"})
    assert response.status_code == 200, response.text
    assert "tried_lifecycle" not in response.text
    if stored_lifecycle:
        assert stored_lifecycle not in response.text
    with Session(engine) as session:
        assert session.get(InstantOrder, oid).tried_lifecycle == stored_lifecycle
