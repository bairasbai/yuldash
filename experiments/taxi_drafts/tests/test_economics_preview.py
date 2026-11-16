from dataclasses import fields, replace
from fractions import Fraction
import unittest

from experiments.taxi_drafts.contracts import Category, InvalidDraft
from experiments.taxi_drafts.economics_preview import Costs, Forecast, preview_economics


def costs():
    # Explicit synthetic zeros, NOT free production services or approved tariffs.
    return Costs(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)


def forecast():
    return Forecast(Category.ECONOMY, "region-fixture", 10, 20, 1000, 200, 100, costs())


class EconomicsTests(unittest.TestCase):
    def test_explicit_zero_cost_case(self):
        r = preview_economics(forecast())
        self.assertEqual(r.missing, ())
        self.assertEqual(r.driver_gross_per_completed_kop, 1100)
        self.assertEqual(r.platform_net_period_kop, 1000)
        self.assertEqual(r.attempts_per_completed, 2)
        self.assertEqual(r.conditional_break_even_rides, 0)
        self.assertTrue(r.limitations)

    def test_each_unknown_cost_is_incomplete_not_zero(self):
        for f in fields(Costs):
            with self.subTest(field=f.name):
                s = replace(forecast(), costs=replace(costs(), **{f.name:None}))
                r = preview_economics(s)
                self.assertEqual(r.missing, ("costs."+f.name,))
                self.assertIsNone(r.platform_net_period_kop)
                self.assertIsNone(r.conditional_break_even_rides)
                self.assertIsNone(r.driver_gross_per_completed_kop)

    def test_unknown_volume_and_fare(self):
        for name in ("completed_rides", "search_attempts", "base_kop", "scarcity_kop", "commission_kop"):
            r = preview_economics(replace(forecast(), **{name:None}))
            self.assertEqual(r.missing, (name,))
            self.assertIsNone(r.platform_net_period_kop)

    def test_fee_on_full_total_exact_fraction(self):
        s = replace(forecast(), base_kop=1, scarcity_kop=2, commission_kop=1,
                    costs=replace(costs(), payment_bps=1, payment_fixed_per_completed_kop=1))
        r = preview_economics(s)
        self.assertEqual(r.payment_fee_per_completed_kop, Fraction(10003, 10000))
        self.assertEqual(r.platform_net_period_kop, Fraction(-3, 1000))

    def test_failed_attempts_counted(self):
        s = replace(forecast(), costs=replace(costs(), search_per_attempt_kop=2, failed_attempt_extra_kop=3))
        r = preview_economics(s)
        self.assertEqual(r.variable_cost_period_kop, 20*2+10*3)
        self.assertEqual(r.platform_net_period_kop, 930)

    def test_zero_completed_still_costs_and_no_invented_ratio(self):
        s = replace(forecast(), completed_rides=0,
                    costs=replace(costs(), search_per_attempt_kop=2, failed_attempt_extra_kop=3, fixed_period_kop=100))
        r = preview_economics(s)
        self.assertEqual(r.platform_net_period_kop, -200)
        self.assertIsNone(r.unit_margin_kop)
        self.assertIsNone(r.attempts_per_completed)
        self.assertIsNone(r.conditional_break_even_rides)

    def test_break_even_is_ceiling_with_explicit_ratio(self):
        s = replace(forecast(), costs=replace(costs(), search_per_attempt_kop=1, fixed_period_kop=1000))
        r = preview_economics(s)
        self.assertEqual(r.unit_margin_kop, 98)
        self.assertEqual(r.attempts_per_completed, 2)
        self.assertEqual(r.conditional_break_even_rides, 11)

    def test_zero_and_negative_margin_have_no_break_even(self):
        for promo in (100, 101):
            r = preview_economics(replace(forecast(), costs=replace(costs(), platform_promo_per_completed_kop=promo)))
            self.assertLessEqual(r.unit_margin_kop, 0)
            self.assertIsNone(r.conditional_break_even_rides)
            self.assertEqual(r.driver_gross_per_completed_kop, 1100)

    def test_commission_cannot_take_scarcity_and_promo_not_driver(self):
        s = replace(forecast(), commission_kop=1000, costs=replace(costs(), platform_promo_per_completed_kop=50))
        r = preview_economics(s)
        self.assertEqual(r.driver_gross_per_completed_kop, 200)
        with self.assertRaises(InvalidDraft):
            replace(s, commission_kop=1001)

    def test_each_per_completed_cost_and_fixed_are_counted(self):
        for f in fields(Costs):
            if "per_completed_kop" not in f.name:
                continue
            r = preview_economics(replace(forecast(), costs=replace(costs(), **{f.name:1})))
            self.assertEqual(r.platform_net_period_kop, 990)
        r = preview_economics(replace(forecast(), costs=replace(costs(), fixed_period_kop=7)))
        self.assertEqual(r.platform_net_period_kop, 993)

    def test_invalid_numbers_and_counters(self):
        for bad in (True, -1, 0.5, float("nan"), float("inf")):
            with self.assertRaises(InvalidDraft):
                replace(costs(), search_per_attempt_kop=bad)
            with self.assertRaises(InvalidDraft):
                replace(forecast(), completed_rides=bad)
        with self.assertRaises(InvalidDraft):
            replace(forecast(), completed_rides=21)
        with self.assertRaises(InvalidDraft):
            replace(costs(), payment_bps=10001)

    def test_each_class_is_metadata_not_magic_multiplier(self):
        for category in Category:
            r = preview_economics(replace(forecast(), category=category))
            self.assertEqual(r.platform_net_period_kop, 1000)


if __name__ == "__main__":
    unittest.main()
