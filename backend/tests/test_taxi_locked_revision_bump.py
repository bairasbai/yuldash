"""Pre-implementation SQLite probe for guarded-lock UPDATE observability.

The candidate below is a TEST-LOCAL SQL model, not the runtime helper. Audit
triggers show its extra write; this is not arbitrary-trigger equivalence or
production schema inspection. Later runtime acceptance must be separate.
"""

from uuid import uuid4

import pytest
from sqlalchemy import select, text, update, func
from sqlmodel import Session

from app import instant_service as isv
from app.db import engine
from app.models import InstantOrder, InstantOrderStatus as S
from test_taxi_status_never_resurrects import _order
from test_taxi_search_revision_postgres import _disposable_pg_before_client  # noqa: F401

MAX = 2**63 - 1


@pytest.fixture
def sqlite_audit(client, user_factory):
    if engine.dialect.name != "sqlite":
        pytest.skip("pre-implementation SQLite trigger probe only")
    passenger = user_factory("RevisionAuditPassenger")
    oid = _order(passenger["id"], S.created, search_revision=0)
    suffix = uuid4().hex
    trigger = "revision_audit_" + suffix
    audit = "revision_events_" + suffix
    try:
        with engine.connect() as c:
            # Inspect actual conftest-created app schema BEFORE adding our own
            # connection-local TEMP trigger. This says nothing about deployed DB.
            assert (
                c.execute(
                    text(
                        "SELECT name FROM sqlite_master WHERE type='trigger' AND tbl_name='instantorder'"
                    )
                ).all()
                == []
            )
            c.execute(
                text(
                    f'CREATE TEMP TABLE "{audit}" (old_value INTEGER, new_value INTEGER)'
                )
            )
            c.execute(
                text(
                    f'CREATE TEMP TRIGGER "{trigger}" AFTER UPDATE ON main.instantorder WHEN NEW.id={oid} BEGIN INSERT INTO "{audit}" VALUES(OLD.search_revision,NEW.search_revision); END'
                )
            )
            c.commit()
            try:
                yield c, oid, audit
            finally:
                c.rollback()
                c.execute(text(f'DROP TRIGGER "{trigger}"'))
                c.execute(text(f'DROP TABLE "{audit}"'))
                c.commit()
    finally:
        with Session(engine) as session:
            row = session.get(InstantOrder, oid)
            if row is not None:
                session.delete(row)
                session.commit()


def _events(c, audit):
    return [
        tuple(row)
        for row in c.execute(text(f'SELECT old_value,new_value FROM "{audit}"'))
    ]


def _candidate_model(c, oid):
    # Real SQL lock/write. Fully consume RETURNING: (None,) != missing row.
    row = c.execute(
        update(InstantOrder)
        .where(InstantOrder.id == oid, isv._can_increment_search_revision(c.dialect.name))
        .values(search_revision=InstantOrder.search_revision)
        .returning(InstantOrder.search_revision)
    ).first()
    if row is None:
        return None
    old = row[0]
    new = 1 if old is None else old + 1
    result = c.execute(
        update(InstantOrder)
        .where(
            InstantOrder.id == oid,
            InstantOrder.search_revision.is_not_distinct_from(old),
        )
        .values(search_revision=new)
    )
    assert result.rowcount == 1
    return new


@pytest.mark.parametrize("old", [None, 0, MAX - 1])
def test_probe_valid_candidate_two_updates_legacy_one_and_rollback(sqlite_audit, old):
    c, oid, audit = sqlite_audit
    c.execute(
        update(InstantOrder).where(InstantOrder.id == oid).values(search_revision=old)
    )
    c.execute(text(f'DELETE FROM "{audit}"'))
    c.commit()
    expected = 1 if old is None else old + 1
    assert _candidate_model(c, oid) == expected
    assert _events(c, audit) == [(old, old), (old, expected)]
    c.rollback()
    assert _events(c, audit) == []
    assert (
        c.execute(
            select(InstantOrder.search_revision).where(InstantOrder.id == oid)
        ).scalar_one()
        == old
    )
    # The exact previous SQL-arithmetic shape is an explicit baseline model,
    # not monkeypatched runtime and not future runtime acceptance.
    c.execute(
        update(InstantOrder)
        .where(InstantOrder.id == oid, isv._can_increment_search_revision(c.dialect.name))
        .values(search_revision=func.coalesce(InstantOrder.search_revision, 0) + 1)
    )
    assert _events(c, audit) == [(old, expected)]
    c.rollback()
    assert _events(c, audit) == []


@pytest.mark.parametrize("old", [MAX, -1])
def test_probe_invalid_owner_zero_update_events(sqlite_audit, old):
    c, oid, audit = sqlite_audit
    c.execute(
        update(InstantOrder).where(InstantOrder.id == oid).values(search_revision=old)
    )
    c.execute(text(f'DELETE FROM "{audit}"'))
    c.commit()
    assert _candidate_model(c, oid) is None
    assert _events(c, audit) == []
    c.rollback()
    assert (
        c.execute(
            select(InstantOrder.search_revision).where(InstantOrder.id == oid)
        ).scalar_one()
        == old
    )


def test_probe_missing_is_not_existing_null_owner(sqlite_audit):
    c, oid, audit = sqlite_audit
    c.execute(
        update(InstantOrder).where(InstantOrder.id == oid).values(search_revision=None)
    )
    c.execute(text(f'DELETE FROM "{audit}"'))
    c.commit()
    assert _candidate_model(c, -1) is None
    assert _events(c, audit) == []
    assert _candidate_model(c, oid) == 1
    assert _events(c, audit) == [(None, None), (None, 1)]
    c.rollback()
    assert _events(c, audit) == []


@pytest.fixture
def bump_order(client, user_factory):
    passenger = user_factory("ActualBumpPassenger")
    oid = _order(passenger["id"], S.searching, search_revision=20)
    try:
        yield oid, passenger
    finally:
        with Session(engine) as session:
            row = session.get(InstantOrder, oid)
            if row is not None:
                session.delete(row)
                session.commit()


@pytest.mark.parametrize("old", [None, 0, MAX - 1, MAX, -1])
def test_actual_helper_revision_boundaries_and_caller_rollback(bump_order, old):
    from fastapi import HTTPException

    oid, _ = bump_order
    with engine.begin() as c:
        c.execute(
            update(InstantOrder)
            .where(InstantOrder.id == oid)
            .values(search_revision=old)
        )
    with Session(engine) as session:
        order = session.get(InstantOrder, oid)
        if old in (MAX, -1):
            with pytest.raises(HTTPException) as error:
                isv._bump_search_revision(session, order)
            assert error.value.status_code == 409
        else:
            assert isv._bump_search_revision(session, order) == (
                1 if old is None else old + 1
            )
        assert session.in_transaction()
        session.rollback()
    with engine.connect() as c:
        assert (
            c.execute(
                select(InstantOrder.search_revision).where(InstantOrder.id == oid)
            ).scalar_one()
            == old
        )


def test_actual_missing_owner_conflict(bump_order):
    from fastapi import HTTPException

    oid, _ = bump_order
    with Session(engine) as session:
        order = session.get(InstantOrder, oid)
        # Keep loaded object but release read transaction before independent delete.
        session.expunge(order)
        session.rollback()
        with engine.begin() as c:
            c.execute(InstantOrder.__table__.delete().where(InstantOrder.id == oid))
        with pytest.raises(HTTPException) as error:
            isv._bump_search_revision(session, order)
        assert error.value.status_code == 409
        session.rollback()


def test_actual_dirty_revision_rejected_before_any_sql(bump_order):
    from fastapi import HTTPException
    from sqlalchemy import event

    oid, _ = bump_order
    with Session(engine) as session:
        order = session.get(InstantOrder, oid)
        order.search_revision = 31
        statements = []

        def record(*args):
            statements.append(args[2])

        event.listen(engine, "before_cursor_execute", record)
        try:
            with pytest.raises(HTTPException) as error:
                isv._bump_search_revision(session, order)
            assert error.value.status_code == 409
            assert statements == [] and order.search_revision == 31
        finally:
            event.remove(engine, "before_cursor_execute", record)
            session.rollback()


def test_actual_unrelated_dirty_not_flushed_or_discarded_and_stale_revision_fresh(
    bump_order,
):
    oid, _ = bump_order
    with Session(engine, expire_on_commit=False) as session:
        order = session.get(InstantOrder, oid)
        session.commit()
        with engine.begin() as c:
            c.execute(
                update(InstantOrder)
                .where(InstantOrder.id == oid)
                .values(search_revision=25)
            )
        assert order.search_revision == 20
        before_text = order.to_text
        order.to_text = "Pending local route"
        assert isv._bump_search_revision(session, order) == 26
        assert order.to_text == "Pending local route"
        with session.no_autoflush:
            db_text = session.execute(
                select(InstantOrder.to_text).where(InstantOrder.id == oid)
            ).scalar_one()
        assert db_text == before_text
        session.commit()
    with engine.connect() as c:
        assert c.execute(
            select(InstantOrder.search_revision, InstantOrder.to_text).where(
                InstantOrder.id == oid
            )
        ).one() == (26, "Pending local route")


def test_actual_helper_two_trigger_events_and_rollback(sqlite_audit):
    c, oid, audit = sqlite_audit
    with Session(bind=c) as session:
        order = session.get(InstantOrder, oid)
        assert isv._bump_search_revision(session, order) == 1
        assert _events(c, audit) == [(0, 0), (0, 1)]
        session.rollback()
        assert _events(c, audit) == []


def test_actual_trigger_changes_revision_cas_zero_requires_rollback(sqlite_audit):
    from fastapi import HTTPException

    c, oid, audit = sqlite_audit
    trigger = "mutate_revision_" + uuid4().hex
    c.execute(
        text(
            f'CREATE TEMP TRIGGER "{trigger}" AFTER UPDATE ON main.instantorder WHEN NEW.id={oid} AND NEW.search_revision=OLD.search_revision BEGIN UPDATE instantorder SET search_revision=OLD.search_revision+7 WHERE id=NEW.id; END'
        )
    )
    c.commit()
    try:
        with Session(bind=c) as session:
            order = session.get(InstantOrder, oid)
            with pytest.raises(HTTPException) as error:
                isv._bump_search_revision(session, order)
            assert error.value.status_code == 409
            assert (
                c.execute(
                    select(InstantOrder.search_revision).where(InstantOrder.id == oid)
                ).scalar_one()
                == 7
            )
            session.rollback()
            assert _events(c, audit) == []
            assert (
                c.execute(
                    select(InstantOrder.search_revision).where(InstantOrder.id == oid)
                ).scalar_one()
                == 0
            )
    finally:
        c.rollback()
        c.execute(text(f'DROP TRIGGER "{trigger}"'))
        c.commit()


@pytest.mark.parametrize("first_commit", [False, True])
def test_sqlite_two_physical_connections_busy_then_no_lost_increment(
    bump_order, first_commit
):
    from sqlalchemy.exc import OperationalError

    if engine.dialect.name != "sqlite":
        pytest.skip("SQLite writer contention control")
    oid, _ = bump_order
    with engine.connect() as c1, engine.connect() as c2:
        assert c1.connection.driver_connection is not c2.connection.driver_connection
        original_timeout = c2.exec_driver_sql("PRAGMA busy_timeout").scalar_one()
        c2.commit()
        try:
            c2.exec_driver_sql("PRAGMA busy_timeout=1")
            c2.commit()
            with Session(bind=c1) as first, Session(bind=c2) as second:
                a = first.get(InstantOrder, oid)
                b = second.get(InstantOrder, oid)
                assert isv._bump_search_revision(first, a) == 21
                with pytest.raises(OperationalError) as error:
                    isv._bump_search_revision(second, b)
                assert (
                    error.value.orig.sqlite_errorcode & 255 == 5
                )  # SQLITE_BUSY family.
                second.rollback()  # Never blindly retry an active failed/read transaction.
                first.commit() if first_commit else first.rollback()
                fresh = second.get(InstantOrder, oid)
                assert isv._bump_search_revision(second, fresh) == (
                    22 if first_commit else 21
                )
                second.commit()
        finally:
            # Restore connection-local state even if the contention assertion or
            # helper fails; never return a poisoned timeout to the shared pool.
            c2.rollback()
            c2.exec_driver_sql(f"PRAGMA busy_timeout={int(original_timeout)}")
            c2.commit()
    with engine.connect() as c:
        assert c.execute(
            select(InstantOrder.search_revision).where(InstantOrder.id == oid)
        ).scalar_one() == (22 if first_commit else 21)


@pytest.mark.parametrize("first_commit", [False, True])
def test_pg_actual_helper_lock_wait_reads_fresh_revision(bump_order, first_commit):
    from concurrent.futures import ThreadPoolExecutor
    from threading import Event
    from time import monotonic, sleep

    if engine.dialect.name != "postgresql":
        pytest.skip("actual PG lock wait requires dedicated disposable runner")
    oid, _ = bump_order
    started = Event()
    pids = {}
    with engine.connect() as c1, Session(bind=c1) as first:
        pids["first"] = first.execute(text("SELECT pg_backend_pid()")).scalar_one()
        order = first.get(InstantOrder, oid)
        assert isv._bump_search_revision(first, order) == 21

        def worker():
            with engine.connect() as c2, Session(bind=c2) as second:
                pids["second"] = second.execute(
                    text("SELECT pg_backend_pid()")
                ).scalar_one()
                stale = second.get(InstantOrder, oid)
                assert stale.search_revision == 20
                started.set()
                try:
                    value = isv._bump_search_revision(second, stale)
                    second.commit()
                    return value
                finally:
                    second.rollback()

        with ThreadPoolExecutor(max_workers=1) as pool:
            future = pool.submit(worker)
            try:
                assert started.wait(20)
                assert pids["first"] != pids["second"]
                deadline = monotonic() + 3
                while True:
                    with engine.connect() as observer:
                        assert (
                            observer.execute(
                                text("SELECT pg_backend_pid()")
                            ).scalar_one()
                            not in pids.values()
                        )
                        blockers = observer.execute(
                            text("SELECT pg_blocking_pids(:pid)"),
                            {"pid": pids["second"]},
                        ).scalar_one()
                    if pids["first"] in blockers:
                        break
                    assert monotonic() < deadline
                    sleep(0.01)
                first.commit() if first_commit else first.rollback()
                assert future.result(timeout=5) == (22 if first_commit else 21)
            finally:
                first.rollback()
    with engine.connect() as c:
        assert c.execute(
            select(InstantOrder.search_revision).where(InstantOrder.id == oid)
        ).scalar_one() == (22 if first_commit else 21)


@pytest.mark.parametrize("kind", ["destination", "waypoints"])
def test_sqlite_route_capture_blocks_cancel_after_refresh(
    bump_order, monkeypatch, kind
):
    """Regression of saved old-helper boundary: competing cancel is now BUSY.

    The controlled interleave remains after the real route refresh. Actual
    cancellation cannot commit before the captured route writer releases lock.
    """
    import fakeredis
    from app import services
    from test_taxi_route_offer_ordering import _apply, _quote

    if engine.dialect.name != "sqlite":
        pytest.skip("SQLite ignores route SELECT FOR UPDATE")
    oid, passenger = bump_order
    redis = fakeredis.FakeStrictRedis(decode_responses=True)
    monkeypatch.setattr(isv, "_redis_override", redis)
    monkeypatch.setattr(isv, "send_push", lambda *args, **kwargs: None)
    monkeypatch.setattr(services, "send_push", lambda *args, **kwargs: None)
    seen = []
    try:
        with Session(engine) as session:
            order = session.get(InstantOrder, oid)
            before_route = (order.to_lat, order.to_lng, order.waypoints_json)
            quote = _quote(session, order, kind)
            real_refresh = session.refresh

            def after_route_refresh(instance, *args, **kwargs):
                real_refresh(instance, *args, **kwargs)
                if kwargs.get("with_for_update") and not seen:
                    from sqlalchemy.exc import OperationalError
                    from test_taxi_route_capture_locks import _fast_busy

                    with (
                        engine.connect() as contender,
                        _fast_busy(contender),
                        Session(bind=contender) as cancel_session,
                    ):
                        with pytest.raises(OperationalError) as error:
                            isv.cancel_order(
                                cancel_session,
                                oid,
                                isv.Actor.passenger,
                                passenger["id"],
                            )
                        assert error.value.orig.sqlite_errorcode & 255 == 5
                        cancel_session.rollback()
                    with engine.connect() as observer:
                        seen.append(
                            observer.execute(
                                select(InstantOrder.status).where(
                                    InstantOrder.id == oid
                                )
                            ).scalar_one()
                        )

            monkeypatch.setattr(session, "refresh", after_route_refresh)
            result = _apply(session, order, kind, quote)
            assert seen == [S.searching]
            assert result.status == S.expired
        with engine.connect() as observer:
            row = observer.execute(
                select(InstantOrder.status, InstantOrder.search_revision).where(
                    InstantOrder.id == oid
                )
            ).one()
            assert row == (S.expired, 21)
            after_route = observer.execute(
                select(
                    InstantOrder.to_lat,
                    InstantOrder.to_lng,
                    InstantOrder.waypoints_json,
                ).where(InstantOrder.id == oid)
            ).one()
            assert tuple(after_route) != before_route
            if kind == "destination":
                assert tuple(after_route[:2]) == (52.60, 58.33)
                assert after_route[2] == before_route[2]
            else:
                assert tuple(after_route[:2]) == before_route[:2]
                assert isv.parse_waypoints(after_route[2]) == [
                    {
                        "lat": 52.595,
                        "lng": 58.32,
                        "text": "Новая остановка",
                        "done": False,
                    }
                ]
    finally:
        redis.close()


@pytest.mark.parametrize("old", [MAX, -1])
def test_actual_invalid_helper_has_zero_audit_events(sqlite_audit, old):
    from fastapi import HTTPException

    c, oid, audit = sqlite_audit
    c.execute(
        update(InstantOrder).where(InstantOrder.id == oid).values(search_revision=old)
    )
    c.execute(text(f'DELETE FROM "{audit}"'))
    c.commit()
    with Session(bind=c) as session:
        order = session.get(InstantOrder, oid)
        with pytest.raises(HTTPException) as error:
            isv._bump_search_revision(session, order)
        assert error.value.status_code == 409
        assert _events(c, audit) == []
        session.rollback()
