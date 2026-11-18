"""Actual entry-writer conversion acceptance, not durable resume activation.

Downstream matcher spies isolate entry SQL/commit contracts. The invalid advance
lifecycle case deliberately uses the real matcher. PG tests require the guarded
disposable runner; SQLite contention is not a PostgreSQL locking proof.
"""

from contextlib import contextmanager, nullcontext
from concurrent.futures import ThreadPoolExecutor
from threading import Event
from time import monotonic, sleep
from uuid import uuid4

import fakeredis
import pytest
from fastapi import HTTPException
from sqlalchemy import event, select, text, update
from sqlalchemy.exc import OperationalError
from sqlmodel import Session

from app import instant_service as isv
from app.db import engine
from app.models import InstantOrder, InstantOrderStatus as S, Notification
from test_taxi_status_never_resurrects import _order
from test_taxi_search_revision_postgres import _disposable_pg_before_client  # noqa: F401

MAX = 2**63 - 1
ENTRIES = ("start", "advance", "reassign")
SOURCES = {"start": S.created, "advance": S.offered, "reassign": S.cancelled}
BAD_LIFECYCLES = [
    "",
    "A" * 32,
    "я" * 32,
    "a" * 31,
    "a" * 31 + "\0",
    "a" * 32 + "\0tail",
]


@pytest.fixture
def orders(client, user_factory, monkeypatch):
    passenger = user_factory("EntryLockPassenger")
    ids = []
    redis = fakeredis.FakeStrictRedis(decode_responses=True)
    monkeypatch.setattr(isv, "_redis_override", redis)

    def create(entry, revision=20, lifecycle=None, **kwargs):
        oid = _order(
            passenger["id"],
            SOURCES[entry],
            search_revision=revision,
            tried_lifecycle=lifecycle,
            **kwargs,
        )
        ids.append(oid)
        return oid

    try:
        yield create, passenger, redis
    finally:
        try:
            with engine.begin() as c:
                c.execute(
                    Notification.__table__.delete().where(
                        Notification.user_id == passenger["id"]
                    )
                )
                for oid in ids:
                    c.execute(
                        InstantOrder.__table__.delete().where(InstantOrder.id == oid)
                    )
        finally:
            redis.close()


@pytest.fixture
def effects(monkeypatch):
    seen = []

    def matching(session, order, *args, **kwargs):
        with engine.connect() as observer:
            row = observer.execute(
                select(InstantOrder.search_revision, InstantOrder.status).where(
                    InstantOrder.id == order.id
                )
            ).one()
        assert row == (order.search_revision, S.searching)
        seen.append(("matcher", order.search_revision))
        return order

    monkeypatch.setattr(isv, "try_offer_next", matching)
    monkeypatch.setattr(isv, "_notify_reassign", lambda *args: seen.append(("notify",)))
    monkeypatch.setattr(isv, "_remember_tried", lambda *args: seen.append(("tried",)))
    return seen


def _call(session, order, entry, oid, **kwargs):
    if entry == "start":
        return isv.start_matching(session, order)
    if entry == "advance":
        return isv.advance_after_no_accept(session, order, **kwargs)
    return isv._reassign_after_driver_cancel(session, oid, None)


def _read(oid):
    with engine.connect() as c:
        return dict(
            c.execute(select(InstantOrder.__table__).where(InstantOrder.id == oid))
            .mappings()
            .one()
        )


@contextmanager
def _audit(oid):
    if engine.dialect.name != "sqlite":
        pytest.skip("SQLite no-op trigger audit")
    suffix = uuid4().hex
    table = "entry_audit_" + suffix
    trigger = "entry_trigger_" + suffix
    with engine.connect() as c:
        c.execute(
            text(f'CREATE TEMP TABLE "{table}" (old_value INTEGER,new_value INTEGER)')
        )
        c.execute(
            text(
                f'CREATE TEMP TRIGGER "{trigger}" AFTER UPDATE ON main.instantorder WHEN NEW.id={oid} BEGIN INSERT INTO "{table}" VALUES(OLD.search_revision,NEW.search_revision); END'
            )
        )
        c.commit()
        try:
            yield c, table
        finally:
            c.rollback()
            c.execute(text(f'DROP TRIGGER "{trigger}"'))
            c.execute(text(f'DROP TABLE "{table}"'))
            c.commit()


@pytest.mark.parametrize("entry", ENTRIES)
@pytest.mark.parametrize("revision", [None, 0, MAX - 1, MAX, -1])
def test_actual_entry_bigint_boundary_commit_semantics(
    orders, effects, entry, revision
):
    create, passenger, _ = orders
    oid = create(entry, revision)
    before = _read(oid)
    with Session(engine) as session:
        order = session.get(InstantOrder, oid)
        session.add(Notification(user_id=passenger["id"], title_ru="Synthetic pending"))
        result = _call(session, order, entry, oid)
        assert (
            result is None
            if entry == "reassign" and revision in (MAX, -1)
            else result is not None
        )
    after = _read(oid)
    if revision in (MAX, -1):
        assert after == before and effects == []
    else:
        assert after["status"] == S.searching and after["search_revision"] == (
            1 if revision is None else revision + 1
        )
        assert ("matcher", after["search_revision"]) in effects
    with engine.connect() as c:
        assert (
            len(
                c.execute(
                    select(Notification.id).where(
                        Notification.user_id == passenger["id"]
                    )
                ).all()
            )
            == 1
        )


@pytest.mark.parametrize("entry", ENTRIES)
@pytest.mark.parametrize("expire", [False, True])
def test_missing_noop_still_commits_unrelated_work(orders, effects, entry, expire):
    create, passenger, _ = orders
    oid = create(entry)
    with Session(engine, expire_on_commit=False) as session:
        order = session.get(InstantOrder, oid)
        session.commit()
        with engine.begin() as c:
            c.execute(InstantOrder.__table__.delete().where(InstantOrder.id == oid))
        session.expire_on_commit = expire
        session.add(Notification(user_id=passenger["id"], title_ru="Missing owner"))
        result = _call(session, order, entry, oid)
        if entry == "reassign" or expire:
            assert result is None
        else:
            # Existing get() semantics may return the retained identity-map
            # object. It is not evidence the deleted DB owner exists.
            assert result is order
    assert effects == []
    with engine.connect() as c:
        assert (
            c.execute(select(InstantOrder.id).where(InstantOrder.id == oid)).first()
            is None
        )
        assert (
            len(
                c.execute(
                    select(Notification.id).where(
                        Notification.user_id == passenger["id"]
                    )
                ).all()
            )
            == 1
        )


@pytest.mark.parametrize("entry", ENTRIES)
def test_stale_identity_uses_fresh_scalar_owner(orders, effects, entry):
    create, _, _ = orders
    oid = create(entry)
    with Session(engine, expire_on_commit=False) as session:
        order = session.get(InstantOrder, oid)
        session.commit()
        with engine.begin() as c:
            c.execute(
                update(InstantOrder)
                .where(InstantOrder.id == oid)
                .values(search_revision=27)
            )
        assert order.search_revision == 20
        assert _call(session, order, entry, oid).search_revision == 28
    assert _read(oid)["search_revision"] == 28


@pytest.mark.parametrize("entry", ENTRIES)
@pytest.mark.parametrize("expired", [False, True])
def test_dirty_revision_is_no_sql_no_commit_even_expired_identity(
    orders, effects, entry, expired
):
    create, _, _ = orders
    oid = create(entry)
    sql = []

    def capture(*args):
        sql.append(args[2])

    with Session(engine) as session:
        order = session.get(InstantOrder, oid)
        if expired:
            session.expire(order)
        order.search_revision = 44
        event.listen(engine, "before_cursor_execute", capture)
        try:
            with pytest.raises(HTTPException) as error:
                _call(session, order, entry, oid)
            assert error.value.status_code == 409 and sql == []
            assert order.search_revision == 44 and session.in_transaction()
        finally:
            event.remove(engine, "before_cursor_execute", capture)
            session.rollback()
    assert _read(oid)["search_revision"] == 20 and effects == []


@pytest.mark.parametrize("entry", ["start", "reassign"])
@pytest.mark.parametrize("lifecycle", [None, "a" * 32] + BAD_LIFECYCLES)
def test_lifecycle_guard_exact_sqlite_bytes_and_trigger_observability(
    orders, effects, entry, lifecycle
):
    # PG rejects NUL and overlength VARCHAR before the helper can run. This
    # matrix observes SQLite byte/trigger semantics only; skip before seeding.
    if engine.dialect.name != "sqlite":
        pytest.skip("SQLite lifecycle storage/trigger matrix; no PG invalid seed")
    create, _, _ = orders
    oid = create(entry, lifecycle=lifecycle)
    before = _read(oid)
    valid = lifecycle is None or lifecycle == "a" * 32
    with _audit(oid) as (c, audit), Session(bind=c) as session:
        result = _call(session, session.get(InstantOrder, oid), entry, oid)
        records = [
            tuple(row)
            for row in c.execute(text(f'SELECT old_value,new_value FROM "{audit}"'))
        ]
        assert records == ([(20, 20), (20, 21)] if valid else [])
        if valid:
            assert result.search_revision == 21
        else:
            assert effects == [] and _read(oid) == before


@pytest.mark.parametrize("guard", ["driver", "token", "offered_at", "expires_at"])
def test_advance_optional_guard_loser_zero_trigger_effects(orders, effects, guard):
    from datetime import timedelta
    from app.timeutil import utcnow

    create, _, _ = orders
    now = utcnow()
    oid = create(
        "advance",
        offer_token="a" * 32,
        offered_at=now,
        offer_expires_at=now + timedelta(seconds=30),
    )
    kwargs = {
        "driver": {"expected_driver_id": 999999},
        "token": {"expected_offer_token": "b" * 32},
        "offered_at": {
            "expected_offer_times": (
                now - timedelta(seconds=1),
                now + timedelta(seconds=30),
            )
        },
        "expires_at": {"expected_offer_times": (now, now + timedelta(seconds=31))},
    }[guard]
    before = _read(oid)
    with _audit(oid) as (c, audit), Session(bind=c) as session:
        _call(session, session.get(InstantOrder, oid), "advance", oid, **kwargs)
        assert c.execute(text(f'SELECT * FROM "{audit}"')).all() == []
    assert _read(oid) == before and effects == []


@pytest.mark.parametrize("lifecycle", BAD_LIFECYCLES)
def test_advance_preserves_invalid_lifecycle_baseline_no_actual_lookup(
    orders, monkeypatch, lifecycle
):
    if engine.dialect.name == "postgresql" and (
        "\0" in lifecycle or len(lifecycle) > 32
    ):
        pytest.skip(
            "PostgreSQL text forbids NUL and VARCHAR(32) rejects overlength before service"
        )
    # Empty/uppercase/nonASCII32/short text are representable on PG and still
    # exercise the actual advance compatibility behavior there.
    create, _, _ = orders
    oid = create("advance", lifecycle=lifecycle)
    lookups = []
    monkeypatch.setattr(isv, "candidates", lambda *args: lookups.append(True))
    with Session(engine) as session:
        result = isv.advance_after_no_accept(session, session.get(InstantOrder, oid))
        assert result.status == S.searching and result.search_revision == 21
    assert _read(oid)["tried_lifecycle"] == lifecycle and lookups == []


@pytest.mark.parametrize("entry", ENTRIES)
@pytest.mark.parametrize("mode", ["normal", "autoflush_false", "context"])
def test_flush_policy_preserves_unrelated_dirty_and_new_objects(
    orders, effects, entry, mode
):
    create, passenger, _ = orders
    oid = create(entry)
    observations = []
    active = [False]

    def observe(connection, cursor, statement, parameters, context, executemany):
        upper = statement.upper()
        if (
            active[0]
            and "INSTANTORDER" in upper
            and ("RETURNING" in upper or "FOR UPDATE" in upper)
        ):
            active[0] = False
            observations.append(
                connection.execute(
                    select(InstantOrder.to_text).where(InstantOrder.id == oid)
                ).scalar_one()
            )

    with Session(engine, autoflush=mode != "autoflush_false") as session:
        order = session.get(InstantOrder, oid)
        old_text = order.to_text
        order.to_text = "Local dirty route"
        session.add(
            Notification(user_id=passenger["id"], title_ru="Pending new object")
        )
        event.listen(engine, "after_cursor_execute", observe)
        try:
            active[0] = True
            with session.no_autoflush if mode == "context" else nullcontext():
                result = _call(session, order, entry, oid)
            assert (
                result.search_revision == 21 and result.to_text == "Local dirty route"
            )
        finally:
            event.remove(engine, "after_cursor_execute", observe)
    assert observations == ["Local dirty route" if mode == "normal" else old_text]
    assert _read(oid)["to_text"] == "Local dirty route"


@pytest.mark.parametrize("entry", ENTRIES)
def test_postcommit_superseded_owner_has_no_continuation_effects(
    orders, effects, monkeypatch, entry
):
    create, _, _ = orders
    oid = create(entry)
    with Session(engine) as session:
        order = session.get(InstantOrder, oid)
        real_commit = session.commit
        once = []

        def commit_then_new_owner():
            real_commit()
            if not once:
                once.append(True)
                with engine.begin() as c:
                    c.execute(
                        update(InstantOrder)
                        .where(InstantOrder.id == oid)
                        .values(status=S.cancelled, search_revision=22)
                    )

        monkeypatch.setattr(session, "commit", commit_then_new_owner)
        _call(session, order, entry, oid)
    assert _read(oid)["search_revision"] == 22 and effects == []


@pytest.mark.parametrize("entry", ENTRIES)
def test_same_generation_lifecycle_cas_loser_has_no_effects(orders, effects, entry):
    create, _, _ = orders
    oid = create(entry)
    with _audit(oid) as (c, audit):
        name = "entry_supersede_" + uuid4().hex
        c.execute(
            text(f'''CREATE TEMP TRIGGER "{name}" AFTER UPDATE ON main.instantorder
            WHEN NEW.id={oid} AND NEW.search_revision=OLD.search_revision
            AND NEW.tried_lifecycle IS OLD.tried_lifecycle
            BEGIN UPDATE instantorder SET tried_lifecycle='{"b" * 32}' WHERE id=NEW.id; END''')
        )
        c.commit()
        try:
            with Session(bind=c) as session:
                _call(session, session.get(InstantOrder, oid), entry, oid)
            row = _read(oid)
            assert row["search_revision"] == 20 and row["status"] == SOURCES[entry]
            assert row["tried_lifecycle"] == "b" * 32 and effects == []
        finally:
            c.rollback()
            c.execute(text(f'DROP TRIGGER "{name}"'))
            c.commit()


@pytest.mark.parametrize("entry", ENTRIES)
@pytest.mark.parametrize("commit", [False, True])
def test_sqlite_busy_rollback_then_fresh_increment(orders, effects, entry, commit):
    if engine.dialect.name != "sqlite":
        pytest.skip("SQLite writer-lock behavior")
    create, _, _ = orders
    oid = create(entry)
    with engine.connect() as c1, engine.connect() as c2:
        assert c1.connection.driver_connection is not c2.connection.driver_connection
        timeout = c2.exec_driver_sql("PRAGMA busy_timeout").scalar_one()
        c2.commit()
        try:
            c2.exec_driver_sql("PRAGMA busy_timeout=1")
            c2.commit()
            with Session(bind=c1) as first, Session(bind=c2) as second:
                owner = first.get(InstantOrder, oid)
                stale = second.get(InstantOrder, oid)
                assert isv._bump_search_revision(first, owner) == 21
                with pytest.raises(OperationalError) as error:
                    _call(second, stale, entry, oid)
                assert error.value.orig.sqlite_errorcode & 255 == 5
                second.rollback()
                first.commit() if commit else first.rollback()
                assert _call(
                    second, second.get(InstantOrder, oid), entry, oid
                ).search_revision == (22 if commit else 21)
        finally:
            c2.rollback()
            c2.exec_driver_sql(f"PRAGMA busy_timeout={int(timeout)}")
            c2.commit()
    assert _read(oid)["search_revision"] == (22 if commit else 21)


@pytest.mark.parametrize("entry", ENTRIES)
@pytest.mark.parametrize("commit", [False, True])
def test_pg_entry_waits_on_actual_writer_and_reads_fresh_owner(
    orders, effects, entry, commit
):
    if engine.dialect.name != "postgresql":
        pytest.skip("guarded disposable PostgreSQL only")
    create, _, _ = orders
    oid = create(entry)
    started = Event()
    pids = {}
    with engine.connect() as c1, Session(bind=c1) as first:
        pids["first"] = first.execute(text("SELECT pg_backend_pid()")).scalar_one()
        assert isv._bump_search_revision(first, first.get(InstantOrder, oid)) == 21

        def worker():
            with engine.connect() as c2, Session(bind=c2) as second:
                pids["second"] = second.execute(
                    text("SELECT pg_backend_pid()")
                ).scalar_one()
                stale = second.get(InstantOrder, oid)
                assert stale.search_revision == 20
                started.set()
                try:
                    return _call(second, stale, entry, oid).search_revision
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
                        blocked = observer.execute(
                            text("SELECT pg_blocking_pids(:pid)"),
                            {"pid": pids["second"]},
                        ).scalar_one()
                    if pids["first"] in blocked:
                        break
                    assert monotonic() < deadline
                    sleep(0.01)
                first.commit() if commit else first.rollback()
                assert future.result(timeout=5) == (22 if commit else 21)
            finally:
                first.rollback()
    assert _read(oid)["search_revision"] == (22 if commit else 21)


@pytest.mark.parametrize("entry", ENTRIES)
def test_clean_expired_identity_supported(orders, effects, entry):
    create, _, _ = orders
    oid = create(entry)
    with Session(engine) as session:
        order = session.get(InstantOrder, oid)
        session.expire(order)
        result = _call(session, order, entry, oid)
        assert result.search_revision == 21
    assert ("matcher", 21) in effects


@pytest.mark.parametrize("entry", ENTRIES)
def test_normal_autoflush_dirty_status_is_respected_before_owner_read(
    orders, effects, entry
):
    create, _, _ = orders
    oid = create(entry)
    with Session(engine) as session:
        order = session.get(InstantOrder, oid)
        order.status = S.done
        _call(session, order, entry, oid)
    assert _read(oid)["status"] == S.done
    assert _read(oid)["search_revision"] == 20 and effects == []


@pytest.mark.parametrize("entry", ["start", "reassign"])
def test_normal_autoflush_dirty_invalid_lifecycle_is_not_ignored(
    orders, effects, entry
):
    create, _, _ = orders
    oid = create(entry)
    with Session(engine) as session:
        order = session.get(InstantOrder, oid)
        order.tried_lifecycle = "INVALID"
        _call(session, order, entry, oid)
    after = _read(oid)
    assert after["status"] == SOURCES[entry] and after["search_revision"] == 20
    assert after["tried_lifecycle"] == "INVALID" and effects == []


@pytest.mark.parametrize(
    "status",
    [
        S.created,
        S.scheduled,
        S.searching,
        S.expired,
        S.offered,
        S.accepted,
        S.arriving,
        S.onboard,
        S.done,
        S.cancelled,
    ],
)
def test_start_source_status_contract(orders, effects, status):
    create, _, _ = orders
    oid = create("start")
    with engine.begin() as c:
        c.execute(
            update(InstantOrder).where(InstantOrder.id == oid).values(status=status)
        )
    with Session(engine) as session:
        result = isv.start_matching(session, session.get(InstantOrder, oid))
    if status in (S.created, S.scheduled, S.searching, S.expired):
        assert result.status == S.searching and result.search_revision == 21
        assert ("matcher", 21) in effects
    else:
        assert (
            result.status == status and result.search_revision == 20 and effects == []
        )


@pytest.mark.parametrize("entry", ["start", "reassign"])
@pytest.mark.parametrize("raw", [b"a" * 32, b"\0" * 32])
def test_raw_sqlite_blob_lifecycle_rejected_before_noop_update(
    orders, effects, entry, raw
):
    if engine.dialect.name != "sqlite":
        pytest.skip("SQLite storage-class guard")
    create, _, _ = orders
    oid = create(entry)
    with engine.begin() as c:
        c.exec_driver_sql(
            "UPDATE instantorder SET tried_lifecycle=? WHERE id=?", (raw, oid)
        )
        assert (
            c.exec_driver_sql(
                "SELECT typeof(tried_lifecycle) FROM instantorder WHERE id=?", (oid,)
            ).scalar_one()
            == "blob"
        )
    before = _read(oid)
    with _audit(oid) as (c, audit), Session(bind=c) as session:
        _call(session, session.get(InstantOrder, oid), entry, oid)
        assert c.execute(text(f'SELECT * FROM "{audit}"')).all() == []
    assert _read(oid) == before and effects == []


@pytest.mark.parametrize("raw", [b"a" * 32, b"\0" * 32])
def test_raw_blob_advance_baseline_still_searching_without_lookup(
    orders, monkeypatch, raw
):
    if engine.dialect.name != "sqlite":
        pytest.skip("SQLite blob compatibility diagnostic")
    create, _, _ = orders
    oid = create("advance")
    with engine.begin() as c:
        c.exec_driver_sql(
            "UPDATE instantorder SET tried_lifecycle=? WHERE id=?", (raw, oid)
        )
    calls = []
    monkeypatch.setattr(isv, "candidates", lambda *args: calls.append(True))
    with Session(engine) as session:
        result = isv.advance_after_no_accept(session, session.get(InstantOrder, oid))
        assert result.status == S.searching and result.search_revision == 21
    assert _read(oid)["tried_lifecycle"] == raw and calls == []


def test_advance_loser_with_null_search_owner_does_not_initialize_or_continue(
    orders, effects
):
    create, _, _ = orders
    oid = create("advance", None)
    with Session(engine, expire_on_commit=False) as session:
        stale = session.get(InstantOrder, oid)
        session.commit()
        with engine.begin() as c:
            c.execute(
                update(InstantOrder)
                .where(InstantOrder.id == oid)
                .values(status=S.searching)
            )
        before = _read(oid)
        isv.advance_after_no_accept(session, stale)
    assert _read(oid) == before and effects == []


def test_start_real_cas_loser_null_search_owner_has_no_continuation(orders, effects):
    create, _, _ = orders
    oid = create("start", None)
    with _audit(oid) as (c, audit):
        name = "null_supersede_" + uuid4().hex
        c.execute(
            text(f'''CREATE TEMP TRIGGER "{name}" AFTER UPDATE ON main.instantorder
            WHEN NEW.id={oid} AND OLD.status='created' AND NEW.status='created'
            BEGIN UPDATE instantorder SET status='searching' WHERE id=NEW.id; END''')
        )
        c.commit()
        try:
            with Session(bind=c) as session:
                isv.start_matching(session, session.get(InstantOrder, oid))
            row = _read(oid)
            assert row["status"] == S.searching and row["search_revision"] is None
            assert row["tried_lifecycle"] is None and effects == []
        finally:
            c.rollback()
            c.execute(text(f'DROP TRIGGER "{name}"'))
            c.commit()
