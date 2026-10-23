"""Исходные и проектные проверки; ограничения ОС показываются как SKIP."""
import os
from pathlib import Path
import sys
import tempfile
import unittest

os.environ["PYTHONDONTWRITEBYTECODE"] = "1"
sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent


def main():
    suite = unittest.TestLoader().discover(str(HERE / "vendor/tests"), top_level_dir=str(HERE / "vendor"))
    if os.name == "nt":
        # Preserve upstream bytes; skip ONLY the two tests requiring an unavailable OS privilege.
        try:
            with tempfile.TemporaryDirectory(prefix="traceability-symlink-probe-") as directory:
                root = Path(directory)
                (root / "target.txt").write_text("synthetic", encoding="utf-8")
                (root / "link.txt").symlink_to(root / "target.txt")
        except OSError as exc:
            if exc.winerror != 1314:
                raise
            from tests.test_validator import AcceptanceGateTests
            for name in ("test_symlink_escape_is_rejected", "test_symlink_loop_is_a_reported_blocker"):
                setattr(AcceptanceGateTests, name, unittest.skip(
                    "Windows: нет права создавать symlink (1314); полный тест выполняется на Linux CI"
                )(getattr(AcceptanceGateTests, name)))
    suite.addTests(unittest.TestLoader().discover(str(HERE / "tests")))
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    return 0 if result.wasSuccessful() else 1


if __name__ == "__main__":
    sys.exit(main())
