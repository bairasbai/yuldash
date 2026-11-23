"""Matcher pause cancels only the search to which the decision belongs."""
from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta
from threading import Event

import pytest
from sqlalchemy import event
from sqlmodel import Session

from app import instant_service as isv, services
from app.db import engine
from app.models import InstantOrder, InstantOrderStatus as S, SafetyProfile
from app.timeutil import utcnow
import test_instant
from test_instant import _offered_order

fake_redis = test_instant.fake_redis


@pytest.mark.parametrize("pause_at", ("before_read", "identity_refresh", "after_read_sqlite"))
def test_lost_matcher_suspension_must_be_a_noop_without_notice(
        client, user_factory, fake_redis, monkeypatch, pause_at, request):
    if pause_at == "after_read_sqlite" and engine.dialect.name != "sqlite":
        pytest.skip("PostgreSQL locks the actual Order until its update commits")
    driver, passenger, offered = _offered_order(
        client, user_factory, fake_redis, "RetirePauseD", "RetirePauseP")
    oid = offered["id"]
    url = f"/instant/orders/{oid}"
    with Session(engine) as s:
        s.add(SafetyProfile(user_id=passenger["id"], suspended_until=utcnow() + timedelta(days=3)))
        s.commit()
    entered, proceed = Event(), Event()
    real_cancel, real_push = isv.cancel_order, services.push_notification
    suspension_notices, cleanups, terminal_rowcounts = [], [], []
    real_cleanup = isv._cleanup_tried

    def pause_suspension(s, order_id, actor, user_id, reason="", **kwargs):
        if reason == "passenger_suspended":
            entered.set()
            assert proceed.wait(10)
            # Explicitly modeled identity refresh in one control. The other
            # leaves the real request identity untouched.
            if pause_at == "identity_refresh":
                s.expire_all()
        return real_cancel(s, order_id, actor, user_id, reason, **kwargs)

    def push(s, uid, category, title_ru, *args, **kwargs):
        if "Аккаунт на паузе" in " ".join(str(x) for x in args):
            suspension_notices.append((uid, kwargs.get("ref_id")))
        return real_push(s, uid, category, title_ru, *args, **kwargs)

    def cleanup(order_id):
        cleanups.append(order_id)
        return real_cleanup(order_id)

    def is_suspension_update(statement, parameters):
        return statement.lstrip().startswith("UPDATE instantorder SET") and "passenger_suspended" in parameters

    def before_terminal_update(conn, cursor, statement, parameters, context, executemany):
        if is_suspension_update(statement, parameters):
            # This is the real DML, after cancel_order loaded and checked Order.
            # Stop before DBAPI execute; SQLite still has not taken a write lock.
            entered.set()
            assert proceed.wait(10)

    def after_terminal_update(conn, cursor, statement, parameters, context, executemany):
        if is_suspension_update(statement, parameters):
            terminal_rowcounts.append(cursor.rowcount)

    if pause_at == "after_read_sqlite":
        event.listen(engine, "before_cursor_execute", before_terminal_update)
        event.listen(engine, "after_cursor_execute", after_terminal_update)
        request.addfinalizer(lambda: event.remove(engine, "before_cursor_execute", before_terminal_update))
        request.addfinalizer(lambda: event.remove(engine, "after_cursor_execute", after_terminal_update))
    else:
        monkeypatch.setattr(isv, "cancel_order", pause_suspension)
    monkeypatch.setattr(isv, "_cleanup_tried", cleanup)
    monkeypatch.setattr(services, "push_notification", push)
    with ThreadPoolExecutor(max_workers=1) as pool:
        decline = pool.submit(client.post, url + "/decline", headers=driver["auth"])
        try:
            assert entered.wait(10), "real matcher never attempted pause cancellation"
            cancelled = client.post(url + "/cancel", headers=passenger["auth"],
                                    json={"reason": "synthetic_self_cancel"})
            assert cancelled.status_code == 200, cancelled.text
            with Session(engine) as s:
                first = s.get(InstantOrder, oid)
                assert first.status == S.cancelled
                assert first.cancel_reason == "synthetic_self_cancel"
                committed_at = first.cancelled_at
        finally:
            proceed.set()
        result = decline.result(timeout=10)
    with Session(engine) as s:
        final = s.get(InstantOrder, oid)
        assert (final.status, final.cancel_reason, final.cancelled_at) == (
            S.cancelled, "synthetic_self_cancel", committed_at,
        )
    assert terminal_rowcounts == ([0] if pause_at == "after_read_sqlite" else [])
    assert cleanups == [oid]
    assert (result.status_code, suspension_notices) == (200, [])



def test_paused_passenger_owned_search_is_cancelled_and_notified(
        client, user_factory, fake_redis, monkeypatch):
    driver, passenger, offered = _offered_order(
        client, user_factory, fake_redis, "MatcherSuspendWinD", "MatcherSuspendWinP")
    oid = offered["id"]
    url = f"/instant/orders/{oid}"
    with Session(engine) as s:
        s.add(SafetyProfile(user_id=passenger["id"], suspended_until=utcnow() + timedelta(days=3)))
        s.commit()
    real_push, real_cleanup = services.push_notification, isv._cleanup_tried
    effects = []

    def push(s, uid, category, title_ru, *args, **kwargs):
        if "Аккаунт на паузе" in " ".join(str(x) for x in args):
            with Session(engine) as observer:
                fresh = observer.get(InstantOrder, oid)
                assert (fresh.status, fresh.cancel_reason) == (S.cancelled, "passenger_suspended")
            effects.append(("notice", uid))
        return real_push(s, uid, category, title_ru, *args, **kwargs)

    def clean(order_id):
        effects.append(("cleanup", order_id))
        return real_cleanup(order_id)

    # No GEO or offer should run after a legitimate pause cancellation.
    def no_candidates(*args, **kwargs):
        pytest.fail("suspended passenger reached matcher candidate lookup")

    monkeypatch.setattr(services, "push_notification", push)
    monkeypatch.setattr(isv, "_cleanup_tried", clean)
    monkeypatch.setattr(isv, "candidates", no_candidates)
    response = client.post(url + "/decline", headers=driver["auth"])
    assert response.status_code == 200, response.text
    assert response.json()["status"] == "cancelled"
    assert effects == [("cleanup", oid), ("notice", passenger["id"])]
