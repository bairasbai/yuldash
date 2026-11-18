"""Limited server-read wait CAS; separate strict diagnostics retain episode gaps."""

from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta
from threading import Event

import pytest
from fastapi import HTTPException
from sqlalchemy import event, inspect, text, update
from sqlmodel import Session

from app import instant_service as isv, taxi_worker
from app.db import engine
from app.models import InstantOrder, InstantOrderStatus as S
from app.routers import instant as router
import test_taxi_terminal_pending_clear as terminal
from test_taxi_search_revision_postgres import _disposable_pg_before_client  # noqa: F401

seeded = terminal.seeded
terminal_case = terminal.terminal_case


def conflict(error):
    assert error.status_code == 409 and error.detail["code"] == "wait_snapshot_conflict"


@pytest.mark.parametrize("status", [S.expired, S.searching])
@pytest.mark.parametrize("revision", [None, 0, 2**63 - 1])
def test_actual_wait_success_does_not_change_generation_or_pending(
    terminal_case, client, monkeypatch, status, revision
):
    make, passenger, _, now, _ = terminal_case
    oid, before = make("wait_end", status=status, search_revision=revision)
    monkeypatch.setattr(router, "utcnow", lambda: now)
    response = client.post(f"/instant/orders/{oid}/wait", headers=passenger["auth"])
    assert response.status_code == 200, response.text
    after = terminal.snapshot(oid)
    assert after["wait_until"] == now + timedelta(
        minutes=isv.settings.order_wait_max_min
    )
    assert after["search_revision"] == revision and terminal.pending(
        after
    ) == terminal.pending(before)


@pytest.mark.parametrize(
    "case,code", [("noauth", 401), ("foreign", 403), ("missing", 404), ("status", 409)]
)
def test_actual_wait_original_errors(terminal_case, client, case, code):
    make, passenger, driver, _, _ = terminal_case
    oid, before = make("accept" if case == "status" else "wait_end")
    response = client.post(
        f"/instant/orders/{oid if case != 'missing' else -999}/wait",
        headers={}
        if case == "noauth"
        else (driver if case == "foreign" else passenger)["auth"],
    )
    assert response.status_code == code
    assert response.json().get("detail", {}) != {"code": "wait_snapshot_conflict"}
    assert terminal.snapshot(oid) == before


@pytest.mark.parametrize("field", router._WAIT_SOURCE_FIELDS + ("deleted",))
def test_passive_dirty_source_guard_before_sql_or_commit(terminal_case, field):
    make, passenger, driver, now, _ = terminal_case
    oid, before = make("wait_end")
    with engine.connect() as c:
        with Session(bind=c) as session:
            order = session.get(InstantOrder, oid)
            values = {
                "id": oid + 100000,
                "passenger_id": driver["id"],
                "status": S.searching,
                "search_revision": 42,
                "wait_until": now + timedelta(hours=1),
            }
            if field == "deleted":
                session.delete(order)
            else:
                setattr(order, field, values[field])
            sql, commits = [], []

            def query(*args):
                sql.append(args[2])

            def commit(*args):
                commits.append(True)

            event.listen(c, "before_cursor_execute", query)
            event.listen(session, "before_commit", commit)
            try:
                with pytest.raises(HTTPException) as error:
                    router._capture_wait_snapshot(session, oid, passenger["id"])
                conflict(error.value)
                assert sql == commits == []
            finally:
                event.remove(c, "before_cursor_execute", query)
                event.remove(session, "before_commit", commit)
                session.rollback()
    assert terminal.snapshot(oid) == before


@pytest.mark.parametrize(
    "mode", ["same_value", "unloaded", "stale_clean", "pending_dirty"]
)
def test_snapshot_no_adoption_success_sync_and_unrelated_history(terminal_case, mode):
    make, passenger, _, now, _ = terminal_case
    oid, before = make("wait_end")
    with Session(engine, expire_on_commit=False) as session:
        order = session.get(InstantOrder, oid)
        session.commit()
        if mode == "stale_clean":
            with engine.begin() as other:
                other.execute(
                    update(InstantOrder)
                    .where(InstantOrder.id == oid)
                    .values(wait_until=now)
                )
        if mode == "unloaded":
            session.expire(order, ["wait_until"])
        if mode == "same_value":
            for name in router._WAIT_SOURCE_FIELDS:
                setattr(order, name, getattr(order, name))
        if mode == "pending_dirty":
            order.matching_resume_attempts = 99
        order.from_text = "unrelated wait caller text"
        history = inspect(order).attrs.from_text.history
        pending_history = inspect(order).attrs.matching_resume_attempts.history
        snapshot = router._capture_wait_snapshot(session, oid, passenger["id"])
        assert inspect(order).attrs.from_text.history == history
        assert inspect(order).attrs.matching_resume_attempts.history == pending_history
        if mode == "unloaded":
            assert "wait_until" in inspect(order).unloaded
        deadline = now + timedelta(minutes=10)
        router._apply_wait_snapshot(session, snapshot, deadline)
        assert (
            order.wait_until == deadline
            and not inspect(order).attrs.wait_until.history.has_changes()
        )
        assert inspect(order).attrs.from_text.history == history
        assert inspect(order).attrs.matching_resume_attempts.history == pending_history
        session.commit()
        session.commit()
    after = terminal.snapshot(oid)
    assert (
        after["wait_until"] == deadline
        and after["from_text"] == "unrelated wait caller text"
    )
    assert after["matching_resume_attempts"] == (
        99 if mode == "pending_dirty" else before["matching_resume_attempts"]
    )


def test_absent_target_not_loaded_until_success_payload_and_flushed_delete_404(
    terminal_case,
):
    make, passenger, _, now, _ = terminal_case
    oid, _ = make("wait_end")
    with Session(engine) as session:
        snapshot = router._capture_wait_snapshot(session, oid, passenger["id"])
        router._apply_wait_snapshot(session, snapshot, now)
        assert session.identity_key(InstantOrder, oid) not in session.identity_map
        session.rollback()
        order = session.get(InstantOrder, oid)
        session.delete(order)
        session.flush()
        assert session.identity_key(InstantOrder, oid) not in session.identity_map
        with pytest.raises(HTTPException) as error:
            router._capture_wait_snapshot(session, oid, passenger["id"])
        assert error.value.status_code == 404
        session.rollback()


@pytest.mark.parametrize("changed", router._WAIT_SOURCE_FIELDS[1:])
def test_actual_zero_row_conflict_no_commit_no_identity_sync(terminal_case, changed):
    make, passenger, driver, now, _ = terminal_case
    oid, before = make("wait_end")
    with Session(engine, expire_on_commit=False) as session:
        order = session.get(InstantOrder, oid)
        snapshot = router._capture_wait_snapshot(session, oid, passenger["id"])
        session.commit()  # Separate real winner; this is a CAS control, not a lockwait claim.
        values = {
            "passenger_id": driver["id"],
            "status": S.searching,
            "search_revision": 42,
            "wait_until": now,
        }
        with engine.begin() as c:
            c.execute(
                update(InstantOrder)
                .where(InstantOrder.id == oid)
                .values(**{changed: values[changed]})
            )
        order.from_text = "must not commit on conflict"
        history = inspect(order).attrs.from_text.history
        with pytest.raises(HTTPException) as error:
            router._apply_wait_snapshot(session, snapshot, now + timedelta(hours=1))
        conflict(error.value)
        assert order.wait_until == before["wait_until"]
        assert inspect(order).attrs.from_text.history == history
        session.rollback()
    assert terminal.snapshot(oid)["from_text"] == before["from_text"]


@pytest.mark.parametrize("suffix", ["", ".000000", ".000"])
def test_sqlite_raw_timestamp_same_row_exact_spelling(terminal_case, suffix):
    if engine.dialect.name != "sqlite":
        pytest.skip("SQLite raw storage")
    make, passenger, _, now, _ = terminal_case
    oid, _ = make("wait_end")
    raw = now.replace(microsecond=0).strftime("%Y-%m-%d %H:%M:%S") + suffix
    with engine.begin() as c:
        c.execute(
            text("UPDATE instantorder SET wait_until=:raw WHERE id=:id"),
            {"raw": raw, "id": oid},
        )
    with Session(engine) as session:
        snapshot = router._capture_wait_snapshot(session, oid, passenger["id"])
        assert snapshot.wait_raw == raw and snapshot.wait_storage == "text"
        router._apply_wait_snapshot(session, snapshot, now)
        session.commit()
    assert terminal.snapshot(oid)["wait_until"] == now


def test_sqlite_same_decoded_different_raw_is_conflict(terminal_case):
    if engine.dialect.name != "sqlite":
        pytest.skip("SQLite raw storage")
    make, passenger, _, now, _ = terminal_case
    oid, _ = make("wait_end", wait_until=now.replace(microsecond=0))
    with Session(engine) as session:
        snapshot = router._capture_wait_snapshot(session, oid, passenger["id"])
        session.commit()
        with engine.begin() as c:
            c.execute(
                text(
                    "UPDATE instantorder SET wait_until=substr(wait_until,1,19) WHERE id=:id"
                ),
                {"id": oid},
            )
        assert terminal.snapshot(oid)["wait_until"] == snapshot.wait_until
        with pytest.raises(HTTPException) as error:
            router._apply_wait_snapshot(session, snapshot, now)
        conflict(error.value)
        session.rollback()


@pytest.mark.parametrize("offset", ["+03:00", "-04:00"])
def test_sqlite_aware_decodable_raw_wait_keeps_existing_overwrite_contract(
    terminal_case, client, offset
):
    if engine.dialect.name != "sqlite":
        pytest.skip("SQLite raw storage")
    make, passenger, _, now, _ = terminal_case
    oid, _ = make("wait_end")
    with engine.begin() as c:
        c.execute(
            text("UPDATE instantorder SET wait_until=:raw WHERE id=:id"),
            {"raw": now.isoformat() + offset, "id": oid},
        )
    response = client.post(f"/instant/orders/{oid}/wait", headers=passenger["auth"])
    assert response.status_code == 200, response.text


@pytest.mark.parametrize(
    "scenario", ["null_future_null", "order_again_old_null", "same_deadline"]
)
def test_known_gap_exact_tuple_aba_or_unchanged_state_still_admits(
    terminal_case, client, monkeypatch, scenario
):
    make, passenger, _, now, _ = terminal_case
    deadline = now + timedelta(minutes=isv.settings.order_wait_max_min)
    oid, _ = make(
        "wait_end",
        wait_until=deadline if scenario == "same_deadline" else None,
        **dict.fromkeys(terminal.PENDING),
    )
    live = (
        make("accept", status=S.created)[0]
        if scenario == "order_again_old_null"
        else None
    )
    entered, release = Event(), Event()
    clocks = []

    def clock():
        clocks.append(True)
        if len(clocks) == 1:
            entered.set()
            assert release.wait(20)
        return now

    monkeypatch.setattr(router, "utcnow", clock)
    with ThreadPoolExecutor(max_workers=1) as executor:
        delayed = executor.submit(
            client.post, f"/instant/orders/{oid}/wait", headers=passenger["auth"]
        )
        try:
            assert entered.wait(10)
            captured = terminal.snapshot(oid)
            if scenario == "order_again_old_null":
                result = client.post(
                    "/instant/orders",
                    headers=passenger["auth"],
                    json=terminal.ORDER_BODY,
                )
                assert result.status_code == 200 and result.json()["id"] == live
                assert terminal.snapshot(oid) == captured
            else:
                result = client.post(
                    f"/instant/orders/{oid}/wait", headers=passenger["auth"]
                )
                assert result.status_code == 200
                if scenario == "null_future_null":
                    monkeypatch.setattr(
                        taxi_worker, "utcnow", lambda: deadline + timedelta(seconds=1)
                    )
                    with Session(engine) as worker:
                        assert oid in taxi_worker.finish_expired_waits(worker)
                current = terminal.snapshot(oid)
                for name in router._WAIT_SOURCE_FIELDS:
                    assert current[name] == captured[name]
        finally:
            release.set()
            response = delayed.result(timeout=20)
    assert response.status_code == 200, response.text
    after = terminal.snapshot(oid)
    assert after["wait_until"] == deadline and terminal.pending(after) == terminal.CLEAR


def test_known_gap_old_bodyless_request_before_first_read_indistinguishable(
    terminal_case, client
):
    make, passenger, _, _, _ = terminal_case
    oid, _ = make("wait_end")
    saved_path = f"/instant/orders/{oid}/wait"
    saved_auth = dict(passenger["auth"])
    with Session(engine) as worker:
        assert oid in taxi_worker.finish_expired_waits(worker)
    assert terminal.snapshot(oid)["wait_until"] is None
    response = client.post(saved_path, headers=saved_auth)
    assert response.status_code == 200, response.text
    assert terminal.snapshot(oid)["wait_until"] is not None
    assert terminal.pending(terminal.snapshot(oid)) == terminal.CLEAR


def test_sqlite_real_writer_contention_preserves_operational_error(terminal_case):
    if engine.dialect.name != "sqlite":
        pytest.skip("SQLite BUSY control")
    from sqlalchemy.exc import OperationalError

    make, passenger, _, now, _ = terminal_case
    oid, before = make("wait_end")
    with engine.connect() as holder, engine.connect() as contender:
        assert (
            holder.connection.driver_connection
            is not contender.connection.driver_connection
        )
        original = contender.exec_driver_sql("PRAGMA busy_timeout").scalar_one()
        contender.commit()
        try:
            contender.exec_driver_sql("PRAGMA busy_timeout=1")
            contender.commit()
            with Session(bind=contender) as session:
                snapshot = router._capture_wait_snapshot(session, oid, passenger["id"])
                holder.execute(
                    update(InstantOrder)
                    .where(InstantOrder.id == oid)
                    .values(wait_until=now)
                )
                with pytest.raises(OperationalError) as error:
                    router._apply_wait_snapshot(
                        session, snapshot, now + timedelta(hours=1)
                    )
                assert error.value.orig.sqlite_errorcode == 5
                assert error.value.orig.sqlite_errorname == "SQLITE_BUSY"
                session.rollback()
        finally:
            holder.rollback()
            contender.rollback()
            contender.exec_driver_sql(f"PRAGMA busy_timeout={int(original)}")
            contender.commit()
    assert terminal.snapshot(oid) == before


# Source-distinct old handler from f80b066, executed with current dependencies.
# Not an old server/HTTP-stack claim; no global router rebinding.
_LEGACY_WAIT_SOURCE = r'''
def wait_for_driver(order_id: int, user: User=Depends(current_user), session: Session=Depends(get_session)):
    """«Подожду машину» после «рядом никого».

    Раньше отказ был мгновенным и окончательным: свободных водителей нет → заказ сразу expired,
    повтора поиска не было вообще. В райцентре ночью на линии 2-3 водителя и оба заняты — это
    не исключение, а норма: человек получал отказ за 2 секунды и уходил к конкуренту.
    Теперь пассажир ставит заказ в очередь, а фоновый воркер (app/taxi_worker.py) спокойно
    перезапускает поиск до order_wait_max_min минут и пушит, как только машина найдётся."""
    order = session.get(InstantOrder, order_id)
    if not order:
        raise herr(404, 'Заказ не найден', 'Заказ табылманы')
    if order.passenger_id != user.id:
        raise herr(403, 'Это не твой заказ', 'Был һинең заказың түгел')
    if order.status not in (isv.S.expired, isv.S.searching):
        raise herr(409, 'Ожидание доступно, пока машина не найдена', 'Машина табылғанға тиклем генә көтөп була')
    order.wait_until = utcnow() + timedelta(minutes=isv.settings.order_wait_max_min)
    session.add(order)
    session.commit()
    session.refresh(order)
    return {'ok': True, 'wait_until': order.wait_until, 'wait_minutes': isv.settings.order_wait_max_min, 'order': isv.order_payload(session, order, user)}
'''


@pytest.mark.parametrize("offset", ["+03:00", "-04:00"])
def test_prechange_handler_aware_sqlite_overwrite_baseline(terminal_case, offset):
    if engine.dialect.name != "sqlite":
        pytest.skip("SQLite legacy raw timestamp")
    make, passenger, _, now, _ = terminal_case
    oid, before = make("wait_end")
    with engine.begin() as c:
        c.execute(
            text("UPDATE instantorder SET wait_until=:raw WHERE id=:id"),
            {"raw": now.isoformat() + offset, "id": oid},
        )
    namespace = dict(vars(router))
    exec(
        compile(
            _LEGACY_WAIT_SOURCE, "f80b066/routers/instant.py:wait_for_driver", "exec"
        ),
        namespace,
    )
    from app.models import User

    with Session(engine) as session:
        user = session.get(User, passenger["id"])
        result = namespace["wait_for_driver"](oid, user, session)
    assert result["ok"] is True
    after = terminal.snapshot(oid)
    assert after["wait_until"].tzinfo is None
    assert after["search_revision"] == before["search_revision"]
    assert terminal.pending(after) == terminal.pending(before)


@pytest.mark.parametrize("field", terminal.PENDING)
def test_all_pending_fields_are_unrelated_to_wait_admission(terminal_case, field):
    make, passenger, _, now, _ = terminal_case
    oid, _ = make("wait_end")
    with Session(engine) as session:
        order = session.get(InstantOrder, oid)
        value = {
            terminal.PENDING[0]: 99,
            terminal.PENDING[1]: now,
            terminal.PENDING[2]: 88,
        }[field]
        setattr(order, field, value)
        history = inspect(order).attrs[field].history
        snapshot = router._capture_wait_snapshot(session, oid, passenger["id"])
        router._apply_wait_snapshot(session, snapshot, now)
        assert inspect(order).attrs[field].history == history
        session.commit()
    assert terminal.snapshot(oid)[field] == value


def test_sqlite_same_instant_offset_spelling_cas_loser_preserves_raw_after_rollback(
    terminal_case,
):
    if engine.dialect.name != "sqlite":
        pytest.skip("SQLite offset raw storage")
    make, passenger, _, now, _ = terminal_case
    oid, _ = make("wait_end")
    raw1 = "2026-11-18 12:00:00+03:00"
    raw2 = "2026-11-18 09:00:00+00:00"
    with engine.begin() as c:
        c.execute(
            text("UPDATE instantorder SET wait_until=:raw WHERE id=:id"),
            {"raw": raw1, "id": oid},
        )
    with Session(engine) as session:
        snapshot = router._capture_wait_snapshot(session, oid, passenger["id"])
        session.commit()
        with engine.begin() as c:
            c.execute(
                text("UPDATE instantorder SET wait_until=:raw WHERE id=:id"),
                {"raw": raw2, "id": oid},
            )
        assert terminal.snapshot(oid)["wait_until"] == snapshot.wait_until
        with pytest.raises(HTTPException) as error:
            router._apply_wait_snapshot(session, snapshot, now)
        conflict(error.value)
        session.rollback()
    with engine.connect() as c:
        assert (
            c.execute(
                text("SELECT CAST(wait_until AS TEXT) FROM instantorder WHERE id=:id"),
                {"id": oid},
            ).scalar_one()
            == raw2
        )


@pytest.mark.parametrize("raw", ["not-a-time", b"2026-11-18 12:00:00"])
def test_sqlite_undecodable_and_blob_wait_errors_not_conflict(terminal_case, raw):
    if engine.dialect.name != "sqlite":
        pytest.skip("SQLite invalid timestamp storage")
    make, passenger, _, _, _ = terminal_case
    oid, _ = make("wait_end")
    with engine.begin() as c:
        c.execute(
            text("UPDATE instantorder SET wait_until=:raw WHERE id=:id"),
            {"raw": raw, "id": oid},
        )
    try:
        with Session(engine) as session:
            with pytest.raises(ValueError if isinstance(raw, str) else TypeError):
                router._capture_wait_snapshot(session, oid, passenger["id"])
            session.rollback()
    finally:
        # Restore decodable value for fixture cleanup, not product repair.
        with engine.begin() as c:
            c.execute(
                text("UPDATE instantorder SET wait_until=NULL WHERE id=:id"),
                {"id": oid},
            )


def test_sqlite_actual_wal_read_snapshot_upgrade_error_is_not_409(tmp_path):
    """Synthetic minimal table proves actual SQLite WAL upgrade behavior only."""
    from sqlalchemy import create_engine
    from sqlalchemy.exc import OperationalError

    own = create_engine(f"sqlite:///{tmp_path / 'wait-wal.sqlite'}")
    try:
        with own.begin() as c:
            assert c.exec_driver_sql("PRAGMA journal_mode=WAL").scalar_one() == "wal"
            c.exec_driver_sql(
                "CREATE TABLE instantorder(id INTEGER PRIMARY KEY,passenger_id INTEGER,status TEXT,search_revision BIGINT,wait_until DATETIME)"
            )
            c.exec_driver_sql("INSERT INTO instantorder VALUES(1,1,'expired',41,NULL)")
        with own.connect() as reader, own.connect() as writer:
            assert (
                reader.connection.driver_connection
                is not writer.connection.driver_connection
            )
            reader.exec_driver_sql("BEGIN")
            with Session(bind=reader) as session:
                snapshot = router._capture_wait_snapshot(session, 1, 1)
                writer.exec_driver_sql(
                    "UPDATE instantorder SET wait_until='2026-11-18 12:00:00' WHERE id=1"
                )
                writer.commit()
                with pytest.raises(OperationalError) as error:
                    router._apply_wait_snapshot(
                        session, snapshot, terminal.transfer.known.utcnow()
                    )
                assert error.value.orig.sqlite_errorcode == 517
                session.rollback()
            reader.rollback()
    finally:
        own.dispose()


def test_sqlite_actual_cas_compiles_binary_raw_string_bind(terminal_case):
    if engine.dialect.name != "sqlite":
        pytest.skip("SQLite raw CAS compilation")
    from sqlalchemy import String

    make, passenger, _, now, _ = terminal_case
    oid, _ = make("wait_end")
    seen = []
    with engine.connect() as c:
        with Session(bind=c) as session:
            snapshot = router._capture_wait_snapshot(session, oid, passenger["id"])

            def observe(conn, cursor, statement, parameters, context, many):
                if statement.startswith("UPDATE instantorder"):
                    assert 'COLLATE "BINARY"' in statement and "CAST(" in statement
                    assert "typeof(" in statement
                    # Compiled cache retains old BindParameter.value; execution
                    # parameters carry this invocation's actual raw snapshot.
                    actual = context.compiled_parameters[0]
                    names = [
                        name
                        for name, value in actual.items()
                        if value == snapshot.wait_raw
                    ]
                    assert len(names) == 1
                    bind = context.compiled.binds[names[0]]
                    assert isinstance(bind.type, String)
                    assert bind.type.bind_processor(c.dialect) is None
                    position = context.compiled.positiontup.index(names[0])
                    assert parameters[position] == snapshot.wait_raw
                    seen.append(True)

            event.listen(c, "before_cursor_execute", observe)
            try:
                router._apply_wait_snapshot(session, snapshot, now)
            finally:
                event.remove(c, "before_cursor_execute", observe)
            session.rollback()
    assert seen == [True]
