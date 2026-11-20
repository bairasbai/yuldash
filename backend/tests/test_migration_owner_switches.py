"""Check owner settings on fresh, previously migrated and partially seeded schemas."""

import os

from alembic import command
from sqlalchemy import inspect, text
from sqlmodel import Session, create_engine, select

from app.db import engine
from app.models import AppSetting, AppSettingChange, AuthSession, RefreshToken, User, UserRole
from app.timeutil import utcnow
from test_migrations_match_the_models import _config


PREVIOUS = "cj_request_traveler_origin"
CURRENT = "ck_owner_switches"
LOGIN_KEYS = {"login.sms", "login.telegram", "login.vk", "login.whatsapp"}


def _isolated_database(tmp_path, monkeypatch, pg_env, sqlite_file):
    database = os.environ.get(pg_env)
    if database:
        assert engine.dialect.name == "postgresql"
        assert engine.url.host in {"127.0.0.1", "localhost", "::1"} and not engine.url.query
        assert engine.url.database != database
        url = engine.url.set(database=database).render_as_string(hide_password=False)
    else:
        url = f"sqlite:///{(tmp_path / sqlite_file).as_posix()}"
    monkeypatch.setenv("DATABASE_URL", url)
    return create_engine(url, hide_parameters=True), _config(url)


def test_owner_switch_migration_preserves_existing_values_and_old_users(tmp_path, monkeypatch):
    db, config = _isolated_database(
        tmp_path, monkeypatch, "YULDASH_OWNER_SWITCH_MIGRATION_DB", "owner-switches.sqlite3",
    )
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

        indexes = (
            ("appsetting", "ix_appsetting_updated_by", ["updated_by"]),
            ("appsettingchange", "ix_appsettingchange_admin_id", ["admin_id"]),
            ("appsettingchange", "ix_appsettingchange_history", ["key", "created_at", "id"]),
        )
        with db.begin() as connection:
            for _, name, _ in indexes:
                connection.execute(text(f"DROP INDEX {name}"))
        command.stamp(config, PREVIOUS)
        command.upgrade(config, CURRENT)
        inspector = inspect(db)
        for table, name, columns in indexes:
            assert any(
                index["name"] == name and index["column_names"] == columns
                for index in inspector.get_indexes(table)
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


def test_refresh_proof_migration_never_infers_provenance_for_an_old_session(tmp_path, monkeypatch):
    db, config = _isolated_database(
        tmp_path, monkeypatch, "YULDASH_AUTH_PROOF_MIGRATION_DB", "refresh-auth-proof.sqlite3",
    )
    target = "cl_refresh_auth_proof"
    try:
        assert not inspect(db).get_table_names()
        command.upgrade(config, target)
        command.downgrade(config, CURRENT)
        columns = {column["name"] for column in inspect(db).get_columns("refreshtoken")}
        assert {"auth_method", "auth_time"}.isdisjoint(columns)
        login_id = "qa-legacy-login"
        with Session(db) as session:
            account = User(phone="+79990001043", role=UserRole.admin)
            session.add(account)
            session.flush()
            owner_id = account.id
            session.add(AuthSession(id=login_id, user_id=owner_id))
            session.flush()
            session.execute(text(
                "INSERT INTO refreshtoken (user_id, token_hash, auth_session_id, revoked, expires_at, created_at) "
                "VALUES (:owner, 'fixture-old-refresh-hash', :sid, false, :now, :now)"
            ), {"owner": owner_id, "sid": login_id, "now": utcnow()})
            session.commit()

        for run in range(2):
            command.upgrade(config, target)
            with Session(db) as session:
                token = session.exec(select(RefreshToken)).one()
                assert (token.user_id, token.auth_session_id, token.auth_method, token.auth_time, token.revoked) == (
                    owner_id, login_id, "legacy", None, False,
                )
            if run == 0:
                command.downgrade(config, CURRENT)
                columns = {column["name"] for column in inspect(db).get_columns("refreshtoken")}
                assert {"auth_method", "auth_time"}.isdisjoint(columns)
    finally:
        db.dispose()

def test_auth_epoch_migration_preserves_old_accounts_and_refresh_proof(tmp_path, monkeypatch):
    db, config = _isolated_database(
        tmp_path, monkeypatch, "YULDASH_AUTH_EPOCH_MIGRATION_DB", "auth-epoch.sqlite3",
    )
    earlier, target = "cl_refresh_auth_proof", "cm_auth_epoch"
    try:
        assert not inspect(db).get_table_names()
        command.upgrade(config, target)
        login_id = "qa-epoch-login"
        auth_time = utcnow()
        with Session(db) as session:
            user = User(phone="+79990001044", role=UserRole.admin)
            session.add(user)
            session.flush()
            user_id = user.id
            session.add(AuthSession(id=login_id, user_id=user_id))
            session.flush()
            session.add(RefreshToken(
                user_id=user_id, auth_session_id=login_id, token_hash="fixture-epoch-hash",
                auth_method="telegram", auth_time=auth_time, expires_at=auth_time,
            ))
            session.commit()

        for _ in range(2):
            command.downgrade(config, earlier)
            for table in ("user", "refreshtoken"):
                columns = {column["name"] for column in inspect(db).get_columns(table)}
                assert "auth_epoch" not in columns
            command.upgrade(config, target)
            with Session(db) as session:
                user = session.get(User, user_id)
                token = session.exec(select(RefreshToken)).one()
                assert user.role == UserRole.admin and user.auth_epoch == 0
                assert (token.user_id, token.auth_session_id, token.auth_method, token.auth_time, token.auth_epoch) == (
                    user_id, login_id, "telegram", auth_time, 0,
                )
    finally:
        db.dispose()
