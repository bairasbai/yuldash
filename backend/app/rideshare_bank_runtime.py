"""Read-only bank configuration from trusted application settings."""

import re
from dataclasses import dataclass, field

import httpx
from cryptography.fernet import Fernet
from pydantic import SecretStr

from .config import Settings
from .rideshare_bank_booking import BankRuntime
from .rideshare_bank_provider import YooKassaSafeDeal


def _receipt_url(booking_id: int) -> str:
    return f"https://app.yulbash.ru/receipt/{booking_id}"


@dataclass(frozen=True)
class RideshareBankConfiguration:
    runtime: BankRuntime
    _secret: SecretStr = field(repr=False)

    def provider(self, client: httpx.Client) -> YooKassaSafeDeal:
        return YooKassaSafeDeal(client, shop_id=self.runtime.provider_account_id or "",
                               secret_key=self._secret.get_secret_value(),
                               test_mode=False, read_only=True)


def configure_rideshare_bank(settings: Settings) -> RideshareBankConfiguration | None:
    if not settings.rideshare_bank_read_enabled:
        return None
    shop_id = settings.rideshare_bank_shop_id
    if not re.fullmatch(r"[0-9]{1,64}", shop_id) or not settings.rideshare_bank_secret.get_secret_value().strip():
        return None
    try:
        cipher = Fernet(settings.rideshare_bank_fernet_key.get_secret_value().encode("ascii"))
    except ValueError:
        return None
    runtime = BankRuntime(False, cipher, _receipt_url, provider_account_id=shop_id)
    return RideshareBankConfiguration(runtime, settings.rideshare_bank_secret)
