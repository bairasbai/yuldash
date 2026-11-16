"""Offline taxi contract sketches. No authorization, pricing or side effects.

All IDs/data are synthetic or supplied by trusted future adapters. Successful
validation is NOT permission to create an order, assign a vehicle or move money.
"""
from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum


class InvalidDraft(ValueError):
    """Machine-readable reason only; never include input/PII in exceptions."""


def integer(value: int, *, minimum: int = 0) -> None:
    if type(value) is not int or value < minimum:
        raise InvalidDraft("invalid_integer")


def identifier(value: str) -> None:
    if type(value) is not str or not value or len(value) > 128:
        raise InvalidDraft("invalid_identifier")


def instant(value: datetime) -> None:
    if not isinstance(value, datetime) or value.tzinfo is None or value.utcoffset() is None:
        raise InvalidDraft("timezone_required")


def flag(value: bool) -> None:
    if type(value) is not bool:
        raise InvalidDraft("invalid_flag")


class Category(str, Enum):
    ECONOMY = "standard"  # Existing backend identifier, not a new fifth class.
    COMFORT = "comfort"
    BUSINESS = "business"
    MINIVAN = "minivan"


@dataclass(frozen=True)
class Point:
    """Synthetic fixed-precision coordinate. repr does not disclose position."""
    lat_e6: int = field(repr=False)
    lng_e6: int = field(repr=False)

    def __post_init__(self):
        if (type(self.lat_e6) is not int or type(self.lng_e6) is not int
                or not -90_000_000 <= self.lat_e6 <= 90_000_000
                or not -180_000_000 <= self.lng_e6 <= 180_000_000):
            raise InvalidDraft("invalid_point")


@dataclass(frozen=True)
class Intent:
    passenger_ref: str = field(repr=False)
    points: tuple[Point, ...] = field(repr=False)
    category: Category
    options: tuple[str, ...]
    region_version: str
    round_trip: bool
    return_wait_min: int
    women_only: bool
    payment_method: str
    rider_details_version: str = field(repr=False)
    pickup_details_version: str = field(repr=False)
    scheduled_at: datetime | None = None

    def __post_init__(self):
        identifier(self.passenger_ref)
        identifier(self.region_version)
        identifier(self.rider_details_version)
        identifier(self.pickup_details_version)
        flag(self.round_trip)
        flag(self.women_only)
        integer(self.return_wait_min)
        if self.return_wait_min > 1440 or (not self.round_trip and self.return_wait_min):
            raise InvalidDraft("invalid_return_wait")
        if self.payment_method not in ("cash", "sbp", "negotiate"):
            raise InvalidDraft("unsupported_payment_method")
        if type(self.points) is not tuple or not 2 <= len(self.points) <= 5 or not all(
            type(p) is Point for p in self.points
        ):
            raise InvalidDraft("invalid_route")
        if type(self.category) is not Category:
            raise InvalidDraft("invalid_category")
        if type(self.options) is not tuple:
            raise InvalidDraft("invalid_options")
        for option in self.options:
            identifier(option)
        if self.options != tuple(sorted(set(self.options))):
            raise InvalidDraft("options_must_be_unique_and_sorted")
        if self.scheduled_at is not None:
            instant(self.scheduled_at)


@dataclass(frozen=True)
class QuoteDraft:
    """No client total. Full tariff snapshot/ledger signature is NOT_IMPLEMENTED."""
    quote_ref: str
    intent: Intent = field(repr=False)
    tariff_version: str
    created_at: datetime
    expires_at: datetime

    def __post_init__(self):
        identifier(self.quote_ref)
        identifier(self.tariff_version)
        if type(self.intent) is not Intent:
            raise InvalidDraft("invalid_intent")
        instant(self.created_at)
        instant(self.expires_at)
        if self.expires_at <= self.created_at:
            raise InvalidDraft("invalid_quote_interval")


def check_quote_binding(quote: QuoteDraft, intent: Intent, *, now: datetime,
                        expected_tariff_version: str) -> tuple[str, ...]:
    """Check server-stored quote against requested intent, NOT an order acceptance.

    expected_tariff_version is the version consented in this quote, NOT the
    latest published version. Publishing a new tariff must not revoke a quote.
    Legacy lower-price guarantee and atomic consume/idempotency are not here.
    """
    instant(now)
    identifier(expected_tariff_version)
    issues = []
    if now < quote.created_at:
        issues.append("quote_from_future")
    if now >= quote.expires_at:
        issues.append("quote_expired")
    if quote.intent != intent:
        issues.append("intent_changed")
    if quote.tariff_version != expected_tariff_version:
        issues.append("tariff_version_mismatch")
    return tuple(issues)
