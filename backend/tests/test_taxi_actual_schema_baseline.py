"""TEST-ONLY full app bootstrap/catalog diagnostics, no type conversion.

Alembic's baseline imports CURRENT metadata: chain success is not reconstruction
of historical production schema. Children run isolated chain/init_db sources in
separate databases/files, never fall back from a failed chain to metadata.
Enforcement probes cover three selected child relationships, not every catalog FK.
"""

import ast
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile

import pytest

BACKEND = Path(__file__).resolve().parents[1]
SOURCE_DB = {
    "chain": "yuldash_schema_chain_fixture",
    "init_db": "yuldash_schema_init_fixture",
}
MODE = "actual-schema-baseline"


def _local_root():
    root = Path(os.environ.get("YULDASH_SCHEMA_BASELINE_ROOT", ""))
    assert root.is_absolute() and root.parent == Path("/tmp")
    assert re.fullmatch(r"yuldash-actual-schema\.[a-zA-Z0-9_-]+", root.name)
    assert root.is_dir() and not root.is_symlink()
    return root


def _guard_url(url_text, source=None):
    """Validate destination before app imports or any database connection."""
    from sqlalchemy.engine import make_url

    url = make_url(url_text)
    if url.drivername.startswith("postgresql"):
        assert os.environ.get("YULDASH_TEST_PG_MODE") == MODE
        data = Path(os.environ.get("YULDASH_TEST_PG_DATA", ""))
        assert (
            data.is_absolute()
            and data.name == "data"
            and data.parent.parent == Path("/tmp")
        )
        assert data.parent.name.startswith("yuldash-revision-pg.")
        assert url.drivername == "postgresql+psycopg2"
        assert url.host == "127.0.0.1" and url.port and url.username == "yuldash_test"
        assert not url.query
        assert url.database == (
            SOURCE_DB[source] if source else "yuldash_revision_fixture"
        )
        # Only the runner's two neutral parent settings are allowed. Children
        # receive no PG* variables, including these neutral settings.
        allowed = (
            {"PGPASSFILE": "/dev/null", "PGSERVICEFILE": "/dev/null"}
            if source is None
            else {}
        )
        assert all(
            key in allowed and value == allowed[key]
            for key, value in os.environ.items()
            if key.startswith("PG")
        ), "Inherited libpq overrides forbidden"
    else:
        assert source is not None and url.drivername == "sqlite" and not url.query
        assert Path(url.database) == _local_root() / f"{source}.sqlite"
    return url


def _verify_connection(connection, url):
    from sqlalchemy import text

    if url.drivername.startswith("postgresql"):
        assert (
            Path(connection.execute(text("SHOW data_directory")).scalar_one()).resolve()
            == Path(os.environ["YULDASH_TEST_PG_DATA"]).resolve()
        )
        actual_database = connection.execute(
            text("SELECT current_database()")
        ).scalar_one()
        assert actual_database == url.database
        assert (
            str(connection.execute(text("SELECT inet_server_addr()")).scalar_one())
            == url.host
        )
        assert (
            connection.execute(text("SELECT inet_server_port()")).scalar_one()
            == url.port
        )
        assert (
            connection.execute(text("SELECT current_schema()")).scalar_one() == "public"
        )
    else:
        assert Path(
            connection.exec_driver_sql("PRAGMA database_list").all()[0][2]
        ) == Path(url.database)
        assert connection.exec_driver_sql("PRAGMA foreign_keys").scalar_one() == 1
        actual_database = connection.exec_driver_sql("PRAGMA database_list").all()[0][2]
    return actual_database


def _manifest(engine):
    from sqlalchemy import inspect, text

    inspector = inspect(engine)
    tables = {}
    for name in sorted(inspector.get_table_names()):
        columns = []
        for column in inspector.get_columns(name):
            columns.append(
                {
                    "name": column["name"],
                    "type": str(column["type"]),
                    "nullable": column["nullable"],
                    "default": column.get("default"),
                    "identity": column.get("identity"),
                    "computed": column.get("computed"),
                }
            )
        tables[name] = {
            "columns": columns,
            "pk": inspector.get_pk_constraint(name),
            "unique": inspector.get_unique_constraints(name),
            "checks": inspector.get_check_constraints(name),
            "indexes": inspector.get_indexes(name),
            "foreign_keys": inspector.get_foreign_keys(name),
        }
    views = {
        name: inspector.get_view_definition(name) for name in inspector.get_view_names()
    }
    with engine.connect() as c:
        if engine.dialect.name == "sqlite":
            definitions = [
                dict(row)
                for row in c.execute(
                    text(
                        "SELECT type,name,tbl_name,sql FROM sqlite_master WHERE type IN ('table','index','trigger','view') ORDER BY type,name"
                    )
                ).mappings()
            ]
            enums = []
        else:
            definitions = {
                "indexes": [
                    dict(row)
                    for row in c.execute(
                        text(
                            "SELECT tablename,indexname,indexdef FROM pg_indexes WHERE schemaname='public' ORDER BY tablename,indexname"
                        )
                    ).mappings()
                ],
                "constraints": [
                    dict(row)
                    for row in c.execute(
                        text(
                            "SELECT t.relname AS table_name,c.conname,c.contype,pg_get_constraintdef(c.oid) AS definition FROM pg_constraint c JOIN pg_class t ON t.oid=c.conrelid JOIN pg_namespace n ON n.oid=t.relnamespace WHERE n.nspname='public' ORDER BY t.relname,c.conname"
                        )
                    ).mappings()
                ],
                "triggers": [
                    dict(row)
                    for row in c.execute(
                        text(
                            "SELECT c.relname AS table_name,t.tgname,pg_get_triggerdef(t.oid) AS definition FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND NOT t.tgisinternal ORDER BY c.relname,t.tgname"
                        )
                    ).mappings()
                ],
            }
            enums = inspector.get_enums(schema="public")
    incoming = []
    for table, info in tables.items():
        for fk in info["foreign_keys"]:
            if fk["referred_table"] == "instantorder":
                incoming.append({"table": table, **fk})
    return {
        "tables": tables,
        "views": views,
        "definitions": definitions,
        "enums": enums,
        "incoming_instantorder": incoming,
    }


def _seed_and_check(engine):
    from sqlalchemy import select, text
    from sqlalchemy.exc import IntegrityError
    from sqlmodel import Session
    from app.models import (
        User,
        InstantOrder,
        InstantOrderStatus as S,
        InstantTriedExclusion,
        Message,
        CommissionDebt,
    )

    with Session(engine) as session:
        person = User(phone="+70000000001", name="Synthetic schema fixture")
        session.add(person)
        session.flush()
        rows = [
            InstantOrder(
                passenger_id=person.id,
                status=status,
                search_revision=revision,
                from_text="Synthetic A",
                to_text="Synthetic B",
            )
            for status, revision in (
                (S.searching, None),
                (S.offered, 0),
                (S.done, 2**63 - 1),
                (S.created, 1),
            )
        ]
        session.add_all(rows)
        session.flush()
        ids = [row.id for row in rows]
        person_id = person.id
        session.add_all(
            [
                InstantTriedExclusion(
                    order_id=oid, lifecycle="a" * 32, driver_id=person_id
                )
                for oid in ids
            ]
        )
        session.add(
            Message(
                order_id=ids[2], sender_id=person_id, text="Synthetic linked message"
            )
        )
        # Data integrity only. No payment/ledger/service operation is executed.
        session.add(CommissionDebt(order_id=ids[2], driver_id=person_id, amount_kop=0))
        session.commit()
    models = (User, InstantOrder, InstantTriedExclusion, Message, CommissionDebt)

    def snapshot(connection):
        result = {}
        for model in models:
            statement = select(model.__table__)
            if model is User:
                statement = statement.where(User.id == person_id)
            elif model is InstantOrder:
                statement = statement.where(InstantOrder.id.in_(ids))
            else:
                statement = statement.where(model.order_id.in_(ids))
            result[model.__tablename__] = sorted(
                [dict(r) for r in connection.execute(statement).mappings()],
                key=lambda r: json.dumps(r, sort_keys=True, default=str),
            )
        return result

    with engine.connect() as c:
        before = snapshot(c)
    controls = []
    with engine.connect() as c:
        statements = [
            (
                "duplicate_history_pk",
                InstantTriedExclusion.__table__.insert().values(
                    order_id=ids[0], lifecycle="a" * 32, driver_id=person_id
                ),
            ),
            (
                "history_bad_parent",
                InstantTriedExclusion.__table__.insert().values(
                    order_id=-1, lifecycle="a" * 32, driver_id=person_id
                ),
            ),
            ("order_bad_user", InstantOrder.__table__.insert().values(passenger_id=-1)),
            (
                "duplicate_commission_order",
                CommissionDebt.__table__.insert().values(
                    order_id=ids[2], driver_id=person_id, amount_kop=0
                ),
            ),
            (
                "restrict_linked_parent",
                InstantOrder.__table__.delete().where(InstantOrder.id == ids[2]),
            ),
        ]
        for label, statement in statements:
            with pytest.raises(IntegrityError):
                with c.begin_nested():
                    c.execute(statement)
            controls.append(label)
        c.rollback()
        transaction = c.begin()
        c.execute(InstantOrder.__table__.delete().where(InstantOrder.id == ids[3]))
        assert (
            c.execute(
                select(InstantTriedExclusion.order_id).where(
                    InstantTriedExclusion.order_id == ids[3]
                )
            ).all()
            == []
        )
        transaction.rollback()
        controls.append("history_cascade_then_outer_rollback")
    with engine.connect() as held, engine.connect() as observer:
        assert (
            held.connection.driver_connection
            is not observer.connection.driver_connection
        )
        after = snapshot(observer)
        assert after == before
        if engine.dialect.name == "sqlite":
            assert observer.exec_driver_sql("PRAGMA foreign_keys").scalar_one() == 1
            assert observer.exec_driver_sql("PRAGMA foreign_key_check").all() == []
            assert (
                observer.exec_driver_sql("PRAGMA integrity_check").scalar_one() == "ok"
            )
        else:
            assert (
                observer.execute(
                    text(
                        "SELECT count(*) FROM instantorder WHERE matching_resume_revision IS NOT NULL OR matching_resume_next_at IS NOT NULL OR matching_resume_attempts IS NOT NULL"
                    )
                ).scalar_one()
                == 0
            )
    assert all(
        row["matching_resume_revision"] is None
        and row["matching_resume_next_at"] is None
        and row["matching_resume_attempts"] is None
        for row in before["instantorder"]
    )
    return {
        "controls": controls,
        "before": before,
        "after": after,
        "independent_connection": True,
    }


def _bootstrap(source):
    """Closed child entrypoint: exact source, own file/fixed database only."""
    assert source in SOURCE_DB
    root = _local_root()
    report_path = root / f"{source}.json"
    assert not report_path.exists()
    url_text = os.environ["DATABASE_URL"]
    report = {"source": source, "stage": "guard"}
    try:
        url = _guard_url(url_text, source)
        import pytest_socket

        if url.drivername == "sqlite":
            pytest_socket.disable_socket(allow_unix_socket=True)
        else:
            pytest_socket.socket_allow_hosts(["127.0.0.1"], allow_unix_socket=True)
        from sqlalchemy import create_engine, event
        from sqlalchemy.engine import Engine

        if url.drivername == "sqlite":

            @event.listens_for(Engine, "connect")
            def foreign_keys(dbapi_connection, record):
                cursor = dbapi_connection.cursor()
                cursor.execute("PRAGMA foreign_keys=ON")
                cursor.close()

        guard_engine = create_engine(url, hide_parameters=True)
        try:
            with guard_engine.connect() as connection:
                actual_database = _verify_connection(connection, url)
                from sqlalchemy import inspect as inspect_schema

                assert inspect_schema(connection).get_table_names() == [], (
                    "Bootstrap destination must be empty"
                )
                report["provenance"] = {
                    "source": source,
                    "database": actual_database,
                    "dialect": url.drivername,
                    "guard_verified_before_app_import": True,
                }
        finally:
            guard_engine.dispose()
        # Actual app/Alembic imports only after destination guard and network block.
        report["stage"] = "bootstrap"
        if source == "chain":
            from alembic import command
            from alembic.config import Config

            config = Config(str(BACKEND / "alembic.ini"))
            config.set_main_option("script_location", str(BACKEND / "alembic"))
            command.upgrade(config, "co_instant_matching_resume")
        else:
            from app.db import init_db

            init_db()
        from app.db import engine

        report["stage"] = "manifest"
        report["manifest"] = _manifest(engine)
        from sqlmodel import SQLModel

        actual_tables = report["manifest"]["tables"]
        missing = {}
        for name, model_table in SQLModel.metadata.tables.items():
            if name not in actual_tables:
                missing[name] = [column.name for column in model_table.columns]
            else:
                absent = sorted(
                    set(model_table.columns.keys())
                    - {column["name"] for column in actual_tables[name]["columns"]}
                )
                if absent:
                    missing[name] = absent
        report["metadata_missing"] = missing
        assert missing == {}, "Actual bootstrap incomplete relative to current metadata"
        with engine.connect() as connection:
            from sqlalchemy import text

            assert _verify_connection(connection, url) == actual_database
            version_present = "alembic_version" in actual_tables
            versions = (
                list(
                    connection.execute(
                        text("SELECT version_num FROM alembic_version")
                    ).scalars()
                )
                if version_present
                else []
            )
            report["provenance"]["alembic_version"] = {
                "table_present": version_present,
                "versions": versions,
            }
            if source == "chain":
                assert version_present and versions == ["co_instant_matching_resume"]
            else:
                assert not version_present and versions == []
        report["stage"] = "seed_enforcement"
        report["data"] = _seed_and_check(engine)
        report["stage"] = "complete"
        report["success"] = True
        engine.dispose()
    except Exception as error:
        message = str(error).replace(url_text, "<disposable DSN>")
        from sqlalchemy.engine import make_url

        password = make_url(url_text).password
        if password:
            message = message.replace(password, "<redacted>")
        report.update(success=False, error_type=type(error).__name__, error=message)
        # Persist exact source/stage failure; no fallback or alternate bootstrap.
        report_path.write_text(
            json.dumps(report, default=str, sort_keys=True), encoding="utf-8"
        )
        print(
            f"Bootstrap failed: source={source} stage={report['stage']} type={type(error).__name__}",
            file=sys.stderr,
        )
        raise SystemExit(1) from None
    report_path.write_text(
        json.dumps(report, default=str, sort_keys=True), encoding="utf-8"
    )


@pytest.fixture(scope="module", params=["sqlite", "postgresql"])
def bootstraps(request):
    dialect = request.param
    base = os.environ.get("DATABASE_URL", "")
    if dialect == "postgresql" and not base.startswith("postgresql"):
        pytest.skip("dedicated fresh-cluster actual-schema-baseline mode required")
    if dialect == "postgresql":
        _guard_url(base)
    root = Path(tempfile.mkdtemp(prefix="yuldash-actual-schema.", dir="/tmp"))
    reports = {}
    try:
        # Artifacts are intentionally retained for failure/review, not overwritten.
        for source in SOURCE_DB:
            env = {
                key: value
                for key, value in os.environ.items()
                if not key.startswith("PG")
                and key.lower()
                not in {"http_proxy", "https_proxy", "all_proxy", "no_proxy"}
            }
            env.update(
                ENV="dev",
                SEED_DEMO="false",
                JWT_SECRET="synthetic-schema-test-secret",
                YULDASH_SCHEMA_BASELINE_ROOT=str(root),
                PYTHONPATH=str(BACKEND),
            )
            if dialect == "sqlite":
                env["DATABASE_URL"] = "sqlite:///" + str(root / f"{source}.sqlite")
            else:
                from sqlalchemy.engine import make_url

                env["DATABASE_URL"] = (
                    make_url(base)
                    .set(database=SOURCE_DB[source])
                    .render_as_string(hide_password=False)
                )
            with (
                (root / f"{source}.stdout.log").open("w", encoding="utf-8") as stdout,
                (root / f"{source}.stderr.log").open("w", encoding="utf-8") as stderr,
            ):
                try:
                    result = subprocess.run(
                        [
                            sys.executable,
                            str(Path(__file__).resolve()),
                            "--bootstrap",
                            source,
                        ],
                        cwd=BACKEND,
                        env=env,
                        stdout=stdout,
                        stderr=stderr,
                        timeout=180,
                        check=False,
                    )
                    exit_code = result.returncode
                except subprocess.TimeoutExpired:
                    # subprocess.run kills and waits for its child before raising;
                    # bootstrap uses no grandchildren. Record failure, never fallback.
                    exit_code = 124
                    stderr.write("Bootstrap timed out; child terminated and waited.\n")
            (root / f"{source}.exit").write_text(str(exit_code))
            path = root / f"{source}.json"
            reports[source] = {
                "exit": exit_code,
                "report": json.loads(path.read_text())
                if path.exists()
                else {"success": False, "stage": "no_report"},
            }
        print(f"actual-schema artifacts: {root}")
        yield reports, root, dialect
    finally:
        # subprocess.run has returned or killed/waited for a timed-out child.
        # Retain diagnostic artifacts, but remove only our exact SQLite files.
        # Cleanup errors deliberately propagate rather than hiding leaked state.
        for source in SOURCE_DB:
            for suffix in ("", "-wal", "-shm", "-journal"):
                (root / f"{source}.sqlite{suffix}").unlink(missing_ok=True)


@pytest.mark.parametrize("source", SOURCE_DB)
def test_actual_bootstrap_manifest_and_enforcement(bootstraps, source):
    reports, root, dialect = bootstraps
    result = reports[source]
    report = result["report"]
    assert result["exit"] == 0 and report.get("success"), (
        f"{source}: stage={report.get('stage')} error={report.get('error_type')} artifacts={root}"
    )
    assert report["metadata_missing"] == {}
    assert report["provenance"]["guard_verified_before_app_import"]
    if dialect == "postgresql":
        assert report["provenance"]["database"] == SOURCE_DB[source]
    assert report["provenance"]["alembic_version"] == (
        {"table_present": True, "versions": ["co_instant_matching_resume"]}
        if source == "chain"
        else {"table_present": False, "versions": []}
    )
    manifest = report["manifest"]
    assert len(manifest["tables"]) > 40
    columns = {c["name"]: c for c in manifest["tables"]["instantorder"]["columns"]}
    for name in ("search_revision", "matching_resume_revision"):
        assert (
            columns[name]["type"] == "BIGINT"
            and columns[name]["nullable"]
            and columns[name]["default"] is None
        )
    incoming = {fk["table"]: fk for fk in manifest["incoming_instantorder"]}
    assert {"message", "instanttriedexclusion", "commissiondebt"} <= incoming.keys()
    assert incoming["instanttriedexclusion"]["options"].get("ondelete") == "CASCADE"
    data = report["data"]
    assert data["before"] == data["after"] and data["independent_connection"]
    assert len(data["controls"]) == 6


def test_catalog_parity_is_recorded_not_assumed(bootstraps):
    reports, root, _ = bootstraps
    if not all(r["report"].get("success") for r in reports.values()):
        pytest.skip("No parity claim when either independent bootstrap failed")
    chain = reports["chain"]["report"]["manifest"]["tables"]
    direct = reports["init_db"]["report"]["manifest"]["tables"]
    changes = {
        name: {"chain": chain.get(name), "init_db": direct.get(name)}
        for name in sorted(set(chain) | set(direct))
        if chain.get(name) != direct.get(name)
    }
    (root / "catalog-differences.json").write_text(
        json.dumps(changes, sort_keys=True, default=str)
    )
    print(f"actual catalog differences: {list(changes)}; details in {root}")
    # This diagnostic explicitly records differences; zero drift is NOT assumed.
    assert "instantorder" in chain and "instantorder" in direct


def test_dynamic_baseline_provenance_is_explicit():
    path = BACKEND / "alembic/versions/0001_baseline_schema.py"
    tree = ast.parse(path.read_text())
    upgrade = next(
        node
        for node in tree.body
        if isinstance(node, ast.FunctionDef) and node.name == "upgrade"
    )
    assert any(
        isinstance(node, ast.Call)
        and isinstance(node.func, ast.Attribute)
        and node.func.attr == "create_all"
        for node in ast.walk(upgrade)
    )
    print("dynamic baseline SHA256:", hashlib.sha256(path.read_bytes()).hexdigest())


if __name__ == "__main__":
    if (
        len(sys.argv) != 3
        or sys.argv[1] != "--bootstrap"
        or sys.argv[2] not in SOURCE_DB
    ):
        raise SystemExit("Only fixed --bootstrap chain|init_db is supported")
    _bootstrap(sys.argv[2])
