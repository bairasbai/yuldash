"""Real scalar SQLite/canonical quote; deterministic synthetic routing only."""
from dataclasses import FrozenInstanceError
from datetime import timedelta

import pytest
from sqlalchemy import event, select, update
from sqlmodel import Session, SQLModel, create_engine

from app import instant_service as isv, pricing, promo_ride
from app import taxi_offered_waypoint_preview as offered
from app.models import InstantOrder, InstantOrderStatus as S, Tariff, TaxiRouteEditOutcome, User
from app.timeutil import utcnow

_O = InstantOrder.__table__
_A = (54.7, 55.8)
_B = (54.71, 55.81)
_W = (54.705, 55.805)
_STOP = dict(lat=_W[0], lng=_W[1], text="SYNTHETIC STOP")


@pytest.fixture
def testdb(tmp_path):
    db = create_engine(f"sqlite:///{tmp_path / 'offered-preview.sqlite'}", hide_parameters=True)
    SQLModel.metadata.create_all(db)
    at = utcnow()
    with Session(db) as s:
        owner, recipient, second = (User(phone=f"+7999900520{i}") for i in range(3))
        tariff = Tariff(zone="city", category="standard", base=80, per_km=10, per_min=4)
        s.add_all((owner, recipient, second, tariff))
        s.flush()
        row = InstantOrder(
            passenger_id=owner.id, current_offer_driver_id=recipient.id, tariff_id=tariff.id,
            status=S.offered, search_round=1, created_at=at - timedelta(seconds=3),
            searching_at=at - timedelta(seconds=2), offered_at=at - timedelta(seconds=1),
            offer_expires_at=at + timedelta(seconds=60),
            from_lat=_A[0], from_lng=_A[1], from_text="SYNTHETIC PICKUP",
            to_lat=_B[0], to_lng=_B[1], to_text="SYNTHETIC DESTINATION",
            ride_price=240, ride_base_price=210, price_estimate=280,
            pickup_fee_kop=4000, promo_discount_kop=10000, distance_km=8, eta_min=11,
        )
        s.add(row)
        s.flush()
        vals = db, owner.id, row.id, recipient.id, second.id, tariff.id, at
        s.commit()
    try:
        yield vals
    finally:
        db.dispose()


def _preview(testdb, *, owner=None, oid=None, requested=None, budget=lambda _: None, until=None):
    db, owner_id, order_id, _, _, _, at = testdb
    return offered.preview_offered_waypoints(
        db, owner_id=owner if owner is not None else owner_id,
        order_id=oid if oid is not None else order_id,
        requested=[_STOP] if requested is None else requested,
        quote_valid_until=at + timedelta(seconds=90) if until is None else until,
        guard_budget=budget,
    )


def _row(db, oid):
    with db.connect() as c:
        return c.execute(select(_O).where(_O.c.id == oid)).one()


def _mutate(db, oid, **values):
    with db.begin() as c:
        c.execute(update(_O).where(_O.c.id == oid).values(**values))


def _unexpected(*args, **kwargs):
    pytest.fail("Unexpected dispatch/network/side-effect getter")


@pytest.fixture
def route(monkeypatch):
    for name in ("reconcile_offer", "try_offer_next", "start_matching",
                 "advance_after_no_accept", "notify_driver_destination", "livepos_get"):
        monkeypatch.setattr(isv, name, _unexpected)
    calls = []

    def route_metrics(a, b):
        calls.append((a, b))
        return pricing.RouteMetrics(distance_km=3.0, duration_min=4.0, source="synthetic")

    monkeypatch.setattr(pricing, "route_metrics", route_metrics)
    return calls


def test_real_pricing_only_reads_scalar_offer_and_keeps_destination(testdb, route):
    db, owner_id, oid, _, _, _, at = testdb
    before = _row(db, oid)
    statements = []
    budget = []

    def watch(conn, cursor, statement, parameters, context, executemany):
        statements.append(statement.strip().upper())

    event.listen(db, "before_cursor_execute", watch)
    p = _preview(testdb, budget=budget.append)
    assert p is not None
    assert budget == [owner_id] and route == [(_A, _W), (_W, _B)]
    assert len(statements) >= 3
    assert all(statement.startswith("SELECT") and "FOR UPDATE" not in statement for statement in statements)
    assert p.valid_until == before.offer_expires_at and p.observed_at < p.valid_until
    assert p.terms.ride_price_kop == 17000  # canonical round_to_10(80 + 6*10 + 8*4)
    assert p.terms.previous_ride_price_kop == 24000
    assert p.terms.payable_estimate_kop == 21000 - promo_ride.cap_for_price(10000, 170)
    assert p.terms.previous_payable_estimate_kop == 18000
    assert p.ride_base_price == 170 and (p.distance_km, p.eta_min) == (6, 8)
    assert not hasattr(p, "source") and not hasattr(p, "waypoints_json")
    assert "SYNTHETIC" not in repr(p) and p._source[0:2] == (oid, owner_id)
    assert isv.parse_waypoints(p._waypoints_json) == [_STOP | {"done": False}]
    with pytest.raises(FrozenInstanceError):
        p.valid_until = at
    with db.connect() as conn:
        assert conn.scalar(select(TaxiRouteEditOutcome.attempt_id)) is None
    assert _row(db, oid) == before
    limited = _preview(testdb, until=at + timedelta(seconds=30))
    assert limited.valid_until == at + timedelta(seconds=30)


def test_owner_explicit_order_and_budget_before_routing(testdb, route):
    db, owner, oid, recipient, other, _, _ = testdb
    budget = []
    assert _preview(testdb, owner=recipient, budget=budget.append) is None
    assert _preview(testdb, owner=other, budget=budget.append) is None
    assert _preview(testdb, oid=oid + 999, budget=budget.append) is None
    assert _preview(testdb, requested=[_STOP | {"to_lng": 56}], budget=budget.append) is None
    assert _preview(testdb, requested=[], budget=budget.append) is None
    assert budget == route == []

    class BudgetExhausted(Exception):
        pass

    def denied(uid):
        assert uid == owner
        raise BudgetExhausted

    with pytest.raises(BudgetExhausted):
        _preview(testdb, budget=denied)
    assert route == []


@pytest.mark.parametrize("change", [
    dict(status=S.accepted), dict(status=S.searching), dict(driver_id=1),
    dict(search_round=0), dict(current_offer_driver_id=None), dict(offered_at=None),
    dict(searching_at=None), dict(offer_expires_at=None), dict(fallback_categories="comfort"),
    dict(retry_count=1), dict(reassigns=1), dict(round_trip=True), dict(pickup_pending=True),
    dict(paid=True), dict(price_final=280), dict(waiting_fee_kop=1), dict(pending_reason="price"),
    dict(pending_price=260), dict(route_resume_claim_id="historical"),
    dict(route_resume_id="previous"), dict(ride_price=0), dict(price_estimate=1),
    dict(waypoints_json=isv.dump_waypoints([_STOP | {"done": True}])),
    dict(waypoints_json='[{"lat":"malformed"}]'),
])
def test_unsupported_scope_refuses_before_budget_or_quote(testdb, route, change):
    db, _, oid, *_ = testdb
    _mutate(db, oid, **change)
    saved = _row(db, oid)
    assert _preview(testdb, budget=_unexpected) is None
    assert route == [] and _row(db, oid) == saved


@pytest.mark.parametrize("field", ("scheduled_at", "wait_until", "waiting_started_at",
                                    "destination_changed_at", "expired_at"))
def test_scheduled_waiting_or_fresh_legacy_write_refused(testdb, route, field):
    db, _, oid, _, _, _, at = testdb
    _mutate(db, oid, **{field: at})
    assert _preview(testdb, budget=_unexpected) is None
    assert route == []


@pytest.mark.parametrize("field", ("receiver", "owner", "status", "round", "deadline", "route", "promo"))
def test_fresh_scalar_recheck_refuses_actual_competing_commit_during_pricing(testdb, monkeypatch, field):
    db, _, oid, recipient, other, _, at = testdb
    changes = {
        "receiver": dict(current_offer_driver_id=other),
        "owner": dict(passenger_id=other),
        "status": dict(status=S.accepted, driver_id=recipient),
        "round": dict(search_round=2),
        "deadline": dict(offer_expires_at=at + timedelta(seconds=45)),
        "route": dict(to_text="SYNTHETIC NEW DESTINATION"),
        "promo": dict(promo_discount_kop=9999),
    }[field]
    calls = []

    def detour(a, b):
        if not calls:
            _mutate(db, oid, **changes)
        calls.append((a, b))
        return pricing.RouteMetrics(distance_km=3.0, duration_min=4.0, source="synthetic")

    monkeypatch.setattr(pricing, "route_metrics", detour)
    assert _preview(testdb) is None
    assert calls == [(_A, _W), (_W, _B)]
    after = _row(db, oid)
    assert all(getattr(after, key) == value for key, value in changes.items())
    assert after.waypoints_json == ""


def test_expiry_initial_refusal_and_expiry_while_pricing_do_not_reconcile(testdb, route, monkeypatch):
    db, _, oid, _, _, _, at = testdb
    before = _row(db, oid)
    ticks = iter((at, at, before.offer_expires_at))
    monkeypatch.setattr(offered, "utcnow", lambda: next(ticks))
    assert _preview(testdb) is None and len(route) == 2
    monkeypatch.setattr(offered, "utcnow", lambda: before.offer_expires_at)
    assert _preview(testdb, budget=_unexpected) is None
    assert len(route) == 2 and _row(db, oid) == before


def test_canonical_active_tariff_fallback_outside_category_refused(testdb, route):
    db, _, oid, *_ = testdb
    _mutate(db, oid, category="comfort")
    assert _preview(testdb, budget=_unexpected) is None and route == []


def test_canonical_driver_consent_refusal_remains_no_candidate(testdb, route):
    db, _, oid, *_ = testdb
    _mutate(db, oid, ride_price=40, price_estimate=80)
    assert _preview(testdb) is None and len(route) == 2


def test_budget_elapsed_to_offer_boundary_never_routes(testdb, route, monkeypatch):
    db, owner, oid, _, _, _, at = testdb
    row = _row(db, oid)
    ticks = iter((at, row.offer_expires_at))
    budget = []
    monkeypatch.setattr(offered, "utcnow", lambda: next(ticks))
    assert _preview(testdb, budget=budget.append) is None
    assert budget == [owner] and route == [] and _row(db, oid) == row


def test_competing_canonical_tariff_change_during_pricing_refuses(testdb, monkeypatch):
    db, _, oid, _, _, tariff, _ = testdb
    row = _row(db, oid)
    legs = []

    def detour(a, b):
        if not legs:
            with db.begin() as conn:
                conn.execute(update(Tariff).where(Tariff.id == tariff).values(per_km=91))
        legs.append((a, b))
        return pricing.RouteMetrics(distance_km=3.0, duration_min=4.0, source="synthetic")

    monkeypatch.setattr(pricing, "route_metrics", detour)
    assert _preview(testdb) is None and legs == [(_A, _W), (_W, _B)]
    assert _row(db, oid) == row


@pytest.mark.parametrize("changed", ("owner", "offer", "tariff"))
def test_actual_change_inside_budget_refuses_before_any_routing(testdb, route, changed):
    db, owner, oid, _, other, tid, _ = testdb

    def budget(uid):
        assert uid == owner
        if changed == "tariff":
            with db.begin() as conn:
                conn.execute(update(Tariff).where(Tariff.id == tid).values(per_min=61))
        else:
            values = {"owner": {"passenger_id": other}, "offer": {"current_offer_driver_id": other}}
            _mutate(db, oid, **values[changed])

    assert _preview(testdb, budget=budget) is None
    assert route == []


def test_tariff_aba_at_actual_pricing_read_is_not_accepted(testdb, route, monkeypatch):
    db, _, oid, _, _, tariff_id, _ = testdb
    trace = []
    original = isv.active_tariff

    def selected(session, zone, category):
        # A is captured by the preview. Persist B before the canonical SQL
        # pricing read, then restore A after that SQL read returns its ORM row.
        with db.begin() as conn:
            conn.execute(update(Tariff).where(Tariff.id == tariff_id).values(per_km=20))
        try:
            row = original(session, zone, category)
            assert row.id == tariff_id and row.per_km == 20
            trace.append(row.per_km)
        finally:
            with db.begin() as conn:
                conn.execute(update(Tariff).where(Tariff.id == tariff_id).values(per_km=10))
        return row

    monkeypatch.setattr(isv, "active_tariff", selected)
    saved = _row(db, oid)
    assert _preview(testdb) is None
    assert trace == [20] and route == [(_A, _W), (_W, _B)]
    assert _row(db, oid) == saved
    with db.connect() as conn:
        assert conn.scalar(select(Tariff.per_km).where(Tariff.id == tariff_id)) == 10
