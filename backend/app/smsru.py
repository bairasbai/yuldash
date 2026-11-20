"""SMS.ru wire protocol for login codes."""

from dataclasses import dataclass
from enum import StrEnum
import re

import httpx

from .config import settings


class SmsDelivery(StrEnum):
    SENT = "sent"
    FAILED = "failed"
    UNKNOWN = "unknown"


@dataclass(frozen=True)
class SmsSendResult:
    delivery: SmsDelivery
    message_id: str | None = None
    provider_status: int | None = None


SEND_TIMEOUT = 5.0
MESSAGE_ID = re.compile(r"[0-9]{1,31}-[0-9]{1,32}")
_QUEUED = 100
PENDING_STATUSES = (100, 101, 102)
# Receipt statuses prove provider acceptance. Downstream non-delivery may still be billable.
RECEIPT_STATUSES = {*PENDING_STATUSES, 103, 104, 105, 106, 107, 108, 150}
# Only documented synchronous pre-acceptance rejections. Omit transient provider errors.
_REJECTED = {
    200, 201, 202, 203, 204, 205, 206, 207, 208, 209, 212, 214, 215, 216,
    221, 222, 230, 231, 232, 233,
}


def login_blockers() -> tuple[str, ...]:
    required = {"SMS_RU_API_ID": settings.sms_ru_api_id, "SMS_FROM": settings.sms_from,
                "SMS_BUDGET_HMAC_KEY": settings.sms_budget_hmac_key}
    missing = tuple(key for key, value in required.items() if not value.strip())
    if not settings.sms_ru_login_ready:
        return (*missing, "smsru_confirmation_pending")
    return missing


def otp_message(code: str, lang: str) -> str:
    title = "Юлдашҡа инеү коды" if lang == "ba" else "Код входа в Юлдаш"
    return f"{title}: {code}\n@app.yulbash.ru #{code}"


def _client() -> httpx.Client:
    return httpx.Client(timeout=SEND_TIMEOUT, follow_redirects=False, trust_env=False)


def _call(method: str, **fields) -> dict:
    with _client() as client:
        try:
            response = client.post(
                f"https://sms.ru/sms/{method}", data={**fields, "api_id": settings.sms_ru_api_id, "json": "1"},
            )
            if response.status_code != 200:
                return {}
            parsed = response.json()
        except (httpx.HTTPError, ValueError):
            return {}
    return parsed if isinstance(parsed, dict) else {}


def _status(row: dict) -> int | None:
    code = row.get("status_code")
    return code if type(code) is int else None


def _row(data: dict, key: str) -> dict:
    messages = data.get("sms")
    item = messages.get(key) if isinstance(messages, dict) else None
    return item if isinstance(item, dict) else {}


def send_login_code(phone: str, code: str, *, lang: str) -> SmsSendResult:
    if settings.sms_provider != "smsru" or login_blockers():
        return SmsSendResult(SmsDelivery.FAILED)
    recipient = phone.removeprefix("+")
    ttl = max(1, min(1440, settings.otp_ttl_sec // 60))
    data = _call("send", to=recipient, msg=otp_message(code, lang), ttl=str(ttl), **{"from": settings.sms_from})
    top_status = _status(data)
    row = _row(data, recipient)
    status = _status(row)
    message_id = row.get("sms_id")
    if data.get("status") == "OK" and top_status == _QUEUED:
        if isinstance(message_id, str) and MESSAGE_ID.fullmatch(message_id):
            if row.get("status") == "OK" and status == _QUEUED:
                return SmsSendResult(SmsDelivery.SENT, message_id, status)
            return SmsSendResult(SmsDelivery.UNKNOWN, message_id)
        if "sms_id" not in row and row.get("status") == "ERROR" and status in _REJECTED:
            return SmsSendResult(SmsDelivery.FAILED, provider_status=status)
    elif data.get("status") == "ERROR" and top_status in _REJECTED and not data.get("sms"):
        return SmsSendResult(SmsDelivery.FAILED, provider_status=top_status)
    return SmsSendResult(SmsDelivery.UNKNOWN)


def lookup_statuses(message_ids: list[str]) -> dict[str, int]:
    if not message_ids or not settings.sms_ru_api_id.strip():
        return {}
    if len(message_ids) > 100 or not all(MESSAGE_ID.fullmatch(message_id) for message_id in message_ids):
        raise ValueError("Invalid SMS.ru status batch")
    data = _call("status", sms_id=",".join(message_ids))
    if data.get("status") != "OK" or _status(data) != _QUEUED:
        return {}
    codes = {}
    for message_id in message_ids:
        row = _row(data, message_id)
        status = _status(row)
        if row.get("status") == "OK" and status in RECEIPT_STATUSES:
            codes[message_id] = status
    return codes
