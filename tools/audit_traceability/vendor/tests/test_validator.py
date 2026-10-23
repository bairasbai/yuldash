import copy
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from traceability.io import read_document
from traceability.schema_check import SchemaContractError, load_schema, validate_structure
from traceability.validator import validate


ROOT = Path(__file__).resolve().parent.parent
DEMO = ROOT / "demo"


class AcceptanceGateTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="traceability-test-")
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name) / "demo"
        shutil.copytree(DEMO, self.root, ignore=shutil.ignore_patterns("__pycache__"))
        self.data = read_document(self.root / "accepted.json")

    def check(self, data=None, **kwargs):
        return validate(self.data if data is None else data, self.root, **kwargs)

    def assertBlocked(self, code, data=None, **kwargs):
        result = self.check(data, **kwargs)
        self.assertFalse(result["gate_passed"])
        self.assertFalse(result["valid"])
        self.assertFalse(result["accepted"])
        self.assertIn(code, {row["code"] for row in result["blockers"]}, result)
        return result

    def test_valid_accepted_synthetic_demo(self):
        result = self.check()
        self.assertTrue(result["gate_passed"])
        self.assertTrue(result["implemented"])
        self.assertTrue(result["accepted"])
        self.assertEqual(result["computed_state"], "accepted")
        self.assertEqual(result["diagnostics"]["accepted_requirements"], 2)
        self.assertEqual(result["scope"], "demo")
        self.assertIn("SYNTHETIC DEMO ONLY", result["limitations"][0])

    def test_schema_missing_property(self):
        del self.data["tests"][0]["target_commit"]
        self.assertBlocked("SCHEMA")

    def test_schema_missing_sha256(self):
        del self.data["artifacts"][0]["sha256"]
        self.assertBlocked("SCHEMA")

    def test_schema_types_enums_patterns_and_unknown_fields(self):
        mutations = [
            lambda d: d.update(schema_version="2.0"),
            lambda d: d.update(features="not an array"),
            lambda d: d["audit"].update(reported_score=True),
            lambda d: d["audit"].update(reported_score=101),
            lambda d: d["audit"].update(reported_score=float("nan")),
            lambda d: d["tests"][0].update(state="done"),
            lambda d: d["features"][0].update(classification="parity"),
            lambda d: d["artifacts"][0].update(sha256="abc"),
            lambda d: d["implementations"][0].update(commit="HEAD"),
            lambda d: d["requirements"][0].update(text="  \n  "),
            lambda d: d["audit"].update(untrusted_status="accepted"),
        ]
        for mutate in mutations:
            with self.subTest(mutation=mutate):
                data = copy.deepcopy(self.data)
                mutate(data)
                self.assertBlocked("SCHEMA", data)

    def test_schema_engine_rejects_unsupported_keywords(self):
        schema = load_schema()
        schema["format"] = "date-time"
        with self.assertRaises(SchemaContractError):
            validate_structure(self.data, schema)

    def test_schema_rejects_whitespace_after_ids_commits_hashes_and_timestamps(self):
        for ending in ("\n", "\r\n", " ", "\t", "\u2028"):
            for kind in ("id", "all-commits", "sha256", "timestamp"):
                with self.subTest(ending=repr(ending), kind=kind):
                    data = copy.deepcopy(self.data)
                    if kind == "id":
                        data["audit"]["id"] += ending
                    elif kind == "all-commits":
                        data["audit"]["target_commit"] += ending
                        for group, field in (("implementations", "commit"), ("tests", "target_commit"), ("acceptances", "target_commit")):
                            for row in data[group]:
                                row[field] += ending
                    elif kind == "sha256":
                        data["artifacts"][0]["sha256"] += ending
                    else:
                        data["tests"][0]["started_at"] += ending
                    self.assertBlocked("SCHEMA", data)

    def test_timestamp_format_rejects_normalized_offsets_and_extra_precision(self):
        for value in ("2026-10-23T09:00:00+00:99", "2026-10-23T09:00:00+01:99",
                      "2026-10-23T09:00:00+00:60", "2026-10-23T09:00:00-00:99",
                      "2026-10-23T09:00:00+24:00", "2026-10-23T09:00:60Z",
                      "2026-10-23T09:00:00.1234567Z", "2026-10-23T09:00:00.123456789Z"):
            with self.subTest(timestamp=value):
                data = copy.deepcopy(self.data)
                data["tests"][0]["started_at"] = value
                self.assertBlocked("SCHEMA", data)

    def test_review_order_cannot_hide_precision_beyond_supported_resolution(self):
        self.data["tests"][0]["started_at"] = "2026-10-23T09:00:00.1234560Z"
        self.data["tests"][0]["finished_at"] = "2026-10-23T09:00:00.1234569Z"
        self.data["acceptances"][0]["reviewed_at"] = "2026-10-23T09:00:00.1234561Z"
        self.assertBlocked("SCHEMA")

    def test_schema_engine_never_follows_remote_references(self):
        schema = load_schema()
        schema["properties"]["audit"] = {"$ref": "https://invalid.example/schema"}
        with self.assertRaises(SchemaContractError):
            validate_structure(self.data, schema)

    def test_duplicate_entity_ids(self):
        self.data["features"].append({**self.data["features"][0], "title": "DEMO duplicate identity"})
        self.assertBlocked("DUPLICATE_ID")

    def test_duplicate_ids_across_entity_types(self):
        self.data["people"][0]["id"] = self.data["audit"]["id"]
        self.assertBlocked("DUPLICATE_ID")

    def test_duplicate_reference_entries(self):
        self.data["requirements"][0]["test_ids"] *= 2
        self.assertBlocked("SCHEMA")

    def test_dangling_references_in_every_link_type(self):
        links = [("audit", "author_ids"), ("evidence", "artifact_ids"), ("features", "requirement_ids"),
                 ("requirements", "feature_id"), ("requirements", "evidence_ids"),
                 ("requirements", "implementation_ids"), ("requirements", "test_ids"), ("requirements", "acceptance_ids"),
                 ("implementations", "author_ids"), ("tests", "implementation_ids"), ("tests", "executor_id"),
                 ("acceptances", "reviewer_id"), ("acceptances", "reviewed_artifact_ids")]
        for group, field in links:
            with self.subTest(group=group, field=field):
                data = copy.deepcopy(self.data)
                row = data["audit"] if group == "audit" else data[group][0]
                row[field] = ["ID-DEMO-MISSING"] if isinstance(row[field], list) else "ID-DEMO-MISSING"
                self.assertBlocked("DANGLING_REFERENCE", data)

    def test_links_must_be_reciprocal(self):
        self.data["requirements"][0]["test_ids"] = []
        self.assertBlocked("LINK_MISMATCH")

    def test_feature_cannot_claim_someone_elses_requirement(self):
        self.data["features"][0]["requirement_ids"] = [self.data["requirements"][1]["id"]]
        self.assertBlocked("LINK_MISMATCH")

    def test_missing_local_file(self):
        (self.root / self.data["artifacts"][0]["path"]).unlink()
        self.assertBlocked("ARTIFACT_MISSING")

    def test_wrong_sha256(self):
        self.data["artifacts"][0]["sha256"] = "0" * 64
        self.assertBlocked("HASH_MISMATCH")

    def test_modified_artifact_bytes(self):
        with (self.root / self.data["artifacts"][0]["path"]).open("ab") as stream:
            stream.write(b"modified")
        self.assertBlocked("HASH_MISMATCH")

    def test_absolute_traversal_and_nonportable_paths_are_rejected(self):
        for path in ("/etc/passwd", "../outside", "artifacts/../outside", "artifacts//x", "./application.py", "C:\\file"):
            with self.subTest(path=path):
                data = copy.deepcopy(self.data)
                data["artifacts"][0]["path"] = path
                self.assertBlocked("ARTIFACT_PATH", data)

    def test_symlink_escape_is_rejected(self):
        outside = Path(self.directory.name) / "outside.txt"
        outside.write_text("outside the allowed artifact root")
        (self.root / "escape.txt").symlink_to(outside)
        self.data["artifacts"][0]["path"] = "escape.txt"
        self.assertBlocked("ARTIFACT_PATH")

    def test_symlink_loop_is_a_reported_blocker(self):
        (self.root / "loop.txt").symlink_to("loop.txt")
        self.data["artifacts"][0]["path"] = "loop.txt"
        self.assertBlocked("ARTIFACT_IO")

    def test_artifact_kinds_cannot_replace_execution_with_static_source(self):
        execution = self.data["tests"][0]["artifact_ids"][0]
        next(row for row in self.data["artifacts"] if row["id"] == execution)["kind"] = "source"
        self.assertBlocked("ARTIFACT_KIND")

    def test_commit_mismatch_at_each_stage(self):
        for group, field in (("implementations", "commit"), ("tests", "target_commit"), ("acceptances", "target_commit")):
            with self.subTest(group=group):
                data = copy.deepcopy(self.data)
                data[group][0][field] = "e" * 40
                self.assertBlocked("COMMIT_MISMATCH", data)

    def test_proposed_and_unknown_tests_are_not_executed(self):
        for state in ("proposed", "unknown"):
            with self.subTest(state=state):
                data = copy.deepcopy(self.data)
                data["tests"][0]["state"] = state
                result = self.assertBlocked("TEST_NOT_EXECUTED", data)
                self.assertTrue(result["requirements"][0]["implemented"])
                self.assertFalse(result["requirements"][0]["accepted"])

    def test_failed_and_unknown_test_results_block_acceptance(self):
        for state in ("failed", "unknown"):
            with self.subTest(result=state):
                data = copy.deepcopy(self.data)
                data["tests"][0]["result"] = state
                self.assertBlocked("TEST_NOT_PASSED", data)

    def test_execution_certainty_must_be_observed(self):
        self.data["tests"][0]["certainty"] = "inferred"
        self.assertBlocked("TEST_NOT_OBSERVED")

    def test_proposed_implementation_is_not_implemented(self):
        self.data["implementations"][0]["state"] = "proposed"
        self.assertBlocked("IMPLEMENTATION_NOT_IMPLEMENTED")

    def test_source_inference_and_unknown_cannot_satisfy_observation(self):
        for certainty in ("inferred", "unknown"):
            with self.subTest(certainty=certainty):
                data = copy.deepcopy(self.data)
                data["evidence"][0]["certainty"] = certainty
                self.assertBlocked("SOURCE_NOT_OBSERVED", data)

    def test_auxiliary_inferred_unknown_are_visible_without_becoming_observed(self):
        row = self.check()["requirements"][0]
        self.assertEqual(row["evidence_certainty"], {"observed": 1, "inferred": 1, "unknown": 1})
        self.assertEqual(row["observed_support_ids"], ["EVD-DEMO-FAVORITES"])

    def test_no_source_evidence(self):
        self.data["requirements"][0]["evidence_ids"] = []
        self.assertBlocked("SOURCE_MISSING")

    def test_acceptance_requires_a_separate_person(self):
        for reviewer in ("PERSON-DEMO-AUTHOR", "PERSON-DEMO-TESTER"):
            with self.subTest(reviewer=reviewer):
                data = copy.deepcopy(self.data)
                data["acceptances"][0]["reviewer_id"] = reviewer
                self.assertBlocked("REVIEWER_NOT_INDEPENDENT", data)

    def test_pending_or_rejected_acceptance_is_not_acceptance(self):
        for decision in ("pending", "rejected"):
            with self.subTest(decision=decision):
                data = copy.deepcopy(self.data)
                data["acceptances"][0]["decision"] = decision
                self.assertBlocked("ACCEPTANCE_NOT_ACCEPTED", data)

    def test_acceptance_cannot_be_inferred(self):
        self.data["acceptances"][0]["certainty"] = "unknown"
        self.assertBlocked("ACCEPTANCE_NOT_OBSERVED")

    def test_real_execution_artifacts_must_be_declared_reviewed(self):
        self.data["acceptances"][0]["reviewed_artifact_ids"].remove("ART-DEMO-EXECUTION-FAVORITES")
        self.assertBlocked("EXECUTION_REVIEW_INCOMPLETE")

    def test_review_cannot_predate_execution(self):
        self.data["acceptances"][0]["reviewed_at"] = "2000-01-01T00:00:00Z"
        self.assertBlocked("ACCEPTANCE_BEFORE_EXECUTION")

    def test_timestamps_must_be_real_and_ordered(self):
        self.data["tests"][0]["started_at"] = "2026-02-30T00:00:00Z"
        self.assertBlocked("TIMESTAMP")
        self.data["tests"][0]["started_at"] = "2099-01-01T00:00:00Z"
        self.assertBlocked("TIMESTAMP_ORDER")

    def test_skipped_p0_stays_blocking_even_with_approved_decision(self):
        data = read_document(self.root / "bad-skipped-p0.json")
        result = self.assertBlocked("SKIPPED_BLOCKER", data)
        self.assertTrue(result["skips"][0]["approved"])
        self.assertTrue(result["skips"][0]["blocks_gate"])
        self.assertIn("REQ-DEMO-BOOKING", result["diagnostics"]["mandatory_open_ids"])

    def test_skipped_non_p0_must_stays_blocking(self):
        data = read_document(self.root / "bad-skipped-p0.json")
        data["requirements"][1]["priority"] = "P2"
        self.assertBlocked("SKIPPED_BLOCKER", data)

    def test_p0_is_blocking_even_when_level_is_may(self):
        data = read_document(self.root / "bad-skipped-p0.json")
        data["requirements"][1]["level"] = "may"
        self.assertBlocked("SKIPPED_BLOCKER", data)

    def test_skip_without_explicit_decision_cannot_pass(self):
        self.data["requirements"][0]["disposition"] = "skipped"
        self.assertBlocked("SKIP_DECISION_MISSING")

    def test_optional_skip_is_explicit_visible_and_not_accepted(self):
        data = read_document(self.root / "accepted-with-optional-skip.json")
        result = self.check(data)
        self.assertTrue(result["gate_passed"], result["blockers"])
        self.assertEqual(result["computed_state"], "accepted_with_skips")
        self.assertFalse(result["accepted"])
        self.assertFalse(result["requirements"][-1]["accepted"])
        self.assertEqual(len(result["skips"]), 1)
        self.assertTrue(result["skips"][0]["reason"])
        self.assertEqual(result["diagnostics"]["total_requirements"], 3)
        self.assertEqual(result["diagnostics"]["accepted_percent"], 66.67)

    def test_pending_skip_decision_cannot_pass(self):
        data = read_document(self.root / "accepted-with-optional-skip.json")
        data["skip_decisions"][0]["decision"] = "pending"
        self.assertBlocked("SKIP_NOT_APPROVED", data)

    def test_skip_requires_nonempty_reason(self):
        data = read_document(self.root / "accepted-with-optional-skip.json")
        data["skip_decisions"][0]["reason"] = "   "
        self.assertBlocked("SCHEMA", data)

    def test_skip_decider_must_be_independent(self):
        data = read_document(self.root / "accepted-with-optional-skip.json")
        data["skip_decisions"][0]["decider_id"] = "PERSON-DEMO-AUTHOR"
        self.assertBlocked("SKIP_DECIDER_NOT_INDEPENDENT", data)

    def test_skip_does_not_hide_file_integrity_blockers(self):
        data = read_document(self.root / "accepted-with-optional-skip.json")
        data["artifacts"][-1]["sha256"] = "0" * 64
        self.assertBlocked("HASH_MISMATCH", data)

    def test_incomplete_must_blocks_at_real_99_percent(self):
        data = read_document(self.root / "bad-incomplete-must-high-score.json")
        result = self.assertBlocked("EXECUTION_INCOMPLETE", data)
        self.assertEqual(result["diagnostics"]["accepted_percent"], 99.0)
        self.assertEqual(result["diagnostics"]["reported_score_ignored"], 99)
        self.assertFalse(result["diagnostics"]["score_controls_gate"])
        self.assertEqual(result["diagnostics"]["mandatory_open_ids"], ["REQ-DEMO-CRITICAL"])
        self.assertEqual(result["diagnostics"]["by_classification"]["business_feature"], {"total": 1, "accepted": 0})
        self.assertEqual(result["diagnostics"]["by_classification"]["helper"], {"total": 99, "accepted": 99})

    def test_implemented_and_accepted_are_distinct(self):
        self.data["audit"]["claimed_state"] = "implemented"
        self.data["acceptances"] = []
        for row in self.data["requirements"]:
            row["acceptance_ids"] = []
        result = self.assertBlocked("ACCEPTANCE_INCOMPLETE")
        self.assertTrue(result["implemented"])
        self.assertEqual(result["computed_state"], "implemented")

    def test_claimed_unknown_cannot_disable_the_strict_gate(self):
        self.data["audit"]["claimed_state"] = "unknown"
        self.data["tests"][0]["state"] = "proposed"
        self.assertBlocked("TEST_NOT_EXECUTED")

    def test_baseline_preserves_feature_identity(self):
        baseline = copy.deepcopy(self.data)
        self.assertTrue(self.check(baseline=baseline)["gate_passed"])
        self.data["features"][0]["id"] = "FEATURE-DEMO-RENAMED"
        self.data["requirements"][0]["feature_id"] = "FEATURE-DEMO-RENAMED"
        self.assertBlocked("FEATURE_ID_REMOVED", baseline=baseline)

    def test_baseline_prevents_mandatory_downgrade_and_reinterpretation(self):
        baseline = copy.deepcopy(self.data)
        for field, value in (("level", "may"), ("priority", "P2"), ("text", "Unrelated new behavior")):
            with self.subTest(field=field):
                data = copy.deepcopy(self.data)
                data["requirements"][0][field] = value
                self.assertBlocked("REQUIREMENT_IDENTITY_CHANGED", data, baseline=baseline)

    def test_baseline_cannot_use_another_audit_scope(self):
        baseline = copy.deepcopy(self.data)
        baseline["audit"]["scope"] = "real"
        self.assertBlocked("BASELINE_SCOPE", baseline=baseline)


class CommandLineTests(unittest.TestCase):
    def run_cli(self, *args, env=None):
        execution = subprocess.run([sys.executable, "-m", "traceability", *args], cwd=ROOT,
                                   capture_output=True, text=True, check=False, env=env)
        self.assertEqual(execution.stderr, "", execution.stderr)
        return execution.returncode, json.loads(execution.stdout)

    def test_every_saved_fixture_has_exact_expected_exit(self):
        for fixture in sorted(DEMO.glob("*.json")):
            with self.subTest(fixture=fixture.name):
                code, result = self.run_cli("validate", str(fixture))
                expected = 1 if fixture.name.startswith("bad-") else 0
                self.assertEqual(code, expected)
                self.assertEqual(result["gate_passed"], expected == 0)

    def test_nonexistent_input_returns_json_and_exit_2(self):
        code, result = self.run_cli("validate", "demo/not-there.json")
        self.assertEqual(code, 2)
        self.assertEqual(result["blockers"][0]["code"], "INPUT")

    def test_invalid_command_returns_json_and_exit_2(self):
        code, result = self.run_cli("validate")
        self.assertEqual(code, 2)
        self.assertEqual(result["blockers"][0]["code"], "USAGE")

    def test_bad_json_duplicate_keys_nan_and_large_input(self):
        with tempfile.TemporaryDirectory(prefix="traceability-json-") as directory:
            path = Path(directory) / "invalid.json"
            for raw in ('{"x":', '{"x":1,"x":2}', '{"x":NaN}', '{"x":Infinity}', ' ' * (8 * 1024 * 1024 + 1)):
                with self.subTest(raw=raw[:30]):
                    path.write_text(raw, encoding="utf-8")
                    code, result = self.run_cli("validate", str(path))
                    self.assertEqual(code, 2)
                    self.assertEqual(result["blockers"][0]["code"], "INPUT")

    def test_baseline_cli(self):
        code, result = self.run_cli("validate", "demo/accepted.json", "--baseline", "demo/accepted.json")
        self.assertEqual(code, 0)
        self.assertTrue(result["accepted"])

    def test_surrogate_unknown_key_returns_schema_json_without_traceback(self):
        with tempfile.TemporaryDirectory(prefix="traceability-unicode-") as directory:
            path = Path(directory) / "manifest.json"
            path.write_text('{"\\ud800":1}', encoding="ascii")
            for pretty in ([], ["--pretty"]):
                with self.subTest(pretty=pretty):
                    code, result = self.run_cli("validate", str(path), *pretty)
                    self.assertEqual(code, 1)
                    self.assertEqual(result["blockers"][0]["code"], "SCHEMA")

    def test_unicode_skip_reason_round_trips_with_ascii_stdout(self):
        with tempfile.TemporaryDirectory(prefix="traceability-unicode-") as directory:
            root = Path(directory) / "demo"
            shutil.copytree(DEMO, root, ignore=shutil.ignore_patterns("__pycache__"))
            source = read_document(root / "accepted-with-optional-skip.json")
            path = root / "manifest.json"
            for reason in ("\ud800", "Необязательный сценарий отложен: нет провайдера", "Optional \U0001f4cb review"):
                for pretty in ([], ["--pretty"]):
                    with self.subTest(reason=repr(reason), pretty=pretty):
                        source["skip_decisions"][0]["reason"] = reason
                        path.write_text(json.dumps(source, ensure_ascii=True), encoding="ascii")
                        code, result = self.run_cli("validate", str(path), *pretty,
                                                    env={**os.environ, "PYTHONIOENCODING": "ascii"})
                        self.assertEqual(code, 0)
                        self.assertTrue(result["gate_passed"])
                        self.assertEqual(result["skips"][0]["reason"], reason)

    def test_input_error_with_unicode_key_is_json_with_ascii_stdout(self):
        with tempfile.TemporaryDirectory(prefix="traceability-unicode-") as directory:
            path = Path(directory) / "manifest.json"
            path.write_text('{"причина":1,"причина":2}', encoding="utf-8")
            code, result = self.run_cli("validate", str(path), "--pretty",
                                        env={**os.environ, "PYTHONIOENCODING": "ascii"})
            self.assertEqual(code, 2)
            self.assertEqual(result["blockers"][0]["code"], "INPUT")


if __name__ == "__main__":
    unittest.main()
