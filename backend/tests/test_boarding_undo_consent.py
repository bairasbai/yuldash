"""Исправление посадки только после ответа пассажира (решение Александра 24.10.2026).

HTTP двух сторон; SQLite проверяет условные записи, PostgreSQL — также настоящую гонку ответов.
"""
import importlib.util
from pathlib import Path

import pytest
from sqlalchemy import update
from sqlmodel import Session

from app.db import engine
from app.models import Booking, BookingStatus, UserRole
from app.routers import bookings as routes
from test_driver_marks_boarding import _board, _booking, _notes, _trip

TITLE = "Водитель просит исправить посадку"


def _request(client, drv, bid):
    return client.post(f"/bookings/{bid}/boarded/undo", headers=drv["auth"])


def _answer(client, pax, bid, token, confirm=True):
    return client.post(f"/bookings/{bid}/boarded/undo/respond", headers=pax["auth"],
                       json={"request_id": token, "confirm": confirm})


def _asked(client, factory, tag):
    drv, pax, ride, bid = _trip(client, factory, f"consent-{tag}")
    assert _board(client, drv, bid).status_code == 200
    response = _request(client, drv, bid)
    assert response.status_code == 200, response.text
    assert response.json()["boarding_undo_pending"] is True
    return drv, pax, ride, bid, response.json()["boarding_undo_request_id"]


def test_request_preserves_boarding_and_all_protection(client, user_factory):
    drv, pax, _ride, bid, _token = _asked(client, user_factory, "protection")
    mark = _booking(bid).onboard_at
    assert mark is not None and _booking(bid).status == BookingStatus.onboard
    for actor, suffix, body in ((drv, "no-show", None), (drv, "cancel", {"reason": "no_show"}),
                                (pax, "cancel", {"reason": "other"})):
        response = client.post(f"/bookings/{bid}/{suffix}", headers=actor["auth"], json=body)
        assert response.status_code == 409, response.text
    assert _booking(bid).onboard_at == mark
    assert _notes(pax["id"], "Отметку посадки отменили") == 0
    assert _notes(pax["id"], TITLE) == 1


def test_pending_is_visible_to_both_participants_and_driver_list(client, user_factory):
    drv, pax, _ride, bid, token = _asked(client, user_factory, "projection")
    for actor in (drv, pax):
        for endpoint in ("details", "role"):
            row = client.get(f"/bookings/{bid}/{endpoint}", headers=actor["auth"]).json()
            assert row["boarding_undo_pending"] is True and row["boarding_undo_request_id"] == token
            assert 0 < row["boarding_undo_wait_sec"] <= 300
            if endpoint == "role":
                assert row["boarding_undo_sec"] == 0
    row = next(x for x in client.get("/driver/bookings", headers=drv["auth"]).json() if x["booking_id"] == bid)
    assert row["boarding_undo_request_id"] == token and row["boarding_undo_pending"] is True
    assert row["boarding_undo_sec"] == 0


def test_passenger_can_confirm_once_and_replay_is_safe(client, user_factory):
    drv, pax, _ride, bid, token = _asked(client, user_factory, "yes")
    mark = _booking(bid).onboard_at.isoformat(timespec="microseconds")
    for _ in range(2):
        response = _answer(client, pax, bid, token)
        assert response.status_code == 200, response.text
        assert response.json()["status"] == "confirmed" and response.json()["onboard_at"] is None
        assert response.json()["boarding_undo_pending"] is False
        assert response.json()["boarding_undo_request_id"] is None
        assert response.json()["boarding_undo_confirmed_id"] == token
        assert response.json()["boarding_undo_onboard_at"] == mark
    assert _booking(bid).onboard_at is None
    assert _notes(pax["id"], "Отметку посадки отменили") == 1
    assert _request(client, drv, bid).json()["status"] == "confirmed"
    # Оба клиента, в том числе не отправлявший ответ, узнают доказанный регресс этой посадки.
    for actor in (drv, pax):
        for endpoint in ("role", "details"):
            row = client.get(f"/bookings/{bid}/{endpoint}", headers=actor["auth"]).json()
            assert row["boarding_undo_confirmed_id"] == token and row["boarding_undo_onboard_at"] == mark
            assert row["onboard_at"] is None


def test_refusal_and_repeated_driver_requests_do_not_reopen_or_spam(client, user_factory):
    drv, pax, _ride, bid, token = _asked(client, user_factory, "no")
    mark = _booking(bid).onboard_at
    for _ in range(2):
        response = _answer(client, pax, bid, token, False)
        assert response.status_code == 200 and response.json()["status"] == "onboard", response.text
        assert response.json()["boarding_undo_pending"] is False
        assert _request(client, drv, bid).json()["boarding_undo_pending"] is False
    assert _answer(client, pax, bid, token, True).status_code == 409
    assert _notes(pax["id"], TITLE) == 1
    assert _notes(pax["id"], "Отметку посадки отменили") == 0
    assert _booking(bid).onboard_at == mark


def test_repeated_pending_request_keeps_nonce_and_original_deadline(client, user_factory):
    drv, pax, _ride, bid, token = _asked(client, user_factory, "duplicate")
    mark = _booking(bid).onboard_at
    for _ in range(3):
        response = _request(client, drv, bid)
        assert response.status_code == 200 and response.json()["boarding_undo_request_id"] == token
    assert _booking(bid).onboard_at == mark and _notes(pax["id"], TITLE) == 1


@pytest.mark.parametrize("kind", ["driver", "other-passenger", "other-driver"])
def test_only_this_passenger_can_answer(client, user_factory, kind):
    drv, pax, _ride, bid, token = _asked(client, user_factory, f"who-{kind}")
    actor = drv if kind == "driver" else user_factory(f"consent {kind}", role=UserRole.driver if kind == "other-driver" else UserRole.passenger)
    response = _answer(client, actor, bid, token)
    assert response.status_code in (403, 404), response.text
    assert _booking(bid).status == BookingStatus.onboard
    assert _answer(client, pax, bid, "another-request").status_code == 409


def test_old_confirmation_cannot_undo_a_new_boarding(client, user_factory):
    drv, pax, _ride, bid, token = _asked(client, user_factory, "stale")
    assert _answer(client, pax, bid, token).status_code == 200
    assert _board(client, drv, bid).status_code == 200
    assert _booking(bid).boarding_undo_request_id is None
    row = client.get(f"/bookings/{bid}/role", headers=pax["auth"]).json()
    assert row["boarding_undo_confirmed_id"] is None and row["boarding_undo_onboard_at"] is None
    assert row["onboard_at"] is not None
    assert _answer(client, pax, bid, token).status_code == 409
    fresh = _request(client, drv, bid).json()["boarding_undo_request_id"]
    assert fresh and fresh != token
    assert _answer(client, pax, bid, token).status_code == 409
    assert _booking(bid).status == BookingStatus.onboard
    assert _answer(client, pax, bid, fresh).status_code == 200


def test_expiry_keeps_boarding_and_hides_request(client, user_factory, monkeypatch):
    drv, pax, _ride, bid, token = _asked(client, user_factory, "expiry")
    mark = _booking(bid).onboard_at
    monkeypatch.setattr(routes, "utcnow", lambda: mark + routes.BOARDING_UNDO_WINDOW)
    assert _answer(client, pax, bid, token).status_code == 409
    assert _request(client, drv, bid).status_code == 409
    row = client.get(f"/bookings/{bid}/role", headers=pax["auth"]).json()
    assert row["boarding_undo_pending"] is False and row["boarding_undo_request_id"] is None
    assert _booking(bid).status == BookingStatus.onboard and _booking(bid).onboard_at == mark


@pytest.mark.parametrize("terminal", ["cancelled", "done"])
def test_terminal_trip_invalidates_pending_and_old_answers(client, user_factory, terminal):
    drv, pax, _ride, bid, token = _asked(client, user_factory, f"terminal-{terminal}")
    path = "cancel" if terminal == "cancelled" else "driver-status"
    payload = {"reason": "other"} if terminal == "cancelled" else {"status": "done"}
    response = client.post(f"/bookings/{bid}/{path}", headers=drv["auth"], json=payload)
    assert response.status_code == 200, response.text
    assert _answer(client, pax, bid, token).status_code == 409
    row = client.get(f"/bookings/{bid}/details", headers=pax["auth"]).json()
    assert row["boarding_undo_pending"] is False and row["boarding_undo_request_id"] is None
    assert row["status"] == terminal
    if terminal == "cancelled":
        assert row["cancelled_in_transit"] is True and row["can_rate"] is True


@pytest.mark.parametrize("mutation", ["cancel", "finish", "new-request", "decline"])
def test_confirm_cas_rejects_a_change_after_preflight(client, user_factory, monkeypatch, mutation):
    """Детерминированная граница SQL, не заявка на настоящую конкурентность SQLite."""
    _drv, pax, _ride, bid, token = _asked(client, user_factory, f"cas-{mutation}")
    real = Session.execute
    changed = False

    def execute(session, statement, *args, **kwargs):
        nonlocal changed
        if not changed and getattr(statement, "is_update", False) and str(statement.table.name) == "booking":
            changed = True
            values = ({"status": BookingStatus.cancelled} if mutation == "cancel" else
                      {"status": BookingStatus.done} if mutation == "finish" else
                      {"boarding_undo_request_id": "fresh-nonce"} if mutation == "new-request" else
                      {"boarding_undo_answer": False})
            session.connection().execute(update(Booking).where(Booking.id == bid).values(**values))
        return real(session, statement, *args, **kwargs)

    monkeypatch.setattr(Session, "execute", execute)
    response = _answer(client, pax, bid, token)
    assert changed and response.status_code == 409, response.text
    assert _booking(bid).onboard_at is not None


def test_migration_preserves_old_boarding_and_is_idempotent(tmp_path):
    from alembic.migration import MigrationContext
    from alembic.operations import Operations
    from sqlalchemy import create_engine, inspect, text

    migration = Path(__file__).parents[1] / "alembic/versions/cd_boarding_undo_consent_20261024.py"
    spec = importlib.util.spec_from_file_location("consent_migration", migration)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    db = create_engine(f"sqlite:///{tmp_path / 'old-booking.db'}")
    with db.begin() as conn:
        conn.execute(text("CREATE TABLE booking (id INTEGER PRIMARY KEY, status VARCHAR, onboard_at DATETIME)"))
        conn.execute(text("INSERT INTO booking VALUES (1, 'onboard', '2026-10-24 12:00:00')"))
        module.op = Operations(MigrationContext.configure(conn))
        module.upgrade()
        module.upgrade()
        assert {"boarding_undo_request_id", "boarding_undo_requested_at", "boarding_undo_answer"} <= {
            x["name"] for x in inspect(conn).get_columns("booking")}
        row = conn.execute(text("SELECT * FROM booking")).mappings().one()
        assert row["status"] == "onboard" and row["onboard_at"] == "2026-10-24 12:00:00"
        assert row["boarding_undo_request_id"] is None and row["boarding_undo_answer"] is None
    db.dispose()


@pytest.mark.skipif(engine.dialect.name != "postgresql", reason="настоящая гонка row-lock — PostgreSQL")
@pytest.mark.parametrize("answers", [(True, True), (False, False), (True, False), None],
                         ids=["both-confirm", "both-decline", "opposite-answers", "duplicate-request"])
def test_concurrent_request_and_answers_are_single_use_on_pg(client, user_factory, answers):
    from concurrent.futures import ThreadPoolExecutor
    import threading
    from sqlalchemy import event

    drv, pax, _ride, bid = _trip(client, user_factory, f"consent-pg-{answers}")
    assert _board(client, drv, bid).status_code == 200
    token = None
    if answers is not None:
        token = _request(client, drv, bid).json()["boarding_undo_request_id"]
    barrier = threading.Barrier(2, timeout=10)
    field = "boarding_undo_request_id" if answers is None else "boarding_undo_answer"

    def together(conn, cursor, statement, params, context, many):
        if statement.startswith("UPDATE booking SET") and field in statement:
            barrier.wait()

    event.listen(engine, "before_cursor_execute", together)
    try:
        with ThreadPoolExecutor(max_workers=2) as pool:
            if answers is None:
                jobs = [pool.submit(_request, client, drv, bid) for _ in range(2)]
            else:
                jobs = [pool.submit(_answer, client, pax, bid, token, choice) for choice in answers]
            result = [job.result(timeout=20) for job in jobs]
    finally:
        event.remove(engine, "before_cursor_execute", together)
    if answers is None:
        assert [r.status_code for r in result] == [200, 200]
        assert result[0].json()["boarding_undo_request_id"] == result[1].json()["boarding_undo_request_id"]
        assert _notes(pax["id"], TITLE) == 1
        assert _booking(bid).status == BookingStatus.onboard
    elif answers[0] == answers[1]:
        assert [r.status_code for r in result] == [200, 200]
        assert _booking(bid).boarding_undo_answer is answers[0]
    else:
        assert sorted(r.status_code for r in result) == [200, 409]
    assert _notes(pax["id"], "Отметку посадки отменили") <= 1
