"""Owner approval, payout pauses and refund recovery against the synthetic bank."""

from concurrent.futures import ThreadPoolExecutor
from threading import Barrier
from uuid import uuid4

import pytest
from alembic import command
from fastapi.security import HTTPAuthorizationCredentials
from sqlalchemy import inspect, text
from sqlmodel import Session, select

from app.account import delete_user_account
from app.db import engine
from app.models import User, UserRole
from app.owner_switches import PAID_SWITCH
from app.rideshare_bank_accounting import BankSettlementUnavailable
from app.rideshare_bank_booking import BankCheckoutUnavailable, bank_switches
from app.rideshare_bank_dispatch import dispatch_bank_operation, reconcile_bank_operation
from app.rideshare_bank_models import RideshareBankApproval, RideshareBankCheckout, RideshareBankOperation, RideshareBankPosting
from app.rideshare_bank_provider import BankResource
from app.rideshare_bank_recipient import save_bank_recipient
from app.rideshare_bank_settlement import authorize_bank_settlement, cancel_unsent_bank_payout, reauthorize_bank_payout
from test_migration_owner_switches import _isolated_database
from test_rideshare_bank_dispatch import bank_checkout as bank_checkout
from test_rideshare_bank_dispatch import payment_operation, send


def credentials(user):
    return HTTPAuthorizationCredentials(scheme="Bearer", credentials=user["token"])


@pytest.fixture
def paid_checkout(bank_checkout, user_factory, set_paid_owner):
    actor = user_factory("Synthetic bank owner", role=UserRole.admin)
    set_paid_owner(actor)
    service = bank_checkout
    checkout_id = service.begin()
    assert send(service, checkout_id) == "succeeded"
    assert send(service, checkout_id) == "pending"
    service.fake.finish_payment()
    with Session(engine) as session:
        payment_id = payment_operation(session, checkout_id).id
    assert reconcile_bank_operation(engine, checkout_id, payment_id,
                                    runtime=service.runtime, provider=service.provider) == "succeeded"
    return service, checkout_id, actor


def authorize(paid_checkout, kind, amount, *, command=None, actor=None, signed_by=None):
    service, checkout_id, owner = paid_checkout
    actor = actor or owner
    with Session(engine) as session:
        revisions = bank_switches(session) if kind == BankResource.payout else None
        operation = authorize_bank_settlement(
            session, checkout_id, kind, owner_id=actor["id"], credentials=credentials(signed_by or actor),
            command_id=command or uuid4(), amount_kop=amount, runtime=service.runtime, expected_switches=revisions,
        )
        operation_id = operation.id
        session.commit()
        return operation_id


def dispatch(paid_checkout, operation_id):
    service, checkout_id, _ = paid_checkout
    return dispatch_bank_operation(engine, checkout_id, runtime=service.runtime, provider=service.provider,
                                   settlement_operation_id=operation_id)


def observe(paid_checkout, operation_id):
    service, checkout_id, _ = paid_checkout
    return reconcile_bank_operation(engine, checkout_id, operation_id, runtime=service.runtime, provider=service.provider)


def test_only_the_signed_owner_can_reserve_and_repeating_command_never_reserves_again(paid_checkout, user_factory):
    service, checkout_id, owner = paid_checkout
    other = user_factory("Synthetic non-owner administrator", role=UserRole.admin)
    assert owner["id"] > max(service.driver["id"], service.passenger["id"])
    kind, amount, command_id = BankResource.refund, 10_000, uuid4()
    for actor, signer in [(other, other), (owner, other), (service.passenger, service.passenger)]:
        with pytest.raises(BankCheckoutUnavailable, match="bank_owner_required"):
            authorize(paid_checkout, kind, amount, actor=actor, signed_by=signer, command=command_id)
    assert all(request[1] != "refunds" for request in service.fake.requests)

    service.toggle(PAID_SWITCH, False)
    operation_id = authorize(paid_checkout, kind, amount, command=command_id)
    assert authorize(paid_checkout, kind, amount, command=command_id) == operation_id
    with pytest.raises(BankCheckoutUnavailable, match="bank_owner_command_conflict"):
        authorize(paid_checkout, kind, amount + 1, command=command_id)
    assert dispatch(paid_checkout, operation_id) == "pending"
    service.fake.objects[service.fake.by_key[operation_id]]["status"] = "canceled"
    assert observe(paid_checkout, operation_id) == "canceled"
    assert authorize(paid_checkout, kind, amount, command=command_id) == operation_id
    assert dispatch(paid_checkout, operation_id) is None
    assert authorize(paid_checkout, kind, amount) != operation_id
    with Session(engine) as session:
        assert session.get(RideshareBankApproval, operation_id).approved_by == owner["id"]
        assert session.get(RideshareBankPosting, operation_id) is None
        assert session.get(RideshareBankOperation, operation_id).checkout_id == checkout_id


def test_payout_response_lost_preserves_reserve_and_frozen_token_after_reapproval(paid_checkout):
    service, checkout_id, owner = paid_checkout
    payout = authorize(paid_checkout, BankResource.payout, 45_000)
    service.fake.lose_settlement_response.add("payouts")
    assert dispatch(paid_checkout, payout) == "needs_reconciliation"
    request = service.fake.requests[-1]
    assert request[:3] == ("POST", "payouts", payout)
    service.toggle(PAID_SWITCH, False)
    with pytest.raises(BankCheckoutUnavailable, match="bank_paused"):
        dispatch(paid_checkout, payout)
    with pytest.raises(BankSettlementUnavailable, match="still reserves"):
        authorize(paid_checkout, BankResource.refund, 10_000)
    service.toggle(PAID_SWITCH, True)
    with pytest.raises(BankSettlementUnavailable, match="still reserves"):
        authorize(paid_checkout, BankResource.refund, 10_000)
    with pytest.raises(BankCheckoutUnavailable, match="bank_confirmation_required"):
        dispatch(paid_checkout, payout)
    with Session(engine) as session:
        save_bank_recipient(session, service.driver["id"], "synthetic-new-token",
                            expected_version=service.version, cipher=service.runtime.cipher, scope=service.runtime.recipient_scope)
        session.commit()
        revisions = bank_switches(session)
        assert reauthorize_bank_payout(session, checkout_id, payout, owner_id=owner["id"],
                                       credentials=credentials(owner), runtime=service.runtime,
                                       expected_switches=revisions).id == payout
        session.commit()
    service.fake.objects[service.fake.by_key[payout]]["status"] = "succeeded"
    assert dispatch(paid_checkout, payout) == "succeeded"
    assert service.fake.requests[-1] == request
    assert observe(paid_checkout, payout) == "succeeded"
    with Session(engine) as session:
        assert session.get(RideshareBankPosting, payout).amount_kop == 45_000
        assert session.exec(select(RideshareBankPosting).where(RideshareBankPosting.operation_id == payout)).one()


def test_unknown_refund_blocks_payout_and_changes_the_confirmed_balance(paid_checkout):
    service, _, _ = paid_checkout
    service.toggle(PAID_SWITCH, False)
    refund = authorize(paid_checkout, BankResource.refund, 10_000)
    service.fake.lose_settlement_response.add("refunds")
    assert dispatch(paid_checkout, refund) == "needs_reconciliation"
    request = service.fake.requests[-1]
    service.toggle(PAID_SWITCH, True)
    with pytest.raises(BankSettlementUnavailable, match="still reserves"):
        authorize(paid_checkout, BankResource.payout, 45_000)
    service.toggle(PAID_SWITCH, False)
    service.fake.objects[service.fake.by_key[refund]]["status"] = "succeeded"
    assert dispatch(paid_checkout, refund) == "succeeded"
    assert service.fake.requests[-1] == request
    assert observe(paid_checkout, refund) == "succeeded"
    service.toggle(PAID_SWITCH, True)
    with pytest.raises(BankSettlementUnavailable, match="remaining verified balance"):
        authorize(paid_checkout, BankResource.payout, 45_000)
    payout = authorize(paid_checkout, BankResource.payout, 35_000)
    assert dispatch(paid_checkout, payout) == "pending"
    service.fake.objects[service.fake.by_key[payout]]["status"] = "succeeded"
    assert observe(paid_checkout, payout) == "succeeded"
    with Session(engine) as session:
        assert session.get(RideshareBankPosting, refund).amount_kop == 10_000
        assert session.get(RideshareBankPosting, payout).amount_kop == 35_000


def test_simultaneous_owner_replays_share_one_approval(paid_checkout):
    _, checkout_id, _ = paid_checkout
    barrier, command_id = Barrier(2), uuid4()

    def submit():
        barrier.wait(timeout=8)
        return authorize(paid_checkout, BankResource.refund, 10_000, command=command_id)

    with ThreadPoolExecutor(max_workers=2) as group:
        first, second = [group.submit(submit) for _ in range(2)]
        assert first.result(timeout=12) == second.result(timeout=12)
    with Session(engine) as session:
        operations = session.exec(select(RideshareBankOperation).where(
            RideshareBankOperation.checkout_id == checkout_id, RideshareBankOperation.resource == "refunds",
        )).all()
        assert len(operations) == 1
        assert session.get(RideshareBankApproval, operations[0].id).command_id == command_id.hex


def test_detached_account_allows_only_an_explicit_owner_refund(paid_checkout):
    service, checkout_id, _ = paid_checkout
    prior_requests = len(service.fake.requests)
    with Session(engine) as session:
        delete_user_account(session, session.get(User, service.passenger["id"]))
    with Session(engine) as session:
        assert session.get(RideshareBankCheckout, checkout_id).booking_id is None
    with pytest.raises(BankCheckoutUnavailable, match="bank_payment_unavailable"):
        authorize(paid_checkout, BankResource.payout, 45_000)
    assert len(service.fake.requests) == prior_requests
    service.toggle(PAID_SWITCH, False)
    refund = authorize(paid_checkout, BankResource.refund, 45_000)
    assert dispatch(paid_checkout, refund) == "pending"
    service.fake.objects[service.fake.by_key[refund]]["status"] = "succeeded"
    assert observe(paid_checkout, refund) == "succeeded"
    with Session(engine) as session:
        assert session.get(RideshareBankPosting, refund).amount_kop == 45_000


def test_owner_can_release_an_unsent_payout_after_the_recipient_changes(paid_checkout):
    service, checkout_id, owner = paid_checkout
    payout = authorize(paid_checkout, BankResource.payout, 45_000)
    before = len(service.fake.requests)
    with Session(engine) as session:
        save_bank_recipient(session, service.driver["id"], "synthetic-new-token",
                            expected_version=service.version, cipher=service.runtime.cipher, scope=service.runtime.recipient_scope)
        session.commit()
    with pytest.raises(BankCheckoutUnavailable, match="bank_recipient_changed"):
        dispatch(paid_checkout, payout)
    assert len(service.fake.requests) == before
    service.toggle(PAID_SWITCH, False)
    with Session(engine) as session:
        assert cancel_unsent_bank_payout(session, checkout_id, payout, owner_id=owner["id"],
                                        credentials=credentials(owner), runtime=service.runtime).state == "canceled"
        session.commit()
    refund = authorize(paid_checkout, BankResource.refund, 45_000)
    assert dispatch(paid_checkout, refund) == "pending"


def test_settlement_migration_retains_approved_commands(tmp_path, monkeypatch):
    db, config = _isolated_database(tmp_path, monkeypatch, "YULDASH_BANK_SETTLEMENT_MIGRATION_DB", "bank-settlement.sqlite3")
    prior, target = "cv_rideshare_bank_dispatch", "cw_rideshare_bank_settlement"
    try:
        command.upgrade(config, prior)
        with db.begin() as conn:
            conn.execute(text("DROP TABLE IF EXISTS ridesharebankapproval"))
        command.upgrade(config, target)
        assert inspect(db).has_table("ridesharebankapproval")
        command.downgrade(config, prior)
        assert not inspect(db).has_table("ridesharebankapproval")
        command.upgrade(config, target)
        with Session(db) as session:
            checkout = RideshareBankCheckout(amount_kop=10_000, payout_kop=10_000, test_mode=True)
            session.add(checkout)
            session.flush()
            operation = RideshareBankOperation(
                checkout_id=checkout.id, resource=BankResource.refund.value, amount_kop=10_000, settlement_kop=10_000,
            )
            session.add(operation)
            session.flush()
            operation_id = operation.id
            session.add(RideshareBankApproval(operation_id=operation_id, command_id=uuid4().hex))
            session.commit()
        with pytest.raises(RuntimeError, match="authorizations must be preserved"):
            command.downgrade(config, prior)
        with Session(db) as session:
            assert session.get(RideshareBankApproval, operation_id) is not None
    finally:
        db.dispose()
