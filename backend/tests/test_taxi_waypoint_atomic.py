"""Isolated SQLite file, real receipt and route SQL, no routes or worker wiring."""
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta
import sqlite3
from threading import Event
from time import monotonic
from uuid import uuid4

import pytest
from sqlalchemy import event, select, update
from sqlmodel import Session, SQLModel, create_engine

from app import instant_service as isv, taxi_route_edit_outcome as outcome
from app.models import InstantOrder, InstantOrderStatus as S, TaxiRouteEditOutcome, User
from app.taxi_route_agreement import bind_waypoint_write
from app.taxi_waypoint_atomic import WaypointCommit, commit_preboard_waypoints
from app.taxi_waypoint_consent import capture_route_write

_KEY = b"\x25" * 32
_STOP = {"lat": 54.72, "lng": 55.82, "text": "SYNTHETIC STOP"}
_QUOTE = dict(ok=True, needs_driver_ok=False, price=160, old_price=240, base_price=143,
              distance_km=7.5, eta_min=11.5)
_RECEIPT = TaxiRouteEditOutcome.__table__
_ORDER = InstantOrder.__table__


@pytest.fixture
def testdb(tmp_path):
    url = f"sqlite:///{tmp_path / 'waypoint-atomic.sqlite3'}"
    db = create_engine(url, hide_parameters=True, connect_args={"timeout": 8})
    event.listen(db, "connect", lambda raw, _: raw.execute("PRAGMA foreign_keys=ON").close())
    SQLModel.metadata.create_all(db)
    with Session(db) as s:
        owner = User(phone="+79999005180")
        driver = User(phone="+79999005181")
        s.add_all([owner, driver])
        s.flush()
        row = InstantOrder(
            passenger_id=owner.id, driver_id=driver.id, status=S.accepted,
            from_lat=54.71, from_lng=55.81, from_text="SYNTHETIC A",
            to_lat=54.73, to_lng=55.83, to_text="SYNTHETIC B",
            ride_price=240, price_estimate=280, ride_base_price=210,
            pickup_fee_kop=4000, promo_discount_kop=10000, distance_km=8.0, eta_min=12.0,
        )
        s.add(row)
        s.commit()
        order_id = row.id
    try:
        yield db, order_id
    finally:
        db.dispose()


def _clock(conn):
    return datetime.fromisoformat(conn.exec_driver_sql("SELECT strftime('%Y-%m-%d %H:%M:%f000', 'now')").scalar_one())


def _args(db, oid, *, duration=30):
    with db.connect() as conn, Session(bind=conn) as s:
        row = s.get(InstantOrder, oid)
        owner_id = row.passenger_id
        captured = capture_route_write(row)
        now = _clock(conn)
    valid = dict(key=_KEY, owner_id=owner_id, order_id=oid, requested=[_STOP], quote=_QUOTE)
    token = bind_waypoint_write(**valid, source=captured, issued_at=now - timedelta(seconds=1),
                                valid_until=now + timedelta(seconds=duration))
    return dict(**valid, agreement=token, attempt_id=str(uuid4()), receipt_expires_at=now + timedelta(minutes=3))


def _state(db, oid):
    with db.connect() as conn:
        row = conn.execute(select(_ORDER).where(_ORDER.c.id == oid)).mappings().one()
        receipt = conn.execute(select(_RECEIPT).where(_RECEIPT.c.order_id == oid)).mappings().all()
        return row, receipt


def _wait_db(db, boundary):
    end = monotonic() + 5
    with db.connect() as conn:
        while _clock(conn) < boundary:
            assert monotonic() < end
            Event().wait(0.01)


def test_atomic_commit_single_receipt_and_no_legacy_or_external_effects(testdb, monkeypatch):
    db, oid = testdb
    args = _args(db, oid)
    before, _ = _state(db, oid)
    seen = []

    def witness(conn, cursor, statement, parameters, context, executemany):
        if statement.startswith("UPDATE instantorder SET"):
            seen.append(_state(db, oid))

    def unexpected(*args, **kwargs):
        pytest.fail("new transaction triggered an old external side effect")

    for name in ("destination_quote", "livepos_get", "notify_driver_destination"):
        monkeypatch.setattr(isv, name, unexpected)
    event.listen(db, "before_cursor_execute", witness)
    assert commit_preboard_waypoints(db, **args) is WaypointCommit.COMMITTED
    assert len(seen) == 1 and seen[0] == (before, [])
    after, receipt = _state(db, oid)
    assert (after["ride_price"], after["price_estimate"], after["ride_base_price"]) == (160, 200, 143)
    assert (after["distance_km"], after["eta_min"], after["destination_ack_at"]) == (7.5, 11.5, None)
    assert isv.parse_waypoints(after["waypoints_json"]) == [_STOP | {"done": False}]
    assert after["promo_discount_kop"] < before["promo_discount_kop"]
    assert after["status"] == S.accepted and after["price_final"] is None and not after["paid"]
    assert len(receipt) == 1 and receipt[0]["binding"] == args["agreement"].binding
    assert receipt[0]["attempt_id"] == args["attempt_id"]
    assert receipt[0]["expires_at"] == args["receipt_expires_at"]
    assert commit_preboard_waypoints(db, **args) is WaypointCommit.UNKNOWN
    assert commit_preboard_waypoints(db, **(args | {"attempt_id": str(uuid4())})) is WaypointCommit.UNKNOWN
    assert _state(db, oid) == (after, receipt) and len(seen) == 1


def test_prior_exact_evidence_cannot_repeat_or_extend_write_even_if_route_matches(testdb):
    db, oid = testdb
    args = _args(db, oid)
    # Synthetic prior committed evidence, without changing the source. This
    # intentionally exercises the StageResult.EXACT branch rather than just
    # HMAC refusal after the normal successful edit advances the route stamp.
    with db.begin() as conn:
        conn.exec_driver_sql("BEGIN IMMEDIATE")
        assert outcome.stage_committed(
            conn, owner_id=args["owner_id"], order_id=oid, attempt_id=args["attempt_id"],
            binding=args["agreement"].binding, expires_at=args["receipt_expires_at"], now=_clock(conn),
        ) is outcome.StageResult.STAGED
    original = _state(db, oid)
    assert commit_preboard_waypoints(db, **(args | {
        "receipt_expires_at": args["receipt_expires_at"] + timedelta(days=20),
    })) is WaypointCommit.UNKNOWN
    assert _state(db, oid) == original


@pytest.mark.parametrize("mutation", ("owner", "phase", "route", "time"))
def test_first_read_after_real_writer_lock_refuses_stale_or_expired(testdb, mutation):
    db, oid = testdb
    args = _args(db, oid, duration=1.5 if mutation == "time" else 30)
    entered = Event()
    original = _state(db, oid)[0]
    workers = []

    def witness(conn, cursor, statement, parameters, context, executemany):
        if statement == "BEGIN IMMEDIATE":
            workers.append(conn.connection.driver_connection)
            entered.set()

    with ThreadPoolExecutor(max_workers=1) as pool:
        with db.begin() as racer:
            racer.exec_driver_sql("BEGIN IMMEDIATE")
            assert racer.connection.driver_connection.in_transaction
            event.listen(db, "before_cursor_execute", witness)
            future = pool.submit(commit_preboard_waypoints, db, **args)
            assert entered.wait(timeout=5)
            assert len(workers) == 1 and workers[0] is not racer.connection.driver_connection
            assert not future.done()
            if mutation == "time":
                _wait_db(db, args["agreement"].valid_until)
            else:
                changes = {
                    "owner": {"passenger_id": original["driver_id"]},
                    "phase": {"status": S.onboard},
                    "route": {"to_text": "SYNTHETIC NEW DESTINATION"},
                }[mutation]
                racer.execute(update(_ORDER).where(_ORDER.c.id == oid).values(**changes))
        assert future.result(timeout=10) is WaypointCommit.UNKNOWN
    after, receipt = _state(db, oid)
    assert receipt == [] and after["waypoints_json"] == original["waypoints_json"]
    assert after["ride_price"] == original["ride_price"]
    if mutation != "time":
        assert all(after[name] == value for name, value in changes.items())


@pytest.mark.parametrize("boundary", ("quote", "receipt"))
def test_final_sql_refuses_after_staging_if_time_expires(testdb, boundary):
    db, oid = testdb
    args = _args(db, oid, duration=1.5 if boundary == "quote" else 30)
    if boundary == "receipt":
        with db.connect() as conn:
            args["receipt_expires_at"] = _clock(conn) + timedelta(seconds=1.5)
    stop_at = args["agreement"].valid_until if boundary == "quote" else args["receipt_expires_at"]
    before = _state(db, oid)
    observed = []

    def witness(conn, cursor, statement, parameters, context, executemany):
        if statement.startswith("UPDATE instantorder SET"):
            # Verify stage has already written the actual row in this local transaction.
            assert conn.scalar(select(_RECEIPT.c.attempt_id)) == args["attempt_id"]
            _wait_db(db, stop_at)
            observed.append(True)

    event.listen(db, "before_cursor_execute", witness)
    assert commit_preboard_waypoints(db, **args) is WaypointCommit.UNKNOWN
    assert observed == [True] and _state(db, oid) == before


def test_final_zero_row_cas_rolls_back_a_real_staged_receipt(testdb):
    db, oid = testdb
    args = _args(db, oid)
    before = _state(db, oid)
    with db.begin() as conn:
        # Deliberate synthetic trigger in this one private SQLite file, to
        # provoke actual zero-row DML after staging within the same transaction.
        conn.exec_driver_sql("""
            CREATE TRIGGER synthetic_change_after_stage AFTER INSERT ON taxirouteeditoutcome
            BEGIN
                UPDATE instantorder SET ride_base_price=123 WHERE id=NEW.order_id;
            END
        """)
    counts = []

    def witness(conn, cursor, statement, parameters, context, executemany):
        if statement.startswith("UPDATE instantorder SET"):
            assert conn.scalar(select(_ORDER.c.ride_base_price).where(_ORDER.c.id == oid)) == 123
            counts.append(True)

    event.listen(db, "before_cursor_execute", witness)
    assert commit_preboard_waypoints(db, **args) is WaypointCommit.UNKNOWN
    assert counts == [True] and _state(db, oid) == before


@pytest.mark.parametrize("lost_ack", (False, True))
def test_dbapi_commit_uncertainty_does_not_claim_success_or_retry(testdb, monkeypatch, lost_ack):
    db, oid = testdb
    args = _args(db, oid)
    before = _state(db, oid)
    original_commit = db.dialect.do_commit
    attempted = []

    def ambiguous(conn):
        attempted.append(True)
        if lost_ack:
            original_commit(conn)  # actual SQLite transaction persists
        raise sqlite3.OperationalError("synthetic commit boundary interruption")

    with monkeypatch.context() as m:
        m.setattr(db.dialect, "do_commit", ambiguous)
        assert commit_preboard_waypoints(db, **args) is WaypointCommit.UNKNOWN
    assert attempted == [True]
    with db.connect() as conn:
        now = _clock(conn)
    evidence = outcome.committed_evidence(db, owner_id=args["owner_id"], order_id=oid,
                                          attempt_id=args["attempt_id"], binding=args["agreement"].binding, now=now)
    if lost_ack:
        assert evidence is outcome.CommittedEvidence.COMMITTED
        after = _state(db, oid)
        assert after[0]["ride_price"] == 160 and len(after[1]) == 1
        assert commit_preboard_waypoints(db, **args) is WaypointCommit.UNKNOWN
        assert _state(db, oid) == after
    else:
        assert evidence is outcome.CommittedEvidence.UNKNOWN and _state(db, oid) == before


def test_noop_stale_actor_and_disabled_fk_do_not_stage(testdb):
    db, oid = testdb
    args = _args(db, oid)
    before = _state(db, oid)
    assert commit_preboard_waypoints(db, **(args | {"requested": []})) is WaypointCommit.UNKNOWN
    assert commit_preboard_waypoints(db, **(args | {"owner_id": 999999})) is WaypointCommit.UNKNOWN
    with db.connect() as conn:
        conn.exec_driver_sql("PRAGMA foreign_keys=OFF")
        assert conn.exec_driver_sql("PRAGMA foreign_keys").scalar_one() == 0
        conn.commit()
    assert commit_preboard_waypoints(db, **args) is WaypointCommit.UNKNOWN
    assert _state(db, oid) == before


@pytest.mark.parametrize("mode", ("raw_autocommit", "sa_autocommit", "raw_owned_tx", "raw_autocommit_open"))
def test_sqlite_connection_preflight_never_strands_a_physical_writer_lock(testdb, mode):
    if mode.startswith("raw_autocommit") and not hasattr(sqlite3.Connection, "autocommit"):
        pytest.skip("sqlite3 explicit autocommit is Python 3.12+")
    db, oid = testdb
    valid = _args(db, oid)
    before = _state(db, oid)
    if mode.startswith("raw_autocommit"):
        wrong = create_engine(db.url, connect_args={"autocommit": True, "timeout": 0})
    elif mode == "sa_autocommit":
        wrong = create_engine(db.url, isolation_level="AUTOCOMMIT",
                              skip_autocommit_rollback=True, connect_args={"timeout": 0})
    else:
        wrong = create_engine(db.url, connect_args={"timeout": 0})
    if mode in ("raw_owned_tx", "raw_autocommit_open"):
        # Simulate an already unsafe checkout (not created by this operation).
        event.listen(wrong, "checkout", lambda raw, *_: raw.execute("BEGIN IMMEDIATE").close())
    event.listen(wrong, "connect", lambda raw, _: raw.execute("PRAGMA foreign_keys=ON").close())
    observed = []

    def witness(conn, cursor, statement, parameters, context, executemany):
        observed.append(statement)

    event.listen(wrong, "before_cursor_execute", witness)
    try:
        result = commit_preboard_waypoints(wrong, **valid)
        # Prove a different, valid connection can really acquire the SQLite
        # writer lock while unsupported engine is still alive and pooled.
        control = create_engine(db.url, connect_args={"timeout": 0})
        try:
            with control.begin() as conn:
                conn.exec_driver_sql("BEGIN IMMEDIATE")
                assert conn.connection.driver_connection.in_transaction
        finally:
            control.dispose()
        assert result is WaypointCommit.UNKNOWN and observed == []
        assert _state(db, oid) == before
        assert commit_preboard_waypoints(db, **valid) is WaypointCommit.COMMITTED
    finally:
        wrong.dispose()
