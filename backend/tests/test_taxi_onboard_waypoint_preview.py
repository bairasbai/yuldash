"""Synthetic, provider-free tests of the inert onboard comparison."""
import json
from dataclasses import FrozenInstanceError, replace
from datetime import datetime, timedelta, timezone

import pytest
from fastapi import HTTPException

from app.livepos import LIVEPOS_TTL_SEC
from app.pricing import RouteMetrics
from app.taxi_onboard_waypoint_preview import OnboardAssignment, OnboardPosition, ProposedStop, compare_remaining

_ONBOARD = datetime(2026, 11, 18, 12, 5)
_NOW = _ONBOARD + timedelta(seconds=10)
_ASSIGNMENT = OnboardAssignment(701, 904, 905, _ONBOARD - timedelta(minutes=2), _ONBOARD, 2)
_POSITION = OnboardPosition(_ASSIGNMENT, _NOW - timedelta(seconds=3), 0, 0)
_COMPLETED = {"lat": 0.02, "lng": 0, "text": "Completed", "done": True}
_CURRENT = {"lat": 0.012, "lng": 0, "text": "Current", "done": False}
_OTHER = {"lat": 0.016, "lng": 0, "text": "Other", "done": False}
_ADDITION = ProposedStop(0.017, 0.006, "New stop")
_REQUEST = (ProposedStop(0.012, 0, "Current"), _ADDITION, ProposedStop(0.016, 0, "Other"))
_STORED = json.dumps([_COMPLETED, _CURRENT, _OTHER])


def _inputs(**changes):
    def must_not_route(*_args):
        pytest.fail("Ineligible snapshot must not contact routing")

    return dict(assignment=_ASSIGNMENT, position=_POSITION, now=_NOW, to_lat=0.03, to_lng=0,
                stored_waypoints=_STORED, requested=_REQUEST, standing=False, route=must_not_route) | changes


@pytest.mark.parametrize(("before", "after"), ((7, 11.5), (11.5, 7), (8, 8)))
def test_same_live_sample_only_remaining_stops_and_real_signed_delta(before, after):
    calls = []

    def route(start, stops, finish):
        calls.append((start, stops, finish))
        return RouteMetrics(4, before if len(calls) == 1 else after, source="yandex")

    evidence = compare_remaining(**_inputs(route=route, standing=True))
    assert evidence.delta_min == after - before
    assert calls == [
        ((0, 0), ((0.012, 0), (0.016, 0)), (0.03, 0)),
        ((0, 0), ((0.012, 0), (0.017, 0.006), (0.016, 0)), (0.03, 0)),
    ]
    assert _POSITION.observed_at == _NOW - timedelta(seconds=3)
    assert _STORED == json.dumps([_COMPLETED, _CURRENT, _OTHER])
    assert "lat" not in repr(_POSITION) and str(_ASSIGNMENT.driver_id) not in repr(_ASSIGNMENT)
    with pytest.raises(FrozenInstanceError):
        _POSITION.lng = 15


@pytest.mark.parametrize("changes", (
    {"position": None},
    {"position": {"lat": 0, "lng": 0}},
    {"position": replace(_POSITION, observed_at=_NOW + timedelta(microseconds=1))},
    {"now": _POSITION.observed_at + timedelta(seconds=LIVEPOS_TTL_SEC)},
    {"now": _NOW.replace(tzinfo=timezone.utc)},
    {"assignment": replace(_ASSIGNMENT, driver_id=906)},
    {"assignment": replace(_ASSIGNMENT, passenger_id=907)},
    {"assignment": replace(_ASSIGNMENT, order_id=702)},
    {"assignment": replace(_ASSIGNMENT, reassigns=3)},
    {"assignment": replace(_ASSIGNMENT, onboard_at=_ONBOARD - timedelta(seconds=1))},
    {"assignment": replace(_ASSIGNMENT, accepted_at=_ONBOARD - timedelta(seconds=1))},
    {"to_lat": 0.005},
    {"to_lat": 0.0},
    {"to_lat": float("nan")},
    {"requested": (*_REQUEST, ProposedStop(0.02, 0.02))},
    {"requested": (_REQUEST[-1], _REQUEST[0])},  # surviving stops reversed
    {"requested": (_REQUEST[0], _REQUEST[-1])},  # not an edit
    {"stored_waypoints": '[{"lat": NaN, "lng": 0, "done": false}]'},
    {"stored_waypoints": '[{"lat": 0.01, "lng": 0, "done": "false"}]'},
    {"standing": True, "requested": (_ADDITION, _REQUEST[2])},  # first pending removed
    {"standing": True, "requested": (_ADDITION, _REQUEST[0], _REQUEST[2])},  # put another ahead
    {"standing": True, "stored_waypoints": json.dumps([_COMPLETED])},  # unknown active stop
    {"standing": True, "requested": (replace(_REQUEST[0], text="Different"), *_REQUEST[1:])},
))
def test_ineligible_input_fails_without_routing(changes):
    with pytest.raises(HTTPException) as result:
        compare_remaining(**_inputs(**changes))
    assert result.value.status_code == 409
    assert result.value.detail["code"] == "taxi_waypoints_unavailable"


@pytest.mark.parametrize("bad", (
    None, RouteMetrics(4, 5), RouteMetrics(4, 5, source="mixed"),
    RouteMetrics(4, float("inf"), source="yandex"), RouteMetrics(float("nan"), 5, source="yandex"),
    RouteMetrics(4, True, source="yandex"), RouteMetrics(4, 0, source="yandex"),
))
@pytest.mark.parametrize("bad_first", (False, True))
def test_unreliable_route_has_unknown_not_estimated_zero(bad, bad_first):
    good = RouteMetrics(5, 10, source="yandex")
    results = iter((bad, good) if bad_first else (good, bad))
    evidence = compare_remaining(**_inputs(route=lambda *_: next(results)))
    assert evidence.delta_min is None


@pytest.mark.parametrize("fields", (
    {"driver_id": True}, {"order_id": 0}, {"passenger_id": _ASSIGNMENT.driver_id},
    {"accepted_at": _ONBOARD + timedelta(seconds=1)}, {"reassigns": -1},
))
def test_assignment_constructor_has_strict_structure(fields):
    with pytest.raises(ValueError):
        replace(_ASSIGNMENT, **fields)


@pytest.mark.parametrize("fields", (
    {"lat": float("nan")}, {"lng": float("inf")}, {"lat": 91}, {"lng": -181},
    {"lat": True}, {"observed_at": _ONBOARD - timedelta(microseconds=1)},
))
def test_sample_constructor_refuses_malformed_or_preboard_point(fields):
    with pytest.raises(ValueError):
        replace(_POSITION, **fields)
