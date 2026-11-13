"""Upgrade and downgrade the session schema over real legacy rows."""

import os

from alembic import command
from sqlalchemy import inspect, text
from sqlmodel import Session, create_engine

from app.db import engine
from app.models import User
from app.timeutil import utcnow
from test_migrations_match_the_models import _config


PREVIOUS = "cg_command_receipt_20261111"
CURRENT = "ch_auth_session_20261112"


def test_auth_session_migration_preserves_legacy_refresh_and_web_push(tmp_path, monkeypatch):
    pg_database = os.environ.get("YULDASH_AUTH_SESSION_MIGRATION_DB")
    if pg_database:
        # Use a separate empty database on the same local test cluster.
        assert engine.dialect.name == "postgresql"
        assert engine.url.host in {"127.0.0.1", "localhost", "::1"} and not engine.url.query
        assert engine.url.database != pg_database
        url = engine.url.set(database=pg_database).render_as_string(hide_password=False)
    else:
        url = f"sqlite:///{(tmp_path / 'auth-session-migration.sqlite3').as_posix()}"
    monkeypatch.setenv("DATABASE_URL", url)
    db = create_engine(url, hide_parameters=True)
    config = _config(url)
    try:
        assert not inspect(db).get_table_names()
        command.upgrade(config, CURRENT)
        command.downgrade(config, PREVIOUS)
        assert not inspect(db).has_table("authsession")
        with Session(db) as session:
            user = User(phone="+79990001041")
            session.add(user)
            session.flush()
            owner = user.id
            session.commit()
        now = utcnow()
        with db.begin() as conn:
            conn.execute(text(
                "INSERT INTO refreshtoken (user_id, token_hash, revoked, expires_at, created_at) "
                "VALUES (:owner, 'fixture-legacy-hash', false, :now, :now)"
            ), {"owner": owner, "now": now})
            conn.execute(text(
                "INSERT INTO webpushsubscription (user_id, endpoint, p256dh, auth, content_encoding, device_id, created_at) "
                "VALUES (:owner, 'https://push.invalid/legacy', 'fixture-p256dh', 'fixture-auth', 'aes128gcm', '', :now)"
            ), {"owner": owner, "now": now})
        for pass_number in range(2):
            command.upgrade(config, CURRENT)
            inspector = inspect(db)
            assert inspector.has_table("authsession")
            expires = next(column for column in inspector.get_columns("authsession") if column["name"] == "access_expires_at")
            assert expires["nullable"] is False
            for table in ("refreshtoken", "webpushsubscription"):
                assert any(key["referred_table"] == "authsession" and key["constrained_columns"] == ["auth_session_id"]
                           for key in inspector.get_foreign_keys(table))
                assert any(idx["column_names"] == ["auth_session_id"] for idx in inspector.get_indexes(table))
            with db.connect() as conn:
                assert conn.execute(text("SELECT token_hash, auth_session_id FROM refreshtoken")).one() == (
                    "fixture-legacy-hash", None,
                )
                assert conn.execute(text("SELECT endpoint, auth_session_id, binding_id FROM webpushsubscription")).one() == (
                    "https://push.invalid/legacy", None, None,
                )
            if pass_number == 0:
                command.downgrade(config, PREVIOUS)
                assert not inspect(db).has_table("authsession")
    finally:
        db.dispose()
