"""An incoming hint can request verification, while only the current bank object changes state."""

import pytest
from sqlmodel import Session

from app.account import delete_user_account
from app.db import engine
from app.models import Booking, User
from app.owner_switches import PAID_SWITCH
from app.rideshare_bank_dispatch import reconcile_bank_notification
from app.rideshare_bank_models import RideshareBankCheckout, RideshareBankOperation, RideshareBankPosting
from app.rideshare_bank_provider import BankOutcomeUnknown, BankResource
from app.timeutil import utcnow
from test_rideshare_bank_dispatch import bank_checkout as bank_checkout
from test_rideshare_bank_dispatch import payment_operation, send
from test_rideshare_bank_settlement import authorize, dispatch
from test_rideshare_bank_settlement import paid_checkout as paid_checkout


def notice(event, provider_id, operation_id=None):
    item = {"id": provider_id, "status": "succeeded"}
    if operation_id is not None:
        item["metadata"] = {"rideshare_operation": operation_id}
    return {"type": "notification", "event": event, "object": item}


def verify(service, payload):
    return reconcile_bank_notification(engine, payload, runtime=service.runtime, provider=service.provider)


def test_notification_cannot_mark_a_payment_and_reconciles_a_lost_response(bank_checkout):
    service = bank_checkout
    checkout_id = service.begin()
    assert send(service, checkout_id) == "succeeded"
    service.fake.lose_payment_response = True
    assert send(service, checkout_id) == "needs_reconciliation"
    with Session(engine) as session:
        operation_id = payment_operation(session, checkout_id).id
    provider_id = service.fake.by_key[operation_id]
    service.toggle(PAID_SWITCH, False)
    payload = notice("payment.succeeded", provider_id, operation_id)
    assert verify(service, payload) == "pending"
    with Session(engine) as session:
        assert not session.get(Booking, service.booking_id).paid
        assert session.get(RideshareBankPosting, operation_id) is None
        assert session.get(RideshareBankOperation, operation_id).provider_id == provider_id
    service.fake.finish_payment()
    assert verify(service, notice("payment.succeeded", provider_id)) == "succeeded"
    bank_object = service.fake.objects[provider_id]
    bank_object.update(status="pending", paid=False)
    assert verify(service, notice("payment.waiting_for_capture", provider_id, operation_id)) == "succeeded"
    with Session(engine) as session:
        assert session.get(Booking, service.booking_id).paid
        assert session.get(RideshareBankPosting, operation_id).amount_kop == 45_000
    assert [item[0] for item in service.fake.requests] == ["POST", "POST", "GET", "GET", "GET"]


def test_unowned_inconsistent_or_wrong_environment_hints_do_not_trigger_http(bank_checkout):
    service = bank_checkout
    checkout_id = service.begin()
    assert send(service, checkout_id) == "succeeded"
    assert send(service, checkout_id) == "pending"
    known_key = service.fake.requests[-1][2]
    provider_id = service.fake.by_key[known_key]
    with Session(engine) as session:
        other_checkout = RideshareBankCheckout(amount_kop=45_000, payout_kop=45_000, test_mode=True)
        foreign_checkout = RideshareBankCheckout(amount_kop=45_000, payout_kop=45_000, test_mode=False)
        waiting_checkout = RideshareBankCheckout(amount_kop=45_000, payout_kop=45_000, test_mode=True)
        session.add_all([other_checkout, foreign_checkout, waiting_checkout])
        session.flush()
        other = RideshareBankOperation(
            checkout_id=other_checkout.id, resource="payments", state="needs_reconciliation",
            amount_kop=45_000, settlement_kop=45_000, dispatched_at=utcnow(), request_ciphertext=b"synthetic",
        )
        queued = RideshareBankOperation(
            checkout_id=waiting_checkout.id, resource="refunds", amount_kop=45_000, settlement_kop=45_000,
        )
        foreign = RideshareBankOperation(
            checkout_id=foreign_checkout.id, resource="payments", state="needs_reconciliation",
            amount_kop=45_000, settlement_kop=45_000, dispatched_at=utcnow(), request_ciphertext=b"synthetic",
        )
        session.add_all([other, queued, foreign])
        session.flush()
        queued_id, other_id, foreign_id = queued.id, other.id, foreign.id
        session.commit()
    inputs = [
        notice("payment.succeeded", "unknown-site-identifier"),
        notice("payment.succeeded", "unlinked-site-identifier", foreign_id),
        notice("refund.succeeded", "never-dispatched", queued_id),
        notice("refund.succeeded", provider_id, known_key),
        notice("payment.succeeded", provider_id, other_id),
        notice("payment.succeeded", "other-id", known_key),
        notice("payment.succeeded", "../payments", known_key),
        {"type": "notification", "event": [], "object": {}},
        {"type": "notification", "event": "payment.succeeded", "object": {"metadata": []}},
    ]
    before = len(service.fake.requests)
    for message in inputs:
        assert verify(service, message) is None
    assert len(service.fake.requests) == before


def test_refund_notification_recovers_detached_obligation_after_read_timeout(paid_checkout):
    service, checkout_id, _ = paid_checkout
    refund = authorize(paid_checkout, BankResource.refund, 45_000)
    service.fake.lose_settlement_response.add("refunds")
    assert dispatch(paid_checkout, refund) == "needs_reconciliation"
    provider_id = service.fake.by_key[refund]
    with Session(engine) as session:
        delete_user_account(session, session.get(User, service.passenger["id"]))
    service.toggle(PAID_SWITCH, False)
    service.fake.objects[provider_id]["status"] = "succeeded"
    service.fake.fail_reads.add(provider_id)
    payload = notice("refund.succeeded", provider_id, refund)
    with pytest.raises(BankOutcomeUnknown):
        verify(service, payload)
    with Session(engine) as session:
        assert session.get(RideshareBankOperation, refund).state == "needs_reconciliation"
        assert session.get(RideshareBankPosting, refund) is None
    service.fake.fail_reads.clear()
    assert verify(service, payload) == "succeeded"
    assert verify(service, payload) == "succeeded"
    with Session(engine) as session:
        assert session.get(RideshareBankCheckout, checkout_id).booking_id is None
        assert session.get(RideshareBankPosting, refund).amount_kop == 45_000
