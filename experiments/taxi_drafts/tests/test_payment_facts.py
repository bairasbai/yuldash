from dataclasses import replace
from datetime import datetime, timedelta, timezone
from itertools import permutations
import unittest

from experiments.taxi_drafts.contracts import InvalidDraft
from experiments.taxi_drafts.payment_facts import (
    PaymentScope, MoneyKind, Confirmation, MoneyMethod, OperationFact, project_payment_facts,
)

NOW = datetime(2030, 1, 1, tzinfo=timezone.utc)


class PaymentFactTests(unittest.TestCase):
    def setUp(self):
        self.scope = PaymentScope("order-fixture", "payer-fixture", "payee-fixture", "RUB")
        self.pay = OperationFact("pay-fixture", self.scope, MoneyKind.COLLECTION, 100,
                                 Confirmation.CONFIRMED, MoneyMethod.CASH, NOW, evidence_ref="cash-receipt-fixture")
        self.refund = OperationFact("refund-fixture", self.scope, MoneyKind.REFUND, 30,
                                    Confirmation.CONFIRMED, MoneyMethod.CASH, NOW,
                                    "pay-fixture", "cash-return-fixture")

    def project(self, *facts):
        return project_payment_facts(tuple(facts), expected_scope=self.scope, as_of=NOW)

    def assert_incomplete(self, result):
        self.assertTrue(result.issues)
        self.assertIsNone(result.confirmed_received_kop)
        self.assertIsNone(result.confirmed_returned_kop)
        self.assertIsNone(result.unresolved_refund_reserve_kop)
        self.assertIsNone(result.remaining_confirmed_return_capacity_kop)
        self.assertIsNone(result.unconfirmed_operation_count)

    def test_projection_has_no_paid_or_debt_state(self):
        r = self.project(self.pay, self.refund)
        self.assertEqual((r.confirmed_received_kop, r.confirmed_returned_kop,
                          r.remaining_confirmed_return_capacity_kop), (100, 30, 70))
        for name in ("paid", "order_done", "amount_due", "driver_debt"):
            self.assertFalse(hasattr(r, name))
        self.assertIn("Synthetic only", r.limitation)

    def test_pending_and_unknown_are_not_money_received(self):
        for status in (Confirmation.PENDING, Confirmation.UNKNOWN, Confirmation.FAILED):
            r = self.project(replace(self.pay, status=status, evidence_ref=None))
            self.assertEqual(r.confirmed_received_kop, 0)
            self.assertEqual(r.remaining_confirmed_return_capacity_kop, 0)

    def test_duplicate_payment_and_refund_not_counted_twice(self):
        self.assertEqual(self.project(self.pay, self.refund), self.project(self.pay, self.pay, self.refund, self.refund))
        pending = replace(self.refund, status=Confirmation.PENDING, evidence_ref=None)
        self.assertEqual(self.project(self.pay, pending).unresolved_refund_reserve_kop, 30)
        self.assertEqual(self.project(self.pay, pending, pending).unresolved_refund_reserve_kop, 30)

    def test_same_id_changed_body_or_status_is_conflict(self):
        for change in (dict(amount_kop=99), dict(status=Confirmation.PENDING), dict(evidence_ref="other")):
            r = self.project(self.pay, replace(self.pay, **change))
            self.assert_incomplete(r)
            self.assertIn("operation_identity_conflict", r.issues)

    def test_same_confirmation_cannot_fund_two_operations(self):
        duplicate = replace(self.pay, operation_ref="new-operation-id")
        r = self.project(self.pay, duplicate)
        self.assert_incomplete(r)
        self.assertIn("confirmation_evidence_reused", r.issues)
        self.assertEqual(r, self.project(duplicate, self.pay))

    def test_input_order_independent_including_conflicts(self):
        pending = replace(self.refund, operation_ref="other-refund", status=Confirmation.UNKNOWN,
                          amount_kop=20, evidence_ref=None)
        expected = self.project(self.pay, self.refund, pending)
        for sequence in permutations((self.pay, self.refund, pending)):
            self.assertEqual(self.project(*sequence), expected)
        conflict = replace(self.pay, amount_kop=90)
        self.assertEqual(self.project(self.pay, conflict), self.project(conflict, self.pay))

    def test_confirmed_pending_unknown_refunds_share_capacity(self):
        for status in (Confirmation.CONFIRMED, Confirmation.PENDING, Confirmation.UNKNOWN):
            extra = replace(self.refund, operation_ref="other-refund", amount_kop=71, status=status, evidence_ref="another-receipt")
            r = self.project(self.pay, self.refund, extra)
            self.assert_incomplete(r)
            self.assertIn("refund_capacity_exceeded", r.issues)
        exact = replace(self.refund, operation_ref="other-refund", amount_kop=70, status=Confirmation.UNKNOWN)
        self.assertEqual(self.project(self.pay, self.refund, exact).remaining_confirmed_return_capacity_kop, 0)

    def test_failed_refund_does_not_reserve(self):
        failed = replace(self.refund, amount_kop=200, status=Confirmation.FAILED, evidence_ref=None)
        r = self.project(self.pay, failed)
        self.assertEqual(r.confirmed_returned_kop, 0)
        self.assertEqual(r.unresolved_refund_reserve_kop, 0)
        self.assertEqual(r.remaining_confirmed_return_capacity_kop, 100)

    def test_refund_needs_confirmed_original(self):
        self.assert_incomplete(self.project(self.refund))
        for status in (Confirmation.PENDING, Confirmation.UNKNOWN, Confirmation.FAILED):
            self.assert_incomplete(self.project(replace(self.pay, status=status), self.refund))

    def test_scope_and_method_must_match(self):
        for changes in (dict(order_ref="other"), dict(payer_ref="other"), dict(payee_ref="other")):
            foreign = replace(self.refund, scope=replace(self.scope, **changes))
            self.assert_incomplete(self.project(self.pay, foreign))
        self.assert_incomplete(self.project(self.pay, replace(self.refund, method=MoneyMethod.DIRECT_SBP)))

    def test_refund_direction_and_timestamps(self):
        swapped = replace(self.scope, payer_ref=self.scope.payee_ref, payee_ref=self.scope.payer_ref)
        self.assert_incomplete(self.project(self.pay, replace(self.refund, scope=swapped)))
        self.assert_incomplete(self.project(self.pay, replace(self.refund, occurred_at=NOW-timedelta(seconds=1))))
        self.assert_incomplete(self.project(replace(self.pay, occurred_at=NOW+timedelta(seconds=1))))

    def test_cash_confirmation_needs_explicit_receipt_not_client_claim(self):
        with self.assertRaises(InvalidDraft):
            replace(self.pay, evidence_ref=None)
        with self.assertRaises(InvalidDraft):
            replace(self.refund, evidence_ref="  ")
        with self.assertRaises(InvalidDraft):
            replace(self.pay, status=True)
        with self.assertRaises(InvalidDraft):
            self.project({"client_says_paid": True})

    def test_invalid_amounts_ids_times(self):
        for value in (True, 0, -1, 0.5, float("nan"), float("inf")):
            with self.assertRaises(InvalidDraft):
                replace(self.pay, amount_kop=value)
        with self.assertRaises(InvalidDraft):
            replace(self.pay, operation_ref=" ")
        with self.assertRaises(InvalidDraft):
            replace(self.pay, occurred_at=NOW.replace(tzinfo=None))
        with self.assertRaises(InvalidDraft):
            project_payment_facts((self.pay,), expected_scope=self.scope, as_of=NOW.replace(tzinfo=None))

    def test_all_methods_are_fixture_only_and_repr_private(self):
        for method in MoneyMethod:
            p, r = replace(self.pay, method=method), replace(self.refund, method=method)
            self.assertEqual(self.project(p, r).confirmed_returned_kop, 30)
        for secret in ("order-fixture", "payer-fixture", "pay-fixture", "cash-receipt-fixture"):
            self.assertNotIn(secret, repr(self.pay))

    def test_empty_projection_no_fabricated_payment(self):
        r = self.project()
        self.assertEqual(r.confirmed_received_kop, 0)
        self.assertEqual(r.confirmed_returned_kop, 0)
        self.assertEqual(r.unconfirmed_operation_count, 0)


if __name__ == "__main__":
    unittest.main()
