"""Legacy wait expiry never mutates or releases a capped recovery's promo."""
from datetime import timedelta

import pytest
from sqlalchemy import event, update
from sqlmodel import Session

from app import taxi_worker as worker
from app.db import engine
from app.models import InstantOrderStatus as S
from app.timeutil import utcnow
from test_taxi_wait_route_resume_guard import _marker, _O, _seed, _snapshot


def _run(dry=False):
    with Session(engine) as s:
        return worker.finish_expired_waits(s, dry_run=dry)


def _is_expiry(statement):
    sql = statement.strip().upper().split(" WHERE ", 1)[0]
    return sql.startswith("UPDATE INSTANTORDER SET") and "WAIT_UNTIL" in sql


@pytest.mark.parametrize("partial", (False, True))
def test_marked_expired_wait_is_excluded_from_dry_run_and_actual_expiry(user_factory, monkeypatch, partial):
    owner = user_factory("WaitExpiryMarked")
    marker = dict(route_resume_claim_id="") if partial else _marker(owner["id"] + 10)
    if not partial:
        recipient = user_factory("WaitExpiryPrior")
        marker["route_resume_previous_driver_id"] = recipient["id"]
    oid = _seed(owner["id"], wait_until=utcnow() - timedelta(seconds=30), promo_discount_kop=5000, **marker)
    saved = _snapshot(oid)
    notices = []
    monkeypatch.setattr(worker, "push_notification", lambda *a, **kw: notices.append(kw["ref_id"]))
    assert oid not in _run(dry=True)
    assert oid not in _run()
    assert _snapshot(oid) == saved and oid not in notices


@pytest.mark.skipif(engine.dialect.name != "sqlite", reason="independent SQL between read and UPDATE")
@pytest.mark.parametrize("rival", ("marker", "renewed_wait", "accepted"))
def test_real_losing_expiry_cas_never_notifies_or_clears_promo(user_factory, monkeypatch, rival):
    owner, recipient = (user_factory(name) for name in ("WaitExpiryActor", "WaitExpiryOther"))
    oid = _seed(owner["id"], wait_until=utcnow() - timedelta(seconds=30), promo_discount_kop=5000)
    changed = {
        "marker": _marker(recipient["id"]),
        "renewed_wait": dict(wait_until=utcnow() + timedelta(minutes=5)),
        "accepted": dict(status=S.accepted, driver_id=recipient["id"]),
    }[rival]
    entered = []
    notices = []
    monkeypatch.setattr(worker, "push_notification", lambda *a, **kw: notices.append(kw["ref_id"]))

    def witness(conn, cursor, statement, params, context, many):
        if _is_expiry(statement) and not entered:
            entered.append(True)
            with engine.begin() as other:
                assert other.connection.driver_connection is not conn.connection.driver_connection
                assert other.execute(update(_O).where(_O.c.id == oid).values(**changed)).rowcount == 1

    event.listen(engine, "before_cursor_execute", witness)
    try:
        processed = _run()
    finally:
        event.remove(engine, "before_cursor_execute", witness)
    assert entered == [True]
    after = _snapshot(oid)
    assert oid not in processed and oid not in notices
    assert after.promo_discount_kop == 5000
    assert all(getattr(after, f) == v for f, v in changed.items())


@pytest.mark.skipif(engine.dialect.name != "sqlite", reason="independent SQL at actual promo write")
def test_late_promo_release_after_acknowledged_expiry_respects_new_marker(user_factory, monkeypatch):
    owner, recipient = (user_factory(name) for name in ("WaitPromoActor", "WaitPromoNew"))
    oid = _seed(owner["id"], wait_until=utcnow() - timedelta(seconds=30), promo_discount_kop=5000)
    entered = []
    monkeypatch.setattr(worker, "push_notification", lambda *a, **kw: None)

    def witness(conn, cursor, statement, params, context, many):
        if statement.startswith("UPDATE instantorder SET promo_discount_kop") and not entered:
            entered.append(True)
            with engine.begin() as other:
                assert other.connection.driver_connection is not conn.connection.driver_connection
                assert other.execute(update(_O).where(_O.c.id == oid).values(
                    route_resume_previous_driver_id=recipient["id"],
                )).rowcount == 1

    event.listen(engine, "before_cursor_execute", witness)
    try:
        assert oid in _run()  # expiry genuinely committed before this rival
    finally:
        event.remove(engine, "before_cursor_execute", witness)
    after = _snapshot(oid)
    assert entered == [True]
    assert after.wait_until is None and after.status is S.expired
    assert after.promo_discount_kop == 5000 and after.route_resume_previous_driver_id == recipient["id"]


def test_ordinary_due_wait_dry_run_then_expiry_and_existing_release(user_factory, monkeypatch):
    owner = user_factory("OrdinaryWaitExpiry")
    oid = _seed(owner["id"], wait_until=utcnow() - timedelta(seconds=30), promo_discount_kop=5000)
    saved = _snapshot(oid)
    notices = []
    monkeypatch.setattr(worker, "push_notification", lambda *a, **kw: notices.append(kw["ref_id"]))
    assert oid in _run(dry=True)
    assert _snapshot(oid) == saved and notices == []
    assert oid in _run()
    after = _snapshot(oid)
    assert after.status is S.expired and after.wait_until is None
    assert after.promo_discount_kop == 0 and notices == [oid]
    assert oid not in _run() and notices == [oid]


@pytest.mark.skipif(engine.dialect.name != "sqlite", reason="independent SQL before the post-close effect read")
def test_latest_marker_before_postclose_notice_defers_notice_and_promo(user_factory, monkeypatch):
    owner = user_factory("WaitNoticeOwner")
    oid = _seed(owner["id"], wait_until=utcnow() - timedelta(seconds=30), promo_discount_kop=5000)
    entered, notices = [], []
    monkeypatch.setattr(worker, "push_notification", lambda *a, **kw: notices.append(kw["ref_id"]))

    def witness(conn, cursor, statement, params, context, many):
        if (statement.splitlines()[0].strip() == "SELECT instantorder.id"
                and "instantorder.route_resume_id IS NULL" in statement and not entered):
            entered.append(True)
            with engine.begin() as other:
                assert other.connection.driver_connection is not conn.connection.driver_connection
                assert other.execute(update(_O).where(_O.c.id == oid).values(
                    route_resume_claim_id="synthetic new partial recovery",
                )).rowcount == 1

    event.listen(engine, "before_cursor_execute", witness)
    try:
        assert oid in _run()  # original expiry did commit, before the newer marker
    finally:
        event.remove(engine, "before_cursor_execute", witness)
    assert entered == [True] and notices == []
    assert _snapshot(oid).promo_discount_kop == 5000
