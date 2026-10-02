"""Replay recorded deliberate breakages ("mutations") to prove that tests catch them.

A spec docs/audit-mutations/<leaf>.json lists mutations: an exact snippet of a source
file, its broken replacement and the test command that must catch it. Replay first runs
the command on the untouched code (it must pass), then applies the breakage, runs the
command again (a real test failure is required: a crash, collection error or compile
error does not count), and always restores the original bytes and re-checks their hash.

The lock-run command serializes heavy shared resources (Gradle) between agents working
in different checkouts of this repository on one machine.
"""
import argparse
import ctypes
import hashlib
import json
import os
import random
import re
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path
from urllib.parse import urlparse

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_PYTHON = Path(r"C:\Users\Bayra\Yuldash\backend\.venv\Scripts\python.exe")
DEFAULT_JAVA_HOME = r"C:\Program Files\Android\Android Studio\jbr"
RUNNERS = {"pytest", "gradle"}
REQUIRED = ("id", "file", "find", "replace", "runner")
GRADLE_FAILED_TESTS = re.compile(r"There were failing tests|\d+ tests completed, \d+ failed")
GRADLE_BROKEN_BUILD = re.compile(r"Compilation error|e: file:|Execution failed for task ':app:compile")
PYTEST_FAILED = re.compile(r"\b\d+ failed\b")


def lock_dir():
    return Path(os.environ.get("AUDIT_LOCK_DIR") or Path(tempfile.gettempdir()) / "yuldash-audit-locks")


def pid_alive(pid):
    if os.name == "nt":
        handle = ctypes.windll.kernel32.OpenProcess(0x1000, False, int(pid))  # QUERY_LIMITED_INFORMATION
        if not handle:
            return False
        code = ctypes.c_ulong()
        ok = ctypes.windll.kernel32.GetExitCodeProcess(handle, ctypes.byref(code))
        ctypes.windll.kernel32.CloseHandle(handle)
        return bool(ok) and code.value == 259  # STILL_ACTIVE
    try:
        os.kill(int(pid), 0)
    except OSError:
        return False
    return True


def acquire(name, owner, timeout):
    path = lock_dir() / name
    path.parent.mkdir(parents=True, exist_ok=True)
    deadline = time.monotonic() + timeout
    announced = False
    while True:
        try:
            path.mkdir()
            (path / "owner.json").write_text(json.dumps({"pid": os.getpid(), "owner": owner, "since": time.time()}),
                                             encoding="utf-8")
            return path
        except FileExistsError:
            try:
                info = json.loads((path / "owner.json").read_text(encoding="utf-8"))
            except (OSError, ValueError):
                info = None
            age = time.time() - path.stat().st_mtime if path.exists() else 0
            if info and not pid_alive(info.get("pid", 0)) and age > 30:
                print(f"[lock] снимаю брошенный замок {name}: владелец {info.get('owner')} pid {info.get('pid')} не работает",
                      flush=True)
                shutil.rmtree(path, ignore_errors=True)
                continue
            if info is None and age > 120:
                shutil.rmtree(path, ignore_errors=True)
                continue
            if not announced:
                print(f"[lock] жду {name}: занят {info.get('owner') if info else '?'}", flush=True)
                announced = True
            if time.monotonic() > deadline:
                raise SystemExit(f"[lock] не дождался {name} за {timeout} с")
            time.sleep(3)


def release(path):
    shutil.rmtree(path, ignore_errors=True)


PG_BIN = Path(os.environ.get("AUDIT_PG_BIN", r"C:\Program Files\PostgreSQL\16\bin"))
# Isolated throwaway cluster of the file walk (trust auth on loopback only); never a real server.
DEFAULT_PG_BASE = "postgresql://audit_user@127.0.0.1:55450"


class FreshPostgres:
    """Create a throwaway database on the isolated test cluster (AUDIT_PG_BASE) and drop it afterwards."""

    def __init__(self, label):
        base = os.environ.get("AUDIT_PG_BASE") or DEFAULT_PG_BASE
        parsed = urlparse(base)
        self.conn = ["--host", parsed.hostname or "127.0.0.1", "--port", str(parsed.port or 5432),
                     "--username", parsed.username or "postgres", "--no-password"]
        self.name = re.sub(r"[^a-z0-9_]", "_", f"walk_{label}_{os.getpid()}_{int(time.time() * 1000)}".lower())[:60]
        self.url = f"{base.rstrip('/')}/{self.name}"

    def _tool(self, name):
        exe = PG_BIN / (name + (".exe" if os.name == "nt" else ""))
        return str(exe) if exe.exists() else name

    def __enter__(self):
        subprocess.run([self._tool("createdb"), *self.conn, self.name], check=True, capture_output=True)
        return self.url

    def __exit__(self, *exc):
        subprocess.run([self._tool("dropdb"), *self.conn, "--if-exists", self.name], capture_output=True)
        return False


def gradle_command(tests):
    # Explicit ".\\" path: Claude Code sets NoDefaultCurrentDirectoryInExePath=1, so a bare
    # "gradlew.bat" is not found in the working directory on Windows.
    script = ".\\gradlew.bat" if os.name == "nt" else "./gradlew"
    cmd = [script, ":app:testDebugUnitTest", "--console=plain"]
    for t in tests:
        cmd += ["--tests", t]
    return cmd


def run_case(root, mutation, timeout):
    """Run the mutation's test command once; return (exit_code, combined_output)."""
    env = os.environ.copy()
    if mutation["runner"] == "pytest":
        python = os.environ.get("AUDIT_PYTHON") or (str(DEFAULT_PYTHON) if DEFAULT_PYTHON.exists() else sys.executable)
        cwd = root / mutation.get("cwd", "backend")
        if env.get("DATABASE_URL", "").startswith("postgres"):
            env.pop("DATABASE_URL")
        cmd = [python, "-m", "pytest", "-q", "-p", "no:cacheprovider", *mutation["args"]]
        if mutation.get("db") == "postgres":
            with FreshPostgres(mutation["id"]) as url:
                env["DATABASE_URL"] = url
                proc = subprocess.run(cmd, cwd=cwd, env=env, capture_output=True, text=True, encoding="utf-8",
                                      errors="replace", timeout=timeout)
        else:
            proc = subprocess.run(cmd, cwd=cwd, env=env, capture_output=True, text=True, encoding="utf-8",
                                  errors="replace", timeout=timeout)
        return proc.returncode, proc.stdout + proc.stderr
    env.setdefault("JAVA_HOME", DEFAULT_JAVA_HOME)
    held = acquire("gradle", f"mutation {mutation['id']}", timeout)
    try:
        proc = subprocess.run(gradle_command(mutation["tests"]), cwd=root / "android", env=env, capture_output=True,
                              text=True, encoding="utf-8", errors="replace", timeout=timeout, shell=(os.name == "nt"))
    finally:
        release(held)
    return proc.returncode, proc.stdout + proc.stderr


def caught(mutation, code, output):
    if mutation["runner"] == "pytest":
        return code == 1 and bool(PYTEST_FAILED.search(output))
    return code != 0 and bool(GRADLE_FAILED_TESTS.search(output)) and not GRADLE_BROKEN_BUILD.search(output)


def load_spec(path):
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    problems, seen = [], set()
    for m in data.get("mutations", []):
        missing = [k for k in REQUIRED if not m.get(k)]
        if missing:
            problems.append(f"{m.get('id', '?')}: нет полей {missing}")
            continue
        if m["id"] in seen:
            problems.append(f"{m['id']}: повтор id")
        seen.add(m["id"])
        if m["runner"] not in RUNNERS:
            problems.append(f"{m['id']}: неизвестный runner {m['runner']}")
        if m["runner"] == "pytest" and not m.get("args"):
            problems.append(f"{m['id']}: для pytest нужны args")
        if m["runner"] == "gradle" and not m.get("tests"):
            problems.append(f"{m['id']}: для gradle нужен список tests")
        if m["find"] == m["replace"]:
            problems.append(f"{m['id']}: поломка ничего не меняет")
    if not data.get("mutations"):
        problems.append("в спецификации нет ни одной поломки")
    return data, problems


def localized(text, data):
    """Match the snippet to the file's line endings (checkouts may use CRLF)."""
    return text.replace("\r\n", "\n").replace("\n", "\r\n") if b"\r\n" in data else text.replace("\r\n", "\n")


def occurrences(root, m):
    data = (root / m["file"]).read_bytes()
    return data.count(localized(m["find"], data).encode("utf-8")), data


def cmd_validate(root, args):
    data, problems = load_spec(args.spec)
    for m in data.get("mutations", []):
        if all(m.get(k) for k in REQUIRED):
            if not (root / m["file"]).is_file():
                problems.append(f"{m['id']}: нет файла {m['file']}")
                continue
            count, _ = occurrences(root, m)
            if count != 1:
                problems.append(f"{m['id']}: фрагмент find встречается {count} раз(а) в {m['file']}, нужно ровно 1")
    for p in problems:
        print(f"✗ {p}")
    if problems:
        print(f"SPEC INVALID {len(problems)}")
        raise SystemExit(1)
    print(f"SPEC OK {len(data['mutations'])}")


BACKUP_SUFFIX = ".audit-original"
SKIP_DIRS = {".git", "node_modules", "build", ".gradle", ".venv", "venv", "__pycache__", ".unlazy"}


def pending_backups(root):
    """Originals saved next to a mutated file. One left behind means a replay was killed mid-mutation."""
    found = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        found += [Path(dirpath) / f for f in filenames if f.endswith(BACKUP_SUFFIX)]
    return sorted(found)


def recover(root):
    restored = []
    for backup in pending_backups(root):
        target = backup.with_name(backup.name[:-len(BACKUP_SUFFIX)])
        target.write_bytes(backup.read_bytes())
        backup.unlink()
        restored.append(target.relative_to(root).as_posix())
        print(f"RECOVERED {target.relative_to(root).as_posix()} — исходник возвращён после прерванной поломки", flush=True)
    return restored


def cmd_recover(root, args):
    restored = recover(root)
    print(f"RECOVERY DONE {len(restored)}")


def cmd_replay(root, args):
    data, problems = load_spec(args.spec)
    if problems:
        raise SystemExit("спецификация неверна: " + "; ".join(problems))
    recover(root)  # a killed earlier run must never leave its breakage in the source
    selected = data["mutations"]
    if args.only:
        wanted = set(args.only.split(","))
        selected = [m for m in selected if m["id"] in wanted]
        if {m["id"] for m in selected} != wanted:
            raise SystemExit(f"нет таких id: {sorted(wanted - {m['id'] for m in selected})}")
    if args.sample and args.sample < len(selected):
        selected = random.Random(args.seed).sample(selected, args.sample)
    baseline_ok = {}
    killed = 0
    for m in selected:
        key = json.dumps([m["runner"], m.get("cwd"), m.get("args"), m.get("tests"), m.get("db")])
        if key not in baseline_ok:
            code, out = run_case(root, m, args.timeout)
            baseline_ok[key] = code == 0
            if code != 0:
                print(f"{m['id']}: BASELINE FAILED (тест падает и без поломки), exit {code}\n{out[-1500:]}")
        if not baseline_ok[key]:
            continue
        target = root / m["file"]
        count, original = occurrences(root, m)
        if count != 1:
            print(f"{m['id']}: INVALID — фрагмент встречается {count} раз(а)")
            continue
        digest = hashlib.sha256(original).hexdigest()
        mutated = original.replace(localized(m["find"], original).encode("utf-8"),
                                   localized(m["replace"], original).encode("utf-8"), 1)
        backup = target.with_name(target.name + BACKUP_SUFFIX)
        backup.write_bytes(original)  # survives a killed process; recover() puts it back
        try:
            target.write_bytes(mutated)
            code, out = run_case(root, m, args.timeout)
        finally:
            target.write_bytes(original)
        if hashlib.sha256(target.read_bytes()).hexdigest() != digest:
            raise SystemExit(f"{m['id']}: файл {m['file']} не восстановлен — остановка")
        backup.unlink()
        if caught(m, code, out):
            killed += 1
            names = re.findall(r"FAILED (\S+)|(\S+) > .* FAILED", out)
            first = next((a or b for a, b in names), "")
            print(f"{m['id']}: KILLED ({m['file']}) {first}")
        else:
            print(f"{m['id']}: SURVIVED ({m['file']}) exit {code}\n{out[-1500:]}")
    total = len(selected)
    if killed == total and total:
        print(f"MUTATIONS KILLED {killed}/{total}")
    else:
        print(f"MUTATIONS NOT ALL KILLED {killed}/{total}")
        raise SystemExit(1)


def cmd_lock_run(root, args):
    if not args.cmd:
        raise SystemExit("после -- нужна команда")
    held = acquire(args.name, args.owner, args.timeout)
    try:
        proc = subprocess.run(args.cmd, cwd=args.cwd or os.getcwd(), shell=(os.name == "nt"))
    finally:
        release(held)
    raise SystemExit(proc.returncode)


def main():
    for stream in (sys.stdout, sys.stderr):
        stream.reconfigure(encoding="utf-8")  # Russian output must not depend on the console code page
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--root", type=Path, default=ROOT)
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("validate", help="Check spec structure and that every snippet occurs exactly once")
    p.add_argument("--spec", required=True)
    p = sub.add_parser("replay", help="Prove each selected breakage is caught; prints MUTATIONS KILLED k/k")
    p.add_argument("--spec", required=True)
    p.add_argument("--only", help="Comma-separated mutation ids")
    p.add_argument("--sample", type=int, help="Replay only N random mutations (parent spot check)")
    p.add_argument("--seed", type=int, default=0)
    p.add_argument("--timeout", type=int, default=1800)
    sub.add_parser("recover", help="Put back sources left mutated by a killed replay (prints RECOVERY DONE n)")
    p = sub.add_parser("lock-run", help="Run a command while holding a named machine-wide lock")
    p.add_argument("--name", default="gradle")
    p.add_argument("--owner", default="unknown")
    p.add_argument("--timeout", type=int, default=7200)
    p.add_argument("--cwd")
    p.add_argument("cmd", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    if getattr(args, "cmd", None) and args.cmd[0] == "--":
        args.cmd = args.cmd[1:]
    root = args.root.resolve()
    {"validate": cmd_validate, "replay": cmd_replay, "lock-run": cmd_lock_run,
     "recover": cmd_recover}[args.command](root, args)


if __name__ == "__main__":
    main()
