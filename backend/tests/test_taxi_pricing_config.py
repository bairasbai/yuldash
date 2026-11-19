"""Isolated preview contract, NOT current taxi fares or full-order pricing."""
from dataclasses import FrozenInstanceError, replace
from fractions import Fraction

import pytest

from app.taxi_pricing_config import (
    CoreFareConfig,
    INT64_MAX,
    MAX_MULTIPLIER_BPS,
    core_fare_preview,
)


def payload(**changes):
    # Synthetic inputs, not a proposal to publish a live tariff.
    return dict(schema_version=1, version_id="fixture-v1", area_id="fixture-area",
                zone="city", category="standard", base_kop=10000,
                per_km_kop=1500, per_min_kop=500, minimum_kop=15000,
                rounding_step_kop=1000, static_multiplier_bps=10000) | changes


def config(**changes):
    return CoreFareConfig.from_mapping(payload(**changes))


def test_documented_synthetic_example():
    result = core_fare_preview(config(), distance_m=5000, duration_s=900,
                               dynamic_multiplier_bps=10000)
    assert result.core_price_kop == 25000
    assert result.rounded_core_kop == 25000
    assert not result.minimum_applied
    assert result.config_version == "fixture-v1"


@pytest.mark.parametrize("category", ["standard", "comfort", "business", "minivan"])
@pytest.mark.parametrize("zone", ["city", "intercity"])
def test_metadata_is_explicit_not_an_implicit_tariff_or_geographic_lookup(category, zone):
    c = config(category=category, zone=zone)
    assert c.category == category and c.zone == zone
    assert core_fare_preview(c, distance_m=0, duration_s=0,
                             dynamic_multiplier_bps=10000).core_price_kop == 15000


@pytest.mark.parametrize("field", ["schema_version", "base_kop", "per_km_kop", "per_min_kop",
                                    "minimum_kop", "rounding_step_kop", "static_multiplier_bps"])
@pytest.mark.parametrize("bad", [True, False, "1", 1.0, None, float("nan"), float("inf"), -1])
def test_mapping_and_direct_constructor_reject_non_integer_or_negative_values(field, bad):
    p = payload(**{field: bad})
    with pytest.raises(ValueError):
        CoreFareConfig.from_mapping(p)
    with pytest.raises(ValueError):
        CoreFareConfig(**p)


@pytest.mark.parametrize("field", ["base_kop", "per_km_kop", "per_min_kop", "minimum_kop",
                                    "rounding_step_kop"])
def test_integer_range_bound(field):
    with pytest.raises(ValueError):
        config(**{field: INT64_MAX + 1})


@pytest.mark.parametrize("changes", [dict(schema_version=2), dict(rounding_step_kop=0),
    dict(static_multiplier_bps=0), dict(static_multiplier_bps=MAX_MULTIPLIER_BPS + 1),
    dict(category="economy"), dict(category="courier"), dict(zone="unknown"),
    dict(version_id=""), dict(version_id="a" * 65), dict(version_id="../v1"),
    dict(area_id="Уфа"), dict(area_id="a b"), dict(area_id=None)])
def test_invalid_config(changes):
    with pytest.raises(ValueError):
        config(**changes)


def test_parser_rejects_unknown_or_missing_keys_and_non_objects():
    with pytest.raises(ValueError):
        CoreFareConfig.from_mapping(payload(pickup_fee_kop=100))
    p = payload()
    del p["base_kop"]
    with pytest.raises(ValueError):
        CoreFareConfig.from_mapping(p)
    for bad in [[], None, "{}", 0]:
        with pytest.raises(ValueError):
            CoreFareConfig.from_mapping(bad)


def test_configuration_is_detached_and_immutable():
    p = payload()
    c = CoreFareConfig.from_mapping(p)
    p["base_kop"] = 1
    assert c.base_kop == 10000
    with pytest.raises(FrozenInstanceError):
        c.base_kop = 1
    # A different version is a separate value, not mutation of the first.
    new = replace(c, version_id="fixture-v2", base_kop=20000)
    assert new.base_kop == 20000 and c.base_kop == 10000


@pytest.mark.parametrize("field", ["distance_m", "duration_s", "dynamic_multiplier_bps"])
@pytest.mark.parametrize("bad", [True, "10", 1.5, None, float("nan"), float("inf"), -1,
                                  INT64_MAX + 1])
def test_preview_input_validation(field, bad):
    args = dict(distance_m=1000, duration_s=60, dynamic_multiplier_bps=10000)
    args[field] = bad
    with pytest.raises(ValueError):
        core_fare_preview(config(), **args)


def test_zero_dynamic_multiplier_is_not_a_free_fare_switch():
    with pytest.raises(ValueError):
        core_fare_preview(config(), distance_m=0, duration_s=0,
                          dynamic_multiplier_bps=0)
    with pytest.raises(ValueError):
        core_fare_preview(config(), distance_m=0, duration_s=0,
                          dynamic_multiplier_bps=MAX_MULTIPLIER_BPS + 1)
    with pytest.raises(ValueError):
        core_fare_preview(payload(), distance_m=0, duration_s=0,
                          dynamic_multiplier_bps=10000)


@pytest.mark.parametrize("base,expected", [(1500, 2000), (2500, 2000), (3500, 4000),
                                          (1499, 1000), (1501, 2000)])
def test_half_even_rounding(base, expected):
    result = core_fare_preview(config(base_kop=base, minimum_kop=0),
                               distance_m=0, duration_s=0, dynamic_multiplier_bps=10000)
    assert result.core_price_kop == expected


def test_minimum_is_applied_after_rounding_without_rounding_the_minimum():
    result = core_fare_preview(config(base_kop=1500, minimum_kop=2345), distance_m=0,
                               duration_s=0, dynamic_multiplier_bps=10000)
    assert result.rounded_core_kop == 2000
    assert result.core_price_kop == 2345 and result.minimum_applied


def test_multipliers_apply_once_before_one_final_rounding():
    # 12.50 RUB * 1.2 * 1.2 = 18 RUB -> 20 RUB. Rounding base first gives 10 RUB.
    result = core_fare_preview(config(base_kop=1250, minimum_kop=0,
                                      static_multiplier_bps=12000), distance_m=0,
                               duration_s=0, dynamic_multiplier_bps=12000)
    assert result.core_price_kop == 2000


def test_partial_metre_and_second_costs_are_not_truncated_before_rounding():
    result = core_fare_preview(config(base_kop=0, per_km_kop=1000, per_min_kop=60,
                                      minimum_kop=0, rounding_step_kop=1),
                               distance_m=1, duration_s=1, dynamic_multiplier_bps=10000)
    assert result.core_price_kop == 2


@pytest.mark.parametrize("distance,seconds", [(0, 0), (1, 1), (999, 59), (1001, 61),
                                             (5000, 900), (10000, 3600)])
def test_exact_fraction_oracle(distance, seconds):
    c = config(rounding_step_kop=1, static_multiplier_bps=11750, minimum_kop=0)
    exact = (Fraction(10000) + Fraction(1500 * distance, 1000)
             + Fraction(500 * seconds, 60)) * Fraction(11750 * 12345, 10000**2)
    expected = round(exact)
    assert core_fare_preview(c, distance_m=distance, duration_s=seconds,
                             dynamic_multiplier_bps=12345).core_price_kop == expected


@pytest.mark.parametrize("base,km_rate,min_rate,distance,seconds,expected", [
    (7000, 1150, 500, 5000, 900, 20000),
    (7000, 1150, 500, 10000, 1500, 31000),
    (15000, 2500, 900, 10000, 1500, 62000),
])
def test_selected_exact_legacy_examples_only(base, km_rate, min_rate, distance, seconds, expected):
    # Selected golden examples do NOT establish parity with the full float legacy engine.
    c = config(base_kop=base, per_km_kop=km_rate, per_min_kop=min_rate, minimum_kop=0)
    legacy_rub = (base / 100 + km_rate / 100 * (distance / 1000)
                  + min_rate / 100 * (seconds / 60))
    assert int(round(legacy_rub / 10)) * 1000 == expected
    assert core_fare_preview(c, distance_m=distance, duration_s=seconds,
                             dynamic_multiplier_bps=10000).core_price_kop == expected


def test_monotonic_distance_duration_and_multiplier():
    c = config(base_kop=0, minimum_kop=0)
    def fare(d, t, k):
        return core_fare_preview(c, distance_m=d, duration_s=t,
                                 dynamic_multiplier_bps=k).core_price_kop
    distances = [fare(i * 100, 0, 10000) for i in range(100)]
    durations = [fare(0, i * 6, 10000) for i in range(100)]
    multipliers = [fare(1000, 60, 10000 + i * 100) for i in range(100)]
    for series in (distances, durations, multipliers):
        assert series == sorted(series)
        # Exercise several rounding steps; a constant floor is not useful evidence.
        assert len(set(series)) >= 3


def test_output_overflow_rejected_and_maximum_valid_integer_preserved():
    c = config(base_kop=INT64_MAX, rounding_step_kop=1, minimum_kop=0)
    result = core_fare_preview(c, distance_m=0, duration_s=0, dynamic_multiplier_bps=10000)
    assert result.core_price_kop == INT64_MAX
    with pytest.raises(ValueError):
        core_fare_preview(c, distance_m=1, duration_s=1, dynamic_multiplier_bps=10000)
    with pytest.raises(FrozenInstanceError):
        result.core_price_kop = 0


def test_no_external_state_is_read_or_written(monkeypatch):
    import builtins
    import socket
    def forbidden(*args, **kwargs):
        raise AssertionError("pure preview attempted I/O")
    monkeypatch.setattr(builtins, "open", forbidden)
    monkeypatch.setattr(socket.socket, "connect", forbidden)
    assert core_fare_preview(config(), distance_m=5000, duration_s=900,
                             dynamic_multiplier_bps=10000).core_price_kop == 25000
