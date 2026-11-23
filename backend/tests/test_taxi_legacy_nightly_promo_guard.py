"""A real nightly closure may lose ownership of promo before its second transaction."""
from datetime import timedelta

import pytest
from sqlalchemy import event
from sqlmodel import Session

from app import cleanup, db as db_module, promo_ride, taxi_route_resume as resume
from app.models import InstantOrderStatus as S, PromoCode, PromoRedemption, User
from app.routers.instant import wait_for_driver
from test_taxi_offered_waypoint_preview import _mutate, _row, testdb  # noqa: F401


@pytest.fixture
def reserved(request, monkeypatch):
    db, owner, oid, driver, other, _, now = request.getfixturevalue("testdb")
    cut = now - timedelta(hours=7)
    _mutate(db, oid, status=S.scheduled, scheduled_at=cut, created_at=cut, driver_id=None,
            current_offer_driver_id=None, offer_expires_at=None)
    monkeypatch.setattr(cleanup, "engine", db)
    monkeypatch.setattr(db_module, "engine", db)
    monkeypatch.setattr(cleanup.settings, "taxi_stale_hours", 6)
    monkeypatch.setattr(cleanup, "_notify_closed", lambda *a: None)
    with Session(db) as s:
        campaign = PromoCode(code="SYNNIGHT", title="SYNTHETIC", kind=promo_ride.KIND, perk_value=100)
        s.add(campaign)
        s.flush()
        red = PromoRedemption(promo_id=campaign.id, user_id=owner, discount_kop=10_000,
                              used_order_id=oid, used_at=cut)
        s.add(red)
        s.flush()
        rid = red.id
        s.commit()
    return db, owner, oid, driver, other, now, cut, rid


def _check_redemption(db, rid):
    with Session(db) as s:
        red = s.get(PromoRedemption, rid)
        return red.used_order_id, red.used_at


@pytest.mark.parametrize("race", ("wait_after_commit", "wait_at_promo", "marker_at_promo", "owner_at_promo"))
def test_later_ordinary_wait_or_other_terminal_identity_cannot_lose_promo(reserved, monkeypatch, race):
    db, owner, oid, _, other, now, cut, rid = reserved
    orig_commit, tx, rival_writes, promo_rowcounts = db.dialect.do_commit, [], [], []

    def rival(physical):
        # Sanity: night closure is acknowledged and still the only row at
        # this boundary. Do not simulate a /wait by mutating its columns.
        assert _row(db, oid).status is S.expired
        rival_writes.append(True)
        if race.startswith("wait_"):
            with Session(db) as other_session:
                assert other_session.connection().connection.driver_connection is not physical
                response = wait_for_driver(oid, other_session.get(User, owner), other_session)
                assert response["ok"] and response["wait_until"] > now
        else:
            changes = {resume._SEAL: b"x"*32} if race == "marker_at_promo" else {"passenger_id": other}
            with db.begin() as other_conn:
                assert other_conn.connection.driver_connection is not physical
                other_conn.execute(resume._ORDER.update().where(resume._COL.id == oid).values(**changes))

    def committed(conn):
        orig_commit(conn)
        raw = getattr(conn, "driver_connection", conn)
        if race == "wait_after_commit" and tx and raw is tx[0] and not rival_writes:
            rival(raw)

    def before_sql(conn, cursor, sql, parameters, context, many):
        raw = conn.connection.driver_connection
        if sql.startswith("UPDATE instantorder SET status = 'expired'"):
            tx.append(raw)
        if ("at_promo" in race and sql.startswith("UPDATE instantorder SET promo_discount_kop")
                and not rival_writes):
            rival(raw)

    def after_sql(conn, cursor, sql, parameters, context, many):
        if sql.startswith("UPDATE instantorder SET promo_discount_kop"):
            promo_rowcounts.append(cursor.rowcount)

    event.listen(db, "before_cursor_execute", before_sql)
    event.listen(db, "after_cursor_execute", after_sql)
    try:
        monkeypatch.setattr(db.dialect, "do_commit", committed)
        assert cleanup.close_stale_orders(now) == 1
    finally:
        event.remove(db, "before_cursor_execute", before_sql)
        event.remove(db, "after_cursor_execute", after_sql)
    final = _row(db, oid)
    assert rival_writes == [True] and final.promo_discount_kop == 10_000
    assert promo_rowcounts == ([] if race == "wait_after_commit" else [0])
    assert _check_redemption(db, rid) == (oid, cut)
    if race.startswith("wait_"):
        assert final.wait_until > now
    elif race == "marker_at_promo":
        assert final.route_resume_agreement_seal == b"x"*32
    else:
        assert final.passenger_id == other


@pytest.mark.parametrize("assigned", (False, True))
def test_unchanged_nightly_closure_releases_promo_but_keeps_old_wait_semantics(reserved, assigned):
    db, _, oid, driver, _, now, _, rid = reserved
    already_waiting = now + timedelta(minutes=3) if assigned else None
    if assigned:
        _mutate(db, oid, status=S.arriving, driver_id=driver, wait_until=already_waiting)
    assert cleanup.close_stale_orders(now) == 1
    final = _row(db, oid)
    assert final.status is (S.cancelled if assigned else S.expired)
    assert final.promo_discount_kop == 0 and final.wait_until == already_waiting
    assert _check_redemption(db, rid) == (None, None)
