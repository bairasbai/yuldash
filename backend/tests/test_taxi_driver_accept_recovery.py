"""Inactive opaque recovery, exercised against real sealed order/receipt SQL."""
import json
from datetime import timedelta
from uuid import uuid4

import pytest
from cryptography.fernet import Fernet
from fastapi import FastAPI, HTTPException
from fastapi.security import HTTPAuthorizationCredentials
from fastapi.testclient import TestClient
from jose import ExpiredSignatureError
from sqlalchemy import event, select, update
from sqlmodel import Session
from test_taxi_offered_waypoint_retire import _clock, _wait_db, healthy, route, testdb  # noqa: F401 - fixtures
from test_taxi_sealed_offer_card import _bind, offered  # noqa: F401
from test_taxi_sealed_resume_claim import near_deadline, retired  # noqa: F401

from app import security
from app import taxi_driver_accept_outcome as outcome
from app import taxi_driver_accept_recovery as recovery
from app import taxi_route_resume as resume
from app import taxi_sealed_offer_accept as accept
from app.models import AuthSession, InstantOrder, TaxiDriverAcceptOutcome, User
from app.models import InstantOrderStatus as S
from app.routers import auth
from app.taxi_sealed_offer_card import SealedOfferCardProof

_ORDER = InstantOrder.__table__.c
_DRIVER = User.__table__.c
_LOGIN = AuthSession.__table__.c
_RECEIPT = TaxiDriverAcceptOutcome.__table__
_KNOWN = outcome.DriverAcceptEvidence.COMMITTED
_UNKNOWN = outcome.DriverAcceptEvidence.UNKNOWN


@pytest.fixture
def example(request):
    data = request.getfixturevalue("offered")
    db, owner, oid, driver, args, _ = data
    proof = _bind(data)
    assert proof is not None
    old_driver = resume._state(resume.observe(db, oid))["route_resume_previous_driver_id"]
    assert old_driver not in (owner, driver)
    sessions = [(driver, "a"*64), (driver, "b"*64), (old_driver, "c"*64), (owner, "d"*64)]
    with Session(db) as s:
        s.add_all(AuthSession(user_id=user, id=sid) for user, sid in sessions)
        s.commit()
    principal = recovery.DriverRecoveryPrincipal(driver, "a"*64, 0)
    with db.connect() as conn:
        later = _clock(conn) + timedelta(minutes=3)
    params = {
        "principal": principal, "owner_id": owner, "order_id": oid, "attempt_id": str(uuid4()),
        "displayed_proof": proof, "receipt_expires_at": later, "context_expires_at": later,
    }
    return db, args["key"], Fernet(Fernet.generate_key()), params, old_driver


def _mint(example, **override):
    db, key, cipher, params, _ = example
    with db.connect() as conn:
        return recovery.mint_driver_recovery_envelope(conn, cipher, key, **(params | override))


def _accept(example, **override):
    db, key, _, params, _ = example
    return accept.accept_sealed_offer_with_outcome(
        db, key, params["displayed_proof"], owner_id=params["owner_id"], order_id=params["order_id"],
        driver_id=params["principal"].driver_id, attempt_id=params["attempt_id"],
        **({"expires_at": params["receipt_expires_at"]} | override),
    )


def _recover(example, token, **options):
    db, key, cipher, params, _ = example
    config = {"principal": params["principal"], "cipher": cipher, "key": key}
    return recovery.recover_driver_acceptance(db, envelope=token, **(config | options))


def _state(example):
    db, _, _, params, _ = example
    with db.connect() as conn:
        order = conn.execute(select(InstantOrder.__table__).where(_ORDER.id == params["order_id"])).one()
        receipts = conn.execute(select(_RECEIPT)).all()
        return order, receipts


def test_mint_and_recover_exact_no_authority_after_reassignment(example):
    db, _, cipher, params, other = example
    before = _state(example)
    envelope = _mint(example)
    assert envelope and _state(example) == before
    assert _recover(example, envelope) is _UNKNOWN  # issuance not admission or evidence
    clear = json.loads(cipher.decrypt(envelope.encode("ascii")))  # inspect only synthetic fixture
    assert clear == [
        "taxi-driver-sealed-accept-recovery-v1", params["principal"].driver_id, params["principal"].session_id,
        0, params["order_id"], params["attempt_id"], params["displayed_proof"]._digest.hex(),
        params["receipt_expires_at"].isoformat(timespec="microseconds"),
    ]
    assert "SYNTHETIC" not in str(clear) and params["principal"].session_id not in envelope
    assert _accept(example) is accept.SealedAcceptance.ACCEPTED
    oid = params["order_id"]
    with db.begin() as conn:
        conn.execute(update(InstantOrder).where(_ORDER.id == oid).values(status=S.cancelled))
    assert _recover(example, envelope) is _KNOWN
    with db.begin() as conn:
        conn.execute(update(InstantOrder).where(_ORDER.id == oid).values(status=S.accepted, driver_id=other))
    after = _state(example)
    for _ in range(3):
        assert _recover(example, envelope) is _KNOWN
    assert _state(example) == after and _mint(example) is None
    assert _recover(example, envelope, principal=recovery.DriverRecoveryPrincipal(other, "c"*64, 0)) is _UNKNOWN


def test_issue_rejects_unowned_swapped_or_invalid_context_in_same_db_observation(example):
    db, _, _, params, other = example
    before = _state(example)
    driver = params["principal"].driver_id
    variations = (
        {"owner_id": other}, {"order_id": params["order_id"] + 1},
        {"displayed_proof": SealedOfferCardProof(b"z"*32)}, {"attempt_id": "not-a-uuid"},
        {"principal": recovery.DriverRecoveryPrincipal(other, "a"*64, 0)},
        {"principal": recovery.DriverRecoveryPrincipal(driver, "d"*64, 0)},
        {"principal": recovery.DriverRecoveryPrincipal(driver, "a"*64, 1)},
        {"principal": recovery.DriverRecoveryPrincipal(driver, "e"*64, 0)},
    )
    for bad in variations:
        assert _mint(example, **bad) is None
    with db.begin() as conn:
        conn.execute(update(AuthSession).where(_LOGIN.id == "a"*64).values(revoked_at=_clock(conn)))
    assert _mint(example) is None and _state(example) == before


def test_tamper_wrong_session_receipt_and_key_rotation_stay_unknown(example):
    _, _, cipher, params, other = example
    token = _mint(example)
    assert token and _accept(example) is accept.SealedAcceptance.ACCEPTED
    driver = params["principal"].driver_id
    assert _recover(example, token) is _KNOWN
    assert _recover(example, token, principal=recovery.DriverRecoveryPrincipal(driver, "b"*64, 0)) is _UNKNOWN
    assert _recover(example, token, principal=recovery.DriverRecoveryPrincipal(params["owner_id"], "d"*64, 0)) is _UNKNOWN
    assert _recover(example, token, principal=recovery.DriverRecoveryPrincipal(other, "c"*64, 0)) is _UNKNOWN
    assert _recover(example, token, cipher=Fernet(Fernet.generate_key())) is _UNKNOWN
    assert _recover(example, token, key=b"synthetic-rotated-proof-key") is _UNKNOWN
    opened = json.loads(cipher.decrypt(token.encode("ascii")))
    for index, replacement in ((0, "different-purpose"), (5, str(uuid4())), (6, "e"*64), (7, "malformed")):
        invalid = opened.copy()
        invalid[index] = replacement
        assert _recover(example, cipher.encrypt(recovery._canonical(invalid)).decode("ascii")) is _UNKNOWN
    p = len(token) // 2
    assert _recover(example, token[:p] + ("A" if token[p] != "A" else "B") + token[p+1:]) is _UNKNOWN
    for invalid in (token + "=", token + "\n", token[:20], "", object()):
        assert _recover(example, invalid) is _UNKNOWN


@pytest.mark.parametrize("what", ("auth-epoch", "session-revoked"))
def test_reauth_or_revocation_before_the_receipt_statement_prevents_disclosure(example, what):
    db, _, _, params, _ = example
    token = _mint(example)
    assert token and _accept(example) is accept.SealedAcceptance.ACCEPTED
    observed = []

    def revoke(conn, cursor, statement, parameters, context, executemany):
        if statement.startswith("SELECT taxidriveracceptoutcome.binding") and not observed:
            observed.append(True)
            with db.begin() as other:
                if what == "auth-epoch":
                    other.execute(update(User).where(_DRIVER.id == params["principal"].driver_id).values(auth_epoch=1))
                else:
                    other.execute(update(AuthSession).where(_LOGIN.id == "a"*64).values(revoked_at=_clock(other)))

    event.listen(db, "before_cursor_execute", revoke)
    try:
        assert _recover(example, token) is _UNKNOWN
    finally:
        event.remove(db, "before_cursor_execute", revoke)
    assert observed == [True]


def test_result_context_expiry_uses_db_clock_and_cannot_extend_receipt(example):
    db, key, _, params, _ = example
    with db.connect() as conn:
        now = _clock(conn)
    past = now - timedelta(seconds=1)
    assert _mint(example, receipt_expires_at=past) is None
    assert _mint(example, context_expires_at=past) is None
    expires = now + timedelta(seconds=2)
    narrow = _mint(example, context_expires_at=expires)
    wide = _mint(example)
    assert narrow and wide and _accept(example) is accept.SealedAcceptance.ACCEPTED
    assert _recover(example, narrow) is _KNOWN
    _wait_db(db, expires)
    assert _recover(example, narrow) is _UNKNOWN and _recover(example, wide) is _KNOWN
    with db.begin() as conn:
        conn.execute(update(_RECEIPT).values(expires_at=past))
    assert _recover(example, wide) is _UNKNOWN
    assert outcome.committed_acceptance(
        db, key, driver_id=params["principal"].driver_id, order_id=params["order_id"],
        attempt_id=params["attempt_id"], proof=params["displayed_proof"],
    ) is _UNKNOWN


def test_real_auth_rotation_and_scoped_logout_gate_exact_recovery(example, monkeypatch):
    db, proof_key, cipher, params, other_driver = example
    driver = params["principal"].driver_id

    def login(user_id):
        # Exercise production issuance after identity verification, without using SMS/Telegram.
        with Session(db) as session:
            return security.issue_tokens(session, user_id)

    def principal(pair):
        credentials = HTTPAuthorizationCredentials(scheme="Bearer", credentials=pair["access_token"])
        with Session(db) as session:
            user, claims = security._authenticated_user(credentials, session)
            return recovery.DriverRecoveryPrincipal(user.id, claims["sid"], claims["auth_epoch"])

    def get_session():
        with Session(db) as session:
            yield session

    app = FastAPI()
    app.include_router(auth.router)
    app.dependency_overrides[auth.get_session] = get_session
    original_pair = login(driver)
    original = principal(original_pair)
    assert original.driver_id == driver and original.session_id == original_pair["session_id"]
    scoped = db, proof_key, cipher, params | {"principal": original}, other_driver
    envelope = _mint(scoped)
    assert envelope and _recover(scoped, envelope) is _UNKNOWN
    assert _accept(scoped) is accept.SealedAcceptance.ACCEPTED
    # The active-order response can no longer prove the attempt after cancellation.
    with db.begin() as conn:
        conn.execute(update(InstantOrder).where(_ORDER.id == params["order_id"]).values(status=S.cancelled))
    after_accept = _state(scoped)

    # Issue an actually expired signed access for the same trusted claims, without
    # sleeping or replacing the original login's normal refresh credential.
    with monkeypatch.context() as expired:
        expired.setattr(security.settings, "access_expire_min", -1)
        with Session(db) as session:
            login_row = session.get(AuthSession, original.session_id)
            expired_access = security.make_token(driver, auth_session=login_row, auth_epoch=original.auth_epoch)
    with pytest.raises(ExpiredSignatureError):
        security._decode(expired_access)
    with pytest.raises(HTTPException) as rejected:
        principal({"access_token": expired_access})
    assert rejected.value.status_code == 401

    with TestClient(app) as client:
        rotated = client.post("/auth/refresh", json={"refresh_token": original_pair["refresh_token"]})
        assert rotated.status_code == 200
        rotated_pair = rotated.json()
        assert rotated_pair["refresh_token"] != original_pair["refresh_token"]
        assert rotated_pair["session_id"] == original.session_id
        authenticated = principal(rotated_pair)
        assert authenticated == original
        assert _recover(scoped, envelope, principal=authenticated) is _KNOWN

        different = principal(login(other_driver))
        second_login = login(driver)
        same_account = principal(second_login)
        assert different.driver_id == other_driver
        assert same_account.driver_id == driver and same_account.session_id != original.session_id
        assert _recover(scoped, envelope, principal=different) is _UNKNOWN
        assert _recover(scoped, envelope, principal=same_account) is _UNKNOWN

        closed = client.post("/auth/logout/session", headers={
            "Authorization": "Bearer " + rotated_pair["logout_token"],
        })
        assert closed.status_code == 200
        # Access validation and outcome SQL must both reject the revoked login,
        # including a principal retained by an in-flight request before logout.
        with pytest.raises(HTTPException) as rejected:
            principal(rotated_pair)
        assert rejected.value.status_code == 401
        assert _recover(scoped, envelope, principal=original) is _UNKNOWN
        assert client.post("/auth/refresh", json={"refresh_token": rotated_pair["refresh_token"]}).status_code == 401
        assert principal(second_login) == same_account
        assert _recover(scoped, envelope, principal=same_account) is _UNKNOWN
        assert _state(scoped) == after_accept
