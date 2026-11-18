"""Diagnostic: a delayed real /wait can reopen a worker-closed wait episode.

PASS records the current limitation, NOT a fix or authorization to activate a
pending consumer. Router clock is paused after its real status/ownership read;
worker SQL/commit, authentication and subsequent HTTP write remain unmocked.
"""

from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta
from threading import Event

import pytest
from sqlmodel import Session

from app import instant_service as isv, taxi_worker
from app.db import engine
from app.models import InstantOrderStatus as S
from app.routers import instant as router
import test_taxi_terminal_pending_clear as terminal
from test_taxi_search_revision_postgres import _disposable_pg_before_client  # noqa: F401

seeded = terminal.seeded
terminal_case = terminal.terminal_case


@pytest.mark.parametrize("closure", ["worker", "order_again"])
def test_known_gap_delayed_wait_reopens_closed_episode_without_pending(
    terminal_case, client, monkeypatch, closure
):
    make, passenger, _, now, _ = terminal_case
    oid, before = make("wait_end")
    assert before["status"] == S.expired and before["wait_until"] < now
    assert terminal.pending(before) != terminal.CLEAR
    live = make("accept", status=S.created)[0] if closure == "order_again" else None
    read_completed = Event()
    resume_http = Event()
    calls = []

    def hold_clock_after_authorized_status_read():
        # wait_for_driver invokes router.utcnow only after real IDOR/status
        # checks, immediately before assigning its new wait_until.
        calls.append(True)
        read_completed.set()
        assert resume_http.wait(20), "coordinator did not release /wait clock"
        return now

    monkeypatch.setattr(router, "utcnow", hold_clock_after_authorized_status_read)
    with ThreadPoolExecutor(max_workers=1) as executor:
        request = executor.submit(
            client.post, f"/instant/orders/{oid}/wait", headers=passenger["auth"]
        )
        try:
            assert read_completed.wait(10), "HTTP did not reach its post-read clock"
            assert not request.done()
            if closure == "worker":
                with Session(engine) as worker:
                    assert oid in taxi_worker.finish_expired_waits(worker)
            else:
                # Real authenticated order-again request takes its existing-live
                # commit branch after explicitly abandoning the old queue.
                newer = client.post(
                    "/instant/orders",
                    headers=passenger["auth"],
                    json=terminal.ORDER_BODY,
                )
                assert newer.status_code == 200, newer.text
                assert newer.json()["id"] == live
            closed = terminal.snapshot(oid)
            assert closed["status"] == S.expired
            assert closed["wait_until"] is None
            assert terminal.pending(closed) == terminal.CLEAR
            assert closed["search_revision"] == before["search_revision"]
        finally:
            resume_http.set()
            # Join before own-order fixture teardown, including failed assertions.
            response = request.result(timeout=20)
    assert calls == [True]
    assert response.status_code == 200, response.text
    assert response.json()["ok"] is True
    after = terminal.snapshot(oid)
    expected_deadline = now + timedelta(minutes=isv.settings.order_wait_max_min)
    assert after["wait_until"] == expected_deadline and expected_deadline > now
    assert after["status"] == S.expired
    assert after["search_revision"] == before["search_revision"]
    assert terminal.pending(after) == terminal.CLEAR
    # A new wait episode exists, but this inactive slice has no producer. The
    # stale request was not rejected by an episode CAS; no safety claim follows.
