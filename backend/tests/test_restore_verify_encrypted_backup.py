"""Run the real restore script with real gzip/OpenSSL and isolated SQL command doubles.

No application/conftest import, database connection, env-file read, or external service.
This verifies shell control flow and plaintext delivery, not PostgreSQL restoration.
"""
from __future__ import annotations

import gzip
import os
import re
import shlex
import shutil
import subprocess
from pathlib import Path

import pytest

SCRIPT = Path(__file__).resolve().parents[1] / "ops" / "restore-verify.sh"
SQL = b'-- synthetic restore drill\nCREATE TABLE "audit_example" (id integer);\n'
TEST_KEY = "synthetic-local-restore-key"


def _shell_path(path: Path) -> str:
    value = path.resolve().as_posix()
    if os.name == "nt" and re.match(r"^[A-Za-z]:/", value):
        return f"/{value[0].lower()}{value[2:]}"
    return value


def _write_script(path: Path, source: str) -> None:
    path.write_text(source, encoding="utf-8", newline="\n")
    path.chmod(0o755)


@pytest.fixture(scope="module")
def shell_tools():
    git_bash = Path(os.environ.get("ProgramFiles", "C:/Program Files")) / "Git/bin/bash.exe"
    bash = str(git_bash) if os.name == "nt" and git_bash.is_file() else shutil.which("bash")
    if not bash:
        pytest.skip("restore shell regression requires an existing Bash installation")
    # Exclude inherited application/provider settings and arbitrary executables.
    env = {key: os.environ[key] for key in ("SYSTEMROOT", "WINDIR", "COMSPEC", "TEMP", "TMP")
           if key in os.environ}
    env["PATH"] = "/mingw64/bin:/usr/bin:/bin" if os.name == "nt" else "/usr/bin:/bin:/usr/local/bin"
    probe = subprocess.run(
        [bash, "--noprofile", "--norc", "-c", "command -v openssl && command -v gzip && command -v gunzip"],
        env=env, capture_output=True, timeout=15, check=False,
    )
    if probe.returncode:
        pytest.skip("restore shell regression requires existing OpenSSL, gzip and gunzip")
    return bash, env


@pytest.fixture
def drill(tmp_path, shell_tools):
    bash, base_env = shell_tools
    commands = tmp_path / "commands"
    commands.mkdir()
    events = tmp_path / "sql-events.tsv"
    received = tmp_path / "received.sql"
    state = tmp_path / "temporary-database.txt"
    app = tmp_path / "synthetic-app"
    app.mkdir()
    (app / "alembic.ini").write_text("# synthetic config only\n", encoding="utf-8")
    _write_script(commands / "sudo", """#!/usr/bin/env bash
[[ "$1" == '-u' && "$2" == 'postgres' && "$3" == 'psql' ]] || exit 91
shift 2
exec "$@"
""")
    _write_script(commands / "psql", r'''#!/usr/bin/env bash
database='' query=''
while [ "$#" -gt 0 ]; do
  case "$1" in
    -d) database="$2"; shift 2 ;;
    -c) query="$2"; shift 2 ;;
    *) shift ;;
  esac
done
printf '%s\t%s\n' "$database" "$query" >> "$DRILL_EVENTS"
case "$query" in
  'CREATE DATABASE '* )
    [[ "$database" == postgres && "$query" == 'CREATE DATABASE "verify_qa_restore_'* ]] || exit 92
    printf '%s' "$query" > "$DRILL_DB_STATE"
    ;;
  'DROP DATABASE IF EXISTS '* )
    [[ "$database" == postgres && "$query" == 'DROP DATABASE IF EXISTS "verify_qa_restore_'* ]] || exit 93
    rm -f "$DRILL_DB_STATE"
    ;;
  *)
    [[ "$database" == verify_qa_restore_* ]] || exit 94
    case "$query" in
      '') cat > "$DRILL_RECEIVED"; [ "${DRILL_FAIL_RESTORE:-0}" != 1 ] || exit 17 ;;
      *information_schema.tables*) printf '20\n' ;;
      'SELECT count(*) FROM '* ) printf '1\n' ;;
      *alembic_version*) printf 'synthetic_head\n' ;;
      *) exit 95 ;;
    esac
    ;;
esac
''')
    _write_script(commands / "alembic", """#!/usr/bin/env bash
[[ "$#" == 1 && "$1" == heads ]] || exit 96
printf 'synthetic_head (head)\\n'
""")
    env = {**base_env, "PATH": f"{_shell_path(commands)}:{base_env['PATH']}",
           "BACKUP_ENV_FILE": _shell_path(tmp_path / "never-present-backup.env"),
           "BACKUP_DIR": _shell_path(tmp_path), "DB_NAME": "qa_restore",
           "APP_DIR": _shell_path(app), "PSQL_AS": "sudo -u postgres",
           "ALEMBIC_CMD": _shell_path(commands / "alembic"),
           "DRILL_EVENTS": _shell_path(events), "DRILL_RECEIVED": _shell_path(received),
           "DRILL_DB_STATE": _shell_path(state), "BACKUP_ENCRYPT_PASSPHRASE": TEST_KEY}

    def encrypt(payload: bytes) -> Path:
        archive = tmp_path / "synthetic.sql.gz.enc"
        result = subprocess.run(
            [bash, "--noprofile", "--norc", "-c",
             ("openssl enc -aes-256-cbc -pbkdf2 -iter 200000 -salt "
              f"-pass env:BACKUP_ENCRYPT_PASSPHRASE -out {shlex.quote(_shell_path(archive))}")],
            input=payload, env=env, capture_output=True, timeout=15, check=False,
        )
        assert result.returncode == 0, result.stderr.decode("utf-8", errors="replace")
        return archive

    def run(archive: Path, **overrides):
        before = archive.read_bytes() if archive.exists() else None
        result = subprocess.run(
            [bash, "--noprofile", "--norc", _shell_path(SCRIPT), _shell_path(archive)],
            env={**env, **overrides}, capture_output=True, encoding="utf-8", errors="replace", timeout=15, check=False,
        )
        assert not state.exists(), "temporary database was not cleaned up"
        assert (archive.read_bytes() if archive.exists() else None) == before, "input backup changed"
        rows = events.read_text(encoding="utf-8").splitlines() if events.exists() else []
        creates = [row.split('\t', 1)[1] for row in rows if '\tCREATE DATABASE ' in row]
        drops = [row.split('\t', 1)[1] for row in rows if '\tDROP DATABASE IF EXISTS ' in row]
        assert len(creates) == len(drops), (creates, drops)
        if creates:
            assert len(creates) == 1
            assert creates[0].removeprefix('CREATE DATABASE ') == drops[0].removeprefix('DROP DATABASE IF EXISTS ')
        print(f"restore exit={result.returncode}, temporary databases={len(creates)}, cleaned={len(drops)}")
        print(result.stdout)
        print(result.stderr)
        return result, rows

    return tmp_path, encrypt, run, received


@pytest.mark.parametrize("encrypted", [False, True], ids=["gzip", "encrypted"])
def test_valid_backup_delivers_exact_sql_and_cleans_up(drill, encrypted):
    tmp_path, encrypt, run, received = drill
    if encrypted:
        archive = encrypt(gzip.compress(SQL))
    else:
        archive = tmp_path / "synthetic.sql.gz"
        archive.write_bytes(gzip.compress(SQL))
    result, rows = run(archive)
    assert result.returncode == 0, result.stdout + result.stderr
    assert received.read_bytes() == SQL
    assert "[verify] OK:" in result.stdout
    assert "synthetic_head = голова" in result.stdout
    assert any('SELECT count(*) FROM information_schema.tables' in row for row in rows)
    for table in ("user", "ride", "booking"):
        assert any(f'SELECT count(*) FROM "{table}";' in row for row in rows)


def test_corrupt_gzip_stops_before_database_creation(drill):
    tmp_path, _, run, received = drill
    archive = tmp_path / "corrupt.sql.gz"
    archive.write_bytes(gzip.compress(SQL)[:-5])
    result, rows = run(archive)
    assert result.returncode != 0
    assert "битый gzip-архив" in result.stderr
    assert "[verify] OK:" not in result.stdout
    assert not rows
    assert not received.exists()


@pytest.mark.parametrize("fault", ["wrong-key", "truncated-ciphertext", "not-gzip"])
def test_encrypted_decode_failure_cannot_pass_through_successful_psql(drill, fault):
    _, encrypt, run, _ = drill
    archive = encrypt(b"not a gzip stream" if fault == "not-gzip" else gzip.compress(SQL))
    overrides = {}
    if fault == "wrong-key":
        overrides["BACKUP_ENCRYPT_PASSPHRASE"] = "synthetic-wrong-key"
    elif fault == "truncated-ciphertext":
        archive.write_bytes(archive.read_bytes()[:-7])
    result, rows = run(archive, **overrides)
    assert result.returncode != 0
    assert "не удалось расшифровать или применить дамп" in result.stderr
    assert "[verify] OK:" not in result.stdout
    # psql consumes stdin and succeeds even if it is empty: pipefail must still reject.
    assert any(row.endswith('\t') for row in rows)
    assert not any('SELECT count(*)' in row for row in rows)


def test_missing_encryption_key_fails_and_cleans_up(drill):
    _, encrypt, run, received = drill
    result, rows = run(encrypt(gzip.compress(SQL)), BACKUP_ENCRYPT_PASSPHRASE="")
    assert result.returncode != 0
    assert "BACKUP_ENCRYPT_PASSPHRASE не задан" in result.stderr
    assert "[verify] OK:" not in result.stdout
    assert any('\tCREATE DATABASE ' in row for row in rows)
    assert not received.exists()


@pytest.mark.parametrize("encrypted", [False, True], ids=["gzip", "encrypted"])
def test_restore_command_failure_stops_integrity_checks_and_cleans_up(drill, encrypted):
    tmp_path, encrypt, run, received = drill
    if encrypted:
        archive = encrypt(gzip.compress(SQL))
    else:
        archive = tmp_path / "synthetic.sql.gz"
        archive.write_bytes(gzip.compress(SQL))
    result, rows = run(archive, DRILL_FAIL_RESTORE="1")
    assert result.returncode != 0
    assert received.read_bytes() == SQL
    assert "[verify] OK:" not in result.stdout
    assert not any('SELECT count(*)' in row for row in rows)
