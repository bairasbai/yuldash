"""Executed by run-isolated.sh before importing any pytest/application code."""
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

tools = Path(__file__).resolve().parent
sys.path.insert(0, str(tools))
from guards import require_isolation, worker_environment  # noqa: E402


def run():
    require_isolation()
    if len(sys.argv) != 3:
        raise RuntimeError
    python, browser = (Path(v) for v in sys.argv[1:])
    if not all(p.is_absolute() and p.is_file() and os.access(p, os.X_OK) for p in (python, browser)):
        raise RuntimeError
    root = tools.parents[1]
    backend = root / "backend"
    if (backend / ".env").exists() or (backend / ".env").is_symlink():
        raise RuntimeError("unexpected dotenv")
    if not (root / "webapp/tf09-qa-dist/tf09-qa.html").is_file():
        raise RuntimeError
    with tempfile.TemporaryDirectory(prefix="tf09-browser-") as directory:
        private = Path(directory)
        capture = private / "result"
        with capture.open("wb") as log:
            result = subprocess.run(
                [str(python), "-B", "-m", "pytest", "-q", "--disable-warnings", "-p", "no:cacheprovider",
                 "tests/test_tf09_pwa_browser.py"],
                cwd=backend, env=worker_environment(private, backend, tools, browser),
                stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT, timeout=160, check=False,
            )
        out = capture.read_text(errors="replace")
        return result.returncode == 0 and bool(re.search(r"(?m)^1 passed(?:, \d+ warnings?)? in \d+(?:\.\d+)?s$", out))


if __name__ == "__main__":
    os.umask(0o077)
    try:
        passed = run()
    except (OSError, RuntimeError, ValueError, subprocess.TimeoutExpired):
        passed = False
    print("TF09 PWA browser: PASS" if passed else "TF09 PWA browser: HOLD")
    sys.exit(0 if passed else 1)
