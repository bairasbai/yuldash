"""Check owner settings on fresh, previously migrated and partially seeded schemas."""

import os

from alembic import command
from sqlalchemy import inspect, text
from sqlmodel import Session, create_engine, select

from app.db import engine
from app.models import AppSetting, AppSettingChange, User, UserRole
from app.timeutil import utcnow
from test_migrations_match_the_models import _config


PREVIOUS = "cj_request_traveler_origin"
CURRENT = "ck_owner_switches"
LOGIN_KEYS = {"login.sms", "login.telegram", "login.vk", "login.whatsapp"}


def test_owner_switch_migration_preserves_existing_values_and_old_users(tmp_path, monkeypatch):
    database = os.environ.get("YULDASH_OWNER_SWITCH_MIGRATION_DB")
    if database:
        assert engine.dialect.name == "postgresql"
        assert engine.url.host in {"127.0.0.1", "localhost", "::1"} and not engine.url.query
        assert engine.url.database != database
        url = engine.url.set(database=database).render_as_string(hide_password=False)
    else:
        url = f"sqlite:///{(tmp_path / 'owner-switches.sqlite3').as_posix()}"
    monkeypatch.setenv("DATABASE_URL", url)
    config = _config(url)
    db = create_engine(url, hide_parameters=True)

    try:
        assert not inspect(db).get_table_names()
        command.upgrade(config, CURRENT)
        with Session(db) as session:
            states = session.exec(select(AppSetting)).all()
            assert {state.key for state in states} == LOGIN_KEYS
            assert all(state.value is None and state.updated_at is None and state.updated_by is None for state in states)
            admin = User(phone="+79990001042", role=UserRole.admin)
            session.add(admin)
            session.flush()
            admin_id = admin.id
            when = utcnow()
            state = session.get(AppSetting, "login.sms")
            state.value, state.updated_at, state.updated_by = False, when, admin_id
            session.add(AppSettingChange(
                key=state.key, old_value=None, new_value=False, created_at=when, admin_id=admin_id,
            ))
            session.commit()

        with db.begin() as connection:
            connection.execute(text("DROP INDEX ix_appsettingchange_history"))
        command.stamp(config, PREVIOUS)
        command.upgrade(config, CURRENT)
        inspector = inspect(db)
        assert any(
            index["name"] == "ix_appsettingchange_history" and index["column_names"] == ["key", "created_at", "id"]
            for index in inspector.get_indexes("appsettingchange")
        )
        for table, column in (("appsetting", "updated_by"), ("appsettingchange", "admin_id")):
            assert any(
                fk["constrained_columns"] == [column] and fk["referred_table"] == "user"
                and fk["options"].get("ondelete") == "SET NULL"
                for fk in inspector.get_foreign_keys(table)
            )
        with Session(db) as session:
            state = session.get(AppSetting, "login.sms")
            history = session.exec(select(AppSettingChange)).one()
            assert (state.value, state.updated_by, state.updated_at) == (False, admin_id, when)
            assert (history.key, history.old_value, history.new_value, history.admin_id, history.created_at) == (
                state.key, None, False, admin_id, when,
            )

        command.downgrade(config, PREVIOUS)
        assert not inspect(db).has_table("appsetting")
        assert not inspect(db).has_table("appsettingchange")
        command.upgrade(config, CURRENT)
        with Session(db) as session:
            assert session.get(User, admin_id).role == UserRole.admin
            states = session.exec(select(AppSetting)).all()
            assert {state.key for state in states} == LOGIN_KEYS
            assert all(state.value is None for state in states)
            assert not session.exec(select(AppSettingChange)).all()
    finally:
        db.dispose()
