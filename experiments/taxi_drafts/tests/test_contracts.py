"""Synthetic, offline tests. No server, credentials or real passenger data."""
from dataclasses import FrozenInstanceError, replace
from datetime import datetime, timedelta, timezone
import unittest

from experiments.taxi_drafts.contracts import (
    Category, Intent, InvalidDraft, Point, QuoteDraft, check_quote_binding,
)
from experiments.taxi_drafts.fleet import (
    Assignment, AssignmentState, HandoverEvidence, check_ordinary_handover,
)
from experiments.taxi_drafts.road_eta import (
    Comparison, Estimate, RouteStatus, preview_shortlist,
)

NOW = datetime(2030, 1, 1, tzinfo=timezone.utc)
A, B, C, D = (Point(i, i) for i in range(4))


def intent():
    return Intent("passenger-fixture", (A, B, C, D), Category.ECONOMY,
                  ("booster",), "region-v1", False, 0, False, "cash", "rider-v1", "pickup-v1")


class QuoteTests(unittest.TestCase):
    def setUp(self):
        self.intent = intent()
        self.quote = QuoteDraft("quote-fixture", self.intent, "tariff-v1", NOW,
                                NOW + timedelta(seconds=60))

    def check(self, **kwargs):
        return check_quote_binding(self.quote, kwargs.pop("intent", self.intent),
                                   now=kwargs.pop("now", NOW),
                                   expected_tariff_version=kwargs.pop("version", "tariff-v1"))

    def test_valid_binding_is_immutable(self):
        self.assertEqual(self.check(), ())
        with self.assertRaises(FrozenInstanceError):
            self.quote.tariff_version = "v2"

    def test_every_intent_change_is_bound(self):
        changes = dict(passenger_ref="other", points=(A, C, B, D), category=Category.BUSINESS,
                       options=(), region_version="region-v2", scheduled_at=NOW,
                       round_trip=True, women_only=True, payment_method="sbp",
                       rider_details_version="rider-v2", pickup_details_version="pickup-v2")
        for field, value in changes.items():
            with self.subTest(field=field):
                self.assertEqual(self.check(intent=replace(self.intent, **{field: value})),
                                 ("intent_changed",))
        original = replace(self.intent, round_trip=True, return_wait_min=1)
        changed = replace(original, return_wait_min=2)
        quote = replace(self.quote, intent=original)
        self.assertIn("intent_changed", check_quote_binding(
            quote, changed, now=NOW, expected_tariff_version="tariff-v1"))

    def test_expiry_boundary_and_clock(self):
        self.assertEqual(self.check(now=NOW+timedelta(seconds=59)), ())
        self.assertEqual(self.check(now=NOW+timedelta(seconds=60)), ("quote_expired",))
        self.assertEqual(self.check(now=NOW-timedelta(microseconds=1)), ("quote_from_future",))
        with self.assertRaises(InvalidDraft):
            self.check(now=NOW.replace(tzinfo=None))

    def test_wrong_version(self):
        self.assertEqual(self.check(version="v2"), ("tariff_version_mismatch",))

    def test_equivalent_timezone(self):
        self.assertEqual(self.check(now=NOW.astimezone(timezone(timedelta(hours=3)))), ())

    def test_malformed_inputs(self):
        cases = [dict(options=("b", "a")), dict(options=("b", "b")),
                 dict(points=[A, B]), dict(points=(A,)), dict(points=(A,)*6),
                 dict(category="standard"), dict(scheduled_at=NOW.replace(tzinfo=None)),
                 dict(women_only=1), dict(return_wait_min=True), dict(return_wait_min=1),
                 dict(payment_method="card"), dict(passenger_ref="")]
        for change in cases:
            with self.subTest(change=change), self.assertRaises(InvalidDraft):
                replace(self.intent, **change)
        with self.assertRaises(InvalidDraft):
            replace(self.quote, expires_at=NOW)

    def test_point_rejects_nonfinite_and_bool(self):
        for value in (float("nan"), float("inf"), True, 90_000_001):
            with self.subTest(value=value), self.assertRaises(InvalidDraft):
                Point(value, 0)

    def test_repr_does_not_leak_locations_or_people(self):
        self.assertNotIn("passenger-fixture", repr(self.intent))
        self.assertNotIn("lat_e6", repr(A))
        self.assertNotIn("passenger-fixture", repr(self.quote))


class EtaTests(unittest.TestCase):
    def setUp(self):
        self.context = Comparison("provider-fixture", "map-v1", "car", "traffic-v1", NOW, D)
        self.a = Estimate("driver-a", self.context, A, NOW, NOW, RouteStatus.ROAD, 90)
        self.b = replace(self.a, candidate_ref="driver-b", origin=B, duration_sec=30)
        self.origins = {"driver-a": A, "driver-b": B}

    def preview(self, *estimates, **kwargs):
        return preview_shortlist(tuple(estimates), context=self.context, origins=self.origins,
                                 now=kwargs.pop("now", NOW), max_estimate_age_sec=30,
                                 max_position_age_sec=60, limit=kwargs.pop("limit", 10))

    def test_road_time_not_distance(self):
        self.assertEqual(self.preview(self.a, self.b).candidates, ("driver-b", "driver-a"))
        self.assertEqual(self.preview(self.a, self.b, limit=1).candidates, ("driver-b",))

    def test_every_context_field_and_direction(self):
        changes = dict(provider="other", map_version="v2", profile="walk",
                       traffic_version="v2", departure_at=NOW+timedelta(seconds=1), pickup=A)
        for field, value in changes.items():
            with self.subTest(field=field):
                e = replace(self.a, context=replace(self.context, **{field:value}))
                self.assertEqual(self.preview(e).rejected, (("driver-a", "incomparable_route"),))
        self.assertEqual(self.preview(replace(self.a, origin=D)).candidates, ())

    def test_failures_are_distinct_no_geometric_fallback(self):
        for status in (RouteStatus.UNREACHABLE, RouteStatus.TIMEOUT, RouteStatus.MISSING):
            e = replace(self.a, status=status, duration_sec=None)
            self.assertEqual(self.preview(e).rejected, (("driver-a", status.value),))
            self.assertEqual(self.preview(e).candidates, ())
        self.assertEqual(self.preview().candidates, ())

    def test_freshness_boundaries(self):
        self.assertEqual(self.preview(self.a, now=NOW+timedelta(seconds=30)).candidates, ("driver-a",))
        self.assertEqual(self.preview(self.a, now=NOW+timedelta(seconds=31)).rejected,
                         (("driver-a", "stale_estimate"),))
        old = replace(self.a, position_at=NOW-timedelta(seconds=61))
        self.assertEqual(self.preview(old).rejected, (("driver-a", "stale_position"),))
        self.assertEqual(self.preview(self.a, now=NOW-timedelta(seconds=1)).rejected,
                         (("driver-a", "future_observation"),))

    def test_reject_invalid_durations_statuses_and_duplicate_candidate(self):
        for value in (-1, True, 0.1, float("nan"), float("inf"), None):
            with self.subTest(value=value), self.assertRaises(InvalidDraft):
                replace(self.a, duration_sec=value)
        for changes in (dict(status="road"), dict(status=RouteStatus.TIMEOUT),
                        dict(position_at=NOW+timedelta(seconds=1))):
            with self.assertRaises(InvalidDraft):
                replace(self.a, **changes)
        with self.assertRaises(InvalidDraft):
            self.preview(self.a, self.a)
        with self.assertRaises(InvalidDraft):
            self.preview(self.a, limit=True)

    def test_zero_eta_and_ties_deterministic(self):
        a, b = replace(self.a, duration_sec=0), replace(self.b, duration_sec=0)
        self.assertEqual(self.preview(b, a).candidates, ("driver-a", "driver-b"))


class FleetTests(unittest.TestCase):
    def setUp(self):
        self.old = Assignment("assignment-a", "fleet", "vehicle", "driver-a", 1, AssignmentState.ACTIVE)
        self.new = Assignment("assignment-b", "fleet", "vehicle", "driver-b", 2, AssignmentState.READY)
        self.e = HandoverEvidence(1, 2, 3, 3, 3, True, True, True, False, False, False, False, False)

    def test_fixture_consistent_no_side_effects(self):
        self.assertEqual(check_ordinary_handover(self.old, self.new, self.e), ())
        self.assertIs(self.old.state, AssignmentState.ACTIVE)
        self.assertIs(self.new.state, AssignmentState.READY)

    def test_each_blocker(self):
        for field in ("fleet_authorized", "driver_eligible", "vehicle_eligible",
                      "vehicle_hold", "old_driver_has_live_order", "new_driver_has_live_order",
                      "vehicle_has_live_order", "new_driver_has_other_vehicle"):
            with self.subTest(field=field):
                evidence = replace(self.e, **{field: not getattr(self.e, field)})
                self.assertTrue(check_ordinary_handover(self.old, self.new, evidence))
        for field in ("old_revision", "new_revision", "old_ack_revision", "new_ack_revision", "record_revision"):
            with self.subTest(field=field):
                evidence = replace(self.e, **{field:99})
                self.assertTrue(check_ordinary_handover(self.old, self.new, evidence))

    def test_ended_assignment_cannot_be_handed_over_again(self):
        old = replace(self.old, state=AssignmentState.ENDED)
        self.assertIn("invalid_handover_state", check_ordinary_handover(old, self.new, self.e))

    def test_identity_mismatch(self):
        for changes in (dict(vehicle_ref="other"), dict(fleet_ref="other"),
                        dict(driver_ref="driver-a"), dict(assignment_ref="assignment-a")):
            with self.subTest(changes=changes):
                self.assertTrue(check_ordinary_handover(self.old, replace(self.new, **changes), self.e))

    def test_unknown_state_and_checkbox_coercion(self):
        with self.assertRaises(InvalidDraft):
            replace(self.new, state="unknown")
        with self.assertRaises(InvalidDraft):
            replace(self.e, fleet_authorized="true")
        with self.assertRaises(InvalidDraft):
            replace(self.e, old_revision=True)


if __name__ == "__main__":
    unittest.main()
