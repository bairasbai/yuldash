"""Manage the current driver's consent to use a payout recipient."""

from datetime import datetime, timedelta, timezone
from hashlib import sha256
import hmac
import re
from typing import Annotated, Literal

from fastapi import APIRouter, Depends, Path, Response
from fastapi.security import HTTPAuthorizationCredentials
from pydantic import BaseModel, ConfigDict, Field, SecretStr
from sqlalchemy import delete
from sqlmodel import Session, select

from ..config import settings
from ..db import get_session
from ..errors import herr
from ..models import User, UserRole
from ..rideshare_bank_booking import BankCheckoutUnavailable, BankRuntime, BankSwitchRevision, bank_switches
from ..rideshare_bank_models import RideshareBankRecipient, RideshareBankRecipientSetup
from ..rideshare_bank_recipient import BankRecipientUnavailable, recipient_matches_scope, save_bank_recipient
from ..security import bearer, current_user, lock_authenticated_user
from ..timeutil import utcnow
from .rideshare_bank import receipt_bank_runtime

router = APIRouter(prefix="/driver/bank-recipient", tags=["rideshare-bank"])

_SETUP_DURATION = timedelta(minutes=10)
_RECEIPT_DURATION = timedelta(hours=24)
_VERSION_PATTERN = r"^[a-f0-9]{32}$"
SetupId = Annotated[str, Path(pattern=_VERSION_PATTERN)]
Runtime = Annotated[BankRuntime | None, Depends(receipt_bank_runtime)]
Database = Annotated[Session, Depends(get_session)]
Credentials = Annotated[HTTPAuthorizationCredentials, Depends(bearer)]


class RecipientState(BaseModel):
    state: Literal["disabled", "unconfigured", "configured", "requires_reconnect"]
    version: str | None
    updated_at: datetime | None
    can_revoke: bool


class ExpectedRecipient(BaseModel):
    model_config = ConfigDict(extra="forbid")
    version: Annotated[str, Field(pattern=_VERSION_PATTERN)] | None


class RecipientSetup(BaseModel):
    setup_id: str
    expires_at: datetime


class RecipientConsent(BaseModel):
    model_config = ConfigDict(extra="forbid")
    consent: Annotated[bool, Field(strict=True)]
    token: Annotated[SecretStr, Field(min_length=1, max_length=2048)]


class RecipientConfirmation(BaseModel):
    version: str
    confirmed_at: datetime


def _driver_only(user: User) -> None:
    if user.role != UserRole.driver:
        raise herr(403, "Настройка доступна водителю.", "Көйләү водителгә асыҡ.", code="bank_recipient_driver_required")


def _authenticated_driver(credentials: Credentials, session: Database) -> tuple[User, str]:
    driver, active_session = lock_authenticated_user(credentials, session)
    _driver_only(driver)
    if not active_session:
        raise herr(401, "Войди заново, чтобы продолжить.", "Дауам итер өсөн яңынан ин.",
                   code="bank_recipient_session_required")
    return driver, sha256(active_session.encode()).hexdigest()


def _setup_switches(session: Session, runtime: BankRuntime | None, driver: User, *,
                    lock: bool = False) -> BankSwitchRevision | None:
    if runtime is None or not driver.verified:
        return None
    try:
        runtime.require_writes()
        return bank_switches(session, lock=lock)
    except BankCheckoutUnavailable:
        return None


def _unavailable():
    return herr(409, "Подключение выплаты недоступно.", "Түләүҙе тоташтырып булмай.", code="bank_recipient_disabled")


def _stale():
    return herr(409, "Обнови реквизит и начни подключение заново.",
                "Реквизитты яңырт һәм яңынан тоташтыр.", code="bank_recipient_changed")


def _recipient_state(session: Session, driver: User, runtime: BankRuntime | None) -> RecipientState:
    record = session.get(RideshareBankRecipient, driver.id, populate_existing=True)
    has_recipient = record is not None and record.token_ciphertext is not None
    if _setup_switches(session, runtime, driver) is None:
        state = "disabled"
    elif not has_recipient:
        state = "unconfigured"
    elif recipient_matches_scope(runtime.cipher, runtime.recipient_scope, record):
        state = "configured"
    else:
        state = "requires_reconnect"
    return RecipientState(
        state=state, version=record.version if record else None, can_revoke=has_recipient,
        updated_at=record.updated_at.replace(tzinfo=timezone.utc) if record else None,
    )


@router.get("", response_model=RecipientState)
def bank_recipient_state(response: Response, session: Database, runtime: Runtime,
                         driver: User = Depends(current_user)) -> RecipientState:
    _driver_only(driver)
    response.headers["Cache-Control"] = "no-store"
    return _recipient_state(session, driver, runtime)


@router.post("/setup", response_model=RecipientSetup)
def begin_recipient_setup(body: ExpectedRecipient, response: Response, session: Database,
                          runtime: Runtime, credentials: Credentials) -> RecipientSetup:
    driver, fingerprint = _authenticated_driver(credentials, session)
    switches = _setup_switches(session, runtime, driver, lock=True)
    if runtime is None or switches is None:
        raise _unavailable()
    record = session.get(RideshareBankRecipient, driver.id, populate_existing=True)
    if body.version != (record.version if record else None):
        raise _stale()
    now = utcnow()
    session.exec(delete(RideshareBankRecipientSetup).where(
        RideshareBankRecipientSetup.user_id == driver.id,
        RideshareBankRecipientSetup.confirmed_at.is_(None),
        RideshareBankRecipientSetup.expires_at <= now,
    ))
    attempt = session.exec(select(RideshareBankRecipientSetup).where(
        RideshareBankRecipientSetup.user_id == driver.id,
        RideshareBankRecipientSetup.session_fingerprint == fingerprint,
        RideshareBankRecipientSetup.test_mode == runtime.test_mode,
        RideshareBankRecipientSetup.provider_account_id == runtime.provider_account_id,
        RideshareBankRecipientSetup.expected_version == body.version,
        RideshareBankRecipientSetup.paid_revision == switches.paid,
        RideshareBankRecipientSetup.card_revision == switches.card,
        RideshareBankRecipientSetup.confirmed_at.is_(None),
        RideshareBankRecipientSetup.expires_at > now,
    )).one_or_none()
    if attempt is None:
        attempt = RideshareBankRecipientSetup(
            user_id=driver.id, session_fingerprint=fingerprint, test_mode=runtime.test_mode,
            provider_account_id=runtime.provider_account_id, expected_version=body.version,
            paid_revision=switches.paid, card_revision=switches.card, expires_at=now + _SETUP_DURATION,
        )
        session.add(attempt)
    result = RecipientSetup(setup_id=attempt.id, expires_at=attempt.expires_at.replace(tzinfo=timezone.utc))
    session.commit()
    response.headers["Cache-Control"] = "no-store"
    return result


def _owned_attempt(session: Session, driver: User, fingerprint: str, setup_id: str) -> RideshareBankRecipientSetup:
    attempt = session.get(RideshareBankRecipientSetup, setup_id, populate_existing=True)
    if (attempt is None or attempt.user_id != driver.id or (attempt.test_mode and settings.is_prod)
            or not hmac.compare_digest(attempt.session_fingerprint, fingerprint)):
        raise herr(404, "Подключение выплаты не найдено.", "Түләүҙе тоташтырыу табылманы.",
                   code="bank_recipient_setup_not_found")
    return attempt


def _receipt(attempt: RideshareBankRecipientSetup, now: datetime) -> RecipientConfirmation | None:
    if not attempt.confirmed_at or not attempt.result_version or now >= attempt.confirmed_at + _RECEIPT_DURATION:
        return None
    return RecipientConfirmation(version=attempt.result_version,
                                 confirmed_at=attempt.confirmed_at.replace(tzinfo=timezone.utc))


@router.get("/setup/{setup_id}", response_model=RecipientConfirmation | None)
def recipient_confirmation(setup_id: SetupId, response: Response, session: Database,
                           credentials: Credentials) -> RecipientConfirmation | None:
    driver, fingerprint = _authenticated_driver(credentials, session)
    attempt = _owned_attempt(session, driver, fingerprint, setup_id)
    response.headers["Cache-Control"] = "no-store"
    return _receipt(attempt, utcnow())


@router.post("/setup/{setup_id}/confirm", response_model=RecipientConfirmation)
def confirm_recipient_setup(setup_id: SetupId, body: RecipientConsent, response: Response, session: Database,
                            runtime: Runtime, credentials: Credentials) -> RecipientConfirmation:
    driver, fingerprint = _authenticated_driver(credentials, session)
    attempt = _owned_attempt(session, driver, fingerprint, setup_id)
    token = body.token.get_secret_value()
    if not body.consent or not re.fullmatch(r"synthetic-[a-f0-9]{32}", token):
        raise herr(422, "Подтверди реквизит для выплат.", "Түләү реквизитын раҫла.", code="bank_recipient_consent_required")
    digest = hmac.new(settings.jwt_secret.encode(), f"rideshare-recipient:{setup_id}:{token}".encode(), sha256).hexdigest()
    now = utcnow()
    response.headers["Cache-Control"] = "no-store"
    if attempt.confirmed_at is not None:
        result = _receipt(attempt, now)
        if result is None or not hmac.compare_digest(attempt.command_digest or "", digest):
            raise _stale()
        return result
    switches = _setup_switches(session, runtime, driver, lock=True)
    if runtime is None or switches is None:
        raise _unavailable()
    if (attempt.expires_at <= now or attempt.test_mode != runtime.test_mode
            or attempt.provider_account_id != runtime.provider_account_id
            or (attempt.paid_revision, attempt.card_revision) != (switches.paid, switches.card)):
        raise _stale()
    try:
        saved = save_bank_recipient(session, driver.id, token, expected_version=attempt.expected_version,
                                    cipher=runtime.cipher, scope=runtime.recipient_scope)
    except BankRecipientUnavailable:
        raise _stale() from None
    result = RecipientConfirmation(version=saved.version, confirmed_at=now.replace(tzinfo=timezone.utc))
    attempt.confirmed_at = now
    attempt.command_digest = digest
    attempt.result_version = result.version
    session.add(attempt)
    session.commit()
    return result


@router.post("/revoke", response_model=RecipientState)
def revoke_recipient(body: ExpectedRecipient, response: Response, session: Database, runtime: Runtime,
                     credentials: Credentials) -> RecipientState:
    driver, _ = _authenticated_driver(credentials, session)
    try:
        save_bank_recipient(session, driver.id, None, expected_version=body.version)
    except BankRecipientUnavailable:
        raise _stale() from None
    result = _recipient_state(session, driver, runtime)
    session.commit()
    response.headers["Cache-Control"] = "no-store"
    return result
