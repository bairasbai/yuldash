"""Exact driver-card observation only, never accept, fee, receipt, or dispatch."""
from dataclasses import FrozenInstanceError
from datetime import timedelta
from uuid import uuid4

import pytest
from sqlalchemy import event, insert, literal

from app import instant_service as isv, pricing, services, taxi_marked_offer_card as card
from app import taxi_route_resume as resume
from app.models import InstantOrderStatus as S, LedgerEntry, LedgerKind, Payment
from app.timeutil import utcnow
from test_taxi_offered_waypoint_preview import _mutate, _row, _STOP, _unexpected, testdb  # noqa: F401
from test_taxi_offered_waypoint_retire import _args, healthy, route  # noqa: F401
from app.taxi_offered_waypoint_retire import OfferedRetirement, retire_offered_waypoints

_KEY = b"\xc7" * 32
_STOPS = isv.dump_waypoints([_STOP | {"done": False}])


def _identity(data, driver=None):
    _, owner, order, prior, successor, *_ = data
    return dict(owner_id=owner, order_id=order, driver_id=successor if driver is None else driver)


def _bind(data, **overrides):
    db = data[0]
    with db.connect() as conn:
        return card.bind_current_card(conn, _KEY, **(_identity(data) | overrides))


def _matches(data, proof, *, key=_KEY, **overrides):
    with data[0].connect() as conn:
        return card.matches_current_card(conn, key, proof, **(_identity(data) | overrides))


@pytest.fixture
def marked(request):
    fixture = request.getfixturevalue("testdb")
    db, _, oid, prior, successor, *_ = fixture
    retired = utcnow() - timedelta(seconds=1)
    _mutate(db, oid, current_offer_driver_id=successor, offered_at=retired,
            offer_expires_at=retired + timedelta(seconds=30), search_round=2,
            destination_changed_at=retired, route_resume_id=uuid4().hex,
            route_resume_previous_driver_id=prior, route_resume_retired_at=retired,
            route_resume_deadline_at=retired + timedelta(seconds=120), waypoints_json=_STOPS)
    return fixture


def test_real_retire_claim_store_yields_only_private_driver_observation(request, monkeypatch):
    request.getfixturevalue("route")
    data = request.getfixturevalue("healthy")
    db, _, oid, prior, successor, *_ = data
    assert _bind(data, driver_id=prior) is None  # real valid unmarked offer
    args = _args(data)
    assert retire_offered_waypoints(db, **args) is OfferedRetirement.COMMITTED
    assert _bind(data) is None  # searching is not a card
    claim = resume.claim(db, resume.observe(db, oid), lease_seconds=25)
    assert resume.store_offer(db, claim, driver_id=successor, offer_seconds=20)
    before = _row(db, oid)
    assert before.status is S.offered and before.route_resume_previous_driver_id == prior
    statements = []

    for obj, names in (
        (isv, ("reconcile_offer", "try_offer_next", "_settle_pickup_fee", "_push_offer", "destination_quote")),
        (pricing, ("route_metrics",)),
        (services, ("push_notification",)),
    ):
        for name in names:
            monkeypatch.setattr(obj, name, _unexpected)

    def trace(conn, cursor, sql, parameters, context, executemany):
        statements.append(sql.split()[0].upper())

    event.listen(db, "before_cursor_execute", trace)
    try:
        first = _bind(data)
        assert type(first) is card.MarkedOfferCardProof
        assert _matches(data, first)
        assert _bind(data, driver_id=prior) is None
        assert _bind(data, owner_id=prior) is None
        assert _bind(data, order_id=oid + 100) is None
        assert not _matches(data, first, key=b"\xba" * 32)
        assert statements == ["SELECT"] * 6
    finally:
        event.remove(db, "before_cursor_execute", trace)

    assert _row(db, oid) == before
    assert not hasattr(first, "__dict__")
    assert len(first._digest) == 32
    assert "SYNTHETIC" not in repr(first) and first._digest.hex() not in repr(first)
    assert before.route_resume_id not in repr(first)
    with pytest.raises(FrozenInstanceError):
        first._digest = b"x" * 32


@pytest.mark.parametrize("values", [
    {"route_resume_id": None}, {"route_resume_id": "bad"}, {"route_resume_retired_at": None},
    {"route_resume_previous_driver_id": None}, {"route_resume_deadline_at": None},
    {"route_resume_claim_id": "a" * 32}, {"route_resume_ready_at": "time"},
    {"status": S.searching}, {"current_offer_driver_id": None}, {"offer_expires_at": None},
    {"destination_changed_at": None}, {"search_round": 1}, {"pickup_pending": True},
    {"paid": True}, {"price_final": 280}, {"payment_method": "card"},
    {"pending_price": 240}, {"waiting_fee_kop": 25}, {"cancel_fee_kop": 2},
    {"price_estimate": 1}, {"promo_discount_kop": 99999}, {"pickup_fee_kop": -1},
    {"ride_base_price": -1}, {"distance_km": -1.0},
    {"round_trip": True}, {"fallback_categories": "comfort"},
    {"waypoints_json": "not json"}, {"waypoints_json": '[{"lat":1,"lat":2,"lng":1,"text":"x","done":false}]'},
    {"waypoints_json": isv.dump_waypoints([_STOP | {"done": True}])},
])
def test_rejects_partial_corrupt_and_uncertain_rows(marked, values):
    db, _, oid, *_ = marked
    if values.get("route_resume_ready_at") == "time":
        values = {"route_resume_ready_at": utcnow()}
    proof = _bind(marked)
    assert proof is not None
    _mutate(db, oid, **values)
    assert _bind(marked) is None
    assert not _matches(marked, proof)


@pytest.mark.parametrize("variant", ["lease", "deadline", "widened", "lease_past_deadline", "future_offer"])
def test_real_snapshot_time_and_stored_ceiling(marked, variant, monkeypatch):
    db, _, oid, *_ = marked
    before = _row(db, oid)
    retired, lease, deadline = before.route_resume_retired_at, before.offer_expires_at, before.route_resume_deadline_at
    if variant in ("lease", "deadline"):
        # Exact edge through an SQL value from the same read projection; the
        # positive fixture also samples the real database clock.
        edge = lease if variant == "lease" else deadline
        if variant == "deadline":
            _mutate(db, oid, offer_expires_at=deadline)
        monkeypatch.setattr(resume, "_clock", lambda conn: literal(edge.isoformat(sep=" ")))
    elif variant == "widened":
        _mutate(db, oid, route_resume_deadline_at=retired + timedelta(seconds=121))
    elif variant == "lease_past_deadline":
        _mutate(db, oid, offer_expires_at=deadline + timedelta(seconds=1))
    else:
        _mutate(db, oid, offered_at=utcnow() + timedelta(seconds=5))
    assert _bind(marked) is None


@pytest.mark.parametrize("variation", ["recipient", "owner", "driver_attached", "prior_same", "payment", "ledger"])
def test_bound_identity_assignment_and_local_settlement(marked, variation):
    db, owner, oid, prior, successor, *_ = marked
    proof = _bind(marked)
    assert proof is not None
    if variation == "payment":
        with db.begin() as c:
            c.execute(insert(Payment).values(user_id=owner, order_id=oid, purpose="ride", status="pending"))
    elif variation == "ledger":
        with db.begin() as c:
            c.execute(insert(LedgerEntry).values(driver_id=successor, order_id=oid, kind=LedgerKind.earn))
    else:
        values = {"recipient": {"current_offer_driver_id": prior}, "owner": {"passenger_id": prior},
                  "driver_attached": {"driver_id": successor}, "prior_same": {
                      "route_resume_previous_driver_id": successor}}[variation]
        _mutate(db, oid, **values)
    assert _bind(marked) is None
    assert not _matches(marked, proof)


@pytest.mark.parametrize("values", [
    {"from_text": "SYNTHETIC CHANGED PICKUP"}, {"to_lat": 55.0},
    {"waypoints_json": isv.dump_waypoints([_STOP | {"text": "SYNTHETIC CHANGED STOP", "done": False}])},
    {"search_round": 3}, {"ride_base_price": 220}, {"eta_min": 12.0}, {"distance_km": 9.0},
    {"promo_discount_kop": 8000}, {"ride_price": 250, "price_estimate": 290},
])
def test_previous_digest_never_matches_mutated_but_coherent_current_observation(marked, values):
    db, _, oid, *_ = marked
    original = _bind(marked)
    assert original is not None and _matches(marked, original)
    _mutate(db, oid, **values)
    assert not _matches(marked, original)
    current = _bind(marked)
    assert current is not None and _matches(marked, current)
    # Deliberate boundary: the marker has no link to an accepted projection
    # and a new observation cannot say a previous passenger agreed to this row.


def test_ordinary_card_v2_and_untyped_digests_are_not_marked_proofs(marked, monkeypatch):
    db, _, oid, _, successor, *_ = marked
    from app.config import settings
    monkeypatch.setattr(settings, "jwt_secret", _KEY.hex())
    before = _row(db, oid)
    old_v2 = isv.offer_card_precondition(isv.capture_offer_for_accept(before), None, successor, utcnow())
    assert old_v2 and old_v2.startswith("v1.")
    assert not _matches(marked, old_v2)
    assert not _matches(marked, card.MarkedOfferCardProof(bytes.fromhex(old_v2[3:])), key=_KEY.hex().encode())
    assert not _matches(marked, card.MarkedOfferCardProof(b"x"))


def test_payment_for_another_order_is_not_mistaken_for_this_card(marked):
    db, owner, oid, *_ = marked
    proof = _bind(marked)
    assert proof is not None
    with db.begin() as c:
        order = card.InstantOrder.__table__
        other_order = c.execute(insert(order).values(passenger_id=owner).returning(order.c.id)).scalar_one()
        assert other_order != oid
        c.execute(insert(Payment).values(user_id=owner, order_id=other_order, purpose="ride", status="pending"))
    assert _matches(marked, proof)
