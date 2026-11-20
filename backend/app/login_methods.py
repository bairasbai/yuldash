"""Способы входа, которые сервер действительно может обслужить (RS-01, затем RS-04)."""
from sqlmodel import Session

from .errors import herr
from .owner_switches import LOGIN_METHODS as ORDER, login_settings, login_state, require_complete_login_settings


def available_methods(session: Session) -> list[str]:
    rows = login_settings(session)
    return [method for method in ORDER if login_state(f"login.{method}", rows.get(f"login.{method}")).effective]


def refuse_disabled(method: str, session: Session, *, retired: bool = False, issuing_sms: bool = False) -> None:
    if retired:
        enabled = False
    else:
        key = f"login.{method}"
        rows = login_settings(session, lock="share" if issuing_sms else None)
        if issuing_sms:
            require_complete_login_settings(rows)
        enabled = login_state(key, rows.get(key)).effective
    if not enabled:
        raise herr(
            403,
            "Этот способ входа сейчас выключен. Выбери другой на экране входа.",
            "Был инеү ысулы хәҙер һүндерелгән. Инеү экранында икенсеһен һайла.",
            code="login_method_disabled", method=method,
        )
