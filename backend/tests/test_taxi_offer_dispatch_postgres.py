"""Offer dispatch isolation, ONLY in the guarded disposable revision PG cluster.

No real notification transport. Passing guard does not prevent a later transition
or delayed delivery; the transport checkpoint deliberately exercises that limit.
"""
from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta

import pytest
from sqlalchemy import text
from sqlmodel import Session

from app import instant_service as isv
from app.db import engine
from app.models import InstantOrder, InstantOrderStatus as S, UserRole
from app.timeutil import utcnow
from test_taxi_status_never_resurrects import _order, fake_redis  # noqa: F401
from test_taxi_search_revision_postgres import _disposable_pg_before_client  # noqa: F401
from test_taxi_accept_cleanup_generation import _cleanup_accept_orders  # noqa: F401

pytestmark = [
    pytest.mark.skipif(engine.dialect.name != "postgresql", reason="requires disposable PostgreSQL"),
    pytest.mark.usefixtures("fake_redis"),
]


def _offer(user_factory):
    passenger = user_factory("PGDispatchPassenger")
    driver = user_factory("PGDispatchDriver", role=UserRole.driver)
    oid = _order(passenger["id"], S.offered, search_revision=40,
                 current_offer_driver_id=driver["id"], offer_token="a" * 32,
                 tried_lifecycle="b" * 32,
                 offer_expires_at=utcnow() + timedelta(minutes=5))
    return oid, passenger["id"], driver["id"]


@pytest.mark.parametrize("change", ["cancel", "assignment"])
def test_pg_postcommit_supersession_suppresses_captured_offer(client, user_factory, monkeypatch, change):
    oid, _, did = _offer(user_factory)
    other = user_factory("PGDispatchOtherDriver", role=UserRole.driver)
    sent = []
    monkeypatch.setattr(isv, "send_push", lambda *a, **k: sent.append((a, k)))
    with Session(engine) as caller:
        snapshot = isv._capture_offer_snapshot(caller, oid, did)
        assert snapshot is not None
        caller.commit()
        with Session(engine) as writer:
            row = writer.get(InstantOrder, oid)
            if change == "cancel":
                row.status = S.cancelled
            else:
                row.current_offer_driver_id = other["id"]
                row.offer_token = "c" * 32
                row.search_revision += 1
            writer.add(row)
            writer.commit()
        isv._push_offer(caller, snapshot)
    assert sent == [], "old recipient must not receive a superseded offer"


@pytest.mark.parametrize("flushed", [False, True])
def test_pg_guard_closes_before_transport_without_rolling_back_caller(client, user_factory, monkeypatch, flushed):
    oid, pax, did = _offer(user_factory)
    sentinel = _order(pax, S.created)
    guards, sent = [], []

    class ObservedGuardSession(Session):
        closed_by_guard = False

        def __init__(self, *args, **kwargs):
            super().__init__(*args, **kwargs)
            guards.append(self)

        def close(self):
            super().close()
            self.closed_by_guard = True

    monkeypatch.setattr(isv, "Session", ObservedGuardSession)
    with Session(engine) as caller:
        snapshot = isv._capture_offer_snapshot(caller, oid, did)
        assert snapshot is not None
        caller.commit()
        pending = caller.get(InstantOrder, sentinel)
        pending.from_text = "PG caller write survives guard"
        caller.add(pending)
        if flushed:
            caller.flush()
        caller_pid = caller.execute(text("SELECT pg_backend_pid()")).scalar_one()

        def transport(*args, **kwargs):
            assert guards and all(g.closed_by_guard and not g.in_transaction() for g in guards)
            assert caller.in_transaction(), "guard must not close caller transaction"

            def concurrent_transition():
                with engine.connect() as connection:
                    connection.execute(text("SET lock_timeout = '2s'"))
                    connection.execute(text("SET statement_timeout = '3s'"))
                    pid = connection.execute(text("SELECT pg_backend_pid()")).scalar_one()
                    connection.execute(text("UPDATE instantorder SET status = 'cancelled' WHERE id = :oid"), {"oid": oid})
                    connection.commit()
                    return pid

            # Real UPDATE must finish while transport is still running. A guard
            # row-lock retained until transport returns would fail this bound.
            with ThreadPoolExecutor(max_workers=1) as pool:
                writer_pid = pool.submit(concurrent_transition).result(timeout=6)
            assert writer_pid != caller_pid
            sent.append((args, kwargs))

        monkeypatch.setattr(isv, "send_push", transport)
        isv._push_offer(caller, snapshot)
        assert len(sent) == 1
        caller.commit()
    with Session(engine) as observer:
        assert observer.get(InstantOrder, sentinel).from_text == "PG caller write survives guard"
        assert observer.get(InstantOrder, oid).status == S.cancelled
