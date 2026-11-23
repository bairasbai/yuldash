"""True private SQLite schedule activation witnesses; no providers or delivery."""
from datetime import timedelta

import pytest
from sqlalchemy import event
from sqlmodel import Session

from app import instant_service as isv, safety_logic, services, taxi_route_resume as resume
from app.models import InstantOrder, InstantOrderStatus as S
from test_taxi_offered_waypoint_preview import _mutate, _row, testdb  # noqa: F401


@pytest.fixture
def scheduled(request):
    db, owner, oid, driver, other, tariff, now = request.getfixturevalue("testdb")
    _mutate(db, oid, status=S.scheduled, scheduled_at=now - timedelta(minutes=1),
            searching_at=None, search_round=0, offered_at=None,
            current_offer_driver_id=None, offer_expires_at=None)
    return db, owner, oid, driver, other, tariff, now


def _run(data):
    db, _, oid, *_ = data
    with Session(db) as session:
        return isv.activate_scheduled(session, session.get(InstantOrder, oid)).status


def _studio(data, monkeypatch, *, paused=False, guarantee=0):
    _, _, oid, _, _, tariff, _ = data
    seen = []

    def note(name, value=None):
        def effect(*a, **kw):
            seen.append(name)
            return value
        return effect

    monkeypatch.setattr(safety_logic, "account_paused", note("standing", paused))
    monkeypatch.setattr(isv.settings, "promo_ride_max_price_share", 0.3)
    monkeypatch.setattr(isv.settings, "scheduled_price_guarantee_percent", guarantee)
    monkeypatch.setattr(isv, "_redis", note("matcher"))
    monkeypatch.setattr(isv, "_cleanup_tried", note("cleanup"))
    monkeypatch.setattr(isv, "push_bilingual", note("no-driver-notice"))
    monkeypatch.setattr(isv, "_notify_cancel", note("cancel-notice"))
    monkeypatch.setattr(services, "push_notification", note("pause-notice"))
    monkeypatch.setattr(isv, "estimate", note("price", {
        "price": 220, "ride_price": 170, "base_price": 150,
        "pickup_fee": 28, "pickup_km": 3.4, "pickup_pending": False,
        "options_fee": 15, "weather_fee": 7, "weather_kind": "snow",
        "distance_km": 6.2, "eta_min": 9, "tariff_id": tariff, "surge_k": 1.0, "pricing_k": 1.25,
    }))
    return seen


@pytest.mark.parametrize("field", resume._MARKERS)
def test_existing_mark_prevents_price_and_suspension_policy(scheduled, monkeypatch, field):
    db, _, oid, driver, *_, now = scheduled
    values = dict(route_resume_id="a"*32, route_resume_previous_driver_id=driver,
                  route_resume_retired_at=now, route_resume_ready_at=now, route_resume_deadline_at=now,
                  route_resume_claim_id="", route_resume_agreement_seal=b"r"*32)
    _mutate(db, oid, **{field: values[field]})
    before = _row(db, oid)
    for paused in (False, True):
        effects = _studio(scheduled, monkeypatch, paused=paused)
        assert _run(scheduled) is S.scheduled
        assert effects == [] and _row(db, oid) == before


@pytest.mark.parametrize("rival", ("marker", "owner", "schedule", "route", "fare", "phase", "suspension"))
def test_independent_sqlite_rival_at_real_scheduled_update(scheduled, monkeypatch, rival):
    db, _, oid, _, other, _, now = scheduled
    effects = _studio(scheduled, monkeypatch, paused=rival == "suspension")
    changes = {
        "marker": {resume._SEAL: b"r"*32},
        "owner": {"passenger_id": other},
        "schedule": {"scheduled_at": now + timedelta(hours=2)},
        "route": {"to_text": "SYNTHETIC DIFFERENT ROUTE"},
        "fare": {"ride_price": 210, "promo_discount_kop": 9000},
        "phase": {"status": S.cancelled},
        "suspension": {resume._SEAL: b"r"*32},
    }[rival]
    hit, counts = [], []

    def target(sql, params):
        return sql.startswith("UPDATE instantorder SET") and (
            "passenger_suspended" in params if rival == "suspension" else "price_estimate=" in sql.split(" WHERE")[0]
        )

    def before_sql(conn, cursor, sql, params, context, executemany):
        if target(sql, params) and not hit:
            with db.begin() as independent:
                assert independent.connection.driver_connection is not conn.connection.driver_connection
                independent.execute(resume._ORDER.update().where(resume._COL.id == oid).values(**changes))
            hit.append(_row(db, oid))

    def after_sql(conn, cursor, sql, params, context, executemany):
        if target(sql, params):
            counts.append(cursor.rowcount)

    event.listen(db, "before_cursor_execute", before_sql)
    event.listen(db, "after_cursor_execute", after_sql)
    try:
        _run(scheduled)
    finally:
        event.remove(db, "before_cursor_execute", before_sql)
        event.remove(db, "after_cursor_execute", after_sql)
    assert len(hit) == 1 and counts == [0] and _row(db, oid) == hit[0]
    assert effects == (["standing"] if rival == "suspension" else ["standing", "price"])


@pytest.mark.parametrize("after", ("fare_commit", "during_exact_start"))
def test_late_rival_gets_neither_id_only_reclamp_nor_a_stale_start(scheduled, monkeypatch, after):
    db, _, oid, *_ = scheduled
    effects = _studio(scheduled, monkeypatch)
    raw_writer, expected = [], []
    original = db.dialect.do_commit

    def rival(raw):
        with db.begin() as other:
            assert other.connection.driver_connection is not raw
            # Preserve phase/identity but update a compensation field and the
            # associated terms after the old price boundary.
            values = ({"promo_discount_kop": 15_000, resume._SEAL: b"r"*32}
                      if after == "fare_commit" else {"pickup_fee_kop": 5200})
            other.execute(resume._ORDER.update().where(resume._COL.id == oid).values(**values))
        expected.append(_row(db, oid))

    def before_sql(conn, cursor, sql, params, context, executemany):
        if sql.startswith("UPDATE instantorder SET price_estimate"):
            raw_writer.append(conn.connection.driver_connection)
        if (after == "during_exact_start" and sql.startswith("UPDATE instantorder SET status")
                and S.searching.value in params and not expected):
            rival(conn.connection.driver_connection)

    def after_commit(conn):
        original(conn)
        raw = getattr(conn, "driver_connection", conn)
        if after == "fare_commit" and raw_writer and raw is raw_writer[0] and not expected:
            assert _row(db, oid).price_estimate == 220
            rival(raw)

    event.listen(db, "before_cursor_execute", before_sql)
    try:
        monkeypatch.setattr(db.dialect, "do_commit", after_commit)
        assert _run(scheduled) is S.scheduled
    finally:
        event.remove(db, "before_cursor_execute", before_sql)
    assert len(expected) == 1 and _row(db, oid) == expected[0]
    assert effects == ["standing", "price"]


def test_unmarked_price_discount_and_search_keep_the_old_amounts(scheduled, monkeypatch):
    db, _, oid, _, _, tariff, _ = scheduled
    effects = _studio(scheduled, monkeypatch)
    assert _run(scheduled) is S.expired
    row = _row(db, oid)
    assert (row.price_estimate, row.ride_price, row.ride_base_price, row.promo_discount_kop) == (220, 170, 150, 5100)
    assert (row.pickup_fee_kop, row.options_fee_kop, row.weather_fee_kop) == (2800, 1500, 700)
    assert (row.tariff_id, row.distance_km, row.eta_min, row.pricing_k) == (tariff, 6.2, 9, 1.25)
    assert effects == ["standing", "price", "standing", "matcher", "cleanup", "no-driver-notice"]


def test_current_unmarked_paused_schedule_still_cancels_without_price_or_fee(scheduled, monkeypatch):
    db, _, oid, *_ = scheduled
    effects = _studio(scheduled, monkeypatch, paused=True)
    assert _run(scheduled) is S.cancelled
    row = _row(db, oid)
    assert row.cancel_reason == "passenger_suspended" and row.cancel_fee_kop == 0
    assert effects == ["standing", "cleanup", "cancel-notice", "pause-notice"]
