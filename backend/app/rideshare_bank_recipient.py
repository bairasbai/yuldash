"""Keep versioned payout tokens separate from the ordinary driver profile."""

from dataclasses import dataclass
from datetime import datetime
import json
from uuid import uuid4

from cryptography.fernet import Fernet, InvalidToken
from sqlmodel import Session

from .models import UserRole
from .rideshare_bank_models import RideshareBankRecipient
from .security import lock_refresh_user
from .timeutil import utcnow


class BankRecipientUnavailable(ValueError):
    """The driver or the exact confirmed recipient is no longer available."""


@dataclass(frozen=True)
class BankRecipientScope:
    test_mode: bool
    provider_account_id: str | None

    def envelope_prefix(self, user_id: int, version: str) -> bytes:
        binding = json.dumps([user_id, version, self.test_mode, self.provider_account_id], separators=(",", ":"))
        return b"rideshare-recipient-v1\0" + binding.encode("ascii") + b"\0"


@dataclass(frozen=True)
class BankRecipientState:
    version: str
    configured: bool
    updated_at: datetime


def _state(recipient: RideshareBankRecipient) -> BankRecipientState:
    return BankRecipientState(recipient.version, recipient.token_ciphertext is not None, recipient.updated_at)


def _open(cipher: Fernet, scope: BankRecipientScope, recipient: RideshareBankRecipient) -> str:
    ciphertext = recipient.token_ciphertext
    if ciphertext is None:
        raise BankRecipientUnavailable("Bank payout recipient requires confirmation")
    prefix = scope.envelope_prefix(recipient.user_id, recipient.version)
    try:
        envelope = cipher.decrypt(ciphertext)
        if not envelope.startswith(prefix) or len(envelope) == len(prefix):
            raise BankRecipientUnavailable("Bank payout recipient requires confirmation")
        return envelope[len(prefix):].decode("utf-8")
    except (InvalidToken, UnicodeDecodeError):
        raise BankRecipientUnavailable("Bank payout token cannot be read") from None


def recipient_matches_scope(cipher: Fernet, scope: BankRecipientScope, recipient: RideshareBankRecipient) -> bool:
    try:
        _open(cipher, scope, recipient)
    except BankRecipientUnavailable:
        return False
    return True


def _lock_driver(session: Session, user_id: int) -> None:
    driver = lock_refresh_user(session, user_id)
    if driver is None or driver.role != UserRole.driver:
        raise BankRecipientUnavailable("Bank payout recipient is unavailable")


def save_bank_recipient(session: Session, user_id: int, token: str | None, *, expected_version: str | None,
                        cipher: Fernet | None = None, scope: BankRecipientScope | None = None) -> BankRecipientState | None:
    """Write the authenticated driver's confirmed token or revoke it. The caller commits."""
    if token is not None and (not isinstance(token, str) or not 0 < len(token) <= 2048 or
                              token.isdecimal() or any(char.isspace() or ord(char) < 32 for char in token)):
        raise ValueError("Invalid bank payout token")
    if token is not None and (cipher is None or scope is None):
        raise BankRecipientUnavailable("Bank payout recipient binding is unavailable")
    _lock_driver(session, user_id)
    current = session.get(RideshareBankRecipient, user_id, populate_existing=True)
    ciphertext = current.token_ciphertext if current is not None else None
    if token is None and ciphertext is None:
        return _state(current) if current is not None else None
    if (current.version if current is not None else None) != expected_version:
        raise BankRecipientUnavailable("Bank payout recipient version has changed")

    if current is None:
        current = RideshareBankRecipient(user_id=user_id)
    else:
        current.version = uuid4().hex
        current.updated_at = utcnow()
    current.token_ciphertext = (
        cipher.encrypt(scope.envelope_prefix(user_id, current.version) + token.encode("utf-8"))
        if token is not None else None
    )
    session.add(current)
    session.flush()
    return _state(current)


def bank_recipient_token(session: Session, user_id: int, confirmed_version: str, *,
                         cipher: Fernet, scope: BankRecipientScope) -> str:
    """Resolve the confirmed version and hold the driver lock until the snapshot commits."""
    _lock_driver(session, user_id)
    recipient = session.get(RideshareBankRecipient, user_id, populate_existing=True)
    if recipient is None or recipient.version != confirmed_version or recipient.token_ciphertext is None:
        raise BankRecipientUnavailable("Bank payout recipient requires confirmation")
    return _open(cipher, scope, recipient)
