"""Actually migrate schema in a temporary SQLite file, never application DB."""
from uuid import uuid4

import pytest
from sqlalchemy import delete, event, inspect, select, text
from sqlalchemy.engine import Engine
from sqlmodel import Session, create_engine
from test_migrations_match_the_models import _config

from alembic import command
from app.models import InstantOrder, Message, TaxiDriverAcceptOutcome, User
from app.timeutil import utcnow

PARENT = "df_taxi_route_resume_seal"
HEAD = "dg_taxi_driver_accept_outcome"
TABLE = TaxiDriverAcceptOutcome.__table__


def test_downgrade_requires_drained_child_and_parent_remains(tmp_path, monkeypatch):
    url = f"sqlite:///{(tmp_path / 'driver-accept-outcome.sqlite3').as_posix()}"
    monkeypatch.setenv("DATABASE_URL", url)
    engine = create_engine(url, hide_parameters=True)
    cfg = _config(url)
    try:
        command.upgrade(cfg, HEAD)
        key = str(uuid4())
        with Session(engine) as s:
            owner = User(phone="+79990003094")
            s.add(owner)
            s.flush()
            order = InstantOrder(passenger_id=owner.id)
            s.add(order)
            s.flush()
            oid = order.id
            child = Message(sender_id=owner.id, order_id=oid, text="synthetic")
            s.add_all((child, TaxiDriverAcceptOutcome(driver_id=owner.id, order_id=oid,
                                                  attempt_id=key, binding=b"\x03" * 32, expires_at=utcnow())))
            s.commit()
        with pytest.raises(RuntimeError, match="Drain taxi driver acceptance outcome"):
            command.downgrade(cfg, PARENT)
        with engine.begin() as conn:
            assert conn.scalar(text("SELECT version_num FROM alembic_version")) == HEAD
            assert conn.scalar(select(TABLE.c.attempt_id)) == key
            conn.execute(delete(TABLE))
        command.downgrade(cfg, PARENT)
        assert not inspect(engine).has_table(TABLE.name)
        with engine.begin() as conn:
            # Model an interrupted stamp after the SQLite child DDL committed.
            conn.execute(text("UPDATE alembic_version SET version_num=:head"), {"head": HEAD})
        command.downgrade(cfg, PARENT)
        assert not inspect(engine).has_table(TABLE.name)
        assert inspect(engine).has_table("instantorder")
        with engine.connect() as conn:
            assert conn.scalar(text("SELECT version_num FROM alembic_version")) == PARENT
            assert conn.scalar(text("SELECT order_id FROM message")) == oid
            assert conn.exec_driver_sql("PRAGMA foreign_key_check").fetchall() == []
        command.upgrade(cfg, HEAD)
        with engine.connect() as conn:
            assert conn.scalar(select(TABLE.c.attempt_id)) is None
            assert conn.scalar(text("SELECT order_id FROM message")) == oid
    finally:
        engine.dispose()


def test_sqlite_index_ddl_failure_rolls_back_then_clean_retry(tmp_path, monkeypatch):
    url = f"sqlite:///{(tmp_path / 'driver-accept-partial-upgrade.sqlite3').as_posix()}"
    monkeypatch.setenv("DATABASE_URL", url)
    engine = create_engine(url, hide_parameters=True)
    cfg = _config(url)

    class InjectedDDLFailure(Exception):
        pass

    executed_indexes = []

    def witness(conn, cursor, statement, parameters, context, executemany):
        if statement.startswith(f"CREATE INDEX ix_{TABLE.name}_order_id "):
            executed_indexes.append("first-created")
    def fail_second(conn, cursor, statement, parameters, context, executemany):
        if statement.startswith(f"CREATE INDEX ix_{TABLE.name}_expires_at "):
            assert executed_indexes == ["first-created"]
            raise InjectedDDLFailure("interrupt before second index DDL")

    try:
        command.upgrade(cfg, PARENT)
        # New metadata baseline pre-creates this child. Reconstruct an old-style
        # database at the exact parent without touching parent schema/stamp.
        with engine.begin() as conn:
            TABLE.drop(conn)
        event.listen(Engine, "after_cursor_execute", witness)
        event.listen(Engine, "before_cursor_execute", fail_second)
        try:
            with pytest.raises(InjectedDDLFailure, match="second index DDL"):
                command.upgrade(cfg, HEAD)
        finally:
            event.remove(Engine, "before_cursor_execute", fail_second)
            event.remove(Engine, "after_cursor_execute", witness)
        assert executed_indexes == ["first-created"]
        with engine.connect() as conn:
            assert conn.scalar(text("SELECT version_num FROM alembic_version")) == PARENT
            assert not inspect(conn).has_table(TABLE.name)
            assert conn.scalar(text("SELECT COUNT(*) FROM sqlite_schema WHERE tbl_name=:table"),
                               {"table": TABLE.name}) == 0
        command.upgrade(cfg, HEAD)
        with engine.connect() as conn:
            assert conn.scalar(text("SELECT version_num FROM alembic_version")) == HEAD
            assert {i["name"] for i in inspect(conn).get_indexes(TABLE.name)} == {
                f"ix_{TABLE.name}_order_id", f"ix_{TABLE.name}_expires_at",
            }
    finally:
        engine.dispose()


@pytest.mark.parametrize("variant", ("complete", "missing_index", "partial_index"))
def test_sqlite_upgrade_retries_complete_unstamped_schema_but_leaves_partial_unchanged(
    tmp_path, monkeypatch, variant,
):
    url = f"sqlite:///{(tmp_path / 'prior-upgrade.sqlite3').as_posix()}"
    monkeypatch.setenv("DATABASE_URL", url)
    engine = create_engine(url, hide_parameters=True)
    cfg = _config(url)
    try:
        command.upgrade(cfg, PARENT)
        malformed = variant != "complete"
        if malformed:
            with engine.begin() as conn:
                conn.exec_driver_sql(f"DROP INDEX ix_{TABLE.name}_expires_at")
                if variant == "partial_index":
                    conn.exec_driver_sql(
                        f"CREATE INDEX ix_{TABLE.name}_expires_at ON {TABLE.name} (expires_at) WHERE driver_id=1"
                    )
                    indexes = {row["name"]: row for row in inspect(conn).get_indexes(TABLE.name)}
                    matching_name = indexes[f"ix_{TABLE.name}_expires_at"]
                    assert matching_name["column_names"] == ["expires_at"] and not matching_name["unique"]
                    assert matching_name["dialect_options"]["sqlite_where"] is not None
        query = text("SELECT type, name, sql FROM sqlite_schema WHERE tbl_name=:table ORDER BY name")
        with engine.connect() as conn:
            before = conn.execute(query, {"table": TABLE.name}).all()
        if malformed:
            with pytest.raises(RuntimeError, match="Unexpected taxi driver acceptance outcome schema"):
                command.upgrade(cfg, HEAD)
        else:
            command.upgrade(cfg, HEAD)
        with engine.connect() as conn:
            assert conn.scalar(text("SELECT version_num FROM alembic_version")) == (PARENT if malformed else HEAD)
            assert conn.execute(query, {"table": TABLE.name}).all() == before
    finally:
        engine.dispose()


def test_sqlite_upgrade_rejects_missing_driver_cascade_in_existing_table(tmp_path, monkeypatch):
    url = f"sqlite:///{(tmp_path / 'driver-accept-missing-cascade.sqlite3').as_posix()}"
    monkeypatch.setenv("DATABASE_URL", url)
    engine = create_engine(url, hide_parameters=True)
    cfg = _config(url)
    try:
        command.upgrade(cfg, PARENT)
        with engine.begin() as conn:
            TABLE.drop(conn)
            conn.exec_driver_sql(
                "CREATE TABLE taxidriveracceptoutcome ("
                'driver_id INTEGER NOT NULL REFERENCES "user" (id), '
                "order_id INTEGER NOT NULL REFERENCES instantorder (id) ON DELETE CASCADE, "
                "attempt_id VARCHAR(36) NOT NULL, binding BLOB NOT NULL, expires_at DATETIME NOT NULL, "
                "PRIMARY KEY (driver_id, order_id, attempt_id), "
                "CONSTRAINT ck_taxi_driver_accept_outcome_binding CHECK (length(binding) = 32))",
            )
            for field in ("order_id", "expires_at"):
                conn.exec_driver_sql(f"CREATE INDEX ix_{TABLE.name}_{field} ON {TABLE.name} ({field})")
        query = text("SELECT sql FROM sqlite_schema WHERE tbl_name=:table ORDER BY name")
        with engine.connect() as conn:
            before = conn.execute(query, {"table": TABLE.name}).all()
        with pytest.raises(RuntimeError, match="Unexpected taxi driver acceptance outcome schema"):
            command.upgrade(cfg, HEAD)
        with engine.connect() as conn:
            assert conn.scalar(text("SELECT version_num FROM alembic_version")) == PARENT
            assert conn.execute(query, {"table": TABLE.name}).all() == before
    finally:
        engine.dispose()


def test_historical_sqlite_dev_boot_creates_exact_inactive_table(tmp_path, monkeypatch):
    from app import db

    url = f"sqlite:///{(tmp_path / 'driver-accept-dev-boot.sqlite3').as_posix()}"
    monkeypatch.setenv("DATABASE_URL", url)
    engine = create_engine(url, hide_parameters=True)
    cfg = _config(url)
    try:
        command.upgrade(cfg, PARENT)
        with engine.begin() as conn:
            TABLE.drop(conn)  # real old dev schema lacks the new table
        assert not inspect(engine).has_table(TABLE.name)
        monkeypatch.setattr(db, "engine", engine)
        monkeypatch.setattr(db.settings, "database_url", url)
        db.init_db()
        assert {
            (f["constrained_columns"][0], f["referred_table"], f["options"].get("ondelete"))
            for f in inspect(engine).get_foreign_keys(TABLE.name)
        } == {("driver_id", "user", "CASCADE"), ("order_id", "instantorder", "CASCADE")}
        command.upgrade(cfg, HEAD)  # exact dev-created shape is valid to stamp
        with engine.connect() as conn:
            assert conn.scalar(text("SELECT version_num FROM alembic_version")) == HEAD
            assert conn.scalar(select(TABLE.c.attempt_id)) is None
            assert conn.exec_driver_sql("PRAGMA foreign_key_check").fetchall() == []
    finally:
        engine.dispose()
