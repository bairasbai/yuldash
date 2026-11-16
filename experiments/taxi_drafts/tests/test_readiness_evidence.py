from dataclasses import replace
from datetime import datetime, timedelta, timezone
import unittest

from experiments.taxi_drafts.contracts import InvalidDraft
from experiments.taxi_drafts.readiness_evidence import (
    ReadinessScope, CheckPhase, CheckKind, CheckOutcome, ReadinessEvidence,
    ReadinessDecision as D, review_readiness,
)

NOW = datetime(2030, 1, 1, tzinfo=timezone.utc)


class ReadinessTests(unittest.TestCase):
    def setUp(self):
        self.scope = ReadinessScope("order-fixture", "driver-fixture", "vehicle-fixture", "batch-fixture",
                                    1, 2, 3, "policy-v1", CheckPhase.OFFER, NOW)
        self.items = tuple(ReadinessEvidence(self.scope, k, CheckOutcome.PASS, NOW,
                                            NOW+timedelta(seconds=30), "proof-fixture") for k in CheckKind)

    def review(self, items=None, **kw):
        return review_readiness(self.items if items is None else items, expected=kw.get("scope", self.scope),
                                now=kw.get("now", NOW))

    def test_full_set_only_candidate_not_assignment(self):
        result = self.review()
        self.assertEqual(result.decision, D.CANDIDATE_ONLY)
        self.assertEqual(result.reasons, ())
        self.assertIn("No assignment", result.limitation)

    def test_each_missing_check_and_empty_require_recheck(self):
        for item in self.items:
            subset = tuple(e for e in self.items if e.kind != item.kind)
            self.assertEqual(self.review(subset).decision, D.RECHECK_REQUIRED)
        self.assertEqual(self.review(()).decision, D.RECHECK_REQUIRED)

    def test_duplicates_even_identical_are_not_full_set(self):
        self.assertEqual(self.review(self.items+(self.items[0],)).decision, D.RECHECK_REQUIRED)

    def test_each_unknown_and_valid_failure(self):
        for i in range(len(self.items)):
            for outcome, decision in ((CheckOutcome.UNKNOWN, D.RECHECK_REQUIRED), (CheckOutcome.FAIL, D.NOT_A_CANDIDATE)):
                items = list(self.items)
                items[i] = replace(items[i], outcome=outcome)
                self.assertEqual(self.review(tuple(items)).decision, decision)

    def test_each_scope_field_is_bound(self):
        changes = dict(order_ref="other", driver_ref="other", vehicle_ref="other", batch_ref="other",
                       order_revision=4, vehicle_revision=4, assignment_generation=4, policy_version="v2",
                       phase=CheckPhase.ACCEPT, request_started_at=NOW-timedelta(seconds=1))
        for name, value in changes.items():
            with self.subTest(name=name):
                changed = replace(self.items[0], scope=replace(self.scope, **{name:value}))
                self.assertEqual(self.review((changed,)+self.items[1:]).decision, D.RECHECK_REQUIRED)

    def test_accept_new_batch_cannot_refresh_old_pass(self):
        accept = replace(self.scope, phase=CheckPhase.ACCEPT, batch_ref="new", request_started_at=NOW+timedelta(seconds=1))
        self.assertEqual(self.review(scope=accept, now=accept.request_started_at).decision, D.RECHECK_REQUIRED)
        relabeled = tuple(replace(e, scope=accept) for e in self.items)
        self.assertEqual(self.review(relabeled, scope=accept, now=accept.request_started_at).decision, D.RECHECK_REQUIRED)
        fresh = tuple(replace(e, scope=accept, observed_at=accept.request_started_at) for e in self.items)
        self.assertEqual(self.review(fresh, scope=accept, now=accept.request_started_at).decision, D.CANDIDATE_ONLY)
        revoked = (replace(fresh[0], outcome=CheckOutcome.FAIL),)+fresh[1:]
        self.assertEqual(self.review(revoked, scope=accept, now=accept.request_started_at).decision, D.NOT_A_CANDIDATE)

    def test_time_boundaries_and_future(self):
        self.assertEqual(self.review(now=NOW+timedelta(seconds=29)).decision, D.CANDIDATE_ONLY)
        self.assertEqual(self.review(now=NOW+timedelta(seconds=30)).decision, D.RECHECK_REQUIRED)
        self.assertEqual(self.review(now=NOW-timedelta(seconds=1)).decision, D.RECHECK_REQUIRED)
        future = replace(self.items[0], observed_at=NOW+timedelta(seconds=1))
        self.assertEqual(self.review((future,)+self.items[1:]).decision, D.RECHECK_REQUIRED)

    def test_onboard_never_candidate_or_automatic_trip_stop(self):
        scope = replace(self.scope, phase=CheckPhase.ONBOARD)
        for outcome in CheckOutcome:
            items = tuple(replace(e, scope=scope, outcome=outcome) for e in self.items)
            self.assertEqual(self.review(items, scope=scope).decision, D.ACTIVE_TRIP_POLICY_REQUIRED)
        self.assertEqual(self.review((), scope=scope).decision, D.ACTIVE_TRIP_POLICY_REQUIRED)

    def test_stale_failure_is_recheck_not_current_personal_restriction(self):
        stale = replace(self.items[0], outcome=CheckOutcome.FAIL, observed_at=NOW-timedelta(seconds=2), valid_until=NOW)
        self.assertEqual(self.review((stale,)+self.items[1:]).decision, D.RECHECK_REQUIRED)

    def test_invalid_types_intervals_and_private_repr(self):
        for change in (dict(outcome=True), dict(outcome="pass"), dict(valid_until=NOW),
                       dict(observed_at=NOW.replace(tzinfo=None)), dict(evidence_ref=" ")):
            with self.assertRaises(InvalidDraft):
                replace(self.items[0], **change)
        with self.assertRaises(InvalidDraft):
            replace(self.scope, order_revision=True)
        for obj in (self.scope, self.items[0], self.review()):
            for ref in ("order-fixture", "driver-fixture", "vehicle-fixture", "batch-fixture", "proof-fixture"):
                self.assertNotIn(ref, repr(obj))


if __name__ == "__main__":
    unittest.main()
