"""Actual private SQLite closure and promotion ownership; no SMS/push network."""
from datetime import timedelta

import pytest
from sqlalchemy import event
from sqlmodel import Session

from app import db as db_module, promo_ride, taxi_route_resume as resume, taxi_worker as worker
from app.models import InstantOrderStatus as S, PromoCode, PromoRedemption
from app.timeutil import utcnow
from test_taxi_offered_waypoint_preview import _mutate, _row, testdb  # noqa: F401


@pytest.fixture
def stuck(request, monkeypatch):
    db, owner, oid, driver, other, _, at = request.getfixturevalue("testdb")
    # All effects and the dedicated promo session stay within this test DB.
    monkeypatch.setattr(db_module, "engine", db)
    monkeypatch.setattr(worker.settings, "order_stuck_hours", 6)
    early = at - timedelta(hours=7)
    _mutate(db, oid, status=S.searching, created_at=early, searching_at=early, retry_count=2,
            offered_at=None, current_offer_driver_id=None, offer_expires_at=None)
    with Session(db) as s:
        campaign = PromoCode(code="SYNTHETIC", title="SYNTHETIC", kind=promo_ride.KIND, perk_value=100)
        s.add(campaign)
        s.flush()
        red = PromoRedemption(promo_id=campaign.id, user_id=owner, discount_kop=10_000,
                              used_order_id=oid, used_at=early)
        s.add(red)
        s.flush()
        redemption = red.id
        s.commit()
    effects = []
    monkeypatch.setattr(worker.isv, "_notify_order_shares",
                        lambda s, order, status: effects.append(("shares", order.id, status)))
    monkeypatch.setattr(worker, "push_notification",
                        lambda s, user, *a, **kw: effects.append(("push", user, kw["ref_id"])))
    return db, owner, oid, driver, other, early, redemption, effects


def _run(db, *, dry=False):
    with Session(db) as session:
        return worker.close_stuck_orders(session, dry_run=dry)


def _redemption(db, rid):
    with Session(db) as s:
        red = s.get(PromoRedemption, rid)
        return red.used_order_id, red.used_at


@pytest.mark.parametrize("marker", (*resume._MARKERS, "all"))
def test_marked_order_excluded_from_dry_run_and_real_close(stuck, marker):
    db, _, oid, driver, _, early, red, effects = stuck
    values = dict(route_resume_id="a"*32, route_resume_claim_id="", route_resume_previous_driver_id=driver,
                  route_resume_retired_at=early, route_resume_ready_at=early, route_resume_deadline_at=early,
                  route_resume_agreement_seal=b"x"*32)
    _mutate(db, oid, **(values if marker == "all" else {marker: values[marker]}))
    before = _row(db, oid)
    assert _run(db, dry=True) == [] and _run(db) == []
    assert _row(db, oid) == before
    assert effects == [] and _redemption(db, red) == (oid, early)


@pytest.mark.parametrize("race", ("seal", "status", "driver", "owner", "same_phase_search", "active_clock", "wait"))
def test_rival_writes_before_real_closure_dml_and_worker_has_no_effects(stuck, race):
    db, _, oid, driver, other, early, red, effects = stuck
    # Active-clock case uses a real assigned phase, still stale at selection.
    if race == "active_clock":
        _mutate(db, oid, status=S.onboard, driver_id=driver, onboard_at=early)
    updates = {
        "seal": {resume._SEAL: b"x"*32},
        "status": {"status": S.scheduled, "scheduled_at": utcnow() + timedelta(hours=1)},
        "driver": {"driver_id": driver},
        "owner": {"passenger_id": other},
        "same_phase_search": {"searching_at": early + timedelta(minutes=1), "search_round": 2},
        "active_clock": {"onboard_at": utcnow()},
        "wait": {"wait_until": utcnow() + timedelta(minutes=5)},
    }[race]
    rival_state, counts = [], []

    def close_sql(sql, params):
        return sql.startswith("UPDATE instantorder SET") and "stuck_timeout" in params

    def before_sql(conn, cursor, sql, params, context, many):
        if close_sql(sql, params) and not rival_state:
            with db.begin() as rival:
                assert rival.connection.driver_connection is not conn.connection.driver_connection
                rival.execute(resume._ORDER.update().where(resume._COL.id == oid).values(**updates))
            rival_state.append(_row(db, oid))

    def after_sql(conn, cursor, sql, params, context, many):
        if close_sql(sql, params):
            counts.append(cursor.rowcount)

    event.listen(db, "before_cursor_execute", before_sql)
    event.listen(db, "after_cursor_execute", after_sql)
    try:
        closed = _run(db)
    finally:
        event.remove(db, "before_cursor_execute", before_sql)
        event.remove(db, "after_cursor_execute", after_sql)
    assert counts == [0] and closed == []
    assert len(rival_state) == 1 and _row(db, oid) == rival_state[0]
    assert effects == [] and _redemption(db, red) == (oid, early)


@pytest.mark.parametrize("when", ("after_close_commit", "at_promo_write"))
def test_terminal_rival_never_loses_promo_to_prior_closed_id(stuck, monkeypatch, when):
    db, owner, oid, _, _, early, red, effects = stuck
    orig_commit = db.dialect.do_commit
    conn_id, rivals = [], []

    def after_commit(conn):
        orig_commit(conn)
        if when == "after_close_commit" and conn_id and getattr(conn, "driver_connection", conn) is conn_id[0] and not rivals:
            assert _row(db, oid).status is S.expired
            rivals.append(True)
            _mutate(db, oid, **{resume._SEAL: b"x"*32})

    def before_sql(conn, cursor, sql, params, context, many):
        if sql.startswith("UPDATE instantorder SET") and "stuck_timeout" in params and not conn_id:
            conn_id.append(conn.connection.driver_connection)
        if when == "at_promo_write" and sql.startswith("UPDATE instantorder SET promo_discount_kop") and not rivals:
            rivals.append(True)
            with db.begin() as rival:
                assert rival.connection.driver_connection is not conn.connection.driver_connection
                rival.execute(resume._ORDER.update().where(resume._COL.id == oid).values(**{resume._SEAL: b"x"*32}))

    event.listen(db, "before_cursor_execute", before_sql)
    try:
        monkeypatch.setattr(db.dialect, "do_commit", after_commit)
        assert _run(db) == [oid]
    finally:
        event.remove(db, "before_cursor_execute", before_sql)
    assert rivals == [True] and _row(db, oid).promo_discount_kop == 10_000
    assert _redemption(db, red) == (oid, early)
    assert effects == ([] if when == "after_close_commit" else [("shares", oid, "cancelled"), ("push", owner, oid)])


@pytest.mark.parametrize("assigned", (False, True))
def test_old_ordinary_closure_same_status_fields_contacts_and_promo(stuck, assigned):
    db, owner, oid, driver, _, early, red, effects = stuck
    if assigned:
        _mutate(db, oid, status=S.onboard, driver_id=driver, onboard_at=early)
    before = _row(db, oid)
    assert _run(db, dry=True) == [oid] and _row(db, oid) == before
    assert _run(db) == [oid] and _run(db) == []
    after = _row(db, oid)
    expected = S.cancelled if assigned else S.expired
    assert dict(after._mapping) == dict(before._mapping) | {
        "status": expected, "cancel_by": "system", "cancel_reason": "stuck_timeout",
        "cancelled_at": after.cancelled_at, "promo_discount_kop": 0,
    }
    assert _redemption(db, red) == (None, None)
    assert effects[0] == ("shares", oid, "cancelled")
    assert set(effects[1:]) == {("push", user, oid) for user in ({owner, driver} if assigned else {owner})}
