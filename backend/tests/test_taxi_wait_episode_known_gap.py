"""Protected-after-read regression; historical reopening diagnostics remain in git/logs.

The original 40b74c2/8887806 diagnostic observed reopening. This explicit
expectation change covers only the captured nonNULL deadline, not client replay. Router clock is paused after its real status/ownership read;
worker SQL/commit, authentication and subsequent HTTP write remain unmocked.
"""

from concurrent.futures import ThreadPoolExecutor
from threading import Event

import pytest
from sqlmodel import Session

from app import taxi_worker
from app.db import engine
from app.models import InstantOrderStatus as S
from app.routers import instant as router
import test_taxi_terminal_pending_clear as terminal
from test_taxi_search_revision_postgres import _disposable_pg_before_client  # noqa: F401

seeded = terminal.seeded
terminal_case = terminal.terminal_case


@pytest.mark.parametrize("closure", ["worker", "order_again"])
def test_protected_after_read_wait_rejects_closed_observed_deadline(
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
    assert response.status_code == 409, response.text
    assert response.json()["detail"] == {
        "code": "wait_snapshot_conflict",
        "ru": "Заказ изменился. Обнови экран и попробуй снова.",
        "ba": "Заказ үҙгәрҙе. Экранды яңырт та тағы ҡабатла.",
    }  # BA wording is a draft, not native linguistic QA.
    after = terminal.snapshot(oid)
    assert after == closed
    assert after["wait_until"] is None and terminal.pending(after) == terminal.CLEAR
