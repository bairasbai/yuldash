from datetime import datetime, timedelta, timezone

import pytest
from alembic import command
from pydantic import ValidationError
from sqlalchemy import delete, event, inspect
from sqlmodel import Session, create_engine, select

from app import cleanup
from app.chat_receipt_models import ChatMessageReceipt
from app.config import Settings
from app.models import Booking, Message, Ride, User
from app.timeutil import utcnow
from test_migrations_match_the_models import _config


PREVIOUS = "da_route_watch_dispatch"
CURRENT = "db_chat_message_receipt"


def test_receipts_are_disabled_without_a_start_and_normalize_explicit_offsets():
    assert Settings(chat_receipts_since=None).chat_receipts_since is None
    assert Settings(chat_receipts_since="").chat_receipts_since is None
    assert Settings(chat_receipts_since="2026-11-21T12:00:00+05:00").chat_receipts_since == datetime(
        2026, 11, 21, 7, tzinfo=timezone.utc,
    )
    with pytest.raises(ValidationError):
        Settings(chat_receipts_since="2026-11-21T12:00:00")


def test_chat_receipt_migration_keeps_history_and_cascades_with_message(tmp_path, monkeypatch):
    url = f"sqlite:///{(tmp_path / 'chat-receipt-migration.sqlite3').as_posix()}"
    monkeypatch.setenv("DATABASE_URL", url)
    database = create_engine(url, hide_parameters=True)

    @event.listens_for(database, "connect")
    def enforce_foreign_keys(connection, _):
        connection.execute("PRAGMA foreign_keys=ON")

    cfg = _config(url)
    try:
        # The historical baseline uses current metadata on an empty database.
        command.upgrade(cfg, CURRENT)
        command.downgrade(cfg, PREVIOUS)
        assert not inspect(database).has_table("chatmessagereceipt")
        with Session(database) as session:
            sender = User(phone="+79990006201")
            recipient = User(phone="+79990006202")
            session.add_all([sender, recipient])
            session.flush()
            ride = Ride(driver_id=sender.id, from_city="Баймак", to_city="Сибай", depart_at=utcnow())
            session.add(ride)
            session.flush()
            booking = Booking(ride_id=ride.id, passenger_id=recipient.id)
            session.add(booking)
            session.flush()
            message = Message(booking_id=booking.id, sender_id=sender.id, text="synthetic legacy chat")
            session.add(message)
            session.flush()
            message_id, recipient_id = message.id, recipient.id
            session.commit()

        command.upgrade(cfg, CURRENT)
        schema = inspect(database)
        assert schema.get_pk_constraint("chatmessagereceipt")["constrained_columns"] == ["message_id", "user_id"]
        assert {key["referred_table"]: key["options"]["ondelete"]
                for key in schema.get_foreign_keys("chatmessagereceipt")} == {"message": "CASCADE", "user": "CASCADE"}
        with Session(database) as session:
            assert session.get(Message, message_id).text == "synthetic legacy chat"
            assert session.exec(select(ChatMessageReceipt)).all() == []
            session.add(ChatMessageReceipt(message_id=message_id, user_id=recipient_id, delivered=True, read=True))
            session.commit()
        command.downgrade(cfg, PREVIOUS)
        assert not inspect(database).has_table("chatmessagereceipt")
        command.upgrade(cfg, CURRENT)
        with Session(database) as session:
            assert session.get(Message, message_id).text == "synthetic legacy chat"
            assert session.exec(select(ChatMessageReceipt)).all() == []
            session.add(ChatMessageReceipt(
                message_id=message_id, user_id=recipient_id, delivered=True, read=True,
                updated_at=utcnow() - timedelta(days=365),
            ))
            session.commit()

        # A legacy development SQLite database could have foreign keys turned off.
        with database.connect() as connection:
            connection.exec_driver_sql("PRAGMA foreign_keys=OFF")
            connection.execute(ChatMessageReceipt.__table__.insert().values(
                message_id=message_id + 1000, user_id=recipient_id, updated_at=utcnow(),
            ))
            connection.commit()
            connection.exec_driver_sql("PRAGMA foreign_keys=ON")

        monkeypatch.setattr(cleanup, "engine", database)
        rule = next(rule for rule in cleanup._rules(utcnow()) if rule[1] == "chatmessagereceipt")
        assert cleanup._delete_batched(*rule[1:]) == 1
        with Session(database) as session:
            remaining = session.get(ChatMessageReceipt, (message_id, recipient_id))
            assert remaining is not None and remaining.read
            session.exec(delete(Message).where(Message.id == message_id))
            session.commit()
            assert session.exec(select(ChatMessageReceipt)).all() == []
    finally:
        database.dispose()
