"""Phase 1 only: disposable actual-schema PostgreSQL type/lock rehearsal.

No production migration, ORM mapping, adapter-domain proof, downgrade, SQLite,
API, deployed-fleet or durable-recovery claim. Each case clones its own closed
actual bootstrap source; successful cases never contaminate later scenarios.
Catalog equality is limited to the explicit inventories below, not every PG
object property. Sequence options/ownership are captured; advancing sequence
values on failed INSERT is PostgreSQL behavior, not transactional data rollback.
"""

from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
from copy import deepcopy
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time
from uuid import uuid4

import pytest
from sqlalchemy import create_engine, text
from sqlalchemy.engine import make_url
from sqlalchemy.exc import IntegrityError
from sqlalchemy.pool import NullPool

# Module import, not fixture import: no unintended baseline test collection.
import test_taxi_actual_schema_baseline as baseline

MODE = "actual-schema-pg-type-rehearsal"
REVISION_COLUMNS = ("search_revision", "matching_resume_revision")


class PreflightRejected(RuntimeError):
    """Test-local precondition failure, not an application/API policy."""


class InjectedRollback(RuntimeError):
    pass


def _guard(connection, database):
    data = Path(os.environ["YULDASH_TEST_PG_DATA"])
    assert data.is_absolute() and data.name == "data"
    assert data.parent.parent == Path("/tmp")
    assert data.parent.name.startswith("yuldash-revision-pg.")
    actual = connection.execute(
        text("SELECT current_database(), inet_server_addr(), inet_server_port()")
    ).one()
    assert actual == (database, "127.0.0.1", connection.engine.url.port)
    assert Path(connection.exec_driver_sql("SHOW data_directory").scalar_one()) == data


def _base_url():
    if not os.environ.get("DATABASE_URL", "").startswith("postgresql"):
        pytest.skip("dedicated fresh PostgreSQL rehearsal mode required")
    assert os.environ.get("YULDASH_TEST_PG_MODE") in {
        MODE,
        "actual-schema-pg-domain-rehearsal",
        "actual-schema-close-observation",
        "storage-check-contract",
        "actual-orm-counter",
    }
    url = make_url(os.environ["DATABASE_URL"])
    assert url.drivername == "postgresql+psycopg2"
    assert url.host == "127.0.0.1" and url.port and not url.query
    assert url.username == "yuldash_test" and url.database == "yuldash_revision_fixture"
    neutral = {"PGPASSFILE": "/dev/null", "PGSERVICEFILE": "/dev/null"}
    assert all(neutral.get(k) == v for k, v in os.environ.items() if k.startswith("PG"))
    return url


def _quote(name):
    assert re.fullmatch(r"[a-z][a-z0-9_]*", name)
    return '"' + name + '"'


def _engine(url):
    return create_engine(url, poolclass=NullPool, hide_parameters=True)


@pytest.fixture(scope="module", params=tuple(baseline.SOURCE_DB))
def source(request):
    url = _base_url()
    source_name = request.param
    source_db = baseline.SOURCE_DB[source_name]
    root = Path(tempfile.mkdtemp(prefix="yuldash-actual-schema.", dir="/tmp"))
    admin = _engine(url)
    try:
        with admin.connect() as c:
            _guard(c, url.database)
        env = {
            k: v
            for k, v in os.environ.items()
            if not k.startswith("PG")
            and k.lower() not in {"http_proxy", "https_proxy", "all_proxy", "no_proxy"}
        }
        env.update(
            DATABASE_URL=url.set(database=source_db).render_as_string(
                hide_password=False
            ),
            YULDASH_TEST_PG_MODE=baseline.MODE,
            YULDASH_SCHEMA_BASELINE_ROOT=str(root),
            PYTHONPATH=str(baseline.BACKEND),
            ENV="dev",
            SEED_DEMO="false",
            JWT_SECRET="synthetic-rehearsal-secret",
        )
        with (
            (root / "bootstrap.stdout.log").open("w") as out,
            (root / "bootstrap.stderr.log").open("w") as err,
        ):
            try:
                result = subprocess.run(
                    [sys.executable, baseline.__file__, "--bootstrap", source_name],
                    cwd=baseline.BACKEND,
                    env=env,
                    stdout=out,
                    stderr=err,
                    timeout=180,
                    check=False,
                )
                code = result.returncode
            except subprocess.TimeoutExpired:
                code = 124  # subprocess.run has killed and waited for the child.
                err.write("Bootstrap timeout; child killed and waited.\n")
        (root / "bootstrap.exit").write_text(str(code))
        print(f"type rehearsal source artifacts: {root}")
        assert code == 0, f"actual bootstrap failed, evidence: {root}"
        report = json.loads((root / f"{source_name}.json").read_text())
        assert report["success"] and report["metadata_missing"] == {}
        assert report["provenance"]["database"] == source_db
        assert report["provenance"]["alembic_version"] == (
            {"table_present": True, "versions": ["co_instant_matching_resume"]}
            if source_name == "chain"
            else {"table_present": False, "versions": []}
        )
        yield url, source_db, source_name, root
    finally:
        admin.dispose()


def _await_zero_backends(
    connection, database, artifact, *, own_pid=None, raw_closed=None
):
    """Bounded synchronization, not a diagnosis of the original count=1 failure."""
    assert connection.connection.driver_connection.autocommit is True
    assert connection.engine.url.database == "yuldash_revision_fixture"
    assert (
        connection.engine.url.username == "yuldash_test"
        and not connection.engine.url.query
    )
    _guard(connection, "yuldash_revision_fixture")
    assert database in baseline.SOURCE_DB.values() or re.fullmatch(
        r"yuldash_type_case_[0-9a-f]{32}", database
    )
    assert database != connection.engine.url.database
    started = time.monotonic()
    deadline = started + 2.0
    previous = None
    first_nonzero = False
    with artifact.open("x") as evidence:

        def record(event, rows):
            evidence.write(
                json.dumps(
                    {
                        "event": event,
                        "monotonic": time.monotonic(),
                        "elapsed": time.monotonic() - started,
                        "database": database,
                        "own_pid": own_pid,
                        "raw_closed_at_entry": raw_closed,
                        "backends": rows,
                    },
                    default=str,
                    sort_keys=True,
                )
                + "\n"
            )
            evidence.flush()

        while True:
            rows = [
                dict(row)
                for row in connection.execute(
                    text(
                        "SELECT pid,backend_type,state,application_name,backend_start,state_change,"
                        "wait_event_type,wait_event FROM pg_stat_activity WHERE datname=:db ORDER BY pid"
                    ),
                    {"db": database},
                )
                .mappings()
                .all()
            ]
            now = time.monotonic()
            if rows != previous:
                record("transition", rows)
                previous = rows
            if rows and not first_nonzero:
                record("first_nonzero", rows)
                first_nonzero = True
            if not rows and now <= deadline:
                record("zero", rows)
                return
            if now >= deadline:
                record("timeout", rows)
                raise AssertionError(
                    f"backend zero not observed within 2s; evidence: {artifact}"
                )
            time.sleep(min(0.01, max(0.0, deadline - time.monotonic())))


@pytest.fixture
def database(source):
    url, source_db, source_name, root = source
    name = "yuldash_type_case_" + uuid4().hex
    admin = _engine(url)
    engine = _engine(url.set(database=name))
    registry = set()
    assert source_db in baseline.SOURCE_DB.values()
    assert name not in {*baseline.SOURCE_DB.values(), url.database, "postgres"}
    template = _engine(url.set(database=source_db))
    try:
        with template.connect() as c:
            _guard(c, source_db)
            fingerprint = (_catalog(c), _data(c))
            fingerprint_pid = c.exec_driver_sql("SELECT pg_backend_pid()").scalar_one()
            fingerprint_raw = c.connection.driver_connection
        template.dispose()
        assert fingerprint_raw.closed != 0
        # Dedicated autocommit admin connection, never a caller transaction.
        with admin.connect().execution_options(isolation_level="AUTOCOMMIT") as c:
            _guard(c, url.database)
            assert c.connection.driver_connection.autocommit is True
            _await_zero_backends(
                c,
                source_db,
                root / f"{name}-before-create.jsonl",
                own_pid=fingerprint_pid,
                raw_closed=fingerprint_raw.closed,
            )
            assert not c.execute(
                text("SELECT EXISTS(SELECT 1 FROM pg_database WHERE datname=:db)"),
                {"db": name},
            ).scalar_one()
            try:
                c.exec_driver_sql(
                    f"CREATE DATABASE {_quote(name)} TEMPLATE {_quote(source_db)}"
                )
                registry.add(name)
            except Exception:
                # A lost acknowledgement may have created this exact nonce.
                # Observe on a healthy guarded connection; never retry CREATE.
                with admin.connect().execution_options(
                    isolation_level="AUTOCOMMIT"
                ) as observer:
                    _guard(observer, url.database)
                    if observer.execute(
                        text(
                            "SELECT EXISTS(SELECT 1 FROM pg_database WHERE datname=:db)"
                        ),
                        {"db": name},
                    ).scalar_one():
                        registry.add(name)
                raise
        assert name in registry
        with engine.connect() as c:
            _guard(c, name)
            assert (_catalog(c), _data(c)) == fingerprint
        yield engine, source_name
    finally:
        engine.dispose()
        template.dispose()
        try:
            if name in registry:
                with admin.connect().execution_options(
                    isolation_level="AUTOCOMMIT"
                ) as c:
                    _guard(c, url.database)
                    assert c.connection.driver_connection.autocommit is True
                    _await_zero_backends(c, name, root / f"{name}-before-drop.jsonl")
                    c.exec_driver_sql(f"DROP DATABASE {_quote(name)}")
                    assert not c.execute(
                        text(
                            "SELECT EXISTS(SELECT 1 FROM pg_database WHERE datname=:db)"
                        ),
                        {"db": name},
                    ).scalar_one()
                    registry.remove(name)
        finally:
            admin.dispose()


def _catalog(c):
    """Authoritative catalog using the SAME locked transaction, no side session."""
    queries = {
        "columns": """SELECT t.relname, a.attname, format_type(a.atttypid,a.atttypmod) AS type,
            a.attnotnull, pg_get_expr(d.adbin,d.adrelid) AS default, a.attidentity,a.attgenerated
            FROM pg_class t JOIN pg_namespace n ON n.oid=t.relnamespace
            JOIN pg_attribute a ON a.attrelid=t.oid
            LEFT JOIN pg_attrdef d ON d.adrelid=t.oid AND d.adnum=a.attnum
            WHERE n.nspname='public' AND t.relkind IN ('r','p') AND a.attnum>0 AND NOT a.attisdropped
            ORDER BY t.relname,a.attnum""",
        "constraints": """SELECT t.relname,c.conname,c.contype,pg_get_constraintdef(c.oid) AS definition
            FROM pg_constraint c JOIN pg_class t ON t.oid=c.conrelid
            JOIN pg_namespace n ON n.oid=t.relnamespace WHERE n.nspname='public'
            ORDER BY t.relname,c.conname""",
        "indexes": "SELECT tablename,indexname,indexdef FROM pg_indexes WHERE schemaname='public' ORDER BY tablename,indexname",
        "triggers": """SELECT c.relname,t.tgname,pg_get_triggerdef(t.oid) AS definition
            FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid
            JOIN pg_namespace n ON n.oid=c.relnamespace
            WHERE n.nspname='public' AND NOT t.tgisinternal ORDER BY c.relname,t.tgname""",
        "views": "SELECT viewname,definition FROM pg_views WHERE schemaname='public' ORDER BY viewname",
        "sequences": """SELECT sequencename,data_type,start_value,min_value,max_value,increment_by,cycle,cache_size
            FROM pg_sequences WHERE schemaname='public' ORDER BY sequencename""",
        "sequence_ownership": """SELECT s.relname AS sequence,t.relname AS owner,a.attname,d.deptype
            FROM pg_class s JOIN pg_namespace n ON n.oid=s.relnamespace
            JOIN pg_depend d ON d.objid=s.oid AND d.classid='pg_class'::regclass
            JOIN pg_class t ON t.oid=d.refobjid
            JOIN pg_attribute a ON a.attrelid=t.oid AND a.attnum=d.refobjsubid
            WHERE n.nspname='public' AND s.relkind='S' AND d.deptype IN ('a','i')
            ORDER BY s.relname,t.relname,a.attname""",
        "enums": """SELECT t.typname,e.enumlabel,e.enumsortorder FROM pg_type t
            JOIN pg_namespace n ON n.oid=t.typnamespace JOIN pg_enum e ON e.enumtypid=t.oid
            WHERE n.nspname='public' ORDER BY t.typname,e.enumsortorder""",
    }
    return {
        k: [dict(row) for row in c.execute(text(sql)).mappings()]
        for k, sql in queries.items()
    }


def _data(c):
    names = c.exec_driver_sql(
        "SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename"
    ).scalars()
    return {
        name: c.exec_driver_sql(
            f"SELECT to_jsonb(t) FROM {_quote(name)} t ORDER BY to_jsonb(t)::text"
        )
        .scalars()
        .all()
        for name in names
    }


def _read(engine):
    with engine.connect() as c:
        return _catalog(c), _data(c)


def _numeric_catalog(before):
    expected = deepcopy(before)
    for col in expected["columns"]:
        if col["relname"] == "instantorder" and col["attname"] in REVISION_COLUMNS:
            assert col["type"] == "bigint"
            col["type"] = "numeric"
    return expected


def _begin(c):
    assert c.get_transaction() is None
    assert c.connection.driver_connection.autocommit is False
    tx = c.begin()
    assert (
        c.exec_driver_sql("SHOW transaction_isolation").scalar_one() == "read committed"
    )
    c.exec_driver_sql("SET LOCAL lock_timeout='4s'")
    c.exec_driver_sql("SET LOCAL statement_timeout='10s'")
    return tx


def _upgrade(c, expected_catalog, fail_after=None):
    assert c.in_transaction() and c.connection.driver_connection.autocommit is False
    c.exec_driver_sql("LOCK TABLE instantorder IN ACCESS EXCLUSIVE MODE")
    # No authoritative reads before lock acquisition; refresh after any wait.
    catalog = _catalog(c)
    if catalog != expected_catalog or catalog["triggers"] or catalog["views"]:
        raise PreflightRejected("unsupported catalog")
    columns = {
        r["attname"]: r for r in catalog["columns"] if r["relname"] == "instantorder"
    }
    for name in REVISION_COLUMNS:
        if (
            columns[name]["type"] != "bigint"
            or columns[name]["attnotnull"]
            or columns[name]["default"] is not None
        ):
            raise PreflightRejected("unexpected revision shape")
    if c.exec_driver_sql("""SELECT EXISTS(SELECT 1 FROM instantorder WHERE search_revision<0
        OR matching_resume_revision IS NOT NULL OR matching_resume_next_at IS NOT NULL
        OR matching_resume_attempts IS NOT NULL)""").scalar_one():
        raise PreflightRejected("inactive nonnegative preflight")
    before = _data(c)
    for number, name in enumerate(REVISION_COLUMNS, 1):
        c.exec_driver_sql(
            f"ALTER TABLE instantorder ALTER COLUMN {_quote(name)} TYPE NUMERIC USING {_quote(name)}::numeric"
        )
        if fail_after == number:
            raise InjectedRollback(f"after ALTER {number}")
    assert _catalog(c) == _numeric_catalog(catalog)
    assert _data(c) == before


def _blocked(engine, waiter, holder):
    assert waiter != holder
    deadline = time.monotonic() + 2.5
    with engine.connect() as observer:
        while time.monotonic() < deadline:
            if (
                holder
                in observer.execute(
                    text("SELECT pg_blocking_pids(:pid)"), {"pid": waiter}
                ).scalar_one()
            ):
                return
            time.sleep(0.01)
    raise AssertionError("expected actual PostgreSQL lock wait")


@contextmanager
def _worker(engine, action):
    from queue import Queue

    queue = Queue()
    with ThreadPoolExecutor(max_workers=1) as pool:

        def run():
            with engine.connect() as c:
                tx = _begin(c)
                queue.put(c.exec_driver_sql("SELECT pg_backend_pid()").scalar_one())
                try:
                    result = action(c)
                    tx.commit()
                    return result
                finally:
                    if tx.is_active:
                        tx.rollback()

        future = pool.submit(run)
        yield queue.get(timeout=3), future
        # Every caller releases its holder in finally before this join.
        future.result(timeout=12)


def test_atomic_commit_preserves_catalog_data_and_selected_enforcement(database):
    engine, source_name = database
    before, data = _read(engine)
    full_before = baseline._manifest(engine)
    with engine.connect() as c:
        tx = _begin(c)
        _upgrade(c, before)
        tx.commit()
    after, actual = _read(engine)
    assert after == _numeric_catalog(before) and actual == data
    expected = deepcopy(full_before)
    for col in expected["tables"]["instantorder"]["columns"]:
        if col["name"] in REVISION_COLUMNS:
            assert col["type"] == "BIGINT"
            col["type"] = "NUMERIC"
    assert baseline._manifest(engine) == expected
    _enforcement(engine, source_name)
    assert _read(engine) == (after, actual)


def _enforcement(engine, source_name):
    """Three selected child relationships plus named ledger partial indexes."""
    with engine.connect() as c:
        oid = c.exec_driver_sql(
            "SELECT min(order_id) FROM instanttriedexclusion"
        ).scalar_one()
        history = c.execute(
            text(
                "SELECT lifecycle,driver_id FROM instanttriedexclusion WHERE order_id=:id"
            ),
            {"id": oid},
        ).one()
        debt_columns = (
            c.exec_driver_sql(
                "SELECT attname FROM pg_attribute WHERE attrelid='commissiondebt'::regclass AND attnum>0 AND NOT attisdropped AND attname<>'id' ORDER BY attnum"
            )
            .scalars()
            .all()
        )
        debt_list = ",".join(_quote(name) for name in debt_columns)
        cases = [
            (
                "23505",
                "instanttriedexclusion_pkey",
                "INSERT INTO instanttriedexclusion(order_id,lifecycle,driver_id) VALUES(:id,:life,:driver)",
                {"id": oid, "life": history.lifecycle, "driver": history.driver_id},
            ),
            (
                "23503",
                None,
                "INSERT INTO instanttriedexclusion(order_id,lifecycle,driver_id) VALUES(-1,:life,:driver)",
                {"life": history.lifecycle, "driver": history.driver_id},
            ),
            (
                "23503",
                None,
                "DELETE FROM instantorder WHERE id=(SELECT min(order_id) FROM message)",
                {},
            ),
            (
                "23505",
                "uq_commissiondebt_order_id",
                f"INSERT INTO commissiondebt({debt_list}) SELECT {debt_list} FROM commissiondebt LIMIT 1",
                {},
            ),
        ]
        for code, constraint, sql, params in cases:
            with pytest.raises(IntegrityError) as error:
                with c.begin_nested():
                    c.execute(text(sql), params)
            assert error.value.orig.pgcode == code
            if constraint:
                assert error.value.orig.diag.constraint_name == constraint
        # Parent with only history: actual cascade, then rollback restores it.
        free = c.exec_driver_sql("SELECT max(id) FROM instantorder").scalar_one()
        with c.begin_nested() as savepoint:
            c.execute(text("DELETE FROM instantorder WHERE id=:id"), {"id": free})
            assert (
                c.execute(
                    text(
                        "SELECT count(*) FROM instanttriedexclusion WHERE order_id=:id"
                    ),
                    {"id": free},
                ).scalar_one()
                == 0
            )
            savepoint.rollback()
        for prefix, index in (
            ("promo:", "uq_ledgerentry_promo_comp"),
            ("refund:", "uq_ledgerentry_refund"),
        ):
            sql = text("""INSERT INTO ledgerentry(driver_id,kind,amount_kop,created_at,note,ext_id)
                VALUES(:driver,'adj',0,CURRENT_TIMESTAMP,'synthetic',:ext) RETURNING id""")
            params = {
                "driver": history.driver_id,
                "ext": prefix + "synthetic-rehearsal",
            }
            first = c.execute(sql, params).scalar_one()
            if source_name == "chain":
                with pytest.raises(IntegrityError) as error:
                    with c.begin_nested():
                        c.execute(sql, params)
                assert error.value.orig.pgcode == "23505"
                assert error.value.orig.diag.constraint_name == index
            else:
                second = c.execute(sql, params).scalar_one()
                assert second != first  # Missing index remains missing, not repaired.
        c.rollback()


@pytest.mark.parametrize("fail_after", [1, 2])
def test_injected_failure_rolls_back_both_types_and_unrelated_write(
    database, fail_after
):
    engine, _ = database
    before = _read(engine)
    with engine.connect() as c:
        tx = _begin(c)
        c.exec_driver_sql("UPDATE public.user SET name='uncommitted sentinel'")
        with pytest.raises(InjectedRollback, match=f"after ALTER {fail_after}"):
            _upgrade(c, before[0], fail_after)
        tx.rollback()
    assert _read(engine) == before


@pytest.mark.parametrize(
    "assignment",
    [
        "search_revision=-1",
        "matching_resume_revision=0",
        "matching_resume_next_at=CURRENT_TIMESTAMP",
        "matching_resume_attempts=0",
        "matching_resume_revision=0,matching_resume_next_at=CURRENT_TIMESTAMP",
        "matching_resume_revision=0,matching_resume_attempts=0",
        "matching_resume_next_at=CURRENT_TIMESTAMP,matching_resume_attempts=0",
        "matching_resume_revision=0,matching_resume_next_at=CURRENT_TIMESTAMP,matching_resume_attempts=0",
    ],
)
def test_inactive_preflight_rejects_raw_bad_tuple_without_ddl(database, assignment):
    engine, _ = database
    with engine.begin() as c:
        c.exec_driver_sql(
            f"UPDATE instantorder SET {assignment} WHERE id=(SELECT min(id) FROM instantorder)"
        )
    before = _read(engine)
    with engine.connect() as c:
        tx = _begin(c)
        with pytest.raises(PreflightRejected, match="inactive"):
            _upgrade(c, before[0])
        tx.rollback()
    assert _read(engine) == before


@pytest.mark.parametrize(
    "ddl",
    [
        "ALTER TABLE instantorder ALTER COLUMN search_revision SET DEFAULT 0",
        "ALTER TABLE instantorder ALTER COLUMN search_revision TYPE NUMERIC",
        "ALTER TABLE instantorder ADD CONSTRAINT rehearsal_revision_check CHECK(search_revision>=0)",
    ],
)
def test_unexpected_shape_is_rejected_and_not_repaired(database, ddl):
    engine, _ = database
    expected = _read(engine)[0]
    with engine.begin() as c:
        c.exec_driver_sql(ddl)
    before = _read(engine)
    with engine.connect() as c:
        tx = _begin(c)
        with pytest.raises(PreflightRejected, match="catalog"):
            _upgrade(c, expected)
        tx.rollback()
    assert _read(engine) == before


@pytest.mark.parametrize("kind", ["trigger", "view"])
def test_unsupported_dependency_rejected_before_ddl(database, kind):
    engine, _ = database
    expected = _read(engine)[0]
    with engine.begin() as c:
        if kind == "view":
            c.exec_driver_sql(
                "CREATE VIEW rehearsal_owner_view AS SELECT search_revision FROM instantorder"
            )
        else:
            c.exec_driver_sql(
                "CREATE FUNCTION rehearsal_trigger_fn() RETURNS trigger LANGUAGE plpgsql AS $$BEGIN RETURN NEW; END$$"
            )
            c.exec_driver_sql(
                "CREATE TRIGGER rehearsal_owner_trigger BEFORE UPDATE ON instantorder FOR EACH ROW EXECUTE FUNCTION rehearsal_trigger_fn()"
            )
    before = _read(engine)
    with engine.connect() as c:
        tx = _begin(c)
        with pytest.raises(PreflightRejected, match="catalog"):
            _upgrade(c, expected)
        tx.rollback()
    assert _read(engine) == before


@pytest.mark.parametrize("bad", ["search_revision=-1", "matching_resume_attempts=1"])
@pytest.mark.parametrize("commit_writer", [False, True])
def test_writer_first_wait_then_fresh_preflight(database, bad, commit_writer):
    engine, _ = database
    before = _read(engine)
    with engine.connect() as holder:
        tx = _begin(holder)
        pid = holder.exec_driver_sql("SELECT pg_backend_pid()").scalar_one()
        holder.exec_driver_sql(
            f"UPDATE instantorder SET {bad} WHERE id=(SELECT min(id) FROM instantorder)"
        )

        def action(c):
            try:
                _upgrade(c, before[0])
                return "converted"
            except PreflightRejected as error:
                assert str(error) == "inactive nonnegative preflight"
                return "rejected"

        with _worker(engine, action) as (waiter, future):
            try:
                _blocked(engine, waiter, pid)
            finally:
                if commit_writer:
                    tx.commit()
                else:
                    tx.rollback()
            assert future.result(timeout=8) == (
                "rejected" if commit_writer else "converted"
            )
    catalog, data = _read(engine)
    assert catalog == (before[0] if commit_writer else _numeric_catalog(before[0]))
    if not commit_writer:
        assert data == before[1]
    else:
        expected = deepcopy(before[1])
        field, value = bad.split("=")
        min(expected["instantorder"], key=lambda row: row["id"])[field] = int(value)
        assert data == expected


def test_rehearsal_first_blocks_real_writer_until_commit(database):
    engine, _ = database
    before = _read(engine)
    with engine.connect() as holder:
        tx = _begin(holder)
        pid = holder.exec_driver_sql("SELECT pg_backend_pid()").scalar_one()
        _upgrade(holder, before[0])

        def action(c):
            c.exec_driver_sql(
                "UPDATE instantorder SET from_text='writer after release' WHERE id=(SELECT min(id) FROM instantorder)"
            )
            return "committed"

        with _worker(engine, action) as (waiter, future):
            try:
                _blocked(engine, waiter, pid)
                assert not future.done()  # Block is not proof of reading old data.
            finally:
                tx.commit()
            assert future.result(timeout=8) == "committed"
    catalog, data = _read(engine)
    expected = deepcopy(before[1])
    min(expected["instantorder"], key=lambda row: row["id"])["from_text"] = (
        "writer after release"
    )
    assert catalog == _numeric_catalog(before[0]) and data == expected
