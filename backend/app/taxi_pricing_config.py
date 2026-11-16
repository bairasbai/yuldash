"""Pure, version-labelled CORE fare preview for a future taxi configuration service.

NOT connected to order pricing. No defaults, persistence, activation, API or settings.
This is not a complete tariff: pickup, waiting, options, weather, promotions, commission,
territory resolution and the legacy intercity taper are intentionally unsupported.
Do not use this output as an order total or migrate existing fares through it.

Inputs use integer kopecks per kilometre/minute, metres, seconds and basis points
(10_000 = 1). Exact rational arithmetic, one half-even rounding, then the minimum.
Int64 bounds and the 100x multiplier ceiling are technical preview limits, NOT
approved business limits; a future publishing layer needs narrower policy checks.
"""
from dataclasses import dataclass, fields
from fractions import Fraction
import re


INT64_MAX = 2**63 - 1
MAX_MULTIPLIER_BPS = 1_000_000
_ID = re.compile(r"[a-z0-9][a-z0-9._-]{0,63}", re.ASCII)
_CATEGORIES = frozenset({"standard", "comfort", "business", "minivan"})
_ZONES = frozenset({"city", "intercity"})


def _integer(name: str, value: object, minimum: int = 0, maximum: int = INT64_MAX) -> None:
    # bool is an int subclass; accepting it in money is a silent config mistake.
    if type(value) is not int or not minimum <= value <= maximum:
        raise ValueError(f"invalid_{name}")


def _identifier(name: str, value: object) -> None:
    if type(value) is not str or _ID.fullmatch(value) is None:
        raise ValueError(f"invalid_{name}")


@dataclass(frozen=True, slots=True)
class CoreFareConfig:
    """An immutable value, not an authoritative published tariff version.

    IDs are metadata for a future publisher. This module neither verifies global
    uniqueness nor resolves geographic boundaries. No field has a business default.
    """
    schema_version: int
    version_id: str
    area_id: str
    zone: str
    category: str
    base_kop: int
    per_km_kop: int
    per_min_kop: int
    minimum_kop: int
    rounding_step_kop: int
    static_multiplier_bps: int

    def __post_init__(self) -> None:
        _integer("schema_version", self.schema_version, 1, 1)
        _identifier("version_id", self.version_id)
        _identifier("area_id", self.area_id)
        if type(self.zone) is not str or self.zone not in _ZONES:
            raise ValueError("invalid_zone")
        if type(self.category) is not str or self.category not in _CATEGORIES:
            raise ValueError("invalid_category")
        for name in ("base_kop", "per_km_kop", "per_min_kop", "minimum_kop"):
            _integer(name, getattr(self, name))
        _integer("rounding_step_kop", self.rounding_step_kop, 1)
        _integer("static_multiplier_bps", self.static_multiplier_bps,
                 1, MAX_MULTIPLIER_BPS)

    @classmethod
    def from_mapping(cls, payload: object) -> "CoreFareConfig":
        """Parse a decoded JSON object, rejecting missing/unknown fields and coercion.

        JSON decoding and HTTP body-size limits belong to a future API. This pure
        boundary only accepts a plain dict and scalar bounded values.
        """
        if type(payload) is not dict or set(payload) != {f.name for f in fields(cls)}:
            raise ValueError("invalid_config_fields")
        return cls(**payload)


@dataclass(frozen=True, slots=True)
class CoreFarePreview:
    """Internal core-fare result; never a public quote or final order charge."""
    config_version: str
    rounded_core_kop: int
    core_price_kop: int
    minimum_applied: bool


def core_fare_preview(config: CoreFareConfig, *, distance_m: int, duration_s: int,
                      dynamic_multiplier_bps: int) -> CoreFarePreview:
    """max(minimum, round_step((base + km + minutes) * static * dynamic)).

    Only non-negative distances/times and strictly positive multipliers are valid.
    The minimum may be off the rounding grid: it is applied AFTER rounding, matching
    the ordering of the existing engine. Selected exact examples can coincide with
    legacy float calculations; general parity is NOT asserted.
    """
    if type(config) is not CoreFareConfig:
        raise ValueError("invalid_config")
    _integer("distance_m", distance_m)
    _integer("duration_s", duration_s)
    _integer("dynamic_multiplier_bps", dynamic_multiplier_bps, 1, MAX_MULTIPLIER_BPS)
    raw = (Fraction(config.base_kop)
           + Fraction(config.per_km_kop * distance_m, 1000)
           + Fraction(config.per_min_kop * duration_s, 60))
    raw *= Fraction(config.static_multiplier_bps * dynamic_multiplier_bps, 10000**2)
    # Fraction.__round__ uses exact ties-to-even. No float or per-component rounding.
    rounded = round(raw / config.rounding_step_kop) * config.rounding_step_kop
    price = max(config.minimum_kop, rounded)
    _integer("rounded_core_kop", rounded)
    _integer("core_price_kop", price)
    return CoreFarePreview(config.version_id, rounded, price, config.minimum_kop > rounded)
