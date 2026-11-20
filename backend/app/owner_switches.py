"""Effective owner settings and row locking for paid admission."""

from dataclasses import dataclass
from typing import Literal

from sqlalchemy import text, update
from sqlmodel import Session, select

from .card_payments import BANK_PROJECT_UNACCEPTED, CARD_PAYMENTS_SWITCH
from .config import settings
from .errors import herr
from .models import AppSetting, User
from .smsru import login_blockers as smsru_login_blockers


LOGIN_METHODS = ("sms", "telegram", "vk", "whatsapp")
LOGIN_KEYS = tuple(f"login.{method}" for method in LOGIN_METHODS)
PAID_SWITCH = "external.paid"
SMS_NOTICES_SWITCH = "external.sms_notices"
EXTERNAL_KEYS = (PAID_SWITCH, SMS_NOTICES_SWITCH)
OWNER_SWITCH_KEYS = (*EXTERNAL_KEYS, CARD_PAYMENTS_SWITCH)
SWITCH_KEYS = (*LOGIN_KEYS, *OWNER_SWITCH_KEYS)
PAID_PAUSED = "external_paid_paused"


@dataclass(frozen=True)
class SwitchState:
    stored: bool | None
    effective: bool
    blockers: tuple[str, ...]


def seed_login_settings(session: Session) -> None:
    """Development schemas are created from models without Alembic's seed rows."""
    session.execute(text("INSERT INTO appsetting (key) VALUES (:key) ON CONFLICT (key) DO NOTHING"),
                    [{"key": key} for key in SWITCH_KEYS])


def login_settings(session: Session, *, lock: Literal["update", "share"] | None = None) -> dict[str, AppSetting]:
    """Read the settings that serialize login admission and owner writes."""
    query = select(AppSetting).where(AppSetting.key.in_(SWITCH_KEYS)).order_by(AppSetting.key)
    if lock:
        if session.get_bind().dialect.name == "sqlite":
            session.execute(update(AppSetting).where(AppSetting.key.in_(SWITCH_KEYS)).values(value=AppSetting.value))
        else:
            query = query.with_for_update(read=lock == "share")
    return {row.key: row for row in session.exec(query.execution_options(populate_existing=True)).all()}


def require_complete_login_settings(rows: dict[str, AppSetting]) -> None:
    if any(key not in rows for key in LOGIN_KEYS):
        raise herr(503, "Настройки входа недоступны. Попробуй позже.",
                   "Инеү көйләүҙәре әлегә юҡ. Һуңыраҡ ҡабатлап ҡара.",
                   code="switch_storage_unavailable")


def _sms_blockers() -> tuple[str, ...]:
    if settings.sms_provider == "mock" and not settings.is_prod:
        return ()
    if settings.sms_provider == "smsru":
        return smsru_login_blockers()
    elif settings.sms_provider == "smsdar":
        required = {"SMSDAR_ID": settings.smsdar_id, "SMSDAR_PASSWORD": settings.smsdar_password}
    else:
        return ("SMS_PROVIDER",)
    missing = tuple(key for key, value in required.items() if not value.strip())
    return (*missing, "sms_atomic_budget_pending")


def paid_owner(user: User) -> bool:
    owner = settings.admin_telegram_chat_id.strip()
    return bool(owner and user.telegram_id == owner)


def stored_on(rows: dict[str, AppSetting], key: str) -> bool:
    row = rows.get(key)
    return row is not None and row.value is True


def sms_notice_blockers() -> tuple[str, ...]:
    if not settings.sms_notify_enabled:
        return ("SMS_NOTIFY_ENABLED",)
    if settings.sms_provider == "smsru":
        fields = {"SMS_RU_API_ID": settings.sms_ru_api_id, "SMS_FROM": settings.sms_from}
    elif settings.sms_provider == "smsdar":
        fields = {"SMSDAR_ID": settings.smsdar_id, "SMSDAR_PASSWORD": settings.smsdar_password,
                  "SMSDAR_SENDER": settings.smsdar_sender}
    else:
        return ("SMS_PROVIDER",)
    return tuple(key for key, value in fields.items() if not value.strip())


def switch_state(key: str, row: AppSetting | None, *, paid_enabled: bool) -> SwitchState:
    if row is None:
        return SwitchState(None, False, ("switch_storage_unavailable",))
    if key == CARD_PAYMENTS_SWITCH:
        return SwitchState(row.value, False, (BANK_PROJECT_UNACCEPTED,))
    if key == PAID_SWITCH:
        return SwitchState(row.value, row.value is True, ())
    if key == SMS_NOTICES_SWITCH:
        blockers = sms_notice_blockers() or (() if paid_enabled else (PAID_PAUSED,))
        return SwitchState(row.value, row.value is True and not blockers, blockers)
    if key == "login.telegram":
        required = {"TELEGRAM_BOT_TOKEN": settings.telegram_bot_token,
                    "TELEGRAM_WEBHOOK_SECRET": settings.telegram_webhook_secret}
        blockers = tuple(name for name, value in required.items() if not value.strip())
        # This method is mandatory for operator recovery, including with older data.
        return SwitchState(row.value, not blockers, blockers)
    if key == "login.sms":
        blockers = _sms_blockers()
        mock = settings.sms_provider == "mock" and not settings.is_prod
        if not blockers and not mock and not paid_enabled:
            blockers = (PAID_PAUSED,)
        enabled = mock if row.value is None else row.value
        return SwitchState(row.value, enabled and not blockers, blockers)
    if key == "login.vk":
        return SwitchState(row.value, False, ("vk_id_integration_pending",))
    return SwitchState(row.value, False, ("whatsapp_integration_pending",))
