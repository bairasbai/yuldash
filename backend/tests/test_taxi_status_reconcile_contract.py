"""HTTP reconciliation contracts for delayed status hints, not client suppression.

GET is a current authorized snapshot and may lazily advance an expired offer.
Another transition may occur after its response. No delivery ordering, logout UI,
network fallback, lock-screen privacy or client reconciliation is implemented here.
"""
from copy import deepcopy
from datetime import timedelta

import pytest
from sqlmodel import Session

from app import instant_service as isv
from app.db import engine
from app.models import InstantOrder, InstantOrderStatus as S, UserRole
from app.timeutil import utcnow
from test_taxi_status_never_resurrects import _order, fake_redis  # noqa: F401
from test_taxi_accept_cleanup_generation import _cleanup_accept_orders  # noqa: F401

FROM_TEXT = "ул. 8 Марта, 12, кв. 9"
TO_TEXT = "ул. Гагарина, 5, кв. 9"


@pytest.fixture
def status_order(user_factory, monkeypatch, request):
    redis = request.getfixturevalue("fake_redis")
    passenger = user_factory("ReconcilePassenger")
    first = user_factory("ReconcileFirstDriver", role=UserRole.driver)
    second = user_factory("ReconcileSecondDriver", role=UserRole.driver)
    outsider = user_factory("ReconcileOutsider")
    oid = _order(passenger["id"], S.offered, search_revision=20,
                 current_offer_driver_id=first["id"], offer_token="a" * 32,
                 offer_expires_at=utcnow() + timedelta(minutes=10),
                 from_text=FROM_TEXT, to_text=TO_TEXT,
                 from_lat=52.591234, from_lng=58.317891,
                 to_lat=52.716123, to_lng=58.664789)
    status_queue, offer_transports = [], []
    def status_transport(session, user_id, title_ru, title_ba, body_ru, body_ba, **kwargs):
        status_queue.append((user_id, deepcopy(kwargs["data"])))
    def push_transport(session, user_id, title, body, **kwargs):
        offer_transports.append((user_id, deepcopy(kwargs.get("data", {}))))
    monkeypatch.setattr(isv, "push_bilingual", status_transport)
    monkeypatch.setattr(isv, "send_push", push_transport)
    monkeypatch.setattr("app.services.push_notification", lambda *args, **kwargs: None)
    monkeypatch.setattr(isv, "candidates", lambda *args, **kwargs: [second["id"]])
    yield oid, passenger, first, second, outsider, status_queue, offer_transports
    redis.delete(isv._tried_key(oid))


def _denied_without_order_payload(response, expected_status):
    assert response.status_code == expected_status, response.text
    body = response.json()
    assert set(body) == {"detail"}
    forbidden = {"from_text", "to_text", "from_lat", "from_lng", "to_lat", "to_lng",
                 "offer_token", "offer_expires_at", "driver_phone", "passenger_phone",
                 "phone", "driver_id", "passenger_id", "comment", "entrance"}
    def keys(value):
        if isinstance(value, dict):
            return set(value) | set().union(*(keys(v) for v in value.values()))
        if isinstance(value, list):
            return set().union(*(keys(v) for v in value))
        return set()
    assert not forbidden.intersection(keys(body))
    assert FROM_TEXT not in response.text and TO_TEXT not in response.text
    assert "52.591234" not in response.text and "58.664789" not in response.text


def test_delayed_accepted_hint_is_reconciled_by_current_authorized_http(client, status_order):
    oid, passenger, first, second, outsider, queued, offers = status_order
    with Session(engine) as session:
        accepted = isv.transition(session, oid, isv.Actor.driver, S.accepted, first["id"], idempotent=False)
        assert accepted.status == S.accepted and accepted.search_revision == 20
    assert queued == [(passenger["id"], {"type": "instant_status", "order_id": str(oid), "status": "accepted"})]
    with Session(engine) as session:
        reassigned = isv.cancel_order(session, oid, isv.Actor.driver, first["id"], "car_problem")
        assert reassigned.status == S.offered and reassigned.search_revision == 21
        assert reassigned.current_offer_driver_id == second["id"]
        assert reassigned.offer_expires_at > utcnow()
    delivered_old = queued.pop(0)[1]
    assert delivered_old["status"] == "accepted", "transport delay is intentionally still observable"
    response = client.get(f"/instant/orders/{oid}", headers=passenger["auth"])
    assert response.status_code == 200, response.text
    payload = response.json()
    with Session(engine) as observer:
        current = observer.get(InstantOrder, oid)  # Compare after potentially mutating GET.
        assert current.status == S.offered and current.current_offer_driver_id == second["id"]
        assert payload["status"] == current.status.value != delivered_old["status"]
        assert payload["driver_id"] is None and payload["offer_token"] is None
        current_token = current.offer_token
    assert "current_offer_driver_id" not in payload, "passenger API does not expose unaccepted candidate identity"
    assert payload["from_text"] == FROM_TEXT and payload["to_text"] == TO_TEXT
    _denied_without_order_payload(client.get(f"/instant/orders/{oid}", headers=first["auth"]), 403)
    _denied_without_order_payload(client.get(f"/instant/orders/{oid}", headers=outsider["auth"]), 403)
    candidate = client.get(f"/instant/orders/{oid}", headers=second["auth"])
    assert candidate.status_code == 200, candidate.text
    data = candidate.json()
    assert data["status"] == "offered" and data["offer_token"] == current_token
    assert data["driver_phone"] == "" and data["passenger_phone"] == ""
    assert data["from_text"] == FROM_TEXT, "existing offered API pickup contract is intentionally unchanged"
    assert data["to_text"] == "ул. Гагарина"
    assert (data["from_lat"], data["from_lng"], data["to_lat"], data["to_lng"]) == (52.59, 58.32, 52.72, 58.66)
    with Session(engine) as observer:
        current = observer.get(InstantOrder, oid)
        assert current.status.value == data["status"] and current.offer_token == data["offer_token"]
        assert current.current_offer_driver_id == second["id"]
    assert any(recipient == second["id"] and data.get("type") == "instant_offer" for recipient, data in offers)


def test_search_revision_is_not_a_sequence_for_normal_ride_statuses(client, status_order):
    oid, passenger, first, second, outsider, queued, offers = status_order
    observations = []
    for status in (S.accepted, S.arriving, S.onboard):
        with Session(engine) as session:
            result = isv.transition(session, oid, isv.Actor.driver, status, first["id"], idempotent=False)
            assert result.status == status and result.search_revision == 20
        response = client.get(f"/instant/orders/{oid}", headers=passenger["auth"])
        assert response.status_code == 200, response.text
        with Session(engine) as observer:
            current = observer.get(InstantOrder, oid)
            assert response.json()["status"] == current.status.value == status.value
            assert response.json()["driver_id"] == current.driver_id == first["id"]
            observations.append((current.status, current.search_revision))
    assert observations == [(S.accepted, 20), (S.arriving, 20), (S.onboard, 20)]
    assert [data["status"] for recipient, data in queued] == ["accepted", "arriving", "onboard"]
    print("CONTRACT LIMIT: search_revision is unchanged across accepted/arriving/onboard")


def test_unauthenticated_and_missing_order_return_errors_without_order_data(client, status_order):
    oid, passenger, first, second, outsider, queued, offers = status_order
    _denied_without_order_payload(client.get(f"/instant/orders/{oid}"), 401)
    _denied_without_order_payload(client.get(f"/instant/orders/{oid + 1000000}", headers=passenger["auth"]), 404)


def test_real_get_lazy_timeout_rechecks_old_candidate_authorization(client, status_order):
    oid, passenger, first, second, outsider, queued, offers = status_order
    with Session(engine) as setup:
        order = setup.get(InstantOrder, oid)
        order.offer_expires_at = utcnow() - timedelta(seconds=1)
        setup.add(order)
        setup.commit()
    # D1 passes _order_for_view initially; real reconcile advances to D2, then
    # real order_payload must check participation again (no actor_authorized bypass).
    response = client.get(f"/instant/orders/{oid}", headers=first["auth"])
    _denied_without_order_payload(response, 403)
    with Session(engine) as observer:
        current = observer.get(InstantOrder, oid)
        assert current.status == S.offered and current.current_offer_driver_id == second["id"]
        assert current.search_revision == 21 and current.offer_token != "a" * 32
        assert current.offer_expires_at > utcnow()
        new_token = current.offer_token
    candidate = client.get(f"/instant/orders/{oid}", headers=second["auth"])
    assert candidate.status_code == 200 and candidate.json()["offer_token"] == new_token
    assert candidate.json()["driver_phone"] == "" and candidate.json()["passenger_phone"] == ""
    assert candidate.json()["to_text"] == "ул. Гагарина"
    assert candidate.json()["from_text"] == FROM_TEXT
