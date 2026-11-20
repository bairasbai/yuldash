from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace
from threading import Barrier

import pytest
from alembic import command
from sqlalchemy import inspect, text
from sqlalchemy.exc import IntegrityError
from sqlmodel import Session, select

from app.account import delete_user_account
from app.db import engine
from app.models import Booking, BookingStatus, LedgerEntry, Payment, User, UserRole
from app.rideshare_bank_accounting import BankSettlementUnavailable, record_bank_observation, reserve_settlement
from app.rideshare_bank_models import RideshareBankCheckout, RideshareBankOperation, RideshareBankPosting
from app.rideshare_bank_provider import BankObservation, BankResource
from app.timeutil import utcnow
from test_last_seat_race import _ride
from test_migration_owner_switches import _isolated_database


@pytest.fixture
def bank_checkout(client, user_factory):
    driver = user_factory("Synthetic bank driver", role=UserRole.driver)
    passenger = user_factory("Synthetic bank passenger")
    ride_id = _ride(client, driver)
    created = client.post("/bookings", headers=passenger["auth"], json={"ride_id": ride_id})
    assert created.status_code < 300
    booking_id = created.json()["id"]
    with Session(engine) as session:
        booking = session.get(Booking, booking_id)
        booking.status = BookingStatus.done
        checkout = RideshareBankCheckout(booking_id=booking_id, amount_kop=120_005, payout_kop=120_005, test_mode=True)
        session.add(checkout)
        session.flush()
        session.add(RideshareBankOperation(checkout_id=checkout.id, resource=BankResource.deal.value, state="succeeded",
                                          provider_id=f"synthetic-deal-{checkout.id}", dispatched_at=utcnow()))
        capture = RideshareBankOperation(checkout_id=checkout.id, resource=BankResource.payment.value, state="succeeded",
                                        amount_kop=120_005, settlement_kop=120_005,
                                        provider_id=f"synthetic-capture-{checkout.id}", dispatched_at=utcnow())
        session.add(capture)
        session.flush()
        session.add(RideshareBankPosting(operation_id=capture.id, amount_kop=120_005, settlement_kop=120_005))
        session.commit()
        return checkout.id, booking_id


def test_unknown_payout_blocks_refund_until_verified_and_neither_uses_the_wallet(bank_checkout):
    checkout_id, booking_id = bank_checkout
    with Session(engine) as session:
        payout = reserve_settlement(session, checkout_id, BankResource.payout, amount_kop=120_005, settlement_kop=120_005)
        payout.state = "needs_reconciliation"
        payout.dispatched_at = utcnow()
        payout_id = payout.id
        session.commit()

    with Session(engine) as session:
        with pytest.raises(BankSettlementUnavailable, match="still reserves"):
            reserve_settlement(session, checkout_id, BankResource.refund, amount_kop=20_000, settlement_kop=20_000)

    with Session(engine) as session:
        canceled = BankObservation(
            resource=BankResource.payout, provider_id=f"synthetic-payout-{checkout_id}", status="canceled",
            operation_id=payout_id, test=True, amount_kop=120_005, deal_id=f"synthetic-deal-{checkout_id}",
        )
        failed = record_bank_observation(session, checkout_id, payout_id, canceled)
        assert failed.state == "canceled"
        session.commit()
        refund = reserve_settlement(session, checkout_id, BankResource.refund, amount_kop=20_000, settlement_kop=20_000)
        refund.state = "needs_reconciliation"
        refund.dispatched_at = utcnow()
        refund_id = refund.id
        session.commit()

    with Session(engine) as session:
        with pytest.raises(BankSettlementUnavailable, match="still reserves"):
            reserve_settlement(session, checkout_id, BankResource.payout, amount_kop=120_005, settlement_kop=120_005)

    with Session(engine) as session:
        returned = BankObservation(
            resource=BankResource.refund, provider_id=f"synthetic-refund-{checkout_id}", status="succeeded",
            operation_id=refund_id, test=None, amount_kop=20_000, settlement_kop=20_000,
            deal_id=f"synthetic-deal-{checkout_id}", payment_id=f"synthetic-capture-{checkout_id}",
        )
        for wrong in (replace(returned, payment_id="unrelated-payment"), replace(returned, test=False),
                      replace(returned, settlement_kop=19_999), replace(returned, operation_id="unrelated-operation")):
            with pytest.raises(BankSettlementUnavailable):
                record_bank_observation(session, checkout_id, refund_id, wrong)
        assert session.get(RideshareBankPosting, refund_id) is None
        assert record_bank_observation(session, checkout_id, refund_id, returned).state == "succeeded"
        assert record_bank_observation(session, checkout_id, refund_id, returned).state == "succeeded"
        assert record_bank_observation(session, checkout_id, refund_id, replace(returned, status="pending")).state == "succeeded"
        with pytest.raises(BankSettlementUnavailable, match="contradicts"):
            record_bank_observation(session, checkout_id, refund_id, replace(returned, status="canceled"))
        session.commit()
        with pytest.raises(BankSettlementUnavailable, match="remaining verified balance"):
            reserve_settlement(session, checkout_id, BankResource.payout, amount_kop=120_005, settlement_kop=120_005)
        payout = reserve_settlement(session, checkout_id, BankResource.payout, amount_kop=100_005, settlement_kop=100_005)
        assert payout.amount_kop == 100_005
        assert not session.exec(select(Payment).where(Payment.booking_id == booking_id)).all()
        assert not session.exec(select(LedgerEntry).where(LedgerEntry.booking_id == booking_id)).all()
        session.commit()


def test_refund_and_payout_race_keeps_a_single_reservation(bank_checkout):
    checkout_id, _ = bank_checkout
    start = Barrier(2)

    def contender(resource, amount):
        with Session(engine) as session:
            start.wait(timeout=8)
            try:
                reserved = reserve_settlement(session, checkout_id, resource, amount_kop=amount, settlement_kop=amount)
                chosen_id = reserved.id
                session.commit()
                return chosen_id
            except BankSettlementUnavailable:
                session.rollback()
                return None

    with ThreadPoolExecutor(max_workers=2) as race:
        payout = race.submit(contender, BankResource.payout, 120_005)
        refund = race.submit(contender, BankResource.refund, 20_000)
        winners = [result for result in (payout.result(timeout=12), refund.result(timeout=12)) if result is not None]
    assert len(winners) == 1
    with Session(engine) as session:
        assert session.exec(select(RideshareBankOperation.id).where(
            RideshareBankOperation.checkout_id == checkout_id, RideshareBankOperation.state == "queued",
        )).all() == winners
        session.add(RideshareBankOperation(checkout_id=checkout_id, resource=BankResource.payout.value,
                                           amount_kop=120_005, settlement_kop=120_005))
        with pytest.raises(IntegrityError):
            session.commit()


def test_only_one_open_checkout_for_the_same_booking(bank_checkout):
    checkout_id, booking_id = bank_checkout
    with Session(engine) as session:
        session.add(RideshareBankCheckout(booking_id=booking_id, amount_kop=120_005, payout_kop=120_005, test_mode=True))
        with pytest.raises(IntegrityError):
            session.commit()
        session.rollback()
        checkout = session.get(RideshareBankCheckout, checkout_id)
        checkout.closed_at = utcnow()
        session.commit()
        session.add(RideshareBankCheckout(booking_id=booking_id, amount_kop=120_005, payout_kop=120_005, test_mode=True))
        session.commit()
        assert len(session.exec(select(RideshareBankCheckout).where(
            RideshareBankCheckout.booking_id == booking_id,
        )).all()) == 2


def test_account_deletion_detaches_the_booking_but_keeps_pending_bank_obligations(bank_checkout):
    checkout_id, booking_id = bank_checkout
    with Session(engine) as session:
        passenger_id = session.get(Booking, booking_id).passenger_id
        operation = reserve_settlement(session, checkout_id, BankResource.payout, amount_kop=120_005,
                                       settlement_kop=120_005)
        operation.state = "needs_reconciliation"
        operation.dispatched_at = utcnow()
        operation_id = operation.id
        session.commit()
        delete_user_account(session, session.get(User, passenger_id))

    with Session(engine) as session:
        assert session.get(User, passenger_id) is None
        assert session.get(Booking, booking_id) is None
        assert session.get(RideshareBankCheckout, checkout_id).booking_id is None
        assert session.get(RideshareBankOperation, operation_id).state == "needs_reconciliation"
        confirmed = session.exec(
            select(RideshareBankPosting)
            .join(RideshareBankOperation, RideshareBankPosting.operation_id == RideshareBankOperation.id)
            .where(RideshareBankOperation.checkout_id == checkout_id),
        ).one()
        assert confirmed.amount_kop == 120_005
        with pytest.raises(BankSettlementUnavailable, match="unavailable"):
            reserve_settlement(session, checkout_id, BankResource.payout, amount_kop=120_005, settlement_kop=120_005)


def test_bank_accounting_upgrade_requires_empty_history_for_downgrade(tmp_path, monkeypatch):
    db, config = _isolated_database(tmp_path, monkeypatch, "YULDASH_BANK_ACCOUNTING_MIGRATION_DB", "bank-accounting.sqlite3")
    previous, target = "cs_card_payment_switch", "ct_rideshare_bank_accounting"
    try:
        command.upgrade(config, previous)
        names = ("ridesharebankposting", "ridesharebankoperation", "ridesharebankcheckout")
        # The initial migration can create current metadata. Restore the previous shape.
        with db.begin() as connection:
            for table in names:
                if inspect(connection).has_table(table):
                    connection.execute(text(f"DROP TABLE {table}"))
        command.upgrade(config, target)
        assert all(inspect(db).has_table(table) for table in names)
        indexes = {index["name"]: index for index in inspect(db).get_indexes("ridesharebankoperation")}
        assert indexes["uq_rideshare_bank_active_operation"]["unique"]
        assert indexes["uq_rideshare_bank_final_operation"]["unique"]
        assert any(index["name"] == "uq_rideshare_bank_open_checkout" and index["unique"]
                   for index in inspect(db).get_indexes("ridesharebankcheckout"))
        with Session(db) as session:
            checkout = RideshareBankCheckout(amount_kop=120_005, payout_kop=120_005, test_mode=True)
            session.add(checkout)
            session.commit()
            checkout_id = checkout.id
        with pytest.raises(RuntimeError, match="history must be preserved"):
            command.downgrade(config, previous)
        with Session(db) as session:
            checkout = session.get(RideshareBankCheckout, checkout_id)
            assert checkout.amount_kop == 120_005
            session.delete(checkout)
            session.commit()
        command.downgrade(config, previous)
        assert not any(inspect(db).has_table(table) for table in names)
        command.upgrade(config, target)
        assert all(inspect(db).has_table(table) for table in names)
    finally:
        db.dispose()
