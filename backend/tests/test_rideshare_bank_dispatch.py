import json
from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta
from threading import Barrier
from types import SimpleNamespace

import httpx
import pytest
from alembic import command
from cryptography.fernet import Fernet
from sqlalchemy import delete, inspect, text
from sqlmodel import Session, select

from app import rideshare_bank_dispatch as dispatch
from app.card_payments import CARD_PAYMENTS_SWITCH
from app.config import settings
from app.db import engine
from app.models import AppSetting, AppSettingChange, Booking, BookingStatus, DriverPaySettings, Payment, UserRole
from app.owner_switches import PAID_SWITCH, login_settings
from app.rideshare_bank_booking import BankCheckoutUnavailable, BankRuntime, bank_switches, start_bank_checkout
from app.rideshare_bank_dispatch import claim_bank_dispatch, dispatch_bank_operation, reconcile_bank_operation
from app.rideshare_bank_models import RideshareBankCheckout, RideshareBankOperation, RideshareBankPosting
from app.rideshare_bank_provider import BankObservation, BankResource, YooKassaSafeDeal
from app.rideshare_bank_recipient import save_bank_recipient
from app.timeutil import utcnow
from test_last_seat_race import _ride
from test_migration_owner_switches import _isolated_database


class SyntheticBank:
    def __init__(self):
        self.requests = []
        self.objects = {}
        self.by_key = {}
        self.lookup_pages = {}
        self.lose_deal_response = False
        self.lose_payment_response = False
        self.lose_settlement_response = set()
        self.on_first_deal = None

    def handle(self, request):
        assert request.url.host == "api.yookassa.ru"
        resource, *path = request.url.path.removeprefix("/v3/").split("/")
        if request.method == "GET":
            if not path:
                params = dict(request.url.params)
                self.requests.append(("LIST", resource, params))
                configured = self.lookup_pages.get((resource, params.get("cursor")))
                if configured is not None:
                    return httpx.Response(200, json=configured)
                objects = [value for key, value in self.objects.items() if key.startswith(f"synthetic-{resource}-")]
                return httpx.Response(200, json={"type": "list", "items": objects})
            self.requests.append(("GET", resource, path[0]))
            return httpx.Response(200, json=self.objects[path[0]])
        key = request.headers["Idempotence-Key"]
        self.requests.append(("POST", resource, key, request.content))
        with Session(engine) as db:
            operation = db.get(RideshareBankOperation, key)
            assert operation.dispatched_at and operation.lease_token and operation.request_ciphertext
            assert request.content not in operation.request_ciphertext
        if key in self.by_key:
            return httpx.Response(200, json=self.objects[self.by_key[key]])
        body = json.loads(request.content)
        provider_id = f"synthetic-{resource}-{key}"
        result = {"id": provider_id, "test": True, "metadata": body["metadata"]}
        if resource == "deals":
            result.update(status="opened", type="safe_deal", fee_moment="deal_closed",
                          balance={"currency": "RUB", "value": "0"}, payout_balance={"currency": "RUB", "value": "0"})
        elif resource == "payments":
            result.update(status="pending", paid=False, amount=body["amount"], deal=body["deal"],
                          confirmation={"type": "redirect", "confirmation_url": "https://yoomoney.ru/checkout/synthetic"})
        elif resource == "payouts":
            result.update(status="pending", amount=body["amount"], deal=body["deal"])
        else:
            assert resource == "refunds"
            payment = self.objects[body["payment_id"]]
            reductions = body.get("deal", {}).get("refund_settlements", payment["deal"]["settlements"])
            result.update(status="pending", amount=body["amount"], payment_id=payment["id"],
                          deal={"id": payment["deal"]["id"], "refund_settlements": reductions})
        self.by_key[key] = provider_id
        self.objects[provider_id] = result
        if resource == "deals" and self.on_first_deal is not None:
            self.on_first_deal()
        if resource == "deals" and self.lose_deal_response:
            self.lose_deal_response = False
            raise httpx.ReadTimeout("Synthetic deal response lost")
        if resource == "payments" and self.lose_payment_response:
            self.lose_payment_response = False
            raise httpx.ReadTimeout("Synthetic response lost")
        if resource in self.lose_settlement_response:
            self.lose_settlement_response.remove(resource)
            raise httpx.ReadTimeout("Synthetic settlement response lost")
        return httpx.Response(200, json=result)

    def finish_payment(self):
        payment, = (entry for entry in self.objects.values() if entry["status"] == "pending")
        payment.update(status="succeeded", paid=True, payment_method={"type": "bank_card"})
        payment.pop("confirmation")


@pytest.fixture
def bank_checkout(client, user_factory):
    driver = user_factory("Synthetic driver", role=UserRole.driver, taxi_approved=False)
    passenger = user_factory("Synthetic passenger")
    ride_id = _ride(client, driver)
    created = client.post("/bookings", headers=passenger["auth"], json={"ride_id": ride_id})
    assert created.status_code == 200, created.text
    booking_id = created.json()["id"]
    cipher = Fernet(Fernet.generate_key())
    runtime = BankRuntime(True, cipher, lambda number: f"https://booking.example/trips/{number}/receipt")
    inserted_changes = []

    def toggle(key, value):
        with Session(engine) as db:
            row = login_settings(db, lock="update")[key]
            log = AppSettingChange(key=key, old_value=row.value, new_value=value)
            row.value = value
            db.add(log)
            db.flush()
            inserted_changes.append(log.id)
            db.commit()

    with Session(engine) as db:
        original = {key: db.get(AppSetting, key).value for key in (PAID_SWITCH, CARD_PAYMENTS_SWITCH)}
        booking = db.get(Booking, booking_id)
        booking.status = BookingStatus.done
        booking.pay_amount = 450
        db.add(DriverPaySettings(user_id=driver["id"], accepts_card=True, accepts_card_at=utcnow()))
        version = save_bank_recipient(db, driver["id"], "synthetic-payout-token", expected_version=None, cipher=cipher).version
        db.commit()
    toggle(PAID_SWITCH, True)
    toggle(CARD_PAYMENTS_SWITCH, True)

    def begin(expected=None, *, expected_amount=45_000, selected_version=version, actor=None, selected_runtime=runtime):
        with Session(engine) as db:
            if expected is None:
                expected = bank_switches(db)
            checkout = start_bank_checkout(db, booking_id, passenger["id"] if actor is None else actor,
                                           expected_switches=expected, recipient_version=selected_version,
                                           expected_amount_kop=expected_amount, runtime=selected_runtime)
            value = checkout.id
            db.commit()
            return value

    fake = SyntheticBank()
    with httpx.Client(transport=httpx.MockTransport(fake.handle), trust_env=False) as http:
        provider = YooKassaSafeDeal(http, shop_id="synthetic-shop", secret_key="synthetic-secret", test_mode=True)
        try:
            yield SimpleNamespace(
                begin=begin, driver=driver, passenger=passenger, booking_id=booking_id, runtime=runtime,
                provider=provider, fake=fake, toggle=toggle, version=version,
            )
        finally:
            with Session(engine) as db:
                for key, value in original.items():
                    db.get(AppSetting, key).value = value
                db.execute(delete(AppSettingChange).where(AppSettingChange.id.in_(inserted_changes)))
                db.commit()


def send(checkout, checkout_id):
    return dispatch_bank_operation(engine, checkout_id, runtime=checkout.runtime, provider=checkout.provider)


def payment_operation(session, checkout_id):
    return session.exec(select(RideshareBankOperation).where(
        RideshareBankOperation.checkout_id == checkout_id, RideshareBankOperation.resource == BankResource.payment.value,
    )).one()


def test_two_concurrent_confirmations_create_one_checkout(bank_checkout):
    checkout = bank_checkout
    barrier = Barrier(2)

    def begin():
        barrier.wait(timeout=8)
        return checkout.begin()

    with ThreadPoolExecutor(max_workers=2) as pool:
        first, second = [pool.submit(begin) for _ in range(2)]
        checkout_id = first.result(timeout=12)
        assert second.result(timeout=12) == checkout_id
    with Session(engine) as db:
        operations = db.exec(select(RideshareBankOperation).where(RideshareBankOperation.checkout_id == checkout_id)).all()
        assert [(action.resource, action.state) for action in operations] == [("deals", "queued")]
    assert checkout.fake.requests == []


def test_pause_after_deal_cannot_admit_payment_without_new_user_confirmation(bank_checkout):
    checkout = bank_checkout
    with Session(engine) as db:
        initial = bank_switches(db)
    checkout_id = checkout.begin(initial)
    checkout.fake.on_first_deal = lambda: checkout.toggle(PAID_SWITCH, False)
    assert send(checkout, checkout_id) == "succeeded"
    with pytest.raises(BankCheckoutUnavailable, match="bank_paused"):
        send(checkout, checkout_id)
    checkout.toggle(PAID_SWITCH, True)
    with pytest.raises(BankCheckoutUnavailable, match="bank_confirmation_required"):
        send(checkout, checkout_id)
    with pytest.raises(BankCheckoutUnavailable, match="bank_switch_revision_conflict"):
        checkout.begin(initial)
    assert len(checkout.fake.requests) == 1
    assert checkout.begin() == checkout_id
    assert send(checkout, checkout_id) == "pending"
    assert [row[1] for row in checkout.fake.requests] == ["deals", "payments"]


def test_lost_response_reuses_payload_and_paid_receipt_survives_pause(client, bank_checkout):
    checkout = bank_checkout
    with Session(engine) as db:
        original_switches = bank_switches(db)
    checkout_id = checkout.begin(original_switches)
    assert send(checkout, checkout_id) == "succeeded"
    checkout.fake.lose_payment_response = True
    assert send(checkout, checkout_id) == "needs_reconciliation"
    base = f"/bookings/{checkout.booking_id}"
    auth = checkout.passenger["auth"]
    assert client.post(f"{base}/pay", headers=auth, json={"method": "cash"}).status_code == 409
    assert client.post(f"{base}/pay-agreement", headers=auth, json={"pay_amount": 451}).status_code == 409
    with Session(engine) as db:
        operation = payment_operation(db, checkout_id)
        submitted_at, operation_id = operation.dispatched_at, operation.id
        encrypted = operation.request_ciphertext
        assert operation.provider_id is None
    checkout.runtime = BankRuntime(True, checkout.runtime.cipher, lambda _: "https://booking.example/new-path")
    assert send(checkout, checkout_id) == "pending"
    assert checkout.fake.requests[-1] == checkout.fake.requests[-2]
    assert len(checkout.fake.objects) == 2
    checkout.toggle(PAID_SWITCH, False)
    checkout.fake.finish_payment()
    for _ in range(2):
        assert reconcile_bank_operation(engine, checkout_id, operation_id, runtime=checkout.runtime,
                                        provider=checkout.provider) == "succeeded"
    with Session(engine) as db:
        operation = payment_operation(db, checkout_id)
        assert (operation.dispatched_at, operation.request_ciphertext) == (submitted_at, encrypted)
        assert operation.confirmation_ciphertext is None
        assert db.get(RideshareBankPosting, operation_id).amount_kop == 45_000
        assert not db.exec(select(Payment).where(Payment.booking_id == checkout.booking_id)).all()
        booking = db.get(Booking, checkout.booking_id)
        assert booking.paid and booking.payment_method == "rideshare_card"
    assert checkout.begin(original_switches) == checkout_id
    assert [row[0] for row in checkout.fake.requests] == ["POST", "POST", "POST", "GET", "GET"]


def test_cash_and_dispatch_race_allows_only_one_payment_path(client, bank_checkout):
    checkout = bank_checkout
    checkout_id = checkout.begin()
    assert send(checkout, checkout_id) == "succeeded"
    barrier = Barrier(2)

    def cash():
        barrier.wait(timeout=8)
        return client.post(f"/bookings/{checkout.booking_id}/pay", headers=checkout.passenger["auth"],
                           json={"method": "cash"}).status_code

    def card():
        barrier.wait(timeout=8)
        try:
            return send(checkout, checkout_id)
        except BankCheckoutUnavailable as exc:
            return exc.code

    with ThreadPoolExecutor(max_workers=2) as pool:
        cash_result, card_result = pool.submit(cash), pool.submit(card)
        outcome = cash_result.result(timeout=12), card_result.result(timeout=12)
    assert outcome in {(200, "bank_booking_unavailable"), (409, "pending")}
    assert len(checkout.fake.requests) == (1 if outcome[0] == 200 else 2)


def test_lease_takeover_rejects_a_stale_result_and_an_expired_key(bank_checkout, monkeypatch):
    checkout = bank_checkout
    checkout_id = checkout.begin()
    now = [utcnow()]
    monkeypatch.setattr(dispatch, "utcnow", lambda: now[0])
    with Session(engine) as db:
        first = claim_bank_dispatch(db, checkout_id, runtime=checkout.runtime)
        db.commit()
    now[0] = first.send_before + timedelta(seconds=1)
    with Session(engine) as db:
        second = claim_bank_dispatch(db, checkout_id, runtime=checkout.runtime)
        db.commit()
    assert first.lease_token != second.lease_token and first.request == second.request
    observation = BankObservation(BankResource.deal, "synthetic-stale-deal", "opened", first.operation_id, True)
    with Session(engine) as db:
        assert dispatch._apply_bank_result(db, checkout_id, first.operation_id, checkout.runtime, observation,
                                           lease_token=first.lease_token) is None
        db.commit()
        operation = db.get(RideshareBankOperation, first.operation_id)
        assert operation.provider_id is None and operation.lease_token == second.lease_token
    now[0] += timedelta(hours=24)
    assert send(checkout, checkout_id) is None and checkout.fake.requests == []
    with Session(engine) as db:
        assert db.get(RideshareBankOperation, first.operation_id).state == "needs_reconciliation"


def test_send_rechecks_deadline_after_the_admission_commit(bank_checkout, monkeypatch):
    checkout = bank_checkout
    checkout_id = checkout.begin()
    real_claim = dispatch.claim_bank_dispatch
    now = [utcnow()]
    monkeypatch.setattr(dispatch, "utcnow", lambda: now[0])

    def delayed_claim(*args, **kwargs):
        result = real_claim(*args, **kwargs)
        now[0] = result.send_before
        return result

    monkeypatch.setattr(dispatch, "claim_bank_dispatch", delayed_claim)
    assert send(checkout, checkout_id) is None and checkout.fake.requests == []


def test_closed_gates_and_wrong_actor_do_not_create_or_send_payment(client, bank_checkout, monkeypatch):
    checkout = bank_checkout
    with pytest.raises(BankCheckoutUnavailable, match="bank_booking_unavailable"):
        checkout.begin(actor=checkout.driver["id"])
    with monkeypatch.context() as patch:
        patch.setattr(settings, "env", "production")
        with pytest.raises(BankCheckoutUnavailable, match="bank_test_in_production"):
            checkout.begin()
    checkout_id = checkout.begin()
    assert send(checkout, checkout_id) == "succeeded"
    with Session(engine) as db:
        save_bank_recipient(db, checkout.driver["id"], "synthetic-new-token",
                            expected_version=checkout.version, cipher=checkout.runtime.cipher)
        db.commit()
    with pytest.raises(BankCheckoutUnavailable, match="bank_recipient_changed"):
        send(checkout, checkout_id)
    assert len(checkout.fake.requests) == 1
    response = client.post(f"/bookings/{checkout.booking_id}/pay", headers=checkout.passenger["auth"],
                           json={"method": "cash"})
    assert response.status_code == 200


def test_changed_terms_keep_unknown_deal_and_require_current_confirmed_amount(bank_checkout):
    checkout = bank_checkout
    first = checkout.begin()
    checkout.fake.lose_deal_response = True
    assert send(checkout, first) == "needs_reconciliation"
    with Session(engine) as db:
        version = save_bank_recipient(db, checkout.driver["id"], "synthetic-new-token",
                                      expected_version=checkout.version, cipher=checkout.runtime.cipher).version
        db.get(Booking, checkout.booking_id).pay_amount = 500
        db.commit()
    with pytest.raises(BankCheckoutUnavailable, match="bank_amount_changed"):
        checkout.begin(selected_version=version)
    assert checkout.begin(selected_version=version, expected_amount=50_000) == first
    with Session(engine) as db:
        row = db.get(RideshareBankCheckout, first)
        assert row.closed_at is None and row.recipient_version == version
    assert send(checkout, first) == "succeeded"
    assert send(checkout, first) == "pending"
    deal, replay, debit = checkout.fake.requests
    assert deal == replay
    assert debit[:2] == ("POST", "payments")
    assert json.loads(debit[3])["amount"] == {"value": "500.00", "currency": "RUB"}
    assert sum(entry["status"] == "opened" for entry in checkout.fake.objects.values()) == 1


def test_renewed_driver_consent_requires_passenger_confirmation(bank_checkout):
    checkout = bank_checkout
    checkout_id = checkout.begin()
    assert send(checkout, checkout_id) == "succeeded"
    with Session(engine) as db:
        consent = db.exec(select(DriverPaySettings).where(DriverPaySettings.user_id == checkout.driver["id"])).one()
        consent.accepts_card_at += timedelta(seconds=1)
        db.commit()
    with pytest.raises(BankCheckoutUnavailable, match="bank_checkout_changed"):
        send(checkout, checkout_id)
    assert checkout.begin() == checkout_id
    assert send(checkout, checkout_id) == "pending"
    assert [request[1] for request in checkout.fake.requests] == ["deals", "payments"]


def test_dispatch_schema_upgrade_from_old_columns(tmp_path, monkeypatch):
    db, config = _isolated_database(tmp_path, monkeypatch, "YULDASH_BANK_DISPATCH_MIGRATION_DB", "bank-dispatch.sqlite3")
    previous, target = "cu_rideshare_bank_recipient", "cv_rideshare_bank_dispatch"
    columns = {
        "ridesharebankcheckout": {"paid_revision", "card_revision", "recipient_version", "driver_consent_at", "receipt_conflict"},
        "ridesharebankoperation": {"request_ciphertext", "confirmation_ciphertext", "lease_token", "lease_until"},
    }
    try:
        command.upgrade(config, previous)
        with db.begin() as connection:
            for table, added in columns.items():
                existing = {row["name"] for row in inspect(connection).get_columns(table)}
                for column in sorted(added & existing):
                    connection.execute(text(f"ALTER TABLE {table} DROP COLUMN {column}"))
        command.upgrade(config, target)
        for table, added in columns.items():
            assert added <= {row["name"] for row in inspect(db).get_columns(table)}
        with Session(db) as session:
            session.add(RideshareBankCheckout(amount_kop=45_000, payout_kop=45_000, test_mode=True))
            session.commit()
        with pytest.raises(RuntimeError, match="must be preserved"):
            command.downgrade(config, previous)
        with Session(db) as session:
            assert session.exec(select(RideshareBankCheckout)).one().amount_kop == 45_000
    finally:
        db.dispose()
