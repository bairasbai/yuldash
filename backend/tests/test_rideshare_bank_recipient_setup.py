from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace
from datetime import timedelta
from threading import Barrier
from uuid import uuid4

import pytest
from alembic import command
from sqlalchemy import inspect, text
from sqlmodel import Session

from app.cleanup import _delete_batched, _rules
from app.config import settings
from app.db import engine
from app.models import UserRole
from app.owner_switches import PAID_SWITCH
from app.rideshare_bank_models import RideshareBankCheckout, RideshareBankRecipient, RideshareBankRecipientSetup
from app.rideshare_bank_recipient import BankRecipientUnavailable, bank_recipient_token
from app.routers.rideshare_bank import receipt_bank_runtime
from app.security import issue_tokens
from app.timeutil import utcnow
from test_migration_owner_switches import _isolated_database
from test_rideshare_bank_dispatch import bank_checkout as bank_checkout
from test_rideshare_bank_receipt import include_synthetic_receipt as include_synthetic_receipt


PATH = "/api/v1/driver/bank-recipient"


def _new_session(user):
    with Session(engine) as db:
        token = issue_tokens(db, user["id"], auth_method="telegram", auth_time=utcnow())["access_token"]
    return {"Authorization": f"Bearer {token}"}


def _begin(client, auth, version):
    response = client.post(f"{PATH}/setup", headers=auth, json={"version": version})
    assert response.status_code == 200, response.text
    assert response.json()["mode"] == "synthetic" and response.json()["version"] == version
    return f"{PATH}/setup/{response.json()['setup_id']}"


def _confirm(client, path, auth, token, *, consent=True):
    return client.post(f"{path}/confirm", headers=auth, json={"token": token, "consent": consent})


def _token():
    return f"synthetic-{uuid4().hex}"


@pytest.fixture
def setup(bank_checkout, include_synthetic_receipt):
    include_synthetic_receipt(bank_checkout)
    return bank_checkout, _new_session(bank_checkout.driver)


def test_confirmed_attempt_is_private_and_replayable_only_for_the_exact_command(client, setup, user_factory):
    service, auth = setup
    original = client.get(PATH, headers=auth)
    assert original.json()["state"] == "configured" and original.headers["Cache-Control"] == "no-store"
    path = _begin(client, auth, original.json()["version"])
    assert _begin(client, auth, original.json()["version"]) == path
    assert client.get(path, headers=auth).json() is None
    assert client.get(f"{path}/receipt", headers=auth).json() == {"confirmation": None}
    token = _token()
    assert _confirm(client, path, auth, token, consent=False).status_code == 422
    assert _confirm(client, path, _new_session(service.driver), token).status_code == 404
    unrelated = _new_session(user_factory(role=UserRole.driver, taxi_approved=False))
    assert client.get(path, headers=unrelated).status_code == 404
    assert client.get(f"{path}/receipt", headers=unrelated).status_code == 404
    assert _confirm(client, path, unrelated, token).status_code == 404
    assert client.get(PATH, headers=service.passenger["auth"]).status_code == 403

    first = _confirm(client, path, auth, token)
    assert first.status_code == 200 and token not in first.text
    assert set(first.json()) == {"version", "confirmed_at"}
    assert first.json()["version"] != original.json()["version"]
    service.toggle(PAID_SWITCH, False)
    assert client.get(path, headers=auth).json() == first.json()
    assert client.get(f"{path}/receipt", headers=auth).json() == {"confirmation": first.json()}
    assert _confirm(client, path, auth, token).json() == first.json()
    assert _confirm(client, path, auth, _token()).status_code == 409
    assert client.get(PATH, headers=auth).json()["state"] == "disabled"
    with Session(engine) as db:
        attempt = db.get(RideshareBankRecipientSetup, path.rsplit("/", 1)[-1])
        assert attempt.result_version == first.json()["version"]
        assert token not in attempt.command_digest
        assert bank_recipient_token(db, service.driver["id"], attempt.result_version,
                                    cipher=service.runtime.cipher, scope=service.runtime.recipient_scope) == token


def test_scope_or_expiry_change_rejects_pending_setup(client, setup, monkeypatch):
    service, auth = setup
    path = _begin(client, auth, service.version)
    with monkeypatch.context() as patch:
        patch.setitem(client.app.dependency_overrides, receipt_bank_runtime,
                      lambda: replace(service.runtime, provider_account_id="another-shop"))
        state = client.get(PATH, headers=auth).json()
        assert state["state"] == "requires_reconnect" and state["can_revoke"] is True
        assert _confirm(client, path, auth, _token()).status_code == 409
    with Session(engine) as db:
        row = db.get(RideshareBankRecipientSetup, path.rsplit("/", 1)[-1])
        row.expires_at = utcnow() - timedelta(seconds=1)
        db.commit()
    assert _confirm(client, path, auth, _token()).status_code == 409
    keeps = _begin(client, auth, service.version)
    assert keeps != path
    service.toggle(PAID_SWITCH, False)
    service.toggle(PAID_SWITCH, True)
    assert _confirm(client, keeps, auth, _token()).status_code == 409


def test_legacy_binding_can_be_revoked_on_pause_and_requires_reconnection(client, setup):
    service, auth = setup
    with Session(engine) as db:
        current = db.get(RideshareBankRecipient, service.driver["id"])
        current.token_ciphertext = service.runtime.cipher.encrypt(b"legacy-synthetic-token")
        db.commit()
    assert client.get(PATH, headers=auth).json()["state"] == "requires_reconnect"
    service.toggle(PAID_SWITCH, False)
    blocked = client.post(f"{PATH}/setup", headers=auth, json={"version": service.version})
    assert blocked.status_code == 409
    revoked = client.post(f"{PATH}/revoke", headers=auth, json={"version": service.version})
    assert revoked.status_code == 200
    assert revoked.json()["state"] == "disabled" and revoked.json()["can_revoke"] is False
    assert revoked.json()["version"] != service.version
    with Session(engine) as db:
        assert db.get(RideshareBankRecipient, service.driver["id"]).token_ciphertext is None


def test_production_and_inactive_login_cannot_start_synthetic_setup(client, setup, monkeypatch):
    service, auth = setup
    missing_session = client.post(f"{PATH}/setup", headers=service.driver["auth"], json={"version": service.version})
    assert missing_session.status_code == 401
    path = _begin(client, auth, service.version)
    monkeypatch.setattr(settings, "env", "production")
    assert client.post(f"{PATH}/setup", headers=auth, json={"version": service.version}).status_code == 409
    assert client.get(path, headers=auth).status_code == 404
    assert _confirm(client, path, auth, _token()).status_code == 404


def test_only_one_of_two_confirmations_for_the_same_version_can_win(client, setup):
    service, auth = setup
    other_auth = _new_session(service.driver)
    first_path = _begin(client, auth, service.version)
    other_path = _begin(client, other_auth, service.version)
    assert first_path != other_path
    barrier = Barrier(2)

    def race(path, headers):
        barrier.wait(timeout=10)
        return _confirm(client, path, headers, _token())

    with ThreadPoolExecutor(max_workers=2) as pool:
        first = pool.submit(race, first_path, auth)
        second = pool.submit(race, other_path, other_auth)
        results = [first.result(timeout=20), second.result(timeout=20)]
    assert sorted(result.status_code for result in results) == [200, 409]
    winner = next(result for result in results if result.status_code == 200)
    assert client.get(PATH, headers=auth).json()["version"] == winner.json()["version"]


def test_revoke_and_replace_race_never_overwrites_the_new_version(client, setup):
    service, auth = setup
    path = _begin(client, auth, service.version)
    barrier = Barrier(2)

    def confirm():
        barrier.wait(timeout=10)
        return _confirm(client, path, auth, _token())

    def revoke():
        barrier.wait(timeout=10)
        return client.post(f"{PATH}/revoke", headers=auth, json={"version": service.version})

    with ThreadPoolExecutor(max_workers=2) as pool:
        confirmed = pool.submit(confirm)
        revoked = pool.submit(revoke)
        confirmation = confirmed.result(timeout=20)
        revocation = revoked.result(timeout=20)
    assert sorted((confirmation.status_code, revocation.status_code)) == [200, 409]
    current = client.get(PATH, headers=auth).json()
    winner = confirmation if confirmation.status_code == 200 else revocation
    assert current["version"] == winner.json()["version"]
    assert current["state"] == ("configured" if confirmation.status_code == 200 else "unconfigured")


def test_ciphertext_cannot_be_used_for_another_driver_or_version(client, setup, user_factory):
    service, _ = setup
    other = user_factory(role=UserRole.driver, taxi_approved=False)
    with Session(engine) as db:
        prior = db.get(RideshareBankRecipient, service.driver["id"])
        db.add(RideshareBankRecipient(user_id=other["id"], version=prior.version,
                                      token_ciphertext=prior.token_ciphertext))
        db.flush()
        with pytest.raises(BankRecipientUnavailable):
            bank_recipient_token(db, other["id"], prior.version, cipher=service.runtime.cipher,
                                 scope=service.runtime.recipient_scope)
        prior.version = uuid4().hex
        db.flush()
        with pytest.raises(BankRecipientUnavailable):
            bank_recipient_token(db, service.driver["id"], prior.version, cipher=service.runtime.cipher,
                                 scope=service.runtime.recipient_scope)


def test_setup_migration_preserves_legacy_recipients_and_cascades_from_driver(tmp_path, monkeypatch):
    db, config = _isolated_database(tmp_path, monkeypatch, "YULDASH_BANK_SETUP_MIGRATION_DB", "bank-setup.sqlite3")
    previous, target = "cy_rideshare_bank_account_binding", "cz_rideshare_recipient_setup"
    try:
        command.upgrade(config, previous)
        with db.begin() as connection:
            if inspect(connection).has_table("ridesharebankrecipientsetup"):
                connection.execute(text("DROP TABLE ridesharebankrecipientsetup"))
        command.upgrade(config, target)
        db.dispose()
        foreign_key, = inspect(db).get_foreign_keys("ridesharebankrecipientsetup")
        assert foreign_key["referred_table"] == "user"
        assert foreign_key["options"]["ondelete"] == "CASCADE"
        assert inspect(db).has_table("ridesharebankrecipient")
        command.downgrade(config, previous)
        assert not inspect(db).has_table("ridesharebankrecipientsetup")
        assert inspect(db).has_table("ridesharebankrecipient")
    finally:
        db.dispose()


def test_cleanup_keeps_recoverable_and_referenced_recipient_receipts(user_factory):
    driver_id = user_factory(role=UserRole.driver, taxi_approved=False)["id"]
    now = utcnow()
    old = now - timedelta(days=2)
    current_version, checkout_version, unused_version, recent_version = (uuid4().hex for _ in range(4))
    ids = {}
    with Session(engine) as db:
        db.add(RideshareBankRecipient(user_id=driver_id, version=current_version))
        db.add(RideshareBankCheckout(test_mode=True, amount_kop=100, payout_kop=100, recipient_version=checkout_version,
                                     closed_at=now))
        for name, version, confirmed_at in (
            ("current", current_version, old), ("checkout", checkout_version, old), ("unused", unused_version, old),
            ("recent", recent_version, now - timedelta(hours=1)), ("expired", None, None),
        ):
            row = RideshareBankRecipientSetup(
                user_id=driver_id, session_fingerprint="a" * 64, test_mode=True, paid_revision=1, card_revision=1,
                expires_at=old, result_version=version, confirmed_at=confirmed_at,
            )
            db.add(row)
            ids[name] = row.id
        db.commit()

    _, table, where, params = next(rule for rule in _rules(now) if rule[1] == "ridesharebankrecipientsetup")
    assert _delete_batched(table, f"({where}) AND user_id = :driver_id", {**params, "driver_id": driver_id}) == 2
    with Session(engine) as db:
        assert {name for name, setup_id in ids.items() if db.get(RideshareBankRecipientSetup, setup_id) is not None} == {
            "current", "checkout", "recent",
        }
