"""Проверка добавления полей на временной базе со старыми записями."""

from alembic import command
from sqlalchemy import inspect, text
from sqlmodel import Session, create_engine

from app.models import Booking, CommandReceipt, Ride, User
from app.timeutil import utcnow
from test_migrations_match_the_models import _config


PREVIOUS = "ch_auth_session_20261112"
CURRENT = "ci_booking_traveler_name"


def test_migration_preserves_existing_booking_and_command(tmp_path, monkeypatch):
    url = f"sqlite:///{(tmp_path / 'legacy.sqlite').as_posix()}"
    monkeypatch.setenv("DATABASE_URL", url)
    database = create_engine(url)
    cfg = _config(url)
    try:
        # Базовая ревизия создаёт таблицы из текущих моделей. Сначала записываем
        # старые по смыслу данные, затем снимаем поля новой ревизии перед проверкой.
        command.upgrade(cfg, CURRENT)
        with Session(database) as session:
            driver = User(phone="+12025550117")
            owner = User(phone="+12025550118")
            session.add_all([driver, owner])
            session.flush()
            ride = Ride(driver_id=driver.id, from_city="A", to_city="B", depart_at=utcnow(), seats_left=2)
            session.add(ride)
            session.flush()
            booking = Booking(ride_id=ride.id, passenger_id=owner.id)
            session.add(booking)
            session.flush()
            receipt = CommandReceipt(user_id=owner.id, request_key="old-booking-key-001",
                                     scope="booking.create", target_id=ride.id, payload_hash="0" * 64,
                                     state="applied", ref_kind="booking", ref_id=booking.id,
                                     ref_created_at=booking.created_at)
            session.add(receipt)
            session.flush()
            booking_id, owner_id, receipt_id = booking.id, owner.id, receipt.id
            session.commit()

        command.downgrade(cfg, PREVIOUS)
        with database.connect() as connection:
            assert connection.execute(text("SELECT passenger_id FROM booking WHERE id=:id"),
                                      {"id": booking_id}).scalar_one() == owner_id
            assert connection.execute(text("SELECT ref_id FROM commandreceipt WHERE id=:id"),
                                      {"id": receipt_id}).scalar_one() == booking_id
        inspector = inspect(database)
        assert "traveler_name" not in {column["name"] for column in inspector.get_columns("booking")}
        assert not {"conflict_booking_id", "conflict_booking_created_at"} & {
            column["name"] for column in inspector.get_columns("commandreceipt")
        }
        command.upgrade(cfg, CURRENT)
        with Session(database) as session:
            booking = session.get(Booking, booking_id)
            receipt = session.get(CommandReceipt, receipt_id)
            assert (booking.passenger_id, booking.traveler_name) == (owner_id, None)
            assert (receipt.ref_id, receipt.conflict_booking_id, receipt.conflict_booking_created_at) == (
                booking_id, None, None,
            )
    finally:
        database.dispose()
