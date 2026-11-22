"""A populated taxi order and its chat must survive a consent migration round trip."""
import os

from alembic import command
from sqlalchemy import inspect, text
from sqlmodel import Session, create_engine

from app.db import engine
from app.models import InstantOrder, Message, User
from test_migrations_match_the_models import _config


FORK = "cz_rideshare_recipient_setup"
MAIN_PARENT = "db_chat_message_receipt"
TAXI_PARENT = "da_taxi_consent_attempt"
HEAD = "dc_merge_taxi_consent_receipts"


def test_consent_migration_preserves_referenced_orders(tmp_path, monkeypatch):
    pg_database = os.environ.get("TAXI_CONSENT_MIGRATION_TEST_DB")
    if pg_database:
        assert engine.dialect.name == "postgresql" and engine.url.host == "127.0.0.1"
        assert pg_database == "taxi_consent_migration" and pg_database != engine.url.database
        url = engine.url.set(database=pg_database).render_as_string(hide_password=False)
    else:
        url = f"sqlite:///{(tmp_path / 'taxi-consent.sqlite3').as_posix()}"

    monkeypatch.setenv("DATABASE_URL", url)
    database = create_engine(url, hide_parameters=True)
    cfg = _config(url)
    try:
        assert not inspect(database).get_table_names()
        command.upgrade(cfg, HEAD)
        with Session(database) as session:
            passenger = User(phone="+79990006203")
            session.add(passenger)
            session.flush()
            order = InstantOrder(passenger_id=passenger.id)
            session.add(order)
            session.flush()
            message = Message(sender_id=passenger.id, order_id=order.id, text="synthetic taxi chat")
            session.add(message)
            session.flush()
            order_id, message_id = order.id, message.id
            session.commit()

        # A fresh install starts with current models. Reach the actual parent shape
        # before checking the upgrade path for an existing order with a referencing row.
        command.downgrade(cfg, f"{TAXI_PARENT}@{FORK}")
        with database.connect() as connection:
            assert connection.execute(text("SELECT version_num FROM alembic_version")).scalars().all() == [MAIN_PARENT]
        legacy = inspect(database)
        assert not legacy.has_table("taxiconsentattempt")
        assert "consent_request_key" not in {c["name"] for c in legacy.get_columns("instantorder")}

        command.upgrade(cfg, HEAD)
        upgraded = inspect(database)
        indexes = {i["name"]: i["column_names"] for i in upgraded.get_indexes("instantorder") if i["unique"]}
        assert indexes["uq_taxi_consent_owner_key"] == ["passenger_id", "consent_request_key"]
        assert indexes["uq_taxi_consent_quote"] == ["consent_quote_id"]
        assert upgraded.get_pk_constraint("taxiconsentattempt")["constrained_columns"] == ["owner_id", "request_key"]
        # The other published branch already has taxi consent, but lacks the two
        # rideshare revisions. Upgrading must run both before joining the histories.
        command.downgrade(cfg, f"{MAIN_PARENT}@{FORK}")
        taxi_parent = inspect(database)
        assert taxi_parent.has_table("taxiconsentattempt")
        assert not taxi_parent.has_table("routewatchevent")
        assert not taxi_parent.has_table("chatmessagereceipt")
        with database.connect() as connection:
            assert connection.execute(text("SELECT version_num FROM alembic_version")).scalars().all() == [TAXI_PARENT]
        command.upgrade(cfg, HEAD)
        merged = inspect(database)
        assert merged.has_table("routewatchevent") and merged.has_table("chatmessagereceipt")
        with Session(database) as session:
            assert session.execute(text("SELECT version_num FROM alembic_version")).scalars().all() == [HEAD]
            restored = session.get(InstantOrder, order_id)
            assert restored is not None and restored.consent_request_key is None
            chat = session.get(Message, message_id)
            assert chat is not None and chat.order_id == order_id and chat.text == "synthetic taxi chat"
            if database.dialect.name == "sqlite":
                assert session.execute(text("PRAGMA foreign_keys")).scalar_one() == 1
                assert session.execute(text("PRAGMA foreign_key_check")).all() == []
    finally:
        database.dispose()


def test_published_sqlite_constraints_rollback_preserves_cascading_children(tmp_path, monkeypatch):
    # The original taxi-only QA revision used inline UNIQUE constraints.
    url = f"sqlite:///{(tmp_path / 'legacy-taxi-consent.sqlite3').as_posix()}"
    monkeypatch.setenv("DATABASE_URL", url)
    database = create_engine(url)
    cfg = _config(url)
    try:
        with database.begin() as connection:
            assert connection.exec_driver_sql("PRAGMA foreign_keys").scalar_one() == 1
            connection.exec_driver_sql("""
                CREATE TABLE instantorder (
                    id INTEGER PRIMARY KEY, passenger_id INTEGER NOT NULL,
                    consent_request_key VARCHAR(36), consent_request_digest VARCHAR(64),
                    consent_quote_id VARCHAR(36), consent_snapshot_json VARCHAR,
                    CONSTRAINT uq_taxi_consent_owner_key UNIQUE (passenger_id, consent_request_key),
                    CONSTRAINT uq_taxi_consent_quote UNIQUE (consent_quote_id)
                )
            """)
            connection.exec_driver_sql("""
                CREATE TABLE linked_chat (
                    order_id INTEGER PRIMARY KEY REFERENCES instantorder(id) ON DELETE CASCADE
                )
            """)
            connection.exec_driver_sql("CREATE TABLE changes (order_id INTEGER)")
            connection.exec_driver_sql("""
                CREATE TRIGGER audit_order AFTER UPDATE OF passenger_id ON instantorder
                BEGIN INSERT INTO changes VALUES(NEW.id); END
            """)
            connection.exec_driver_sql("INSERT INTO instantorder(id, passenger_id) VALUES(11, 2)")
            connection.exec_driver_sql("INSERT INTO linked_chat VALUES(11)")
        command.stamp(cfg, FORK)
        command.upgrade(cfg, "cz_taxi_initial_price_consent")
        assert inspect(database).get_indexes("instantorder") == []
        command.downgrade(cfg, FORK)
        with database.begin() as connection:
            assert connection.exec_driver_sql("SELECT id, passenger_id FROM instantorder").all() == [(11, 2)]
            assert connection.exec_driver_sql("SELECT order_id FROM linked_chat").all() == [(11,)]
            connection.exec_driver_sql("UPDATE instantorder SET passenger_id=3 WHERE id=11")
            assert connection.exec_driver_sql("SELECT order_id FROM changes").all() == [(11,)]
            assert connection.exec_driver_sql("PRAGMA foreign_key_check").all() == []
        assert {c["name"] for c in inspect(database).get_columns("instantorder")} == {"id", "passenger_id"}
    finally:
        database.dispose()
