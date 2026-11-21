import pytest
from alembic import command
from cryptography.fernet import Fernet
from sqlalchemy import inspect, text
from sqlmodel import Session

from app.db import engine
from app.models import UserRole
from app.rideshare_bank_models import RideshareBankRecipient
from app.rideshare_bank_recipient import BankRecipientScope, BankRecipientUnavailable, bank_recipient_token, save_bank_recipient
from test_migration_owner_switches import _isolated_database


def test_token_is_encrypted_and_a_changed_recipient_requires_new_confirmation(user_factory):
    driver = user_factory(role=UserRole.driver, taxi_approved=False)
    driver_id = driver["id"]
    cipher = Fernet(Fernet.generate_key())
    with Session(engine) as session:
        first = save_bank_recipient(session, driver_id, "synthetic-first-token", expected_version=None, cipher=cipher, scope=BankRecipientScope(True, None))
        original = first.version
        stored = session.get(RideshareBankRecipient, driver_id)
        assert first.configured and stored.token_ciphertext and stored.token_ciphertext != b"synthetic-first-token"
        session.commit()
    with Session(engine) as session:
        assert bank_recipient_token(session, driver_id, original, cipher=cipher, scope=BankRecipientScope(True, None)) == "synthetic-first-token"
        with pytest.raises(BankRecipientUnavailable, match="version has changed"):
            save_bank_recipient(session, driver_id, "synthetic-first-token", expected_version=None, cipher=cipher, scope=BankRecipientScope(True, None))
        updated = save_bank_recipient(session, driver_id, "synthetic-second-token", expected_version=original, cipher=cipher, scope=BankRecipientScope(True, None))
        newer = updated.version
        assert newer != original
        with pytest.raises(BankRecipientUnavailable, match="requires confirmation"):
            bank_recipient_token(session, driver_id, original, cipher=cipher, scope=BankRecipientScope(True, None))
        assert bank_recipient_token(session, driver_id, newer, cipher=cipher, scope=BankRecipientScope(True, None)) == "synthetic-second-token"
        with pytest.raises(BankRecipientUnavailable, match="cannot be read"):
            bank_recipient_token(session, driver_id, newer, cipher=Fernet(Fernet.generate_key()), scope=BankRecipientScope(True, None))
        session.commit()


def test_revocation_erases_the_token_and_repeat_does_not_change_the_revision(user_factory):
    owner = user_factory(role=UserRole.driver, taxi_approved=False)
    cipher = Fernet(Fernet.generate_key())
    with Session(engine) as session:
        original = save_bank_recipient(session, owner["id"], "synthetic-payout-token", expected_version=None, cipher=cipher, scope=BankRecipientScope(True, None)).version
        revoked = save_bank_recipient(session, owner["id"], None, expected_version=original, cipher=cipher, scope=BankRecipientScope(True, None))
        assert revoked.version != original and not revoked.configured
        assert session.get(RideshareBankRecipient, owner["id"]).token_ciphertext is None
        again = save_bank_recipient(session, owner["id"], None, expected_version=original, cipher=cipher, scope=BankRecipientScope(True, None))
        assert again.version == revoked.version
        with pytest.raises(BankRecipientUnavailable):
            bank_recipient_token(session, owner["id"], original, cipher=cipher, scope=BankRecipientScope(True, None))
        session.commit()


def test_passenger_and_unexpected_payment_data_cannot_create_recipient(user_factory):
    passenger = user_factory()
    driver = user_factory(role=UserRole.driver, taxi_approved=False)
    cipher = Fernet(Fernet.generate_key())
    with Session(engine) as session:
        with pytest.raises(BankRecipientUnavailable):
            save_bank_recipient(session, passenger["id"], "synthetic-payout-token", expected_version=None, cipher=cipher, scope=BankRecipientScope(True, None))
        with pytest.raises(ValueError, match="Invalid bank payout token"):
            save_bank_recipient(session, driver["id"], "0000000000000000", expected_version=None, cipher=cipher, scope=BankRecipientScope(True, None))
        assert session.get(RideshareBankRecipient, passenger["id"]) is None
        assert session.get(RideshareBankRecipient, driver["id"]) is None


def test_account_deletion_erases_the_payout_recipient(client, user_factory):
    driver = user_factory(role=UserRole.driver, taxi_approved=False)
    cipher = Fernet(Fernet.generate_key())
    with Session(engine) as session:
        save_bank_recipient(session, driver["id"], "synthetic-payout-token", expected_version=None, cipher=cipher, scope=BankRecipientScope(True, None))
        session.commit()
    response = client.post("/me/delete", headers=driver["auth"])
    assert response.status_code == 200
    with Session(engine) as session:
        assert session.get(RideshareBankRecipient, driver["id"]) is None


def test_bank_recipient_migration_from_the_preceding_schema(tmp_path, monkeypatch):
    db, config = _isolated_database(tmp_path, monkeypatch, "YULDASH_BANK_RECIPIENT_MIGRATION_DB", "bank-recipient.sqlite3")
    previous, target = "ct_rideshare_bank_accounting", "cu_rideshare_bank_recipient"
    try:
        command.upgrade(config, previous)
        with db.begin() as connection:
            if inspect(connection).has_table("ridesharebankrecipient"):
                connection.execute(text("DROP TABLE ridesharebankrecipient"))
        command.upgrade(config, target)
        cols = {column["name"] for column in inspect(db).get_columns("ridesharebankrecipient")}
        assert cols == {"user_id", "version", "token_ciphertext", "updated_at"}
        foreign_keys = inspect(db).get_foreign_keys("ridesharebankrecipient")
        assert len(foreign_keys) == 1 and foreign_keys[0]["options"]["ondelete"] == "CASCADE"
        command.downgrade(config, previous)
        assert not inspect(db).has_table("ridesharebankrecipient")
    finally:
        db.dispose()
