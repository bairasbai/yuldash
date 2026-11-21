"""A bank confirmation link is visible only for its admitted passenger operation."""

import pytest
from sqlmodel import Session

from app.config import settings
from app.db import engine
from app.owner_switches import PAID_SWITCH
from app.rideshare_bank_confirmation import seal_bank_confirmation
from app.rideshare_bank_dispatch import reconcile_bank_operation
from app.rideshare_bank_models import RideshareBankCheckout, RideshareBankOperation
from app.timeutil import utcnow
from test_rideshare_bank_command import card_path, new_session, terms
from test_rideshare_bank_dispatch import bank_checkout as bank_checkout
from test_rideshare_bank_dispatch import send
from test_rideshare_bank_receipt import include_synthetic_receipt as include_synthetic_receipt


_LINK = "https://yoomoney.ru/checkout/synthetic"


def _link(client, service, headers=None):
    return client.get(card_path(service) + "/checkout-link", headers=headers or service.passenger["auth"])


def _pending(service):
    checkout = service.begin()
    assert send(service, checkout) == "succeeded"
    assert send(service, checkout) == "pending"
    payment = service.fake.requests[-1][2]
    return checkout, payment


@pytest.mark.parametrize("terminal", ["canceled", "succeeded"])
def test_only_passenger_can_read_admitted_link_even_on_pause(client, bank_checkout, include_synthetic_receipt,
                                                            user_factory, terminal, monkeypatch):
    service = bank_checkout
    include_synthetic_receipt(service)
    stranger = user_factory("Synthetic stranger")
    assert _link(client, service).json() == {"checkout_url": None}
    checkout, operation = _pending(service)
    service.toggle(PAID_SWITCH, False)
    before = len(service.fake.requests)
    fresh_session, _ = new_session(service.passenger["id"])
    response = _link(client, service, fresh_session)
    assert response.headers["Cache-Control"] == "no-store"
    assert response.json() == {"checkout_url": _LINK}
    receipt_path = card_path(service)
    assert client.get(receipt_path, headers=fresh_session).json()["payment_attempt_id"] == operation
    assert client.get(receipt_path, headers=service.driver["auth"]).json()["payment_attempt_id"] is None
    assert client.get(receipt_path + "/checkout-link", headers=fresh_session,
                      params={"expected_payment_id": operation}).json() == {"checkout_url": _LINK}
    assert client.get(receipt_path + "/checkout-link", headers=fresh_session,
                      params={"expected_payment_id": "0" * 32}).json() == {"checkout_url": None}
    for actor in (service.driver, stranger):
        assert _link(client, service, actor["auth"]).status_code == 404
    with monkeypatch.context() as prod:
        prod.setattr(settings, "env", "production")
        assert _link(client, service).json() == {"checkout_url": None}
    assert len(service.fake.requests) == before
    if terminal == "succeeded":
        service.fake.finish_payment(operation)
    else:
        service.fake.objects[service.fake.by_key[operation]]["status"] = terminal
    assert reconcile_bank_operation(engine, checkout, operation, runtime=service.runtime,
                                    provider=service.provider) == terminal
    assert _link(client, service).json() == {"checkout_url": None}
    with Session(engine) as session:
        assert session.get(RideshareBankOperation, operation).confirmation_ciphertext is None
    if terminal == "canceled":
        service.toggle(PAID_SWITCH, True)
        response = client.post(receipt_path + "/confirm", headers=fresh_session, json=terms(client, service))
        assert response.status_code == 200
        assert send(service, checkout) == "pending"
        next_attempt = client.get(receipt_path, headers=fresh_session).json()["payment_attempt_id"]
        assert next_attempt is not None and next_attempt != operation
        assert client.get(receipt_path + "/checkout-link", headers=fresh_session,
                          params={"expected_payment_id": operation}).json() == {"checkout_url": None}
        assert client.get(receipt_path + "/checkout-link", headers=fresh_session,
                          params={"expected_payment_id": next_attempt}).json() == {"checkout_url": _LINK}


def test_link_ciphertext_is_bound_and_legacy_recovery_uses_verified_get(client, bank_checkout,
                                                                      include_synthetic_receipt):
    service = bank_checkout
    include_synthetic_receipt(service)
    checkout, operation = _pending(service)
    cipher = service.runtime.cipher
    foreign = seal_bank_confirmation(cipher, checkout + 1, operation, True, _LINK)
    wrong_mode = seal_bank_confirmation(cipher, checkout, operation, False, _LINK)
    wrong_host = cipher.encrypt(
        f"rideshare-confirmation-v1\0{checkout}\0{operation}\0{1}\0https://yoomoney.ru.invalid/checkout".encode("ascii"),
    )
    before = len(service.fake.requests)
    for stale in (foreign, wrong_mode, wrong_host, cipher.encrypt(_LINK.encode()), b"corrupt"):
        with Session(engine) as session:
            session.get(RideshareBankOperation, operation).confirmation_ciphertext = stale
            session.commit()
        assert _link(client, service).json() == {"checkout_url": None}
    assert len(service.fake.requests) == before
    assert reconcile_bank_operation(engine, checkout, operation, runtime=service.runtime,
                                    provider=service.provider) == "pending"
    assert _link(client, service).json() == {"checkout_url": _LINK}
    with Session(engine) as session:
        session.get(RideshareBankCheckout, checkout).closed_at = utcnow()
        session.commit()
    assert _link(client, service).json() == {"checkout_url": None}


def test_new_pending_observation_without_a_redirect_withdraws_previous_link(
        client, bank_checkout, include_synthetic_receipt):
    service = bank_checkout
    include_synthetic_receipt(service)
    checkout, operation = _pending(service)
    assert _link(client, service).json() == {"checkout_url": _LINK}
    service.fake.objects[service.fake.by_key[operation]].pop("confirmation")
    assert reconcile_bank_operation(engine, checkout, operation, runtime=service.runtime,
                                    provider=service.provider) == "pending"
    assert _link(client, service).json() == {"checkout_url": None}
