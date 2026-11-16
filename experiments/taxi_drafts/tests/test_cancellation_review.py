from dataclasses import replace
from datetime import datetime, timedelta, timezone
import unittest

from experiments.taxi_drafts.contracts import InvalidDraft
from experiments.taxi_drafts.recovery import OrderSnapshot
from experiments.taxi_drafts.cancellation_review import (
    CancellationScope, CancelTerms, ConsentReview, review_cancel_terms,
    PickupEvidence, ContactEvidence, NoShowFacts, NoShowReview, review_no_show,
)

NOW = datetime(2030, 1, 1, tzinfo=timezone.utc)


class CancellationTests(unittest.TestCase):
    def setUp(self):
        self.scope = CancellationScope("order-fixture", "driver-fixture", 3, 2)
        self.terms = CancelTerms("decision-fixture", self.scope, "policy-v1", "fixture_reason",
                                 10, "RUB", NOW, NOW+timedelta(seconds=30))
        self.snap = OrderSnapshot("order-fixture", 3, 2, "arriving")
        self.facts = NoShowFacts(self.snap, "driver-fixture", "policy-v1", NOW,
                                NOW+timedelta(seconds=30), NOW-timedelta(seconds=60),
                                NOW-timedelta(seconds=60), NOW+timedelta(seconds=10), None,
                                PickupEvidence.CONFIRMED, ContactEvidence.RECORDED, False)

    def review(self, facts=None, **kw):
        return review_no_show(facts or self.facts, current_scope=kw.get("scope", self.scope),
                              current_snapshot=kw.get("snapshot", self.snap), policy_version=kw.get("policy", "policy-v1"),
                              now=kw.get("now", NOW))

    def test_cancel_exact_and_each_changed_term(self):
        self.assertEqual(review_cancel_terms(self.terms, self.terms, now=NOW), ConsentReview.ALIGNED_FIXTURE)
        changes = dict(decision_ref="new", policy_version="v2", reason_code="other", fee_kop=0,
                       created_at=NOW-timedelta(seconds=1), expires_at=NOW+timedelta(seconds=31))
        for name, value in changes.items():
            with self.subTest(name=name):
                self.assertEqual(review_cancel_terms(self.terms, replace(self.terms, **{name:value}), now=NOW), ConsentReview.RECONFIRM)
        for changes in (dict(order_ref="other"), dict(actor_ref="other"), dict(revision=4), dict(assignment_generation=3)):
            new = replace(self.terms, scope=replace(self.scope, **changes))
            self.assertEqual(review_cancel_terms(self.terms, new, now=NOW), ConsentReview.RECONFIRM)

    def test_cancel_missing_and_expiry_boundaries(self):
        self.assertEqual(review_cancel_terms(None, self.terms, now=NOW), ConsentReview.RECONFIRM)
        for seconds in (-1, 30):
            self.assertEqual(review_cancel_terms(self.terms, self.terms, now=NOW+timedelta(seconds=seconds)), ConsentReview.TIME_INVALID)

    def test_cancel_invalid_money_and_identifiers(self):
        for value in (True, -1, 0.1, float("nan")):
            with self.assertRaises(InvalidDraft):
                replace(self.terms, fee_kop=value)
        for change in (dict(currency="USD"), dict(reason_code=" "), dict(created_at=NOW.replace(tzinfo=None)), dict(expires_at=NOW)):
            with self.assertRaises(InvalidDraft):
                replace(self.terms, **change)
        self.assertEqual(replace(self.terms, fee_kop=0).fee_kop, 0)

    def test_no_show_boundary_and_no_timer_reset(self):
        self.assertEqual(self.review(), NoShowReview.WAIT)
        self.assertEqual(self.review(now=NOW+timedelta(seconds=10)), NoShowReview.CANDIDATE_ONLY)
        self.assertEqual(self.review(now=NOW+timedelta(seconds=10)), NoShowReview.CANDIDATE_ONLY)
        self.assertEqual(self.facts.waiting_started_at, NOW-timedelta(seconds=60))

    def test_unknown_invalid_location_contact_presence_need_review(self):
        for change in (dict(pickup=PickupEvidence.UNKNOWN), dict(pickup=PickupEvidence.INVALID),
                       dict(contact=ContactEvidence.UNKNOWN), dict(contact=ContactEvidence.MISSING),
                       dict(passenger_present=None)):
            self.assertEqual(self.review(replace(self.facts, **change)), NoShowReview.REVIEW_REQUIRED)

    def test_present_or_onboard_blocks(self):
        self.assertEqual(self.review(replace(self.facts, passenger_present=True)), NoShowReview.BLOCKED)
        for state in ("onboard", "done", "cancelled", "accepted"):
            snap = replace(self.snap, state=state)
            self.assertEqual(self.review(replace(self.facts, snapshot=snap), snapshot=snap), NoShowReview.BLOCKED)

    def test_stale_generation_revision_actor_policy(self):
        for change in (dict(order_ref="other"), dict(actor_ref="other"), dict(revision=4), dict(assignment_generation=3)):
            self.assertEqual(self.review(scope=replace(self.scope, **change)), NoShowReview.REVIEW_REQUIRED)
        self.assertEqual(self.review(policy="v2"), NoShowReview.REVIEW_REQUIRED)
        self.assertEqual(self.review(snapshot=replace(self.snap, revision=4)), NoShowReview.REVIEW_REQUIRED)

    def test_incomplete_or_contradictory_times(self):
        changes = [dict(arrived_at=None), dict(waiting_started_at=None), dict(eligible_at=None),
                   dict(waiting_started_at=NOW+timedelta(seconds=1)),
                   dict(eligible_at=NOW-timedelta(seconds=61)),
                   dict(arrived_at=NOW+timedelta(seconds=1)),
                   dict(scheduled_not_before=NOW)]
        for change in changes:
            self.assertEqual(self.review(replace(self.facts, **change)), NoShowReview.REVIEW_REQUIRED)

    def test_scheduled_boundary(self):
        f = replace(self.facts, scheduled_not_before=self.facts.waiting_started_at)
        self.assertEqual(self.review(f, now=NOW+timedelta(seconds=10)), NoShowReview.CANDIDATE_ONLY)

    def test_evidence_time_and_invalid_boolean(self):
        for seconds in (-1, 30):
            self.assertEqual(self.review(now=NOW+timedelta(seconds=seconds)), NoShowReview.REVIEW_REQUIRED)
        with self.assertRaises(InvalidDraft):
            replace(self.facts, passenger_present=0)
        with self.assertRaises(InvalidDraft):
            replace(self.scope, revision=True)
        self.assertNotIn("driver-fixture", repr(self.facts))
        self.assertNotIn("order-fixture", repr(self.terms))


if __name__ == "__main__":
    unittest.main()
