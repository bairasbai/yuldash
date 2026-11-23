"""Additive storage, no defaults/backfills, populated-marker downgrade refusal."""
import pytest
from alembic import command
from sqlalchemy import inspect, text
from sqlmodel import Session, create_engine

from app.models import InstantOrder, Message, User
from test_migrations_match_the_models import _config

BASE = "dc_merge_taxi_consent_receipts"
HEAD = "dd_taxi_route_resume"
COLUMNS = {name for name in (
    "route_resume_id", "route_resume_previous_driver_id", "route_resume_retired_at",
    "route_resume_deadline_at", "route_resume_ready_at", "route_resume_claim_id",
)}


def test_new_schema_downgrade_guard_and_referenced_row_roundtrip(tmp_path, monkeypatch):
    url = f"sqlite:///{(tmp_path / 'taxi-route-resume.sqlite3').as_posix()}"
    monkeypatch.setenv("DATABASE_URL", url)
    engine = create_engine(url)
    cfg = _config(url)
    try:
        command.upgrade(cfg, HEAD)  # actual baseline create_all may precreate schema
        current = inspect(engine)
        cols = {c["name"]: c for c in current.get_columns("instantorder")}
        assert all(cols[name]["nullable"] and cols[name]["default"] is None for name in COLUMNS)
        indexes = {i["name"]: i for i in current.get_indexes("instantorder")}
        for key in ("ready", "deadline"):
            spec = indexes[f"ix_instantorder_route_resume_{key}"]
            assert spec["column_names"] == [f"route_resume_{key}_at", "id"]
            assert "route_resume_id IS NOT NULL" in str(spec["dialect_options"]["sqlite_where"])

        with Session(engine) as s:
            user = User(phone="+79990005634")
            s.add(user)
            s.flush()
            order = InstantOrder(passenger_id=user.id)
            s.add(order)
            s.flush()
            message = Message(sender_id=user.id, order_id=order.id, text="synthetic route resume chat")
            s.add(message)
            s.flush()
            order_id, msg_id = order.id, message.id
            assert order.route_resume_id is None
            order.route_resume_id = "a" * 32  # even partial persisted provenance blocks removal
            s.commit()
        with pytest.raises(RuntimeError, match="Drain taxi route resume markers"):
            command.downgrade(cfg, BASE)
        with engine.begin() as conn:
            assert conn.scalar(text("SELECT version_num FROM alembic_version")) == HEAD
            assert conn.scalar(text("SELECT route_resume_id FROM instantorder")) == "a" * 32
            conn.execute(text("UPDATE instantorder SET route_resume_id=NULL"))
        command.downgrade(cfg, BASE)
        assert not COLUMNS.intersection(c["name"] for c in inspect(engine).get_columns("instantorder"))
        assert not any("route_resume" in i["name"] for i in inspect(engine).get_indexes("instantorder"))
        with engine.connect() as conn:
            assert conn.scalar(text("SELECT version_num FROM alembic_version")) == BASE
            assert conn.scalar(text("SELECT order_id FROM message WHERE id=:id"), {"id": msg_id}) == order_id
            assert conn.exec_driver_sql("PRAGMA foreign_key_check").fetchall() == []
        command.upgrade(cfg, HEAD)  # real additive path against the parent shape
        with engine.begin() as conn:
            # An old writer references only its known columns; schema-only
            # must not opt it into recovery or require it to supply new data.
            conn.execute(text("UPDATE instantorder SET to_text=:text WHERE id=:id"),
                         {"id": order_id, "text": "synthetic legacy update"})
        with Session(engine) as s:
            persisted = s.get(InstantOrder, order_id)
            assert all(getattr(persisted, name) is None for name in COLUMNS)
            assert persisted.to_text == "synthetic legacy update"
            assert s.get(Message, msg_id).order_id == order_id
    finally:
        engine.dispose()


@pytest.mark.parametrize("partial", ("absent", "column_only", "index_only", "missing_index"))
def test_sqlite_restarts_ddl_already_gone_only_if_whole_package_absent(tmp_path, monkeypatch, partial):
    url = f"sqlite:///{(tmp_path / 'resume-restart.sqlite3').as_posix()}"
    monkeypatch.setenv("DATABASE_URL", url)
    engine = create_engine(url)
    cfg = _config(url)
    try:
        command.upgrade(cfg, HEAD)
        if partial == "missing_index":
            with engine.begin() as conn:
                conn.exec_driver_sql("DROP INDEX ix_instantorder_route_resume_ready")
        else:
            command.downgrade(cfg, BASE)
            with engine.begin() as conn:
                if partial == "column_only":
                    conn.exec_driver_sql("ALTER TABLE instantorder ADD COLUMN route_resume_retired_at DATETIME")
                elif partial == "index_only":
                    conn.exec_driver_sql("CREATE INDEX ix_instantorder_route_resume_ready ON instantorder (id)")
                # Model the interruption after physical DDL but before the
                # downgrade's independent version stamp is persisted.
                conn.execute(text("UPDATE alembic_version SET version_num=:head"), {"head": HEAD})
        current = inspect(engine)
        cols = {c["name"] for c in current.get_columns("instantorder")}
        indexes = {i["name"] for i in current.get_indexes("instantorder")}
        if partial == "absent":
            command.downgrade(cfg, BASE)
        else:
            with pytest.raises(RuntimeError, match="Unexpected partial taxi route resume schema"):
                command.downgrade(cfg, BASE)
        with engine.connect() as conn:
            assert conn.scalar(text("SELECT version_num FROM alembic_version")) == (BASE if partial == "absent" else HEAD)
        after = inspect(engine)
        assert {c["name"] for c in after.get_columns("instantorder")} == cols
        assert {i["name"] for i in after.get_indexes("instantorder")} == indexes
    finally:
        engine.dispose()
