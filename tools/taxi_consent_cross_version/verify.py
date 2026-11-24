"""Pinned old/app/new offline witness; PostgreSQL requires the dedicated CI service."""
from contextlib import closing
from datetime import datetime
import json
import os
import re
import stat
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import tarfile

OLD, NEW = "db_chat_message_receipt", "dc_merge_taxi_consent_receipts"
COLUMNS = ("consent_request_key", "consent_request_digest", "consent_quote_id", "consent_snapshot_json")
HERE = Path(__file__).resolve().parent
OLD_SHA = "662eed80295476e31c4c4d045b5ed1f2384c60dd"
NEW_SHA = "f8410ad7a538be4fdd64c65e29a2af8e932501d2"
PG_IMAGE = "postgres@sha256:72d22ce26d04fe56a5384e9f6b026152d1cf05db602a7a3524a3ed337d5f5752"
PG_SOCKET = Path("/tmp/yuldash-taxi-cross-version-pg")
PG_ROLE_DB = "taxi_cross_version_qa"
PG_OPTIONS = dict(dbname=PG_ROLE_DB, user=PG_ROLE_DB, host=str(PG_SOCKET), port="5432", connect_timeout="5")
SOURCES = ("backend/app/", "backend/alembic/", "backend/alembic.ini", "backend/requirements.txt", "backend/requirements-dev.txt")
PG_URL = f"postgresql+psycopg2://{PG_ROLE_DB}@/{PG_ROLE_DB}?host={PG_SOCKET}&port=5432&connect_timeout=5"
KEY = "10000000-0000-4000-8000-000000000001"
QUOTE = "10000000-0000-4000-8000-000000000002"
LEGACY_TAG, NEW_TAG = "synthetic legacy order", "synthetic committed order"
CHAT = "synthetic retained chat"
BODY = {
    "from_lat": 54.7, "from_lng": 55.8, "to_lat": 54.71, "to_lng": 55.81,
    "from_text": "SYNTHETIC A", "to_text": "SYNTHETIC B", "request_key": KEY, "quote_id": QUOTE,
}
RESULT = "/instant/orders/result/v1"
CONFIRM = "/instant/orders/confirmed/v1"
CLOSE = "/instant/orders/close-attempt/v1"


class Observer:
    """Read-only snapshot observer separate from app SQLModel/adapter/Pools."""
    def __init__(self, raw, postgres):
        self.raw = raw
        self.postgres = postgres

    def close(self):
        self.raw.close()

    def execute(self, sql, params=()):
        if not self.postgres:
            return self.raw.execute(sql, params)
        with self.raw.cursor() as cursor:
            cursor.execute(sql.replace("?", "%s"), params)
            return _Rows(cursor.fetchall())


class _Rows:
    def __init__(self, rows):
        self.rows = rows

    def fetchall(self):
        return self.rows

    def fetchone(self):
        return self.rows[0] if self.rows else None


def audit(initial=False):
    assert os.environ.get("QA_GUARD_INSTALLED") == "1"
    mode = os.environ["QA_DB"]
    root = Path(os.environ["HOME"]).parent
    assert root.resolve() == root and root.name.startswith("disposable-")
    if mode == "sqlite":
        db = root / "case.sqlite3"
        assert db.is_file() and not db.is_symlink() and os.environ["DATABASE_URL"] == f"sqlite:///{db}"
        connection = sqlite3.connect(db)
        assert connection.execute("PRAGMA integrity_check").fetchall() == [("ok",)]
        assert connection.execute("PRAGMA foreign_key_check").fetchall() == []
        return Observer(connection, False)
    assert mode == "postgresql" and os.environ["DATABASE_URL"] == PG_URL
    import psycopg2

    connection = psycopg2.connect(**PG_OPTIONS)
    connection.autocommit = True
    con = Observer(connection, True)
    assert con.execute(
        "SELECT current_database(), session_user, current_schema(), inet_server_addr() IS NULL, "
        "inet_client_addr() IS NULL, current_setting('server_version_num')::int / 10000"
    ).fetchone() == (PG_ROLE_DB, PG_ROLE_DB, "public", True, True, 16)
    # Dedicated fixture DB: refuse before Alembic or application boot if already populated.
    if initial:
        assert con.execute(
            "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace "
            "WHERE n.nspname='public'"
        ).fetchone() == (0,)
    return con


def columns(con):
    sql = (
        "SELECT column_name FROM information_schema.columns "
        "WHERE table_schema='public' AND table_name='instantorder'"
    )
    if con.postgres:
        return {r[0] for r in con.execute(sql).fetchall()}
    return {r[1] for r in con.execute("PRAGMA table_info(instantorder)").fetchall()}


def attempt_exists(con):
    sql = (
        "SELECT 1 FROM information_schema.tables WHERE table_schema='public' AND table_name='taxiconsentattempt'"
        if con.postgres else "SELECT 1 FROM sqlite_master WHERE name='taxiconsentattempt'"
    )
    return bool(con.execute(sql).fetchall())


def materialize(git_root, ref, dest):
    def git(*args):
        return subprocess.run(
            ["git", "-C", str(git_root), *args], check=True, capture_output=True, timeout=60,
            env={"PATH": "/usr/bin:/bin", "HOME": str(dest), "GIT_CONFIG_NOSYSTEM": "1", "GIT_NO_LAZY_FETCH": "1", "GIT_NO_REPLACE_OBJECTS": "1"},
        ).stdout
    dest.mkdir()
    # Only local objects pinned by commit; no network or existing worktree refs.
    assert git("rev-parse", "--verify", f"{ref}^{{commit}}").decode().strip() == ref
    objects = git("ls-tree", "-rz", ref, "--", *SOURCES).split(b"\0")[:-1]
    expected = {}
    for entry in objects:
        meta, name = entry.decode().split("\t", 1)
        mode, kind, object_id = meta.split()
        assert mode in {"100644", "100755"} and kind == "blob"
        expected[name] = object_id
    assert expected and "backend/.env" not in expected
    payload = git("archive", ref, "--", *SOURCES)
    from io import BytesIO

    with tarfile.open(fileobj=BytesIO(payload)) as archive:
        assert all(member.isfile() or member.isdir() for member in archive.getmembers())
        archive.extractall(dest, filter="data")
    found = {(f.relative_to(dest).as_posix()): f for f in dest.rglob("*") if f.is_file()}
    assert found.keys() == expected.keys()
    from hashlib import sha1

    for name, f in found.items():
        blob = f.read_bytes()
        assert sha1(f"blob {len(blob)}\0".encode() + blob).hexdigest() == expected[name]

def rev(con):
    return con.execute("SELECT version_num FROM alembic_version").fetchall()


def stored(con):
    sql = ", ".join(COLUMNS)
    return con.execute(f"SELECT id, passenger_id, {sql} FROM instantorder WHERE consent_request_key=?", (KEY,)).fetchall()


def receipt_projection(con):
    attempts = con.execute(
        "SELECT owner_id,request_key,quote_id,expires_at,state FROM taxiconsentattempt ORDER BY owner_id,request_key"
    ).fetchall()
    def project(rows):
        return [[v.isoformat() if isinstance(v, datetime) else v for v in row] for row in rows]

    return {"orders": project(stored(con)), "attempts": project(attempts)}


def phase(name):
    version = {"seed-legacy": "old", "seed-receipt": "next", "old-app": "old", "new-drain": "next"}[name]
    assert Path.cwd() == Path(os.environ["HOME"]).parent / version / "backend"
    # Refuse an unknown DSN/workspace before importing any application module.
    guarded_database = audit()
    # No application lifespan, migration-on-startup, workers, Redis, or provider wiring.
    from fastapi import FastAPI
    from fastapi.testclient import TestClient
    from sqlmodel import Session
    from app.config import settings
    from app.db import engine
    from app.models import InstantOrder, InstantOrderStatus, Message, User
    from app.routers import instant
    from app.security import make_token

    api = FastAPI()
    api.include_router(instant.router)
    expected_driver = "postgresql+psycopg2" if guarded_database.postgres else "sqlite"
    assert engine.url.drivername == expected_driver and not settings.taxi_enabled
    def auth(user):
        return {"Authorization": "Bearer " + make_token(user)}

    with closing(guarded_database) as con, Session(engine) as db:
        idsfile = Path(os.environ["HOME"]).parent / "ids.json"
        if name == "seed-legacy":
            assert rev(con) == [(OLD,)]
            assert not set(COLUMNS) & columns(con)
            assert not attempt_exists(con)
            one, other = User(phone="+79990006301"), User(phone="+79990006302")
            db.add_all([one, other]); db.flush()
            row = InstantOrder(passenger_id=one.id, status=InstantOrderStatus.done, comment=LEGACY_TAG)
            db.add(row); db.flush()
            chat = Message(sender_id=one.id, order_id=row.id, text=CHAT)
            db.add(chat); db.flush()
            idsfile.write_text(json.dumps(dict(one=one.id, other=other.id, legacy=row.id, chat=chat.id)))
            db.commit()
            print("old head: legacy order and referencing chat committed")
            return

        assert rev(con) == [(NEW,)]
        ids = json.loads(idsfile.read_bytes())
        assert con.execute("SELECT order_id, text FROM message WHERE id=?", (ids["chat"],)).fetchall() == [(ids["legacy"], CHAT)]
        assert db.get(InstantOrder, ids["legacy"]).comment == LEGACY_TAG
        if name == "seed-receipt":
            from datetime import timedelta
            from app import taxi_quote_consent as consent
            from app.timeutil import utcnow
            assert not settings.taxi_initial_consent_admission_enabled
            assert con.execute("SELECT " + ", ".join(COLUMNS) + " FROM instantorder WHERE id=?", (ids["legacy"],)).fetchall() == [(None,)*4]
            expires = utcnow() + timedelta(minutes=10)
            snapshot = consent.public_snapshot(dict(
                request_key=KEY, quote_id=QUOTE,
                snapshot_digest="a" * 64, pricing_inputs_digest="b" * 64, expires_at=expires.isoformat(),
                estimate=dict(price=500, ride_price=500, distance_km=7.0, eta_min=15, round_trip=False),
                promo=dict(discount_kop=0),
            ))
            row = InstantOrder(passenger_id=ids["one"], status=InstantOrderStatus.done, comment=NEW_TAG,
                               price_final=500, ride_price=500,
                               consent_request_key=KEY, consent_request_digest=consent.request_digest(instant.ConfirmedOrderIn(**BODY)),
                               consent_quote_id=QUOTE, consent_snapshot_json=consent.canonical(dict(quote=snapshot, accepted=snapshot)))
            # An already committed receipt is deliberately seeded using candidate ORM; no fake dispatch.
            db.add(row); db.flush()
            db.add(consent.TaxiConsentAttempt(owner_id=ids["one"], request_key=KEY, quote_id=QUOTE,
                                             expires_at=expires, state="committed"))
            ids["committed"] = row.id
            idsfile.write_text(json.dumps(ids))
            db.commit()
            (idsfile.parent / "receipt.json").write_text(json.dumps(receipt_projection(con)))
            print("new head: legacy references preserved, committed synthetic consent written")
            return

        frozen = json.loads((idsfile.parent / "receipt.json").read_bytes())
        assert len(frozen["orders"]) == len(frozen["attempts"]) == 1
        assert receipt_projection(con) == frozen
        if name == "old-app":
            # A binary rollback keeps the newer migration head; never invoke Alembic downgrade.
            assert not any(c in InstantOrder.model_fields for c in COLUMNS)
            with TestClient(api) as http:
                result = http.get(f"/instant/orders/{ids['committed']}", headers=auth(ids["one"]))
                assert (result.status_code, result.json()["id"]) == (200, ids["committed"])
                assert not set(COLUMNS) & set(result.json()) and KEY not in result.text
                assert http.get(f"/instant/orders/{ids['committed']}", headers=auth(ids["other"])).status_code == 403
                absent = http.post(RESULT, headers=auth(ids["one"]), json={"request_key": KEY})
                assert absent.status_code in (404, 405) and KEY not in absent.text
            # Actual old model, independent DB unit of work. A new non-consent legacy row
            # must insert without knowing the additive columns; metadata on old rows is untouched.
            row = InstantOrder(passenger_id=ids["other"], status=InstantOrderStatus.done, comment="old app write")
            db.add(row)
            fetched = db.get(InstantOrder, ids["committed"])
            assert fetched.comment == NEW_TAG
            fetched.comment = "old model updates unrelated field"
            db.flush()
            legacy_id = row.id
            db.commit()
            assert con.execute("SELECT consent_request_key FROM instantorder WHERE id=?", (legacy_id,)).fetchall() == [(None,)]
            assert receipt_projection(con) == frozen
            print("old app/new schema: authorized legacy GET and old ORM INSERT pass; recovery endpoint absent")
            return

        assert name == "new-drain" and not settings.taxi_initial_consent_admission_enabled
        assert db.get(InstantOrder, ids["committed"]).comment == "old model updates unrelated field"
        with TestClient(api) as http:
            result = http.post(RESULT, headers=auth(ids["one"]), json={"request_key": KEY})
            assert result.status_code == 200 and result.headers["cache-control"] == "no-store"
            assert result.json() == dict(schema="taxi-consent-result-v1", state="committed", order_id=ids["committed"],
                                         initial_payable_kop=50000, next="fetch_order")
            foreign = http.post(RESULT, headers=auth(ids["other"]), json={"request_key": KEY})
            assert foreign.status_code == 200 and foreign.json() == dict(schema="taxi-consent-result-v1", state="unknown")
            replay = http.post(CONFIRM, headers=auth(ids["one"]), json=BODY)
            assert replay.status_code == 200 and replay.headers["cache-control"] == "no-store"
            assert replay.json()["order"]["id"] == ids["committed"]
            assert replay.json()["consent"]["accepted"]["payable_kop"] == 50000
            closed = http.post(CLOSE, headers=auth(ids["one"]), json={"request_key": KEY})
            assert closed.status_code == 200 and closed.headers["cache-control"] == "no-store"
            assert closed.json() == result.json()
            denied = http.post(CONFIRM, headers=auth(ids["other"]), json=BODY)
            assert denied.status_code == 503 and denied.json()["detail"]["code"] == "taxi_consent_admission_disabled"
        assert receipt_projection(con) == frozen
        assert con.execute("SELECT state FROM taxiconsentattempt").fetchall() == [("committed",)]
        assert con.execute("SELECT count(*) FROM instantorder").fetchone() == (3,)
        print("new app/default off: exact owner replay/read/close preserved, foreign lookup unknown; no new admission")


PHASES = (
    ("old-migration", "old", ("-m", "alembic", "upgrade", OLD)),
    ("legacy-write", "old", (__file__, "seed-legacy")),
    ("populated-upgrade", "next", ("-m", "alembic", "upgrade", NEW)),
    ("receipt-seed", "next", (__file__, "seed-receipt")),
    ("old-binary", "old", (__file__, "old-app")),
    ("new-drain", "next", (__file__, "new-drain")),
)


def run_matrix(root, mode, socket_identity=None):
    for p in ("temp", "home"):
        (root / p).mkdir()
    dsn = PG_URL if mode == "postgresql" else f"sqlite:///{root / 'case.sqlite3'}"
    env = {
        "HOME": str(root / "home"), "TMPDIR": str(root / "temp"), "PATH": "/usr/bin:/bin",
        "PYTHONDONTWRITEBYTECODE": "1", "ENV": "dev", "SEED_DEMO": "false",
        "RATE_LIMIT_ENABLED": "false", "DATABASE_URL": dsn,
        "SMS_PROVIDER": "mock", "PAYMENTS_PROVIDER": "mock", "REDIS_URL": "",
        "TAXI_ENABLED": "false", "DAILY_DIGEST_ENABLED": "false",
        "JWT_SECRET": "synthetic-cross-version-test-signing-key-not-for-use",
        "QA_DENIED": str(root / "denied"), "QA_DB": mode,
    }
    if mode == "postgresql":
        assert socket_identity
        env["QA_SOCKET_ID"] = socket_identity

    def run(label, version, *args):
        cwd = root / version / "backend"
        guarded = env | {"PYTHONPATH": os.pathsep.join(map(str, (HERE, cwd)))}
        try:
            result = subprocess.run(
                (sys.executable, *args), cwd=cwd, env=guarded, timeout=90,
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            )
        except subprocess.TimeoutExpired:
            raise SystemExit(f"{label}:timeout") from None
        if result.returncode:
            raise SystemExit(f"{label}:exit:{result.returncode}")

    run("guard-negative", "old", __file__, "negative")
    denied = root / "denied"
    assert denied.read_text().splitlines() == ["socket.connect", "libpq.unapproved_dsn"]
    denied.unlink()
    if mode == "postgresql":
        run("service-identity", "old", __file__, "audit-empty")
    else:
        assert not (root / "case.sqlite3").exists()
    for label, version, command in PHASES:
        run(label, version, *command)
        if denied.exists():
            raise SystemExit(f"{label}:network-denied")
        print(f"{label}:pass", flush=True)


def negative():
    import socket
    import psycopg2

    assert os.environ.get("QA_GUARD_INSTALLED") == "1"
    with socket.socket() as sock:
        try:
            sock.connect(("127.0.0.1", 9))
        except PermissionError:
            pass
        else:
            raise AssertionError("Python guard missing")
    try:
        psycopg2.connect(host="127.0.0.1", connect_timeout=1)
    except PermissionError:
        pass
    else:
        raise AssertionError("libpq guard missing")


def service_provenance():
    """Bind PG access to the actual job-owned service ID, never to a caller DSN."""
    service = os.environ.get("QA_SERVICE_CONTAINER", "")
    assert re.fullmatch(r"[0-9a-f]{64}", service)
    assert (os.getenv("GITHUB_ACTIONS"), os.getenv("RUNNER_OS"), os.getenv("RUNNER_ARCH")) == ("true", "Linux", "X64")
    def docker(*args):
        return subprocess.run(
            ["docker", "--host=unix:///var/run/docker.sock", *args],
            timeout=10, check=True, capture_output=True, env={"PATH": "/usr/bin:/bin"},
        ).stdout
    summary = docker(
        "inspect", "--type=container", "--format",
        "{{.Id}} {{.Config.Image}} {{.State.Running}} {{.State.Health.Status}} "
        "{{.HostConfig.Privileged}} {{.HostConfig.NetworkMode}}",
        service,
    ).decode().split()
    assert summary[:5] == [service, PG_IMAGE, "true", "healthy", "false"]
    assert len(summary) == 6 and summary[5] not in {"host", "default", "bridge"}
    assert not json.loads(docker("inspect", "--format", "{{json .HostConfig.PortBindings}}", service))
    mounts = json.loads(docker("inspect", "--format", "{{json .Mounts}}", service))
    binds = [(m["Source"], m["Destination"]) for m in mounts if m["Type"] == "bind"]
    assert binds == [(str(PG_SOCKET), "/run/postgresql")]
    node = PG_SOCKET / ".s.PGSQL.5432"
    assert PG_SOCKET.resolve(strict=True) == PG_SOCKET and node.is_socket() and not node.is_symlink()
    st = node.lstat()
    fingerprint = f"{st.st_dev}:{st.st_ino}"
    assert docker("exec", service, "stat", "-c", "%d:%i", "/run/postgresql/.s.PGSQL.5432").decode().strip() == fingerprint
    return fingerprint


def main():
    if sys.flags.optimize:
        raise SystemExit("optimized Python not allowed")
    if len(sys.argv) == 2:
        action = sys.argv[1]
        if action == "negative":
            negative()
        elif action == "audit-empty":
            with closing(audit(initial=True)):
                pass
        else:
            phase(action)
        return
    if len(sys.argv) != 4 or sys.argv[1] != "--readonly-objects" or sys.argv[3] not in {"sqlite", "postgresql"}:
        raise SystemExit("usage: verify.py --readonly-objects LOCAL_GIT_ROOT sqlite|postgresql")
    mode = sys.argv[3]
    socket_identity = None
    if mode == "postgresql":
        try:
            socket_identity = service_provenance()
        except (subprocess.SubprocessError, ValueError, KeyError, OSError, AssertionError):
            raise SystemExit("service-provenance:fail") from None
    try:
        git_root = Path(sys.argv[2]).resolve(strict=True)
        assert git_root.is_dir()
    except (OSError, RuntimeError, ValueError, AssertionError):
        raise SystemExit("immutable-source:fail") from None
    try:
        temp_path = Path(os.environ["TMPDIR"])
        assert temp_path.is_absolute() and temp_path.resolve(strict=True) == temp_path
        st = temp_path.lstat()
        assert stat.S_ISDIR(st.st_mode) and st.st_uid == os.getuid()
        with tempfile.TemporaryDirectory(dir=temp_path, prefix="disposable-") as disposable:
            root = Path(disposable).resolve(strict=True)
            try:
                materialize(git_root, OLD_SHA, root / "old")
                materialize(git_root, NEW_SHA, root / "next")
            except (subprocess.SubprocessError, OSError, AssertionError, tarfile.TarError):
                raise SystemExit("immutable-source:fail") from None
            try:
                run_matrix(root, mode, socket_identity)
            except (OSError, AssertionError):
                raise SystemExit("harness-guard:fail") from None
        assert not root.exists()
    except (OSError, RuntimeError, ValueError, KeyError, AssertionError):
        raise SystemExit("temporary-workspace:fail") from None
    print(f"cross-version-{mode}:pass")


if __name__ == "__main__":
    try:
        main()
    except Exception:
        # Any unexpected runtime error still yields a finite, non-sensitive diagnostic.
        raise SystemExit("harness:unexpected-failure") from None
