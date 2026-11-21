"""ЮKassa Safe Deal transport for rideshare. Admission and storage belong to the caller."""

import json
import re
from dataclasses import dataclass, field
from datetime import datetime, timezone
from enum import Enum
from urllib.parse import urlsplit

import httpx

from .payments import _rub


_ORIGIN = "https://api.yookassa.ru/v3"
_CHECKOUT_HOSTS = frozenset({"yookassa.ru", "yoomoney.ru"})
_META_KEY = "rideshare_operation"
_ID = re.compile(r"[A-Za-z0-9_-]{1,128}")
_KEY = re.compile(r"[A-Za-z0-9_-]{1,64}")
_RUB = re.compile(r"(-?)(0|[1-9][0-9]{0,13})(?:\.([0-9]{1,2}))?")
_RECOVERY_PAGE_SIZE = 100
_RECOVERY_MAX_PAGES = 5


class BankResource(str, Enum):
    deal = "deals"
    payment = "payments"
    refund = "refunds"
    payout = "payouts"


_STATUSES = {
    BankResource.deal: frozenset({"opened", "closed"}),
    BankResource.payment: frozenset({"pending", "waiting_for_capture", "succeeded", "canceled"}),
    BankResource.refund: frozenset({"pending", "succeeded", "canceled"}),
    BankResource.payout: frozenset({"pending", "succeeded", "canceled"}),
}
_NOTIFICATION_RESOURCES = {
    "payment.waiting_for_capture": BankResource.payment,
    "payment.succeeded": BankResource.payment,
    "payment.canceled": BankResource.payment,
    "refund.succeeded": BankResource.refund,
    "payout.succeeded": BankResource.payout,
    "payout.canceled": BankResource.payout,
}


class BankOutcomeUnknown(RuntimeError):
    """Transport or protocol failure. No operation can be declared absent."""


class BankRequestRejected(RuntimeError):
    """This HTTP request was refused. Earlier dispatches may still need reconciliation."""

    def __init__(self, status_code: int):
        self.status_code = status_code
        super().__init__(f"Bank request rejected ({status_code})")


@dataclass(frozen=True)
class BankRequest:
    resource: BankResource
    key: str
    body: bytes = field(repr=False)


@dataclass(frozen=True)
class BankObservation:
    resource: BankResource
    provider_id: str
    status: str
    operation_id: str | None
    test: bool | None
    amount_kop: int | None = None
    deal_id: str | None = None
    payment_id: str | None = None
    settlement_kop: int | None = None
    payout_balance_kop: int | None = None
    expires_at: str | None = None
    checkout_url: str | None = field(default=None, repr=False)


@dataclass(frozen=True)
class BankNotification:
    resource: BankResource
    provider_id: str
    operation_id: str | None


def bank_notification(payload: object) -> BankNotification | None:
    """Extract reference hints while leaving the bank's current state to an authenticated GET."""
    if not isinstance(payload, dict) or payload.get("type") != "notification":
        return None
    event = payload.get("event")
    resource = _NOTIFICATION_RESOURCES.get(event) if isinstance(event, str) else None
    if resource is None:
        return None
    try:
        value = _object(payload.get("object"))
        metadata = _object(value["metadata"]) if "metadata" in value else {}
        reference = _identifier(metadata[_META_KEY]) if _META_KEY in metadata else None
        return BankNotification(resource, _identifier(value.get("id")), reference)
    except ValueError:
        return None


def _identifier(value: object) -> str:
    if not isinstance(value, str) or not _ID.fullmatch(value):
        raise ValueError("Invalid bank identifier")
    return value


def _object(value: object) -> dict:
    if not isinstance(value, dict):
        raise ValueError("Invalid bank object")
    return value


def _money(amount_kop: int) -> dict[str, str]:
    if type(amount_kop) is not int or not 0 < amount_kop < 10**16:
        raise ValueError("Bank amount must be positive whole kopecks")
    return {"value": _rub(amount_kop), "currency": "RUB"}


def _read_money(obj: object) -> int:
    amount = _object(obj)
    text = amount.get("value")
    match = _RUB.fullmatch(text) if isinstance(text, str) else None
    if amount.get("currency") != "RUB" or match is None:
        raise ValueError("Invalid bank amount")
    sign, rub, fraction = match.groups()
    minor = 100 * int(rub) + int((fraction or "").ljust(2, "0"))
    return -minor if sign else minor


def _https_url(value: object, *, hosts: frozenset[str] | None = None) -> str:
    if not isinstance(value, str) or any(ord(char) < 32 or char.isspace() or char == "\\" for char in value):
        raise ValueError("Invalid bank URL")
    url = urlsplit(value)
    if (url.scheme != "https" or not url.hostname or url.username is not None or
            url.password is not None or url.port not in (None, 443) or
            (hosts is not None and url.hostname not in hosts)):
        raise ValueError("Invalid bank URL")
    return value


def validate_checkout_url(value: object) -> str:
    return _https_url(value, hosts=_CHECKOUT_HOSTS)


def _prepare(resource: BankResource, operation_id: str, key: str, body: dict) -> BankRequest:
    reference = _identifier(operation_id)
    if not isinstance(key, str) or not _KEY.fullmatch(key):
        raise ValueError("Invalid bank idempotency key")
    payload = {**body, "metadata": {_META_KEY: reference}, "description": f"Rideshare {reference}"}
    return BankRequest(resource, key, json.dumps(payload, sort_keys=True, separators=(",", ":")).encode("utf-8"))


def prepare_deal(operation_id: str, key: str) -> BankRequest:
    return _prepare(BankResource.deal, operation_id, key, {"type": "safe_deal", "fee_moment": "deal_closed"})


def prepare_payment(operation_id: str, key: str, *, deal_id: str, amount_kop: int,
                    payout_kop: int, return_url: str) -> BankRequest:
    amount, payout = _money(amount_kop), _money(payout_kop)
    if payout_kop > amount_kop:
        raise ValueError("Payout exceeds the payment")
    return _prepare(BankResource.payment, operation_id, key, {
        "amount": amount,
        "capture": True,
        "payment_method_data": {"type": "bank_card"},
        "confirmation": {"type": "redirect", "return_url": _https_url(return_url)},
        "deal": {"id": _identifier(deal_id), "settlements": [{"type": "payout", "amount": payout}]},
    })


def prepare_refund(operation_id: str, key: str, *, payment_id: str, amount_kop: int,
                   payout_reduction_kop: int | None = None) -> BankRequest:
    body = {"payment_id": _identifier(payment_id), "amount": _money(amount_kop)}
    if payout_reduction_kop is not None:
        reduction = _money(payout_reduction_kop)
        if payout_reduction_kop > amount_kop:
            raise ValueError("Payout reduction exceeds the refund")
        body["deal"] = {"refund_settlements": [{"type": "payout", "amount": reduction}]}
    return _prepare(BankResource.refund, operation_id, key, body)


def prepare_payout(operation_id: str, key: str, *, deal_id: str, amount_kop: int,
                   payout_token: str) -> BankRequest:
    if (not isinstance(payout_token, str) or not 0 < len(payout_token) <= 2048 or
            any(char.isspace() or ord(char) < 32 for char in payout_token)):
        raise ValueError("Invalid payout token")
    return _prepare(BankResource.payout, operation_id, key, {
        "deal": {"id": _identifier(deal_id)}, "amount": _money(amount_kop), "payout_token": payout_token,
    })


def _settlement(deal: dict, key: str) -> int:
    rows = deal.get(key)
    if not isinstance(rows, list) or len(rows) != 1:
        raise ValueError("Unexpected bank settlements")
    settlement = _object(rows[0])
    if settlement.get("type") != "payout":
        raise ValueError("Unexpected bank settlement type")
    return _read_money(settlement.get("amount"))


def _observation(resource: BankResource, payload: object, expected_test: bool) -> BankObservation:
    data = _object(payload)
    status = data.get("status")
    if not isinstance(status, str) or status not in _STATUSES[resource]:
        raise ValueError("Unrecognized bank state")
    test = data.get("test")
    # Refund objects can omit this field. Their parent payment is verified separately.
    if test is not expected_test and not (resource == BankResource.refund and test is None):
        raise ValueError("Wrong bank environment")
    metadata = _object(data["metadata"]) if "metadata" in data else {}
    reference = _identifier(metadata[_META_KEY]) if _META_KEY in metadata else None
    fields = dict(resource=resource, provider_id=_identifier(data.get("id")), status=status,
                  operation_id=reference, test=test)
    if resource == BankResource.deal:
        if data.get("type") != "safe_deal" or data.get("fee_moment") != "deal_closed":
            raise ValueError("Wrong deal type")
        expiry = data.get("expires_at")
        if expiry is not None and not isinstance(expiry, str):
            raise ValueError("Invalid deal expiry")
        return BankObservation(**fields, amount_kop=_read_money(data.get("balance")),
                               payout_balance_kop=_read_money(data.get("payout_balance")), expires_at=expiry)
    amount = _read_money(data.get("amount"))
    if amount <= 0:
        raise ValueError("Invalid operation amount")
    deal = _object(data.get("deal"))
    fields.update(amount_kop=amount, deal_id=_identifier(deal.get("id")))
    if resource == BankResource.payment:
        if status == "succeeded" and (data.get("paid") is not True or
                                      _object(data.get("payment_method")).get("type") != "bank_card"):
            raise ValueError("Unconfirmed card payment")
        confirmation = _object(data["confirmation"]) if "confirmation" in data else {}
        checkout = confirmation.get("confirmation_url")
        return BankObservation(**fields, settlement_kop=_settlement(deal, "settlements"),
                               checkout_url=None if checkout is None else validate_checkout_url(checkout))
    if resource == BankResource.refund:
        return BankObservation(**fields, payment_id=_identifier(data.get("payment_id")),
                               settlement_kop=_settlement(deal, "refund_settlements"))
    return BankObservation(**fields)


def _check_creation(operation: BankRequest, observation: BankObservation) -> None:
    payload = json.loads(operation.body)
    if observation.operation_id != payload["metadata"][_META_KEY]:
        raise ValueError("Bank operation reference does not match")
    if operation.resource == BankResource.deal:
        return
    if observation.amount_kop != _read_money(payload["amount"]):
        raise ValueError("Bank operation amount does not match")
    if operation.resource == BankResource.refund:
        if observation.payment_id != payload["payment_id"]:
            raise ValueError("Bank refund refers to another payment")
        if "deal" in payload and observation.settlement_kop != _settlement(payload["deal"], "refund_settlements"):
            raise ValueError("Bank refund settlement does not match")
        return
    if observation.deal_id != payload["deal"]["id"]:
        raise ValueError("Bank operation refers to another deal")
    if operation.resource == BankResource.payment and observation.settlement_kop != _settlement(payload["deal"], "settlements"):
        raise ValueError("Bank payment settlement does not match")


class YooKassaSafeDeal:
    def __init__(self, client: httpx.Client, *, shop_id: str, secret_key: str, test_mode: bool, read_only: bool = False):
        if not shop_id or not secret_key or type(test_mode) is not bool:
            raise ValueError("Safe Deal requires separate configured credentials and environment")
        self._client = client
        self._auth = (shop_id, secret_key)
        self._test_mode = test_mode
        self._read_only = read_only

    @property
    def test_mode(self) -> bool:
        return self._test_mode

    @property
    def account_id(self) -> str:
        return self._auth[0]

    def submit(self, operation: BankRequest) -> BankObservation:
        if self._read_only:
            raise PermissionError("Bank transport is read-only")
        return self._request(operation.resource, operation=operation)

    def fetch(self, resource: BankResource, provider_id: str) -> BankObservation:
        return self._request(resource, provider_id=_identifier(provider_id))

    def find_operation(self, resource: BankResource, operation_id: str, *, since: datetime, until: datetime,
                       payment_id: str | None = None) -> str | None:
        """Read the complete bounded window before treating one exact match as a candidate."""
        reference = _identifier(operation_id)
        params = {
            "created_at.gte": since.replace(tzinfo=timezone.utc).isoformat(),
            "created_at.lte": until.replace(tzinfo=timezone.utc).isoformat(),
            "limit": str(_RECOVERY_PAGE_SIZE),
        }
        if resource == BankResource.refund:
            params["payment_id"] = _identifier(payment_id)
        elif payment_id is not None:
            raise ValueError("Only a refund lookup can specify a payment")
        url = f"{_ORIGIN}/{resource.value}"
        found = None
        seen_cursors = set()
        for _ in range(_RECOVERY_MAX_PAGES):
            try:
                response = self._client.get(url, auth=self._auth, params=params, follow_redirects=False)
                if response.status_code != 200:
                    raise BankOutcomeUnknown("Bank lookup did not complete")
                page = _object(response.json())
                items = page.get("items")
                if (page.get("type") != "list" or not isinstance(items, list) or len(items) > _RECOVERY_PAGE_SIZE):
                    raise ValueError("Invalid bank list")
                for item in items:
                    row = _object(item)
                    metadata = row.get("metadata")
                    if not isinstance(metadata, dict) or metadata.get(_META_KEY) != reference:
                        continue
                    candidate = _identifier(row.get("id"))
                    if found is not None and candidate != found:
                        raise BankOutcomeUnknown("Bank lookup found more than one object")
                    found = candidate
                if "next_cursor" not in page:
                    return found
                cursor = page["next_cursor"]
                if not isinstance(cursor, str) or not 0 < len(cursor) <= 512 or cursor in seen_cursors:
                    raise ValueError("Invalid bank cursor")
                seen_cursors.add(cursor)
                params["cursor"] = cursor
            except (httpx.HTTPError, TypeError, ValueError):
                raise BankOutcomeUnknown("Bank lookup could not verify the result") from None
        raise BankOutcomeUnknown("Bank lookup exceeded the page budget")

    def _request(self, resource: BankResource, *, operation: BankRequest | None = None,
                 provider_id: str | None = None) -> BankObservation:
        url = f"{_ORIGIN}/{resource.value}"
        try:
            if operation is not None:
                response = self._client.post(
                    url, auth=self._auth, content=operation.body, follow_redirects=False,
                    headers={"Content-Type": "application/json", "Idempotence-Key": operation.key},
                )
            else:
                response = self._client.get(f"{url}/{provider_id}", auth=self._auth, follow_redirects=False)
        except httpx.HTTPError:
            raise BankOutcomeUnknown("Bank transport did not confirm the result") from None
        if operation is not None and response.status_code in {400, 401, 403, 422}:
            raise BankRequestRejected(response.status_code)
        if response.status_code != 200:
            raise BankOutcomeUnknown("Bank did not confirm the result")
        try:
            observed = _observation(resource, response.json(), self._test_mode)
            if provider_id is not None and observed.provider_id != provider_id:
                raise ValueError("Unexpected bank object identifier")
            if operation is not None:
                _check_creation(operation, observed)
            return observed
        except (KeyError, TypeError, ValueError):
            raise BankOutcomeUnknown("Bank returned an unverified result") from None
