"""Inactive recovery envelope for an exact accepted attempt, never admission.

A trusted future caller provides an authenticated principal and dedicated
Fernet cipher (not JWT/bank keys) plus the existing proof/receipt key. No
configuration, issuer endpoint, automatic recovery or acceptance caller here.
"""
import hmac
import json
import re
from base64 import urlsafe_b64decode, urlsafe_b64encode
from dataclasses import dataclass
from datetime import UTC, datetime

from cryptography.fernet import Fernet, InvalidToken
from sqlalchemy import DateTime, literal, select
from sqlalchemy.engine import Connection, Engine
from sqlalchemy.exc import SQLAlchemyError

from . import taxi_driver_accept_outcome as outcome
from . import taxi_route_resume as resume
from . import taxi_sealed_offer_card as cards
from .models import AuthSession, User

_PURPOSE = "taxi-driver-sealed-accept-recovery-v1"
_SID = re.compile(r"[0-9a-f]{64}")
_HEX = re.compile(r"[0-9a-f]{64}")
_WIRE = re.compile(r"[A-Za-z0-9_-]{1,1000}={0,2}")
_LOGIN = AuthSession.__table__.c
_USER = User.__table__.c


@dataclass(frozen=True, slots=True, repr=False)
class DriverRecoveryPrincipal:
    """From authenticated session context, never from request JSON."""

    driver_id: int
    session_id: str
    auth_epoch: int


def _principal(value) -> bool:
    return (type(value) is DriverRecoveryPrincipal
            and type(value.driver_id) is int and 0 < value.driver_id <= 2147483647
            and type(value.session_id) is str and _SID.fullmatch(value.session_id) is not None
            and type(value.auth_epoch) is int and 0 <= value.auth_epoch < 1 << 63)


def _active(value: DriverRecoveryPrincipal):
    return select(_LOGIN.id).join_from(AuthSession.__table__, User.__table__, _LOGIN.user_id == _USER.id).where(
        _LOGIN.id == value.session_id, _LOGIN.user_id == value.driver_id, _LOGIN.revoked_at.is_(None),
        _USER.auth_epoch == value.auth_epoch,
    ).exists()


def _canonical(items):
    return json.dumps(items, ensure_ascii=True, separators=(",", ":"), allow_nan=False).encode("ascii")


def _stamp(value: datetime) -> str:
    return value.isoformat(timespec="microseconds")


def mint_driver_recovery_envelope(
    conn: Connection, cipher: Fernet, key: bytes, *, principal: DriverRecoveryPrincipal,
    owner_id: int, order_id: int, attempt_id: str, displayed_proof: cards.SealedOfferCardProof,
    receipt_expires_at: datetime, context_expires_at: datetime,
) -> str | None:
    """Issue no authority: only an active DB session and exact scoped current card."""
    if (type(cipher) is not Fernet or not _principal(principal) or not outcome._supported(conn.engine)
            or not resume._utc(receipt_expires_at) or not resume._utc(context_expires_at)
            or outcome._binding(key, principal.driver_id, order_id, attempt_id, displayed_proof) is None):
        return None
    expiry = min(receipt_expires_at, context_expires_at)
    try:
        # The result window may outlive the offer lease. Issuance cannot:
        # scope session and result expiry in the same exact live-card SELECT.
        current = cards._read_sealed_offer(
            conn, key, owner_id=owner_id, order_id=order_id, driver_id=principal.driver_id,
            fences=(_active(principal), outcome._expiry_clock(conn) < literal(expiry, type_=DateTime())),
        )
        if current is None or not hmac.compare_digest(current._proof._digest, displayed_proof._digest):
            return None
        payload = [_PURPOSE, principal.driver_id, principal.session_id, principal.auth_epoch,
                   order_id, attempt_id, current._proof._digest.hex(), _stamp(expiry)]
        # Fernet's timestamp is not our expiry check. Use the same DB observation
        # to stamp it; both issue and status enforce the absolute DB cutoff.
        stamped = int(current._observed_at.replace(tzinfo=UTC).timestamp())
        return cipher.encrypt_at_time(_canonical(payload), stamped).decode("ascii")
    except (SQLAlchemyError, ValueError, TypeError, OverflowError, OSError):
        return None


def recover_driver_acceptance(
    engine: Engine, cipher: Fernet, key: bytes, *, principal: DriverRecoveryPrincipal, envelope: str,
) -> outcome.DriverAcceptEvidence:
    """One read-only result, no order DTO, no authority to dispatch or accept."""
    unknown = outcome.DriverAcceptEvidence.UNKNOWN
    if (type(cipher) is not Fernet or not _principal(principal) or not outcome._supported(engine)
            or type(envelope) is not str or _WIRE.fullmatch(envelope) is None):
        return unknown
    try:
        wire = envelope.encode("ascii")
        if urlsafe_b64encode(urlsafe_b64decode(wire)) != wire:
            return unknown
        raw = cipher.decrypt(wire)
        items = json.loads(raw)
        if type(items) is not list or len(items) != 8 or _canonical(items) != raw:
            return unknown
        purpose, driver_id, sid, epoch, order_id, attempt_id, proof_hex, stamp = items
        if (purpose != _PURPOSE or type(driver_id) is not int or driver_id != principal.driver_id
                or type(sid) is not str or not sid.isascii() or not hmac.compare_digest(sid, principal.session_id)
                or type(epoch) is not int or epoch != principal.auth_epoch
                or type(proof_hex) is not str or _HEX.fullmatch(proof_hex) is None
                or type(stamp) is not str or len(stamp) != 26):
            return unknown
        expiry = datetime.fromisoformat(stamp)
        if not resume._utc(expiry) or _stamp(expiry) != stamp:
            return unknown
        proof = cards.SealedOfferCardProof(bytes.fromhex(proof_hex))
        binding = outcome._binding(key, driver_id, order_id, attempt_id, proof)
        if binding is None:
            return unknown
        with engine.connect() as conn:
            return outcome._committed_in(
                conn, driver_id=driver_id, order_id=order_id, attempt_id=attempt_id, binding=binding,
                fences=(_active(principal), outcome._expiry_clock(conn) < literal(expiry, type_=DateTime())),
            )
    except (InvalidToken, SQLAlchemyError, ValueError, TypeError, OverflowError, RecursionError):
        return unknown
