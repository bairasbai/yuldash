"""Selected authoritative clears with synthetic inactive pending obligations.

Actual services/API/workers; no producer, account cutover or blanket expiry policy.
Finish-early retains its existing ownership semantics, not a new race guarantee.
"""

from contextlib import nullcontext
from datetime import timedelta

import pytest
from fastapi import HTTPException
from sqlalchemy import event, inspect, text, update
from sqlmodel import Session

from app import cleanup, instant_service as isv, taxi_worker
from app.db import engine
from app.models import InstantOrder, InstantOrderStatus as S
import test_taxi_pending_transfer_writers as transfer
from test_taxi_search_revision_postgres import _disposable_pg_before_client  # noqa: F401

seeded = transfer.seeded
PENDING = transfer.PENDING
snapshot = transfer.known.snapshot
pending = transfer.pending
CLEAR = (None, None, None)
OPERATIONS = ("accept", "done", "cancel", "finish_early", "stuck", "wait_end")


@pytest.fixture
def terminal_case(seeded):
    create, passenger, driver, now = seeded
    ids = []

    def make(operation="accept", **changes):
        status = {
            "done": S.onboard,
            "finish_early": S.onboard,
            "wait_end": S.expired,
        }.get(operation, S.offered)
        oid, _ = create(status)
        ids.append(oid)
        values = {}
        if operation in ("done", "finish_early"):
            values.update(
                driver_id=driver["id"],
                accepted_at=now - timedelta(minutes=10),
                onboard_at=now - timedelta(minutes=5),
                price_estimate=300,
                price_final=None,
                waiting_fee_kop=250,
                pickup_fee_kop=0,
                options_fee_kop=0,
                weather_fee_kop=0,
            )
        if operation == "stuck":
            old = now - timedelta(hours=taxi_worker.settings.order_stuck_hours + 2)
            values.update(
                created_at=old,
                searching_at=old,
                offered_at=old,
                wait_until=now + timedelta(hours=1),
            )
        if operation == "wait_end":
            values.update(wait_until=now - timedelta(seconds=5))
        values.update(changes)
        if values:
            with engine.begin() as c:
                c.execute(
                    update(InstantOrder).where(InstantOrder.id == oid).values(**values)
                )
        return oid, snapshot(oid)

    try:
        yield make, passenger, driver, now, ids
    finally:
        # API completion may create real synthetic debt/ledger records; transport
        # interception does not prevent persisted notifications.
        with engine.begin() as c:
            for oid in ids:
                for table in ("drivercancel", "commissiondebt", "ledgerentry"):
                    c.execute(
                        text(f"DELETE FROM {table} WHERE order_id=:id"), {"id": oid}
                    )
                c.execute(
                    text(
                        "DELETE FROM notification WHERE ref_kind='instant' AND ref_id=:id"
                    ),
                    {"id": oid},
                )
                # API-created orders are not in the base fixture's private list.
                c.execute(
                    text("DELETE FROM offerdecline WHERE order_id=:id"), {"id": oid}
                )
                c.execute(
                    text("DELETE FROM instanttriedexclusion WHERE order_id=:id"),
                    {"id": oid},
                )
                c.execute(text("DELETE FROM instantorder WHERE id=:id"), {"id": oid})


def invoke(session, oid, operation, passenger, driver):
    if operation in ("accept", "done"):
        return isv.transition(
            session,
            oid,
            isv.Actor.driver,
            S.accepted if operation == "accept" else S.done,
            driver["id"],
        )
    if operation == "cancel":
        return isv.cancel_order(session, oid, isv.Actor.passenger, passenger["id"])
    if operation == "finish_early":
        return isv.finish_early(session, session.get(InstantOrder, oid), "other")
    if operation == "stuck":
        assert oid in taxi_worker.close_stuck_orders(session)
    else:
        assert oid in taxi_worker.finish_expired_waits(session)


@pytest.mark.parametrize("operation", OPERATIONS)
@pytest.mark.parametrize(
    "shape", ["complete", "partial", "old_plus_one", "negative", "absent"]
)
def test_selected_actual_winners_clear_without_pending_validation(
    terminal_case, operation, shape
):
    make, passenger, driver, _, _ = terminal_case
    values = {
        "complete": {},
        "partial": {PENDING[1]: None},
        "old_plus_one": {PENDING[0]: 42},
        "negative": {PENDING[0]: -1, PENDING[2]: -1},
        "absent": dict.fromkeys(PENDING),
    }[shape]
    oid, before = make(operation, **values)
    with Session(engine) as session:
        invoke(session, oid, operation, passenger, driver)
    after = snapshot(oid)
    assert (
        pending(after) == CLEAR
        and after["search_revision"] == before["search_revision"]
    )
    expected = {
        "accept": S.accepted,
        "done": S.done,
        "cancel": S.cancelled,
        "finish_early": S.done,
        "stuck": S.expired,
        "wait_end": S.expired,
    }[operation]
    assert after["status"] == expected
    if operation == "done":
        assert after["price_final"] == 302 and after["waiting_fee_kop"] == 250
    if operation in ("stuck", "wait_end"):
        assert after["wait_until"] is None


@pytest.mark.parametrize("operation", ["accept", "done", "cancel", "finish_early"])
@pytest.mark.parametrize("mode", ["normal", "disabled", "context"])
@pytest.mark.parametrize("dirty", ["one", "all", "same"])
def test_authoritative_dirty_clear_preserves_other_history_and_cannot_resurrect(
    terminal_case, operation, mode, dirty
):
    make, passenger, driver, now, _ = terminal_case
    oid, _ = make(operation)
    seen = []
    with Session(
        engine, autoflush=mode != "disabled", expire_on_commit=False
    ) as session:
        order = session.get(InstantOrder, oid)
        if dirty == "same":
            order.matching_resume_attempts = 3
        else:
            order.matching_resume_attempts = 9
            if dirty == "all":
                order.matching_resume_revision = 99
                order.matching_resume_next_at = now + timedelta(hours=1)
        order.from_text = "caller-owned unrelated text"

        def before_commit(actual):
            if seen:
                return
            assert actual is session
            assert tuple(getattr(order, n) for n in PENDING) == CLEAR
            if operation != "finish_early":
                assert all(
                    not inspect(order).attrs[n].history.has_changes() for n in PENDING
                )
            # Earlier query autoflush is intentionally preserved. Disabled paths
            # must preserve unrelated dirty state until the real commit flush.
            assert order.from_text == "caller-owned unrelated text"
            if mode != "normal":
                assert inspect(order).attrs.from_text.history.has_changes()
            seen.append(True)

        event.listen(session, "before_commit", before_commit)
        try:
            with session.no_autoflush if mode == "context" else nullcontext():
                invoke(session, oid, operation, passenger, driver)
            assert seen == [True]
            assert tuple(getattr(order, n) for n in PENDING) == CLEAR
            session.commit()  # No stale pending resurrection on later flush.
        finally:
            event.remove(session, "before_commit", before_commit)
    assert pending(snapshot(oid)) == CLEAR
    assert snapshot(oid)["from_text"] == "caller-owned unrelated text"


@pytest.mark.parametrize("operation", ["accept", "cancel"])
def test_real_stale_local_status_cas_loser_does_not_sync_pending(
    terminal_case, operation
):
    make, passenger, driver, _, _ = terminal_case
    oid, _ = make(operation)
    with Session(engine, autoflush=False, expire_on_commit=False) as session:
        order = session.get(InstantOrder, oid)
        session.commit()
        with engine.begin() as winner:
            winner.execute(
                update(InstantOrder)
                .where(InstantOrder.id == oid)
                .values(status=S.cancelled)
            )
        # Clean stale offered object is retained by ORM SELECT; actual WHERE
        # status=offered must lose against committed cancelled storage.
        assert order.status == S.offered
        before = snapshot(oid)
        with pytest.raises(HTTPException) as error:
            invoke(session, oid, operation, passenger, driver)
        assert error.value.status_code == 409
        assert pending(snapshot(oid)) == pending(before)
        assert tuple(getattr(order, n) for n in PENDING) == pending(before)


@pytest.mark.parametrize("kind", ["same_target", "terminal", "foreign"])
def test_early_returns_and_auth_failure_do_not_repair(terminal_case, kind):
    make, passenger, driver, now, _ = terminal_case
    oid, before = make(
        "accept",
        status=S.accepted if kind == "same_target" else S.cancelled,
        driver_id=driver["id"],
        accepted_at=now,
    )
    with Session(engine, autoflush=False) as session:
        if kind == "same_target":
            isv.transition(session, oid, isv.Actor.driver, S.accepted, driver["id"])
        elif kind == "terminal":
            isv.cancel_order(session, oid, isv.Actor.passenger, passenger["id"])
        else:
            with pytest.raises(HTTPException):
                isv.transition(
                    session, oid, isv.Actor.driver, S.accepted, passenger["id"]
                )
        session.rollback()
    assert snapshot(oid) == before


@pytest.mark.parametrize("operation", ["stuck", "wait_end"])
@pytest.mark.parametrize("skip", ["dry", "recent_or_future", "terminal"])
def test_worker_nonwinners_preserve_pending(terminal_case, operation, skip):
    make, _, _, now, _ = terminal_case
    changes = {}
    if skip == "recent_or_future":
        changes = (
            {"offered_at": now}
            if operation == "stuck"
            else {"wait_until": now + timedelta(hours=1)}
        )
    if skip == "terminal":
        changes = {"status": S.cancelled}
    oid, before = make(operation, **changes)
    with Session(engine) as session:
        fn = (
            taxi_worker.close_stuck_orders
            if operation == "stuck"
            else taxi_worker.finish_expired_waits
        )
        result = fn(session, dry_run=skip == "dry")
        assert (oid in result) == (skip == "dry")
    assert snapshot(oid) == before


@pytest.mark.parametrize("path", ["accept", "done", "cancel", "destination/decline"])
def test_actual_authenticated_terminal_api(terminal_case, client, path):
    make, passenger, driver, _, _ = terminal_case
    operation = "finish_early" if path == "destination/decline" else path
    oid, before = make(operation)
    actor = passenger if path == "cancel" else driver
    response = client.post(
        f"/instant/orders/{oid}/{path}", headers=actor["auth"], json={"reason": "other"}
    )
    assert response.status_code == 200, response.text
    after = snapshot(oid)
    assert (
        pending(after) == CLEAR
        and after["search_revision"] == before["search_revision"]
    )
    if path == "done":
        assert after["price_final"] == 302
    if path == "destination/decline":
        assert response.json()["finished_early"] is True
        assert after["status"] == S.done and after["price_final"] is not None


def test_actual_raw_cleanup_selected_branches_not_scheduled_policy(terminal_case):
    make, _, driver, now, _ = terminal_case
    old = now - timedelta(hours=cleanup.settings.taxi_stale_hours + 2)
    selected = [
        make("done", status=status, created_at=old, driver_id=driver["id"])[0]
        for status in (S.accepted, S.arriving, S.onboard)
    ]
    scheduled, scheduled_before = make(
        "accept",
        status=S.scheduled,
        created_at=old,
        scheduled_at=old,
        wait_until=now + timedelta(hours=1),
    )
    cleanup.close_stale_orders(now=now)
    for oid, status in zip(selected, (S.cancelled, S.cancelled, S.done)):
        after = snapshot(oid)
        assert after["status"] == status and pending(after) == CLEAR
    after = snapshot(scheduled)
    assert after["status"] == S.expired
    assert pending(after) == pending(scheduled_before)
    assert after["wait_until"] == scheduled_before["wait_until"]


@pytest.mark.parametrize("action", ["no_drivers", "wait_cancel"])
def test_wait_episode_disposition_is_selected_not_blanket_expired(
    terminal_case, action
):
    make, passenger, driver, now, _ = terminal_case
    oid, before = make(
        "accept",
        status=S.searching if action == "no_drivers" else S.expired,
        wait_until=now + timedelta(minutes=10),
        current_offer_driver_id=None,
        offer_expires_at=None,
    )
    with Session(engine) as session:
        if action == "no_drivers":
            isv.try_offer_next(session, session.get(InstantOrder, oid), notify=False)
        else:
            isv.cancel_order(session, oid, isv.Actor.passenger, passenger["id"])
    after = snapshot(oid)
    assert pending(after) == (pending(before) if action == "no_drivers" else CLEAR)
    assert after["status"] == (S.expired if action == "no_drivers" else S.cancelled)
    assert after["wait_until"] == (
        before["wait_until"] if action == "no_drivers" else None
    )


ORDER_BODY = {
    "from_lat": 52.591,
    "from_lng": 58.317,
    "to_lat": 52.716,
    "to_lng": 58.664,
    "from_text": "Synthetic pickup",
    "to_text": "Synthetic destination",
}


@pytest.mark.parametrize("existing", [False, True])
def test_actual_order_again_clears_only_own_queue_in_both_commit_branches(
    terminal_case, client, user_factory, existing
):
    make, passenger, _, now, ids = terminal_case
    queued, _ = make("wait_end", wait_until=now + timedelta(hours=1))
    foreign_user = user_factory("TerminalForeignQueue")
    foreign, foreign_before = make(
        "wait_end", passenger_id=foreign_user["id"], wait_until=now + timedelta(hours=1)
    )
    no_wait, no_wait_before = make("wait_end", wait_until=None)
    live = make("accept", status=S.created)[0] if existing else None
    response = client.post(
        "/instant/orders", headers=passenger["auth"], json=ORDER_BODY
    )
    assert response.status_code == 200, response.text
    returned = response.json()["id"]
    if existing:
        assert returned == live
    else:
        assert returned not in ids
        ids.append(returned)
    assert snapshot(queued)["wait_until"] is None and pending(snapshot(queued)) == CLEAR
    assert snapshot(foreign) == foreign_before
    assert snapshot(no_wait) == no_wait_before


def test_actual_order_again_bulk_exact_ids_stale_local_and_no_implicit_load(
    terminal_case,
):
    """Real router function/SQL; separate tests exercise HTTP authentication."""
    from app.models import User
    from app.routers import instant as router

    make, passenger, _, now, _ = terminal_case
    matched, _ = make("wait_end", wait_until=None)
    stale_loser, _ = make("wait_end", wait_until=now + timedelta(hours=1))
    absent, _ = make("wait_end", wait_until=now + timedelta(hours=1))
    live, _ = make("accept", status=S.created)
    seen = []
    with Session(engine, autoflush=False, expire_on_commit=False) as session:
        winner = session.get(InstantOrder, matched)
        loser = session.get(InstantOrder, stale_loser)
        user = session.get(User, passenger["id"])
        # Local nonmatch, DB match; and local match, DB nonmatch. No dirty status.
        session.commit()
        with engine.begin() as c:
            c.execute(
                update(InstantOrder)
                .where(InstantOrder.id == stale_loser)
                .values(wait_until=None)
            )
            c.execute(
                update(InstantOrder)
                .where(InstantOrder.id == matched)
                .values(wait_until=now + timedelta(hours=1))
            )
        assert winner.wait_until is None and loser.wait_until is not None
        # The absent owner is intentionally never loaded in this Session.
        assert session.identity_key(InstantOrder, absent) not in session.identity_map
        winner.matching_resume_attempts = 99
        loser.matching_resume_attempts = 88
        loser_history = inspect(loser).attrs.matching_resume_attempts.history
        winner.from_text = "unrelated preserved bulk text"

        def before_commit(actual):
            if seen:
                return
            assert actual is session
            assert tuple(getattr(winner, n) for n in PENDING) == CLEAR
            assert all(
                not inspect(winner).attrs[n].history.has_changes() for n in PENDING
            )
            assert winner.wait_until is None
            assert loser.matching_resume_attempts == 88
            assert (
                inspect(loser).attrs.matching_resume_attempts.history == loser_history
            )
            assert inspect(winner).attrs.from_text.history.has_changes()
            assert (
                session.identity_key(InstantOrder, absent) not in session.identity_map
            )
            seen.append(True)

        event.listen(session, "before_commit", before_commit)
        try:
            result = router.create_order(router.OrderIn(**ORDER_BODY), user, session)
        finally:
            event.remove(session, "before_commit", before_commit)
        assert result["id"] == live and seen == [True]
    assert pending(snapshot(matched)) == pending(snapshot(absent)) == CLEAR
    assert (
        snapshot(stale_loser)["matching_resume_attempts"] == 88
    )  # Ordinary commit flush, not clear.


@pytest.mark.parametrize("operation", ["accept", "cancel"])
def test_cas_loser_dirty_pending_history_not_synchronized_before_existing_commit(
    terminal_case, operation
):
    make, passenger, driver, _, _ = terminal_case
    oid, _ = make(operation)
    observations = []
    with Session(engine, autoflush=False, expire_on_commit=False) as session:
        order = session.get(InstantOrder, oid)
        session.commit()
        with engine.begin() as c:
            c.execute(
                update(InstantOrder)
                .where(InstantOrder.id == oid)
                .values(status=S.cancelled)
            )
        order.matching_resume_attempts = 99
        previous = inspect(order).attrs.matching_resume_attempts.history

        def before_commit(actual):
            assert actual is session
            assert order.status == S.offered  # No fetch-sync on DB zero-row.
            assert order.matching_resume_attempts == 99
            assert inspect(order).attrs.matching_resume_attempts.history == previous
            observations.append(True)

        event.listen(session, "before_commit", before_commit)
        try:
            with pytest.raises(HTTPException) as error:
                invoke(session, oid, operation, passenger, driver)
            assert error.value.status_code == 409
        finally:
            event.remove(session, "before_commit", before_commit)
    assert observations == [True]
    # Existing commit still flushes caller changes even after a zero-row CAS.
    # This is not disposal/repair, nor a new rollback contract for old callers.
    assert snapshot(oid)["matching_resume_attempts"] == 99
    assert snapshot(oid)["status"] == S.cancelled


def test_real_driver_cancel_first_commit_clears_before_event_and_reassign(
    terminal_case,
):
    make, _, driver, now, _ = terminal_case
    oid, before = make(
        "accept", status=S.accepted, driver_id=driver["id"], accepted_at=now
    )
    commits = []
    with Session(engine) as session:

        def after_commit(actual):
            assert actual is session
            row = snapshot(oid)
            commits.append((row["status"], row["search_revision"], pending(row)))

        event.listen(session, "after_commit", after_commit)
        try:
            isv.cancel_order(
                session,
                oid,
                isv.Actor.driver,
                driver["id"],
                "synthetic interruption boundary",
            )
        finally:
            event.remove(session, "after_commit", after_commit)
    assert commits[0] == (S.cancelled, before["search_revision"], CLEAR)
    assert all(item[2] == CLEAR for item in commits)
    after = snapshot(oid)
    assert after["search_revision"] == 42 and after["status"] == S.expired
    # This observes separate commits, NOT durable recovery between them.


@pytest.mark.parametrize("operation", ["finish_early", "stuck", "wait_end"])
@pytest.mark.parametrize("stale_fields", [(n,) for n in PENDING] + [PENDING])
def test_orm_clear_covers_stale_local_null_but_committed_nonnull(
    terminal_case, operation, stale_fields
):
    make, passenger, driver, now, _ = terminal_case
    oid, _ = make(operation, **dict.fromkeys(stale_fields))
    with Session(engine, expire_on_commit=False) as session:
        order = session.get(InstantOrder, oid)
        assert all(getattr(order, n) is None for n in stale_fields)
        session.commit()
        with engine.begin() as other:
            other.execute(
                update(InstantOrder)
                .where(InstantOrder.id == oid)
                .values(
                    matching_resume_revision=41,
                    matching_resume_next_at=now + timedelta(minutes=1),
                    matching_resume_attempts=7,
                )
            )
        assert pending(snapshot(oid)) == (41, now + timedelta(minutes=1), 7)
        assert all(getattr(order, n) is None for n in stale_fields)
        invoke(session, oid, operation, passenger, driver)
    after = snapshot(oid)
    assert after["status"] == (S.done if operation == "finish_early" else S.expired)
    assert pending(after) == CLEAR


@pytest.mark.parametrize("operation", ["finish_early", "stuck", "wait_end"])
def test_orm_terminal_status_and_three_nulls_share_actual_sql_update(
    terminal_case, operation
):
    make, passenger, driver, _, _ = terminal_case
    # A status-change control; expired→expired remains in the general matrix.
    changes = {"status": S.searching} if operation == "wait_end" else {}
    oid, _ = make(operation, **dict.fromkeys(PENDING), **changes)
    statements = []
    with engine.connect() as connection:

        def capture(conn, cursor, statement, parameters, context, many):
            normalized = statement.lower()
            if normalized.startswith("update instantorder set"):
                statements.append(normalized.split(" where ", 1)[0])

        event.listen(connection, "before_cursor_execute", capture)
        try:
            with Session(bind=connection) as session:
                invoke(session, oid, operation, passenger, driver)
        finally:
            event.remove(connection, "before_cursor_execute", capture)
    status_updates = [s for s in statements if "status=" in s]
    assert len(status_updates) == 1
    assert all(f"{name}=" in status_updates[0] for name in PENDING)
    # No second pending-only repair UPDATE is permitted.
    assert [
        s for s in statements if any(f"{n}=" in s for n in PENDING)
    ] == status_updates
    assert pending(snapshot(oid)) == CLEAR


@pytest.mark.parametrize("expired", [False, True])
def test_assign_pending_clear_unloaded_scalar_fields_has_no_implicit_read(
    terminal_case, expired
):
    """Supplementary actual helper proof; service UPDATE tested independently."""
    from sqlalchemy.orm import load_only
    from sqlalchemy import select

    make, _, _, _, _ = terminal_case
    oid, before = make("finish_early")
    sql = []
    with engine.connect() as connection:
        with Session(bind=connection) as session:
            if expired:
                order = session.get(InstantOrder, oid)
                session.expire(order, PENDING)
            else:
                order = session.execute(
                    select(InstantOrder)
                    .options(load_only(InstantOrder.id, InstantOrder.status))
                    .where(InstantOrder.id == oid)
                ).scalar_one()
            assert all(n in inspect(order).unloaded for n in PENDING)
            order.from_text = "unrelated preserved assignment"
            old = inspect(order).attrs.from_text.history

            def capture(*args):
                sql.append(args[2])

            event.listen(connection, "before_cursor_execute", capture)
            try:
                isv._assign_pending_clear(order)
            finally:
                event.remove(connection, "before_cursor_execute", capture)
            assert sql == []
            assert tuple(getattr(order, n) for n in PENDING) == CLEAR
            assert all(inspect(order).attrs[n].history.has_changes() for n in PENDING)
            assert inspect(order).attrs.from_text.history == old
            session.rollback()
    assert snapshot(oid) == before


class AfterOrmClearFault(RuntimeError):
    pass


@pytest.mark.parametrize("operation", ["finish_early", "stuck", "wait_end"])
def test_orm_actual_update_failure_rolls_back_status_pending_and_related_write(
    terminal_case, operation
):
    make, passenger, driver, _, _ = terminal_case
    # Require a real status transition in the fault detector.
    changes = {"status": S.searching} if operation == "wait_end" else {}
    oid, before = make(operation, **changes)
    fired = []
    with engine.connect() as connection:
        original = connection.execute(
            text('SELECT name FROM "user" WHERE id=:id'), {"id": passenger["id"]}
        ).scalar_one()
        connection.rollback()

        def fail_after_sql(conn, cursor, statement, parameters, context, many):
            head = statement.lower().split(" where ", 1)[0]
            if (
                head.startswith("update instantorder set")
                and "status=" in head
                and all(f"{n}=" in head for n in PENDING)
            ):
                fired.append(True)
                raise AfterOrmClearFault(
                    "actual terminal UPDATE completed, modeled precommit interruption"
                )

        with Session(bind=connection) as session:
            session.execute(
                text('UPDATE "user" SET name=:name WHERE id=:id'),
                {"name": "uncommitted related sentinel", "id": passenger["id"]},
            )
            event.listen(connection, "after_cursor_execute", fail_after_sql)
            try:
                if operation == "finish_early":
                    with pytest.raises(AfterOrmClearFault):
                        invoke(session, oid, operation, passenger, driver)
                else:
                    worker = (
                        taxi_worker.close_stuck_orders
                        if operation == "stuck"
                        else taxi_worker.finish_expired_waits
                    )
                    assert oid not in worker(session)
            finally:
                event.remove(connection, "after_cursor_execute", fail_after_sql)
                session.rollback()
    assert fired == [True] and snapshot(oid) == before
    with engine.connect() as observer:
        assert (
            observer.execute(
                text('SELECT name FROM "user" WHERE id=:id'), {"id": passenger["id"]}
            ).scalar_one()
            == original
        )
