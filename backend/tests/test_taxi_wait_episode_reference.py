"""Artificial SQLite protocol traces, NOT application/API or PostgreSQL evidence.

Run explicitly with --noconftest. This file imports no application modules. Fixed
markers and the fixture's non-reuse set are test controls, not a production UUID
algorithm, migration, receipt store, or a proposed durable history registry.
"""

import re
import sqlite3
from contextlib import contextmanager
from dataclasses import dataclass

import pytest

A, B, C, D = (character * 32 for character in "abcd")
ALLOWED = ("expired", "searching")


class ProtocolError(Exception):
    pass


class UnknownMode(ProtocolError):
    pass


class UnsupportedVersion(ProtocolError):
    pass


class MissingMarker(ProtocolError):
    pass


class MalformedMarker(ProtocolError):
    pass


class WrongOwner(ProtocolError):
    pass


class Ineligible(ProtocolError):
    pass


class MissingOrder(ProtocolError):
    pass


class StaleMarker(ProtocolError):
    pass


@dataclass(frozen=True)
class Request:
    order_id: int = 1
    passenger: int = 7
    version: str = "v1"
    marker: str | None = A


@dataclass(frozen=True)
class Owner:
    id: int
    passenger: int
    mode: str
    status: str
    marker: str | None
    deadline: int | None


def observe(connection, order_id=1):
    row = connection.execute(
        "SELECT id, passenger, mode, status, marker, deadline "
        "FROM reference_order WHERE id = ?",
        (order_id,),
    ).fetchone()
    return Owner(*row) if row else None


def capture(connection, request):
    """Stored mode is authoritative; a request cannot downgrade a strict row."""
    if not connection.in_transaction:
        raise RuntimeError("Caller must begin its transaction before capture")
    owner = observe(connection, request.order_id)
    if owner is None:
        raise MissingOrder
    if owner.passenger != request.passenger:
        raise WrongOwner
    if owner.mode not in ("strict", "legacy"):
        raise UnknownMode
    expected_version = "v1" if owner.mode == "strict" else "legacy"
    if request.version != expected_version:
        raise UnsupportedVersion
    if owner.status not in ALLOWED:
        raise Ineligible
    if owner.mode == "strict":
        if request.marker is None:
            raise MissingMarker
        if type(request.marker) is not str or not re.fullmatch(
            r"[0-9a-f]{32}", request.marker
        ):
            raise MalformedMarker
        if request.marker != owner.marker:
            raise StaleMarker
    return owner


def apply(connection, owner, replacement, deadline, issued):
    """No commit, rollback, reread/adoption, external effect, or automatic retry.

    BEGIN IMMEDIATE is the cooperative caller precondition, exercised below; an
    in_transaction check alone is not proof of a SQLite writer lock.
    """
    if not connection.in_transaction:
        raise RuntimeError("Caller owns the transaction")
    if owner.mode == "strict":
        if type(replacement) is not str or not re.fullmatch(
            r"[0-9a-f]{32}", replacement
        ):
            raise MalformedMarker
        if replacement in issued:
            raise ValueError("Fixture replacement must never have been issued")
    else:
        replacement = owner.marker
    cursor = connection.execute(
        "UPDATE reference_order SET marker = ?, deadline = ? "
        "WHERE id = ? AND passenger = ? AND mode = ? AND status = ? "
        "AND status IN ('expired', 'searching') AND marker IS ?",
        (
            replacement,
            deadline,
            owner.id,
            owner.passenger,
            owner.mode,
            owner.status,
            owner.marker,
        ),
    )
    try:
        won = cursor.rowcount
    finally:
        cursor.close()
    if won and owner.mode == "strict":
        issued.add(replacement)
    return won


def renew(connection, request, replacement, issued, deadline=100):
    return apply(
        connection, capture(connection, request), replacement, deadline, issued
    )


def close_wait(connection, request, replacement, issued):
    # Same owner/mode/marker CAS as renewal, including NULL -> NULL rotation.
    return apply(connection, capture(connection, request), replacement, None, issued)


@pytest.fixture
def database(tmp_path):
    directory = tmp_path / "owned-wait-reference"
    directory.mkdir()
    path = directory / "reference.sqlite"
    connections = []
    issued = {A, D}

    @contextmanager
    def connect():
        connection = sqlite3.connect(path, isolation_level=None, timeout=0.02)
        connections.append(connection)
        try:
            connection.execute("PRAGMA busy_timeout = 20")
            yield connection
        finally:
            if connection.in_transaction:
                connection.rollback()
            connection.close()
            connections.remove(connection)

    try:
        with connect() as connection:
            assert connection.execute("PRAGMA journal_mode = WAL").fetchone() == (
                "wal",
            )
            connection.executescript(
                "CREATE TABLE reference_order (id INTEGER PRIMARY KEY, passenger INTEGER NOT NULL, "
                "mode TEXT NOT NULL, status TEXT NOT NULL, marker TEXT, deadline INTEGER);"
                "CREATE TABLE unrelated (id INTEGER PRIMARY KEY, value TEXT NOT NULL);"
                "INSERT INTO unrelated VALUES (1, 'original');"
            )
            connection.execute(
                "INSERT INTO reference_order VALUES (1, 7, 'strict', 'expired', ?, NULL)",
                (A,),
            )
            connection.execute(
                "INSERT INTO reference_order VALUES (2, 7, 'strict', 'accepted', ?, NULL)",
                (D,),
            )
        yield connect, issued
    finally:
        # A failure does not leave another test's handles/file state behind.
        for connection in connections:
            try:
                if connection.in_transaction:
                    connection.rollback()
            finally:
                connection.close()
        for suffix in ("", "-wal", "-shm", "-journal"):
            (directory / (path.name + suffix)).unlink(missing_ok=True)
        directory.rmdir()


def begin(connection):
    connection.execute("BEGIN IMMEDIATE")


def commit_renew(database, request=Request(), replacement=B, deadline=100):
    connect, issued = database
    with connect() as connection:
        begin(connection)
        assert renew(connection, request, replacement, issued, deadline) == 1
        connection.commit()


@pytest.mark.parametrize(
    "saved", [True, False], ids=["saved-bodyless", "fresh-bodyless"]
)
def test_legacy_identical_requests_after_closure_remain_indistinguishable(
    database, saved
):
    connect, issued = database
    request = Request(version="legacy", marker=None) if saved else None
    with connect() as connection:
        connection.execute(
            "UPDATE reference_order SET mode='legacy', marker=NULL, deadline=50 WHERE id=1"
        )
        begin(connection)
        assert (
            close_wait(connection, Request(version="legacy", marker=None), None, issued)
            == 1
        )
        connection.commit()
    request = request or Request(version="legacy", marker=None)
    commit_renew(database, request, None)
    with connect() as observer:
        assert observe(observer) == Owner(1, 7, "legacy", "expired", None, 100)


@pytest.mark.parametrize("initial_deadline", [None, 50])
def test_strict_closure_invalidates_saved_request_even_before_its_first_read(
    database, initial_deadline
):
    connect, issued = database
    saved = Request()
    with connect() as connection:
        connection.execute(
            "UPDATE reference_order SET deadline=? WHERE id=1", (initial_deadline,)
        )
        begin(connection)
        assert close_wait(connection, saved, B, issued) == 1
        connection.commit()
    with connect() as connection:
        before = observe(connection)
        begin(connection)
        with pytest.raises(StaleMarker):
            renew(connection, saved, C, issued)
        assert (
            observe(connection) == before == Owner(1, 7, "strict", "expired", B, None)
        )
        connection.rollback()
    # A fresh, deliberate observation may requeue under this same-order model policy.
    commit_renew(database, Request(marker=B), C)


def test_null_future_null_aba_does_not_restore_strict_marker(database):
    connect, issued = database
    commit_renew(database)
    with connect() as connection:
        begin(connection)
        assert close_wait(connection, Request(marker=B), C, issued) == 1
        connection.commit()
        begin(connection)
        with pytest.raises(StaleMarker):
            renew(connection, Request(), D, issued)
        assert observe(connection).deadline is None
        assert observe(connection).marker == C


def test_same_deadline_success_rotates_and_old_request_loses(database):
    connect, issued = database
    with connect() as connection:
        connection.execute("UPDATE reference_order SET deadline=100 WHERE id=1")
    commit_renew(database, deadline=100)
    with connect() as connection:
        begin(connection)
        with pytest.raises(StaleMarker):
            renew(connection, Request(), C, issued, 100)
        assert observe(connection) == Owner(1, 7, "strict", "expired", B, 100)


@pytest.mark.parametrize(
    "changes,command,error",
    [
        ({}, Request(marker=None), MissingMarker),
        ({}, Request(marker=""), MalformedMarker),
        ({}, Request(marker="A" * 32), MalformedMarker),
        ({}, Request(marker="a" * 32 + "\0"), MalformedMarker),
        ({}, Request(marker=123), MalformedMarker),
        ({}, Request(version="legacy", marker=None), UnsupportedVersion),
        ({}, Request(version="v2"), UnsupportedVersion),
        ({"mode": "unknown"}, Request(), UnknownMode),
        ({"mode": "legacy"}, Request(), UnsupportedVersion),
        ({}, Request(passenger=8), WrongOwner),
        ({}, Request(order_id=999), MissingOrder),
        ({"status": "accepted"}, Request(), Ineligible),
        ({"status": "cancelled"}, Request(), Ineligible),
        ({"status": "done"}, Request(), Ineligible),
        ({}, Request(marker=B), StaleMarker),
    ],
)
def test_rejection_never_mutates_rotates_or_commits_unrelated_work(
    database, changes, command, error
):
    connect, issued = database
    with connect() as connection, connect() as observer:
        for column, value in changes.items():
            assert column in {"mode", "status"}
            connection.execute(
                f"UPDATE reference_order SET {column}=? WHERE id=1", (value,)
            )
        before = observe(observer)
        issued_before = set(issued)
        begin(connection)
        connection.execute("UPDATE unrelated SET value='pending' WHERE id=1")
        with pytest.raises(error):
            renew(connection, command, C, issued)
        assert connection.in_transaction
        assert observe(connection) == observe(observer) == before
        assert issued == issued_before
        assert observer.execute("SELECT value FROM unrelated").fetchone() == (
            "original",
        )
        connection.rollback()
        assert connection.execute("SELECT value FROM unrelated").fetchone() == (
            "original",
        )


@pytest.mark.parametrize("operation", ["renew", "close"])
@pytest.mark.parametrize(
    "column,value",
    [("passenger", 8), ("mode", "legacy"), ("status", "accepted"), ("marker", B)],
)
def test_captured_owner_cas_cannot_overwrite_newer_owner(
    database, operation, column, value
):
    connect, issued = database
    with connect() as connection:
        begin(connection)
        captured = capture(connection, Request())
        connection.commit()
    with connect() as changer:
        assert column in {"passenger", "mode", "status", "marker"}
        changer.execute(f"UPDATE reference_order SET {column}=? WHERE id=1", (value,))
    with connect() as connection:
        begin(connection)
        before = observe(connection)
        # Apply the actual old immutable capture; no reread-and-adopt.
        assert (
            apply(
                connection, captured, C, None if operation == "close" else 200, issued
            )
            == 0
        )
        assert observe(connection) == before
        assert C not in issued
        connection.rollback()


def test_noncooperative_closure_leaves_old_marker_usable_known_gap(database):
    connect, _ = database
    with connect() as connection:
        connection.execute("UPDATE reference_order SET deadline=50 WHERE id=1")
        connection.execute("UPDATE reference_order SET deadline=NULL WHERE id=1")
    commit_renew(database)  # Deliberate negative: writer did not rotate.
    with connect() as observer:
        assert observe(observer).deadline == 100


def test_forced_historical_marker_reuse_reintroduces_aba_known_gap(database):
    connect, issued = database
    commit_renew(database)
    with connect() as connection:
        begin(connection)
        with pytest.raises(ValueError, match="never have been issued"):
            close_wait(connection, Request(marker=B), A, issued)
        connection.rollback()
        # Deliberately bypass the fixture's freshness rule, modeling old restore/reuse.
        connection.execute(
            "UPDATE reference_order SET marker=?, deadline=NULL WHERE id=1", (A,)
        )
    commit_renew(database, replacement=C)
    with connect() as observer:
        assert observe(observer) == Owner(1, 7, "strict", "expired", C, 100)


def test_newer_order_does_not_invalidate_untouched_old_null_order_known_gap(database):
    connect, _ = database
    with connect() as connection:
        before = observe(connection)
        assert observe(connection, 2).status == "accepted"
        # Current-style abandonment excludes old NULL rows. Another row is not an epoch.
        connection.execute(
            "UPDATE reference_order SET deadline=NULL WHERE id=1 AND deadline IS NOT NULL"
        )
        assert observe(connection) == before
    commit_renew(database)
    with connect() as observer:
        assert observe(observer).deadline == 100
        assert observe(observer, 2) == Owner(2, 7, "strict", "accepted", D, None)


def test_modeled_lost_response_after_commit_is_reconcile_not_receipt_replay(database):
    connect, issued = database
    original = Request()
    commit_renew(database, original)
    # Model discarding the response AFTER known commit, not a process/network experiment.
    with connect() as connection:
        before = observe(connection)
        begin(connection)
        with pytest.raises(StaleMarker):
            renew(connection, original, C, issued, deadline=200)
        connection.rollback()
    with connect() as observer:
        assert observe(observer) == before == Owner(1, 7, "strict", "expired", B, 100)
        # This read cannot prove which command caused the row; no receipt store exists.


@pytest.mark.parametrize("finish", ["commit", "rollback"])
def test_independent_visibility_and_unrelated_caller_work_are_atomic(database, finish):
    connect, issued = database
    with connect() as writer, connect() as observer:
        before = observe(observer)
        begin(writer)
        writer.execute("UPDATE unrelated SET value='changed' WHERE id=1")
        assert renew(writer, Request(), B, issued) == 1
        assert writer.in_transaction
        assert observe(writer) == Owner(1, 7, "strict", "expired", B, 100)
        assert (
            not observer.in_transaction
        )  # Each observation is a fresh autocommit read.
        assert observe(observer) == before
        assert observer.execute("SELECT value FROM unrelated").fetchone() == (
            "original",
        )
        getattr(writer, finish)()
        expected = (
            before if finish == "rollback" else Owner(1, 7, "strict", "expired", B, 100)
        )
        assert observe(observer) == expected
        assert observer.execute("SELECT value FROM unrelated").fetchone() == (
            "original" if finish == "rollback" else "changed",
        )


@pytest.mark.parametrize("finish", ["commit", "rollback"])
def test_two_real_connections_busy_then_same_original_cas_commit_vs_rollback(
    database, finish
):
    connect, issued = database
    with connect() as first, connect() as second:
        assert first is not second
        begin(second)
        original = capture(second, Request())
        second.rollback()
        begin(first)
        assert renew(first, Request(), B, issued) == 1
        assert (
            observe(second) == original
        )  # Independent WAL reader sees committed old owner.
        with pytest.raises(sqlite3.OperationalError) as caught:
            begin(second)
        assert caught.value.sqlite_errorcode == sqlite3.SQLITE_BUSY
        assert caught.value.sqlite_errorname == "SQLITE_BUSY"
        assert not second.in_transaction
        getattr(first, finish)()
        begin(second)  # Explicit test-harness retry, NEVER an application retry loop.
        won = apply(second, original, C, 200, issued)
        assert won == (0 if finish == "commit" else 1)
        second.commit()
        expected = (
            Owner(1, 7, "strict", "expired", B, 100)
            if finish == "commit"
            else Owner(1, 7, "strict", "expired", C, 200)
        )
        assert observe(first) == observe(second) == expected


@pytest.mark.parametrize("status", ["expired", "searching"])
def test_allowed_status_success_preserves_other_order_and_owner_fields(
    database, status
):
    connect, issued = database
    with connect() as connection:
        connection.execute("UPDATE reference_order SET status=? WHERE id=1", (status,))
        other = observe(connection, 2)
        begin(connection)
        assert renew(connection, Request(), B, issued) == 1
        connection.commit()
    with connect() as observer:
        assert observe(observer) == Owner(1, 7, "strict", status, B, 100)
        assert observe(observer, 2) == other


def test_actual_sql_error_after_mutation_requires_caller_rollback(database):
    connect, issued = database
    with connect() as connection, connect() as observer:
        before = observe(observer)
        begin(connection)
        connection.execute("UPDATE unrelated SET value='pending' WHERE id=1")
        assert renew(connection, Request(), B, issued) == 1
        with pytest.raises(sqlite3.IntegrityError) as caught:
            connection.execute("UPDATE unrelated SET value=NULL WHERE id=1")
        assert caught.value.sqlite_errorcode == sqlite3.SQLITE_CONSTRAINT_NOTNULL
        assert connection.in_transaction
        assert observe(observer) == before
        connection.rollback()
        assert observe(observer) == before
        assert observer.execute("SELECT value FROM unrelated").fetchone() == (
            "original",
        )
