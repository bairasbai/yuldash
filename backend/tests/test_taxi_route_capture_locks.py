"""Actual narrow route capture guard, not full quote/fallback/durable protection."""

from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
from datetime import timedelta
from threading import Event
from time import monotonic, sleep
from uuid import uuid4

import fakeredis
import pytest
from fastapi import HTTPException
from sqlalchemy import event, inspect, select, text, update
from sqlalchemy.exc import OperationalError
from sqlmodel import Session

from app import instant_service as isv, services
from app.db import engine
from app.models import (
    InstantOrder,
    InstantOrderStatus as S,
    UserRole,
    Notification,
    OfferDecline,
    InstantTriedExclusion,
)
from app.timeutil import utcnow
from test_taxi_status_never_resurrects import _order
from test_taxi_search_revision_postgres import _disposable_pg_before_client  # noqa: F401

SOURCE = (
    "status",
    "search_revision",
    "driver_id",
    "destination_changed_at",
    "to_lat",
    "to_lng",
    "waypoints_json",
    "category",
    "pricing_k",
    "ride_price",
    "price_estimate",
    "pending_to_lat",
    "pending_to_lng",
    "pending_to_text",
    "pending_price",
)


def _delete_own_order(connection, oid):
    # Real helpers persist Notification rows before send_push. Transport spies
    # do not prevent that write, and ref_id has no order FK cascade. Always
    # remove only this fixture's rows, including partial-operation outcomes.
    connection.execute(
        Notification.__table__.delete().where(
            Notification.ref_kind == "instant", Notification.ref_id == oid
        )
    )
    connection.execute(
        OfferDecline.__table__.delete().where(OfferDecline.order_id == oid)
    )
    connection.execute(
        InstantTriedExclusion.__table__.delete().where(
            InstantTriedExclusion.order_id == oid
        )
    )
    connection.execute(InstantOrder.__table__.delete().where(InstantOrder.id == oid))


@pytest.fixture
def route_order(client, user_factory, monkeypatch, request):
    passenger = user_factory("CapturePassenger")
    driver = user_factory("CaptureDriver", role=UserRole.driver)
    redis = fakeredis.FakeStrictRedis(decode_responses=True)
    monkeypatch.setattr(isv, "_redis_override", redis)
    monkeypatch.setattr(isv, "send_push", lambda *args, **kwargs: None)
    monkeypatch.setattr(services, "send_push", lambda *args, **kwargs: None)
    initial_status = getattr(request, "param", S.offered)
    oid = _order(
        passenger["id"],
        initial_status,
        search_revision=20,
        current_offer_driver_id=driver["id"] if initial_status == S.offered else None,
        offer_token="a" * 32,
        offer_expires_at=utcnow() + timedelta(minutes=10)
        if initial_status == S.offered
        else None,
    )
    try:
        yield oid, passenger, driver
    finally:
        try:
            with engine.begin() as c:
                _delete_own_order(c, oid)
        finally:
            redis.close()


def _row(oid):
    with engine.connect() as c:
        return dict(
            c.execute(select(InstantOrder.__table__).where(InstantOrder.id == oid))
            .mappings()
            .one()
        )


@contextmanager
def _sql():
    statements = []

    def record(*args):
        statements.append(args[2])

    event.listen(engine, "before_cursor_execute", record)
    try:
        yield statements
    finally:
        event.remove(engine, "before_cursor_execute", record)


@pytest.mark.parametrize(
    "kind",
    [
        "expired_all",
        "expired_source",
        "unloaded_source",
        "dirty_source",
        "dirty_other",
        "detached",
        "transient",
        "wrong_session",
        "pending_delete",
        "flushed_delete",
    ],
)
def test_invalid_target_rejected_before_any_sql(route_order, kind):
    oid, _, _ = route_order
    with Session(engine) as session, Session(engine) as other:
        order = session.get(InstantOrder, oid)
        target_session = session
        if kind == "expired_all":
            session.expire(order)
        elif kind == "expired_source":
            session.expire(order, ["to_lat"])
        elif kind == "unloaded_source":
            session.expunge(order)
            from sqlalchemy.orm import defer

            order = session.execute(
                select(InstantOrder)
                .options(defer(InstantOrder.to_lat))
                .where(InstantOrder.id == oid)
            ).scalar_one()
        elif kind == "dirty_source":
            order.to_lat += 0.01
        elif kind == "dirty_other":
            order.comment = "Unflushed local comment"
        elif kind == "detached":
            session.expunge(order)
        elif kind == "transient":
            order = InstantOrder(passenger_id=1)
        elif kind == "wrong_session":
            target_session = other
        elif kind == "pending_delete":
            session.delete(order)
        elif kind == "flushed_delete":
            session.delete(order)
            session.flush()
        with _sql() as statements:
            with pytest.raises(HTTPException) as error:
                isv._lock_route_mutation(target_session, order)
            assert error.value.status_code == 409 and statements == []
        session.rollback()
        other.rollback()


@pytest.mark.parametrize("field", SOURCE)
def test_each_expired_source_rejected_before_sql(route_order, field):
    oid, _, _ = route_order
    with Session(engine) as session:
        order = session.get(InstantOrder, oid)
        session.expire(order, [field])
        with _sql() as statements:
            with pytest.raises(HTTPException) as error:
                isv._lock_route_mutation(session, order)
            assert error.value.status_code == 409 and statements == []
        session.rollback()


def test_same_value_assignment_and_unrelated_expired_field_allowed(route_order):
    oid, _, _ = route_order
    with Session(engine) as session:
        order = session.get(InstantOrder, oid)
        order.to_lat = order.to_lat
        assert not inspect(order).attrs.to_lat.history.has_changes()
        session.expire(order, ["comment"])
        isv._lock_route_mutation(session, order)
        assert session.in_transaction() and order.search_revision == 20
        session.rollback()


@pytest.mark.parametrize(
    "field", [column.key for column in inspect(InstantOrder).column_attrs]
)
def test_any_dirty_target_column_rejected_without_sql(route_order, field):
    oid, _, _ = route_order
    with Session(engine) as session:
        order = session.get(InstantOrder, oid)
        value = getattr(order, field)
        setattr(order, field, None if value is not None else "synthetic dirty")
        assert inspect(order).attrs[field].history.has_changes()
        with _sql() as statements:
            with pytest.raises(HTTPException) as error:
                isv._lock_route_mutation(session, order)
            assert error.value.status_code == 409 and statements == []
        session.rollback()


def test_expired_id_only_uses_persistent_identity_without_prelock_load(route_order):
    oid, _, _ = route_order
    with Session(engine) as session:
        order = session.get(InstantOrder, oid)
        session.expire(order, ["id"])
        with _sql() as statements:
            isv._lock_route_mutation(session, order)
        if engine.dialect.name == "sqlite":
            assert statements[0].lstrip().upper().startswith("UPDATE")
        else:
            assert "FOR UPDATE" in statements[0].upper()
        session.rollback()


@pytest.mark.parametrize("commit", [False, True])
def test_other_rows_dirty_and_new_not_flushed_inside_capture(route_order, commit):

    oid, passenger, _ = route_order
    note_id = None
    try:
        with Session(engine) as session:
            original = session.get(InstantOrder, oid)
            from app.models import User

            other = session.get(User, passenger["id"])
            old_name = other.name
            other.name = "Locally changed passenger"
            note = Notification(user_id=passenger["id"], title_ru="Synthetic capture")
            session.add(note)
            isv._lock_route_mutation(session, original)
            with session.no_autoflush:
                assert (
                    session.execute(
                        select(User.name).where(User.id == passenger["id"])
                    ).scalar_one()
                    == old_name
                )
                assert note.id is None
            if commit:
                session.commit()
                note_id = note.id
            else:
                session.rollback()
        with engine.connect() as c:
            assert c.execute(
                select(User.name).where(User.id == passenger["id"])
            ).scalar_one() == ("Locally changed passenger" if commit else old_name)
    finally:
        if note_id is not None:
            with engine.begin() as c:
                c.execute(
                    Notification.__table__.delete().where(Notification.id == note_id)
                )


@contextmanager
def _fast_busy(connection):
    timeout = connection.exec_driver_sql("PRAGMA busy_timeout").scalar_one()
    connection.commit()
    try:
        connection.exec_driver_sql("PRAGMA busy_timeout=1")
        connection.commit()
        yield
    finally:
        connection.rollback()
        connection.exec_driver_sql(f"PRAGMA busy_timeout={int(timeout)}")
        connection.commit()


def _transition(session, oid, passenger, driver, action):
    if action == "cancel":
        return isv.cancel_order(session, oid, isv.Actor.passenger, passenger["id"])
    return isv.transition(
        session, oid, isv.Actor.driver, S.accepted, driver["id"], idempotent=False
    )


@pytest.mark.parametrize("action", ["cancel", "accept"])
def test_other_transition_commits_first_stale_route_conflicts_without_writes(
    route_order, action
):
    oid, passenger, driver = route_order
    with (
        engine.connect() as c1,
        engine.connect() as c2,
        Session(bind=c1, expire_on_commit=False) as route,
        Session(bind=c2) as other,
    ):
        assert c1.connection.driver_connection is not c2.connection.driver_connection
        stale = route.get(InstantOrder, oid)
        route.commit()  # Retain stale object without a DB reader lock.
        _transition(other, oid, passenger, driver, action)
        after_winner = _row(oid)
        with pytest.raises(HTTPException) as error:
            isv._lock_route_mutation(route, stale)
        assert error.value.status_code == 409
        route.rollback()
    assert _row(oid) == after_winner


@pytest.mark.parametrize("action", ["cancel", "accept"])
@pytest.mark.parametrize("commit", [False, True])
def test_sqlite_route_lock_wins_busy_then_explicit_fresh_transition(
    route_order, action, commit
):
    if engine.dialect.name != "sqlite":
        pytest.skip("SQLite writer-lock behavior")
    oid, passenger, driver = route_order
    with engine.connect() as c1, engine.connect() as c2:
        assert c1.connection.driver_connection is not c2.connection.driver_connection
        with _fast_busy(c2), Session(bind=c1) as route, Session(bind=c2) as other:
            order = route.get(InstantOrder, oid)
            isv._lock_route_mutation(route, order)
            with pytest.raises(OperationalError) as error:
                _transition(other, oid, passenger, driver, action)
            assert error.value.orig.sqlite_errorcode & 255 == 5
            other.rollback()
            route.commit() if commit else route.rollback()
            result = _transition(other, oid, passenger, driver, action)
            assert result.status == (S.cancelled if action == "cancel" else S.accepted)
    assert _row(oid)["search_revision"] == 20


@pytest.mark.parametrize("mutate", [False, True])
def test_sqlite_audit_extra_noop_and_trigger_owner_conflict_rollback(
    route_order, mutate
):
    if engine.dialect.name != "sqlite":
        pytest.skip("SQLite TEMP trigger contract")
    oid, _, _ = route_order
    suffix = uuid4().hex
    audit, trigger = "capture_audit_" + suffix, "capture_trigger_" + suffix
    before = _row(oid)
    with engine.connect() as c:
        c.execute(
            text(
                f'CREATE TEMP TABLE "{audit}" (before_revision INTEGER, after_revision INTEGER)'
            )
        )
        extra = (
            f"UPDATE instantorder SET to_lat=to_lat+0.01 WHERE id={oid};"
            if mutate
            else ""
        )
        c.execute(
            text(
                f'CREATE TEMP TRIGGER "{trigger}" AFTER UPDATE ON main.instantorder WHEN NEW.id={oid} AND NEW.to_lat=OLD.to_lat BEGIN INSERT INTO "{audit}" VALUES(OLD.search_revision,NEW.search_revision); {extra} END'
            )
        )
        c.commit()
        try:
            with Session(bind=c) as session:
                order = session.get(InstantOrder, oid)
                if mutate:
                    with pytest.raises(HTTPException) as error:
                        isv._lock_route_mutation(session, order)
                    assert error.value.status_code == 409
                else:
                    isv._lock_route_mutation(session, order)
                assert [
                    tuple(r) for r in c.execute(text(f'SELECT * FROM "{audit}"'))
                ] == [(20, 20)]
                session.rollback()
                assert c.execute(text(f'SELECT * FROM "{audit}"')).all() == []
        finally:
            c.rollback()
            c.execute(text(f'DROP TRIGGER "{trigger}"'))
            c.execute(text(f'DROP TABLE "{audit}"'))
            c.commit()
    assert _row(oid) == before


@pytest.mark.parametrize("action", ["cancel", "accept"])
def test_pg_route_lock_blocks_actual_transition_until_outer_release(
    route_order, action
):
    if engine.dialect.name != "postgresql":
        pytest.skip("guarded disposable PostgreSQL")
    oid, passenger, driver = route_order
    started = Event()
    pids = {}
    with engine.connect() as c1, Session(bind=c1) as route:
        pids["route"] = route.execute(text("SELECT pg_backend_pid()")).scalar_one()
        isv._lock_route_mutation(route, route.get(InstantOrder, oid))

        def worker():
            with engine.connect() as c2, Session(bind=c2) as other:
                pids["other"] = other.execute(
                    text("SELECT pg_backend_pid()")
                ).scalar_one()
                started.set()
                try:
                    return _transition(other, oid, passenger, driver, action).status
                finally:
                    other.rollback()

        with ThreadPoolExecutor(max_workers=1) as pool:
            future = pool.submit(worker)
            try:
                assert started.wait(20) and pids["route"] != pids["other"]
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
                            {"pid": pids["other"]},
                        ).scalar_one()
                    if pids["route"] in blockers:
                        break
                    assert monotonic() < deadline
                    sleep(0.01)
                route.rollback()
                assert future.result(timeout=5) == (
                    S.cancelled if action == "cancel" else S.accepted
                )
            finally:
                route.rollback()


@pytest.mark.parametrize("path", ["destination", "waypoints", "destination_accept"])
def test_actual_http_three_apply_callers_accepted_revision_unchanged(
    client, user_factory, monkeypatch, path
):
    from test_instant import _offered_order

    redis = fakeredis.FakeStrictRedis(decode_responses=True)
    monkeypatch.setattr(isv, "_redis_override", redis)
    monkeypatch.setattr(isv, "send_push", lambda *args, **kwargs: None)
    monkeypatch.setattr(services, "send_push", lambda *args, **kwargs: None)
    oid = None
    try:
        driver, passenger, payload = _offered_order(
            client, user_factory, redis, "CaptureHttpDriver", "CaptureHttpPassenger"
        )
        oid = payload["id"]
        accepted = client.post(f"/instant/orders/{oid}/accept", headers=driver["auth"])
        assert accepted.status_code == 200, accepted.text
        before = _row(oid)
        if path == "waypoints":
            response = client.post(
                f"/instant/orders/{oid}/waypoints",
                headers=passenger["auth"],
                json={
                    "waypoints": [
                        {"lat": 52.595, "lng": 58.32, "text": "Новая остановка"}
                    ]
                },
            )
        elif path == "destination":
            response = client.post(
                f"/instant/orders/{oid}/destination",
                headers=passenger["auth"],
                json={"to_lat": 52.60, "to_lng": 58.33, "to_text": "Новое место"},
            )
            assert response.json().get("applied") is True, response.text
        else:
            proposal = client.post(
                f"/instant/orders/{oid}/destination",
                headers=passenger["auth"],
                json={"to_lat": 55.0, "to_lng": 60.0, "to_text": "Дальний пункт"},
            )
            assert (
                proposal.status_code == 200
                and proposal.json().get("waiting_driver") is True
            ), proposal.text
            assert _row(oid)["pending_to_lat"] == 55.0
            response = client.post(
                f"/instant/orders/{oid}/destination/accept", headers=driver["auth"]
            )
        assert response.status_code == 200, response.text
        after = _row(oid)
        assert after["status"] == S.accepted and after["driver_id"] == driver["id"]
        assert after["search_revision"] == before["search_revision"]
        if path == "waypoints":
            assert (
                isv.parse_waypoints(after["waypoints_json"])[-1]["text"]
                == "Новая остановка"
            )
        else:
            assert (after["to_lat"], after["to_lng"]) == (
                (55.0, 60.0) if path == "destination_accept" else (52.60, 58.33)
            )
    finally:
        try:
            if oid is not None:
                with engine.begin() as c:
                    _delete_own_order(c, oid)
        finally:
            redis.close()


@pytest.mark.parametrize("field", SOURCE)
def test_every_captured_owner_field_change_rejects_without_adoption(route_order, field):
    oid, _, driver = route_order
    with Session(engine, expire_on_commit=False) as session:
        stale = session.get(InstantOrder, oid)
        original = getattr(stale, field)
        session.commit()
        values = {
            "status": S.cancelled,
            "search_revision": 21,
            "driver_id": driver["id"],
            "destination_changed_at": utcnow(),
            "to_lat": 53.1,
            "to_lng": 59.1,
            "waypoints_json": '[{"lat":53.0,"lng":59.0,"text":"Synthetic"}]',
            "category": "comfort",
            "pricing_k": 1.5,
            "ride_price": 712,
            "price_estimate": 713,
            "pending_to_lat": 53.2,
            "pending_to_lng": 59.2,
            "pending_to_text": "Pending synthetic",
            "pending_price": 714,
        }
        assert values[field] != original
        # Explicit scalar-CAS perturbation, not a claimed application transition.
        with engine.begin() as c:
            c.execute(
                update(InstantOrder)
                .where(InstantOrder.id == oid)
                .values(**{field: values[field]})
            )
        changed = _row(oid)
        with pytest.raises(HTTPException) as error:
            isv._lock_route_mutation(session, stale)
        assert error.value.status_code == 409
        session.rollback()
    assert _row(oid) == changed


def test_sqlite_preexisting_reader_upgrade_busy_requires_caller_rollback(route_order):
    if engine.dialect.name != "sqlite":
        pytest.skip("SQLite read-lock upgrade")
    oid, _, _ = route_order
    before = _row(oid)
    with engine.connect() as reader, engine.connect() as writer:
        assert (
            reader.connection.driver_connection
            is not writer.connection.driver_connection
        )
        with (
            _fast_busy(reader),
            Session(bind=reader) as old,
            Session(bind=writer) as winner,
        ):
            reader.exec_driver_sql(
                "BEGIN"
            )  # Actual reader transaction, not legacy deferred SELECT.
            stale = old.get(InstantOrder, oid)
            isv._lock_route_mutation(winner, winner.get(InstantOrder, oid))
            with pytest.raises(OperationalError) as error:
                isv._lock_route_mutation(old, stale)
            assert error.value.orig.sqlite_errorcode & 255 == 5
            old.rollback()
            winner.rollback()
    assert _row(oid) == before


@pytest.mark.parametrize("action", ["cancel", "accept"])
def test_pg_transition_writes_first_then_waiting_route_rejects_fresh_owner(
    route_order, action
):
    if engine.dialect.name != "postgresql":
        pytest.skip("guarded disposable PostgreSQL")
    oid, passenger, driver = route_order
    written, route_started, release = Event(), Event(), Event()
    pids = {}
    with Session(engine, expire_on_commit=False) as loader:
        captured = loader.get(InstantOrder, oid)
        # Each route worker loads its own stale row before the competing write.
        original_values = {name: getattr(captured, name) for name in SOURCE}

    def hold_after_actual_update(
        connection, cursor, statement, parameters, context, executemany
    ):
        if (
            connection.info.get("capture_transition")
            and not written.is_set()
            and statement.lstrip().upper().startswith("UPDATE INSTANTORDER")
        ):
            written.set()
            assert release.wait(15), "coordinator did not release actual transition"

    def transition_worker():
        with engine.connect() as c, Session(bind=c) as session:
            c.info["capture_transition"] = True
            try:
                pids["transition"] = session.execute(
                    text("SELECT pg_backend_pid()")
                ).scalar_one()
                return _transition(session, oid, passenger, driver, action).status
            finally:
                session.rollback()
                c.info.pop("capture_transition", None)

    def route_worker():
        with engine.connect() as c, Session(bind=c, expire_on_commit=False) as session:
            pids["route"] = session.execute(
                text("SELECT pg_backend_pid()")
            ).scalar_one()
            stale = session.get(InstantOrder, oid)
            assert {name: getattr(stale, name) for name in SOURCE} == original_values
            route_started.set()
            assert written.wait(15)
            try:
                with pytest.raises(HTTPException) as error:
                    isv._lock_route_mutation(session, stale)
                assert error.value.status_code == 409
            finally:
                session.rollback()

    event.listen(engine, "after_cursor_execute", hold_after_actual_update)
    try:
        with ThreadPoolExecutor(max_workers=2) as pool:
            waiting_route = pool.submit(route_worker)
            changing = None
            try:
                assert route_started.wait(20)
                changing = pool.submit(transition_worker)
                assert written.wait(20)
                assert pids["route"] != pids["transition"]
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
                            {"pid": pids["route"]},
                        ).scalar_one()
                    if pids["transition"] in blockers:
                        break
                    assert monotonic() < deadline
                    sleep(0.01)
                release.set()
                assert changing.result(timeout=5) == (
                    S.cancelled if action == "cancel" else S.accepted
                )
                waiting_route.result(timeout=5)
            finally:
                release.set()
    finally:
        event.remove(engine, "after_cursor_execute", hold_after_actual_update)
    after = _row(oid)
    assert after["search_revision"] == original_values["search_revision"]
    for name in ("to_lat", "to_lng", "waypoints_json", "price_estimate", "ride_price"):
        assert after[name] == original_values[name]


@pytest.mark.parametrize("revision", [None, 20])
def test_accepted_guard_allows_nullable_revision_without_increment(
    route_order, revision
):
    oid, _, driver = route_order
    with engine.begin() as c:
        c.execute(
            update(InstantOrder)
            .where(InstantOrder.id == oid)
            .values(
                status=S.accepted,
                driver_id=driver["id"],
                current_offer_driver_id=None,
                offer_expires_at=None,
                search_revision=revision,
            )
        )
    before = _row(oid)
    with Session(engine) as session:
        order = session.get(InstantOrder, oid)
        isv._lock_route_mutation(session, order)
        session.commit()
    assert _row(oid) == before


@pytest.mark.parametrize("route_order", [S.searching, S.offered], indirect=True)
@pytest.mark.parametrize("path", ["destination", "waypoints"])
def test_authenticated_searching_and_offered_route_apply(client, route_order, path):
    # Synthetic starting owner, actual authenticated quote/apply/matcher. This
    # checks caller compatibility, not how the starting order was produced.
    oid, passenger, _ = route_order
    if path == "destination":
        response = client.post(
            f"/instant/orders/{oid}/destination",
            headers=passenger["auth"],
            json={"to_lat": 52.60, "to_lng": 58.33, "to_text": "Новое место"},
        )
    else:
        response = client.post(
            f"/instant/orders/{oid}/waypoints",
            headers=passenger["auth"],
            json={
                "waypoints": [{"lat": 52.595, "lng": 58.32, "text": "Новая остановка"}]
            },
        )
    assert response.status_code == 200, response.text
    after = _row(oid)
    assert after["status"] == S.expired  # No online presence in this fixture.
    assert after["search_revision"] == 21
    if path == "destination":
        assert (after["to_lat"], after["to_lng"]) == (52.60, 58.33)
    else:
        assert (
            isv.parse_waypoints(after["waypoints_json"])[-1]["text"]
            == "Новая остановка"
        )
