"""Synthetic platform-economics scenarios, NOT prices, accounting or cashflow.

No business defaults or provider tariffs. Exact fractions are forecast units,
not transferable amounts. Unknown costs produce INCOMPLETE, never zero profit.
"""
from dataclasses import dataclass, fields
from fractions import Fraction

from .contracts import Category, InvalidDraft, identifier, integer


LIMITATIONS = (
    "synthetic_forecast_not_real_tariff_or_payment",
    "driver_gross_excludes_driver_and_fleet_costs",
    "conditional_break_even_assumes_fixed_mix_and_attempt_ratio",
    "not_cashflow_tax_advice_or_profit_guarantee",
    "cost_completeness_and_sources_not_verified",
)


@dataclass(frozen=True)
class Costs:
    search_per_attempt_kop: int | None
    failed_attempt_extra_kop: int | None
    payment_bps: int | None
    payment_fixed_per_completed_kop: int | None
    sms_per_completed_kop: int | None
    support_per_completed_kop: int | None
    tax_per_completed_kop: int | None
    refund_loss_per_completed_kop: int | None
    platform_promo_per_completed_kop: int | None
    acquisition_per_completed_kop: int | None
    infra_per_completed_kop: int | None
    other_per_completed_kop: int | None
    fixed_period_kop: int | None

    def __post_init__(self):
        for f in fields(self):
            value = getattr(self, f.name)
            if value is not None:
                integer(value)
        if self.payment_bps is not None and self.payment_bps > 10_000:
            raise InvalidDraft("invalid_payment_bps")


@dataclass(frozen=True)
class Forecast:
    """Homogeneous class/region scenario; all totals use the same forecast period."""
    category: Category
    region_ref: str
    completed_rides: int | None
    search_attempts: int | None
    base_kop: int | None
    scarcity_kop: int | None
    commission_kop: int | None
    costs: Costs

    def __post_init__(self):
        if type(self.category) is not Category or type(self.costs) is not Costs:
            raise InvalidDraft("invalid_scenario_type")
        identifier(self.region_ref)
        for name in ("completed_rides", "search_attempts", "base_kop", "scarcity_kop", "commission_kop"):
            value = getattr(self, name)
            if value is not None:
                integer(value)
        if (self.completed_rides is not None and self.search_attempts is not None
                and self.completed_rides > self.search_attempts):
            raise InvalidDraft("completed_exceeds_attempts")
        if (self.commission_kop is not None and self.base_kop is not None
                and self.commission_kop > self.base_kop):
            raise InvalidDraft("commission_exceeds_base")


@dataclass(frozen=True)
class ForecastResult:
    missing: tuple[str, ...]
    driver_gross_per_completed_kop: int | None
    payment_fee_per_completed_kop: Fraction | None
    platform_revenue_period_kop: int | None
    variable_cost_period_kop: Fraction | None
    platform_net_period_kop: Fraction | None
    unit_margin_kop: Fraction | None
    attempts_per_completed: Fraction | None
    conditional_break_even_rides: int | None
    limitations: tuple[str, ...] = LIMITATIONS


def preview_economics(scenario: Forecast) -> ForecastResult:
    """No side effects. Returns no calculated amounts if any input cost is unknown.

    Payment fee is charged per completed ride, on full B+A (not commission).
    Extra fees for unsuccessful searches/cancellations/payment attempts MUST be
    represented in failed_attempt_extra_kop, rather than silently assumed free.
    Costs are explicit forecast inputs; this module cannot audit their adequacy.
    """
    if type(scenario) is not Forecast:
        raise InvalidDraft("invalid_scenario")
    names = ("completed_rides", "search_attempts", "base_kop", "scarcity_kop", "commission_kop")
    missing = [name for name in names if getattr(scenario, name) is None]
    missing += ["costs."+f.name for f in fields(scenario.costs) if getattr(scenario.costs, f.name) is None]
    if missing:
        return ForecastResult(tuple(missing), None, None, None, None, None, None, None, None)
    c = scenario.costs
    n, attempts = scenario.completed_rides, scenario.search_attempts
    total = scenario.base_kop + scenario.scarcity_kop
    fee = Fraction(total*c.payment_bps, 10_000) + c.payment_fixed_per_completed_kop
    per_completed = fee + sum(getattr(c, name) for name in (
        "sms_per_completed_kop", "support_per_completed_kop", "tax_per_completed_kop",
        "refund_loss_per_completed_kop", "platform_promo_per_completed_kop",
        "acquisition_per_completed_kop", "infra_per_completed_kop", "other_per_completed_kop",
    ))
    variable = (n*per_completed + attempts*c.search_per_attempt_kop
                + (attempts-n)*c.failed_attempt_extra_kop)
    revenue = n*scenario.commission_kop
    margin = Fraction(revenue-variable, n) if n else None
    ratio = Fraction(attempts, n) if n else None
    break_even = None
    if margin is not None and margin > 0:
        target = Fraction(c.fixed_period_kop, 1)/margin
        break_even = (target.numerator + target.denominator - 1)//target.denominator
    return ForecastResult((), total-scenario.commission_kop, fee, revenue, variable,
                          revenue-variable-c.fixed_period_kop, margin, ratio, break_even)
