"""RS-03: protection committed between cleanup SELECT and UPDATE must preserve evidence.

Real PostgreSQL READ COMMITTED with a barrier immediately before the actual UPDATE.
SQLite cannot prove this statement-snapshot race and is explicitly skipped.
No production cleanup change is included in the initial RED-test revision.
"""
import json
import threading

import pytest
from sqlalchemy import event
from sqlmodel import Session

from app import cleanup
from app.db import engine
from app.models import Booking, BookingStatus, Incident, Report, SosEvent
from test_cleanup import _ride_with_booking

pytestmark = [
    pytest.mark.skipif(engine.dialect.name != "postgresql", reason="READ COMMITTED race requires PostgreSQL"),
    pytest.mark.timeout(90),
]
FIELDS = ("meet_lat", "meet_lng", "meet_note", "meet_landmark_id", "alight_lat", "alight_lng",
          "alight_note", "alight_landmark_id", "meet_proposal")


@pytest.mark.parametrize("kind", ["sos", "incident", "report"])
@pytest.mark.parametrize("transition", ["insert", "reopen"])
def test_protection_committed_after_selection_prevents_wipe_pg(client, monkeypatch, kind, transition):
    monkeypatch.setattr(cleanup, "DRY", False)
    with Session(engine) as session:
        bid = _ride_with_booking(session, status=BookingStatus.done, depart_days_ago=60, confirmed=True)
        booking = session.get(Booking, bid)
        uid = booking.passenger_id
        booking.alight_lat, booking.alight_lng = 52.8, 58.7
        booking.alight_note = "synthetic dropoff evidence"
        booking.meet_proposal = json.dumps({"proposal_id": "synthetic-retained", "kind": "dropoff",
                                           "point": {"lat": 52.8, "lng": 58.7}})
        session.add(booking)
        session.commit()
        original = tuple(getattr(booking, field) for field in FIELDS)
        if transition == "reopen":
            if kind == "sos":
                blocker = SosEvent(user_id=uid, booking_id=bid, category="other", status="closed")
            elif kind == "incident":
                blocker = Incident(booking_id=bid, type="harassment", reporter_role="passenger",
                                   description="synthetic closed case", status="resolved")
            else:
                blocker = Report(reporter_id=uid, booking_id=bid, category="other", status="closed")
            session.add(blocker)
            session.commit()
            blocker_id = blocker.id
        else:
            blocker_id = None
    barrier = threading.Barrier(2)
    committed = threading.Event()
    errors = []
    actual_rowcounts = []
    selected = []
    armed = {"value": True}

    def is_wipe(statement):
        normalized = " ".join(statement.lower().split())
        return normalized.startswith("update booking set meet_lat=")

    def before_update(conn, cursor, statement, parameters, context, executemany):
        if is_wipe(statement) and armed["value"]:
            armed["value"] = False
            assert selected, "Barrier must be after cleanup eligibility SELECT"
            assert conn.get_isolation_level() == "READ COMMITTED"
            barrier.wait(timeout=15)
            assert committed.wait(15), "Concurrent protection did not commit before UPDATE"
            assert not errors, errors

    def after_statement(conn, cursor, statement, parameters, context, executemany):
        normalized = " ".join(statement.lower().split())
        if normalized.startswith("select id from booking where") and "limit 2000" in normalized:
            selected.append(cursor.rowcount)
        if is_wipe(statement):
            actual_rowcounts.append(cursor.rowcount)

    def protect():
        try:
            barrier.wait(timeout=15)
            with Session(engine) as other:
                if blocker_id is not None:
                    model = {"sos": SosEvent, "incident": Incident, "report": Report}[kind]
                    blocker = other.get(model, blocker_id)
                    blocker.status = "new" if kind == "report" else "open"
                elif kind == "sos":
                    blocker = SosEvent(user_id=uid, booking_id=bid, category="other", status="open")
                elif kind == "incident":
                    blocker = Incident(booking_id=bid, type="harassment", reporter_role="passenger",
                                       description="synthetic concurrent case", status="open")
                else:
                    blocker = Report(reporter_id=uid, booking_id=bid, category="other", status="new")
                other.add(blocker)
                other.commit()
            committed.set()
        except BaseException as error:
            errors.append(repr(error))
            committed.set()

    worker = threading.Thread(target=protect, name=f"rs03-cleanup-{kind}-{transition}")
    event.listen(engine, "before_cursor_execute", before_update)
    event.listen(engine, "after_cursor_execute", after_statement)
    try:
        worker.start()
        reported_count = cleanup.scrub_meeting_points()
    finally:
        barrier.abort()
        committed.set()
        worker.join(timeout=20)
        event.remove(engine, "before_cursor_execute", before_update)
        event.remove(engine, "after_cursor_execute", after_statement)
    assert not worker.is_alive(), "Protection worker must finish before test exits"
    assert not errors, errors
    with Session(engine) as session:
        booking = session.get(Booking, bid)
        retained = tuple(getattr(booking, field) for field in FIELDS)
    assert selected and actual_rowcounts, "Actual selection and UPDATE must both execute"
    assert (retained, actual_rowcounts, reported_count) == (original, [0], 0), {
        "kind": kind, "transition": transition, "retained": retained == original,
        "actual_update_rowcounts": actual_rowcounts, "reported_cleanup_count": reported_count,
        "committed_before_update": committed.is_set(),
    }
