"""Контракты оболочки на синтетических Git-репозиториях; не приёмка приложения."""
import copy
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

HERE = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("yuldash_traceability", HERE / "check.py")
checker = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(checker)


class YuldashGateTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="yuldash-traceability-synthetic-")
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        # This is a synthetic miniature repository, never a project checkout copy.
        shutil.copytree(HERE / "vendor/demo", self.root / "proof")
        self.git("init", "--quiet")
        self.git("config", "user.name", "Synthetic Test")
        self.git("config", "user.email", "synthetic@example.invalid")
        self.git("config", "core.autocrlf", "false")
        self.git("config", "commit.gpgsign", "false")
        self.data = json.loads((self.root / "proof/accepted.json").read_text(encoding="utf-8"))
        self.data["audit"].update(id=checker.AUDIT, scope="real", title="SYNTHETIC wrapper contract only")
        old_ids = [r["id"] for r in self.data["requirements"]]
        mapping = {old_ids[0]: "W1-E2E-01", old_ids[1]: "W1-E2E-02"}
        for req in self.data["requirements"]:
            req["id"] = mapping[req["id"]]
        for group in ("features", "implementations", "tests", "acceptances", "skip_decisions"):
            for row in self.data[group]:
                row["requirement_ids"] = [mapping[r] for r in row["requirement_ids"]]
        for i in range(3, 8):
            req = copy.deepcopy(self.data["requirements"][1])
            req.update(id=f"W1-E2E-{i:02}", text=f"SYNTHETIC criterion {i}")
            self.data["requirements"].append(req)
            for group in ("features", "implementations", "tests", "acceptances"):
                for row in self.data[group]:
                    if "W1-E2E-02" in row["requirement_ids"]:
                        row["requirement_ids"].append(req["id"])
        for artifact in self.data["artifacts"]:
            artifact["path"] = "proof/" + artifact["path"]
        self.write()
        self.git("add", "proof", checker.MANIFEST)
        self.git("-c", "core.hooksPath=", "commit", "--quiet", "-m", "Synthetic baseline")
        self.base = self.git("rev-parse", "HEAD").decode().strip()
        self.retarget(self.base)
        self.write()

    def git(self, *args):
        return subprocess.run(["git", "-C", str(self.root), *args], check=True,
                              capture_output=True, timeout=30).stdout

    def retarget(self, commit):
        self.data["audit"]["target_commit"] = commit
        for group, field in (("implementations", "commit"), ("tests", "target_commit"), ("acceptances", "target_commit")):
            for row in self.data[group]:
                row[field] = commit

    def write(self):
        target = self.root / checker.MANIFEST
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(self.data, ensure_ascii=False), encoding="utf-8")

    def run_cli(self, *args, expected=0):
        result = subprocess.run([sys.executable, "-B", str(HERE / "check.py"), "--root", str(self.root),
                                 "--baseline-ref", self.base, "--json", *args],
                                capture_output=True, text=True, encoding="utf-8", timeout=30)
        self.assertEqual(result.returncode, expected, result.stdout + result.stderr)
        return json.loads(result.stdout)

    def blocked(self, code):
        self.write()
        result = self.run_cli(expected=1)
        self.assertFalse(result["gate_passed"])
        self.assertFalse(result["accepted"])
        self.assertIn(code, {r["code"] for r in result["blockers"]}, result)
        return result

    def test_synthetic_complete_chain_returns_zero(self):
        result = self.run_cli()
        self.assertTrue(result["gate_passed"])
        self.assertTrue(result["accepted"])
        self.assertEqual(result["yuldash"]["baseline_ref"], self.base)
        self.assertEqual(result["diagnostics"]["accepted_requirements"], 7)

    def test_missing_baseline_argument_returns_two_json(self):
        result = subprocess.run([sys.executable, "-B", str(HERE / "check.py"), "--json"],
                                capture_output=True, text=True, encoding="utf-8", timeout=30)
        self.assertEqual(result.returncode, 2)
        self.assertFalse(json.loads(result.stdout)["gate_passed"])

    def test_baseline_must_be_full_existing_commit(self):
        for ref in ("HEAD", self.base[:8], "f" * 40):
            with self.subTest(ref=ref):
                result = self.run_cli("--baseline-ref", ref, expected=2)
                self.assertEqual(result["blockers"][0]["code"], "INPUT")

    def test_old_target_is_blocked_even_when_all_claims_match(self):
        self.retarget("d" * 40)
        self.blocked("YULDASH_TARGET")

    def test_dirty_implementation_cannot_pass_with_updated_hash(self):
        artifact = next(a for a in self.data["artifacts"] if a["kind"] == "implementation")
        path = self.root / artifact["path"]
        path.write_bytes(path.read_bytes() + b"\n# changed after commit\n")
        artifact["sha256"] = hashlib.sha256(path.read_bytes()).hexdigest()
        self.blocked("YULDASH_DIRTY_IMPLEMENTATION")

    def test_assume_unchanged_cannot_hide_modified_implementation(self):
        artifact = next(a for a in self.data["artifacts"] if a["kind"] == "implementation")
        self.git("update-index", "--assume-unchanged", "--", artifact["path"])
        path = self.root / artifact["path"]
        path.write_bytes(path.read_bytes() + b"\n# hidden from git diff\n")
        artifact["sha256"] = hashlib.sha256(path.read_bytes()).hexdigest()
        self.blocked("YULDASH_DIRTY_IMPLEMENTATION")

    def test_windows_crlf_source_matches_git_lf_but_preserves_raw_hash(self):
        artifact = next(a for a in self.data["artifacts"] if a["kind"] == "implementation")
        path = self.root / artifact["path"]
        path.write_bytes(path.read_bytes().replace(b"\n", b"\r\n"))
        artifact["sha256"] = hashlib.sha256(path.read_bytes()).hexdigest()
        self.write()
        self.run_cli()

    def test_deleted_implementation_cannot_pass(self):
        artifact = next(a for a in self.data["artifacts"] if a["kind"] == "implementation")
        (self.root / artifact["path"]).unlink()
        self.blocked("ARTIFACT_MISSING")

    def test_untracked_implementation_cannot_pass(self):
        artifact = next(a for a in self.data["artifacts"] if a["kind"] == "implementation")
        new = self.root / "proof/untracked.py"
        new.write_bytes((self.root / artifact["path"]).read_bytes())
        artifact["path"] = "proof/untracked.py"
        self.blocked("YULDASH_UNTRACKED_IMPLEMENTATION")

    def test_changed_execution_bytes_are_blocked(self):
        artifact = next(a for a in self.data["artifacts"] if a["kind"] == "execution")
        (self.root / artifact["path"]).write_text("changed", encoding="utf-8")
        self.blocked("HASH_MISMATCH")

    def test_missing_execution_is_blocked(self):
        artifact = next(a for a in self.data["artifacts"] if a["kind"] == "execution")
        (self.root / artifact["path"]).unlink()
        self.blocked("ARTIFACT_MISSING")

    def test_worktree_baseline_tampering_does_not_change_git_baseline(self):
        # The SAME worktree file is both candidate and apparent baseline. Git stays immutable.
        removed = self.data["requirements"].pop()
        for group in ("features", "implementations", "tests", "acceptances"):
            for row in self.data[group]:
                row["requirement_ids"] = [r for r in row["requirement_ids"] if r != removed["id"]]
        result = self.blocked("REQUIREMENT_ID_REMOVED")
        self.assertIn("YULDASH_REQUIRED_SCOPE", {b["code"] for b in result["blockers"]})

    def test_changed_requirement_identity_is_blocked(self):
        for field, value in (("text", "weaker"), ("level", "should"), ("priority", "P2")):
            with self.subTest(field=field):
                old = self.data["requirements"][0][field]
                self.data["requirements"][0][field] = value
                self.blocked("REQUIREMENT_IDENTITY_CHANGED")
                self.data["requirements"][0][field] = old

    def test_required_skip_is_blocked(self):
        self.data["requirements"][0]["disposition"] = "skipped"
        self.blocked("SKIPPED_BLOCKER")

    def test_demo_scope_cannot_pass(self):
        self.data["audit"]["scope"] = "demo"
        self.blocked("YULDASH_SCOPE")

    def test_incomplete_chain_is_blocked_and_readable(self):
        self.data["requirements"][0]["acceptance_ids"] = []
        self.data["acceptances"][0]["requirement_ids"] = []
        self.data["acceptances"] = self.data["acceptances"][1:]
        result = self.blocked("ACCEPTANCE_INCOMPLETE")
        text = checker.render(result)
        self.assertIn("НЕ ПРОЙДЕНА", text)
        self.assertIn("нет независимой приёмки", text)

    def test_forbidden_paths_never_reach_artifact_reader(self):
        # Do not create or read even synthetic files with prohibited names.
        for raw in ("mobile/file.txt", ".git/config", "android/local.properties", "backend/.env",
                    "a/google-services.json", "a/keystore.properties", "a/test.JKS", "a/credentials.json",
                    "test-results/synthetic/BuildConfig.java", "a/bUiLdCoNfIg.JaVa",
                    "../outside", "/absolute", "C:/absolute", "a\\b", "a/../b", "a/./b",
                    "a//b", "a/file.", "a/file "):
            with self.subTest(path=raw):
                self.data["artifacts"][0]["path"] = raw
                self.write()
                with patch.object(checker, "validate", side_effect=AssertionError("artifact reader invoked")):
                    result = checker.check(self.root, checker.MANIFEST, self.base)
                self.assertEqual(result["blockers"][0]["code"], "YULDASH_PATH")

    def test_manifest_itself_cannot_be_protected(self):
        with patch.object(checker, "read_document", side_effect=AssertionError("read invoked")):
            for raw in ("android/local.properties", "test-results/synthetic/BuildConfig.java"):
                with self.subTest(path=raw), self.assertRaises(checker.InputFailure):
                    checker.check(self.root, raw, self.base)

    def test_symlink_component_is_rejected(self):
        link = self.root / "proof/link"
        try:
            link.symlink_to(self.root / "proof/artifacts", target_is_directory=True)
        except OSError as exc:
            if os.name != "nt" or getattr(exc, "winerror", None) != 1314:
                raise
            self.skipTest("Windows: нет права создавать symlink (1314)")
        with self.assertRaises(checker.InputFailure):
            checker.safe_path(self.root, "proof/link/source-booking.txt")

    @unittest.skipUnless(os.name == "nt", "NTFS junction проверяется на Windows")
    def test_real_windows_junction_is_rejected_before_reader(self):
        link = self.root / "proof/junction"
        target = self.root / "proof/artifacts"
        # Literal arguments; only harmless fixtures inside this test's own temp directory.
        command = ("New-Item -ItemType Junction -Path $env:YULDASH_SYNTHETIC_LINK "
                   "-Target $env:YULDASH_SYNTHETIC_TARGET | Out-Null")
        subprocess.run(["powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                        command], check=True, capture_output=True, timeout=30,
                       env={**os.environ, "YULDASH_SYNTHETIC_LINK": str(link),
                            "YULDASH_SYNTHETIC_TARGET": str(target)})
        try:
            self.data["artifacts"][0]["path"] = "proof/junction/source-booking.txt"
            self.write()
            with patch.object(checker, "validate", side_effect=AssertionError("reader invoked")):
                result = checker.check(self.root, checker.MANIFEST, self.base)
            self.assertEqual(result["blockers"][0]["code"], "YULDASH_PATH")
            self.assertTrue(target.is_dir())
        finally:
            link.rmdir()  # Remove only the junction, never recursively delete the target.

    def test_optional_skip_is_visible_without_claiming_full_acceptance(self):
        self.data["features"].append({"id": "FEATURE-OPTIONAL", "title": "SYNTHETIC optional helper",
                                       "classification": "helper", "requirement_ids": ["REQ-OPTIONAL"]})
        self.data["requirements"].append({"id": "REQ-OPTIONAL", "feature_id": "FEATURE-OPTIONAL",
            "text": "SYNTHETIC optional helper", "level": "may", "priority": "P2",
            "evidence_ids": ["EVD-DEMO-FAVORITES"], "implementation_ids": [], "test_ids": [],
            "acceptance_ids": [], "disposition": "skipped", "skip_decision_id": "SKIP-OPTIONAL"})
        self.data["skip_decisions"].append({"id": "SKIP-OPTIONAL", "requirement_ids": ["REQ-OPTIONAL"],
            "decider_id": "PERSON-DEMO-REVIEWER", "decision": "approved", "reason": "SYNTHETIC optional skip",
            "decided_at": "2026-10-23T10:00:00Z", "artifact_ids": ["ART-DEMO-SKIP"]})
        self.data["audit"]["claimed_state"] = "accepted_with_skips"
        self.write()
        result = self.run_cli()
        self.assertTrue(result["gate_passed"])
        self.assertFalse(result["accepted"])
        self.assertIn("полной приёмки без пропусков нет", checker.render(result))

    def test_windows_reparse_point_is_rejected_without_following(self):
        class Metadata:
            st_file_attributes = 0x400
        with patch.object(Path, "lstat", return_value=Metadata()):
            with self.assertRaises(checker.InputFailure):
                checker.safe_path(self.root, "proof/file.txt")

    def test_duplicate_json_keys_are_not_silently_replaced(self):
        target = self.root / checker.MANIFEST
        target.write_text('{"audit": 1, "audit": 2}', encoding="utf-8")
        self.run_cli(expected=2)

    def test_commands_in_manifest_are_not_executed(self):
        marker = self.root / "EXECUTED"
        self.data["tests"][0]["command"] = f'python -c "open({str(marker)!r}, \'w\').write(\'bad\')"'
        self.write()
        self.run_cli()
        self.assertFalse(marker.exists())


class VendoredSourceTest(unittest.TestCase):
    def test_all_selected_upstream_bytes_are_unchanged(self):
        manifest = json.loads((HERE / "upstream-files.json").read_text(encoding="utf-8"))
        self.assertEqual(manifest["version"], "1.0.2")
        for path, expected in manifest["files"].items():
            with self.subTest(path=path):
                self.assertEqual(hashlib.sha256((HERE / "vendor" / path).read_bytes()).hexdigest(), expected)

    def test_real_manifest_has_required_scope_and_valid_source_bytes(self):
        root = HERE.parents[1]
        document = json.loads((root / checker.MANIFEST).read_text(encoding="utf-8"))
        self.assertFalse(checker.validate_structure(document))
        self.assertEqual(document["audit"]["id"], checker.AUDIT)
        self.assertEqual(document["audit"]["scope"], "real")
        by_id = {r["id"]: r for r in document["requirements"]}
        self.assertTrue(checker.REQUIREMENTS.issubset(by_id))
        self.assertTrue(all(by_id[r]["level"] == "must" for r in checker.REQUIREMENTS))
        for artifact in document["artifacts"]:
            if artifact["kind"] != "source":
                continue
            path = checker.safe_path(root, artifact["path"])
            self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), artifact["sha256"])

    def test_agent_entrypoint_documentation_links_resolve(self):
        root = HERE.parents[1]
        for path in (HERE / "UPSTREAM.md", root / "docs/audit-traceability/README.md",
                     root / "docs/audit-traceability/rideshare-scope.md",
                     root / ".claude/skills/audit-traceability/SKILL.md"):
            for target in re.findall(r"\]\(([^)]+)\)", path.read_text(encoding="utf-8")):
                if "://" in target or target.startswith("#"):
                    continue
                with self.subTest(source=path.name, target=target):
                    self.assertTrue((path.parent / target.split("#", 1)[0]).is_file())


if __name__ == "__main__":
    unittest.main()
