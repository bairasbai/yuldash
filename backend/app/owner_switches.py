"""Effective owner settings and row locking for the login group."""

from dataclasses import dataclass
from typing import Literal

from sqlalchemy import text, update
from sqlmodel import Session, select

from .config import settings
from .errors import herr
from .models import AppSetting
from .smsru import login_blockers as smsru_login_blockers


LOGIN_METHODS = ("sms", "telegram", "vk", "whatsapp")
LOGIN_KEYS = tuple(f"login.{method}" for method in LOGIN_METHODS)


@dataclass(frozen=True)
class SwitchState:
    stored: bool | None
    effective: bool
    blockers: tuple[str, ...]


def seed_login_settings(session: Session) -> None:
    """Development schemas are created from models without Alembic's seed rows."""
    session.execute(text("INSERT INTO appsetting (key) VALUES (:key) ON CONFLICT (key) DO NOTHING"),
                    [{"key": key} for key in LOGIN_KEYS])


def login_settings(session: Session, *, lock: Literal["update", "share"] | None = None) -> dict[str, AppSetting]:
    query = select(AppSetting).where(AppSetting.key.in_(LOGIN_KEYS)).order_by(AppSetting.key)
    if lock:
        if session.get_bind().dialect.name == "sqlite":
            session.execute(update(AppSetting).where(AppSetting.key.in_(LOGIN_KEYS)).values(value=AppSetting.value))
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


def login_state(key: str, row: AppSetting | None) -> SwitchState:
    if row is None:
        return SwitchState(None, False, ("switch_storage_unavailable",))
    if key == "login.telegram":
        required = {"TELEGRAM_BOT_TOKEN": settings.telegram_bot_token,
                    "TELEGRAM_WEBHOOK_SECRET": settings.telegram_webhook_secret}
        blockers = tuple(name for name, value in required.items() if not value.strip())
        # This method is mandatory for operator recovery, including with older data.
        return SwitchState(row.value, not blockers, blockers)
    if key == "login.sms":
        blockers = _sms_blockers()
        enabled = (settings.sms_provider == "mock" and not settings.is_prod) if row.value is None else row.value
        return SwitchState(row.value, enabled and not blockers, blockers)
    if key == "login.vk":
        return SwitchState(row.value, False, ("vk_id_integration_pending",))
    return SwitchState(row.value, False, ("whatsapp_integration_pending",))
