"""Atomic admission and delivery outcome for one SMS login attempt."""

from calendar import timegm
from dataclasses import dataclass
from datetime import datetime, timedelta
from enum import StrEnum
from hashlib import sha256
import hmac
from ipaddress import ip_address
from math import ceil
from uuid import uuid4

from sqlalchemy import update
from sqlalchemy.dialects.postgresql import insert as pg_insert
from sqlalchemy.dialects.sqlite import insert as sqlite_insert
from sqlmodel import Session, select

from .antifraud import normalize_device_id
from .config import settings
from .errors import herr
from .logs import log
from .models import OtpBudgetAlert, OtpBudgetBucket, OtpCode, OtpSendAttempt, User, UserRole
from .timeutil import utcnow


RESEND_SECONDS = 60
SCAN_HOUR = 3600
SCAN_DAY = 86400
_SERVICE_SUBJECT = "global"
_MOCK_KEY = "yuldash-local-mock-sms-budget-v1"


class SmsDelivery(StrEnum):
    SENT = "sent"
    FAILED = "failed"
    UNKNOWN = "unknown"


class BudgetExceeded(Exception):
    def __init__(self, scope: str, retry_after: int, service_day: int):
        super().__init__(scope)
        self.scope = scope
        self.retry_after = retry_after
        self.service_day = service_day

    def refusal(self):
        if self.scope == "service_day":
            error = herr(503, "Сегодня достигнут лимит SMS. Войди через Telegram или попробуй завтра.",
                         "Бөгөн SMS сиге тулды. Telegram аша ин йәки иртәгә ҡабатлап ҡара.",
                         code="sms_daily_cap", retry_after_sec=self.retry_after)
        else:
            error = herr(429, "Слишком часто. Подожди и запроси код ещё раз.",
                         "Бик йыш. Бер аҙ көт тә кодты ҡабат һора.",
                         code="otp_rate_limited", retry_after_sec=self.retry_after)
        error.headers = {"Retry-After": str(self.retry_after)}
        return error


@dataclass(frozen=True)
class _Limit:
    scope: str
    subject: str
    window_start: int
    cap: int
    window_seconds: int

    @property
    def key(self) -> tuple[str, str, int]:
        return self.scope, self.subject, self.window_start


@dataclass(frozen=True)
class SmsAdmission:
    attempt_id: str
    provider_key: str


def _internal_key() -> bytes:
    if settings.sms_budget_hmac_key:
        return settings.sms_budget_hmac_key.encode()
    if not settings.is_prod and settings.sms_provider == "mock":
        return _MOCK_KEY.encode()
    raise herr(503, "Отправка кода сейчас недоступна.", "Кодты әлегә ебәреп булмай.",
               code="sms_budget_key_missing")


def _digest(namespace: str, value: str) -> str:
    return hmac.new(_internal_key(), f"{namespace}\0{value}".encode(), sha256).hexdigest()


def sms_verifier(attempt_id: str, code: str) -> str:
    return _digest(f"sms-otp-v1:{attempt_id}", code)


def _peer_key(peer: str) -> str:
    try:
        address = ip_address(peer)
    except ValueError:
        return "unknown"
    return str(getattr(address, "ipv4_mapped", None) or address)


def _window(now: datetime, width: int) -> int:
    moment = timegm(now.utctimetuple())
    return moment - moment % width


def _insert(session: Session, model):
    dialect = session.get_bind().dialect.name
    if dialect == "postgresql":
        return pg_insert(model)
    if dialect == "sqlite":
        return sqlite_insert(model)
    raise RuntimeError("SMS admission requires PostgreSQL or SQLite")


def _bucket_filter(scope: str, subject: str, window_start: int):
    return (
        OtpBudgetBucket.scope == scope,
        OtpBudgetBucket.subject == subject,
        OtpBudgetBucket.window_start == window_start,
    )


def _reserve_one(session: Session, limit: _Limit, now: datetime, service_day: int) -> None:
    scope, subject, start = limit.key
    session.execute(
        _insert(session, OtpBudgetBucket)
        .values(scope=scope, subject=subject, window_start=start,
                used=0, cap=limit.cap, version=0, updated_at=now)
        .on_conflict_do_nothing(index_elements=["scope", "subject", "window_start"])
    )
    where = _bucket_filter(*limit.key)
    statement = update(OtpBudgetBucket).where(*where)
    if scope == "phone_cooldown":
        statement = statement.where(
            (OtpBudgetBucket.next_allowed_at.is_(None)) | (OtpBudgetBucket.next_allowed_at <= now),
        ).values(used=1, next_allowed_at=now + timedelta(seconds=RESEND_SECONDS))
    else:
        statement = statement.where(OtpBudgetBucket.used < limit.cap).values(used=OtpBudgetBucket.used + 1)
    changed = session.execute(statement.values(
        cap=limit.cap, version=OtpBudgetBucket.version + 1, updated_at=now,
    ))
    if changed.rowcount == 1:
        return
    if scope == "phone_cooldown":
        until = session.exec(select(OtpBudgetBucket.next_allowed_at).where(*where)).one()
        retry_after = ceil((until - now).total_seconds())
    else:
        retry_after = start + limit.window_seconds - timegm(now.utctimetuple())
    raise BudgetExceeded(scope, max(1, retry_after), service_day)


def reserve_sms(session: Session, phone: str, code: str, peer: str, device_id: str) -> SmsAdmission:
    """Call after locking login settings and the phone. Commit before dispatch."""
    now = utcnow()
    hour, day = _window(now, SCAN_HOUR), _window(now, SCAN_DAY)
    peer = _peer_key(peer)
    device = normalize_device_id(device_id)
    ip_hash = _digest("sms-ip-v1", peer)
    device_hash = _digest("sms-device-v1", f"id:{device}" if device else f"peer:{peer}")
    phone_hash = _digest("sms-phone-v1", phone)
    limits = [
        _Limit("service_day", _SERVICE_SUBJECT, day, settings.sms_daily_cap, SCAN_DAY),
        _Limit("ip_hour", ip_hash, hour, 40, SCAN_HOUR),
        _Limit("device_hour", device_hash, hour, 5, SCAN_HOUR),
        _Limit("phone_hour", phone_hash, hour, 5, SCAN_HOUR),
        _Limit("phone_day", phone_hash, day, 10, SCAN_DAY),
        _Limit("phone_cooldown", phone_hash, 0, 1, RESEND_SECONDS),
    ]
    limits.sort(key=lambda item: item.key)
    attempt_id = str(uuid4())
    provider_key = f"sms-{attempt_id}"
    with session.begin_nested():
        for limit in limits:
            _reserve_one(session, limit, now, day)
        session.add(OtpSendAttempt(
            id=attempt_id, provider_idempotency_key=provider_key, state="reserved",
            bucket_keys=[list(limit.key) for limit in limits], created_at=now, updated_at=now,
        ))
        session.add(OtpCode(
            phone=phone, channel="sms", code=sms_verifier(attempt_id, code), attempt_id=attempt_id,
            ip_hash=ip_hash, device_hash=device_hash, created_at=now,
            expires_at=now + timedelta(seconds=settings.otp_ttl_sec),
        ))
    return SmsAdmission(attempt_id, provider_key)


def record_service_limit(session: Session, service_day: int) -> bool:
    result = session.execute(
        _insert(session, OtpBudgetAlert)
        .values(service_day=service_day, kind="sms_daily_cap", created_at=utcnow())
        .on_conflict_do_nothing(index_elements=["service_day", "kind"])
    )
    session.commit()
    return result.rowcount == 1


def notify_service_limit(session: Session) -> None:
    """One committed intent per day. The notification does not use paid SMS."""
    owner = (settings.admin_telegram_chat_id or "").strip()
    if not owner:
        return
    try:
        from .services import push_notification

        user_id = session.exec(select(User.id).where(
            User.telegram_id == owner, User.role == UserRole.admin,
        )).first()
        if user_id is not None:
            push_notification(session, user_id, "safety", "Лимит SMS исчерпан", "SMS сиге тулды",
                              "Новые коды будут доступны после полуночи UTC.",
                              "Яңы кодтар UTC буйынса төн уртаһынан һуң буласаҡ.")
    except Exception as error:
        session.rollback()
        log.warning("[OTP_LIMIT] notification outcome=%s", type(error).__name__)


def send_login_sms(phone: str, code: str, *, lang: str, provider_key: str) -> SmsDelivery:
    """The local provider accepts without external delivery."""
    if settings.sms_provider == "mock" and not settings.is_prod:
        return SmsDelivery.SENT
    return SmsDelivery.FAILED


def finish_sms(session: Session, attempt_id: str, outcome: SmsDelivery) -> bool:
    """Persist one transition and refund only the originally reserved daily quota."""
    now = utcnow()
    sources = ("reserved",) if outcome == SmsDelivery.UNKNOWN else ("reserved", "unknown")
    created = session.execute(update(OtpSendAttempt).where(
        OtpSendAttempt.id == attempt_id, OtpSendAttempt.state.in_(sources),
    ).values(state=outcome.value, updated_at=now).returning(OtpSendAttempt.created_at)).scalar_one_or_none()
    if created is None:
        return False
    if outcome == SmsDelivery.FAILED:
        refunded = session.execute(update(OtpBudgetBucket).where(
            *_bucket_filter("service_day", _SERVICE_SUBJECT, _window(created, SCAN_DAY)),
            OtpBudgetBucket.used > 0,
        ).values(used=OtpBudgetBucket.used - 1, version=OtpBudgetBucket.version + 1, updated_at=now))
        if refunded.rowcount != 1:
            raise RuntimeError("SMS admission service quota was unavailable for refund")
        session.execute(update(OtpCode).where(OtpCode.attempt_id == attempt_id).values(code="", expires_at=now))
    return True
