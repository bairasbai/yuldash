"""Run only in the exact, separately reviewed hosted PostgreSQL lane."""
import os
from pathlib import Path
import stat
import subprocess
import sys
import tempfile

OLD = "f8410ad7a538be4fdd64c65e29a2af8e932501d2"
PRODUCT = "fb96e82a06767ee80e6a38a9d580499b18d7f8b4"


def run(objects, guard, witness):
    # Import an immutable verified helper, not any module from the app checkout.
    sys.path.insert(0, str(guard))
    from verify import PG_URL, materialize, service_provenance

    socket_id = service_provenance()
    work = Path(os.environ["RUNNER_TEMP"])
    info = work.lstat()
    assert work.resolve(strict=True) == work and stat.S_ISDIR(info.st_mode) and info.st_uid == os.getuid()
    with tempfile.TemporaryDirectory(dir=work, prefix="disposable-") as directory:
        root = Path(directory)
        for name, sha in (("old", OLD), ("candidate", PRODUCT)):
            materialize(objects, sha, root / name)
            dotenv = root / name / "backend/.env"
            assert not dotenv.exists() and not dotenv.is_symlink()
        for name in ("home", "tmp"):
            (root / name).mkdir(mode=0o700)
        denied = root / "denied"
        env = {
            "HOME": str(root / "home"), "TMPDIR": str(root / "tmp"), "PATH": "/usr/bin:/bin",
            "ENV": "dev", "SEED_DEMO": "false", "GITHUB_ACTIONS": "true", "RUNNER_OS": "Linux",
            "RUNNER_ARCH": "X64", "QA_DB": "postgresql", "QA_DENIED": str(denied),
            "QA_SOCKET_ID": socket_id, "DATABASE_URL": PG_URL, "PYTHONDONTWRITEBYTECODE": "1",
            "SMS_PROVIDER": "mock", "PAYMENTS_PROVIDER": "mock", "REDIS_URL": "",
            "TAXI_ENABLED": "true", "TAXI_WORKER_ENABLED": "false", "DAILY_DIGEST_ENABLED": "false",
            "RATE_LIMIT_ENABLED": "false", "TAXI_STOP_CONTROL_ENABLED": "false",
            "TAXI_EXTERNAL_PRICING_ENABLED": "false", "TAXI_INITIAL_CONSENT_ADMISSION_ENABLED": "false",
            "JWT_SECRET": "synthetic-tf09-populated-no-service-signing-key",
        }

        def phase(label, tree, *args, negative=False):
            cwd = root / tree / "backend"
            result = subprocess.run(
                (sys.executable, "-B", *map(str, args)), cwd=cwd,
                env=env | {"PYTHONPATH": os.pathsep.join((str(guard), str(cwd)))},
                stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                timeout=100, check=False,
            )
            assert result.returncode == 0
            if negative:
                assert not denied.is_symlink()
                assert denied.read_text().splitlines() == ["socket.connect", "libpq.unapproved_dsn"]
                denied.unlink()
            assert not denied.exists() and not denied.is_symlink()
            print(f"TF09 populated PG: {label}=pass", flush=True)

        phase("negative-controls", "old", guard / "verify.py", "negative", negative=True)
        phase("empty-service", "old", guard / "verify.py", "audit-empty")
        phase("old-schema", "old", "-m", "alembic", "upgrade", "dc_merge_taxi_consent_receipts")
        phase("old-seed", "old", witness, "seed-old")
        phase("candidate-migration", "candidate", "-m", "alembic", "upgrade", "dd_taxi_stop_control")
        phase("candidate-witness", "candidate", witness, "check-upgraded")


if __name__ == "__main__":
    os.umask(0o077)
    try:
        assert not sys.flags.optimize and len(sys.argv) == 4
        run(*(Path(arg).resolve(strict=True) for arg in sys.argv[1:]))
    except Exception:
        raise SystemExit("TF09 populated PG: guarded witness failed") from None
