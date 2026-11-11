"""Миграция RS-05 на старых строках во временной базе."""

import os
import re

from alembic import command
from sqlalchemy import inspect
from sqlmodel import Session, create_engine, select

from app.db import engine
from app.models import Booking, CommandReceipt, Ride, User
from app.timeutil import utcnow
from test_migrations_match_the_models import _config


PREVIOUS = "cf_merge_legacy_safety_20261102"
CURRENT = "cg_command_receipt_20261111"


def test_migration_up_down_up_preserves_existing_ride_and_booking(tmp_path, monkeypatch):
    pg_database = os.environ.get("RS05A_MIGRATION_TEST_DB")
    if pg_database:
        assert re.fullmatch(r"rs05a_migration(?:_r[0-9]+)?", pg_database)
        assert engine.dialect.name == "postgresql" and engine.url.host == "127.0.0.1"
        database_url = engine.url.set(database=pg_database).render_as_string(hide_password=False)
    else:
        database_url = f"sqlite:///{(tmp_path / 'before_rs05a.sqlite').as_posix()}"
    monkeypatch.setenv("DATABASE_URL", database_url)
    cfg = _config(database_url)
    database = create_engine(database_url)

    try:
        assert not inspect(database).get_table_names()
        command.upgrade(cfg, PREVIOUS)
        # При создании с нуля базовая миграция подхватывает текущие модели.
        # Получаем исторический состав перед проверкой добавления таблицы.
        CommandReceipt.__table__.drop(database, checkfirst=True)
        with Session(database) as session:
            driver = User(phone="+79990001031")
            passenger = User(phone="+79990001032")
            session.add_all([driver, passenger])
            session.flush()
            ride = Ride(driver_id=driver.id, from_city="A", to_city="B", depart_at=utcnow(), seats_left=2)
            session.add(ride)
            session.flush()
            booking = Booking(ride_id=ride.id, passenger_id=passenger.id)
            session.add(booking)
            session.flush()
            owner_id, ride_id, booking_id = passenger.id, ride.id, booking.id
            booking_created_at = booking.created_at
            session.commit()

        def check_legacy():
            with Session(database) as session:
                ride = session.get(Ride, ride_id)
                booking = session.get(Booking, booking_id)
                assert (ride.from_city, ride.to_city, ride.seats_left) == ("A", "B", 2)
                assert (booking.ride_id, booking.passenger_id) == (ride_id, owner_id)

        for first in (True, False):
            command.upgrade(cfg, CURRENT)
            database.dispose()
            inspector = inspect(database)
            assert {index["name"] for index in inspector.get_indexes("commandreceipt")
                    if not index.get("duplicates_constraint")} == {
                "ix_commandreceipt_user_id", "ix_commandreceipt_created_at",
            }
            assert {unique["name"] for unique in inspector.get_unique_constraints("commandreceipt")} == {
                "uq_command_receipt_owner_key",
            }
            assert inspector.get_foreign_keys("commandreceipt")[0]["options"]["ondelete"] == "CASCADE"
            check_legacy()
            with Session(database) as session:
                assert session.exec(select(CommandReceipt)).all() == []
                if first:
                    session.add(CommandReceipt(
                        user_id=owner_id, request_key="migration-check-key",
                        scope="booking.create", target_id=ride_id, payload_hash="0" * 64,
                        state="applied", ref_kind="booking", ref_id=booking_id, ref_created_at=booking_created_at,
                    ))
                    session.commit()
            if first:
                command.downgrade(cfg, PREVIOUS)
                assert not inspect(database).has_table("commandreceipt")
                check_legacy()
    finally:
        database.dispose()
