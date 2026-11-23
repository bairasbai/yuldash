"""Offline CI analyzer regressions; all HTTP, generation and publishing are mocked."""
import json
import sys
import unittest
from copy import deepcopy
from io import BytesIO
from pathlib import Path
from unittest.mock import patch
from urllib import request

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
import analyze_ci_failure as ci


REPO = "owner/repo"
SHA = "a" * 40
RUN = {
    "id": 100, "path": ci.CI_PATH, "event": "pull_request", "status": "completed", "conclusion": "failure",
    "head_repository": {"full_name": REPO}, "head_branch": "feature", "head_sha": SHA,
    "run_attempt": 2, "pull_requests": [{"number": 9}],
}
EVENT = {"action": "completed", "repository": {"full_name": REPO, "default_branch": "main"}, "workflow_run": RUN}
PR = {
    "state": "open", "draft": False, "head": {"repo": {"full_name": REPO}, "ref": "feature", "sha": SHA},
    "base": {"ref": "main", "repo": {"full_name": REPO}},
}
JOB = {"id": 4, "name": "backend-tests", "conclusion": "failure", "steps": [
    {"name": "Set up", "conclusion": "success"}, {"name": "Run tests", "conclusion": "failure"},
]}


class CIRoutingTests(unittest.TestCase):
    def setUp(self):
        self.run_data = deepcopy(RUN)
        self.pr = deepcopy(PR)
        self.candidates = [{"number": 9}]
        self.jobs = [JOB]
        self.api = self.enterContext(patch.object(ci.shared, "request_json", side_effect=self.github))
        self.log_patch = patch.object(ci, "job_log", return_value="PYTEST_FAILURE")
        self.logs = self.log_patch.start()
        self.addCleanup(self.log_patch.stop)
        self.model = self.enterContext(patch.object(ci.shared, "generate_json", create=True, return_value={
            "cause": "проверка количества", "location": "pytest", "fix": "сверить элементы",
        }))
        self.lookup = self.enterContext(patch.object(ci.shared, "find_comment", return_value=None))
        self.post = self.enterContext(patch.object(ci.shared, "upsert_comment"))

    def github(self, url, _headers):
        if url.endswith("/actions/runs/100"):
            return self.run_data
        if url.endswith("/pulls?per_page=100"):
            return self.candidates
        if url.endswith("/pulls/9"):
            return self.pr
        if url.endswith("/runs/100/attempts/2/jobs?per_page=100&page=1"):
            return {"jobs": self.jobs}
        if url.endswith("/runs/100/attempts/2/jobs?per_page=100&page=2"):
            return {"jobs": [JOB]}
        self.fail("Unexpected GitHub call")

    def analyze(self, event=EVENT):
        return ci.analyze(event, REPO, {"Authorization": "Bearer dummy"}, "dummy", enabled=True)

    def test_routes_current_attempt_paginates_and_reuses_one_marker(self):
        self.jobs = [{"id": 3, "name": "ok", "conclusion": "success"}] * 100
        self.analyze()
        self.lookup.return_value = {"body": self.post.call_args.args[-1]}
        self.analyze()
        self.logs.assert_called_once()
        self.model.assert_called_once()
        self.post.assert_called_once()
        repo, pr, _, marker, body = self.post.call_args.args
        self.assertEqual((repo, pr), (REPO, 9))
        self.assertIn(marker, body.splitlines()[:1])
        self.assertIn("/runs/100/attempts/2", body)
        self.assertIn("/runs/100/job/4", body)
        _, prompt, _ = self.model.call_args.args
        self.assertEqual(json.loads(prompt), [{
            "job": "BACKEND_SQLITE", "conclusion": "failure", "diagnostic": "PYTEST_FAILURE",
        }])

    def test_missing_log_uses_step_metadata(self):
        self.logs.side_effect = TimeoutError()
        self.analyze()
        self.model.assert_not_called()
        self.assertIn("UNKNOWN", self.post.call_args.args[-1])
        self.assertIn("Недостаточно данных", self.post.call_args.args[-1])

    def test_excludes_foreign_or_non_pull_request_runs_before_any_http(self):
        for overrides in ({"head_repository": {"full_name": "fork/repo"}}, {"event": "push"},
                          {"event": "workflow_run"}, {"event": "pull_request_target"},
                          {"conclusion": "success"}, {"path": ".github/workflows/other.yml"},
                          {"head_sha": "untrusted input"}):
            self.analyze({**EVENT, "workflow_run": {**RUN, **overrides}})
        self.api.assert_not_called()
        self.model.assert_not_called()

    def test_missing_association_uses_commit_and_rejects_ambiguity(self):
        self.run_data["pull_requests"] = []
        self.candidates = [{"number": 9}, {"number": 10}]
        self.analyze()
        self.model.assert_not_called()
        self.candidates = [{"number": 9}]
        self.analyze()
        self.post.assert_called_once()

    def test_stale_or_closed_or_fork_pr_never_downloads_logs(self):
        for pr in ({**PR, "base": {**PR["base"], "ref": "stack"}},
                   {**PR, "state": "closed"}, {**PR, "draft": True}, {**PR, "draft": None}, {**PR, "head": {**PR["head"], "sha": "b" * 40}},
                   {**PR, "head": {**PR["head"], "repo": {"full_name": "fork/repo"}}}):
            self.pr = pr
            self.analyze()
        self.pr = PR
        self.run_data["run_attempt"] = 3
        self.analyze()
        self.logs.assert_not_called()
        self.model.assert_not_called()
        self.post.assert_not_called()

    def test_head_or_attempt_changed_during_analysis(self):
        def advance(*args):
            self.run_data["run_attempt"] += 1
            return {"cause": "x", "location": "y", "fix": "z"}
        self.model.side_effect = advance
        self.analyze()
        self.post.assert_not_called()

    def test_no_failed_jobs_and_malformed_model_reply(self):
        self.jobs = []
        self.analyze()
        self.model.assert_not_called()
        self.jobs = [JOB]
        self.model.return_value = {"cause": "x", "location": [], "fix": "z"}
        with self.assertRaises(ValueError):
            self.analyze()
        self.post.assert_not_called()

    def test_disabled_by_default_before_any_http(self):
        ci.analyze(EVENT, REPO, {}, "dummy")
        ci.analyze(EVENT, REPO, {}, "dummy", enabled="true")
        self.api.assert_not_called()
        self.model.assert_not_called()
        self.post.assert_not_called()

    def test_unknown_job_step_or_nonenum_classifier_never_reaches_model(self):
        self.jobs = [{**JOB, "name": "private-name-address"}]
        self.analyze()
        self.logs.assert_not_called()
        self.model.assert_not_called()
        self.assertNotIn("private-name-address", self.post.call_args.args[-1])
        self.jobs = [{**JOB, "steps": [{"name": "private-step", "conclusion": "failure"}]}]
        self.analyze()
        self.logs.assert_not_called()
        self.model.assert_not_called()
        self.jobs = [JOB]
        self.logs.return_value = "private-classifier-text"
        self.analyze()
        self.model.assert_not_called()
        self.assertNotIn("private-classifier-text", self.post.call_args.args[-1])

    def test_real_log_path_sends_only_enums_not_log_or_dynamic_names(self):
        self.log_patch.stop()
        raw = ("Error: synthetic personal name and home address\n"
               "FAILED tests/test_no_secrets_under_version_control.py::check - synthetic private reason")
        result = BytesIO(raw.encode())
        with patch.object(ci.request, "build_opener") as opener:
            opener.return_value.open.return_value = result
            self.analyze()
        _, prompt, _ = self.model.call_args.args
        self.assertEqual(json.loads(prompt), [{
            "job": "BACKEND_SQLITE", "conclusion": "failure", "diagnostic": "REPOSITORY_SECRET_SCAN",
        }])
        comment = self.post.call_args.args[-1]
        for fragment in ("synthetic private reason", "synthetic personal name", "Run tests", "backend-tests"):
            self.assertNotIn(fragment, prompt)
            self.assertNotIn(fragment, comment)

    def test_changes_before_model_call_skip_egress(self):
        def advance(*args):
            self.pr["draft"] = True
            return "PYTEST_FAILURE"
        self.logs.side_effect = advance
        self.analyze()
        self.model.assert_not_called()
        self.post.assert_not_called()


class CILogTests(unittest.TestCase):
    def test_fixed_diagnostic_enums_only_ignore_pem_and_setup_groups(self):
        pem = (Path(__file__).parent / "fixtures/gemini-fake-openssh.example").read_text()
        signature = "FAILED tests/test_no_secrets_under_version_control.py::scan - hidden"
        inside = pem.replace("abc123", signature)
        setup = "2026-11-23T01:00:00.000Z ##[group]Run pytest\n" + signature + "\n##[endgroup]"
        for raw in (inside, setup, "Error: personal name and address"):
            self.assertEqual(ci.classify_log(raw, "BACKEND_SQLITE"), "UNKNOWN")
        self.assertEqual(ci.classify_log(signature, "BACKEND_SQLITE"), "REPOSITORY_SECRET_SCAN")
        self.assertEqual(ci.classify_log("FAILED backend/tests/test_exact.py::test_hidden",
                                         "BACKEND_POSTGRES"), "PYTEST_FAILURE")
        self.assertEqual(ci.classify_log(signature, "ANDROID_UNIT"), "UNKNOWN")

    def test_maven_rate_limit_requires_specific_url_status_and_job(self):
        url = ("https://repo.maven.apache.org/maven2/org/robolectric/"
               "android-all-instrumented/sdk/sdk.jar")
        raw = "2026-11-23T01:00:00.000Z Server returned HTTP response code: 429 for URL: " + url
        self.assertEqual(ci.classify_log(raw, "ANDROID_UNIT"), "ROBOLECTRIC_MAVEN_RATE_LIMIT")
        self.assertEqual(ci.classify_log(raw, "BACKEND_SQLITE"), "UNKNOWN")
        self.assertEqual(ci.classify_log(raw.replace("response code: 429", "response code: 500"),
                                         "ANDROID_UNIT"), "UNKNOWN")
        self.assertEqual(ci.classify_log("HTTP 429 private address", "ANDROID_UNIT"), "UNKNOWN")
        self.assertEqual(ci.classify_log("> Task :app:compileDebugKotlin FAILED", "ANDROID_UNIT"),
                         "KOTLIN_COMPILATION_FAILURE")

    def test_token_free_redirect(self):
        req = request.Request("https://api.github.com/job/4/logs", headers={"Authorization": "Bearer dummy"})
        handler = ci.LogRedirect()
        new = handler.redirect_request(req, None, 302, "", {}, "https://logs.blob.core.windows.net/signed")
        self.assertFalse(new.has_header("Authorization"))
        for bad in ("http://logs.blob.core.windows.net/file", "https://user@logs.blob.core.windows.net/file",
                    "https://logs.blob.core.windows.net.evil.invalid", "https://evil.invalid/"):
            with self.assertRaises(ValueError):
                handler.redirect_request(req, None, 302, "", {}, bad)
