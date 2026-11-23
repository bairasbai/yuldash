"""Private SQLite projection equality proof; no runtime activation or repricing."""
from dataclasses import FrozenInstanceError, replace
from datetime import timedelta
from uuid import uuid4

import pytest

from app import instant_service as isv, pricing, taxi_marked_offer_card as card
from app import taxi_retirement_agreement_seal as seal, taxi_route_resume as resume
from app.models import InstantOrderStatus as S
from app.taxi_offered_waypoint_retire import OfferedRetirement, _locked_state, retire_offered_waypoints
from app.timeutil import utcnow
from test_taxi_offered_waypoint_preview import _STOP, _mutate, _unexpected
from test_taxi_offered_waypoint_retire import _args, healthy, route, testdb  # noqa: F401 - fixture exports


@pytest.fixture
def approved(request, monkeypatch):
    request.getfixturevalue("route")
    data = request.getfixturevalue("healthy")
    db, owner_id, order_id, prior, successor, *_ = data
    args = _args(data)
    with db.connect() as conn:
        captured = _locked_state(conn, owner_id, order_id)
    # The canonical synthetic quote is already done. Nothing here may quote,
    # dispatch, call a provider, notify or settle pickup after that.
    monkeypatch.setattr(pricing, "route_metrics", _unexpected)
    for name in ("destination_quote", "try_offer_next", "_push_offer", "_settle_pickup_fee"):
        monkeypatch.setattr(isv, name, _unexpected)
    assert retire_offered_waypoints(db, **args) is OfferedRetirement.COMMITTED
    actual = resume._state(resume.observe(db, order_id))
    kwargs = dict(
        key=args["key"], owner_id=owner_id, order_id=order_id, old_source=captured,
        preview=args["preview"], agreement=args["agreement"],
        marker_id=actual["route_resume_id"], retired_at=actual["route_resume_retired_at"],
        deadline_at=actual["route_resume_deadline_at"],
    )
    # Feed only generated lineage from this result. Route/fare to bind come
    # from verified old snapshot + independently vetted replacement projection.
    saved = seal.bind_retirement_agreement(**kwargs)
    assert saved is not None and saved._digest == actual[resume._SEAL]
    return db, owner_id, order_id, prior, successor, kwargs, saved, actual


def _match(fixture, state=None, **overrides):
    _, owner, oid, _, _, kwargs, saved, retired = fixture
    args = dict(key=kwargs["key"], seal=saved, owner_id=owner, order_id=oid,
                current=retired if state is None else state, now=utcnow())
    return seal.matches_retirement_agreement(**(args | overrides))


def _successor(fixture):
    db, _, oid, _, successor, *_ = fixture
    assert resume.claim(db, resume.observe(db, oid), lease_seconds=30) is None
    _mutate(db, oid, **{resume._SEAL: None})  # earlier unsealed lab only
    claim = resume.claim(db, resume.observe(db, oid), lease_seconds=30)
    assert claim is not None and _match(fixture, resume._state(claim))
    final = resume.store_offer(db, claim, driver_id=successor, offer_seconds=25)
    assert final is not None
    return resume._state(final)


def test_vetted_projection_matches_exact_sql_retirement_claim_and_offer(approved):
    db, owner, oid, prior, successor, kwargs, fingerprint, retired = approved
    assert retired["status"] is S.searching and _match(approved)
    assert retired["route_resume_previous_driver_id"] == prior
    assert retired["route_resume_deadline_at"] - retired["route_resume_retired_at"] == timedelta(seconds=120)
    final = _successor(approved)
    assert final["status"] is S.offered and final["search_round"] == retired["search_round"] + 1
    assert final["current_offer_driver_id"] == successor and final["offer_expires_at"] is not None
    assert final["route_resume_ready_at"] is None and final["route_resume_claim_id"] is None
    assert all(final[name] == retired[name] for name in seal._FIELDS)
    assert _match(approved, final)
    with db.connect() as conn:
        distinct = card.bind_current_card(conn, kwargs["key"], owner_id=owner, order_id=oid, driver_id=successor)
    assert distinct is not None and distinct._digest != fingerprint._digest
    assert not _match(approved, final, seal=distinct)
    assert not hasattr(fingerprint, "__dict__")
    assert fingerprint._digest.hex() not in repr(fingerprint)
    assert retired["route_resume_id"] not in repr(fingerprint)
    with pytest.raises(FrozenInstanceError):
        fingerprint._digest = b"x"


def test_every_declared_immutable_is_bound_independently(approved):
    *_, state = approved
    assert _match(approved)
    mutations = {
        "id": state["id"] + 1, "passenger_id": state["passenger_id"] + 10,
        "route_resume_id": uuid4().hex,
        "route_resume_previous_driver_id": state["route_resume_previous_driver_id"] + 10,
        "route_resume_retired_at": state["route_resume_retired_at"] - timedelta(microseconds=1),
        "route_resume_deadline_at": state["route_resume_deadline_at"] - timedelta(microseconds=1),
        "destination_changed_at": state["destination_changed_at"] - timedelta(microseconds=1),
        "from_lat": 54.6, "from_lng": 55.6, "from_text": "SYNTHETIC DIFFERENT PICKUP",
        "to_lat": 54.8, "to_lng": 55.9, "to_text": "SYNTHETIC DIFFERENT DESTINATION",
        "category": "comfort", "options": "booster", "women_only": True, "fallback_categories": "comfort",
        "tariff_id": state["tariff_id"] + 1, "pricing_k": 1.1, "surge_k": 1.2,
        "pickup_pending": True, "pickup_enroute": True, "pickup_km": 9.0,
        "pickup_fee_kop": 1000, "options_fee_kop": 2000, "weather_fee_kop": 3000, "weather_kind": "ice",
        "waypoints_json": isv.dump_waypoints([_STOP | {"text": "SYNTHETIC DIFFERENT STOP", "done": False}]),
        "ride_price": state["ride_price"] + 1, "ride_base_price": state["ride_base_price"] + 1,
        "price_estimate": state["price_estimate"] + 1, "promo_discount_kop": 1000,
        "distance_km": state["distance_km"] + 1, "eta_min": state["eta_min"] + 1,
    }
    assert set(mutations) == set(seal._FIELDS)
    for name, changed in mutations.items():
        assert state[name] != changed, name
        # Also test authenticated material absolutely covers each field even
        # when the wrapper would separately refuse a malformed marker shape.
        k = approved[-3]["key"]
        candidate = state | {name: changed}
        assert seal._digest(k, candidate) != seal._digest(k, state), name
        assert not _match(approved, candidate), name
        assert not _match(approved, {f: v for f, v in state.items() if f != name}), name


@pytest.mark.parametrize("change", ("route", "fare", "generation", "rotation"))
def test_coherent_sql_mutation_before_first_card_cannot_match_original_agreement(approved, change):
    db, owner, oid, _, successor, kwargs, fingerprint, _ = approved
    original = _successor(approved)
    edits = {
        "route": {"waypoints_json": isv.dump_waypoints([_STOP | {"text": "SYNTHETIC DIFFERENT STOP", "done": False}])},
        "fare": {"ride_price": original["ride_price"] + 10, "price_estimate": original["price_estimate"] + 10},
        "generation": {"route_resume_id": uuid4().hex},
        "rotation": {"route_resume_retired_at": original["route_resume_retired_at"] - timedelta(seconds=1),
                     "destination_changed_at": original["destination_changed_at"] - timedelta(seconds=1),
                     "route_resume_deadline_at": original["route_resume_deadline_at"] - timedelta(seconds=1)},
    }
    _mutate(db, oid, **edits[change])
    with db.connect() as conn:
        assert card.bind_current_card(conn, kwargs["key"], owner_id=owner, order_id=oid, driver_id=successor)
    changed = resume._state(resume.observe(db, oid))
    assert not _match(approved, changed, seal=fingerprint)


def test_missing_wrong_source_key_marker_and_deadline_never_reissue(approved):
    *_, kwargs, fingerprint, retired = approved
    now = kwargs["retired_at"]
    assert _match(approved, now=now)
    assert not _match(approved, now=now - timedelta(microseconds=1))
    assert not _match(approved, now=kwargs["deadline_at"])
    assert not _match(approved, seal=None)
    assert not _match(approved, seal=replace(fingerprint, _digest=b"x" * 32))
    assert not _match(approved, key=b"\x55" * 32)
    assert not _match(approved, owner_id=True)
    assert not _match(approved, current=retired | {"route_resume_id": None})
    source_modified = kwargs["old_source"][:-1] + (123456789,)
    for changes in (
        {"old_source": source_modified}, {"old_source": resume.observe(approved[0], approved[2]).values},
        {"agreement": None}, {"agreement": replace(kwargs["agreement"], binding=b"x" * 32)},
        {"preview": replace(kwargs["preview"], ride_base_price=1)}, {"owner_id": kwargs["owner_id"] + 10},
        {"order_id": kwargs["order_id"] + 10}, {"key": b"\x55" * 32}, {"marker_id": ""},
        {"deadline_at": kwargs["deadline_at"] + timedelta(microseconds=1)},
        {"deadline_at": kwargs["deadline_at"] - timedelta(seconds=1)},
        {"retired_at": kwargs["preview"].valid_until,
         "deadline_at": kwargs["preview"].valid_until + timedelta(seconds=120)},
    ):
        assert seal.bind_retirement_agreement(**(kwargs | changes)) is None, tuple(changes)
