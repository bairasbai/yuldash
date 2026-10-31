"""Способы входа, которые сервер действительно может обслужить (RS-01, затем RS-04)."""
from .config import settings
from .errors import herr
from .services import sms_channel_live

ORDER = ("sms", "telegram", "vk", "whatsapp")


def available_methods() -> list[str]:
    enabled = {
        "sms": sms_channel_live() or (not settings.is_prod and settings.sms_provider == "mock"),
        "telegram": bool(settings.telegram_bot_token.strip() and settings.telegram_webhook_secret.strip()),
        "vk": False,
        "whatsapp": False,
    }
    return [method for method in ORDER if enabled[method]]


def refuse_disabled(method: str, *, retired: bool = False) -> None:
    if retired or method not in available_methods():
        raise herr(
            403,
            "Этот способ входа сейчас выключен. Выбери другой на экране входа.",
            "Был инеү ысулы хәҙер һүндерелгән. Инеү экранында икенсеһен һайла.",
            code="login_method_disabled", method=method,
        )
