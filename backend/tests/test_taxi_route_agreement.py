"""Inert TF04 boundary: exact agreed terms, no live quote or receipt."""
from dataclasses import FrozenInstanceError, replace
from datetime import datetime, timedelta, timezone

import pytest

from app.models import InstantOrder, InstantOrderStatus
from app.taxi_route_agreement import RouteAgreement, QuotedTerms, bind_quoted_terms, matches_current_terms
from app.taxi_waypoint_consent import capture_route_write

KEY = b"\x01" * 32
NOW = datetime(2026, 11, 19, 10, 30, 0)
UNTIL = NOW + timedelta(seconds=47)
A = (54.7, 55.9, "SYNTHETIC_START")
B = (54.8, 56.0, "SYNTHETIC_DEST")
ONE = (54.71, 55.91, "SYNTHETIC_STOP_ONE", False)
TWO = (54.72, 55.92, "SYNTHETIC_STOP_TWO", True)
ROUTE = (A, (ONE, TWO), B)
TERMS = QuotedTerms(13001, 11000, 12001, 14101)


def _source(**changes):
    values = dict(id=21, passenger_id=41, driver_id=51, status=InstantOrderStatus.accepted,
                  to_lat=B[0], to_lng=B[1], to_text=B[2], waypoints_json="[]",
                  ride_price=110, price_estimate=122, promo_discount_kop=300,
                  created_at=NOW, accepted_at=NOW, destination_changes=1)
    return capture_route_write(InstantOrder(**(values | changes)))


def _bind(**changes):
    inputs = dict(key=KEY, owner_id=41, order_id=21, source=_source(), route=ROUTE,
                  terms=TERMS, issued_at=NOW, valid_until=UNTIL)
    return bind_quoted_terms(**(inputs | changes))


def _match(agreement, **changes):
    inputs = dict(key=KEY, owner_id=41, order_id=21, source=_source(), route=ROUTE,
                  terms=TERMS, now=NOW)
    return matches_current_terms(agreement=agreement, **(inputs | changes))


def test_exact_bind_and_match_is_private_immutable_and_non_consuming():
    agreement = _bind()
    assert _match(agreement)
    assert _match(agreement)  # pure equality is not a durable replay decision
    assert agreement == _bind()
    assert agreement.issued_at == NOW and agreement.valid_until == UNTIL
    # No encoded snapshot, amount, actor ID, address or claim exposed by representation.
    assert len(agreement.binding) == 32 and len(repr(agreement)) < 100
    for text in ("SYNTHETIC", "13001", "12001", agreement.binding.hex()):
        assert text not in repr(agreement)
    with pytest.raises(FrozenInstanceError):
        agreement.valid_until = UNTIL + timedelta(seconds=1)


@pytest.mark.parametrize("values", [
    dict(owner_id=42, source=_source(passenger_id=42)),
    dict(order_id=22, source=_source(id=22)),
    dict(source=_source(driver_id=52)),
    dict(source=_source(status=InstantOrderStatus.onboard)),
    dict(source=_source(destination_changes=2)),
    dict(source=_source(promo_discount_kop=301)),
    dict(source=_source(waypoints_json='[{"text":"changed"}]')),
    dict(route=(A, (TWO, ONE), B)),
    dict(route=(A, (ONE, (TWO[0], TWO[1], TWO[2], False)), B)),
    dict(route=(A, ((54.71001, *ONE[1:]), TWO), B)),
    dict(route=(A, (ONE, TWO), (B[0], B[1], "other label"))),
    dict(route=((A[0], A[1], "other pickup"), (ONE, TWO), B)),
    dict(key=b"\x02" * 32),
])
def test_current_binding_refuses_exact_mismatch(values):
    assert not _match(_bind(), **values)


@pytest.mark.parametrize("field", ("ride_price_kop", "previous_ride_price_kop",
                                     "payable_estimate_kop", "previous_payable_estimate_kop"))
def test_each_displayed_kopeck_term_is_exact(field):
    delta = replace(TERMS, **{field: getattr(TERMS, field) + 1})
    assert not _match(_bind(), terms=delta)


@pytest.mark.parametrize("value", (True, 1.0, -1, "13001", 2**63, None))
def test_invalid_money_is_never_coerced(value):
    bad = replace(TERMS, ride_price_kop=value)
    with pytest.raises(ValueError, match="integer kopecks"):
        _bind(terms=bad)
    assert not _match(_bind(), terms=bad)


@pytest.mark.parametrize("candidate", [
    (A, [ONE], B), (A, ((float("nan"), *ONE[1:]),), B),
    (A, ((ONE[0], float("inf"), *ONE[2:]),), B),
    (A, ((90.1, *ONE[1:]),), B), (A, ((ONE[0], -180.1, *ONE[2:]),), B),
    (A, ((True, *ONE[1:]),), B), (A, ((ONE[0], ONE[1], "X" * 201, False),), B),
    (A, ((ONE[0], ONE[1], ONE[2], 1),), B), (A, (ONE,), (B[0], B[1])),
])
def test_invalid_route_shape_or_point_fails_closed(candidate):
    with pytest.raises(ValueError):
        _bind(route=candidate)
    assert not _match(_bind(), route=candidate)


def test_authoritative_source_identity_and_shape_required_even_on_binding():
    for kwargs in ({"owner_id": 42}, {"order_id": True},
                   {"source": _source()[:-1]}, {"source": _source(to_lat=float("nan"))}):
        with pytest.raises(ValueError):
            _bind(**kwargs)
    assert not _match(_bind(), source=(21, 41))


def test_validity_boundary_no_reissue_extension_or_receipt_inference():
    agreement = _bind()
    assert not _match(agreement, now=NOW - timedelta(microseconds=1))
    assert not _match(agreement, now=UNTIL)
    assert _match(agreement, now=UNTIL - timedelta(microseconds=1))
    assert not _match(replace(agreement, valid_until=UNTIL + timedelta(seconds=1)), now=UNTIL)
    assert not _match(replace(agreement, issued_at=NOW - timedelta(seconds=1)))
    assert not _match(agreement, now=NOW.replace(tzinfo=timezone.utc))
    assert not _match(None)
    assert not _match(RouteAgreement(NOW, UNTIL, b"broken"))
    with pytest.raises(ValueError):
        _bind(valid_until=NOW)
    with pytest.raises(ValueError):
        _bind(issued_at=NOW.replace(tzinfo=timezone.utc))
