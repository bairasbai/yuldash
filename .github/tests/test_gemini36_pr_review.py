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
                {"severity": "BLOCKER", "file": "src/api.py", "line": 12,
                 "title": "Crash", "evidence": "Null access", "fix": "Handle None"},
                {"severity": "BLOCKER", "file": "src/api.py", "line": 99,
                 "title": "Invented", "evidence": "No anchor", "fix": "No"},
                {"severity": "MAJOR", "file": "other.py", "line": 12,
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
            return {"candidates": [{"content": {"parts": [{"text": '{"summary":"OK","findings":[]}'}]}}]}

        with patch.object(reviewer, "request_json", side_effect=fake_request):
            result = reviewer.ask_gemini("diff", "dummy-key-for-test")
        self.assertEqual(result["findings"], [])
        self.assertIn("/gemini-3.6-flash:generateContent", seen["url"])
        self.assertEqual(seen["payload"]["generationConfig"]["responseMimeType"], "application/json")
        self.assertNotIn("dummy-key-for-test", str(seen["payload"]))

    def test_fork_pr_is_ignored_before_model_call(self):
        env = {
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
