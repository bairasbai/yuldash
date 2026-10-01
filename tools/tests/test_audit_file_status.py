"""Exercise the per-file mark registry CLI against a separate synthetic Git repository."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "audit_file_status.py"
KT = "android/app/src/main/java/com/yuldash/app/Wallet.kt"
PY = "backend/app/ledger.py"
MIGRATION = "backend/alembic/versions/0001_init.py"
TOOL = "tools/helper.py"
OUT_OF_SCOPE = ("webapp/src/main.ts", "web/index.html", "promo/ad.html", "backend/tests/test_other.py",
                "mobile/lib/main.dart", "android/app/src/test/java/com/yuldash/app/WalletTest.kt",
                "android/app/src/androidTest/java/com/yuldash/app/WalletInstrumentedTest.kt")


def card(path, status="verified", leaf="leaf-1.1", rules=None, mutations="| M1 | сломал сумму | упал |",
         remainder="Остатка нет."):
    rules = rules if rules is not None else "| R1 | сумма сходится | backend/tests/test_ledger.py::test_sum | да, M1 |"
    return (f"# Карточка: `{path}`\n\n- Статус: {status}\n- Лист: {leaf}\n\n"
            "## Назначение\nСчитает деньги.\n\n"
            "## Функции и разбор\n| Функция | Строки | Что делает | Ошибки | Вердикт |\n|---|---|---|---|---|\n"
            "| total | 1-3 | сумма | нет | ок |\n\n"
            "## Связи\nЭкран кошелька.\n\n"
            f"## Важные правила и тесты\n| ID | Правило | Тесты | Ловит |\n|---|---|---|---|\n{rules}\n\n"
            "## Найденные ошибки\nОшибок не найдено.\n\n"
            f"## Проверка нарочной поломкой\n| ID | Что сломали | Результат |\n|---|---|---|\n{mutations}\n\n"
            f"## Остаток и ограничения\n{remainder}\n")


class FileStatusCliTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="yuldash-file-status-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.git("init", "--quiet")
        self.git("config", "user.email", "audit@example.invalid")
        self.git("config", "user.name", "audit")
        self.git("config", "core.autocrlf", "false")
        self.put(KT, b"class Wallet\n")
        self.put(PY, b"def total(a, b):\n    return a + b\n")
        self.put(MIGRATION, b"revision = '0001'\n")
        self.put(TOOL, b"print('ok')\n")
        self.put(".gitignore", b"*.tmp\n")
        self.put("backend/tests/test_ledger.py", b"def test_sum():\n    assert True\n")
        for path in OUT_OF_SCOPE:
            self.put(path, b"x\n")
        self.git("add", "-A")
        self.git("commit", "--quiet", "-m", "base")

    def git(self, *args):
        return subprocess.run(["git", *args], cwd=self.root, check=True, capture_output=True, text=True).stdout

    def put(self, path, data):
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        return target

    def run_cli(self, *args, ok=True):
        env = {**os.environ, "AUDIT_PYTHON": sys.executable}
        env.pop("DATABASE_URL", None)
        proc = subprocess.run([sys.executable, str(SCRIPT), "--root", str(self.root), *args],
                              capture_output=True, text=True, encoding="utf-8", env=env)
        if ok:
            self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
        return proc

    def registry(self):
        return {e["path"]: e for e in json.loads((self.root / "docs/audit-file-status.json").read_text(encoding="utf-8"))["files"]}

    def spec(self, leaf="leaf-1.1", file=PY):
        spec = {"schema": 1, "leaf": leaf, "mutations": [
            {"id": "M1", "file": file, "find": "a + b", "replace": "a - b", "runner": "pytest",
             "args": ["tests/test_ledger.py"]}]}
        self.put(f"docs/audit-mutations/{leaf}.json", json.dumps(spec).encode("utf-8"))

    def test_init_takes_exactly_the_agreed_scope(self):
        self.run_cli("init")
        reg = self.registry()
        self.assertEqual(set(reg), {KT, PY, MIGRATION, TOOL, ".gitignore"})
        self.assertTrue(all(e["status"] == "not_read" for e in reg.values()))
        self.assertEqual((reg[PY]["block"], reg[PY]["group"]), ("B07", "G1"))
        self.assertEqual((reg[KT]["block"], reg[KT]["group"]), ("B07", "G1"))
        self.assertEqual((reg[MIGRATION]["block"], reg[MIGRATION]["group"]), ("B10", "G7"))

    def test_verified_card_needs_resolvable_tests_and_recorded_breakage(self):
        self.run_cli("init")
        self.put(f"docs/audit-files/{PY}.md", card(PY).encode("utf-8"))
        bad = self.run_cli("check-cards", "--files", PY, ok=False)
        self.assertNotEqual(bad.returncode, 0)
        self.assertIn("нет файла docs/audit-mutations/leaf-1.1.json", bad.stdout)
        self.spec()
        good = self.run_cli("check-cards", "--files", PY, "--leaf", "leaf-1.1")
        self.assertIn("CARDS OK 1", good.stdout)

    def test_reference_to_missing_test_function_is_rejected(self):
        self.run_cli("init")
        self.spec()
        rules = "| R1 | сумма | backend/tests/test_ledger.py::test_does_not_exist | да |"
        self.put(f"docs/audit-files/{PY}.md", card(PY, rules=rules).encode("utf-8"))
        proc = self.run_cli("check-cards", "--files", PY, ok=False)
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("нет функции/класса test_does_not_exist", proc.stdout)
        self.assertNotIn("CARDS OK", proc.stdout)

    def test_kotlin_reference_with_spaces_resolves(self):
        self.run_cli("init")
        test_kt = "android/app/src/test/java/com/yuldash/app/WalletTest.kt"
        self.put(test_kt, b"class WalletTest {\n  @Test fun `balance never negative`() {}\n}\n")
        self.put("docs/audit-mutations/leaf-1.1.json", json.dumps({"mutations": [
            {"id": "M1", "file": KT, "find": "Wallet", "replace": "Purse", "runner": "gradle",
             "tests": ["com.yuldash.app.WalletTest"]}]}).encode("utf-8"))
        rules = f'| R1 | баланс | {test_kt}::"balance never negative" | да |'
        self.put(f"docs/audit-files/{KT}.md", card(KT, rules=rules).encode("utf-8"))
        self.assertIn("CARDS OK 1", self.run_cli("check-cards", "--files", KT).stdout)

    def test_verified_without_breakage_is_rejected(self):
        self.run_cli("init")
        self.spec()
        self.put(f"docs/audit-files/{PY}.md", card(PY, mutations="").encode("utf-8"))
        proc = self.run_cli("check-cards", "--files", PY, ok=False)
        self.assertIn("нужна хотя бы одна нарочная поломка", proc.stdout)

    def test_inactive_needs_a_reason(self):
        self.run_cli("init")
        self.put(f"docs/audit-files/{MIGRATION}.md", card(MIGRATION, status="inactive").encode("utf-8"))
        proc = self.run_cli("check-cards", "--files", MIGRATION, ok=False)
        self.assertIn("Причина", proc.stdout)
        remainder = "Причина: архивный SQL, нигде не вызывается — проверено поиском по репозиторию."
        self.put(f"docs/audit-files/{MIGRATION}.md", card(MIGRATION, status="inactive", remainder=remainder).encode("utf-8"))
        self.assertIn("CARDS OK 1", self.run_cli("check-cards", "--files", MIGRATION).stdout)

    def test_apply_stamps_hash_and_edit_makes_mark_stale(self):
        self.run_cli("init")
        self.spec()
        self.put(f"docs/audit-files/{PY}.md", card(PY).encode("utf-8"))
        self.assertIn("APPLIED 1", self.run_cli("apply", "--files", PY, "--leaf", "leaf-1.1").stdout)
        entry = self.registry()[PY]
        self.assertEqual(entry["status"], "verified")
        self.assertEqual(entry["tests"], ["backend/tests/test_ledger.py::test_sum"])
        self.assertIn("REGISTRY CONSISTENT", self.run_cli("check").stdout)
        self.put(PY, b"def total(a, b):\n    return b + a\n")
        stale = self.run_cli("check", "--strict", ok=False)
        self.assertNotEqual(stale.returncode, 0)
        self.assertIn(PY, stale.stdout)

    def test_crlf_checkout_does_not_make_mark_stale(self):
        self.run_cli("init")
        self.spec()
        self.put(f"docs/audit-files/{PY}.md", card(PY).encode("utf-8"))
        self.run_cli("apply", "--files", PY, "--leaf", "leaf-1.1")
        self.put(PY, b"def total(a, b):\r\n    return a + b\r\n")
        self.assertIn("REGISTRY CONSISTENT", self.run_cli("check", "--strict").stdout)

    def test_final_gate_only_passes_when_every_file_is_final_and_fresh(self):
        self.run_cli("init")
        incomplete = self.run_cli("progress", "--require-final", ok=False)
        self.assertIn("FILE WALK INCOMPLETE", incomplete.stdout)
        self.assertNotIn("FILE WALK COMPLETE", incomplete.stdout)
        reason = "Причина: синтетический файл теста, исполнять нечего — проверено чтением."
        for path in (KT, PY, MIGRATION, TOOL, ".gitignore"):
            self.put(f"docs/audit-files/{path}.md", card(path, status="inactive", remainder=reason).encode("utf-8"))
        self.run_cli("apply", "--files", KT, PY, MIGRATION, TOOL, ".gitignore", "--leaf", "leaf-1.1")
        self.assertIn("FILE WALK COMPLETE 5", self.run_cli("progress", "--require-final").stdout)
        self.put("backend/app/new_module.py", b"x = 1\n")
        drift = self.run_cli("progress", "--require-final", ok=False)
        self.assertIn("новых 1", drift.stdout)

    def test_report_lists_every_file_with_its_mark(self):
        self.run_cli("init")
        self.assertIn("REPORT WRITTEN", self.run_cli("report").stdout)
        text = (self.root / "docs/audit-file-status.md").read_text(encoding="utf-8")
        for path in (KT, PY, MIGRATION, TOOL):
            self.assertIn(f"`{path}`", text)
        self.assertIn("Готово (🟩/⚪/⛔): 0 из 5", text)

    def test_run_tests_is_green_only_when_referenced_tests_pass(self):
        self.run_cli("init")
        self.spec()
        self.put(f"docs/audit-files/{PY}.md", card(PY).encode("utf-8"))
        green = self.run_cli("run-tests", "--files", PY, "--leaf", "leaf-1.1")
        self.assertIn("LEAF TESTS GREEN 1", green.stdout)
        self.put("backend/tests/test_ledger.py", b"def test_sum():\n    assert False\n")
        red = self.run_cli("run-tests", "--files", PY, "--leaf", "leaf-1.1", ok=False)
        self.assertNotEqual(red.returncode, 0)
        self.assertIn("LEAF TESTS RED", red.stdout)
        self.assertNotIn("LEAF TESTS GREEN", red.stdout)

    def test_run_tests_refuses_a_card_without_test_references(self):
        self.run_cli("init")
        self.put(f"docs/audit-files/{PY}.md", card(PY, status="read", rules="", mutations="").encode("utf-8"))
        proc = self.run_cli("run-tests", "--files", PY, ok=False)
        self.assertIn("LEAF TESTS RED", proc.stdout)

    def test_progress_can_be_limited_to_one_group(self):
        self.run_cli("init")
        data = json.loads(self.run_cli("progress", "--group", "G7").stdout)
        self.assertEqual(data["total"], 3)
        self.assertEqual(data["by_status"]["not_read"], 3)

    def test_owns_guard_on_a_finished_branch_ignores_local_edits(self):
        base = self.git("rev-parse", "HEAD").strip()
        self.git("checkout", "--quiet", "-b", "leaf")
        self.put(KT, b"class Wallet2\n")
        self.git("commit", "--quiet", "-am", "leaf work")
        self.git("checkout", "--quiet", "-")
        self.put(PY, b"# local, not part of the leaf\n")
        ok = self.run_cli("owns-guard", "--base", base, "--head", "leaf", "--owns", "android/**")
        self.assertIn("OWNS RESPECTED 1", ok.stdout)
        bad = self.run_cli("owns-guard", "--base", base, "--head", "leaf", "--owns", "backend/**", ok=False)
        self.assertIn("OWNS VIOLATED 1", bad.stdout)

    def test_file_and_glob_lists_can_come_from_files(self):
        self.run_cli("init")
        self.spec()
        self.put(f"docs/audit-files/{PY}.md", card(PY).encode("utf-8"))
        self.put("lists/files.txt", f"{PY}\n\n".encode("utf-8"))
        self.assertIn("CARDS OK 1", self.run_cli("check-cards", "--files-from", "lists/files.txt").stdout)
        base = self.git("rev-parse", "HEAD").strip()
        self.put(PY, b"def total(a, b):\n    return a + b  # touched\n")
        self.put("lists/owns.txt", b"backend/app/**\nlists/**\ndocs/**\n")
        self.assertIn("OWNS RESPECTED", self.run_cli("owns-guard", "--base", base, "--owns-from", "lists/owns.txt").stdout)
        missing = self.run_cli("check-cards", ok=False)
        self.assertNotEqual(missing.returncode, 0)

    def test_owns_guard_flags_edits_outside_the_leaf(self):
        base = self.git("rev-parse", "HEAD").strip()
        self.put(PY, b"def total(a, b):\n    return a + b  # ok\n")
        ok = self.run_cli("owns-guard", "--base", base, "--owns", "backend/app/ledger.py,docs/audit-files/**")
        self.assertIn("OWNS RESPECTED 1", ok.stdout)
        self.put(KT, b"class Wallet2\n")
        bad = self.run_cli("owns-guard", "--base", base, "--owns", "backend/app/ledger.py", ok=False)
        self.assertIn(KT, bad.stdout)
        self.assertIn("OWNS VIOLATED 1", bad.stdout)
        ignored = self.run_cli("owns-guard", "--base", base, "--owns", "backend/app/ledger.py", "--ignore", "android/**")
        self.assertIn("OWNS RESPECTED", ignored.stdout)


if __name__ == "__main__":
    unittest.main()
