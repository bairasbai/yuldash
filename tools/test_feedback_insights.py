import builtins
from contextlib import redirect_stderr, redirect_stdout
import io
import json
from pathlib import Path
import socket
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch

import feedback_insights as feedback
import feedback_docetl as worker


class FeedbackTests(unittest.TestCase):
    def setUp(self):
        self.rows = [{"id": "a", "project": "yuldash", "text": "Search failed"}, {"id": "b", "project": "tili", "text": "Useful guest list"}]
        self.labels = [{"id": "a", "project": "yuldash", "category": "bug", "sentiment": "negative"}, {"id": "b", "project": "tili", "category": "praise", "sentiment": "positive"}]

    def test_accepts_both_projects_and_rejects_bad_inputs(self):
        self.assertEqual(feedback.records({"schemaVersion": 1, "records": self.rows}), self.rows)
        for value in [None, {}, {"schemaVersion": True, "records": []}, {"schemaVersion": 1, "records": self.rows * 2}, {"schemaVersion": 1, "records": [{**self.rows[0], "project": "other"}]}, {"schemaVersion": 1, "records": [{**self.rows[0], "text": "x" * 4001}]}, {"schemaVersion": 1, "records": [{**self.rows[0], "phone": "private"}]}, {"schemaVersion": 1, "records": [{**self.rows[0], "text": "\0"}]}]:
            with self.subTest(value=str(type(value))):
                with self.assertRaises(feedback.InputError):
                    feedback.records(value)

    def test_redaction_is_local_and_reports_no_raw_values(self):
        rows = [{"id": "a", "project": "yuldash", "text": "contact demo@example.invalid +7 (999) 000-00-00 https://example.invalid/secret 54.123456, 55.654321"}]
        found = feedback.privacy_findings(rows)
        self.assertEqual(found[0]["id"], "a")
        self.assertNotIn("example.invalid", json.dumps(found))
        clean = feedback.redact(rows)
        self.assertNotIn("example.invalid", clean[0]["text"])
        self.assertNotIn("999", clean[0]["text"])
        self.assertEqual(rows[0]["text"].count("demo@"), 1)

    def test_counts_are_computed_from_original_records(self):
        report = feedback.summarize(self.rows, list(reversed(self.labels)))
        self.assertEqual(report["total"], 2)
        self.assertEqual(report["projects"]["yuldash"]["categories"]["bug"], 1)
        self.assertEqual(report["projects"]["tili"]["categoryPercent"]["praise"], 100.0)
        self.assertEqual(feedback.summarize([])["total"], 0)
        self.assertEqual(feedback.summarize(self.rows)["mode"], "counts_only")

    def test_rejects_forged_missing_duplicate_and_invalid_results(self):
        for labels in [self.labels[:1], [self.labels[0]] * 2, [{**self.labels[0], "id": "other"}, self.labels[1]], [{**self.labels[0], "project": "tili"}, self.labels[1]], [{**self.labels[0], "category": "execute"}, self.labels[1]], [{**self.labels[0], "id": []}, self.labels[1]]]:
            with self.assertRaises(feedback.InputError):
                feedback.validate_labels(self.rows, labels)
        sdk = [{**row, **label} for row, label in zip(self.rows, self.labels)]
        self.assertEqual(feedback.validate_sdk_results(self.rows, sdk), self.labels)
        sdk[0]["text"] = "Altered by model"
        with self.assertRaises(feedback.InputError):
            feedback.validate_sdk_results(self.rows, sdk)

    def test_default_cli_never_imports_sdk_or_opens_network(self):
        original_import = builtins.__import__

        def deny_sdk(name, *args, **kwargs):
            if name.split(".")[0] in ("docetl", "litellm", "dotenv"):
                raise AssertionError("SDK import forbidden in ordinary commands")
            return original_import(name, *args, **kwargs)

        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "export.json"
            path.write_text(json.dumps({"schemaVersion": 1, "records": self.rows}), encoding="utf8")
            with patch("builtins.__import__", side_effect=deny_sdk), patch.object(socket.socket, "connect", side_effect=AssertionError("Network forbidden")), redirect_stdout(io.StringIO()) as output:
                self.assertEqual(feedback.main(["validate", str(path)]), 0)
                self.assertEqual(feedback.main(["summarize", str(path)]), 0)
            self.assertNotIn("Search failed", output.getvalue())

    def test_requires_every_explicit_opt_in_and_never_overwrites_input(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "export.json"
            source = json.dumps({"schemaVersion": 1, "records": self.rows})
            path.write_text(source, encoding="utf8")
            with patch.object(feedback, "classify", side_effect=AssertionError("Must not run")), redirect_stderr(io.StringIO()):
                self.assertEqual(feedback.main(["classify", str(path)]), 2)
                self.assertEqual(feedback.main(["classify", str(path), "--send-model", "--privacy-reviewed", "--model", "fake/model", "--allowed-host", "example.invalid", "--output", str(path)]), 2)
                self.assertEqual(feedback.main(["redact", str(path), "--output", str(path)]), 2)
                self.assertEqual(feedback.main(["classify", str(path), "--send-model", "--privacy-reviewed", "--model", "fake/model", "--allowed-host", "example.invalid", "--output", str(Path(folder)/"missing"/"out.json")]), 2)
            self.assertEqual(path.read_text(encoding="utf8"), source)

    def test_worker_network_gate_blocks_unapproved_and_private_hosts(self):
        original = worker.restrict_network("provider.example")
        try:
            with self.assertRaises(OSError):
                socket.getaddrinfo("telemetry.example", 443)
            with self.assertRaises(OSError):
                socket.getaddrinfo("provider.example", 80)
            with socket.socket() as connection:
                with self.assertRaises(OSError):
                    connection.connect(("127.0.0.1", 443))
            left, right = socket.socketpair()
            with left, right:
                left.sendall(b"safe")
                self.assertEqual(right.recv(4), b"safe")
        finally:
            socket.getaddrinfo, socket.socket.connect, socket.socket.connect_ex, socket.socketpair = original

    def test_worker_requires_isolation_before_importing_docetl(self):
        with patch.dict("os.environ", {}, clear=True):
            with self.assertRaises(RuntimeError):
                worker.run(self.rows, "fake/model", "provider.example")

    def test_reserves_output_before_model_and_removes_own_incomplete_result(self):
        with tempfile.TemporaryDirectory() as folder:
            source, destination = Path(folder)/"input.json", Path(folder)/"labels.json"
            source.write_text(json.dumps({"schemaVersion": 1, "records": self.rows}), encoding="utf8")
            def failed_model(*args, **kwargs):
                self.assertTrue(destination.exists())
                self.assertEqual(destination.read_text(encoding="utf8"), "")
                raise feedback.InputError("Synthetic failure")
            with patch.object(feedback, "classify", side_effect=failed_model), redirect_stderr(io.StringIO()):
                self.assertEqual(feedback.main(["classify",str(source),"--send-model","--privacy-reviewed","--model","fake/model","--allowed-host","provider.example","--output",str(destination)]),2)
            self.assertFalse(destination.exists())

    def test_worker_timeout_and_cancellation_stop_before_temp_cleanup(self):
        for failure in (subprocess.TimeoutExpired("synthetic", 1), KeyboardInterrupt()):
            process = Mock(pid=999999, returncode=None)
            process.poll.side_effect = lambda: process.returncode
            process.kill.side_effect = lambda: setattr(process, "returncode", -9)
            process.communicate.side_effect = [failure, ("", "")]
            owned = []
            def launch(*args, **kwargs):
                owned.append(Path(kwargs["env"]["DOCETL_HOME_DIR"]))
                self.assertTrue(owned[-1].is_dir())
                self.assertIn("utf8", args[0])
                return process
            expected = feedback.InputError if isinstance(failure, subprocess.TimeoutExpired) else KeyboardInterrupt
            with patch.object(feedback.subprocess,"Popen",side_effect=launch), patch.object(feedback.subprocess,"run") as stop_tree, patch.object(feedback.os,"killpg",create=True):
                with self.assertRaises(expected):
                    feedback.classify(self.rows,model="fake/model",allowed_host="provider.example",timeout=1)
                self.assertTrue(process.kill.called)
                self.assertEqual(process.communicate.call_args.kwargs["timeout"],5)
                if feedback.os.name=="nt":self.assertEqual(stop_tree.call_args.kwargs["timeout"],5)
            self.assertFalse(owned[-1].exists())


if __name__ == "__main__":
    unittest.main()
