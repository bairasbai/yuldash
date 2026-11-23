import json
import subprocess
import sys
import tempfile
import unittest
from copy import deepcopy
from pathlib import Path

from preflight import CHANNELS, SHARES, validate


def fixture():
    build = {
        "backend_commit": "a" * 40, "android_commit": "b" * 40, "pwa_commit": "c" * 40,
        "migration": "qa_revision", "apk_sha256": "d" * 64, "pwa_dist_sha256": "e" * 64,
    }
    origins = dict.fromkeys(CHANNELS, "https://api.staging.invalid")
    origins["pwa_document"] = "https://app.staging.invalid"
    origins["dummy_sms_share"] = "https://tracking.staging.invalid"
    expected = {"provenance": build, "origins": origins}
    observed = {
        "provenance": dict(build),
        "installed_apk_sha256": dict.fromkeys(CHANNELS[:2], build["apk_sha256"]),
        "routes": {
            channel: {"origins": [origin], "final_origin": origin}
            | ({"path_template": "/t/{token}", "final_path_template": "/t/{token}"} if channel in SHARES else {})
            for channel, origin in origins.items()
        },
        "public_rideshare": {"path_template": "/r/{id}", "exercised": False},
    }
    return expected, observed


class Preflight(unittest.TestCase):
    def test_expected_staging_and_same_origin_redirect(self):
        expected, observed = fixture()
        for channel in CHANNELS:
            observed["routes"][channel]["origins"].append(expected["origins"][channel])
        self.assertIsNone(validate(expected, observed))

    def test_the_existing_pinned_loopback_configuration(self):
        expected, observed = fixture()
        for channel in ("android_passenger", "android_driver", "android_share"):
            origin = "http://127.0.0.1:8000"
            expected["origins"][channel] = origin
            observed["routes"][channel].update(origins=[origin], final_origin=origin)
        self.assertIsNone(validate(expected, observed))

    def test_invalid_or_known_production_origin_in_expectation(self):
        for bad in ("https://yulbash.ru", "https://app.yulbash.ru", "https://yulbash.ru.",
                    "https://yulbash.ru@staging.invalid", "https://STAGING.invalid", "https://staging.invalid:443",
                    "https://staging.invalid/path", "http://staging.invalid", "https://0x7f.0x0.0x0.0x1",
                    "https://staging.invalid:65536", "https://staging.invalid?q=secret"):
            with self.subTest(bad=bad):
                expected, observed = fixture()
                expected["origins"]["dummy_sms_share"] = bad
                self.assertEqual(validate(expected, observed), "invalid_expected_origins")

    def test_wrong_redirect_or_end_in_each_channel(self):
        for channel in CHANNELS:
            for key, val in (("origins", []), ("origins", ["https://outside.invalid"]),
                             ("final_origin", "https://outside.invalid")):
                with self.subTest(channel=channel, key=key):
                    expected, observed = fixture()
                    observed["routes"][channel][key] = val
                    self.assertEqual(validate(expected, observed), f"origin_mismatch:{channel}")
        expected, observed = fixture()
        route = observed["routes"]["dummy_sms_share"]
        route["origins"] = [route["final_origin"], "https://yulbash.ru", route["final_origin"]]
        self.assertEqual(validate(expected, observed), "origin_mismatch:dummy_sms_share")

    def test_foreign_apk_bound_to_one_device_or_wrong_migration(self):
        for actor in CHANNELS[:2]:
            expected, observed = fixture()
            observed["installed_apk_sha256"][actor] = "0" * 64
            self.assertEqual(validate(expected, observed), "installed_apk_mismatch")
        for key in expected["provenance"]:
            expected, observed = fixture()
            original = observed["provenance"][key]
            observed["provenance"][key] = "f" * len(original)
            self.assertEqual(validate(expected, observed), "provenance_mismatch")

    def test_missing_dummy_sms_or_bad_templates_or_exercised_public_route(self):
        expected, observed = fixture()
        missing = deepcopy(observed)
        missing["routes"].pop("dummy_sms_share")
        self.assertEqual(validate(expected, missing), "missing_route_observation")
        for flag in (0, True):
            wrong = deepcopy(observed)
            wrong["public_rideshare"]["exercised"] = flag
            self.assertEqual(validate(expected, wrong), "public_rideshare_in_scope")
        for channel in SHARES:
            for field in ("path_template", "final_path_template"):
                wrong = deepcopy(observed)
                wrong["routes"][channel][field] = "/r/{id}"
                self.assertEqual(validate(expected, wrong), f"invalid_route_observation:{channel}")

    def test_inconsistent_actual_client_link_contract_in_expectation(self):
        for channel in ("android_driver", "android_share", "pwa_share"):
            expected, observed = fixture()
            expected["origins"][channel] = "https://outside.invalid"
            self.assertEqual(validate(expected, observed), "inconsistent_client_origin_expectation")

    def test_cli_rejects_duplicate_or_unknown_fields_without_echo(self):
        expected, observed = fixture()
        script = Path(__file__).with_name("preflight.py")
        with tempfile.TemporaryDirectory() as directory:
            expectation = Path(directory) / "expected"
            capture = Path(directory) / "captured"
            expectation.write_text(json.dumps(expected), encoding="utf-8")
            invalid = ("{" + json.dumps("private_lookup") + ":1,\"private_lookup\":2}",
                       json.dumps(observed | {"private_lookup": "test-token"}),
                       "{")
            for data in (json.dumps(observed), *invalid):
                capture.write_text(data, encoding="utf-8")
                result = subprocess.run(
                    [sys.executable, "-B", str(script), str(expectation), str(capture)],
                    capture_output=True, text=True, check=False,
                )
                self.assertEqual(result.returncode, 0 if data == json.dumps(observed) else 1)
                self.assertNotIn("test-token", result.stdout + result.stderr)
                self.assertNotIn("private_lookup", result.stdout + result.stderr)
                if not result.returncode:
                    self.assertIn("safety not verified", result.stdout)

    def test_cli_usage_errors_do_not_echo_argument_values(self):
        script = Path(__file__).with_name("preflight.py")
        fake = "QA_FAKE_MARKER_ABC"
        for args in (
            ("expectation", "observation", f"--unknown={fake}"),
            ("expectation", "observation", fake),
            (f"--{fake}",),
            ("-h" + fake,),
            ("--he",),
            ("--help", fake),
            (),
        ):
            with self.subTest(args=args):
                result = subprocess.run([sys.executable, "-B", str(script), *args],
                                        capture_output=True, text=True, check=False)
                self.assertEqual(result.returncode, 2)
                self.assertEqual(result.stdout, "")
                self.assertEqual(result.stderr, "FAIL: invalid_arguments\n")
        help_result = subprocess.run([sys.executable, "-B", str(script), "--help"],
                                     capture_output=True, text=True, check=False)
        self.assertEqual(help_result.returncode, 0)
        self.assertIn("usage:", help_result.stdout)
        self.assertEqual(help_result.stderr, "")
