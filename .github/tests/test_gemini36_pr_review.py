"""Offline, no-secret regression checks for the Gemini PR reviewer."""
import importlib.util
import os
from pathlib import Path
import unittest
from unittest.mock import patch

MODULE_PATH = Path(__file__).resolve().parents[1] / "scripts" / "gemini36_pr_review.py"
spec = importlib.util.spec_from_file_location("gemini36_pr_review", MODULE_PATH)
reviewer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(reviewer)


class GeminiReviewerTests(unittest.TestCase):
    def test_patch_added_line_positions(self):
        patch_text = "@@ -1,3 +1,4 @@\n unchanged\n-old\n+new\n another\n+last"
        self.assertEqual(reviewer.added_lines(patch_text), {2, 4})

    def test_skips_sensitive_files_and_redacts_key_values(self):
        diff = "@@ -0,0 +1,1 @@\n+api_key = 'secret-123'"
        files = [
            {"filename": "backend/.env", "patch": "@@ -0,0 +1,1 @@\n+SECRET=oops"},
            {"filename": "backend/config.py", "patch": diff},
            {"filename": "android/local.properties", "patch": diff},
        ]
        prompt, changed, count = reviewer.build_prompt(files)
        self.assertEqual(count, 1)
        self.assertEqual(list(changed), ["backend/config.py"])
        self.assertNotIn("secret-123", prompt)
        self.assertNotIn("SECRET=oops", prompt)
        self.assertIn("[REDACTED]", prompt)

    def test_report_only_contains_valid_added_line_findings(self):
        result = {
            "summary": "Check @everyone",
            "findings": [
                {"severity": "CRITICAL", "file": "src/api.py", "line": 12,
                 "title": "Crash", "evidence": "Null access", "fix": "Handle None"},
                {"severity": "CRITICAL", "file": "src/api.py", "line": 99,
                 "title": "Invented", "evidence": "No anchor", "fix": "No"},
                {"severity": "WARNING", "file": "other.py", "line": 12,
                 "title": "Invented", "evidence": "No file", "fix": "No"},
            ],
        }
        output = reviewer.render_review("a" * 40, 2, 1, {"src/api.py": {12}}, result)
        self.assertIn("src/api.py:12", output)
        self.assertNotIn("Invented", output)
        self.assertNotIn("@everyone", output)
        self.assertIn("Неполный охват", output)
        self.assertIn("не запускались", output)

    def test_strict_limit_for_large_diffs(self):
        files = [{"filename": "backend/main.py", "patch": "+" + "a" * 50_000}]
        prompt, lines, count = reviewer.build_prompt(files)
        self.assertEqual((prompt, lines, count), ("", {}, 0))

    def test_model_request_uses_gemini_36_with_json_response(self):
        seen = {}

        def fake_request(url, headers, payload, method):
            seen.update(url=url, headers=headers, payload=payload, method=method)
            return {"candidates": [{"content": {"parts": [{"text": '{"changes":["Изменение"],"key_files":[],"risks":[],"findings":[]}'}]}}]}

        with patch.object(reviewer, "request_json", side_effect=fake_request):
            result = reviewer.ask_gemini("diff", "dummy-key-for-test")
        self.assertEqual(result["findings"], [])
        self.assertIn("/gemini-3.6-flash:generateContent", seen["url"])
        self.assertEqual(seen["payload"]["generationConfig"]["responseMimeType"], "application/json")
        self.assertNotIn("dummy-key-for-test", str(seen["payload"]))

    def test_summary_renders_separate_sections_and_excludes_unseen_paths(self):
        result = {"changes": ["Добавлено ожидание готовности"], "risks": ["Медленный запуск"],
                  "key_files": [{"file": "run.sh", "change": "Проверяет UID"},
                                {"file": "imaginary.py", "change": "invented"}]}
        body = reviewer.render_summary("f" * 40, 2, {"run.sh": {3}}, result)
        self.assertTrue(body.startswith(reviewer.SUMMARY_MARKER))
        for item in ("Суть изменений", "| run.sh | Проверяет UID |", "Зоны риска", "Неполный охват"):
            self.assertIn(item, body)
        self.assertNotIn("imaginary.py", body)

    def test_empty_findings_distinct_from_discarded_unanchored_findings(self):
        clean = reviewer.render_review("a" * 40, 1, 1, {"a.sh": {1}}, {"findings": []})
        self.assertIn("Критических проблем не найдено.", clean)
        invalid = reviewer.render_review("a" * 40, 1, 1, {"a.sh": {1}}, {"findings": [{}]})
        self.assertNotIn("Критических проблем не найдено.", invalid)
        self.assertIn("Требуется ручная проверка", invalid)

    def test_raw_tokens_multiline_keys_renames_and_lockfiles_are_excluded(self):
        diff = "@@ -1 +1 @@\n+new"
        hidden = [{"filename":"renamed.txt", "previous_filename":".env.local", "patch":diff},
                  {"filename":"web/pnpm-lock.yaml", "patch":diff}]
        self.assertEqual(reviewer.build_prompt(hidden)[2], 0)
        # The committed .example is deliberately not a valid key; keep the full
        # multiline syntax as input to the same product sanitizer.
        fake_pem = (Path(__file__).parent / "fixtures/gemini-fake-openssh.example").read_text()
        pem_diff = "\n".join("+" + line for line in fake_pem.splitlines())
        scrubbed = reviewer.scrub_patch("a Bearer abcdefgh\n+GEMINI_API_KEY = 'fake value'\n"
                                       + pem_diff + "\n+normal")
        for item in ("abcdefgh", "fake value", "abc123"):
            self.assertNotIn(item, scrubbed)
        self.assertIn("+normal", scrubbed)
        self.assertEqual(reviewer.added_lines("@@ -1 +1,2 @@\n+++i\n+second"), {1, 2})

    def test_upsert_updates_only_own_exact_marker_across_pages(self):
        marker = reviewer.MARKER
        fake = {"id": 5, "body": marker, "user": {"login": "outsider[bot]"}}
        similar = {**fake, "body": "quoted\n" + marker, "user": {"login": "github-actions[bot]"}}
        owned = {**fake, "id": 19, "user": {"login": "github-actions[bot]"}}
        with patch.object(reviewer, "request_json", side_effect=[[fake, similar]*50, [owned], {}]) as req:
            reviewer.upsert_comment("org/repo", 23, {}, marker, marker + "\nupdated")
        self.assertEqual(req.call_args.args[0], reviewer.API + "/repos/org/repo/issues/comments/19")
        self.assertEqual(req.call_args.kwargs["method"], "PATCH")

    def test_stale_head_prevents_publication_and_same_revision_skips_generation(self):
        sha = "f" * 40
        base = "b" * 40
        pr = {"state": "open", "draft": False,
              "base": {"ref": "main", "sha": base, "repo": {"full_name": "org/repo"}}, "head": {"repo": {"full_name":"org/repo"}, "sha":sha}}
        env = {"YULDASH_GEMINI_PR_DIFF": "true", "YULDASH_DEFAULT_BRANCH": "main", "PR_BASE_REF": "main", "GITHUB_TOKEN":"dummy", "GEMINI_API_KEY":"dummy", "PR_SHA":sha, "GITHUB_REPOSITORY":"org/repo", "PR_NUMBER":"23"}
        files = [{"filename":"a.py", "patch":"@@ -0,0 +1 @@\n+1"}]
        response = {"changes":["x"], "key_files":[], "risks":[], "findings":[]}
        with patch.dict(os.environ, env, clear=True), patch.object(reviewer, "ask_gemini", return_value=response) as model, patch.object(reviewer, "upsert_comment") as publish:
            with patch.object(reviewer, "request_json", side_effect=[pr, [], files, pr, {**pr, "state":"closed"}]):
                self.assertEqual(reviewer.run(), 0)
                publish.assert_not_called()
            with patch.object(reviewer, "request_json", side_effect=[pr, [], files, pr, pr]):
                self.assertEqual(reviewer.run(), 0)
                self.assertEqual([c.args[3] for c in publish.call_args_list], [reviewer.SUMMARY_MARKER, reviewer.MARKER])
            marker = f"{reviewer.MARKER}\n<!-- review v2 {sha} {base} -->"
            comment = {"body":marker, "user":{"login":"github-actions[bot]"}}
            with patch.object(reviewer, "request_json", side_effect=[pr, [comment]]):
                self.assertEqual(reviewer.run(), 0)
            self.assertEqual(model.call_count, 2)
            self.assertEqual(publish.call_count, 2)

    def test_authenticated_json_redirect_is_not_followed(self):
        for code in (301, 302, 303, 307, 308):
            self.assertIsNone(reviewer.NoRedirect().redirect_request(
                None, None, code, "", {}, "https://unused.invalid"))

    def test_review_rechecks_after_diff_read_and_before_egress(self):
        sha = "a" * 40
        repo = "owner/repo"
        pr = {"state": "open", "draft": False, "base": {"ref": "main", "sha": "b" * 40, "repo": {"full_name": repo}},
              "head": {"repo": {"full_name": repo}, "sha": sha}}
        env = {"YULDASH_GEMINI_PR_DIFF": "true", "YULDASH_DEFAULT_BRANCH": "main", "PR_BASE_REF": "main", "GEMINI_API_KEY": "dummy", "GITHUB_TOKEN": "dummy",
               "GITHUB_REPOSITORY": repo, "PR_NUMBER": "1", "PR_SHA": sha}
        files = [{"filename": "x.py", "patch": "@@ -0,0 +1 @@\n+value = 1"}]
        with patch.dict(os.environ, env, clear=True), patch.object(reviewer, "ask_gemini") as model:
            for changed in ({**pr, "draft": True},
                            {**pr, "base": {**pr["base"], "ref": "stack"}}):
                with patch.object(reviewer, "request_json", side_effect=[pr, [], files, changed]):
                    self.assertEqual(reviewer.run(), 0)
        model.assert_not_called()

    def test_default_and_nonexact_opt_in_do_not_read_network_context(self):
        for value in ("", "TRUE", "1", "false"):
            with patch.dict(os.environ, {"YULDASH_GEMINI_PR_DIFF": value}, clear=True):
                with patch.object(reviewer, "request_json") as req:
                    self.assertEqual(reviewer.run(), 0)
                    req.assert_not_called()

    def test_review_rejects_draft_malformed_draft_and_foreign_base(self):
        sha = "a" * 40
        repo = "owner/repo"
        pr = {"state": "open", "draft": False, "base": {"ref": "main", "repo": {"full_name": repo}},
              "head": {"repo": {"full_name": repo}, "sha": sha}}
        self.assertTrue(reviewer.matches_pr(pr, repo, sha, "main"))
        for draft in (True, None, "false", 0):
            self.assertFalse(reviewer.matches_pr({**pr, "draft": draft}, repo, sha, "main"))
        self.assertFalse(reviewer.matches_pr({**pr, "base": {"repo": {"full_name": "other/repo"}}},
                                             repo, sha, "main"))

    def test_nondefault_event_base_skips_before_requests(self):
        env = {"YULDASH_GEMINI_PR_DIFF": "true", "YULDASH_DEFAULT_BRANCH": "main", "PR_BASE_REF": "stack"}
        with patch.dict(os.environ, env, clear=True), patch.object(reviewer, "request_json") as req:
            self.assertEqual(reviewer.run(), 0)
        req.assert_not_called()

    def test_model_links_are_not_rendered_as_clickable_destinations(self):
        malicious_text = "Try HTTPS://example.invalid or www.example.invalid or [site](http://other.invalid)"
        safe = reviewer.safe(malicious_text)
        self.assertNotRegex(safe.lower(), r"https?://|www\.|\[site\]\(")
        self.assertIn("example.invalid", safe)

    def test_fork_pr_is_ignored_before_model_call(self):
        env = {
            "YULDASH_GEMINI_PR_DIFF": "true", "YULDASH_DEFAULT_BRANCH": "main", "PR_BASE_REF": "main",
            "GEMINI_API_KEY": "dummy",
            "GITHUB_TOKEN": "dummy",
            "GITHUB_REPOSITORY": "bairasbai/yuldash",
            "PR_NUMBER": "42",
            "PR_SHA": "f" * 40,
        }
        fork = {"head": {"repo": {"full_name": "outside/fork"}, "sha": "f" * 40}}
        with patch.dict(os.environ, env, clear=True):
            with patch.object(reviewer, "request_json", return_value=fork) as req:
                self.assertEqual(reviewer.run(), 0)
                self.assertEqual(req.call_count, 1)


if __name__ == "__main__":
    unittest.main()
