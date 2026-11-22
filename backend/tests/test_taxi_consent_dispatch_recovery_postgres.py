"""The HTTP process and timer compete on a real PostgreSQL order, not a thread mock."""
import threading
import time
from concurrent.futures import ThreadPoolExecutor

import pytest
from sqlalchemy import event, text
from sqlmodel import Session

from app import instant_service as isv
from app.db import engine
from app.models import InstantOrderStatus as S
from app.routers import instant
from test_taxi_consent_dispatch_recovery import _stop_after_commit, driver_offers as driver_offers
from test_taxi_consent_dispatch_recovery import recovery_worker as recovery_worker
from test_taxi_quote_consent import consent_market as consent_market
from test_taxi_quote_consent import confirm, live_driver, orders, quote


pytestmark = pytest.mark.skipif(engine.dialect.name != "postgresql", reason="independent PostgreSQL sessions required")


def _wait_for_competitor(winner_pid, competing_call):
    # A competing claim can wait on the first row lock or skip the locked row.
    # In both cases the competitor has actually run before the first claim resumes.
    for _ in range(180):
        if competing_call.done():
            competing_call.result()
            return
        with Session(engine) as observer:
            waits = observer.execute(text(
                "SELECT EXISTS(SELECT 1 FROM pg_stat_activity "
                "WHERE :blocker = ANY(pg_blocking_pids(pid)))"
            ), {"blocker": winner_pid}).scalar_one()
        if waits:
            return
        time.sleep(.025)
    raise AssertionError("competing dispatch neither completed nor waited on the first SQL connection")


@pytest.mark.parametrize("first", ["http", "worker"])
def test_http_and_worker_cannot_offer_the_same_order_twice(
    client, user_factory, consent_market, monkeypatch, driver_offers, recovery_worker, first,
):
    driver = live_driver(client, user_factory)
    passenger = user_factory("SyntheticConcurrentDispatch")
    price = quote(client, passenger)
    admitted, resume_http = threading.Event(), threading.Event()
    choosing, finish_selection = threading.Event(), threading.Event()
    winner_pid = []
    original_candidates = isv.candidates

    def pause_http(*args, **kwargs):
        admitted.set()
        if first == "worker":
            assert resume_http.wait(15), "HTTP test barrier not released"

    def choose(redis, session, order, *args, **kwargs):
        if not choosing.is_set():
            winner_pid.append(session.connection().connection.driver_connection.get_backend_pid())
            choosing.set()
            assert finish_selection.wait(15), "candidate test barrier not released"
        return original_candidates(redis, session, order, *args, **kwargs)

    monkeypatch.setattr(instant, "moderate_open_text", pause_http)
    monkeypatch.setattr(isv, "candidates", choose)
    with ThreadPoolExecutor(max_workers=2) as pool:
        request = pool.submit(confirm, client, passenger, price)
        try:
            assert admitted.wait(8), "order was never committed"
            if first == "http":
                assert choosing.wait(8), "HTTP did not start candidate selection"
            worker = pool.submit(recovery_worker)
            assert choosing.wait(8), "worker did not start candidate selection"
            resume_http.set()
            _wait_for_competitor(winner_pid[0], worker if first == "http" else request)
        finally:
            resume_http.set()
            finish_selection.set()
        response = request.result(12)
        worker.result(12)
    assert response.status_code == 200, response.text
    saved, = orders(passenger["id"])
    assert (saved.id, saved.status) == (response.json()["order"]["id"], S.offered)
    assert (saved.current_offer_driver_id, saved.search_round) == (driver["id"], 1)
    assert driver_offers == [(driver["id"], saved.id)]


def test_committing_passenger_cancel_wins_over_selected_recovery(
    client, user_factory, consent_market, monkeypatch, driver_offers, recovery_worker,
):
    live_driver(client, user_factory)
    passenger = user_factory("SyntheticDispatchCancel")
    price = quote(client, passenger)
    order = _stop_after_commit(monkeypatch, client, passenger, price)
    cancelling, release = threading.Event(), threading.Event()
    cancellation_pid = []

    def hold_uncommitted_cancel(connection, cursor, statement, parameters, context, many):
        sql = statement.lower()
        if sql.startswith("update instantorder set") and "cancelled_at=" in sql and not cancelling.is_set():
            cancellation_pid.append(connection.connection.driver_connection.get_backend_pid())
            cancelling.set()
            assert release.wait(15), "cancellation test barrier not released"

    event.listen(engine, "after_cursor_execute", hold_uncommitted_cancel)
    try:
        with ThreadPoolExecutor(max_workers=2) as pool:
            cancel = pool.submit(client.post, f"/instant/orders/{order.id}/cancel", headers=passenger["auth"], json={})
            try:
                assert cancelling.wait(8)
                assert orders(passenger["id"])[0].status == S.created
                worker = pool.submit(recovery_worker)
                _wait_for_competitor(cancellation_pid[0], worker)
            finally:
                release.set()
            assert cancel.result(12).status_code == 200
            worker.result(12)
    finally:
        event.remove(engine, "after_cursor_execute", hold_uncommitted_cancel)
    assert orders(passenger["id"])[0].status == S.cancelled
    assert order.id not in recovery_worker()["consent_dispatches"]
    assert driver_offers == []
