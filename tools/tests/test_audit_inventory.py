"""Exercise the inventory CLI against a separate synthetic Git repository."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "audit_inventory.py"
ACTIVE_CONFIGS = (
    "android/app/proguard-rules.pro",
    "backend/alembic/script.py.mako",
    "backend/deploy/yuldash-automatch.service",
    "backend/deploy/yuldash-automatch.timer",
    "webapp/nginx.conf.example",
    "backend/deploy/yuldash-api.service.example",
    "backend/deploy/nginx-yuldash-scale.conf.example",
    "backend/deploy/pgbouncer.ini.example",
    "backend/deploy/redis.conf.example",
    "backend/deploy/yuldash-cleanup.service",
    "backend/deploy/yuldash-cleanup.timer",
    "backend/deploy/yuldash-doc-check.service",
    "backend/deploy/yuldash-doc-check.timer",
    "backend/deploy/yuldash-rate-reminder.service",
    "backend/deploy/yuldash-rate-reminder.timer",
    "backend/deploy/yuldash-taxi-worker.service",
    "backend/deploy/yuldash-taxi-worker.timer",
    "backend/ops/crontab.example",
    "backend/ops/systemd.example",
)


class InventoryCliTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="yuldash-inventory-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        subprocess.run(["git", "init", "--quiet", str(self.root)], check=True,
                       capture_output=True)
        self.baseline = self.root / "docs/audit-code-inventory.json"
        self.baseline.parent.mkdir()

    def put(self, path, data):
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        return target

    def run_cli(self, *args, expected=0):
        result = subprocess.run([sys.executable, str(SCRIPT), "--root", str(self.root),
                                 *args], capture_output=True, text=True, encoding="utf-8")
        self.assertEqual(result.returncode, expected, result.stdout + result.stderr)
        return json.loads(result.stdout)

    def read_snapshot(self):
        return json.loads(self.baseline.read_text(encoding="utf-8"))

    def test_operational_texts_are_included_and_sensitive_exclusions_remain(self):
        for path in ACTIVE_CONFIGS:
            self.put(path, b"# synthetic operational configuration\n")
        excluded = {
            "backend/.env.example": "secret_or_local_settings_not_read",
            "backend/deploy/service-account.service": "secret_or_local_settings_not_read",
            "android/local.properties": "secret_or_local_settings_not_read",
            "backend/node_modules/example.service": "dependencies_or_generated",
            "mobile/example.pro": "legacy_flutter_do_not_modify",
            "experiments/example.timer": "experimental_not_current_product",
            "docs/example.example": "documentation_artifacts_or_private_material",
            "promo/public/example.png": "binary_asset_or_non_source",
        }
        for path in excluded:
            self.put(path, b"synthetic content only\n")
        delta = self.run_cli("--write")
        self.assertEqual(set(delta["added"]), set(ACTIVE_CONFIGS))
        snapshot = self.read_snapshot()
        self.assertEqual({f["path"] for f in snapshot["files"]}, set(ACTIVE_CONFIGS))
        self.assertTrue(all(f["block"] == "B10" for f in snapshot["files"]))
        self.assertEqual({f["path"]: f["reason"] for f in snapshot["excluded"]}, excluded)
        self.run_cli("--check")

    def test_line_endings_preserve_hash_but_real_content_drift_is_detected(self):
        source = self.put("android/app/proguard-rules.pro", b"-keep class A\r\n# note\r")
        self.run_cli("--write")
        before = self.read_snapshot()["files"][0]["sha256"]
        self.assertEqual(before, hashlib.sha256(b"-keep class A\n# note\n").hexdigest())
        source.write_bytes(b"-keep class A\n# note\n")
        self.assertEqual(self.run_cli("--check")["changed"], [])
        source.write_bytes(b"-keep class B\n# note\n")
        self.assertEqual(self.run_cli("--check", expected=1)["changed"],
                         ["android/app/proguard-rules.pro"])
        self.run_cli("--write")
        self.assertNotEqual(self.read_snapshot()["files"][0]["sha256"], before)
        self.run_cli("--check")

    def test_expanding_scope_keeps_existing_hashes_and_output_out_of_itself(self):
        self.put("android/app/src/main/java/com/example/Example.kt", b"class Example\r\n")
        self.run_cli("--write")
        before = self.read_snapshot()
        for path in ACTIVE_CONFIGS:
            self.put(path, b"# new own template\n")
        delta = self.run_cli("--check", expected=1)
        self.assertEqual(set(delta["added"]), set(ACTIVE_CONFIGS))
        self.assertEqual(delta["changed"], [])
        self.run_cli("--write")
        after = self.read_snapshot()
        self.assertEqual(before["hash_algorithm"], after["hash_algorithm"])
        old_path = before["files"][0]["path"]
        self.assertEqual(before["files"][0], next(f for f in after["files"] if f["path"] == old_path))
        self.assertNotIn("docs/audit-code-inventory.json", {f["path"] for f in after["files"]})
        self.run_cli("--check")


if __name__ == "__main__":
    unittest.main()
