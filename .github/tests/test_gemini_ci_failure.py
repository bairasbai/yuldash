"""Offline CI analyzer regressions; all HTTP, generation and publishing are mocked."""
import json
import sys
import unittest
from copy import deepcopy
from pathlib import Path
from unittest.mock import patch
from urllib import request

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
import analyze_ci_failure as ci


REPO = "owner/repo"
SHA = "a" * 40
RUN = {
    "id": 100, "event": "pull_request", "status": "completed", "conclusion": "failure",
    "head_repository": {"full_name": REPO}, "head_branch": "feature", "head_sha": SHA,
    "run_attempt": 2, "pull_requests": [{"number": 9}],
}
EVENT = {"action": "completed", "repository": {"full_name": REPO}, "workflow_run": RUN}
PR = {
    "state": "open", "head": {"repo": {"full_name": REPO}, "ref": "feature", "sha": SHA},
    "base": {"repo": {"full_name": REPO}},
}
JOB = {"id": 4, "name": "unit", "conclusion": "failure", "steps": [
    {"name": "Set up", "conclusion": "success"}, {"name": "pytest", "conclusion": "failure"},
]}


class CIRoutingTests(unittest.TestCase):
    def setUp(self):
        self.run_data = deepcopy(RUN)
        self.pr = deepcopy(PR)
        self.candidates = [{"number": 9}]
        self.jobs = [JOB]
        self.api = self.enterContext(patch.object(ci.shared, "request_json", side_effect=self.github))
        self.logs = self.enterContext(patch.object(ci, "job_log", return_value="AssertionError: count"))
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
        return ci.analyze(event, REPO, {"Authorization": "Bearer dummy"}, "dummy")

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
            "job": "unit", "conclusion": "failure", "failed_steps": ["pytest"],
            "diagnostics": "AssertionError: count",
        }])

    def test_missing_log_uses_step_metadata(self):
        self.logs.side_effect = TimeoutError()
        self.analyze()
        self.assertEqual(json.loads(self.model.call_args.args[1])[0]["failed_steps"], ["pytest"])
        self.assertIn("выдержка из лога: 0 из 1", self.post.call_args.args[-1])

    def test_excludes_foreign_or_non_pull_request_runs_before_any_http(self):
        for overrides in ({"head_repository": {"full_name": "fork/repo"}}, {"event": "push"},
                          {"event": "workflow_run"}, {"event": "pull_request_target"},
                          {"conclusion": "success"}, {"head_sha": "untrusted input"}):
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
        for pr in ({**PR, "state": "closed"}, {**PR, "head": {**PR["head"], "sha": "b" * 40}},
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


class CILogTests(unittest.TestCase):
    def test_diagnostic_only_secret_filter_before_truncation(self):
        raw = "\n".join([
            "2026-11-23T01:00:00.000Z ##[group]Run pytest",
            "Error setup-command-that-must-stay-private", "##[endgroup]",
            "unrelated output", "-----BEGIN PRIVATE KEY-----", "Error key block", "-----END PRIVATE KEY-----",
            "Error: Authorization: Bearer private", "Error SECRET=hidden", "Error: me@example.org",
            "Error URL https://where.invalid/file?signed=opaque", "Error: 55.123453, 37.456789",
            "Error: " + "line " * 110 + " GITHUB_TOKEN=hidden", "##[error] AssertionError tests/a.py:3",
        ])
        self.assertEqual(ci.diagnostic_excerpt(raw), "##[error] AssertionError tests/a.py:3")

    def test_adb_failure_with_generic_exit_and_nested_preambles(self):
        diagnostic = "adb: unable to connect for root: closed"
        raw = "\n".join(["##[group]Emulator boot", "##[group]Run shell",
                         "adb: unable to print this private preamble", "##[endgroup]",
                         "2026-11-23T01:24:43.0876196Z " + diagnostic, "##[endgroup]",
                         "##[error] Process completed with exit code 1",
                         "Error object " + "a" * 48 + ": missing dependency"])
        excerpt = ci.diagnostic_excerpt(raw)
        self.assertIn(diagnostic, excerpt)
        self.assertIn("exit code 1", excerpt)
        self.assertNotIn("private preamble", excerpt)
        self.assertNotIn("a" * 48, excerpt)
        self.assertIn("missing dependency", excerpt)

    def test_limit_and_token_free_redirect(self):
        raw = "\n".join("##[error] test error number %s" % n for n in range(1000))
        excerpt = ci.diagnostic_excerpt(raw)
        self.assertLessEqual(len(excerpt), ci.MAX_EXCERPT)
        self.assertIn("number 999", excerpt)
        req = request.Request("https://api.github.com/job/4/logs", headers={"Authorization": "Bearer dummy"})
        handler = ci.LogRedirect()
        new = handler.redirect_request(req, None, 302, "", {}, "https://logs.blob.core.windows.net/signed")
        self.assertFalse(new.has_header("Authorization"))
        for bad in ("http://logs.blob.core.windows.net/file", "https://user@logs.blob.core.windows.net/file",
                    "https://logs.blob.core.windows.net.evil.invalid", "https://evil.invalid/"):
            with self.assertRaises(ValueError):
                handler.redirect_request(req, None, 302, "", {}, bad)
