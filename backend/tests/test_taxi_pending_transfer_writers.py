"""Existing pending transfer, not producer/consumer/terminal-clear activation.

Representable malformed tuples are tested. Physically undecodable timestamps
can still raise at DBAPI decoding; this suite never claims arbitrary repair.
"""

from contextlib import nullcontext
from datetime import timedelta, timezone
from uuid import uuid4

import pytest
from fastapi import HTTPException
from sqlalchemy import event, inspect, select, text, update
from sqlmodel import Session

from app import instant_service as isv
from app.db import engine
from app.models import InstantOrder, InstantOrderStatus as S
import test_taxi_pending_writer_known_gaps as known
from test_taxi_search_revision_postgres import _disposable_pg_before_client  # noqa: F401

seeded = known.seeded
PENDING = known.PENDING
MAX = 2**63 - 1
ENTRIES = ("start", "advance", "reassign")
OPERATIONS = ("bump", *ENTRIES, "destination", "fallback")


@pytest.fixture
def pending_case(seeded):
    create, passenger, driver, now = seeded
    ids = []

    def make(operation="bump", **changes):
        status = {
            "start": S.created,
            "advance": S.offered,
            "reassign": S.cancelled,
            "fallback": S.offered,
        }.get(operation, S.searching)
        oid, _ = create(status)
        ids.append(oid)
        if changes:
            with engine.begin() as c:
                c.execute(
                    update(InstantOrder).where(InstantOrder.id == oid).values(**changes)
                )
        return oid, known.snapshot(oid)

    try:
        yield make, passenger, driver, now
    finally:
        # Actual cancel/reassign writes DriverCancel before the base fixture's
        # own Notification/OfferDecline/history/order cleanup.
        with engine.begin() as c:
            for oid in ids:
                c.execute(
                    text("DELETE FROM drivercancel WHERE order_id=:id"), {"id": oid}
                )


def invoke(session, oid, operation, now, driver_id=None):
    order = session.get(InstantOrder, oid)
    if operation == "bump":
        return isv._bump_search_revision(session, order)  # Caller owns commit.
    if operation == "start":
        return isv.start_matching(session, order, notify=False)
    if operation == "advance":
        return isv.advance_after_no_accept(session, order, notify=False)
    if operation == "reassign":
        # Real entry helper from a synthetic cancelled fixture; real cancel path
        # is covered separately, not claimed for this setup.
        return isv._reassign_after_driver_cancel(session, oid, driver_id)
    if operation == "destination":
        return isv.apply_destination(
            session,
            order,
            (52.60, 58.33),
            "New destination",
            {"price": 1, "base_price": 1, "distance_km": 3.0, "eta_min": 6.0},
            now,
        )
    price = isv._category_price(session, order, "standard")
    assert price is None or price >= order.ride_price
    return isv.add_fallback_category(session, order, "standard")


def pending(row):
    return tuple(row[n] for n in PENDING)


@pytest.mark.parametrize("operation", OPERATIONS)
@pytest.mark.parametrize("offset", [-30, 30])
@pytest.mark.parametrize("attempts", [0, 2**31 - 1])
def test_actual_writers_transfer_existing_tuple_and_clamp_only_future(
    pending_case, monkeypatch, operation, offset, attempts
):
    make, _, driver, now = pending_case
    monkeypatch.setattr(isv, "utcnow", lambda: now)
    old_next = now + timedelta(seconds=offset)
    oid, before = make(
        operation, matching_resume_next_at=old_next, matching_resume_attempts=attempts
    )
    with Session(engine, expire_on_commit=False) as s:
        order = s.get(InstantOrder, oid)
        invoke(s, oid, operation, now, driver["id"])
        if operation == "bump":
            assert pending(order.model_dump()) == (42, min(old_next, now), attempts)
            s.commit()
        assert order.search_revision == 42
        assert tuple(getattr(order, n) for n in PENDING) == (
            42,
            min(old_next, now),
            attempts,
        )
    after = known.snapshot(oid)
    assert after["search_revision"] == 42 and pending(after) == (
        42,
        min(old_next, now),
        attempts,
    )
    if operation == "fallback":
        for name in (
            "offer_token",
            "current_offer_driver_id",
            "offer_expires_at",
            "ride_price",
            "price_estimate",
        ):
            assert after[name] == before[name]
        with Session(engine) as s:
            invoke(s, oid, operation, now)
        assert known.snapshot(oid) == after


@pytest.mark.parametrize("operation", ("bump", *ENTRIES))
@pytest.mark.parametrize(
    "bad",
    [
        "all_null",
        "revision_null",
        "time_null",
        "attempts_null",
        "mismatch",
        "negative_revision",
        "negative_attempts",
        "old_null",
    ],
)
def test_representable_ineligible_pending_unchanged_without_new_refusal(
    pending_case, monkeypatch, operation, bad
):
    make, _, driver, now = pending_case
    changes = {
        "all_null": dict.fromkeys(PENDING),
        "revision_null": {"matching_resume_revision": None},
        "time_null": {"matching_resume_next_at": None},
        "attempts_null": {"matching_resume_attempts": None},
        "mismatch": {"matching_resume_revision": 40},
        "negative_revision": {"matching_resume_revision": -1},
        "negative_attempts": {"matching_resume_attempts": -1},
        "old_null": {"search_revision": None},
    }[bad]
    oid, before = make(operation, **changes)
    calls = []
    monkeypatch.setattr(isv, "utcnow", lambda: calls.append(True) or now)
    with Session(engine) as s:
        invoke(s, oid, operation, now, driver["id"])
        if operation == "bump":
            s.commit()
    after = known.snapshot(oid)
    assert after["search_revision"] == (1 if bad == "old_null" else 42)
    assert pending(after) == pending(before)
    if operation == "bump":
        assert calls == []


@pytest.mark.parametrize("revision", [MAX - 1, MAX])
@pytest.mark.parametrize("operation", ("bump", *ENTRIES))
def test_existing_max_boundary_not_extended(pending_case, operation, revision):
    make, _, driver, now = pending_case
    oid, before = make(
        operation, search_revision=revision, matching_resume_revision=revision
    )
    with Session(engine) as s:
        if revision == MAX and operation == "bump":
            with pytest.raises(HTTPException) as error:
                invoke(s, oid, operation, now)
            assert error.value.status_code == 409
            s.rollback()
        else:
            invoke(s, oid, operation, now, driver["id"])
            if operation == "bump":
                s.commit()
    after = known.snapshot(oid)
    assert after["search_revision"] == MAX
    assert pending(after) == (
        (MAX, before["matching_resume_next_at"], 3)
        if revision == MAX - 1
        else pending(before)
    )
    if revision == MAX:
        assert after == before


@pytest.mark.parametrize("field", PENDING)
def test_bump_dirty_pending_skips_whole_tuple_and_preserves_histories(
    pending_case, field
):
    make, _, _, now = pending_case
    oid, before = make()
    with Session(engine, expire_on_commit=False) as s:
        order = s.get(InstantOrder, oid)
        values = {PENDING[0]: 40, PENDING[1]: now + timedelta(minutes=2), PENDING[2]: 9}
        setattr(order, field, values[field])
        order.from_text = "unflushed route"
        histories = {
            n: inspect(order).attrs[n].history for n in (*PENDING, "from_text")
        }
        assert isv._bump_search_revision(s, order) == 42
        assert all(inspect(order).attrs[n].history == histories[n] for n in histories)
        assert getattr(order, field) == values[field]
        s.commit()  # Existing caller flush, deliberately not transfer protection.
    after = known.snapshot(oid)
    expected = list(pending(before))
    expected[PENDING.index(field)] = values[field]
    assert pending(after) == tuple(expected) and after["search_revision"] == 42
    assert after["from_text"] == "unflushed route"


@pytest.mark.parametrize("operation", ENTRIES)
@pytest.mark.parametrize("mode", ["autoflush", "disabled", "context"])
def test_entry_dirty_pending_flush_contract_and_activation_gap(
    pending_case, monkeypatch, operation, mode
):
    make, _, driver, now = pending_case
    oid, _ = make(operation)
    monkeypatch.setattr(isv, "utcnow", lambda: now)
    with Session(engine, autoflush=mode != "disabled", expire_on_commit=False) as s:
        order = s.get(InstantOrder, oid)
        order.matching_resume_attempts = 9
        order.matching_resume_next_at = now + timedelta(minutes=1)
        with s.no_autoflush if mode == "context" else nullcontext():
            invoke(s, oid, operation, now, driver["id"])
        # False/context skips transfer; existing entry commit STILL flushes caller
        # writes. The old pending/new generation pair is a known activation gap.
        expected = (
            (42, now, 9) if mode == "autoflush" else (41, now + timedelta(minutes=1), 9)
        )
        assert tuple(getattr(order, n) for n in PENDING) == expected
    assert known.snapshot(oid)["search_revision"] == 42
    assert pending(known.snapshot(oid)) == expected


@pytest.mark.parametrize("unloaded", [False, True])
def test_fresh_capture_syncs_clean_stale_or_unloaded_pending_not_unrelated_dirty(
    pending_case, monkeypatch, unloaded
):
    make, _, _, now = pending_case
    oid, _ = make()
    monkeypatch.setattr(isv, "utcnow", lambda: now)
    with Session(engine, expire_on_commit=False) as s:
        order = s.get(InstantOrder, oid)
        s.commit()
        with engine.begin() as c:
            c.execute(
                update(InstantOrder)
                .where(InstantOrder.id == oid)
                .values(
                    matching_resume_next_at=now - timedelta(minutes=1),
                    matching_resume_attempts=7,
                )
            )
        if unloaded:
            s.expire(order, PENDING)
        else:
            assert order.matching_resume_attempts == 3
        order.from_text = "preserve unrelated dirty"
        assert isv._bump_search_revision(s, order) == 42
        assert tuple(getattr(order, n) for n in PENDING) == (
            42,
            now - timedelta(minutes=1),
            7,
        )
        assert (
            order.from_text == "preserve unrelated dirty"
            and inspect(order).attrs.from_text.history.has_changes()
        )
        s.rollback()
    assert known.snapshot(oid)["search_revision"] == 41


def test_same_value_assignment_is_clean_and_deleted_target_not_revived(pending_case):
    make, _, _, _ = pending_case
    oid, before = make()
    with Session(engine) as s:
        order = s.get(InstantOrder, oid)
        for n in PENDING:
            setattr(order, n, getattr(order, n))
        assert not any(inspect(order).attrs[n].history.has_changes() for n in PENDING)
        isv._bump_search_revision(s, order)
        assert order.matching_resume_revision == 42
        s.rollback()
    with Session(engine) as s:
        order = s.get(InstantOrder, oid)
        s.delete(order)
        isv._bump_search_revision(s, order)
        assert order in s.deleted
        assert tuple(getattr(order, n) for n in PENDING) == pending(before)
        s.rollback()
    assert known.snapshot(oid) == before


@pytest.mark.parametrize("guard", ["driver", "token", "times"])
def test_optional_advance_loser_does_not_transfer_or_emit(
    pending_case, monkeypatch, guard
):
    make, _, _, now = pending_case
    oid, before = make("advance")
    effects = []
    monkeypatch.setattr(isv, "send_push", lambda *a, **k: effects.append(True))
    kwargs = {
        "driver": {"expected_driver_id": -1},
        "token": {"expected_offer_token": "b" * 32},
        "times": {
            "expected_offer_times": (
                now - timedelta(seconds=1),
                before["offer_expires_at"],
            )
        },
    }[guard]
    with Session(engine) as s:
        isv.advance_after_no_accept(s, s.get(InstantOrder, oid), **kwargs)
    assert known.snapshot(oid) == before and effects == []


@pytest.mark.parametrize("operation", ("bump", *ENTRIES))
@pytest.mark.parametrize("mutation", ["attempts", "equivalent_timestamp_spelling"])
def test_real_sqlite_after_capture_trigger_causes_exact_pending_cas_loser(
    pending_case, operation, monkeypatch, mutation
):
    if engine.dialect.name != "sqlite":
        pytest.skip("SQLite RETURNING/AFTER-trigger control")
    make, _, driver, now = pending_case
    oid, before = make(operation)
    name = "pending_cas_" + uuid4().hex
    if mutation == "equivalent_timestamp_spelling":
        # Actual AFTER trigger changes raw storage, not the decoded timestamp.
        old_next = before["matching_resume_next_at"].replace(microsecond=0)
        with engine.begin() as seed:
            seed.execute(
                update(InstantOrder)
                .where(InstantOrder.id == oid)
                .values(matching_resume_next_at=old_next)
            )
        before = known.snapshot(oid)
    assignment = (
        "matching_resume_attempts=matching_resume_attempts+1"
        if mutation == "attempts"
        else "matching_resume_next_at=substr(matching_resume_next_at,1,19)"
    )
    effects = []
    monkeypatch.setattr(isv, "send_push", lambda *a, **k: effects.append(True))
    with engine.connect() as c:
        c.exec_driver_sql(
            f"CREATE TEMP TRIGGER {name} AFTER UPDATE OF search_revision ON main.instantorder WHEN NEW.id={oid} AND NEW.search_revision IS OLD.search_revision BEGIN UPDATE instantorder SET {assignment} WHERE id=NEW.id; END"
        )
        c.commit()
        try:
            with Session(bind=c) as s:
                if operation == "bump":
                    with pytest.raises(HTTPException) as error:
                        invoke(s, oid, operation, now)
                    assert error.value.status_code == 409
                    assert s.execute(
                        select(InstantOrder.matching_resume_attempts).where(
                            InstantOrder.id == oid
                        )
                    ).scalar_one() == (4 if mutation == "attempts" else 3)
                    s.rollback()
                else:
                    invoke(s, oid, operation, now, driver["id"])
            after = known.snapshot(oid)
            assert (
                after["search_revision"] == 41 and after["status"] == before["status"]
            )
            assert pending(after) == (
                pending(before)
                if operation == "bump" or mutation != "attempts"
                else (41, before["matching_resume_next_at"], 4)
            )
            assert effects == []
        finally:
            c.rollback()
            c.exec_driver_sql(f"DROP TRIGGER {name}")
            c.commit()


def test_actual_driver_cancel_reassign_transfers_and_keeps_terminal_gap_separate(
    pending_case,
):
    make, _, driver, now = pending_case
    oid, before = make(
        "bump", status=S.accepted, driver_id=driver["id"], accepted_at=now
    )
    with Session(engine) as s:
        isv.cancel_order(s, oid, isv.Actor.driver, driver["id"], "synthetic cancel")
    after = known.snapshot(oid)
    assert after["search_revision"] == 42 and after["matching_resume_revision"] == 42
    assert after["matching_resume_attempts"] == 3
    assert after["reassigns"] == before["reassigns"] + 1


@pytest.mark.parametrize("path", ["destination", "alternatives"])
def test_actual_authenticated_http_transfer(pending_case, client, path):
    make, passenger, _, now = pending_case
    oid, before = make("destination" if path == "destination" else "fallback")
    payload = (
        {"to_lat": 52.60, "to_lng": 58.33, "to_text": "New destination"}
        if path == "destination"
        else {"category": "standard"}
    )
    response = client.post(
        f"/instant/orders/{oid}/{path}", headers=passenger["auth"], json=payload
    )
    assert response.status_code == 200, response.text
    after = known.snapshot(oid)
    assert after["search_revision"] == after["matching_resume_revision"] == 42
    assert after["matching_resume_next_at"] == before["matching_resume_next_at"]
    assert after["matching_resume_attempts"] == 3


@pytest.mark.parametrize(
    "bad",
    ["aware", "bool_revision", "bool_attempts", "float_attempts", "overflow_attempts"],
)
def test_supplementary_structural_skip_before_clock_not_db_corruption_repair(
    pending_case, monkeypatch, bad
):
    make, _, _, now = pending_case
    oid, _ = make()
    values = {
        "aware": (41, now.replace(tzinfo=timezone.utc), 3),
        "bool_revision": (True, now, 3),
        "bool_attempts": (41, now, True),
        "float_attempts": (41, now, 0.5),
        "overflow_attempts": (41, now, 2**31),
    }[bad]

    def forbidden():
        raise AssertionError("skip must not consult clock")

    monkeypatch.setattr(isv, "utcnow", forbidden)
    with Session(engine) as s:
        assert isv._pending_transfer(s, oid, 41, values, 42) == ({}, ())


@pytest.mark.parametrize("operation", ("bump", *ENTRIES))
def test_known_activation_gap_old_plus_one_mismatch_can_look_aligned_without_transfer(
    pending_case, monkeypatch, operation
):
    make, _, driver, now = pending_case
    future = now + timedelta(hours=1)
    oid, before = make(
        operation, matching_resume_revision=42, matching_resume_next_at=future
    )
    calls = []
    monkeypatch.setattr(isv, "utcnow", lambda: calls.append(True) or now)
    with Session(engine) as s:
        invoke(s, oid, operation, now, driver["id"])
        if operation == "bump":
            s.commit()
    after = known.snapshot(oid)
    assert after["search_revision"] == 42
    assert pending(after) == pending(before) == (42, future, 3)
    if operation == "bump":
        assert calls == []
    # Accidental equality is NOT validated intent adoption. Consumer activation
    # remains blocked until the broader pending ownership protocol is resolved.


class AfterTransferFault(RuntimeError):
    pass


@pytest.mark.parametrize("operation", ("bump", *ENTRIES))
def test_actual_after_update_fault_rolls_back_owner_pending_and_unrelated_write(
    pending_case, operation, monkeypatch
):
    make, passenger, driver, now = pending_case
    oid, before = make(operation)
    seen = []
    effects = []
    monkeypatch.setattr(isv, "send_push", lambda *a, **k: effects.append(True))
    monkeypatch.setattr(
        known.services, "send_push", lambda *a, **k: effects.append(True)
    )
    with engine.connect() as c:
        original_name = c.execute(
            text('SELECT name FROM "user" WHERE id=:id'), {"id": passenger["id"]}
        ).scalar_one()
        c.rollback()

        def fault(conn, cursor, statement, params, context, many):
            head = statement.lower().split(" where ", 1)[0]
            if (
                head.startswith("update instantorder set")
                and "matching_resume_revision=" in head
            ):
                seen.append(True)
                raise AfterTransferFault("after real owner+pending UPDATE")

        with Session(bind=c) as s:
            s.execute(
                text('UPDATE "user" SET name=:n WHERE id=:id'),
                {"n": "uncommitted transfer sentinel", "id": passenger["id"]},
            )
            event.listen(c, "after_cursor_execute", fault)
            try:
                with pytest.raises(AfterTransferFault, match="after real owner"):
                    invoke(s, oid, operation, now, driver["id"])
                assert seen == [True]
            finally:
                event.remove(c, "after_cursor_execute", fault)
                s.rollback()
    assert known.snapshot(oid) == before and effects == []
    with engine.connect() as observer:
        assert (
            observer.execute(
                text('SELECT name FROM "user" WHERE id=:id'), {"id": passenger["id"]}
            ).scalar_one()
            == original_name
        )


@pytest.mark.parametrize(
    "field", ["matching_resume_revision", "matching_resume_attempts"]
)
def test_sqlite_decodable_real_pending_is_not_silently_repaired(
    pending_case, monkeypatch, field
):
    if engine.dialect.name != "sqlite":
        pytest.skip("actual REAL in SQLite integer-affinity column")
    make, _, _, now = pending_case
    oid, _ = make()
    value = 41.5 if field == "matching_resume_revision" else 0.5
    with engine.begin() as c:
        c.execute(
            text(f"UPDATE instantorder SET {field}=:v WHERE id=:id"),
            {"v": value, "id": oid},
        )
        assert (
            c.execute(
                text(f"SELECT typeof({field}) FROM instantorder WHERE id=:id"),
                {"id": oid},
            ).scalar_one()
            == "real"
        )
    before = known.snapshot(oid)
    assert type(before[field]) is float

    def no_clock():
        raise AssertionError("invalid pending must skip clock")

    monkeypatch.setattr(isv, "utcnow", no_clock)
    with Session(engine) as s:
        isv._bump_search_revision(s, s.get(InstantOrder, oid))
        s.commit()
    after = known.snapshot(oid)
    assert after["search_revision"] == 42 and pending(after) == pending(before)


def test_eligible_invalid_clock_raises_without_repair_and_caller_rollback(
    pending_case, monkeypatch
):
    make, _, _, now = pending_case
    oid, before = make()
    monkeypatch.setattr(isv, "utcnow", lambda: now.replace(tzinfo=timezone.utc))
    with Session(engine) as s:
        with pytest.raises(
            ValueError, match="Pending transfer clock must be a naive UTC datetime"
        ):
            isv._bump_search_revision(s, s.get(InstantOrder, oid))
        s.rollback()
    assert known.snapshot(oid) == before


@pytest.mark.parametrize("operation", ("bump", *ENTRIES))
@pytest.mark.parametrize("suffix", ["", ".000000"])
def test_sqlite_decodable_timestamp_spelling_transfers_without_false_cas_loss(
    pending_case, monkeypatch, operation, suffix
):
    if engine.dialect.name != "sqlite":
        pytest.skip("SQLite raw timestamp representation")
    make, _, driver, now = pending_case
    oid, _ = make(operation)
    old_next = (now - timedelta(seconds=30)).replace(microsecond=0)
    raw = old_next.strftime("%Y-%m-%d %H:%M:%S") + suffix
    with engine.begin() as c:
        c.execute(
            text("UPDATE instantorder SET matching_resume_next_at=:raw WHERE id=:id"),
            {"raw": raw, "id": oid},
        )
    before = known.snapshot(oid)
    assert before["matching_resume_next_at"] == old_next
    monkeypatch.setattr(isv, "utcnow", lambda: now)
    with Session(engine) as s:
        invoke(s, oid, operation, now, driver["id"])
        if operation == "bump":
            s.commit()
    after = known.snapshot(oid)
    assert after["search_revision"] == 42
    assert pending(after) == (42, old_next, 3)


def test_sqlite_raw_pending_cas_uses_text_bind_binary_and_storage_class(
    pending_case, monkeypatch
):
    """Supplementary expression proof, not a substitute for actual writers."""
    if engine.dialect.name != "sqlite":
        pytest.skip("SQLite storage class predicate")
    from sqlalchemy import String, and_
    from sqlalchemy.sql import visitors
    from sqlalchemy.sql.elements import BindParameter

    make, _, _, now = pending_case
    oid, _ = make()
    decoded = now.replace(microsecond=0)
    raw = decoded.strftime("%Y-%m-%d %H:%M:%S")
    monkeypatch.setattr(isv, "utcnow", lambda: now)
    with Session(engine) as s:
        values, predicates = isv._pending_transfer(
            s, oid, 41, (41, decoded, 3, raw, "text"), 42
        )
        assert values["matching_resume_revision"] == 42
        expression = and_(*predicates)
        compiled = str(expression.compile(dialect=engine.dialect))
        assert 'COLLATE "BINARY"' in compiled and "typeof(" in compiled
        raw_binds = [
            node
            for node in visitors.iterate(expression)
            if isinstance(node, BindParameter) and node.value == raw
        ]
        assert len(raw_binds) == 1 and isinstance(raw_binds[0].type, String)
        assert raw_binds[0].type.bind_processor(engine.dialect) is None

        def forbidden():
            raise AssertionError("non-TEXT storage must skip before clock")

        monkeypatch.setattr(isv, "utcnow", forbidden)
        assert isv._pending_transfer(s, oid, 41, (41, decoded, 3, raw, "blob"), 42) == (
            {},
            (),
        )


@pytest.mark.parametrize("path", ["waypoints", "decline", "driver_cancel"])
def test_actual_authenticated_http_pending_transfer_boundaries(
    pending_case, client, path
):
    """Synthetic intent, real authenticated route/decline/cancel service chains.

    Empty presence produces terminal no-driver expiry after the transfer. The
    retained tuple demonstrates transfer only, explicitly NOT terminal clearing.
    """
    make, passenger, driver, now = pending_case
    if path == "waypoints":
        oid, before = make("destination")
        actor, endpoint = passenger, "waypoints"
        payload = {
            "waypoints": [{"lat": 52.595, "lng": 58.32, "text": "Synthetic new stop"}]
        }
    elif path == "decline":
        oid, before = make("advance")
        actor, endpoint = driver, "decline"
        payload = {"expected_offer_token": before["offer_token"], "reason": "far"}
    else:
        oid, before = make(
            "bump", status=S.accepted, driver_id=driver["id"], accepted_at=now
        )
        actor, endpoint = driver, "cancel"
        payload = {"reason": "synthetic cancellation"}
    response = client.post(
        f"/instant/orders/{oid}/{endpoint}", headers=actor["auth"], json=payload
    )
    assert response.status_code == 200, response.text
    after = known.snapshot(oid)
    assert after["status"] == S.expired
    assert after["search_revision"] == 42
    assert pending(after) == (42, before["matching_resume_next_at"], 3)
    if path == "waypoints":
        assert isv.parse_waypoints(after["waypoints_json"])[-1] == {
            "lat": 52.595,
            "lng": 58.32,
            "text": "Synthetic new stop",
            "done": False,  # parse_waypoints canonicalizes an omitted flag.
        }
    elif path == "decline":
        assert after["current_offer_driver_id"] is None
        with engine.connect() as observer:
            assert (
                observer.execute(
                    text(
                        "SELECT count(*) FROM offerdecline WHERE order_id=:id AND driver_id=:driver"
                    ),
                    {"id": oid, "driver": driver["id"]},
                ).scalar_one()
                == 1
            )
    else:
        assert after["reassigns"] == before["reassigns"] + 1
        assert after["driver_id"] is None
        with engine.connect() as observer:
            assert (
                observer.execute(
                    text(
                        "SELECT count(*) FROM drivercancel WHERE order_id=:id AND driver_id=:driver"
                    ),
                    {"id": oid, "driver": driver["id"]},
                ).scalar_one()
                == 1
            )


@pytest.mark.parametrize("operation", ENTRIES)
def test_entry_clean_transfer_selective_sync_before_real_commit(
    pending_case, operation
):
    """Observe the actual commit boundary without replacing commit or flush."""
    make, _, driver, now = pending_case
    oid, before = make(operation)
    observed = []
    with Session(engine, autoflush=False, expire_on_commit=False) as session:
        order = session.get(InstantOrder, oid)
        order.from_text = "unrelated caller route edit"
        old_history = inspect(order).attrs.from_text.history

        def before_commit(actual_session):
            assert actual_session is session
            # Subsequent matcher/expiry commits are not the entry's first commit.
            if observed:
                return
            assert order.search_revision == 42
            assert tuple(getattr(order, name) for name in PENDING) == (
                42,
                before["matching_resume_next_at"],
                3,
            )
            assert all(
                not inspect(order).attrs[name].history.has_changes() for name in PENDING
            )
            assert order.from_text == "unrelated caller route edit"
            assert inspect(order).attrs.from_text.history == old_history
            assert old_history.has_changes()
            observed.append(True)

        event.listen(session, "before_commit", before_commit)
        try:
            invoke(session, oid, operation, now, driver["id"])
        finally:
            event.remove(session, "before_commit", before_commit)
    assert observed == [True]
    after = known.snapshot(oid)
    assert after["search_revision"] == 42
    assert pending(after) == (42, before["matching_resume_next_at"], 3)
    assert after["from_text"] == "unrelated caller route edit"
