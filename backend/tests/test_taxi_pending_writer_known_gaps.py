"""Known-gap diagnostics on actual writers, not a resume producer or a fix.

The complete pending tuple is synthetic fixture data. Empty fake Redis means
real matcher lookup reaches real no-driver expiry; no commit/CAS/helper mocks.
SQLite evidence is sequential commit visibility, NOT PostgreSQL lock proof.
"""

from datetime import timedelta

import fakeredis
import pytest
from sqlalchemy import select, text
from sqlalchemy.pool import SingletonThreadPool, StaticPool
from sqlmodel import Session

from app import instant_service as isv, services, taxi_worker
from app.db import engine
from app.models import InstantOrder, InstantOrderStatus as S, UserRole
from app.timeutil import utcnow
from test_taxi_status_never_resurrects import _order
from test_taxi_search_revision_postgres import _disposable_pg_before_client  # noqa: F401

PENDING = (
    "matching_resume_revision",
    "matching_resume_next_at",
    "matching_resume_attempts",
)
UNCHANGED = (
    "passenger_id",
    "from_lat",
    "from_lng",
    "from_text",
    "category",
    "tried_lifecycle",
    "waiting_fee_kop",
    "pickup_fee_kop",
)


def snapshot(oid, held=None):
    with engine.connect() as observer:
        if held is not None:
            assert (
                observer.connection.driver_connection
                is not held.connection.driver_connection
            )
        return dict(
            observer.execute(
                select(InstantOrder.__table__).where(InstantOrder.id == oid)
            )
            .mappings()
            .one()
        )


@pytest.fixture
def seeded(client, user_factory, monkeypatch):
    if isinstance(engine.pool, (StaticPool, SingletonThreadPool)):
        pytest.skip("independent physical connections required")
    passenger = user_factory("SeededPendingPassenger")
    driver = user_factory("SeededPendingDriver", role=UserRole.driver)
    redis = fakeredis.FakeStrictRedis(decode_responses=True)
    monkeypatch.setattr(isv, "_redis_override", redis)
    # Transport boundaries only. Notification persistence, pricing, ownership and
    # matcher/expiry are real. No TripShare fixture means no SMS recipient exists.
    monkeypatch.setattr(isv, "send_push", lambda *a, **k: None)
    monkeypatch.setattr(services, "send_push", lambda *a, **k: None)
    ids = []
    now = utcnow()

    def create(status, **kwargs):
        values = dict(
            search_revision=41,
            search_round=2,
            searching_at=now - timedelta(seconds=10),
            matching_resume_revision=41,
            matching_resume_next_at=now - timedelta(seconds=5),
            matching_resume_attempts=3,
            category="comfort",
            ride_price=1,
            price_estimate=1,
            distance_km=2.0,
            eta_min=5.0,
            tried_lifecycle=None,
            pickup_pending=False,
        )
        if status == S.offered:
            values.update(
                current_offer_driver_id=driver["id"],
                offer_token="a" * 32,
                offered_at=now,
                offer_expires_at=now + timedelta(minutes=5),
            )
        values.update(kwargs)
        oid = _order(passenger["id"], status, **values)
        ids.append(oid)
        before = snapshot(oid)
        assert tuple(before[k] for k in PENDING) == (
            41,
            values["matching_resume_next_at"],
            3,
        )
        return oid, before

    try:
        yield create, passenger, driver, now
    finally:
        try:
            with engine.begin() as c:
                for oid in ids:
                    c.execute(
                        text(
                            "DELETE FROM notification WHERE ref_kind='instant' AND ref_id=:i"
                        ),
                        {"i": oid},
                    )
                    c.execute(
                        text("DELETE FROM offerdecline WHERE order_id=:i"), {"i": oid}
                    )
                    c.execute(
                        text("DELETE FROM instanttriedexclusion WHERE order_id=:i"),
                        {"i": oid},
                    )
                    c.execute(text("DELETE FROM instantorder WHERE id=:i"), {"i": oid})
        finally:
            redis.close()


def assert_stale_pending(before, after, revision, status):
    assert after["status"] == status
    assert after["search_revision"] == revision
    assert tuple(after[k] for k in PENDING) == tuple(before[k] for k in PENDING)
    assert after["matching_resume_revision"] == 41
    for field in UNCHANGED:
        assert after[field] == before[field], field


@pytest.mark.parametrize("operation", ["start", "advance", "destination", "fallback"])
def test_known_gap_search_writers_leave_old_pending_after_actual_change(
    seeded, operation
):
    create, _, _, now = seeded
    oid, before = create(S.offered if operation == "advance" else S.searching)
    with engine.connect() as held:
        with Session(bind=held) as session:
            order = session.get(InstantOrder, oid)
            if operation == "start":
                isv.start_matching(session, order, notify=False)
            elif operation == "advance":
                isv.advance_after_no_accept(session, order, notify=False)
            elif operation == "destination":
                # Public service contract takes a precomputed quote. This is
                # explicit input, not a pricing helper or commit replacement.
                isv.apply_destination(
                    session,
                    order,
                    (52.60, 58.33),
                    "Новое место",
                    {"price": 1, "base_price": 1, "distance_km": 3.0, "eta_min": 6.0},
                    now,
                )
            else:
                price = isv._category_price(session, order, "standard")
                assert price is None or price >= order.ride_price
                isv.add_fallback_category(session, order, "standard")
            after = snapshot(oid, held)
    assert_stale_pending(before, after, 42, S.expired)
    assert after["expired_at"] is not None
    assert (
        after["current_offer_driver_id"] is None and after["offer_expires_at"] is None
    )
    assert after["search_round"] == (0 if operation == "start" else 2)
    if operation == "destination":
        assert (after["to_lat"], after["to_lng"], after["to_text"]) == (
            52.60,
            58.33,
            "Новое место",
        )
        assert after["destination_changes"] == before["destination_changes"] + 1
    else:
        assert (after["to_lat"], after["to_lng"], after["to_text"]) == (
            before["to_lat"],
            before["to_lng"],
            before["to_text"],
        )
    if operation == "fallback":
        assert after["fallback_categories"] != before["fallback_categories"]


def test_known_gap_offered_fallback_preserves_offer_but_not_pending_generation(seeded):
    create, _, _, _ = seeded
    oid, before = create(S.offered)
    with Session(engine) as session:
        order = session.get(InstantOrder, oid)
        price = isv._category_price(session, order, "standard")
        assert price is None or price >= order.ride_price
        isv.add_fallback_category(session, order, "standard")
    after = snapshot(oid)
    assert_stale_pending(before, after, 42, S.offered)
    for field in (
        "offer_token",
        "current_offer_driver_id",
        "offer_expires_at",
        "offered_at",
        "ride_price",
        "price_estimate",
        "search_round",
    ):
        assert after[field] == before[field], field
    assert after["fallback_categories"] != before["fallback_categories"]
    # Duplicate is a true no-op: it must not be mislabeled as another transfer.
    with Session(engine) as session:
        isv.add_fallback_category(session, session.get(InstantOrder, oid), "standard")
    assert snapshot(oid) == after


@pytest.mark.parametrize("operation", ["accept", "cancel", "timeout"])
def test_known_gap_accepted_or_terminal_retains_seeded_pending(seeded, operation):
    create, passenger, driver, now = seeded
    old = now - timedelta(hours=taxi_worker.settings.order_stuck_hours + 1)
    kwargs = (
        {"created_at": old, "searching_at": old, "offered_at": old}
        if operation == "timeout"
        else {}
    )
    oid, before = create(S.offered, **kwargs)
    with engine.connect() as held:
        with Session(bind=held) as session:
            if operation == "accept":
                isv.transition(
                    session,
                    oid,
                    isv.Actor.driver,
                    S.accepted,
                    driver["id"],
                    idempotent=False,
                )
            elif operation == "cancel":
                isv.cancel_order(
                    session,
                    oid,
                    isv.Actor.passenger,
                    passenger["id"],
                    "seeded diagnostic",
                )
            else:
                assert oid in taxi_worker.close_stuck_orders(session)
            after = snapshot(oid, held)
    expected = {"accept": S.accepted, "cancel": S.cancelled, "timeout": S.expired}[
        operation
    ]
    assert_stale_pending(before, after, 41, expected)
    assert (
        after["current_offer_driver_id"] is None and after["offer_expires_at"] is None
    )
    if operation == "accept":
        assert after["driver_id"] == driver["id"] and after["accepted_at"] is not None
    elif operation == "cancel":
        assert after["cancelled_at"] is not None and after["cancel_by"] == "passenger"
    else:
        assert (
            after["cancelled_at"] is not None
            and after["cancel_reason"] == "stuck_timeout"
        )
