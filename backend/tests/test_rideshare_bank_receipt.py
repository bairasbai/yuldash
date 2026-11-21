"""Exercise the participant receipt through the real ASGI API with synthetic money."""

import pytest
from sqlalchemy import event
from sqlmodel import Session

from app.config import settings
from app.db import engine
from app.main import app
from app.models import Booking, Ride, RideCategory
from app.owner_switches import PAID_SWITCH
from app.rideshare_bank_provider import BankResource
from app.routers.rideshare_bank import receipt_bank_runtime
from test_rideshare_bank_dispatch import bank_checkout as bank_checkout
from test_rideshare_bank_dispatch import send
from test_rideshare_bank_settlement import authorize, dispatch, observe
from test_rideshare_bank_settlement import paid_checkout as paid_checkout


@pytest.fixture
def include_synthetic_receipt(monkeypatch):
    def enable(service):
        monkeypatch.setitem(app.dependency_overrides, receipt_bank_runtime, lambda: service.runtime)
    return enable


def get_receipt(client, service, actor=None):
    return client.get(f"/bookings/{service.booking_id}/card-payment",
                      headers=(actor or service.passenger)["auth"])


def test_receipt_filters_environment_and_participants_before_showing_amount(client, bank_checkout, user_factory,
                                                                                include_synthetic_receipt, monkeypatch):
    service = bank_checkout
    outsider = user_factory("Synthetic outsider")
    assert get_receipt(client, service).json()["payment_status"] == "unavailable"
    checkout_id = service.begin()
    public = client.get(f"/bookings/{service.booking_id}/card-payment?test_mode=true",
                        headers=service.passenger["auth"])
    assert public.status_code == 200 and public.json()["amount_kop"] is None
    include_synthetic_receipt(service)
    reply = get_receipt(client, service)
    assert reply.status_code == 200 and reply.headers["Cache-Control"] == "no-store"
    assert reply.json()["payment_status"] == "queued" and reply.json()["amount_kop"] == 45_000

    inaccessible = get_receipt(client, service, outsider)
    missing = client.get("/bookings/2147483647/card-payment", headers=service.passenger["auth"])
    assert inaccessible.status_code == missing.status_code == 404
    assert inaccessible.json() == missing.json()
    with monkeypatch.context() as prod:
        prod.setattr(settings, "env", "production")
        assert get_receipt(client, service).json()["amount_kop"] is None
    assert send(service, checkout_id) == "succeeded"
    assert send(service, checkout_id) == "pending"
    assert get_receipt(client, service).json()["payment_status"] == "waiting_bank"
    with Session(engine) as session:
        booking = session.get(Booking, service.booking_id)
        session.get(Ride, booking.ride_id).category = RideCategory.parcel
        session.commit()
    assert get_receipt(client, service).json() == missing.json()


def test_both_participants_read_confirmed_refund_and_only_driver_can_see_payout(client, paid_checkout,
                                                                               include_synthetic_receipt):
    service, _, _ = paid_checkout
    include_synthetic_receipt(service)
    service.toggle(PAID_SWITCH, False)
    refund = authorize(paid_checkout, BankResource.refund, 10_000)
    service.fake.lose_settlement_response.add("refunds")
    assert dispatch(paid_checkout, refund) == "needs_reconciliation"
    assert get_receipt(client, service).json()["refund_status"] == "reconciling"
    assert get_receipt(client, service).json()["refunded_kop"] == 0
    assert dispatch(paid_checkout, refund) == "pending"
    service.fake.objects[service.fake.by_key[refund]]["status"] = "succeeded"
    assert observe(paid_checkout, refund) == "succeeded"

    statements = []

    def capture(_conn, _cursor, statement, _params, _context, _many):
        statements.append(statement.lstrip().split(maxsplit=1)[0])

    before = len(service.fake.requests)
    event.listen(engine, "before_cursor_execute", capture)
    try:
        payer = get_receipt(client, service).json()
        driver = get_receipt(client, service, service.driver).json()
        assert get_receipt(client, service).json() == payer
    finally:
        event.remove(engine, "before_cursor_execute", capture)
    assert statements and set(statements) == {"SELECT"}
    assert len(service.fake.requests) == before
    for result in (payer, driver):
        assert (result["payment_status"], result["paid_kop"], result["refund_status"],
                result["refunded_kop"], result["refund_coverage"]) == ("succeeded", 45_000, "succeeded", 10_000, "partial")
    assert (payer["payout_status"], payer["paid_out_kop"], payer["payout_remaining_kop"]) == (None, None, None)
    assert (driver["payout_status"], driver["paid_out_kop"], driver["payout_remaining_kop"]) == ("not_started", 0, 35_000)

    service.toggle(PAID_SWITCH, True)
    payout = authorize(paid_checkout, BankResource.payout, 35_000)
    assert dispatch(paid_checkout, payout) == "pending"
    assert get_receipt(client, service, service.driver).json()["payout_status"] == "waiting_bank"
    service.fake.objects[service.fake.by_key[payout]]["status"] = "succeeded"
    assert observe(paid_checkout, payout) == "succeeded"
    settled = get_receipt(client, service, service.driver).json()
    assert (settled["payout_status"], settled["paid_out_kop"], settled["payout_remaining_kop"]) == ("succeeded", 35_000, 0)
    assert get_receipt(client, service).json() == payer


def test_receipt_shows_full_refund_from_confirmed_posting(client, paid_checkout, include_synthetic_receipt):
    service, _, _ = paid_checkout
    include_synthetic_receipt(service)
    refund = authorize(paid_checkout, BankResource.refund, 45_000)
    assert dispatch(paid_checkout, refund) == "pending"
    assert get_receipt(client, service).json()["refund_coverage"] == "none"
    service.fake.objects[service.fake.by_key[refund]]["status"] = "succeeded"
    assert observe(paid_checkout, refund) == "succeeded"
    result = get_receipt(client, service, service.driver).json()
    assert (result["refund_coverage"], result["refunded_kop"], result["payout_remaining_kop"]) == ("full", 45_000, 0)
