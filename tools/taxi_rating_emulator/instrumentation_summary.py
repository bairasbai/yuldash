"""Fail-closed AndroidJUnitRunner result validation without emitting raw output."""
import json
import sys
from collections import Counter
from pathlib import Path

PACKAGE = "com.yuldash.app."
DRIVER = PACKAGE + "TaxiDriverCompletedInstrumentedTest"
PASSENGER = PACKAGE + "TaxiPostTripInstrumentedTest"
EXPECTED = frozenset(
    [(DRIVER, name) for name in (
        "completed_opensWithIncomeFirstAndFixedReturnAction",
        "completed_submitsPassengerRatingAndKeepsRecoveryActions",
        "completed_keepsBashkirCoreCopyVisible",
        "completed_recoversSavedStarsEvenWhenRatingWindowClosed",
        "completed_deniedRatingKeepsRecoveryActions",
        "completed_lostWriteAnswerReReadsWithoutSecondWrite",
        "completed_unconfirmedWriteRechecksButNeverResends",
        "passengerCompleted_readsOwnSavedRatingAndDoesNotSubmit",
    )] + [(PASSENGER, name) for name in (
        "passengerCompleted_showsCalmRatingFirst",
        "receipt_showsPremiumDocumentAndRecoveryActions",
    )]
)
MAX_RAW = 2_000_000


def validate(data, adb_exit):
    starts = Counter()
    passed = Counter()
    status = {}
    terminal = False
    issue = None
    try:
        if adb_exit:
            raise ValueError("adb_nonzero")
        if len(data) > MAX_RAW:
            raise ValueError("output_oversize")
        for line in data.decode("utf-8", errors="replace").splitlines():
            if line.startswith(("INSTRUMENTATION_FAILED:", "INSTRUMENTATION_ABORTED:",
                                "INSTRUMENTATION_RESULT: shortMsg=")):
                raise ValueError("runner_abort")
            if (terminal and line.startswith(("INSTRUMENTATION_STATUS:", "INSTRUMENTATION_STATUS_CODE:",
                                               "INSTRUMENTATION_CODE:"))):
                raise ValueError("events_after_completion")
            if line.startswith("INSTRUMENTATION_STATUS: "):
                key, _, value = line.removeprefix("INSTRUMENTATION_STATUS: ").partition("=")
                if key in ("class", "test"):
                    if key in status:
                        raise ValueError("duplicate_field")
                    status[key] = value
            elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
                test = (status.get("class"), status.get("test"))
                status.clear()
                code = line.removeprefix("INSTRUMENTATION_STATUS_CODE: ")
                if test not in EXPECTED:
                    raise ValueError("unexpected_test")
                if code == "1":
                    starts[test] += 1
                    if starts[test] != 1:
                        raise ValueError("duplicate_test")
                elif code == "0":
                    if starts[test] != 1 or passed[test]:
                        raise ValueError("unpaired_success")
                    passed[test] = 1
                else:
                    raise ValueError("test_failed_or_skipped")
            elif line.startswith("INSTRUMENTATION_CODE: "):
                if line != "INSTRUMENTATION_CODE: -1" or status:
                    raise ValueError("runner_completion_invalid")
                terminal = True
        if not terminal:
            raise ValueError("missing_runner_completion")
        if set(starts) != EXPECTED or set(passed) != EXPECTED:
            raise ValueError("wrong_test_set")
    except ValueError as exc:
        # All exception values above are static safe enums.
        issue = str(exc)
    return {
        "status": "passed" if issue is None else "failed",
        "expected": len(EXPECTED), "passed": len(passed),
        "reason": issue,
        "successful_tests": ["#".join(test) for test in sorted(passed)],
    }


if __name__ == "__main__":
    raw, adb_exit, target = sys.argv[1:]
    with Path(raw).open("rb") as records:
        report = validate(records.read(MAX_RAW + 1), int(adb_exit))
    summary = json.dumps(report, indent=2) + "\n"
    Path(target).write_text(summary, encoding="utf-8")
    print(f"TF11 instrumentation: {report['status']} ({report['passed']}/{report['expected']})"
          + (f" [{report['reason']}]" if report["reason"] else ""))
    sys.exit(0 if report["status"] == "passed" else 1)
