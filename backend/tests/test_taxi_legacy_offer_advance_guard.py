"""Real legacy decline/reconcile/worker cannot take ownership of capped offers."""
from contextlib import contextmanager
from datetime import timedelta

import fakeredis
import pytest
from sqlalchemy import delete, event, select, update
from sqlalchemy.exc import OperationalError
from sqlmodel import Session

from app import instant_service as isv, taxi_route_resume as resume, taxi_worker
from app.db import engine
from app.models import InstantOrderStatus as S, OfferDecline, TaxiApplication, User, UserRole
from app.timeutil import utcnow
from test_taxi_wait_route_resume_guard import _O, _marker, _seed, _snapshot


@pytest.fixture
def order(user_factory):
    owner = user_factory("LegacyOfferOwner")
    driver = user_factory("LegacyOfferDriver", role=UserRole.driver)
    other = user_factory("LegacyOfferOther", role=UserRole.driver)
    now = utcnow()
    oid = _seed(owner["id"], status=S.offered, current_offer_driver_id=driver["id"],
                offered_at=now-timedelta(minutes=1), offer_expires_at=now-timedelta(seconds=2), search_round=1)
    ids = (owner["id"], driver["id"], other["id"])
    try:
        yield oid, owner, driver, other
    finally:
        with engine.begin() as conn:
            conn.execute(delete(OfferDecline).where(OfferDecline.order_id == oid))
            conn.execute(delete(_O).where(_O.c.id == oid))
            conn.execute(delete(TaxiApplication).where(TaxiApplication.user_id.in_(ids)))
            conn.execute(delete(User).where(User.id.in_(ids)))


@pytest.fixture
def effects(monkeypatch):
    redis = fakeredis.FakeStrictRedis(decode_responses=True)
    out = []
    monkeypatch.setattr(taxi_worker.settings, "order_offer_stale_min", 0)
    monkeypatch.setattr(isv, "_redis", lambda: redis)
    monkeypatch.setattr(isv, "_remember_offered_driver", lambda r, oid, did: out.append(("tried", oid, did)))
    monkeypatch.setattr(isv, "_push_offer", lambda s, o, did: out.append(("send", o.id, did)))
    monkeypatch.setattr(isv, "_cleanup_tried", lambda oid: out.append(("cleanup", oid)))
    monkeypatch.setattr(isv, "push_bilingual", lambda *a, **kw: out.append(("expiry", int(kw["data"]["order_id"]))))
    return out


@contextmanager
def _ordinary_stale_offer(owner, driver):
    now = utcnow()
    oid = _seed(owner["id"], status=S.offered, current_offer_driver_id=driver["id"],
                offered_at=now-timedelta(minutes=1), offer_expires_at=now-timedelta(seconds=2), search_round=1)
    try:
        yield oid
    finally:
        with engine.begin() as conn:
            conn.execute(delete(_O).where(_O.c.id == oid))


def _effects_for(effects, order_id):
    return [entry for entry in effects if entry[1] == order_id]


def _decline(client, oid, driver):
    return client.post(f"/instant/orders/{oid}/decline", headers=driver["auth"], json={"reason": "busy"})


def _audit(oid):
    with engine.connect() as conn:
        return conn.execute(select(OfferDecline.reason).where(OfferDecline.order_id == oid)).scalars().all()


@pytest.mark.parametrize("method", ("decline", "reconcile", "worker"))
@pytest.mark.parametrize("partial", (False, True))
def test_existing_marker_no_movement_effects_or_decline_log(client, order, effects, monkeypatch, method, partial):  # noqa: F811
    oid, owner, driver, other = order
    marker = {"route_resume_claim_id": ""} if partial else _marker(other["id"])
    if not partial:
        # The nominal capped lease fixture is already over only from
        # the legacy offer's perspective. Marker presence is authoritative.
        marker.pop("offered_at")
    with engine.begin() as conn:
        conn.execute(update(_O).where(_O.c.id == oid).values(**marker))
    before = _snapshot(oid)
    geo = []
    monkeypatch.setattr(isv, "candidates", lambda r, s, candidate, **kw: geo.append(candidate.id) or [])
    if method == "decline":
        result = _decline(client, oid, driver)
        assert result.status_code == 200
        assert set(resume._MARKERS).isdisjoint(result.json())
    elif method == "reconcile":
        result = client.get(f"/instant/orders/{oid}", headers=owner["auth"])
        assert result.status_code == 200
    else:
        # The worker scans the shared test database, not just this fixture.
        # Prove it really processes an unrelated eligible order; do not count
        # that order's side effects against the marked order.
        with _ordinary_stale_offer(owner, driver) as ordinary, Session(engine) as session:
            dry_run = taxi_worker.advance_stale_offers(session, dry_run=True)
            assert ordinary in dry_run and oid not in dry_run
            assert geo == effects == []
            moved = taxi_worker.advance_stale_offers(session)
            assert ordinary in moved and oid not in moved
            assert _snapshot(ordinary).status is S.expired
            assert geo.count(ordinary) == 1
            assert _effects_for(effects, ordinary) == [("cleanup", ordinary)]
        assert oid not in geo and _effects_for(effects, oid) == []
    assert _snapshot(oid) == before and _audit(oid) == []
    if method != "worker":
        assert geo == effects == []


@pytest.mark.skipif(engine.dialect.name != "sqlite", reason="actual second SQLite writer at final advance")
@pytest.mark.parametrize("method", ("decline", "worker"))
def test_stale_read_then_mark_at_real_advance_sql_leaves_no_side_effect(client, order, effects, monkeypatch, method):  # noqa: F811
    oid, _, driver, other = order
    hit, expected, geo = [], [], []
    def candidates(redis, session, candidate, **kwargs):
        geo.append(candidate.id)
        return [other["id"]] if candidate.id == oid else []

    monkeypatch.setattr(isv, "candidates", candidates)

    def witness(conn, cursor, statement, parameters, context, many):
        # Bind to this exact advance, not an earlier eligible row from a
        # different test or this worker's subsequent outcome UPDATE.
        if (statement.upper().startswith("UPDATE INSTANTORDER SET STATUS") and not hit
                and context.compiled_parameters[0].get("id_1") == oid):
            hit.append(1)
            with engine.begin() as rival:
                assert rival.connection.driver_connection is not conn.connection.driver_connection
                rival.execute(update(_O).where(_O.c.id == oid).values(route_resume_claim_id="new partial"))
            expected.append(_snapshot(oid))
    event.listen(engine, "before_cursor_execute", witness)
    try:
        if method == "decline":
            assert _decline(client, oid, driver).status_code == 200
        else:
            with Session(engine) as s:
                assert oid not in taxi_worker.advance_stale_offers(s)
    finally:
        event.remove(engine, "before_cursor_execute", witness)
    assert hit == [1] and _snapshot(oid) == expected[0]
    assert _audit(oid) == []
    if method == "worker":
        assert oid not in geo and _effects_for(effects, oid) == []
    else:
        assert effects == geo == []


@pytest.mark.skipif(engine.dialect.name != "sqlite", reason="true write while old advance owns no physical row")
@pytest.mark.parametrize("found", (False, True))
def test_after_owned_advance_a_rival_marker_blocks_real_geo_outcome(
    client, order, effects, monkeypatch, found,  # noqa: F811
):
    oid, _, driver, other = order
    hit = []

    def lookup(redis, session, o, exclude):
        assert o.id == oid and driver["id"] in exclude
        with engine.begin() as rival:
            assert rival.connection.driver_connection is not session.connection().connection.driver_connection
            rival.execute(update(_O).where(_O.c.id == oid).values(route_resume_claim_id="new partial"))
        hit.append(1)
        return [other["id"]] if found else []

    monkeypatch.setattr(isv, "candidates", lookup)
    assert _decline(client, oid, driver).status_code == 200
    row = _snapshot(oid)
    assert hit == [1] and row.route_resume_claim_id == "new partial"
    assert row.status is S.searching and row.search_round == 1 and row.current_offer_driver_id is None
    assert effects == [] and _audit(oid) == ["busy"]  # prior transition owned; not a stale log


@pytest.mark.parametrize("log_fails", (False, True))
def test_ordinary_decline_matches_once_and_optional_log_cannot_break_winner(
    client, order, effects, monkeypatch, log_fails,  # noqa: F811
):
    oid, _, driver, other = order
    monkeypatch.setattr(isv, "candidates", lambda *a, **k: [other["id"]])
    fails = []
    def witness(conn, cursor, statement, parameters, context, many):
        if statement.upper().startswith("INSERT INTO OFFERDECLINE"):
            fails.append(True)
            raise OperationalError(None, None, RuntimeError("synthetic telemetry unavailable"))
    if log_fails:
        event.listen(engine, "before_cursor_execute", witness)
    try:
        assert _decline(client, oid, driver).status_code == 200
    finally:
        if log_fails:
            event.remove(engine, "before_cursor_execute", witness)
    row = _snapshot(oid)
    assert row.status is S.offered and row.current_offer_driver_id == other["id"] and row.search_round == 2
    assert effects.count(("send", oid, other["id"])) == 1
    assert _audit(oid) == ([] if log_fails else ["busy"])
    assert fails == ([True] if log_fails else [])


@pytest.mark.skipif(engine.dialect.name != "sqlite", reason="actual independent SQLite before matcher cancellation")
def test_marker_before_owned_pause_cancel_blocks_cancel_effects(client, order, effects, monkeypatch):  # noqa: F811
    oid, _, driver, _ = order
    entered, suspension = [], []
    monkeypatch.setattr("app.safety_logic.account_paused", lambda *a, **kw: True)
    monkeypatch.setattr("app.services.push_notification", lambda *a, **kw: suspension.append("pause"))
    monkeypatch.setattr(isv, "_notify_cancel", lambda *a, **kw: suspension.append("cancel"))
    monkeypatch.setattr("app.promo_ride.release", lambda *a, **kw: suspension.append("promo"))

    def hook(conn, cursor, statement, params, context, many):
        sql = statement.strip().upper().split(" WHERE ", 1)[0]
        if sql.startswith("UPDATE INSTANTORDER SET") and "CANCELLED_AT" in sql and not entered:
            entered.append(True)
            with engine.begin() as other:
                assert other.connection.driver_connection is not conn.connection.driver_connection
                other.execute(update(_O).where(_O.c.id == oid).values(route_resume_claim_id="pause-rival"))

    event.listen(engine, "before_cursor_execute", hook)
    try:
        assert _decline(client, oid, driver).status_code == 200
    finally:
        event.remove(engine, "before_cursor_execute", hook)
    row = _snapshot(oid)
    assert entered == [True] and row.route_resume_claim_id == "pause-rival"
    assert row.status is S.searching and effects == suspension == []
