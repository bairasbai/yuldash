import unittest

from instrumentation_summary import EXPECTED, MAX_RAW, RECEIPT_TEST, validate


def event(test, status):
    return (f"INSTRUMENTATION_STATUS: class={test[0]}\r\n"
            f"INSTRUMENTATION_STATUS: test={test[1]}\r\n"
            f"INSTRUMENTATION_STATUS_CODE: {status}\r\n")


def complete(events):
    return (events + "INSTRUMENTATION_CODE: -1\r\n").encode()


class InstrumentationSummaryTest(unittest.TestCase):
    def setUp(self):
        self.tests = sorted(EXPECTED)
        self.all_results = "".join(event(case, 1) + event(case, 0) for case in self.tests)

    def test_exact_both_classes_pass_and_arbitrary_output_is_not_returned(self):
        report = validate(complete(self.all_results + "unstructured private diagnostic\n"), 0)
        self.assertEqual((report["status"], report["expected"], report["passed"]), ("passed", 10, 10))
        self.assertNotIn("unstructured", repr(report))
        self.assertEqual(len(report["successful_tests"]), 10)

    def test_exit_code_zero_and_summary_text_alone_are_not_pass(self):
        report = validate(complete("OK (10 tests)\n"), 0)
        self.assertEqual(report["reason"], "wrong_test_set")

    def test_missing_test_is_not_pass(self):
        report = validate(complete("".join(event(case, 1) + event(case, 0) for case in self.tests[1:])), 0)
        self.assertEqual(report["reason"], "wrong_test_set")
        self.assertEqual(report["passed"], 9)

    def test_unexpected_name_cannot_evict_expected_test_or_be_reported(self):
        extra = ("unstructured.private.class", "hidden")
        report = validate(complete(event(extra, 1) + self.all_results), 0)
        self.assertEqual(report["reason"], "unexpected_test")
        self.assertNotIn("unstructured.private.class", repr(report))

    def test_nonzero_exit_fails_even_with_every_pass_record(self):
        self.assertEqual(validate(complete(self.all_results), 124)["reason"], "adb_nonzero")

    def test_require_runner_completion_and_no_duplicate_terminal(self):
        self.assertEqual(validate(self.all_results.encode(), 0)["reason"], "missing_runner_completion")
        self.assertEqual(validate(complete(self.all_results + "INSTRUMENTATION_CODE: -1\n"), 0)["reason"],
                         "events_after_completion")

    def test_failure_skip_or_unpaired_record_are_rejected(self):
        for code in (-1, -2, -3, -4):
            report = validate(complete(event(self.tests[0], 1) + event(self.tests[0], code)), 0)
            self.assertEqual(report["reason"], "test_failed_or_skipped")
        self.assertEqual(validate(complete(event(self.tests[0], 0)), 0)["reason"], "unpaired_success")

    def test_duplicate_pass_and_abort_are_rejected(self):
        self.assertEqual(validate(complete(self.all_results + event(self.tests[0], 0)), 0)["reason"],
                         "unpaired_success")
        report = validate(complete(self.all_results + "INSTRUMENTATION_RESULT: shortMsg=private cause\n"), 0)
        self.assertEqual(report["reason"], "runner_abort")
        self.assertNotIn("private cause", repr(report))

    def test_output_bound_and_duplicate_metadata(self):
        self.assertEqual(validate(b"x" * (MAX_RAW + 1), 0)["reason"], "output_oversize")
        report = validate(complete("INSTRUMENTATION_STATUS: test=x\nINSTRUMENTATION_STATUS: test=y\n"), 0)
        self.assertEqual(report["reason"], "duplicate_field")


    def test_exact_stage_is_attached_only_to_a_real_receipt_assertion_failure(self):
        case = RECEIPT_TEST
        for stage in ("HEADER", "AMOUNT", "ADDRESS", "THANKS"):
            first_line = f"INSTRUMENTATION_STATUS: stack=java.lang.AssertionError: TF11_RECEIPT_ASSERTION_{stage}\n"
            failure = event(case, -2).replace("INSTRUMENTATION_STATUS_CODE:", first_line + "private cause\nINSTRUMENTATION_STATUS_CODE:")
            report = validate(complete(event(case, 1) + failure), 0)
            self.assertEqual(report["status"], "failed")
            self.assertEqual(report["reason"], "test_failed_or_skipped")
            self.assertEqual(report["failed_receipt_assertion"], stage.lower())
            self.assertNotIn("private cause", repr(report))

    def test_success_skip_or_other_method_cannot_claim_receipt_failure_stage(self):
        marker = "INSTRUMENTATION_STATUS: stack=java.lang.AssertionError: TF11_RECEIPT_ASSERTION_ADDRESS\n"
        cases = [(RECEIPT_TEST, -3), (RECEIPT_TEST, -4),
                 (next(test for test in self.tests if test != RECEIPT_TEST), -2)]
        for case, code in cases:
            output = event(case, 1) + event(case, code).replace("INSTRUMENTATION_STATUS_CODE:",
                                                              marker + "INSTRUMENTATION_STATUS_CODE:")
            self.assertIsNone(validate(complete(output), 0)["failed_receipt_assertion"])
        success = event(RECEIPT_TEST, 0)
        data = complete(self.all_results.replace(success, success.replace(
            "INSTRUMENTATION_STATUS_CODE:", marker + "INSTRUMENTATION_STATUS_CODE:"
        ), 1))
        report = validate(data, 0)
        self.assertEqual(report["status"], "passed")
        self.assertIsNone(report["failed_receipt_assertion"])

    def test_stage_in_untrusted_text_duplicate_field_or_no_start_is_not_emitted(self):
        marker = "INSTRUMENTATION_STATUS: stack=java.lang.AssertionError: TF11_RECEIPT_ASSERTION_HEADER\n"
        other_inputs = [
            ("INSTRUMENTATION_STATUS: stack=private assertion\njava.lang.AssertionError: TF11_RECEIPT_ASSERTION_HEADER\n", True),
            ("INSTRUMENTATION_STATUS: stream=java.lang.AssertionError: TF11_RECEIPT_ASSERTION_HEADER\n", True),
            (marker + marker, True), (marker, False),
        ]
        for fields, started in other_inputs:
            outcome = event(RECEIPT_TEST, -2).replace("INSTRUMENTATION_STATUS_CODE:",
                                                      fields + "INSTRUMENTATION_STATUS_CODE:")
            report = validate(complete((event(RECEIPT_TEST, 1) if started else "") + outcome), 0)
            self.assertEqual(report["status"], "failed")
            self.assertIsNone(report["failed_receipt_assertion"])
            self.assertNotIn("private assertion", repr(report))


if __name__ == "__main__":
    unittest.main()
