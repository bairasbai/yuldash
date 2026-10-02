"""Exercise mutation replay and the machine-wide lock against a synthetic project."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "audit_mutation.py"
SOURCE = "backend/app/calc.py"
CODE = b"def add(a, b):\n    return a + b\n\n\ndef unused(x):\n    return x * 2\n"
TEST = b"from app.calc import add\n\n\ndef test_add():\n    assert add(2, 3) == 5\n"


class MutationReplayTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="yuldash-mutation-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.put("backend/app/__init__.py", b"")
        self.put(SOURCE, CODE)
        self.put("backend/tests/test_calc.py", TEST)
        self.locks = self.root / "locks"
        self.env = {**os.environ, "AUDIT_PYTHON": sys.executable, "AUDIT_LOCK_DIR": str(self.locks)}
        self.env.pop("DATABASE_URL", None)

    def put(self, path, data):
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        return target

    def spec(self, *mutations):
        path = self.put("spec.json", json.dumps({"schema": 1, "leaf": "leaf-0", "mutations": [
            {"id": f"M{i + 1}", "file": SOURCE, "runner": "pytest", "args": ["tests/test_calc.py"], **m}
            for i, m in enumerate(mutations)]}).encode("utf-8"))
        return str(path)

    def run_cli(self, *args):
        return subprocess.run([sys.executable, str(SCRIPT), "--root", str(self.root), *args], env=self.env,
                              capture_output=True, text=True, encoding="utf-8", timeout=300)

    def test_validate_requires_snippet_to_occur_exactly_once(self):
        ok = self.run_cli("validate", "--spec", self.spec({"find": "a + b", "replace": "a - b"}))
        self.assertIn("SPEC OK 1", ok.stdout)
        missing = self.run_cli("validate", "--spec", self.spec({"find": "a * b", "replace": "a - b"}))
        self.assertNotEqual(missing.returncode, 0)
        self.assertIn("0 раз", missing.stdout)
        twice = self.run_cli("validate", "--spec", self.spec({"find": "return", "replace": "yield"}))
        self.assertIn("2 раз", twice.stdout)
        self.assertNotIn("SPEC OK", twice.stdout)

    def test_caught_breakage_is_reported_and_source_restored(self):
        proc = self.run_cli("replay", "--spec", self.spec({"find": "a + b", "replace": "a - b"}))
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
        self.assertIn("MUTATIONS KILLED 1/1", proc.stdout)
        self.assertEqual((self.root / SOURCE).read_bytes(), CODE)

    def test_breakage_no_test_notices_fails_the_replay(self):
        proc = self.run_cli("replay", "--spec", self.spec({"find": "x * 2", "replace": "x * 3"}))
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("SURVIVED", proc.stdout)
        self.assertNotIn("MUTATIONS KILLED", proc.stdout)
        self.assertEqual((self.root / SOURCE).read_bytes(), CODE)

    def test_syntax_error_does_not_count_as_caught(self):
        proc = self.run_cli("replay", "--spec", self.spec({"find": "return a + b", "replace": "return a +"}))
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("SURVIVED", proc.stdout)
        self.assertEqual((self.root / SOURCE).read_bytes(), CODE)

    def test_failing_baseline_is_not_counted(self):
        self.put("backend/tests/test_calc.py", TEST.replace(b"== 5", b"== 6"))
        proc = self.run_cli("replay", "--spec", self.spec({"find": "a + b", "replace": "a - b"}))
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("BASELINE FAILED", proc.stdout)
        self.assertIn("MUTATIONS NOT ALL KILLED 0/1", proc.stdout)

    def test_crlf_source_matches_lf_snippet_and_keeps_its_bytes(self):
        crlf = CODE.replace(b"\n", b"\r\n")
        self.put(SOURCE, crlf)
        spec = self.spec({"find": "def add(a, b):\n    return a + b", "replace": "def add(a, b):\n    return a * b"})
        proc = self.run_cli("replay", "--spec", spec)
        self.assertIn("MUTATIONS KILLED 1/1", proc.stdout, proc.stdout + proc.stderr)
        self.assertEqual((self.root / SOURCE).read_bytes(), crlf)

    @unittest.skipUnless(os.environ.get("AUDIT_PG_BASE"), "нужен изолированный PostgreSQL (AUDIT_PG_BASE)")
    def test_postgres_case_gets_a_fresh_database_that_is_dropped_afterwards(self):
        self.put("backend/tests/test_calc.py", TEST + b"\n\ndef test_on_postgres():\n    import os\n"
                 b"    assert os.environ['DATABASE_URL'].startswith('postgresql')\n")
        proc = self.run_cli("replay", "--spec", self.spec({"find": "a + b", "replace": "a - b", "db": "postgres"}))
        self.assertIn("MUTATIONS KILLED 1/1", proc.stdout, proc.stdout + proc.stderr)
        from urllib.parse import urlparse
        base = urlparse(os.environ["AUDIT_PG_BASE"])
        psql = Path(os.environ.get("AUDIT_PG_BIN", r"C:\Program Files\PostgreSQL\16\bin")) / "psql.exe"
        left = subprocess.run([str(psql) if psql.exists() else "psql", "--host", base.hostname, "--port", str(base.port),
                               "--username", base.username, "--no-password", "-d", "postgres", "-tAc",
                               "select count(*) from pg_database where datname like 'walk_m1_%'"],
                              capture_output=True, text=True)
        self.assertEqual(left.stdout.strip(), "0", left.stderr)

    def test_breakage_left_by_a_killed_run_is_put_back(self):
        # A replay killed mid-mutation leaves the broken source and the saved original side by side.
        self.put(SOURCE, CODE.replace(b"a + b", b"a - b"))
        self.put(SOURCE + ".audit-original", CODE)
        proc = self.run_cli("recover")
        self.assertIn("RECOVERY DONE 1", proc.stdout, proc.stdout + proc.stderr)
        self.assertEqual((self.root / SOURCE).read_bytes(), CODE)
        self.assertFalse((self.root / (SOURCE + ".audit-original")).exists())

    def test_replay_repairs_a_killed_run_before_it_starts(self):
        self.put(SOURCE, CODE.replace(b"a + b", b"a - b"))
        self.put(SOURCE + ".audit-original", CODE)
        proc = self.run_cli("replay", "--spec", self.spec({"find": "a + b", "replace": "a - b"}))
        self.assertIn("RECOVERED backend/app/calc.py", proc.stdout, proc.stdout + proc.stderr)
        self.assertIn("MUTATIONS KILLED 1/1", proc.stdout)
        self.assertEqual((self.root / SOURCE).read_bytes(), CODE)
        self.assertFalse((self.root / (SOURCE + ".audit-original")).exists())

    def test_lock_run_returns_command_exit_and_releases_lock(self):
        proc = self.run_cli("lock-run", "--name", "demo", "--owner", "t", "--",
                            sys.executable, "-c", "import sys; sys.exit(3)")
        self.assertEqual(proc.returncode, 3)
        self.assertFalse((self.locks / "demo").exists())

    def test_abandoned_lock_of_dead_process_is_taken_over(self):
        dead = subprocess.run([sys.executable, "-c", "import os; print(os.getpid())"], capture_output=True, text=True)
        stale = self.locks / "demo"
        stale.mkdir(parents=True)
        (stale / "owner.json").write_text(json.dumps({"pid": int(dead.stdout), "owner": "gone", "since": 0}))
        old = time.time() - 600
        os.utime(stale, (old, old))
        proc = self.run_cli("lock-run", "--name", "demo", "--owner", "t", "--timeout", "60", "--",
                            sys.executable, "-c", "print('ran')")
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
        self.assertIn("ran", proc.stdout)
        self.assertIn("брошенный замок", proc.stdout)


if __name__ == "__main__":
    unittest.main()
