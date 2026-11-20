"""Способы входа, которые сервер действительно может обслужить (RS-01, затем RS-04)."""
from sqlmodel import Session

from .errors import herr
from .owner_switches import (
    LOGIN_METHODS as ORDER, PAID_SWITCH, login_settings, require_complete_login_settings, stored_on, switch_state,
)


def available_methods(session: Session) -> list[str]:
    rows = login_settings(session)
    paid_enabled = stored_on(rows, PAID_SWITCH)
    return [method for method in ORDER
            if switch_state(f"login.{method}", rows.get(f"login.{method}"), paid_enabled=paid_enabled).effective]


def refuse_disabled(method: str, session: Session, *, retired: bool = False, issuing_sms: bool = False) -> None:
    if retired:
        enabled = False
    else:
        key = f"login.{method}"
        rows = login_settings(session, lock="share" if issuing_sms else None)
        if issuing_sms:
            require_complete_login_settings(rows)
        # Verification of an admitted code has no paid effect. Its own login switch still applies.
        paid_enabled = not issuing_sms or stored_on(rows, PAID_SWITCH)
        enabled = switch_state(key, rows.get(key), paid_enabled=paid_enabled).effective
    if not enabled:
        raise herr(
            403,
            "Этот способ входа сейчас выключен. Выбери другой на экране входа.",
            "Был инеү ысулы хәҙер һүндерелгән. Инеү экранында икенсеһен һайла.",
            code="login_method_disabled", method=method,
        )
