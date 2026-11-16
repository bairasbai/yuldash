from dataclasses import replace
from datetime import datetime, timedelta, timezone
import unittest

from experiments.taxi_drafts.contracts import InvalidDraft
from experiments.taxi_drafts.recovery import (
    ReadScope, OrderSnapshot, ReadEvidence, ReadKind, ReadDecision, assess_read,
    CommandIdentity, CommandEvidence, ReceiptKind, CommandDecision, assess_command,
)

NOW = datetime(2030, 1, 1, tzinfo=timezone.utc)


class ReadTests(unittest.TestCase):
    def setUp(self):
        self.scope = ReadScope("account-fixture", "session-fixture", "request-2")
        self.anchor = OrderSnapshot("order-fixture", 3, 1, "accepted")
        self.e = ReadEvidence(self.scope, ReadKind.CURRENT, NOW, self.anchor)

    def assess(self, evidence, **kw):
        return assess_read(evidence, expected_scope=self.scope, now=kw.get("now", NOW),
                           max_age_sec=30, anchor=self.anchor)

    def test_error_and_unknown_never_absent(self):
        self.assertEqual(self.assess(None), ReadDecision.KEEP_LAST_UNCONFIRMED)
        e = ReadEvidence(self.scope, ReadKind.ERROR, None)
        self.assertEqual(self.assess(e), ReadDecision.KEEP_LAST_UNCONFIRMED)
        with self.assertRaises(InvalidDraft):
            ReadEvidence(self.scope, ReadKind.CURRENT, NOW)

    def test_explicit_absence_only(self):
        e = ReadEvidence(self.scope, ReadKind.ABSENT, NOW)
        self.assertEqual(self.assess(e), ReadDecision.CONFIRMED_ABSENT)
        with self.assertRaises(InvalidDraft):
            replace(e, snapshot=self.anchor)

    def test_scope_precedes_even_old_auth_failure(self):
        for name in ("account_ref", "session_ref", "request_ref"):
            with self.subTest(name=name):
                scope = replace(self.scope, **{name:"old"})
                self.assertEqual(self.assess(replace(self.e, scope=scope)), ReadDecision.REJECTED_SCOPE)
                e = ReadEvidence(scope, ReadKind.AUTH_REQUIRED, None)
                self.assertEqual(self.assess(e), ReadDecision.REJECTED_SCOPE)

    def test_access_error_does_not_keep_visible_private_data(self):
        for kind, decision in ((ReadKind.AUTH_REQUIRED, ReadDecision.HIDE_AND_REAUTHENTICATE),
                               (ReadKind.ACCESS_REVOKED, ReadDecision.HIDE_AND_RECONCILE_ACCESS)):
            self.assertEqual(self.assess(ReadEvidence(self.scope, kind, None)), decision)

    def test_time_boundary_and_future(self):
        self.assertEqual(self.assess(self.e), ReadDecision.CURRENT_ORDER)
        for delta in (-1, 30, 31):
            self.assertEqual(self.assess(self.e, now=NOW+timedelta(seconds=delta)), ReadDecision.REJECTED_TIME)
        with self.assertRaises(InvalidDraft):
            self.assess(self.e, now=NOW.replace(tzinfo=None))

    def test_order_generation_revision_and_conflicting_same_version(self):
        cases = [(dict(order_ref="other"), ReadDecision.RECONCILE_ORDER),
                 (dict(assignment_generation=2), ReadDecision.RECONCILE_GENERATION),
                 (dict(revision=2), ReadDecision.REJECTED_REVISION),
                 (dict(state="done"), ReadDecision.REJECTED_REVISION)]
        for changes, decision in cases:
            self.assertEqual(self.assess(replace(self.e, snapshot=replace(self.anchor, **changes))), decision)

    def test_terminal_or_resumed_snapshot_does_not_clear_state(self):
        for state in ("done", "expired", "cancelled", "searching"):
            e = replace(self.e, snapshot=replace(self.anchor, revision=4, state=state))
            self.assertEqual(self.assess(e), ReadDecision.CURRENT_ORDER)
            self.assertEqual(self.anchor.state, "accepted")

    def test_invalid_fields_and_private_repr(self):
        for change in (dict(revision=True), dict(assignment_generation=-1), dict(state="unknown")):
            with self.assertRaises(InvalidDraft):
                replace(self.anchor, **change)
        self.assertNotIn("account-fixture", repr(self.scope))
        self.assertNotIn("order-fixture", repr(self.e))


class CommandTests(unittest.TestCase):
    def setUp(self):
        self.cmd = CommandIdentity("account-fixture", "stop", "key-fixture", b'{"stopped":true}')
        self.e = CommandEvidence(self.cmd, ReceiptKind.ABSENT, NOW, NOW+timedelta(seconds=20))

    def assess(self, evidence, **kw):
        return assess_command(kw.get("original", self.cmd), kw.get("proposed", self.cmd), evidence,
                              now=kw.get("now", NOW), max_evidence_age_sec=30)

    def test_unknown_and_lost_original_reconcile(self):
        self.assertEqual(self.assess(None), CommandDecision.RECONCILE)
        self.assertEqual(self.assess(self.e, original=None), CommandDecision.RECONCILE)

    def test_changed_body_key_account_action_conflict(self):
        for changes in (dict(body=b'{"stopped":false}'), dict(idempotency_key="new-key"),
                        dict(account_ref="other"), dict(action="cancel")):
            self.assertEqual(self.assess(self.e, proposed=replace(self.cmd, **changes)),
                             CommandDecision.IDENTITY_CONFLICT)
        e = replace(self.e, command=replace(self.cmd, body=b"other"))
        self.assertEqual(self.assess(e), CommandDecision.IDENTITY_CONFLICT)

    def test_receipt_not_inferred_from_active_order_absence(self):
        with self.assertRaises(InvalidDraft):
            self.assess(ReadEvidence(ReadScope("a", "s", "r"), ReadKind.ABSENT, NOW))
        for kind in (ReceiptKind.APPLIED, ReceiptKind.REJECTED):
            self.assertEqual(self.assess(replace(self.e, kind=kind)), CommandDecision.READ_EXISTING_RECEIPT)

    def test_replay_requires_separate_current_guarantee(self):
        self.assertEqual(self.assess(self.e), CommandDecision.SAME_KEY_REPLAY_CANDIDATE)
        self.assertEqual(self.assess(replace(self.e, replay_guarantee_until=None)), CommandDecision.RECONCILE)
        for seconds in (-1, 20, 30):
            self.assertEqual(self.assess(self.e, now=NOW+timedelta(seconds=seconds)), CommandDecision.RECONCILE)

    def test_repeated_assessment_no_mutation(self):
        original = self.e
        for _ in range(3):
            self.assertEqual(self.assess(self.e), CommandDecision.SAME_KEY_REPLAY_CANDIDATE)
        self.assertEqual(self.e, original)
        self.assertNotIn("key-fixture", repr(self.cmd))
        self.assertNotIn("stopped", repr(self.cmd))

    def test_invalid_evidence(self):
        for changes in (dict(kind="absent"), dict(observed_at=NOW.replace(tzinfo=None)),
                        dict(replay_guarantee_until=NOW)):
            with self.assertRaises(InvalidDraft):
                replace(self.e, **changes)
        with self.assertRaises(InvalidDraft):
            replace(self.cmd, body=b"")


if __name__ == "__main__":
    unittest.main()
