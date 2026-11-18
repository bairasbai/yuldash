"""Actual PG terminal/transfer ordering and deferred internal COMMIT failure.

Synthetic pending fixtures; no producer/consumer activation. SQL, owner helpers
and transaction commits are real. Session events only hold an actual commit.
"""

from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
from threading import Event
from time import monotonic, sleep
from uuid import uuid4

import pytest
from sqlalchemy import event, text
from sqlalchemy.exc import IntegrityError
from sqlmodel import Session

from app import instant_service as isv
from app.db import engine
from app.models import InstantOrder, InstantOrderStatus as S
import test_taxi_terminal_pending_clear as terminal
from test_taxi_search_revision_postgres import _disposable_pg_before_client  # noqa: F401

seeded = terminal.seeded
terminal_case = terminal.terminal_case
pytestmark = pytest.mark.skipif(
    engine.dialect.name != "postgresql", reason="requires guarded disposable PostgreSQL"
)


def configure_transaction(session):
    session.execute(text("SET LOCAL lock_timeout = '12s'"))
    session.execute(text("SET LOCAL statement_timeout = '15s'"))
    return session.execute(text("SELECT pg_backend_pid()")).scalar_one()


@contextmanager
def connected_session():
    with engine.connect() as connection:
        with Session(bind=connection, expire_on_commit=False) as session:
            yield session, configure_transaction(session)


def observe_wait(waiter_pid, holder_pid):
    deadline = monotonic() + 5
    with engine.connect().execution_options(isolation_level="AUTOCOMMIT") as observer:
        observer_pid = observer.execute(text("SELECT pg_backend_pid()")).scalar_one()
        assert len({holder_pid, waiter_pid, observer_pid}) == 3
        while monotonic() < deadline:
            blockers = observer.execute(
                text("SELECT pg_blocking_pids(:pid)"), {"pid": waiter_pid}
            ).scalar_one()
            if holder_pid in blockers:
                print(f"terminal pending holder={holder_pid} waiter={waiter_pid} observer={observer_pid}")
                return
            sleep(0.01)
    pytest.fail("expected actual PostgreSQL owner lock blocking was not observed")


@pytest.mark.parametrize("operation", ("accept", "cancel"))
@pytest.mark.parametrize("first", ("transfer", "terminal"))
def test_actual_terminal_and_transfer_both_lock_orders(terminal_case, operation, first):
    make, passenger, driver, _, _ = terminal_case
    oid, original = make(operation)
    held = Event()
    release = Event()
    waiter_started = Event()
    pids = {}

    def first_actor():
        with connected_session() as (session, pid):
            pids["holder"] = pid

            def hold_commit(actual):
                assert actual is session
                if held.is_set():
                    return
                held.set()
                assert release.wait(30), "coordinator failed to release actual commit"

            event.listen(session, "before_commit", hold_commit)
            try:
                if first == "transfer":
                    order = session.get(InstantOrder, oid)
                    assert isv._bump_search_revision(session, order) == 42
                    session.commit()
                else:
                    terminal.invoke(session, oid, operation, passenger, driver)
            finally:
                event.remove(session, "before_commit", hold_commit)

    def second_actor():
        with connected_session() as (session, pid):
            pids["waiter"] = pid
            waiter_started.set()
            if first == "transfer":
                terminal.invoke(session, oid, operation, passenger, driver)
            else:
                # Advance uses offered source; a committed terminal winner must
                # cause the existing entry no-op, not recreate a search/pending.
                order = session.get(InstantOrder, oid)
                isv.advance_after_no_accept(session, order, notify=False)

    with ThreadPoolExecutor(max_workers=2) as pool:
        holder = pool.submit(first_actor)
        waiter = None
        try:
            assert held.wait(5), "first real owner update did not reach commit"
            assert terminal.snapshot(oid) == original
            waiter = pool.submit(second_actor)
            assert waiter_started.wait(5)
            observe_wait(pids["waiter"], pids["holder"])
            assert not waiter.done()
            assert terminal.snapshot(oid) == original
            release.set()
            holder.result(timeout=20)
            waiter.result(timeout=20)
        finally:
            release.set()
            # Executor joins both workers before fixture cleanup. SQL/event
            # timeouts bound failure paths; do not mask an earlier assertion by
            # calling future.result again from finally.
    after = terminal.snapshot(oid)
    assert after["status"] == (S.accepted if operation == "accept" else S.cancelled)
    assert after["search_revision"] == (42 if first == "transfer" else 41)
    assert terminal.pending(after) == terminal.CLEAR


@pytest.mark.parametrize("operation", ("accept", "done", "cancel", "finish_early"))
def test_actual_internal_commit_deferred_fk_refusal_rolls_back_clear(
    terminal_case, monkeypatch, operation
):
    make, passenger, driver, _, _ = terminal_case
    oid, original = make(operation)
    suffix = uuid4().hex
    parent = f"terminal_parent_{suffix}"
    child = f"terminal_child_{suffix}"
    constraint = f"terminal_fk_{suffix}"
    transports = []
    # External transport interception only; no business helper/SQL/commit mock.
    monkeypatch.setattr(isv, "send_push", lambda *a, **k: transports.append("push"))
    monkeypatch.setattr(terminal.transfer.known.services, "send_push",
                        lambda *a, **k: transports.append("service_push"))
    commits = []
    with engine.connect() as connection:
        # TEMP objects live only on this exact physical connection. Setup is
        # committed first, so rollback of the actual handler cannot erase proof.
        connection.execute(text(f'CREATE TEMP TABLE "{parent}" (id INTEGER PRIMARY KEY)'))
        connection.execute(text(
            f'CREATE TEMP TABLE "{child}" (id INTEGER PRIMARY KEY, '
            f'parent_id INTEGER, CONSTRAINT "{constraint}" FOREIGN KEY (parent_id) '
            f'REFERENCES "{parent}"(id) DEFERRABLE INITIALLY DEFERRED)'
        ))
        connection.commit()
        try:
            with Session(bind=connection, expire_on_commit=False) as session:
                pid = configure_transaction(session)
                session.execute(text(f'INSERT INTO "{parent}" (id) VALUES (1)'))
                session.execute(text(f'INSERT INTO "{child}" (id,parent_id) VALUES (1,999)'))
                assert session.execute(text(f'SELECT count(*) FROM "{child}"')).scalar_one() == 1

                def before_commit(actual):
                    assert actual is session
                    # Flush actual ORM finish if needed, without replacing the
                    # handler's commit. All mutations remain in this transaction.
                    actual.flush()
                    row = actual.execute(text(
                        'SELECT status, matching_resume_revision, matching_resume_next_at, '
                        'matching_resume_attempts FROM instantorder WHERE id=:id'
                    ), {"id": oid}).one()
                    assert row[0] == {
                        "accept": "accepted", "done": "done", "cancel": "cancelled",
                        "finish_early": "done",
                    }[operation]
                    assert tuple(row[1:]) == terminal.CLEAR
                    assert actual.execute(text("SELECT pg_backend_pid()")).scalar_one() == pid
                    commits.append(tuple(row))

                event.listen(session, "before_commit", before_commit)
                try:
                    with pytest.raises(IntegrityError) as failed:
                        terminal.invoke(session, oid, operation, passenger, driver)
                    assert failed.value.orig.pgcode == "23503"
                    assert failed.value.orig.diag.constraint_name == constraint
                    assert len(commits) == 1
                    assert transports == []
                    session.rollback()
                    assert session.execute(text(f'SELECT count(*) FROM "{parent}"')).scalar_one() == 0
                    assert session.execute(text(f'SELECT count(*) FROM "{child}"')).scalar_one() == 0
                    assert terminal.snapshot(oid) == original
                finally:
                    event.remove(session, "before_commit", before_commit)
                    session.rollback()
        finally:
            connection.rollback()
            connection.execute(text(f'DROP TABLE IF EXISTS "{child}"'))
            connection.execute(text(f'DROP TABLE IF EXISTS "{parent}"'))
            connection.commit()
