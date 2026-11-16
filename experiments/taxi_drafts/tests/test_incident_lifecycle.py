from dataclasses import replace
from datetime import datetime, timedelta, timezone
from itertools import permutations
import unittest

from experiments.taxi_drafts.contracts import InvalidDraft
from experiments.taxi_drafts.incident_lifecycle import (
    IncidentScope, IncidentKind as K, IncidentReason as R, IncidentEvent,
    IncidentState as S, project_incident,
)

NOW = datetime(2030, 1, 1, tzinfo=timezone.utc)


class IncidentTests(unittest.TestCase):
    def setUp(self):
        self.scope = IncidentScope("incident-fixture", "order-fixture", "reporter-fixture", "channel-fixture")
        self.accepted = IncidentEvent("event-1", self.scope, 1, K.ACCEPTED, NOW, "proof-1")
        self.delivered = IncidentEvent("event-2", self.scope, 2, K.DELIVERY_CONFIRMED, NOW, "proof-2", channel_ref="channel-fixture")
        self.owned = IncidentEvent("event-3", self.scope, 3, K.TAKEN_IN_WORK, NOW, "proof-3", actor_ref="operator-fixture")
        self.closed = IncidentEvent("event-4", self.scope, 4, K.CLOSED, NOW, "proof-4", actor_ref="operator-fixture", reason=R.CONTACT_UNAVAILABLE)

    def project(self, *events):
        return project_incident(tuple(events), expected_scope=self.scope, as_of=NOW)

    def unknown(self, result):
        self.assertTrue(result.issues)
        self.assertIsNone(result.state)
        self.assertIsNone(result.owner_ref)
        self.assertIsNone(result.delivery_failure_count)
        self.assertIsNone(result.closure_reason)

    def test_received_is_not_delivery_or_operator_or_help(self):
        self.assertEqual(self.project().state, S.NOT_CONFIRMED)
        r = self.project(self.accepted)
        self.assertEqual(r.state, S.ACCEPTED)
        self.assertIsNone(r.owner_ref)
        self.assertFalse(hasattr(r, "help_on_way"))

    def test_delivery_failure_retry_does_not_create_owner(self):
        failed = replace(self.delivered, kind=K.DELIVERY_FAILED)
        r = self.project(self.accepted, failed)
        self.assertEqual(r.state, S.ACCEPTED)
        self.assertEqual(r.delivery_failure_count, 1)
        self.assertIsNone(r.owner_ref)
        retried = replace(self.delivered, revision=3, event_ref="retry")
        self.assertEqual(self.project(self.accepted, failed, retried).state, S.DELIVERED)

    def test_late_delivery_failure_does_not_regress(self):
        failed = replace(self.delivered, revision=4, event_ref="late-failure", kind=K.DELIVERY_FAILED)
        r = self.project(self.accepted, self.delivered, self.owned, failed)
        self.assertEqual(r.state, S.IN_WORK)
        self.assertEqual(r.delivery_failure_count, 1)

    def test_closed_reason_not_help_and_reopen_audited(self):
        r = self.project(self.accepted, self.delivered, self.owned, self.closed)
        self.assertEqual(r.state, S.CLOSED)
        self.assertEqual(r.closure_reason, R.CONTACT_UNAVAILABLE)
        reopened = replace(self.closed, revision=5, event_ref="reopen", kind=K.REOPENED, reason=R.FOLLOWUP_REQUESTED)
        r = self.project(self.accepted, self.delivered, self.owned, self.closed, reopened)
        self.assertEqual(r.state, S.IN_WORK)
        self.assertIsNone(r.closure_reason)

    def test_close_before_owner_or_wrong_owner_unknown(self):
        early = replace(self.closed, revision=3)
        self.unknown(self.project(self.accepted, self.delivered, early))
        self.unknown(self.project(self.accepted, self.delivered, self.owned, replace(self.closed, actor_ref="other")))

    def test_exact_duplicates_and_input_permutations(self):
        events = (self.accepted, self.delivered, self.owned, self.closed)
        expected = self.project(*events)
        self.assertEqual(self.project(*events, self.closed), expected)
        for sequence in permutations(events):
            self.assertEqual(self.project(*sequence), expected)

    def test_conflicting_id_revision_and_missing_chain(self):
        self.unknown(self.project(self.accepted, replace(self.accepted, evidence_ref="other")))
        self.unknown(self.project(self.accepted, replace(self.accepted, event_ref="other")))
        self.unknown(self.project(self.accepted, self.owned))
        self.unknown(self.project(self.delivered))

    def test_wrong_scope_and_responsible_channel(self):
        for name in ("incident_ref", "order_ref", "reporter_ref", "responsible_channel_ref"):
            self.unknown(self.project(replace(self.accepted, scope=replace(self.scope, **{name:"other"}))))
        self.unknown(self.project(self.accepted, replace(self.delivered, channel_ref="other")))

    def test_future_and_non_monotonic_time(self):
        self.unknown(self.project(replace(self.accepted, occurred_at=NOW+timedelta(seconds=1))))
        self.unknown(self.project(self.accepted, replace(self.delivered, occurred_at=NOW-timedelta(seconds=1))))

    def test_required_refs_reasons_and_types(self):
        for change in (dict(evidence_ref=" "), dict(revision=True), dict(revision=0), dict(occurred_at=NOW.replace(tzinfo=None))):
            with self.assertRaises(InvalidDraft):
                replace(self.accepted, **change)
        for event, change in ((self.delivered, dict(channel_ref=None)), (self.owned, dict(actor_ref=None)),
                              (self.closed, dict(reason=None)), (self.closed, dict(reason=R.FOLLOWUP_REQUESTED))):
            with self.assertRaises(InvalidDraft):
                replace(event, **change)

    def test_repr_has_no_refs(self):
        result = self.project(self.accepted, self.delivered, self.owned)
        for obj in (self.scope, self.accepted, self.delivered, self.owned, result):
            for ref in ("incident-fixture", "order-fixture", "reporter-fixture", "channel-fixture", "operator-fixture", "proof-1"):
                self.assertNotIn(ref, repr(obj))


if __name__ == "__main__":
    unittest.main()
