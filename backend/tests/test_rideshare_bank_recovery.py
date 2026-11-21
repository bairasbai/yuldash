"""Restore a bank operation by its exact reference without another POST."""

from datetime import datetime, timedelta, timezone

import pytest
from sqlmodel import Session

from app import rideshare_bank_dispatch as recovery
from app.db import engine
from app.models import Booking
from app.owner_switches import PAID_SWITCH
from app.rideshare_bank_accounting import BankSettlementUnavailable
from app.rideshare_bank_dispatch import reconcile_bank_operation
from app.rideshare_bank_models import RideshareBankOperation, RideshareBankPosting
from app.rideshare_bank_provider import BankOutcomeUnknown, BankResource
from app.timeutil import utcnow
from test_rideshare_bank_dispatch import bank_checkout as bank_checkout
from test_rideshare_bank_dispatch import send
from test_rideshare_bank_settlement import authorize, dispatch
from test_rideshare_bank_settlement import paid_checkout as paid_checkout


def recover(service, checkout_id, operation_id, *, search_unknown=True):
    return reconcile_bank_operation(engine, checkout_id, operation_id, runtime=service.runtime,
                                    provider=service.provider, search_unknown=search_unknown)


@pytest.mark.parametrize("resource", [BankResource.deal, BankResource.payment])
def test_recovers_deal_and_payment_outside_post_window_while_paused(bank_checkout, monkeypatch, resource):
    service = bank_checkout
    checkout_id = service.begin()
    if resource == BankResource.deal:
        service.fake.lose_deal_response = True
    else:
        assert send(service, checkout_id) == "succeeded"
        service.fake.lose_payment_response = True
    assert send(service, checkout_id) == "needs_reconciliation"
    _, _, operation_id, _ = service.fake.requests[-1]
    provider_id = service.fake.by_key[operation_id]
    if resource == BankResource.payment:
        service.fake.finish_payment()
    actual = service.fake.objects[provider_id]
    service.fake.lookup_pages[resource.value, None] = {
        "type": "list", "items": [{"id": "unrelated", "metadata": {}}], "next_cursor": "second",
    }
    service.fake.lookup_pages[resource.value, "second"] = {"type": "list", "items": [actual, actual]}
    service.toggle(PAID_SWITCH, False)
    now = utcnow() + timedelta(days=3)
    monkeypatch.setattr(recovery, "utcnow", lambda: now)
    before = len(service.fake.requests)
    assert recover(service, checkout_id, operation_id, search_unknown=False) is None
    assert len(service.fake.requests) == before
    assert recover(service, checkout_id, operation_id) == "succeeded"
    first, second, checked = service.fake.requests[before:]
    assert first[:2] == second[:2] == ("LIST", resource.value)
    assert second[2] == {**first[2], "cursor": "second"}
    assert checked == ("GET", resource.value, provider_id)
    with Session(engine) as session:
        operation = session.get(RideshareBankOperation, operation_id)
        assert operation.provider_id == provider_id
        stamp = operation.dispatched_at.replace(tzinfo=timezone.utc)
        assert datetime.fromisoformat(first[2]["created_at.gte"]) == stamp - timedelta(minutes=5)
        assert datetime.fromisoformat(first[2]["created_at.lte"]) == stamp + timedelta(hours=24, minutes=5)
        assert session.get(Booking, service.booking_id).paid == (resource == BankResource.payment)


@pytest.mark.parametrize("resource", [BankResource.refund, BankResource.payout])
def test_recovers_unknown_settlement_on_pause_without_a_second_post(paid_checkout, monkeypatch, resource):
    service, checkout_id, _ = paid_checkout
    amount = 10_000 if resource == BankResource.refund else 45_000
    operation_id = authorize(paid_checkout, resource, amount)
    service.fake.lose_settlement_response.add(resource.value)
    assert dispatch(paid_checkout, operation_id) == "needs_reconciliation"
    provider_id = service.fake.by_key[operation_id]
    result = service.fake.objects[provider_id]
    result["status"] = "succeeded"
    service.toggle(PAID_SWITCH, False)
    now = utcnow() + timedelta(days=2)
    monkeypatch.setattr(recovery, "utcnow", lambda: now)
    before = len(service.fake.requests)
    assert recover(service, checkout_id, operation_id) == "succeeded"
    hints, verified = service.fake.requests[before:]
    assert hints[:2] == ("LIST", resource.value)
    if resource == BankResource.refund:
        assert hints[2]["payment_id"] == result["payment_id"]
    else:
        assert "payment_id" not in hints[2]
    assert verified == ("GET", resource.value, provider_id)
    with Session(engine) as session:
        assert session.get(RideshareBankPosting, operation_id).amount_kop == amount


def test_unknown_search_preserves_the_reserve_on_no_match_ambiguous_or_incomplete_pages(paid_checkout):
    service, checkout_id, _ = paid_checkout
    payout = authorize(paid_checkout, BankResource.payout, 45_000)
    assert recover(service, checkout_id, payout) is None  # queued has no admission
    service.fake.lose_settlement_response.add("payouts")
    assert dispatch(paid_checkout, payout) == "needs_reconciliation"
    actual = service.fake.objects[service.fake.by_key[payout]]
    service.fake.lookup_pages["payouts", None] = {"type": "list", "items": []}
    assert recover(service, checkout_id, payout) is None
    service.fake.lookup_pages["payouts", None] = {"type": "list", "items": [actual], "next_cursor": "next"}
    service.fake.lookup_pages["payouts", "next"] = {"type": "list", "items": [{**actual, "id": "another-payout"}]}
    with pytest.raises(BankOutcomeUnknown, match="more than one"):
        recover(service, checkout_id, payout)
    for number, cursor in enumerate(("next", "two", "three", "four"), 2):
        service.fake.lookup_pages["payouts", cursor] = {
            "type": "list", "items": [], "next_cursor": ("next", "two", "three", "four", "five")[number - 1],
        }
    with pytest.raises(BankOutcomeUnknown, match="page budget"):
        recover(service, checkout_id, payout)
    assert all(request[0] == "LIST" for request in service.fake.requests[4:])
    with Session(engine) as session:
        operation = session.get(RideshareBankOperation, payout)
        assert operation.provider_id is None and operation.state == "needs_reconciliation"
        assert session.get(RideshareBankPosting, payout) is None
    with pytest.raises(BankSettlementUnavailable, match="still reserves"):
        authorize(paid_checkout, BankResource.refund, 10_000)


def test_found_payment_must_pass_authoritative_read_and_accounting(bank_checkout):
    service = bank_checkout
    checkout_id = service.begin()
    assert send(service, checkout_id) == "succeeded"
    service.fake.lose_payment_response = True
    assert send(service, checkout_id) == "needs_reconciliation"
    operation_id = service.fake.requests[-1][2]
    result = service.fake.objects[service.fake.by_key[operation_id]]
    service.fake.finish_payment()
    amount = result["amount"]
    result["amount"] = {"value": "451.00", "currency": "RUB"}
    with pytest.raises(BankSettlementUnavailable, match="amount or deal"):
        recover(service, checkout_id, operation_id)
    result["amount"] = amount
    actual_deal = result["deal"]["id"]
    result["deal"]["id"] = "unrelated-deal"
    with pytest.raises(BankSettlementUnavailable, match="amount or deal"):
        recover(service, checkout_id, operation_id)
    with Session(engine) as session:
        assert not session.get(Booking, service.booking_id).paid
        assert session.get(RideshareBankOperation, operation_id).provider_id is None
    result["deal"]["id"] = actual_deal
    assert recover(service, checkout_id, operation_id) == "succeeded"


def test_closed_unknown_deal_remains_for_support(bank_checkout):
    service = bank_checkout
    checkout_id = service.begin()
    service.fake.lose_deal_response = True
    assert send(service, checkout_id) == "needs_reconciliation"
    operation_id = service.fake.requests[-1][2]
    service.fake.objects[service.fake.by_key[operation_id]]["status"] = "closed"
    with pytest.raises(BankOutcomeUnknown, match="Closed bank deal"):
        recover(service, checkout_id, operation_id)
    with Session(engine) as session:
        operation = session.get(RideshareBankOperation, operation_id)
        assert operation.state == "needs_reconciliation" and operation.provider_id is None
