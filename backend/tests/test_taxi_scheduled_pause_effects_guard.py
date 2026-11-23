"""Actual scheduled terminal commit then competing rows, private file SQLite."""
import pytest
from sqlalchemy import event, update
from sqlmodel import Session

from app import instant_service as isv, promo_ride, taxi_route_resume as resume
from app.models import InstantOrder, InstantOrderStatus as S, PromoCode, PromoRedemption
import test_taxi_scheduled_marker_guard as fixture_data
from test_taxi_offered_waypoint_preview import _row, testdb  # noqa: F401

scheduled = fixture_data.scheduled


@pytest.fixture
def reserved(request):
    source = request.getfixturevalue("scheduled")
    db, owner, oid, _, _, _, now = source
    with Session(db) as s:
        campaign = PromoCode(code="SYNTHSCHED", title="SYNTHETIC", kind=promo_ride.KIND, perk_value=100)
        s.add(campaign)
        s.flush()
        red = PromoRedemption(promo_id=campaign.id, user_id=owner, discount_kop=10_000,
                              used_order_id=oid, used_at=now)
        s.add(red)
        s.flush()
        rid = red.id
        s.commit()
    return source, rid


def _redemption(db, rid):
    with Session(db) as s:
        red = s.get(PromoRedemption, rid)
        return red.used_order_id, red.used_at


@pytest.mark.parametrize("race", ("marker_after_close", "superseded_after_close", "marker_at_promo",
                                  "marker_after_promo", "superseded_before_pause_notice",
                                  "redemption_rebound_at_promo"))
def test_scheduled_pause_postcommit_effects_require_same_exact_terminal_event(reserved, monkeypatch, race):
    source, rid = reserved
    db, owner, oid, _, _, _, now = source
    replacement = None
    if race == "redemption_rebound_at_promo":
        with Session(db) as session:
            row = InstantOrder(passenger_id=owner, status=S.created)
            session.add(row)
            session.flush()
            replacement = row.id
            session.commit()
    effects = fixture_data._studio(source, monkeypatch, paused=True)
    writers, matches, promo_counts = [], [], []
    original_commit = db.dialect.do_commit
    original_notify = isv._notify_cancel
    changes = ({resume._SEAL: b"s"*32} if race.startswith("marker")
               else {"cancel_reason": "synthetic terminal replacement"})

    def rival(raw):
        before = _row(db, oid)
        assert before.status is S.cancelled and before.cancel_reason == "passenger_suspended"
        assert before.promo_discount_kop == (0 if race in ("marker_after_promo", "superseded_before_pause_notice") else 10_000)
        with db.begin() as independent:
            assert independent.connection.driver_connection is not raw
            if race == "redemption_rebound_at_promo":
                result = independent.execute(update(PromoRedemption).where(
                    PromoRedemption.id == rid, PromoRedemption.used_order_id == oid,
                ).values(used_order_id=replacement))
            else:
                result = independent.execute(resume._ORDER.update().where(
                    resume._COL.id == oid, resume._COL.status == S.cancelled,
                ).values(**changes))
            assert result.rowcount == 1
        matches.append(_row(db, oid))

    def after_commit(conn):
        original_commit(conn)
        raw = getattr(conn, "driver_connection", conn)
        if not matches:
            if race in ("marker_after_close", "superseded_after_close") and ("close", raw) in writers:
                rival(raw)
            if race == "marker_after_promo" and ("promo", raw) in writers:
                rival(raw)

    def before_sql(conn, cursor, sql, parameters, context, many):
        raw = conn.connection.driver_connection
        if sql.startswith("UPDATE instantorder SET") and "passenger_suspended" in parameters:
            # Identify the actual terminal SET, not a comparison in a later predicate.
            if "cancel_reason=" in sql.split(" WHERE")[0]:
                writers.append(("close", raw))
        if sql.startswith("UPDATE instantorder SET promo_discount_kop"):
            writers.append(("promo", raw))
            if race in ("marker_at_promo", "redemption_rebound_at_promo") and not matches:
                rival(raw)

    def after_sql(conn, cursor, sql, parameters, context, many):
        if sql.startswith("UPDATE instantorder SET promo_discount_kop"):
            promo_counts.append(cursor.rowcount)

    def notify(session, order, actor):
        original_notify(session, order, actor)
        if race == "superseded_before_pause_notice":
            rival(session.connection().connection.driver_connection)

    event.listen(db, "before_cursor_execute", before_sql)
    event.listen(db, "after_cursor_execute", after_sql)
    try:
        monkeypatch.setattr(db.dialect, "do_commit", after_commit)
        monkeypatch.setattr(isv, "_notify_cancel", notify)
        assert fixture_data._run(source) is S.cancelled
    finally:
        event.remove(db, "before_cursor_execute", before_sql)
        event.remove(db, "after_cursor_execute", after_sql)

    assert len(matches) == 1 and _row(db, oid) == matches[0]
    if race in ("marker_after_promo", "superseded_before_pause_notice"):
        assert _redemption(db, rid) == (None, None) and promo_counts == [1]
    elif race == "redemption_rebound_at_promo":
        assert _redemption(db, rid) == (replacement, now) and promo_counts == [1]
    else:
        assert _redemption(db, rid) == (oid, now)
        assert promo_counts == ([0] if race == "marker_at_promo" else [])
    assert effects == (["standing", "cleanup", "cancel-notice"]
                       if race == "superseded_before_pause_notice" else ["standing"])


def test_unsuperseded_scheduled_pause_still_returns_reservation_and_normal_notices(reserved, monkeypatch):
    source, rid = reserved
    db, _, oid, *_ = source
    effects = fixture_data._studio(source, monkeypatch, paused=True)
    assert fixture_data._run(source) is S.cancelled
    row = _row(db, oid)
    assert row.cancel_reason == "passenger_suspended" and row.cancel_fee_kop == row.promo_discount_kop == 0
    assert _redemption(db, rid) == (None, None)
    assert effects == ["standing", "cleanup", "cancel-notice", "pause-notice"]
