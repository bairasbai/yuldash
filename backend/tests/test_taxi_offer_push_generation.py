"""Compatible offer metadata via real offer construction and transport-only spies."""
from datetime import datetime, timedelta, timezone

import pytest
from sqlmodel import Session

from app import instant_service as isv
from app.db import engine
from test_taxi_offer_dispatch_snapshot import _prepare_offered_snapshot
from app.models import InstantOrder, InstantOrderStatus as S, UserRole
from test_taxi_status_never_resurrects import _order, fake_redis  # noqa: F401
from test_taxi_accept_cleanup_generation import _cleanup_accept_orders  # noqa: F401


def _utc_z(value):
    if value.tzinfo is None:
        value = value.replace(tzinfo=timezone.utc)
    return value.astimezone(timezone.utc).isoformat(timespec="microseconds").replace("+00:00", "Z")


@pytest.mark.usefixtures("fake_redis")
def test_real_matcher_push_contains_committed_offer_token_and_absolute_expiry(client, user_factory, monkeypatch):
    passenger = user_factory("PushGenerationPassenger")
    driver = user_factory("PushGenerationDriver", role=UserRole.driver)
    oid = _order(passenger["id"], S.searching, search_revision=1,
                 from_text="ул. Ленина, 12, кв. 43", to_text="ул. Гагарина, 5к2")
    sent = []
    def transport(session, user_id, title, body, **kwargs):
        with Session(engine) as observer:
            committed = observer.get(InstantOrder, oid)
            assert committed.status == S.offered
            sent.append((user_id, title, body, kwargs, committed.offer_token, committed.offer_expires_at))
    monkeypatch.setattr(isv, "send_push", transport)
    monkeypatch.setattr(isv, "candidates", lambda *args, **kwargs: [driver["id"]])
    try:
        with Session(engine) as session:
            result = isv.try_offer_next(session, session.get(InstantOrder, oid))
            assert result.status == S.offered and result.current_offer_driver_id == driver["id"]
        assert len(sent) == 1
        recipient, title, body, kwargs, token, expiry = sent[0]
        assert recipient == driver["id"] and kwargs["data_only"] is True
        payload = kwargs["data"]
        assert payload.get("offer_token") == token, "push must carry the committed offer token"
        assert payload.get("offer_expires_at") == _utc_z(expiry), "push must carry the stored absolute deadline"
        assert all(isinstance(value, str) for value in payload.values())
        assert payload["order_id"] == str(oid) and payload["type"] == "instant_offer"
        assert payload["ttl_sec"] == str(isv.settings.instant_offer_ttl_sec)
        assert payload["from"] == isv.pickup_street_only("ул. Ленина, 12, кв. 43")
        assert payload["to"] == isv.street_only("ул. Гагарина, 5к2")
        assert payload["from"] == "ул. Ленина"
        assert "12" not in payload["from"] and "43" not in payload["from"]
        assert f"{payload['from']} → {payload['to']}" in body
        assert "5к2" not in payload["to"] and "5к2" not in body
        assert "passenger_id" not in payload and "phone" not in payload
        assert int(payload["gross_kop"]) == int(payload["price"]) * 100
        assert int(payload["net_kop"]) == max(int(payload["gross_kop"]) - int(payload["fee_kop"]), 0)
    finally:
        isv._redis().delete(isv._tried_key(oid))


@pytest.fixture
def direct_offer(user_factory, monkeypatch):
    monkeypatch.setattr(isv, "utcnow", lambda: datetime(2025, 11, 18, 12, 0, 0))
    passenger = user_factory("DirectOfferMetadataPassenger")
    driver = user_factory("DirectOfferMetadataDriver", role=UserRole.driver)
    oid = _order(passenger["id"], S.offered, search_revision=1,
                 current_offer_driver_id=driver["id"], offer_token="a" * 32,
                 offer_expires_at=datetime(2030, 1, 2, 3, 4, 5, 123456))
    return oid, driver


def _capture_transport(monkeypatch):
    sent = []
    def transport(session, user_id, title, body, **kwargs):
        sent.append((user_id, dict(kwargs["data"]), kwargs["data_only"]))
    monkeypatch.setattr(isv, "send_push", transport)
    return sent


@pytest.mark.parametrize("token,expiry", [
    (None, None), (None, datetime(2030, 1, 2, 3, 4, 5, 123456)), ("a" * 32, None),
])
def test_legacy_null_offer_fields_are_omitted_independently(client, direct_offer, monkeypatch, token, expiry):
    oid, driver = direct_offer
    sent = _capture_transport(monkeypatch)
    with Session(engine) as session:
        order = session.get(InstantOrder, oid)
        session.expunge(order)
        order.offer_token, order.offer_expires_at = token, expiry
        isv._push_offer(session, _prepare_offered_snapshot(session, order, driver["id"]))
    assert len(sent) == 1
    payload = sent[0][1]
    assert ("offer_token" in payload) == (token is not None)
    assert ("offer_expires_at" in payload) == (expiry is not None)
    if token is not None:
        assert payload["offer_token"] == token
    if expiry is not None:
        assert payload["offer_expires_at"] == _utc_z(expiry)
    assert "None" not in payload.values()
    assert all(isinstance(value, str) for value in payload.values())


@pytest.mark.parametrize("expiry,expected", [
    (datetime(2026, 1, 2, 3, 4, 5, 123456), "2026-01-02T03:04:05.123456Z"),
    (datetime(2026, 1, 2, 3, 4, 5, 123456, tzinfo=timezone(timedelta(hours=5))), "2026-01-01T22:04:05.123456Z"),
    (datetime(2026, 1, 2, 3, 4, 5, tzinfo=timezone(timedelta(hours=-3, minutes=-30))), "2026-01-02T06:34:05.000000Z"),
])
def test_offer_deadline_is_absolute_utc_with_microseconds(client, direct_offer, monkeypatch, expiry, expected):
    oid, driver = direct_offer
    sent = _capture_transport(monkeypatch)
    monkeypatch.setattr(isv, "utcnow", lambda: datetime(2025, 11, 18, 12, 0, 0))
    with Session(engine) as session:
        order = session.get(InstantOrder, oid)
        session.expunge(order)
        # Persist canonical UTC (SQLite drops timezone information); dispatch
        # consumes only the real database factory snapshot, never a replacement.
        order.offer_expires_at = (expiry.astimezone(timezone.utc).replace(tzinfo=None)
                                 if expiry.tzinfo is not None else expiry)
        snapshot = _prepare_offered_snapshot(session, order, driver["id"])
        isv._push_offer(session, snapshot)
    assert sent[0][1]["offer_expires_at"] == expected
    assert sent[0][1]["ttl_sec"] == str(isv.settings.instant_offer_ttl_sec), "legacy TTL field remains unchanged"


@pytest.mark.parametrize("initial_null", [False, True])
def test_offer_generation_is_captured_before_passenger_helper_mutates_order(client, direct_offer, monkeypatch, initial_null):
    oid, driver = direct_offer
    sent = _capture_transport(monkeypatch)
    original_stats = isv.passenger_stats
    with Session(engine) as session:
        order = session.get(InstantOrder, oid)
        session.expunge(order)
        if initial_null:
            order.offer_token = None
            order.offer_expires_at = None
        initial_token, initial_expiry = order.offer_token, order.offer_expires_at
        snapshot = _prepare_offered_snapshot(session, order, driver["id"])
        session.expunge(order)  # Pure caller-object mutation, distinct from committed DB replacement.
        calls = []
        def stats_with_concurrent_refresh(*args, **kwargs):
            calls.append(True)
            order.offer_token = "b" * 32
            order.offer_expires_at = datetime(2030, 1, 1, 0, 0, 0, 987654)
            return original_stats(*args, **kwargs)
        monkeypatch.setattr(isv, "passenger_stats", stats_with_concurrent_refresh)
        isv._push_offer(session, snapshot)
        assert calls == [True]
        assert order.offer_token == "b" * 32
    payload = sent[0][1]
    if initial_null:
        assert "offer_token" not in payload and "offer_expires_at" not in payload
    else:
        assert payload["offer_token"] == initial_token
        assert payload["offer_expires_at"] == _utc_z(initial_expiry)


@pytest.mark.usefixtures("fake_redis")
def test_delayed_transport_retains_old_metadata_without_claiming_client_suppression(client, user_factory, monkeypatch):
    passenger = user_factory("DelayedOfferMetadataPassenger")
    driver = user_factory("DelayedOfferMetadataDriver", role=UserRole.driver)
    oid = _order(passenger["id"], S.searching, search_revision=1)
    queued = _capture_transport(monkeypatch)
    now = [datetime(2026, 11, 18, 10, 0, 0, 123456)]
    monkeypatch.setattr(isv, "utcnow", lambda: now[0])
    monkeypatch.setattr(isv, "candidates", lambda *args, **kwargs: [driver["id"]])
    try:
        with Session(engine) as session:
            first = isv.try_offer_next(session, session.get(InstantOrder, oid))
            first_token, first_expiry = first.offer_token, first.offer_expires_at
        now[0] += timedelta(minutes=2)
        with Session(engine) as session:
            newer = isv.advance_after_no_accept(session, session.get(InstantOrder, oid))
            assert newer.status == S.offered
            assert newer.offer_token != first_token and newer.offer_expires_at > first_expiry
            latest_token = newer.offer_token
        assert len(queued) == 2
        delivered_old = queued.pop(0)
        assert delivered_old[1]["offer_token"] == first_token
        assert delivered_old[1]["offer_expires_at"] == _utc_z(first_expiry)
        assert queued[0][1]["offer_token"] == latest_token
        assert datetime.fromisoformat(delivered_old[1]["offer_expires_at"].replace("Z", "+00:00")) < now[0].replace(tzinfo=timezone.utc)
        # Only metadata provenance is proved. No mobile/UI validation is executed.
    finally:
        isv._redis().delete(isv._tried_key(oid))
