"""Synthetic SQLite A/B comparison: NOT application, HTTP, PG or cutover proof.

Run --noconftest. Stored configuration selects A/B, never request input. Fixture
allocation burns markers even on rollback; this is not a production non-reuse
registry or receipt store. Scheduled intent is explicitly unsupported.
"""

import re
import sqlite3
from contextlib import contextmanager
from dataclasses import dataclass, replace

import pytest

INSERT_ONLY = "INSERT_ONLY"
ACCEPTED_CREATE = "ACCEPTED_CREATE_INCLUDING_EXISTING_RETURN"


@dataclass(frozen=True)
class Observation:
    id: int
    passenger: int
    domain: str
    status: str
    version: str
    row_marker: str | None
    scope_marker: str | None
    deadline: int | None


class Rejected(ValueError):
    pass


def capture(connection, order_id=1):
    row = connection.execute(
        "SELECT o.id,o.passenger,o.domain,o.status,o.version,o.marker,"
        "s.marker,o.deadline FROM orders o JOIN scopes s "
        "ON s.passenger=o.passenger AND s.domain=o.domain WHERE o.id=?",
        (order_id,),
    ).fetchone()
    return Observation(*row) if row else None


def valid_marker(value):
    return type(value) is str and re.fullmatch(r"[0-9a-f]{32}", value) is not None


def mutate_wait(connection, observation, marker, deadline=100, actor=7):
    """Caller BEGIN IMMEDIATE; immutable observation is carried through contention.

    No commit, rollback, retry, snapshot adoption or NULL initialization. An
    in_transaction flag alone is NOT proof of writer admission; tests exercise it.
    """
    if not connection.in_transaction:
        raise RuntimeError("Caller transaction required")
    variant = connection.execute("SELECT variant FROM configuration").fetchone()[0]
    if variant not in ("A", "B"):
        raise Rejected("variant")
    if observation is None:
        raise Rejected("missing")
    if observation.passenger != actor:
        raise Rejected("owner")
    if observation.domain != "instant":
        raise Rejected("scope")
    if observation.version != "v1":
        raise Rejected("version")
    if observation.status not in ("expired", "searching"):
        raise Rejected("status")
    if not valid_marker(observation.row_marker) or not valid_marker(marker):
        raise Rejected("row marker")
    if variant == "B" and not valid_marker(observation.scope_marker):
        raise Rejected("scope marker")
    cursor = connection.execute(
        "UPDATE orders SET marker=?,deadline=? WHERE id=? AND passenger=? "
        "AND domain=? AND status=? AND version=? AND marker IS ? "
        "AND deadline IS ? AND EXISTS(SELECT 1 FROM configuration WHERE variant=?) "
        "AND (?='A' OR EXISTS(SELECT 1 FROM scopes s WHERE s.passenger=orders.passenger "
        "AND s.domain=orders.domain AND s.marker IS ?))",
        (
            marker,
            deadline,
            observation.id,
            observation.passenger,
            observation.domain,
            observation.status,
            observation.version,
            observation.row_marker,
            observation.deadline,
            variant,
            variant,
            observation.scope_marker,
        ),
    )
    try:
        return cursor.rowcount
    finally:
        cursor.close()


def accepted_create(connection, allocate, policy, *, insert=True):
    """Modeled accepted-create event, not actual endpoint admission/auth."""
    if not connection.in_transaction:
        raise RuntimeError("Caller transaction required")
    if policy not in (INSERT_ONLY, ACCEPTED_CREATE):
        raise ValueError("policy")
    variant = connection.execute("SELECT variant FROM configuration").fetchone()[0]
    if variant not in ("A", "B"):
        raise Rejected("variant")
    abandonment = insert or policy == ACCEPTED_CREATE
    rows = connection.execute(
        "SELECT id,status,deadline FROM orders WHERE passenger=7 AND domain='instant'"
    ).fetchall()
    for oid, status, deadline in rows:
        closing = status == "expired" and deadline is not None
        if closing or (variant == "A" and abandonment):
            connection.execute(
                "UPDATE orders SET marker=?,deadline=? WHERE id=?",
                (allocate(), None if closing else deadline, oid),
            )
    if variant == "B" and abandonment:
        connection.execute(
            "UPDATE scopes SET marker=? WHERE passenger=7 AND domain='instant'",
            (allocate(),),
        )
    if insert:
        connection.execute(
            "INSERT INTO orders VALUES(10,7,'instant','accepted','v1',?,NULL)",
            (allocate(),),
        )
    return 10 if insert else 2


@pytest.fixture(params=["A", "B"])
def database(tmp_path, request):
    directory = tmp_path / "owned-compound-reference"
    directory.mkdir()
    path = directory / "reference.sqlite"
    active = []
    counter = 100

    def allocate():
        nonlocal counter
        counter += 1  # Fixture-only allocation burns numbers even after rollback.
        return f"{counter:032x}"

    @contextmanager
    def connect():
        connection = sqlite3.connect(path, isolation_level=None, timeout=0.02)
        active.append(connection)
        try:
            connection.execute("PRAGMA busy_timeout=20")
            yield connection
        finally:
            try:
                if connection.in_transaction:
                    connection.rollback()
            finally:
                connection.close()
                active.remove(connection)

    try:
        with connect() as connection:
            connection.execute("PRAGMA journal_mode=WAL")
            connection.executescript(
                "CREATE TABLE configuration(variant TEXT NOT NULL);"
                "CREATE TABLE scopes(passenger INTEGER,domain TEXT,marker TEXT,"
                "PRIMARY KEY(passenger,domain));"
                "CREATE TABLE orders(id INTEGER PRIMARY KEY,passenger INTEGER NOT NULL,"
                "domain TEXT NOT NULL,status TEXT NOT NULL,version TEXT NOT NULL,"
                "marker TEXT,deadline INTEGER);"
                "CREATE TABLE unrelated(id INTEGER PRIMARY KEY,value TEXT NOT NULL);"
                "INSERT INTO unrelated VALUES(1,'before');"
            )
            connection.execute("INSERT INTO configuration VALUES(?)", (request.param,))
            for passenger, domain in [
                (7, "instant"),
                (8, "instant"),
                (7, "courier"),
                (7, "scheduled"),
            ]:
                connection.execute(
                    "INSERT INTO scopes VALUES(?,?,?)", (passenger, domain, allocate())
                )
            for oid, passenger, domain, status, deadline in [
                (1, 7, "instant", "expired", None),
                (2, 7, "instant", "searching", None),
                (3, 7, "instant", "expired", 90),
                (4, 8, "instant", "expired", 90),
                (5, 7, "courier", "expired", 90),
                (6, 7, "scheduled", "expired", 90),
            ]:
                connection.execute(
                    "INSERT INTO orders VALUES(?,?,?,?,?,?,?)",
                    (oid, passenger, domain, status, "v1", allocate(), deadline),
                )
        yield connect, allocate, request.param
    finally:
        assert not active
        for suffix in ("", "-wal", "-shm", "-journal"):
            candidate = directory / ("reference.sqlite" + suffix)
            if candidate.exists():
                candidate.unlink()
        directory.rmdir()


def snapshot(connect):
    # Every observer is a new, closed autocommit connection, not a held WAL reader.
    with connect() as connection:
        return {
            table: connection.execute(
                f"SELECT * FROM {table} ORDER BY {keys}"
            ).fetchall()
            for table, keys in (
                ("configuration", "variant"),
                ("scopes", "passenger,domain"),
                ("orders", "id"),
                ("unrelated", "id"),
            )
        }


@pytest.mark.parametrize("policy", [INSERT_ONLY, ACCEPTED_CREATE])
@pytest.mark.parametrize("insert", [False, True])
def test_create_policy_null_cross_order_and_nonnull_closure(database, policy, insert):
    connect, allocate, variant = database
    before = snapshot(connect)
    with connect() as connection:
        old = capture(connection)
        closing = capture(connection, 3)
        connection.execute("BEGIN IMMEDIATE")
        assert accepted_create(connection, allocate, policy, insert=insert) == (
            10 if insert else 2
        )
        assert snapshot(connect) == before
        connection.commit()
    with connect() as connection:
        fresh = capture(connection)
        closed = capture(connection, 3)
        assert closed.deadline is None and closed.row_marker != closing.row_marker
        if variant == "B" and not insert and policy == INSERT_ONLY:
            assert closed.scope_marker == closing.scope_marker
        connection.execute("BEGIN IMMEDIATE")
        assert mutate_wait(connection, closing, allocate()) == 0
        invalidated = insert or policy == ACCEPTED_CREATE
        assert mutate_wait(connection, old, allocate()) == int(not invalidated)
        connection.rollback()
        connection.execute("BEGIN IMMEDIATE")
        # Fresh explicit old-order choice remains allowed despite another live order.
        assert mutate_wait(connection, fresh, allocate()) == 1
        connection.commit()
    after = snapshot(connect)
    assert [row for row in after["orders"] if row[0] in (4, 5, 6)] == [
        row for row in before["orders"] if row[0] in (4, 5, 6)
    ]
    assert [row for row in after["scopes"] if row[:2] != (7, "instant")] == [
        row for row in before["scopes"] if row[:2] != (7, "instant")
    ]


@pytest.mark.parametrize("policy", [INSERT_ONLY, ACCEPTED_CREATE])
@pytest.mark.parametrize("fault", [False, True])
def test_create_rollback_atomic_including_unrelated_work(database, policy, fault):
    connect, allocate, _ = database
    before = snapshot(connect)
    with connect() as connection:
        connection.execute("BEGIN IMMEDIATE")
        connection.execute("UPDATE unrelated SET value='pending'")
        accepted_create(connection, allocate, policy)
        if fault:
            with pytest.raises(sqlite3.IntegrityError) as error:
                connection.execute("INSERT INTO unrelated VALUES(2,NULL)")
            assert error.value.sqlite_errorcode == sqlite3.SQLITE_CONSTRAINT_NOTNULL
        assert snapshot(connect) == before
        connection.rollback()
    assert snapshot(connect) == before


@pytest.mark.parametrize("deadline", [None, 100])
def test_local_closure_and_same_deadline_rotate_only_order(database, deadline):
    connect, allocate, _ = database
    with connect() as connection:
        connection.execute("UPDATE orders SET deadline=? WHERE id=1", (deadline,))
        old, other = capture(connection), capture(connection, 2)
        connection.execute("BEGIN IMMEDIATE")
        assert mutate_wait(connection, old, allocate(), deadline) == 1
        connection.commit()
        fresh = capture(connection)
        assert fresh.scope_marker == old.scope_marker
        assert fresh.row_marker != old.row_marker
        connection.execute("BEGIN IMMEDIATE")
        assert mutate_wait(connection, old, allocate()) == 0
        assert mutate_wait(connection, other, allocate()) == 1
        connection.rollback()


@pytest.mark.parametrize(
    "field,value,error",
    [
        ("passenger", 8, "owner"),
        ("domain", "scheduled", "scope"),
        ("domain", None, "scope"),
        ("domain", "unknown", "scope"),
        ("version", "legacy", "version"),
        ("version", None, "version"),
        ("version", 1, "version"),
        ("status", "done", "status"),
        ("row_marker", None, "row marker"),
        ("row_marker", "A" * 32, "row marker"),
        ("row_marker", 2, "row marker"),
        ("row_marker", "a" * 32 + "\x00", "row marker"),
    ],
)
def test_invalid_captured_components_do_not_mutate(database, field, value, error):
    connect, allocate, _ = database
    before = snapshot(connect)
    with connect() as connection:
        observation = replace(capture(connection), **{field: value})
        connection.execute("BEGIN IMMEDIATE")
        with pytest.raises(Rejected, match=error):
            mutate_wait(connection, observation, allocate())
        connection.commit()
    assert snapshot(connect) == before


@pytest.mark.parametrize("scope", [None, "", "A" * 32, 1])
def test_b_cannot_use_a_shape_and_a_does_not_depend_on_scope_component(database, scope):
    connect, allocate, variant = database
    before = snapshot(connect)
    with connect() as connection:
        observation = replace(capture(connection), scope_marker=scope)
        connection.execute("BEGIN IMMEDIATE")
        if variant == "B":
            with pytest.raises(Rejected, match="scope marker"):
                mutate_wait(connection, observation, allocate())
        else:
            assert mutate_wait(connection, observation, allocate()) == 1
        connection.rollback()
    assert snapshot(connect) == before


@pytest.mark.parametrize(
    "change", ["id", "owner", "status", "version", "mode", "null_marker"]
)
def test_authoritative_state_cannot_be_downgraded_or_adopted(database, change):
    connect, allocate, _ = database
    with connect() as connection:
        old = capture(connection)
        if change == "id":
            old = replace(old, id=999)
        else:
            sql = {
                "owner": "UPDATE orders SET passenger=8 WHERE id=1",
                "status": "UPDATE orders SET status='done' WHERE id=1",
                "version": "UPDATE orders SET version='legacy' WHERE id=1",
                "mode": "UPDATE configuration SET variant='unknown'",
                "null_marker": "UPDATE orders SET marker=NULL WHERE id=1",
            }[change]
            connection.execute(sql)
        before = snapshot(connect)
        connection.execute("BEGIN IMMEDIATE")
        if change == "mode":
            with pytest.raises(Rejected, match="variant"):
                mutate_wait(connection, old, allocate())
        else:
            assert mutate_wait(connection, old, allocate()) == 0
        connection.commit()
    assert snapshot(connect) == before


def test_capture_is_one_joined_select_frozen_and_never_initializes(database):
    connect, allocate, _ = database
    with connect() as connection:
        connection.execute("UPDATE orders SET marker=NULL WHERE id=1")
        connection.execute(
            "UPDATE scopes SET marker=NULL WHERE passenger=7 AND domain='instant'"
        )
        before = snapshot(connect)
        statements = []
        connection.set_trace_callback(statements.append)
        observation = capture(connection)
        connection.set_trace_callback(None)
        assert len(statements) == 1 and "JOIN scopes" in statements[0]
        assert observation.row_marker is None and observation.scope_marker is None
        from dataclasses import FrozenInstanceError

        with pytest.raises(FrozenInstanceError):
            observation.row_marker = allocate()
        assert capture(connection, 999) is None
        connection.execute("BEGIN IMMEDIATE")
        with pytest.raises(Rejected, match="missing"):
            mutate_wait(connection, None, allocate())
        with pytest.raises(Rejected, match="row marker"):
            mutate_wait(connection, observation, allocate())
        connection.commit()
    assert snapshot(connect) == before


@pytest.mark.parametrize("policy", [INSERT_ONLY, ACCEPTED_CREATE])
def test_lost_create_response_retry_is_not_historical_idempotency(database, policy):
    connect, allocate, _ = database
    with connect() as connection:
        connection.execute("BEGIN IMMEDIATE")
        accepted_create(connection, allocate, policy)
        connection.commit()  # Response loss modeled; no actual network-fault claim.
        newer_intent = capture(connection)
        connection.execute("BEGIN IMMEDIATE")
        accepted_create(connection, allocate, policy, insert=False)
        connection.commit()
        connection.execute("BEGIN IMMEDIATE")
        assert mutate_wait(connection, newer_intent, allocate()) == int(
            policy == INSERT_ONLY
        )
        connection.rollback()


@pytest.mark.parametrize("bypass", ["noncooperative", "new_order_only", "skip_null"])
def test_noncooperative_and_partial_invalidation_known_gaps(database, bypass):
    connect, allocate, variant = database
    with connect() as connection:
        old = capture(connection)
        connection.execute("BEGIN IMMEDIATE")
        connection.execute(
            "INSERT INTO orders VALUES(10,7,'instant','accepted','v1',?,NULL)",
            (allocate(),),
        )
        if bypass == "new_order_only":
            connection.execute("UPDATE orders SET marker=? WHERE id=10", (allocate(),))
        elif bypass == "skip_null":
            # Deliberately broken A-style invalidation, also ineffective instead of B.
            connection.execute(
                "UPDATE orders SET marker=? WHERE passenger=7 "
                "AND domain='instant' AND deadline IS NOT NULL",
                (allocate(),),
            )
        connection.commit()
        connection.execute("BEGIN IMMEDIATE")
        assert mutate_wait(connection, old, allocate()) == 1, variant
        connection.rollback()


@pytest.mark.parametrize("restore", ["none", "row", "scope", "pair"])
def test_forced_historical_pair_restore_is_explicit_aba_gap(database, restore):
    connect, allocate, variant = database
    with connect() as connection:
        old = capture(connection)
        connection.execute("BEGIN IMMEDIATE")
        accepted_create(connection, allocate, ACCEPTED_CREATE)
        # Force both away, then restore outside the cooperative marker allocator.
        connection.execute("UPDATE orders SET marker=? WHERE id=1", (allocate(),))
        connection.execute(
            "UPDATE scopes SET marker=? WHERE passenger=7 AND domain='instant'",
            (allocate(),),
        )
        if restore in ("row", "pair"):
            connection.execute(
                "UPDATE orders SET marker=? WHERE id=1", (old.row_marker,)
            )
        if restore in ("scope", "pair"):
            connection.execute(
                "UPDATE scopes SET marker=? WHERE passenger=7 AND domain='instant'",
                (old.scope_marker,),
            )
        connection.commit()
        connection.execute("BEGIN IMMEDIATE")
        expected = restore == "pair" or (variant == "A" and restore == "row")
        assert mutate_wait(connection, old, allocate()) == int(expected)
        connection.rollback()


@pytest.mark.parametrize("policy", [INSERT_ONLY, ACCEPTED_CREATE])
@pytest.mark.parametrize("first", ["create", "wait"])
@pytest.mark.parametrize("commit", [False, True])
@pytest.mark.parametrize("insert", [False, True])
def test_two_physical_writers_exact_busy_and_fixed_serial_outcome(
    database, policy, first, commit, insert
):
    connect, allocate, _ = database
    baseline = snapshot(connect)
    with connect() as holder, connect() as contender:
        assert holder is not contender
        old = capture(contender)
        holder.execute("BEGIN IMMEDIATE")
        if first == "create":
            accepted_create(holder, allocate, policy, insert=insert)
        else:
            assert mutate_wait(holder, old, allocate(), 200) == 1
        assert snapshot(connect) == baseline
        with pytest.raises(sqlite3.OperationalError) as error:
            contender.execute("BEGIN IMMEDIATE")
        assert error.value.sqlite_errorcode == sqlite3.SQLITE_BUSY
        assert error.value.sqlite_errorname == "SQLITE_BUSY"
        assert not contender.in_transaction
        if commit:
            holder.commit()
        else:
            holder.rollback()
        contender.execute("BEGIN IMMEDIATE")
        if first == "create":
            invalidated = commit and (insert or policy == ACCEPTED_CREATE)
            assert mutate_wait(contender, old, allocate()) == int(not invalidated)
        else:
            accepted_create(contender, allocate, policy, insert=insert)
        contender.commit()
    with connect() as observer:
        fresh = capture(observer)
        if first == "wait":
            assert (
                fresh.deadline is None
            )  # Committed wait closed; rolled-back wait absent.
            assert observer.execute(
                "SELECT count(*) FROM orders WHERE id=10"
            ).fetchone()[0] == int(insert)
        else:
            invalidated = commit and (insert or policy == ACCEPTED_CREATE)
            assert fresh.deadline == (None if invalidated else 100)
            assert observer.execute(
                "SELECT count(*) FROM orders WHERE id=10"
            ).fetchone()[0] == int(commit and insert)
