"""Nightly taxi closure derives side effects from actual committed SQL rows."""
from datetime import timedelta

import pytest
from sqlalchemy import event

from app import cleanup, promo_ride, taxi_route_resume as resume
from app.models import InstantOrderStatus as S
from test_taxi_offered_waypoint_preview import _mutate, _row, testdb  # noqa: F401


@pytest.fixture
def nightly(request, monkeypatch):
    db, owner, oid, driver, other, _, now = request.getfixturevalue("testdb")
    now = now.replace(microsecond=123456)
    cut = now - timedelta(hours=6)
    monkeypatch.setattr(cleanup, "engine", db)
    monkeypatch.setattr(cleanup.settings, "taxi_stale_hours", 6)
    effects = []

    def receipt(kind, payload):
        if payload:
            # The real DB state is visible to an independent observer
            # when any downstream financial/notification function begins.
            assert _row(db, oid).status in (S.cancelled, S.expired, S.done)
            effects.append((kind, list(payload)))

    monkeypatch.setattr(cleanup, "_accrue_auto_done", lambda ids: receipt("fee", ids))
    monkeypatch.setattr(cleanup, "_notify_closed", receipt)
    def promo_effect(ids, *, _closed_nightlies):
        assert set(ids) == _closed_nightlies.keys()
        assert all(oid == row[0] for oid, row in _closed_nightlies.items())
        receipt("promo", ids)

    monkeypatch.setattr(promo_ride, "release_ids", promo_effect)
    return db, owner, oid, driver, other, now, cut, effects


def _seed(data, phase, *, edge=False):
    db, _, oid, driver, _, _, cut, _ = data
    at = cut if edge else cut - timedelta(seconds=1)
    _mutate(db, oid, status=phase, driver_id=None if phase is S.scheduled else driver,
            created_at=cut + timedelta(seconds=1) if phase is S.scheduled else at,
            scheduled_at=at if phase is S.scheduled else None)


@pytest.mark.parametrize("phase", (S.accepted, S.arriving, S.onboard, S.scheduled))
def test_normal_paths_preserve_threshold_and_effect_class(nightly, phase):
    db, owner, oid, driver, _, now, cut, effects = nightly
    _seed(nightly, phase, edge=True)
    before = _row(db, oid)
    assert cleanup.close_stale_orders(now) == 0 and _row(db, oid) == before and effects == []

    _seed(nightly, phase)
    # Existing done clock must be preserved (none now is set on real UPDATE).
    saved_done = cut - timedelta(minutes=1) if phase is S.onboard else None
    _mutate(db, oid, done_at=saved_done)
    before = _row(db, oid)
    assert cleanup.close_stale_orders(now) == 1 and cleanup.close_stale_orders(now) == 0
    target = S.done if phase is S.onboard else S.expired if phase is S.scheduled else S.cancelled
    assert dict(_row(db, oid)._mapping) == dict(before._mapping) | {"status": target}
    if phase is S.onboard:
        assert effects == [("fee", [oid]), ("order_done", [(oid, driver)]), ("order_done_pax", [(oid, owner)])]
    elif phase is S.scheduled:
        assert effects == [("promo", [oid])]
    else:
        assert effects == [("promo", [oid]), ("order", [(oid, driver)]), ("order_pax", [(oid, owner)])]


@pytest.mark.parametrize("marker", (*resume._MARKERS, "all"))
def test_any_marker_blocks_eligible_nightly_close(nightly, marker):
    db, _, oid, driver, _, now, cut, effects = nightly
    _seed(nightly, S.onboard)
    values = dict(route_resume_id="a"*32, route_resume_claim_id="", route_resume_previous_driver_id=driver,
                  route_resume_retired_at=cut, route_resume_ready_at=cut, route_resume_deadline_at=cut,
                  route_resume_agreement_seal=b"x"*32)
    _mutate(db, oid, **(values if marker == "all" else {marker: values[marker]}))
    before = _row(db, oid)
    assert cleanup.close_stale_orders(now) == 0
    assert effects == [] and _row(db, oid) == before


@pytest.mark.parametrize("phase,rival", (
    (S.arriving, "marker"), (S.onboard, "marker"), (S.scheduled, "marker"),
    (S.accepted, "active"), (S.onboard, "active"), (S.scheduled, "deferred"),
    (S.arriving, "new_owner"), (S.onboard, "new_owner"),
))
def test_real_rival_commits_before_first_batch_dml(nightly, phase, rival):
    db, owner, oid, driver, other, now, cut, effects = nightly
    _seed(nightly, phase)
    hit = []
    updates = {
        "marker": {resume._SEAL: b"x"*32},
        "active": {"passenger_id": other, "driver_id": owner, "status": S.searching, "searching_at": now},
        "deferred": {"scheduled_at": now + timedelta(hours=1)},
        "new_owner": {"passenger_id": other, "driver_id": owner},
    }[rival]

    def witness(conn, cursor, sql, parameters, context, many):
        # All three writes share the same SQLite writer transaction;
        # inject before its first physical write, never through its lock.
        if sql.startswith("UPDATE instantorder SET status") and not hit:
            with db.begin() as independent:
                assert independent.connection.driver_connection is not conn.connection.driver_connection
                independent.execute(resume._ORDER.update().where(resume._COL.id == oid).values(**updates))
            hit.append(_row(db, oid))

    event.listen(db, "before_cursor_execute", witness)
    try:
        count = cleanup.close_stale_orders(now)
    finally:
        event.remove(db, "before_cursor_execute", witness)
    assert len(hit) == 1
    final = _row(db, oid)
    if rival == "new_owner":
        assert count == 1 and (final.passenger_id, final.driver_id) == (other, owner)
        if phase is S.onboard:
            assert final.status is S.done and final.done_at == now
            assert effects == [("fee", [oid]), ("order_done", [(oid, owner)]), ("order_done_pax", [(oid, other)])]
        else:
            assert final.status is S.cancelled
            assert effects == [("promo", [oid]), ("order", [(oid, owner)]), ("order_pax", [(oid, other)])]
    else:
        assert count == 0 and final == hit[0] and effects == []


def test_entire_cleanup_batch_rolls_back_on_last_update_failure(nightly):
    db, _, oid, _, _, now, _, effects = nightly
    _seed(nightly, S.accepted)
    before = _row(db, oid)

    def fail_last(conn, cursor, sql, parameters, context, many):
        if sql.startswith("UPDATE instantorder SET status = 'expired'"):
            raise RuntimeError("synthetic SQL boundary interruption")

    event.listen(db, "before_cursor_execute", fail_last)
    try:
        with pytest.raises(RuntimeError, match="synthetic SQL boundary"):
            cleanup.close_stale_orders(now)
    finally:
        event.remove(db, "before_cursor_execute", fail_last)
    assert _row(db, oid) == before and effects == []
