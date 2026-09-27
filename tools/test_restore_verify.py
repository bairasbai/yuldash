"""Real encryption/gzip, stubbed PostgreSQL: no live database is contacted."""
import gzip
import os
from pathlib import Path
import subprocess
import sys

import pytest


SCRIPT = Path(__file__).resolve().parents[1] / "backend/ops/restore-verify.sh"


@pytest.fixture
def drill(tmp_path):
    bindir = tmp_path / "bin"
    bindir.mkdir()
    calls = tmp_path / "calls"
    psql = bindir / "psql"
    psql.write_text('''#!/bin/sh
echo "$*" >> "$DRILL_CALLS"
case "$*" in
  *information_schema*) echo 20 ;;
  *"SELECT count"*) echo 0 ;;
  *"SELECT version_num"*)
    [ "${DRILL_REVISION_ERROR:-0}" = 0 ] || exit 3
    case "$*" in
      *"LIMIT 1"*) printf '%s\\n' "${DRILL_REVISIONS-revision}" | head -n1 ;;
      *) printf '%s\\n' "${DRILL_REVISIONS-revision}" ;;
    esac ;;
  *ON_ERROR_STOP*) cat > "$DRILL_SQL" ;;
esac
''')
    psql.chmod(0o755)
    plain = tmp_path / "backup.sql.gz"
    plain.write_bytes(gzip.compress(b"SELECT 1;\n"))
    versions = tmp_path / "versions"
    versions.mkdir()
    (tmp_path / "alembic.ini").write_text("[alembic]\nscript_location = %(here)s\n")
    (versions / "first.py").write_text(
        "revision = 'revision'\ndown_revision = None\nbranch_labels = ('synthetic',)\ndepends_on = None\n"
    )
    env = {**os.environ, "PATH": f"{bindir}:{os.environ['PATH']}",
           "PSQL_AS": "env", "APP_DIR": str(tmp_path),
           "ALEMBIC_CMD": str(Path(sys.executable).with_name("alembic")),
           "BACKUP_ENV_FILE": str(tmp_path / "missing.env"),
           "DRILL_CALLS": str(calls), "DRILL_SQL": str(tmp_path / "restored.sql"),
           "BACKUP_ENCRYPT_PASSPHRASE": "synthetic-test-secret"}
    encrypted = tmp_path / "backup.sql.gz.enc"
    subprocess.run(["openssl", "enc", "-aes-256-cbc", "-pbkdf2", "-iter", "200000",
                    "-pass", "env:BACKUP_ENCRYPT_PASSPHRASE", "-in", str(plain),
                    "-out", str(encrypted)], env=env, check=True, capture_output=True)
    return tmp_path, plain, encrypted, env


@pytest.mark.parametrize("encrypted", [False, True])
def test_valid_backup_reaches_restore_and_cleanup(drill, encrypted):
    tmp, plain, cipher, env = drill
    result = subprocess.run(["bash", str(SCRIPT), str(cipher if encrypted else plain)],
                            env=env, capture_output=True, text=True)
    assert result.returncode == 0, result.stdout + result.stderr
    assert (tmp / "restored.sql").read_bytes() == b"SELECT 1;\n"
    assert "DROP DATABASE" in (tmp / "calls").read_text()


@pytest.mark.parametrize("failure", [
    "multiple_heads", "empty_graph", "broken_module", "missing_config", "missing_cli", "cli_failure",
])
def test_migration_graph_cannot_be_silently_skipped(drill, failure):
    tmp, plain, _, env = drill
    if failure == "multiple_heads":
        (tmp / "versions/second.py").write_text(
            "revision = 'second'\ndown_revision = None\nbranch_labels = None\ndepends_on = None\n"
        )
    elif failure == "empty_graph":
        (tmp / "versions/first.py").unlink()
    elif failure == "broken_module":
        (tmp / "versions/first.py").write_text("import nonexistent_restore_probe_dependency\n")
    elif failure == "missing_config":
        (tmp / "alembic.ini").unlink()
    elif failure == "missing_cli":
        env["ALEMBIC_CMD"] = str(tmp / "missing-alembic")
    else:
        env["ALEMBIC_CMD"] = "/bin/false"
    result = subprocess.run(["bash", str(SCRIPT), str(plain)], env=env, capture_output=True, text=True)
    assert result.returncode != 0, result.stdout + result.stderr
    assert "[verify] OK:" not in result.stdout
    assert "совместимость схемы" in result.stderr
    assert "DROP DATABASE" in (tmp / "calls").read_text()


@pytest.mark.parametrize("failure", ["multiple_versions", "missing_version", "query_failure", "old_revision"])
def test_restored_revision_must_be_single_and_match_current_code(drill, failure):
    tmp, plain, _, env = drill
    if failure == "multiple_versions":
        env["DRILL_REVISIONS"] = "revision\nsecond"
    elif failure == "missing_version":
        env["DRILL_REVISIONS"] = ""
    elif failure == "query_failure":
        env["DRILL_REVISION_ERROR"] = "1"
    else:
        env["DRILL_REVISIONS"] = "historical_revision"
    result = subprocess.run(["bash", str(SCRIPT), str(plain)], env=env, capture_output=True, text=True)
    assert result.returncode != 0, result.stdout + result.stderr
    assert "[verify] OK:" not in result.stdout
    assert "совместимость схемы" in result.stderr
    assert "негоден" not in result.stderr
    assert "DROP DATABASE" in (tmp / "calls").read_text()


@pytest.mark.parametrize("failure", ["missing_key", "wrong_key", "corrupt_plain"])
def test_invalid_backup_fails_before_creating_database(drill, failure):
    tmp, plain, cipher, env = drill
    target = cipher
    if failure == "missing_key":
        env.pop("BACKUP_ENCRYPT_PASSPHRASE")
    elif failure == "wrong_key":
        env["BACKUP_ENCRYPT_PASSPHRASE"] = "wrong-synthetic-secret"
    else:
        plain.write_bytes(b"not gzip")
        target = plain
    result = subprocess.run(["bash", str(SCRIPT), str(target)], env=env,
                            capture_output=True, text=True)
    assert result.returncode != 0
    assert not (tmp / "calls").exists()


def test_key_from_backup_env_file_is_exported_for_openssl(drill):
    tmp, _, cipher, env = drill
    env.pop("BACKUP_ENCRYPT_PASSPHRASE")
    config = tmp / "backup.env"
    config.write_text("BACKUP_ENCRYPT_PASSPHRASE='synthetic-test-secret'\n")
    env["BACKUP_ENV_FILE"] = str(config)
    result = subprocess.run(["bash", str(SCRIPT), str(cipher)], env=env,
                            capture_output=True, text=True)
    assert result.returncode == 0, result.stdout + result.stderr
    assert (tmp / "restored.sql").read_bytes() == b"SELECT 1;\n"
