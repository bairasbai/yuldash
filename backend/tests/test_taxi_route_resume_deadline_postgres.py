"""Real PostgreSQL writer-lock deadline verification for the inactive primitive."""
import os
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta

import pytest
from psycopg2.extensions import TRANSACTION_STATUS_IDLE, TRANSACTION_STATUS_INTRANS
from sqlalchemy import create_engine, delete, event, select, text, update

from app import taxi_route_resume as resume
from app.db import engine
from app.models import InstantOrder, InstantOrderStatus as S
from test_taxi_route_resume_inert import _row, _seed


pytestmark = pytest.mark.skipif(
    engine.dialect.name != "postgresql" or os.environ.get("AUDIT_PG_CI_SERVICE") != "1",
    reason="isolated PostgreSQL CI service required",
)

_CLOCK = text("SELECT timezone('UTC', clock_timestamp())")
_ORDER = InstantOrder.__table__


@pytest.fixture
def guarded_postgres(request):
    # Do not permit app TestClient init or any fixture writes on an unknown
    # DSN. Use the exact supplied CI database, not its migration database.
    url = engine.url
    assert (url.drivername == "postgresql+psycopg2" and url.username == "yuldash"
            and url.host in ("localhost", "127.0.0.1", "::1")
            and url.database == "yuldash_run"
            and url.port == 5432 and not url.query), "expected the disposable CI PostgreSQL DSN"
    with engine.connect() as conn:
        # The CI-published port may map loopback on the runner to a
        # non-loopback server address inside the PostgreSQL container.
        db, role = conn.execute(text("SELECT current_database(), current_user")).one()
        assert (db, role) == ("yuldash_run", "yuldash")
    order_id, _, candidate = _seed(request.getfixturevalue("user_factory"))
    try:
        yield order_id, candidate
    finally:
        with engine.begin() as conn:
            conn.execute(delete(_ORDER).where(_ORDER.c.id == order_id))


def _clock(conn):
    return conn.execute(_CLOCK).scalar_one()


def _until(predicate, failure, max_seconds=12):
    end = time.monotonic() + max_seconds
    while time.monotonic() < end:
        if predicate():
            return
        time.sleep(0.015)
    pytest.fail(failure)


@pytest.mark.parametrize("operation", ("claim", "offer"))
@pytest.mark.parametrize("cross_deadline", (False, True), ids=("within-window", "after-deadline"))
def test_real_order_lock_uses_database_deadline_at_actual_write(guarded_postgres, operation, cross_deadline):
    order_id, candidate = guarded_postgres
    with engine.begin() as setup:
        retired_at = _clock(setup)
        deadline = retired_at + timedelta(seconds=8 if cross_deadline else 30)
        setup.execute(update(_ORDER).where(_ORDER.c.id == order_id).values(
            route_resume_retired_at=retired_at, route_resume_deadline_at=deadline,
            route_resume_ready_at=retired_at,
        ))
    expected = resume.observe(engine, order_id)
    assert expected is not None
    if operation == "offer":
        expected = resume.claim(engine, expected, lease_seconds=120)
        assert expected is not None
        assert _row(order_id).route_resume_ready_at == deadline

    attempted = threading.Event()
    contender = {}

    def track_row_lock(conn, cursor, statement, parameters, context, executemany):
        if (threading.get_ident() == contender.get("thread")
                and "FOR UPDATE" in statement.upper() and "instantorder" in statement.lower()):
            contender["pid"] = conn.connection.driver_connection.get_backend_pid()
            attempted.set()

    def invoke():
        contender["thread"] = threading.get_ident()
        if operation == "claim":
            return resume.claim(engine, expected, lease_seconds=120)
        return resume.store_offer(engine, expected, driver_id=candidate, offer_seconds=120)

    with ThreadPoolExecutor(max_workers=1) as pool, engine.connect() as blocker:
        blocker.execute(select(_ORDER.c.id).where(_ORDER.c.id == order_id).with_for_update()).one()
        blocker_pid = blocker.exec_driver_sql("SELECT pg_backend_pid()").scalar_one()
        event.listen(engine, "before_cursor_execute", track_row_lock)
        try:
            write = pool.submit(invoke)
            try:
                assert attempted.wait(8), "primitive never issued its real row-lock statement"
                with engine.connect() as observer:
                    observer_pid = observer.exec_driver_sql("SELECT pg_backend_pid()").scalar_one()
                    assert len({blocker_pid, contender["pid"], observer_pid}) == 3

                    def actually_blocked():
                        if write.done():
                            write.result()
                            pytest.fail("primitive completed without waiting on the held order lock")
                        return observer.execute(text(
                            "SELECT :blocker = ANY(pg_blocking_pids(:contender))"
                        ), {"blocker": blocker_pid, "contender": contender["pid"]}).scalar_one()

                    _until(actually_blocked, "PostgreSQL did not report the expected blocking edge")
                    assert _clock(observer) < deadline, "fixture never proved the pre-deadline lock wait"
                    if cross_deadline:
                        _until(lambda: _clock(observer) >= deadline,
                               "server clock never reached the absolute deadline")
                        assert actually_blocked(), "contender stopped waiting before the late release"
                    else:
                        assert _clock(observer) < deadline
            finally:
                # Release an unchanged Order. A recheck based only on a
                # concurrent row mutation would miss this write-time hazard.
                blocker.rollback()
            result = write.result(timeout=8)
        finally:
            event.remove(engine, "before_cursor_execute", track_row_lock)

    saved = _row(order_id)
    assert saved.route_resume_deadline_at == deadline and saved.route_resume_retired_at == retired_at
    if cross_deadline:
        assert result is None
        assert resume.observe(engine, order_id) == expected
        assert (saved.status, saved.current_offer_driver_id, saved.search_round) == (S.searching, None, 2)
    elif operation == "claim":
        assert result is not None and result == resume.observe(engine, order_id)
        assert saved.route_resume_claim_id and saved.route_resume_ready_at == deadline
        assert (saved.status, saved.current_offer_driver_id, saved.search_round) == (S.searching, None, 2)
    else:
        assert result is not None and result == resume.observe(engine, order_id)
        assert (saved.status, saved.current_offer_driver_id, saved.search_round) == (S.offered, candidate, 3)
        assert saved.offered_at < saved.offer_expires_at == deadline


@pytest.mark.parametrize("source", ("driver", "engine"))
def test_autocommit_configuration_refused_before_postgres_row_lock(guarded_postgres, source):
    order_id, _ = guarded_postgres
    expected = resume.observe(engine, order_id)
    assert expected is not None
    options = {"isolation_level": "AUTOCOMMIT"} if source == "engine" else {}
    bad = create_engine(engine.url, hide_parameters=True, **options)
    if source == "driver":
        def set_driver_mode(raw, record):
            raw.autocommit = True
        event.listen(bad, "checkout", set_driver_mode)
    seen = []

    def watch(conn, cursor, statement, parameters, context, executemany):
        seen.append(statement)

    event.listen(bad, "before_cursor_execute", watch)
    try:
        with pytest.raises(ValueError, match="Unsupported route resume transaction mode"):
            resume.claim(bad, expected, lease_seconds=30)
        assert seen == []  # notably no SELECT FOR UPDATE or UPDATE
        assert resume.observe(engine, order_id) == expected
        result = resume.claim(engine, expected, lease_seconds=30)
        assert result is not None and resume.observe(engine, order_id) == result
    finally:
        bad.dispose()


@pytest.mark.parametrize("hook", ("checkout", "begin"))
def test_preopened_psycopg_transaction_is_not_borrowed_or_committed(guarded_postgres, hook):
    order_id, _ = guarded_postgres
    expected = resume.observe(engine, order_id)
    assert expected is not None
    before_ack = _row(order_id).destination_ack_at
    bad = create_engine(engine.url, hide_parameters=True)
    borrowed = []
    seen = []

    def start_raw(raw):
        assert raw.autocommit is False
        assert raw.get_transaction_status() == TRANSACTION_STATUS_IDLE
        # Actual unrelated pending DML in that physical transaction. Because
        # this field is not in the resume owner tuple, this cannot masquerade
        # as ordinary stale-observation refusal.
        with raw.cursor() as cursor:
            cursor.execute("UPDATE instantorder SET destination_ack_at = "
                           "timezone('UTC', clock_timestamp()) WHERE id = %s", (order_id,))
            assert cursor.rowcount == 1
        assert raw.get_transaction_status() == TRANSACTION_STATUS_INTRANS
        borrowed.append(raw)

    if hook == "checkout":
        event.listen(bad, "checkout", lambda raw, *_: start_raw(raw))
    else:
        event.listen(bad, "begin", lambda conn: start_raw(conn.connection.driver_connection))

    def witness(conn, cursor, statement, parameters, context, executemany):
        seen.append(statement)

    event.listen(bad, "before_cursor_execute", witness)
    try:
        with pytest.raises(ValueError, match="Unsupported route resume transaction mode"):
            resume.claim(bad, expected, lease_seconds=30)
        assert len(borrowed) == 1 and borrowed[0].closed
        assert seen == []  # no primitive SELECT FOR UPDATE or UPDATE
        assert _row(order_id).destination_ack_at == before_ack
        assert resume.observe(engine, order_id) == expected
        called = resume.claim(engine, expected, lease_seconds=30)
        assert called and resume.observe(engine, order_id) == called
    finally:
        bad.dispose()
