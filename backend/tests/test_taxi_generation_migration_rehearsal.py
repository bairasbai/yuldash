"""Disposable miniature rehearsal: SQLite upgrade, PG upgrade/downgrade only."""

import os
from pathlib import Path

# Reject arbitrary PG collection before importing database libraries or opening SQL.
_PG_URL = os.environ.get("DATABASE_URL", "")
_PG = _PG_URL.startswith("postgresql")
if _PG:
    _root = Path(os.environ.get("YULDASH_TEST_PG_DATA", ""))
    if (
        os.environ.get("YULDASH_TEST_PG_MODE") != "generation-migration-rehearsal"
        or not _root.is_absolute()
        or _root.name != "data"
        or _root.parent.parent != Path("/tmp")
        or not _root.parent.name.startswith("yuldash-revision-pg.")
    ):
        raise RuntimeError(
            "Dedicated disposable generation-migration-rehearsal runner required"
        )


from concurrent.futures import ThreadPoolExecutor
from decimal import Decimal
import sqlite3
from threading import Event
from time import monotonic, sleep
from uuid import uuid4

import pytest
from sqlalchemy import Column, Integer, MetaData, Table, create_engine, select, text
from sqlalchemy.engine import make_url
from sqlalchemy.exc import IntegrityError
from app.taxi_generation_adapter_lab import ExactGeneration, decode_decimal

MAX = 2**63 - 1
MIN = -(2**63)
ROWS = [(1, None, None, 1), (2, -1, MAX, 2), (3, MAX, 0, 3), (4, 0, -1, 4)]


@pytest.fixture
def pg_schema():
    if not _PG:
        pytest.skip("dedicated disposable PostgreSQL mode required")
    url = make_url(_PG_URL)
    assert url.drivername == "postgresql+psycopg2" and url.host == "127.0.0.1"
    assert url.database == "yuldash_revision_fixture" and url.username == "yuldash_test"
    assert url.port and not url.query
    engine = create_engine(url, hide_parameters=True)
    name = "generation_" + uuid4().hex
    created = False
    try:
        with engine.begin() as c:
            assert (
                Path(c.execute(text("SHOW data_directory")).scalar_one()).resolve()
                == _root.resolve()
            )
            assert (
                c.execute(text("SELECT current_database()")).scalar_one()
                == "yuldash_revision_fixture"
            )
            assert (
                str(c.execute(text("SELECT inet_server_addr()")).scalar_one())
                == "127.0.0.1"
            )
            assert c.execute(text("SELECT inet_server_port()")).scalar_one() == url.port
            c.execute(text(f'CREATE SCHEMA "{name}"'))
            created = True
        yield engine, name
    finally:
        try:
            if created:
                with engine.begin() as c:
                    c.execute(text(f'DROP SCHEMA IF EXISTS "{name}" CASCADE'))
                with engine.connect() as c:
                    assert (
                        c.execute(
                            text(
                                "SELECT count(*) FROM pg_namespace WHERE nspname=:name"
                            ),
                            {"name": name},
                        ).scalar_one()
                        == 0
                    )
        finally:
            engine.dispose()


@pytest.fixture
def pg_owner(pg_schema):
    engine, schema = pg_schema
    with engine.begin() as c:
        c.execute(
            text(
                f'CREATE TABLE "{schema}".owner (id INTEGER PRIMARY KEY, revision BIGINT, resume BIGINT, slot INTEGER CHECK(slot>=0))'
            )
        )
        c.execute(text(f'CREATE UNIQUE INDEX owner_slot ON "{schema}".owner(slot)'))
        c.execute(
            text(
                f'CREATE TABLE "{schema}".child (id INTEGER PRIMARY KEY, owner_id INTEGER REFERENCES "{schema}".owner(id))'
            )
        )
        c.execute(
            text(
                f'CREATE TABLE "{schema}".queue (id INTEGER PRIMARY KEY, expected NUMERIC, incompatible BOOLEAN NOT NULL)'
            )
        )
        for row in ROWS:
            c.execute(
                text(f'INSERT INTO "{schema}".owner VALUES (:i,:r,:m,:s)'),
                dict(zip(["i", "r", "m", "s"], row)),
            )
        c.execute(text(f'INSERT INTO "{schema}".child VALUES (1,2)'))
    return engine, schema


def pg_upgrade(c, schema, fail_after=None):
    for index, field in enumerate(["revision", "resume"], 1):
        c.execute(
            text(f'ALTER TABLE "{schema}".owner ALTER COLUMN {field} TYPE NUMERIC')
        )
        if fail_after == index:
            raise RuntimeError("injected upgrade failure")


def pg_downgrade(c, schema, validated=None, release=None, after_alter=None):
    # Locks precede ALL validation and last through both ALTERs/outer commit.
    c.execute(
        text(f'LOCK TABLE "{schema}".owner, "{schema}".queue IN ACCESS EXCLUSIVE MODE')
    )
    if c.execute(
        text(f'SELECT count(*) FROM "{schema}".queue WHERE incompatible')
    ).scalar_one():
        raise ValueError("synthetic pending semantics incompatible")
    for row in c.execute(text(f'SELECT revision,resume FROM "{schema}".owner')):
        for value in row:
            number = decode_decimal(value)
            if number is not None and not MIN <= number <= MAX:
                raise ValueError("outside BIGINT range")
    if validated is not None:
        validated.set()
        assert release.wait(20)
    for field in ["revision", "resume"]:
        c.execute(
            text(f'ALTER TABLE "{schema}".owner ALTER COLUMN {field} TYPE BIGINT')
        )
        if after_alter is not None:
            after_alter(field)


def pg_snapshot(engine, schema):
    with engine.connect() as c:
        values = [
            tuple(str(v) if isinstance(v, Decimal) else v for v in row)
            for row in c.execute(text(f'SELECT * FROM "{schema}".owner ORDER BY id'))
        ]
        types = c.execute(
            text(
                "SELECT column_name,data_type FROM information_schema.columns WHERE table_schema=:s AND table_name='owner' ORDER BY ordinal_position"
            ),
            {"s": schema},
        ).all()
        return values, types


def assert_pg_structure(engine, schema):
    with engine.connect() as c:
        assert c.execute(text(f'SELECT * FROM "{schema}".child')).all() == [(1, 2)]
        index = c.execute(
            text(
                "SELECT indexdef FROM pg_indexes WHERE schemaname=:s AND indexname='owner_slot'"
            ),
            {"s": schema},
        ).scalar_one()
        assert "UNIQUE INDEX" in index and "(slot)" in index
    for sql, code in [
        (f'INSERT INTO "{schema}".child VALUES(2,999)', "23503"),
        (f'INSERT INTO "{schema}".owner VALUES(99,0,0,-1)', "23514"),
        (f'INSERT INTO "{schema}".owner VALUES(99,0,0,1)', "23505"),
    ]:
        with pytest.raises(IntegrityError) as error:
            with engine.begin() as c:
                c.execute(text(sql))
        assert error.value.orig.pgcode == code


def test_pg_upgrade_exact_adapter_and_preserved_structure(pg_owner):
    engine, schema = pg_owner
    assert_pg_structure(engine, schema)
    with engine.begin() as c:
        pg_upgrade(c, schema)
    with engine.connect() as c:
        assert (
            c.execute(text(f'SELECT * FROM "{schema}".owner ORDER BY id')).all() == ROWS
        )
    table = Table(
        "owner",
        MetaData(),
        Column("id", Integer, primary_key=True),
        Column("revision", ExactGeneration()),
        Column("resume", ExactGeneration()),
        schema=schema,
    )
    with engine.begin() as c:
        c.execute(
            table.update()
            .where(table.c.id == 3)
            .values(revision=MAX + 1, resume=10**40 + 1)
        )
    with engine.connect() as c:
        assert c.execute(
            select(table.c.revision, table.c.resume).where(table.c.id == 3)
        ).one() == (MAX + 1, 10**40 + 1)
    assert_pg_structure(engine, schema)


@pytest.mark.parametrize("stage", [1, 2])
def test_pg_upgrade_rollback(pg_owner, stage):
    engine, schema = pg_owner
    before = pg_snapshot(engine, schema)
    with pytest.raises(RuntimeError):
        with engine.begin() as c:
            pg_upgrade(c, schema, stage)
    assert pg_snapshot(engine, schema) == before
    assert_pg_structure(engine, schema)


@pytest.mark.parametrize("field", ["revision", "resume"])
@pytest.mark.parametrize(
    "bad", ["1.5", "NaN", "Infinity", "-Infinity", str(MAX + 1), str(MIN - 1)]
)
def test_pg_downgrade_preflight_rejects_without_changes(pg_owner, field, bad):
    engine, schema = pg_owner
    with engine.begin() as c:
        pg_upgrade(c, schema)
        c.execute(
            text(f'UPDATE "{schema}".owner SET {field}=CAST(:v AS NUMERIC) WHERE id=1'),
            {"v": bad},
        )
        if bad == "1.5":
            assert (
                c.execute(
                    text("SELECT CAST(CAST(:v AS NUMERIC) AS BIGINT)"), {"v": bad}
                ).scalar_one()
                == 2
            )
    before = pg_snapshot(engine, schema)
    with pytest.raises(ValueError):
        with engine.begin() as c:
            pg_downgrade(c, schema)
    assert pg_snapshot(engine, schema) == before


def test_pg_downgrade_pending_gate_and_positive_boundaries(pg_owner):
    engine, schema = pg_owner
    with engine.begin() as c:
        pg_upgrade(c, schema)
        c.execute(
            text(f'UPDATE "{schema}".owner SET revision=:lo,resume=:hi WHERE id=2'),
            {"lo": Decimal(MIN), "hi": Decimal(MAX)},
        )
        c.execute(text(f'INSERT INTO "{schema}".queue VALUES(1,0,TRUE)'))
    before = pg_snapshot(engine, schema)
    with pytest.raises(ValueError):
        with engine.begin() as c:
            pg_downgrade(c, schema)
    assert pg_snapshot(engine, schema) == before
    with engine.begin() as c:
        c.execute(text(f'DELETE FROM "{schema}".queue'))
        pg_downgrade(c, schema)
    with engine.connect() as c:
        assert c.execute(
            text(f'SELECT revision,resume FROM "{schema}".owner WHERE id=2')
        ).one() == (MIN, MAX)
    assert all(
        t == "bigint"
        for n, t in pg_snapshot(engine, schema)[1]
        if n in ["revision", "resume"]
    )
    assert_pg_structure(engine, schema)


def wait_blocked(c, waiter, holder):
    deadline = monotonic() + 3
    while monotonic() < deadline:
        if (
            holder
            in c.execute(
                text("SELECT pg_blocking_pids(:p)"), {"p": waiter}
            ).scalar_one()
        ):
            return
        sleep(0.01)
    pytest.fail("actual lock wait not observed")


def test_pg_upgrade_waits_for_reader(pg_owner):
    engine, schema = pg_owner
    ready = Event()
    pid = []
    with engine.connect() as reader, ThreadPoolExecutor(max_workers=1) as pool:
        holder = reader.execute(text("SELECT pg_backend_pid()")).scalar_one()
        reader.execute(text(f'SELECT * FROM "{schema}".owner')).all()

        def alter():
            with engine.begin() as c:
                c.execute(text("SET LOCAL lock_timeout='6s'"))
                c.execute(text("SET LOCAL statement_timeout='8s'"))
                pid.append(c.execute(text("SELECT pg_backend_pid()")).scalar_one())
                ready.set()
                pg_upgrade(c, schema)

        future = pool.submit(alter)
        try:
            assert ready.wait(3)
            assert holder != pid[0]
            with engine.connect() as observer:
                wait_blocked(observer, pid[0], holder)
            reader.commit()
            future.result(timeout=9)
        finally:
            reader.rollback()
    assert all(
        t == "numeric"
        for n, t in pg_snapshot(engine, schema)[1]
        if n in ["revision", "resume"]
    )


@pytest.mark.parametrize("mutation", ["INSERT", "UPDATE"])
def test_pg_downgrade_lock_blocks_writer_until_both_alters_commit(pg_owner, mutation):
    engine, schema = pg_owner
    with engine.begin() as c:
        pg_upgrade(c, schema)
    validated, release, writer_ready = Event(), Event(), Event()
    holder_pid, writer_pid = [], []
    altered = [Event(), Event()]
    continue_alter = [Event(), Event()]

    def observe_alter(field):
        index = ["revision", "resume"].index(field)
        altered[index].set()
        assert continue_alter[index].wait(20)

    def downgrade():
        with engine.begin() as c:
            c.execute(text("SET LOCAL statement_timeout='8s'"))
            holder_pid.append(c.execute(text("SELECT pg_backend_pid()")).scalar_one())
            pg_downgrade(c, schema, validated, release, observe_alter)

    def writer():
        with engine.begin() as c:
            c.execute(text("SET LOCAL lock_timeout='6s'"))
            c.execute(text("SET LOCAL statement_timeout='8s'"))
            writer_pid.append(c.execute(text("SELECT pg_backend_pid()")).scalar_one())
            writer_ready.set()
            if mutation == "INSERT":
                c.execute(text(f'INSERT INTO "{schema}".owner VALUES(9,7,8,9)'))
            else:
                c.execute(
                    text(f'UPDATE "{schema}".owner SET revision=7,resume=8 WHERE id=1')
                )

    with ThreadPoolExecutor(max_workers=2) as pool:
        migration = pool.submit(downgrade)
        try:
            assert validated.wait(3)
            pending = pool.submit(writer)
            assert writer_ready.wait(3)
            assert holder_pid[0] != writer_pid[0]
            with engine.connect() as observer:
                wait_blocked(observer, writer_pid[0], holder_pid[0])
            release.set()
            for index in range(2):
                assert altered[index].wait(3)
                with engine.connect() as observer:
                    wait_blocked(observer, writer_pid[0], holder_pid[0])
                assert not pending.done()
                continue_alter[index].set()
            migration.result(timeout=9)
            pending.result(timeout=9)
        finally:
            release.set()
            for signal in continue_alter:
                signal.set()
    with engine.connect() as c:
        rowid = 9 if mutation == "INSERT" else 1
        assert c.execute(
            text(f'SELECT revision,resume FROM "{schema}".owner WHERE id=:i'),
            {"i": rowid},
        ).one() == (7, 8)
    assert all(
        t == "bigint"
        for n, t in pg_snapshot(engine, schema)[1]
        if n in ["revision", "resume"]
    )


def sqlite_setup(path):
    c = sqlite3.connect(path, isolation_level=None, timeout=0.1)
    c.execute("PRAGMA foreign_keys=ON")
    c.execute(
        "CREATE TABLE owner(id INTEGER PRIMARY KEY,revision BIGINT,resume BIGINT,slot INTEGER CHECK(slot>=0))"
    )
    c.execute("CREATE UNIQUE INDEX owner_slot ON owner(slot)")
    c.execute(
        "CREATE TABLE child(id INTEGER PRIMARY KEY,owner_id INTEGER REFERENCES owner(id))"
    )
    c.execute("CREATE TABLE queue(expected TEXT)")
    c.executemany("INSERT INTO owner VALUES(?,?,?,?)", ROWS)
    c.execute("INSERT INTO child VALUES(1,2)")
    return c


def sqlite_upgrade(c, fail_after=None):
    assert not c.in_transaction
    c.execute("PRAGMA foreign_keys=OFF")
    assert c.execute("PRAGMA foreign_keys").fetchone() == (0,)
    try:
        c.execute("BEGIN IMMEDIATE")
        rows = c.execute("SELECT * FROM owner ORDER BY id").fetchall()
        c.execute(
            "CREATE TABLE replacement(id INTEGER PRIMARY KEY,revision TEXT,resume TEXT,slot INTEGER CHECK(slot>=0))"
        )
        for i, r, m, s in rows:
            assert all(v is None or type(v) is int for v in [r, m])
            c.execute(
                "INSERT INTO replacement VALUES(?,?,?,?)",
                (i, None if r is None else str(r), None if m is None else str(m), s),
            )
        if fail_after == "copy":
            raise RuntimeError("injected copy failure")
        c.execute("DROP TABLE owner")
        if fail_after == "drop":
            raise RuntimeError("injected drop failure")
        c.execute("ALTER TABLE replacement RENAME TO owner")
        if fail_after == "rename":
            raise RuntimeError("injected rename failure")
        c.execute("CREATE UNIQUE INDEX owner_slot ON owner(slot)")
        assert c.execute("PRAGMA foreign_key_check").fetchall() == []
        c.commit()
    finally:
        c.rollback()
        c.execute("PRAGMA foreign_keys=ON")
        assert c.execute("PRAGMA foreign_keys").fetchone() == (1,)


def sqlite_structure(c):
    assert c.execute("PRAGMA integrity_check").fetchall() == [("ok",)]
    assert (
        dict((r[1], r[2]) for r in c.execute("PRAGMA table_info(owner)"))["slot"]
        == "INTEGER"
    )
    assert c.execute("SELECT * FROM child").fetchall() == [(1, 2)]
    assert c.execute("PRAGMA foreign_key_list(child)").fetchone()[2:5] == (
        "owner",
        "owner_id",
        "id",
    )
    assert c.execute("PRAGMA index_info(owner_slot)").fetchall() == [(0, 3, "slot")]
    assert (
        next(
            row
            for row in c.execute("PRAGMA index_list(owner)")
            if row[1] == "owner_slot"
        )[2]
        == 1
    )
    assert c.execute("PRAGMA foreign_key_check").fetchall() == []
    for sql in [
        "INSERT INTO child VALUES(2,999)",
        "INSERT INTO owner VALUES(99,0,0,-1)",
        "INSERT INTO owner VALUES(99,0,0,1)",
    ]:
        with pytest.raises(sqlite3.IntegrityError):
            c.execute(sql)


@pytest.mark.parametrize("stage", [None, "copy", "drop", "rename"])
def test_sqlite_upgrade_or_atomic_rollback_with_constraints(tmp_path, stage):
    path = tmp_path / "rebuild.db"
    c = sqlite_setup(path)
    try:
        sqlite_structure(c)
        before = c.execute(
            "SELECT name,sql FROM sqlite_master WHERE type IN ('table','index') ORDER BY name"
        ).fetchall()
        assert c.execute(
            "SELECT typeof(revision),typeof(resume) FROM owner WHERE id=2"
        ).fetchone() == ("integer", "integer")
        if stage:
            with pytest.raises(RuntimeError):
                sqlite_upgrade(c, stage)
            assert (
                c.execute(
                    "SELECT name,sql FROM sqlite_master WHERE type IN ('table','index') ORDER BY name"
                ).fetchall()
                == before
            )
            assert c.execute("SELECT * FROM owner ORDER BY id").fetchall() == ROWS
        else:
            sqlite_upgrade(c)
            expected = [
                (i, None if r is None else str(r), None if m is None else str(m), s)
                for i, r, m, s in ROWS
            ]
            assert c.execute("SELECT * FROM owner ORDER BY id").fetchall() == expected
            assert [row[2] for row in c.execute("PRAGMA table_info(owner)")][1:3] == [
                "TEXT",
                "TEXT",
            ]
            assert c.execute(
                "SELECT typeof(revision),typeof(resume) FROM owner WHERE id=2"
            ).fetchone() == ("text", "text")
        sqlite_structure(c)
        with sqlite3.connect(path) as independent:
            assert (
                independent.execute("SELECT * FROM owner ORDER BY id").fetchall()
                == c.execute("SELECT * FROM owner ORDER BY id").fetchall()
            )
    finally:
        c.close()


def test_sqlite_lock_before_copy_blocks_writer(tmp_path):
    path = tmp_path / "busy.db"
    a = sqlite_setup(path)
    b = sqlite3.connect(path, isolation_level=None, timeout=0.1)
    try:
        a.execute("PRAGMA foreign_keys=OFF")
        a.execute("BEGIN IMMEDIATE")
        assert a.execute("SELECT count(*) FROM owner").fetchone() == (4,)
        with pytest.raises(sqlite3.OperationalError) as error:
            b.execute("UPDATE owner SET revision=5 WHERE id=1")
        assert error.value.sqlite_errorcode == sqlite3.SQLITE_BUSY
        a.rollback()
        a.execute("PRAGMA foreign_keys=ON")
        assert a.execute("PRAGMA foreign_keys").fetchone() == (1,)
        b.execute("UPDATE owner SET revision=5 WHERE id=1")
        assert a.execute("SELECT revision FROM owner WHERE id=1").fetchone() == (5,)
    finally:
        a.rollback()
        a.execute("PRAGMA foreign_keys=ON")
        a.close()
        b.close()


def test_sqlite_persisted_replay_and_old_writer_are_boundaries_not_drain(tmp_path):
    path = tmp_path / "replay.db"
    c = sqlite_setup(path)
    try:
        sqlite_upgrade(c)
        c.execute("INSERT INTO queue VALUES(?)", (str(MAX),))
        c.execute(
            "UPDATE owner SET revision=?,resume=? WHERE id=3",
            (str(MAX + 1), str(MAX + 1)),
        )
    finally:
        c.close()
    # Simulated restart reads a truly persisted old request, not a fleet proof.
    with sqlite3.connect(path) as restarted:
        old = restarted.execute("SELECT expected FROM queue").fetchone()[0]
        assert (
            restarted.execute(
                "UPDATE owner SET revision=? WHERE id=3 AND revision=?", ("0", old)
            ).rowcount
            == 0
        )
        assert (
            int(
                restarted.execute("SELECT revision FROM owner WHERE id=3").fetchone()[0]
            )
            > MAX
        )
        # Old MAX-gated binary cannot continue the new generation.
        legacy_allowed = (
            int(
                restarted.execute("SELECT revision FROM owner WHERE id=3").fetchone()[0]
            )
            < MAX
        )
        assert not legacy_allowed
        restarted.execute("UPDATE owner SET revision=? WHERE id=3", (old,))
        assert (
            restarted.execute(
                "UPDATE owner SET revision=? WHERE id=3 AND revision=?", ("1", old)
            ).rowcount
            == 1
        )
        # Matching numeric identity alone admits stale replay after value reuse.
        restarted.execute(
            "UPDATE owner SET revision=?,resume=? WHERE id=3", (str(MAX), str(MAX))
        )
        restarted.execute(
            "UPDATE owner SET revision=revision+1,resume=resume+1 WHERE id=3"
        )
        raw = restarted.execute(
            "SELECT revision,resume FROM owner WHERE id=3"
        ).fetchone()
        assert raw[0] == raw[1] != str(MAX + 1)
