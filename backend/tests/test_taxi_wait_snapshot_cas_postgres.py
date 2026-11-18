"""Real guarded PostgreSQL waits and deferred COMMIT refusal, no episode activation."""

from concurrent.futures import ThreadPoolExecutor
from threading import Event
from uuid import uuid4

import pytest
from fastapi import HTTPException
from sqlalchemy import event, text
from sqlalchemy.exc import IntegrityError
from sqlmodel import Session

from app import taxi_worker
from app.db import engine
from app.models import User, InstantOrderStatus as S
from app.routers import instant as router
import test_taxi_terminal_pending_clear as terminal
import test_taxi_terminal_pending_clear_postgres as pg
from test_taxi_search_revision_postgres import _disposable_pg_before_client  # noqa: F401

seeded = terminal.seeded
terminal_case = terminal.terminal_case
pytestmark = pytest.mark.skipif(
    engine.dialect.name != "postgresql", reason="guarded disposable PostgreSQL only"
)


@pytest.mark.parametrize("first", ["closure", "wait"])
def test_pg_actual_wait_and_deadline_closure_both_lock_orders(terminal_case, first):
    make, passenger, _, now, _ = terminal_case
    oid, before = make("wait_end")
    held, release, started = Event(), Event(), Event()
    pids = {}
    outcomes = []

    def actor(kind, is_first):
        with pg.connected_session() as (session, pid):
            pids["holder" if is_first else "waiter"] = pid

            def commit(actual):
                if held.is_set():
                    return
                assert actual is session
                held.set()
                assert release.wait(20)

            if is_first:
                event.listen(session, "before_commit", commit)
            else:
                started.set()
            try:
                if kind == "closure":
                    outcomes.append((kind, taxi_worker.finish_expired_waits(session)))
                else:
                    user = session.get(User, passenger["id"])
                    try:
                        result = router.wait_for_driver(oid, user, session)
                        outcomes.append((kind, result["ok"]))
                    except HTTPException as error:
                        assert first == "closure"
                        assert (
                            error.status_code == 409
                            and error.detail["code"] == "wait_snapshot_conflict"
                        )
                        session.rollback()
                        outcomes.append((kind, "conflict"))
            finally:
                if is_first:
                    event.remove(session, "before_commit", commit)

    with ThreadPoolExecutor(max_workers=2) as pool:
        holder = pool.submit(actor, first, True)
        try:
            assert held.wait(5)
            assert terminal.snapshot(oid) == before
            waiter = pool.submit(
                actor, "wait" if first == "closure" else "closure", False
            )
            assert started.wait(5)
            pg.observe_wait(pids["waiter"], pids["holder"])
            assert not waiter.done()
            release.set()
            holder.result(timeout=20)
            waiter.result(timeout=20)
        finally:
            release.set()
    after = terminal.snapshot(oid)
    if first == "closure":
        assert ("wait", "conflict") in outcomes
        assert after["wait_until"] is None and terminal.pending(after) == terminal.CLEAR
        if before["expired_at"] is None:
            assert now <= after["expired_at"] <= terminal.transfer.known.utcnow()
        else:
            assert after["expired_at"] == before["expired_at"]
        expected = dict(before)
        expected.update(
            status=S.expired, wait_until=None, expired_at=after["expired_at"]
        )
        expected.update(dict.fromkeys(terminal.PENDING))
        assert after == expected
    else:
        assert ("wait", True) in outcomes and ("closure", []) in outcomes
        assert after["wait_until"] > before["wait_until"]
        assert terminal.pending(after) == terminal.pending(before)
        expected = dict(before, wait_until=after["wait_until"])
        assert after == expected


def test_pg_actual_wait_commit_refusal_rolls_back_deadline_and_related_rows(
    terminal_case,
):
    make, passenger, _, _, _ = terminal_case
    oid, before = make("wait_end")
    suffix = uuid4().hex
    parent, child, constraint = (
        f"wait_{kind}_{suffix}" for kind in ("parent", "child", "fk")
    )
    observed = []
    with engine.connect() as c:
        c.execute(text(f'CREATE TEMP TABLE "{parent}" (id INTEGER PRIMARY KEY)'))
        c.execute(
            text(
                f'CREATE TEMP TABLE "{child}" (id INTEGER PRIMARY KEY,pid INTEGER, CONSTRAINT "{constraint}" FOREIGN KEY(pid) REFERENCES "{parent}"(id) DEFERRABLE INITIALLY DEFERRED)'
            )
        )
        c.commit()
        try:
            with Session(bind=c) as session:
                pg.configure_transaction(session)
                user = session.get(User, passenger["id"])
                session.execute(text(f'INSERT INTO "{parent}" VALUES(1)'))
                session.execute(text(f'INSERT INTO "{child}" VALUES(1,999)'))
                assert (
                    session.execute(
                        text(f'SELECT count(*) FROM "{child}"')
                    ).scalar_one()
                    == 1
                )

                def observe(actual):
                    assert actual is session
                    actual.flush()
                    row = actual.execute(
                        text("SELECT wait_until FROM instantorder WHERE id=:id"),
                        {"id": oid},
                    ).scalar_one()
                    assert row > before["wait_until"]
                    observed.append(True)

                event.listen(session, "before_commit", observe)
                try:
                    with pytest.raises(IntegrityError) as error:
                        router.wait_for_driver(oid, user, session)
                    assert error.value.orig.pgcode == "23503"
                    assert error.value.orig.diag.constraint_name == constraint
                    assert observed == [True]
                    session.rollback()
                    assert (
                        session.execute(
                            text(f'SELECT count(*) FROM "{parent}"')
                        ).scalar_one()
                        == 0
                    )
                    assert (
                        session.execute(
                            text(f'SELECT count(*) FROM "{child}"')
                        ).scalar_one()
                        == 0
                    )
                    assert terminal.snapshot(oid) == before
                finally:
                    event.remove(session, "before_commit", observe)
                    session.rollback()
        finally:
            c.rollback()
            c.execute(text(f'DROP TABLE "{child}"'))
            c.execute(text(f'DROP TABLE "{parent}"'))
            c.commit()
