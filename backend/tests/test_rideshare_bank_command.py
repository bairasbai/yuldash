"""Confirm a bank checkout through the shared client API using synthetic sessions."""

from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta
from threading import Barrier

from sqlmodel import Session, select

from app import rideshare_bank_booking
from app.card_payments import CARD_PAYMENTS_SWITCH
from app.db import engine
from app.main import app
from app.models import Booking, DriverPaySettings
from app.owner_switches import PAID_SWITCH
from app.rideshare_bank_dispatch import reconcile_bank_operation
from app.rideshare_bank_models import RideshareBankCheckout, RideshareBankOperation
from app.routers.rideshare_bank import receipt_bank_runtime
from app.security import issue_tokens, revoke_auth_session
from app.timeutil import utcnow
from test_rideshare_bank_dispatch import bank_checkout as bank_checkout
from test_rideshare_bank_dispatch import send
from test_rideshare_bank_receipt import include_synthetic_receipt as include_synthetic_receipt


def card_path(service):
    return f"/bookings/{service.booking_id}/card-payment"


def new_session(user_id):
    with Session(engine) as db:
        pair = issue_tokens(db, user_id, auth_method="sms", auth_time=utcnow())
    return {"Authorization": f"Bearer {pair['access_token']}"}, pair["logout_token"]


def queued(service):
    with Session(engine) as db:
        return db.exec(select(RideshareBankCheckout).where(RideshareBankCheckout.booking_id == service.booking_id)).all()


def terms(client, service):
    result = client.get(f"{card_path(service)}/terms", headers=service.passenger["auth"])
    assert result.status_code == 200
    return result.json()["confirmation"]


def test_card_api_requires_approved_runtime_and_one_strict_confirmation(client, bank_checkout,
                                                                        include_synthetic_receipt, monkeypatch):
    service = bank_checkout
    assert terms(client, service) is None
    include_synthetic_receipt(service)
    offer = terms(client, service)
    assert offer["amount_kop"] == 45_000 and offer["retry_after_payment_id"] is None
    path = f"{card_path(service)}/confirm"
    with monkeypatch.context() as closed:
        closed.setitem(app.dependency_overrides, receipt_bank_runtime, lambda: None)
        result = client.post(path, headers=service.passenger["auth"], json=offer)
        assert result.status_code == 409 and result.json()["detail"]["code"] == "bank_project_unaccepted"
    assert client.post(path, headers=service.driver["auth"], json=offer).status_code == 404
    assert client.post(path, headers=service.passenger["auth"], json={**offer, "amount_kop": True}).status_code == 422
    assert client.post(path, headers=service.passenger["auth"], json={**offer, "recipient_version": "%"}).status_code == 422
    assert len(queued(service)) == 0 and service.fake.requests == []


def test_two_client_sessions_confirm_and_replay_one_bank_checkout(client, bank_checkout, include_synthetic_receipt):
    service = bank_checkout
    include_synthetic_receipt(service)
    offer = terms(client, service)
    first, _ = new_session(service.passenger["id"])
    second, _ = new_session(service.passenger["id"])
    barrier = Barrier(2)

    def confirm(headers):
        barrier.wait(timeout=8)
        return client.post(f"{card_path(service)}/confirm", headers=headers, json=offer)

    with ThreadPoolExecutor(max_workers=2) as workers:
        outcomes = [workers.submit(confirm, headers) for headers in (first, second)]
        assert [outcome.result(timeout=12).status_code for outcome in outcomes] == [200, 200]
    checkout, = queued(service)
    assert service.fake.requests == []
    assert send(service, checkout.id) == "succeeded"
    assert send(service, checkout.id) == "pending"
    result = client.get(card_path(service), headers=second).json()
    assert result["payment_status"] == "waiting_bank" and result["paid_kop"] == 0


def test_changed_terms_and_pause_require_a_fresh_confirmation(client, bank_checkout, include_synthetic_receipt):
    service = bank_checkout
    include_synthetic_receipt(service)
    offer = terms(client, service)
    path = f"{card_path(service)}/confirm"

    def check_conflict(snapshot, code):
        reply = client.post(path, headers=service.passenger["auth"], json=snapshot)
        assert reply.status_code == 409 and reply.json()["detail"]["code"] == code
        assert queued(service) == [] and service.fake.requests == []

    with Session(engine) as db:
        consent = db.exec(select(DriverPaySettings).where(DriverPaySettings.user_id == service.driver["id"])).one()
        consent.accepts_card_at += timedelta(seconds=1)
        db.commit()
    check_conflict(offer, "bank_driver_consent_changed")
    offer = terms(client, service)
    with Session(engine) as db:
        db.get(Booking, service.booking_id).pay_amount = 500
        db.commit()
    check_conflict(offer, "bank_amount_changed")
    offer = terms(client, service)
    service.toggle(PAID_SWITCH, False)
    check_conflict(offer, "bank_paused")
    assert terms(client, service) is None
    service.toggle(PAID_SWITCH, True)
    check_conflict(offer, "bank_switch_revision_conflict")
    offer = terms(client, service)
    service.toggle(CARD_PAYMENTS_SWITCH, False)
    check_conflict(offer, "bank_paused")


def test_explicit_retry_is_available_only_after_bank_confirmed_cancellation(client, bank_checkout,
                                                                               include_synthetic_receipt):
    service = bank_checkout
    include_synthetic_receipt(service)
    initial = terms(client, service)
    path = f"{card_path(service)}/confirm"
    assert client.post(path, headers=service.passenger["auth"], json=initial).status_code == 200
    checkout, = queued(service)
    assert send(service, checkout.id) == "succeeded"
    assert send(service, checkout.id) == "pending"
    _, _, payment, _ = service.fake.requests[-1]
    assert terms(client, service)["retry_after_payment_id"] is None
    service.fake.objects[service.fake.by_key[payment]]["status"] = "canceled"
    assert reconcile_bank_operation(engine, checkout.id, payment, runtime=service.runtime,
                                    provider=service.provider) == "canceled"
    assert client.post(path, headers=service.passenger["auth"], json=initial).status_code == 200
    assert send(service, checkout.id) is None
    retry = terms(client, service)
    assert retry["retry_after_payment_id"] == payment
    for _ in range(2):
        assert client.post(path, headers=service.passenger["auth"], json=retry).status_code == 200
    assert send(service, checkout.id) == "pending"
    with Session(engine) as db:
        payments = db.exec(select(RideshareBankOperation).where(
            RideshareBankOperation.checkout_id == checkout.id, RideshareBankOperation.resource == "payments",
        )).all()
        assert sorted(row.state for row in payments) == ["canceled", "pending"]


def test_card_api_rechecks_session_revoked_before_the_participant_lock(client, bank_checkout,
                                                                          include_synthetic_receipt, monkeypatch):
    service = bank_checkout
    include_synthetic_receipt(service)
    offer = terms(client, service)
    headers, ticket = new_session(service.passenger["id"])
    actual_lock = rideshare_bank_booking.lock_booking_participants

    def revoke_before_lock(*args, **kwargs):
        with Session(engine) as db:
            assert revoke_auth_session(db, ticket)
        return actual_lock(*args, **kwargs)

    monkeypatch.setattr(rideshare_bank_booking, "lock_booking_participants", revoke_before_lock)
    result = client.post(f"{card_path(service)}/confirm", headers=headers, json=offer)
    assert result.status_code == 401
    assert queued(service) == [] and service.fake.requests == []
