"""Protect the banking config from stale flags and cross-account operations."""

import httpx
import pytest
from alembic import command
from cryptography.fernet import Fernet
from pydantic import SecretStr
from sqlalchemy import inspect, text
from sqlmodel import Session

from app.config import Settings, settings
from app.db import engine
from app.owner_switches import PAID_SWITCH
from app.rideshare_bank_booking import BankCheckoutUnavailable, BankRuntime
from app.rideshare_bank_confirmation import seal_bank_confirmation
from app.rideshare_bank_dispatch import dispatch_bank_operation, reconcile_bank_notification, reconcile_bank_operation
from app.rideshare_bank_models import RideshareBankCheckout, RideshareBankOperation
from app.rideshare_bank_provider import BankRequest, BankResource
from app.rideshare_bank_runtime import configure_rideshare_bank
from app.timeutil import utcnow

from test_migration_owner_switches import _isolated_database
from test_rideshare_bank_command import card_path, confirm_path, queued, terms
from test_rideshare_bank_dispatch import bank_checkout as bank_checkout
from test_rideshare_bank_dispatch import send
from test_rideshare_bank_receipt import include_synthetic_receipt as include_synthetic_receipt


def _config(*, enabled=True, shop_id="12345", secret="synthetic-rotated", key=None):
    return Settings(_env_file=None, rideshare_bank_read_enabled=enabled, rideshare_bank_shop_id=shop_id,
                    rideshare_bank_secret=secret, rideshare_bank_fernet_key=key or Fernet.generate_key().decode())


def test_bank_configuration_is_explicit_and_independent_of_legacy_credentials():
    inactive = _config(enabled=False)
    assert configure_rideshare_bank(inactive) is None
    legacy = _config(secret="")
    legacy.yookassa_shop_id = "34567"
    legacy.yookassa_secret_key = "synthetic-legacy-secret"
    assert configure_rideshare_bank(legacy) is None
    assert configure_rideshare_bank(_config(shop_id="123:45")) is None
    assert configure_rideshare_bank(_config(key="not-a-fernet-key")) is None
    configuration = configure_rideshare_bank(_config())
    assert configuration is not None
    assert configuration.runtime.return_url(17) == "https://app.yulbash.ru/receipt/17"
    assert configuration.runtime.provider_account_id == "12345"
    assert "synthetic-rotated" not in repr(configuration)


def test_read_only_runtime_rejects_stale_switches(client, bank_checkout, include_synthetic_receipt):
    service = bank_checkout
    include_synthetic_receipt(service)
    offer = terms(client, service)
    service.runtime = BankRuntime(False, service.runtime.cipher, service.runtime.return_url)
    headers = service.passenger["auth"]
    response = client.get(f"{card_path(service)}/terms", headers=headers)
    assert response.json()["confirmation"] is None
    assert response.json()["blocked_reason"] == "bank_project_unaccepted"
    rejected = client.post(confirm_path(service), headers={**headers, "Idempotency-Key": "cmd1_" + "d" * 32},
                           json=offer)
    assert rejected.status_code == 409 and rejected.json()["detail"]["code"] == "bank_project_unaccepted"
    assert queued(service) == []
    assert service.fake.requests == []


def test_configured_runtime_cannot_resend_a_durably_prepared_request(bank_checkout):
    service = bank_checkout
    checkout_id = service.begin()
    service.fake.lose_deal_response = True
    assert send(service, checkout_id) == "needs_reconciliation"
    _, _, operation_id, wire_body = service.fake.requests[0]
    configuration = configure_rideshare_bank(_config())
    assert configuration is not None
    runtime = configuration.runtime
    with Session(engine) as db:
        checkout = db.get(RideshareBankCheckout, checkout_id)
        checkout.test_mode = False
        checkout.provider_account_id = runtime.provider_account_id
        operation = db.get(RideshareBankOperation, operation_id)
        assert operation.request_ciphertext and operation.provider_id is None
        db.commit()
    with httpx.Client(transport=httpx.MockTransport(service.fake.handle), trust_env=False) as transport:
        provider = configuration.provider(transport)
        with pytest.raises(BankCheckoutUnavailable, match="bank_project_unaccepted"):
            dispatch_bank_operation(engine, checkout_id, runtime=runtime, provider=provider,
                                    expected_operation_id=operation_id)
        with pytest.raises(PermissionError, match="read-only"):
            provider.submit(BankRequest(BankResource.deal, operation_id, wire_body))
    assert len(service.fake.requests) == 1


def test_configured_account_can_read_its_admitted_payment_while_writes_are_closed(client, bank_checkout, monkeypatch):
    service = bank_checkout
    checkout_id = service.begin()
    assert send(service, checkout_id) == "succeeded"
    assert send(service, checkout_id) == "pending"
    operation_id = service.fake.requests[-1][2]
    provider_id = service.fake.by_key[operation_id]
    configuration_settings = _config()
    configuration = configure_rideshare_bank(configuration_settings)
    assert configuration is not None
    runtime = configuration.runtime
    link = "https://yoomoney.ru/checkout/synthetic"
    with Session(engine) as db:
        checkout = db.get(RideshareBankCheckout, checkout_id)
        checkout.test_mode = False
        checkout.provider_account_id = runtime.provider_account_id
        operation = db.get(RideshareBankOperation, operation_id)
        operation.confirmation_ciphertext = seal_bank_confirmation(
            runtime.cipher, checkout_id, operation_id, False, link, account_id=runtime.provider_account_id,
        )
        db.commit()

    def select_config(config):
        monkeypatch.setattr(settings, "rideshare_bank_read_enabled", config.rideshare_bank_read_enabled)
        monkeypatch.setattr(settings, "rideshare_bank_shop_id", config.rideshare_bank_shop_id)
        monkeypatch.setattr(settings, "rideshare_bank_secret", config.rideshare_bank_secret)
        monkeypatch.setattr(settings, "rideshare_bank_fernet_key", config.rideshare_bank_fernet_key)

    requests = []

    def fetch(request):
        requests.append(request)
        assert request.method == "GET"
        assert request.url.path == f"/v3/payments/{provider_id}"
        return httpx.Response(200, json={**service.fake.objects[provider_id], "test": False})

    service.toggle(PAID_SWITCH, False)
    select_config(configuration_settings)
    headers = service.passenger["auth"]
    receipt = card_path(service)
    assert client.get(receipt + "/checkout-link", headers=headers).json() == {"checkout_url": link}
    other_settings = configuration_settings.model_copy(update={"rideshare_bank_shop_id": "56789"})
    other = configure_rideshare_bank(other_settings)
    assert other is not None
    select_config(other_settings)
    assert client.get(receipt + "/checkout-link", headers=headers).json() == {"checkout_url": None}
    assert client.get(receipt, headers=headers).json()["payment_status"] == "waiting_bank"
    with httpx.Client(transport=httpx.MockTransport(fetch), trust_env=False) as transport:
        current_provider = configuration.provider(transport)
        wrong_provider = other.provider(transport)
        with pytest.raises(BankCheckoutUnavailable, match="bank_project_unaccepted"):
            dispatch_bank_operation(engine, checkout_id, runtime=runtime, provider=current_provider)
        with pytest.raises(BankCheckoutUnavailable, match="bank_provider_account"):
            reconcile_bank_operation(engine, checkout_id, operation_id, runtime=runtime, provider=wrong_provider)
        with pytest.raises(BankCheckoutUnavailable, match="bank_provider_environment"):
            reconcile_bank_operation(engine, checkout_id, operation_id, runtime=runtime, provider=service.provider)
        assert reconcile_bank_operation(engine, checkout_id, operation_id, runtime=other.runtime,
                                        provider=wrong_provider) is None
        hint = {"type": "notification", "event": "payment.succeeded", "object": {"id": provider_id}}
        assert reconcile_bank_notification(engine, hint, runtime=other.runtime, provider=wrong_provider) is None
        assert not requests

        with Session(engine) as db:
            db.get(RideshareBankCheckout, checkout_id).provider_account_id = None
            db.commit()
        select_config(configuration_settings)
        assert client.get(receipt + "/checkout-link", headers=headers).json() == {"checkout_url": None}
        assert reconcile_bank_operation(engine, checkout_id, operation_id, runtime=runtime,
                                        provider=current_provider) is None
        assert reconcile_bank_notification(engine, hint, runtime=runtime, provider=current_provider) is None
        assert not requests

        with Session(engine) as db:
            db.get(RideshareBankCheckout, checkout_id).provider_account_id = runtime.provider_account_id
            db.commit()
        service.fake.finish_payment(operation_id)
        rotated = configure_rideshare_bank(configuration_settings.model_copy(
            update={"rideshare_bank_secret": SecretStr("synthetic-replacement")},
        ))
        assert rotated is not None
        assert reconcile_bank_notification(engine, hint, runtime=rotated.runtime,
                                           provider=rotated.provider(transport)) == "succeeded"
        assert len(requests) == 1
    assert client.get(receipt, headers=headers).json()["paid_kop"] == 45_000
    assert client.get(receipt + "/checkout-link", headers=headers).json() == {"checkout_url": None}


def test_account_binding_migration_does_not_adopt_old_rows_or_drop_bound_history(tmp_path, monkeypatch):
    db, config = _isolated_database(tmp_path, monkeypatch, "YULDASH_BANK_RUNTIME_MIGRATION_DB", "bank-runtime.sqlite3")
    previous, target = "cx_rideshare_bank_queue", "cy_rideshare_bank_account_binding"
    try:
        command.upgrade(config, previous)
        with db.begin() as session:
            session.execute(text(
                "INSERT INTO ridesharebankcheckout (amount_kop, payout_kop, test_mode, created_at)"
                " VALUES (100, 100, false, :now)"
            ), {"now": utcnow()})
        command.upgrade(config, target)
        with db.begin() as session:
            assert session.execute(text("SELECT provider_account_id FROM ridesharebankcheckout")).one() == (None,)
        command.downgrade(config, previous)
        assert "provider_account_id" not in {row["name"] for row in inspect(db).get_columns("ridesharebankcheckout")}
        command.upgrade(config, target)
        with db.begin() as session:
            session.execute(text("UPDATE ridesharebankcheckout SET provider_account_id = :account"), {"account": "12345"})
        with pytest.raises(RuntimeError, match="Cannot drop the account binding"):
            command.downgrade(config, previous)
        with db.begin() as session:
            assert session.execute(text("SELECT provider_account_id FROM ridesharebankcheckout")).one() == ("12345",)
    finally:
        db.dispose()
