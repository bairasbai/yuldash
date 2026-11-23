"""Characterize the late-COMMIT release blocker through the real taxi read routes."""
from concurrent.futures import ThreadPoolExecutor
from threading import Event

from fastapi import FastAPI
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, event, text
from sqlmodel import Session, SQLModel
from test_taxi_offered_waypoint_preview import _unexpected
from test_taxi_private_provisional_deadline_postgres import _history, _side_effect_rows, pytestmark  # noqa: F401
from test_taxi_receipted_accept_postgres import (
    _attempt, _known, _order, _receipt, _successor,
    retired_pg, synthetic_route,  # noqa: F401 - pytest fixtures
)
from test_taxi_route_resume_deadline_postgres import _clock, _until

from app import instant_service as isv
from app import taxi_driver_accept_outcome as outcomes
from app import taxi_route_resume as resume
from app import taxi_sealed_offer_accept as assignment
from app.db import get_session
from app.models import Booking, DriverProfile, Rating, TaxiApplication
from app.models import InstantOrderStatus as S
from app.routers import instant
from app.security import make_token

_REAL_RECONCILE = isv.reconcile_offer


def _read_client(db):
    # Real router/auth/serializer without app startup or worker wiring. Only the
    # DB dependency changes, so every request gets a fresh session in this schema.
    app = FastAPI()
    app.include_router(instant.router)

    def isolated_session():
        with Session(db) as session:
            yield session

    app.dependency_overrides[get_session] = isolated_session
    return TestClient(app)


def _create_read_tables(db):
    pending = [m.__table__ for m in (Booking, DriverProfile, Rating, TaxiApplication)]
    owned = set()
    while pending:
        table = pending.pop()
        if table not in owned:
            owned.add(table)
            pending.extend(fk.column.table for fk in table.foreign_keys)
    SQLModel.metadata.create_all(db, tables=owned)


def _get(client, path, actor):
    response = client.get(path, headers={"Authorization": "Bearer " + make_token(actor)})
    assert response.status_code == 200
    return response.json()


def _surface(body):
    # Assert only synthetic, projected facts; don't report raw payloads/phones.
    return (body["status"], body["driver_id"], bool(body["driver_phone"]), bool(body["passenger_phone"]))


def test_pg_known_late_commit_exposes_assignment_through_taxi_reads(request, monkeypatch):
    """Green means the known violation was seen, NOT that public release is safe."""
    original_retirement = request.getfixturevalue("retired_pg")
    _create_read_tables(original_retirement[1])
    writer, reader, args, marked, driver = _history(original_retirement)
    oid, passenger = args["order_id"], args["owner_id"]
    former = resume._state(marked)["route_resume_previous_driver_id"]
    # Reuse actual detail reconciliation that the background synthetic fixture blocks.
    monkeypatch.setattr(isv, "reconcile_offer", _REAL_RECONCILE)
    # This is the only optional read-side egress: no live Redis/provider.
    monkeypatch.setattr(isv, "driver_position", lambda *_: None)
    for name in ("_settle_pickup_fee", "_notify_pickup_fee", "_notify_transition", "_cleanup_tried"):
        monkeypatch.setattr(isv, name, _unexpected)
    deposit_before = _side_effect_rows(reader)
    identity, proof, offer = _successor((writer, reader, args, marked, driver), offer_seconds=120)
    attempt = _attempt(reader, identity)
    before = _order(reader, oid)
    deadline = before["route_resume_deadline_at"]
    assert before["status"] is S.offered and before["offer_expires_at"] == deadline
    detail = f"/instant/orders/{oid}"
    mine = "/instant/orders/mine"

    def assert_denied(client):
        response = client.get(detail, headers={"Authorization": "Bearer " + make_token(former)})
        assert response.status_code == 403

    with reader.connect() as conn:
        schema = conn.exec_driver_sql("SELECT current_schema()").scalar_one()
    prefix = "pgacceptreceipt_"
    assert schema.startswith(prefix) and len(schema) == len(prefix) + 32
    assert all(c in "0123456789abcdef" for c in schema.removeprefix(prefix))
    # DSN inherited from the guarded opt-in retired_pg fixture on #370.
    timeout_db = create_engine(reader.url, hide_parameters=True, connect_args={
        "connect_timeout": 4, "options": f"-csearch_path={schema} -clock_timeout=8000 -cstatement_timeout=12000",
    })
    checked, waiting, physical_commits = [], [], []
    at_commit, release_commit, timeout_entered = Event(), Event(), Event()
    actual_commit = writer.dialect.do_commit

    def final_check(conn, cursor, statement, _parameters, _context, _many):
        statement = " ".join(statement.lower().split())
        pid = conn.connection.driver_connection.get_backend_pid()
        if statement.startswith("insert into taxidriveracceptoutcome"):
            checked.append(("receipt-staged", pid))
        elif (checked and statement.startswith("select instantorder.id from instantorder")
              and "instantorder.route_resume_agreement_seal" in statement and "statement_timestamp()" in statement):
            assert cursor.rowcount == 1
            checked.append(("final-check", pid))

    def hold_commit(raw):
        pid = getattr(raw, "driver_connection", raw).get_backend_pid()
        if checked == [("receipt-staged", pid), ("final-check", pid)] and not at_commit.is_set():
            at_commit.set()
            assert release_commit.wait(24), "test did not release physical commit"
            actual_commit(raw)
            physical_commits.append(pid)
        else:
            actual_commit(raw)

    def timeout_lock(conn, _cursor, statement, _parameters, _context, _many):
        statement = " ".join(statement.lower().split())
        if statement.startswith("select instantorder.id from instantorder") and statement.endswith("for update"):
            waiting.append(conn.connection.driver_connection.get_backend_pid())
            timeout_entered.set()

    try:
        with timeout_db.connect() as conn:
            assert conn.exec_driver_sql("SELECT current_schema()").scalar_one() == schema
            assert conn.exec_driver_sql("SHOW transaction_isolation").scalar_one() == "read committed"
        event.listen(writer, "after_cursor_execute", final_check)
        event.listen(timeout_db, "before_cursor_execute", timeout_lock)
        with _read_client(reader) as client, reader.connect() as observer, ThreadPoolExecutor(max_workers=2) as pool:
            assert observer.exec_driver_sql("SHOW transaction_isolation").scalar_one() == "read committed"
            assert not observer.exec_driver_sql("SELECT pg_is_in_recovery()").scalar_one()
            observer_pid = observer.exec_driver_sql("SELECT pg_backend_pid()").scalar_one()
            with monkeypatch.context() as scoped:
                scoped.setattr(writer.dialect, "do_commit", hold_commit)
                accepting = pool.submit(assignment.accept_sealed_offer_with_outcome, writer, **attempt, proof=proof)
                try:
                    assert at_commit.wait(8)
                    writer_pid = checked[-1][1]
                    assert writer_pid != observer_pid and _clock(observer) < deadline
                    assert _surface(_get(client, detail, passenger)) == ("offered", None, False, False)
                    assert _surface(_get(client, detail, driver)) == ("offered", None, False, False)
                    assert_denied(client)
                    assert _clock(observer) < deadline  # bracket actual pre-cutoff reads with DB time
                    _until(lambda: _clock(observer) >= deadline, "database clock did not reach original cutoff", max_seconds=18)
                    expiring = pool.submit(resume.store_timeout, timeout_db, offer)
                    assert timeout_entered.wait(5)
                    assert len(waiting) == 1 and waiting[0] not in (writer_pid, observer_pid)
                    _until(lambda: observer.execute(text("SELECT :owner = ANY(pg_blocking_pids(:waiting))"),
                                                    {"owner": writer_pid, "waiting": waiting[0]}).scalar_one(),
                           "timeout was not blocked by the actual assignment writer", max_seconds=4)
                    assert not accepting.done() and not expiring.done()
                    # /mine is a pure read. The detail route can reconcile an expired offer.
                    (row,) = _get(client, mine, passenger)
                    assert row["id"] == oid and _surface(row) == ("offered", None, False, False)
                    assert _receipt(reader, oid) == [] and _known(reader, proof, attempt) is outcomes.DriverAcceptEvidence.UNKNOWN
                    held_past_deadline = _clock(observer)
                    assert held_past_deadline >= deadline and not physical_commits
                finally:
                    release_commit.set()
                assert accepting.result(timeout=10) is assignment.SealedAcceptance.ACCEPTED
                assert expiring.result(timeout=10) is None and physical_commits == [writer_pid]
            # This is the WRONG user-visible result (kept as a release-blocker witness).
            assert _surface(_get(client, detail, passenger)) == ("accepted", driver, True, False)
            assert _surface(_get(client, detail, driver)) == ("accepted", driver, False, True)
            (row,) = _get(client, mine, passenger)
            assert row["id"] == oid and _surface(row) == ("accepted", driver, True, False)
            assert_denied(client)
    finally:
        if event.contains(writer, "after_cursor_execute", final_check):
            event.remove(writer, "after_cursor_execute", final_check)
        timeout_db.dispose()

    after = _order(reader, oid)
    assert after["accepted_at"] < deadline <= held_past_deadline
    assert _known(reader, proof, attempt) is outcomes.DriverAcceptEvidence.COMMITTED
    assert len(_receipt(reader, oid)) == 1 and _side_effect_rows(reader) == deposit_before
