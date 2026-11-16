from dataclasses import replace
from datetime import datetime, timedelta, timezone
from fractions import Fraction
import unittest

from experiments.taxi_drafts.contracts import Category, InvalidDraft
from experiments.taxi_drafts.market_signals import (
    SignalContext, CountSignal, Quality, SafetySignal, SafetyState, QualityPolicy,
    SignalDecision, assess_market_signals,
)

NOW = datetime(2030, 1, 1, tzinfo=timezone.utc)


class MarketTests(unittest.TestCase):
    def setUp(self):
        self.ctx = SignalContext("region-v1", Category.ECONOMY, "fixture", "v1", NOW-timedelta(seconds=10), NOW)
        self.demand = CountSignal(self.ctx, NOW, 6, 3, Quality.COMPLETE)
        self.supply = replace(self.demand, count=2)
        self.safety = SafetySignal("region-v1", "safety-v1", NOW, NOW+timedelta(seconds=20), SafetyState.CLEAR)
        self.policy = QualityPolicy(30, 10, 3, "safety-v1")

    def assess(self, **kw):
        return assess_market_signals(kw.get("demand", self.demand), kw.get("supply", self.supply),
                                     kw.get("safety", self.safety), expected=self.ctx,
                                     policy=kw.get("policy", self.policy), now=kw.get("now", NOW))

    def test_ratio_is_exact_observation_not_price(self):
        r = self.assess(demand=replace(self.demand, count=1), supply=replace(self.supply, count=3))
        self.assertEqual(r.decision, SignalDecision.COMPARABLE_OBSERVATION)
        self.assertEqual(r.demand_supply_observation, Fraction(1, 3))
        self.assertIn("NOT_IMPLEMENTED", r.limitation)

    def test_unknown_partial_failed_not_zero(self):
        for signal in (None, replace(self.supply, count=None), replace(self.supply, quality=Quality.PARTIAL),
                       replace(self.supply, quality=Quality.FAILED, count=0)):
            r = self.assess(supply=signal)
            self.assertEqual(r.decision, SignalDecision.DEGRADED)
            self.assertIsNone(r.demand_supply_observation)
            self.assertTrue(r.reasons)

    def test_zero_supply_is_not_infinity(self):
        for demand in (0, 6):
            r = self.assess(demand=replace(self.demand, count=demand), supply=replace(self.supply, count=0))
            self.assertEqual(r.decision, SignalDecision.NO_REACHABLE_SUPPLY)
            self.assertIsNone(r.demand_supply_observation)

    def test_zero_demand_is_valid_with_independent_observations(self):
        r = self.assess(demand=replace(self.demand, count=0))
        self.assertEqual(r.decision, SignalDecision.NO_DEMAND)
        self.assertEqual(r.demand_supply_observation, 0)

    def test_each_context_dimension_and_exact_window(self):
        changes = dict(region_version="region-v2", category=Category.BUSINESS, provider="other", version="v2",
                       window_start=NOW-timedelta(seconds=9), window_end=NOW-timedelta(seconds=1))
        for field, value in changes.items():
            with self.subTest(field=field):
                s = replace(self.supply, context=replace(self.ctx, **{field:value}))
                r = self.assess(supply=s)
                self.assertEqual(r.decision, SignalDecision.DEGRADED)
                self.assertIn("supply_context_mismatch", r.reasons)

    def test_stale_and_future(self):
        long_safety = replace(self.safety, expires_at=NOW+timedelta(seconds=100))
        self.assertEqual(self.assess(safety=long_safety, now=NOW+timedelta(seconds=29)).decision,
                         SignalDecision.COMPARABLE_OBSERVATION)
        self.assertEqual(self.assess(safety=long_safety, now=NOW+timedelta(seconds=30)).decision,
                         SignalDecision.DEGRADED)
        r = self.assess(supply=replace(self.supply, received_at=NOW+timedelta(seconds=1)))
        self.assertIn("supply_future", r.reasons)

    def test_sampling_and_window_boundaries(self):
        self.assertEqual(self.assess().decision, SignalDecision.COMPARABLE_OBSERVATION)
        r = self.assess(supply=replace(self.supply, sample_size=2))
        self.assertIn("supply_insufficient_observations", r.reasons)
        r = self.assess(policy=replace(self.policy, max_window_sec=9))
        self.assertIn("supply_window_too_wide", r.reasons)

    def test_emergency_dominates_quality_but_reasons_preserved(self):
        r = self.assess(safety=replace(self.safety, state=SafetyState.RESTRICTED), supply=None)
        self.assertEqual(r.decision, SignalDecision.RESTRICTION_REPORTED)
        self.assertIn("supply_missing", r.reasons)
        self.assertIsNone(r.demand_supply_observation)

    def test_safety_unknown_stale_future_and_wrong_region(self):
        signals = [None, replace(self.safety, state=SafetyState.UNKNOWN),
                   replace(self.safety, region_version="other"), replace(self.safety, version="v2"),
                   replace(self.safety, observed_at=NOW+timedelta(seconds=1))]
        for safety in signals:
            self.assertEqual(self.assess(safety=safety).decision, SignalDecision.SAFETY_REVIEW_REQUIRED)
        self.assertEqual(self.assess(now=NOW+timedelta(seconds=20)).decision, SignalDecision.SAFETY_REVIEW_REQUIRED)

    def test_stale_restriction_is_conservative_not_verified_live(self):
        r = self.assess(safety=replace(self.safety, state=SafetyState.RESTRICTED), now=NOW+timedelta(seconds=20))
        self.assertEqual(r.decision, SignalDecision.RESTRICTION_REPORTED)
        self.assertIn("safety_not_current", r.reasons)

    def test_invalid_types_and_time(self):
        for bad in (True, -1, 0.1, float("nan"), float("inf")):
            with self.assertRaises(InvalidDraft):
                replace(self.supply, count=bad)
        for bad in (True, 0, -1):
            with self.assertRaises(InvalidDraft):
                replace(self.policy, min_observations=bad)
        with self.assertRaises(InvalidDraft):
            replace(self.ctx, window_start=NOW)
        with self.assertRaises(InvalidDraft):
            replace(self.supply, received_at=NOW-timedelta(seconds=1))
        with self.assertRaises(InvalidDraft):
            self.assess(now=NOW.replace(tzinfo=None))

    def test_all_classes_no_implicit_multiplier(self):
        for category in Category:
            ctx = replace(self.ctx, category=category)
            r = assess_market_signals(replace(self.demand, context=ctx), replace(self.supply, context=ctx),
                                      self.safety, expected=ctx, policy=self.policy, now=NOW)
            self.assertEqual(r.demand_supply_observation, 3)


if __name__ == "__main__":
    unittest.main()
