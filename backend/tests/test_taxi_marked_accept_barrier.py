"""The ordinary driver accept APIs must not take an unhandled capped offer."""
from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta
from threading import Event
from uuid import uuid4

import pytest
from sqlalchemy import select, update
from test_instant import _driver_online
from test_taxi_offer_card_accept_v2 import FIELD, _card, fake_redis, offer  # noqa: F401 - pytest fixtures
from test_taxi_route_write_races import _route

from app import instant_service as isv
from app.db import engine
from app.models import InstantOrder, InstantOrderStatus
from app.taxi_route_resume import _MARKERS

_ORDER = InstantOrder.__table__
_CODE = "taxi_route_resume_accept_unavailable"


def _state(oid):
    with engine.connect() as conn:
        return conn.execute(select(_ORDER).where(_ORDER.c.id == oid)).mappings().one()


def _mark(oid, marked):
    with engine.begin() as conn:
        assert conn.execute(update(_ORDER).where(_ORDER.c.id == oid).values(**marked)).rowcount == 1


def _send(client, oid, driver, path, token):
    body = {"json": {FIELD: token}} if path == "accept/v2" else {}
    return client.post(_route(oid, path), headers=driver["auth"], **body)


def _record_effects(monkeypatch):
    result = []
    for name in ("_cleanup_tried", "_notify_transition", "_notify_pickup_fee"):
        monkeypatch.setattr(isv, name, lambda *_, n=name: result.append(n))
    return result


@pytest.mark.parametrize("path", ["accept", "accept/v2"])
@pytest.mark.parametrize("marks", ["complete", *_MARKERS])
def test_ordinary_accept_rejects_any_mark_without_settlement_or_effects(client, request, monkeypatch, path, marks):
    driver, _, oid = request.getfixturevalue("offer")
    initial = _state(oid)
    token = _card(client, driver)[FIELD]
    retired = initial["offered_at"] - timedelta(seconds=1)
    values = {
        "route_resume_id": uuid4().hex,
        "route_resume_previous_driver_id": driver["id"] + 1000,
        "route_resume_retired_at": retired,
        "route_resume_deadline_at": retired + timedelta(seconds=120),
        "route_resume_ready_at": None if marks == "complete" else retired,
        "route_resume_claim_id": None if marks == "complete" else uuid4().hex,
        "route_resume_agreement_seal": None if marks == "complete" else b"\x66" * 32,
    }
    _mark(oid, values if marks == "complete" else {marks: values[marks]})
    before = _state(oid)
    effects = _record_effects(monkeypatch)
    monkeypatch.setattr(isv, "_settle_pickup_fee", lambda *args: effects.append("settle") or {})
    response = _send(client, oid, driver, path, token)
    assert response.status_code == 409, response.text
    assert response.json()["detail"]["code"] == _CODE
    assert _state(oid) == before and effects == []


@pytest.mark.parametrize("path", ["accept", "accept/v2"])
def test_mark_disposition_is_not_disclosed_to_another_driver(client, request, user_factory, path):
    driver, _, oid = request.getfixturevalue("offer")
    other = _driver_online(client, user_factory, "MarkedBarrierStranger")
    token = _card(client, driver)[FIELD]
    baseline = _send(client, oid, other, path, token)
    _mark(oid, {"route_resume_id": uuid4().hex})
    observed = _send(client, oid, other, path, token)
    assert baseline.status_code == observed.status_code == 403
    assert baseline.json() == observed.json()
    assert _CODE not in observed.text


@pytest.mark.parametrize("path", ["accept", "accept/v2"])
def test_sqlite_marker_committed_after_final_order_read_fences_actual_accept_sql(client, request, monkeypatch, path):
    if engine.dialect.name != "sqlite":
        pytest.skip("PostgreSQL serializes the competing UPDATE behind SELECT FOR UPDATE")
    driver, _, oid = request.getfixturevalue("offer")
    token = _card(client, driver)[FIELD]
    effects = _record_effects(monkeypatch)
    entered, released = Event(), Event()
    marker = uuid4().hex

    def pause_at_last_read(session, order, driver_id):
        assert driver_id == driver["id"] and order.id == oid
        assert order.status is InstantOrderStatus.offered
        assert all(getattr(order, f) is None for f in _MARKERS)
        entered.set()
        assert released.wait(8)
        # A non-empty real SQL projection proves that a lost accept cannot
        # persist pickup fees, even if a legacy calculation already ran.
        return {"pickup_fee_kop": 12300, "pickup_pending": False}

    monkeypatch.setattr(isv, "_settle_pickup_fee", pause_at_last_read)
    with ThreadPoolExecutor(max_workers=1) as pool:
        pending = pool.submit(_send, client, oid, driver, path, token)
        try:
            assert entered.wait(8)
            _mark(oid, {"route_resume_claim_id": marker})
            before = _state(oid)
            assert before["route_resume_claim_id"] == marker
        finally:
            released.set()
        response = pending.result(timeout=10)
    assert response.status_code == 409, response.text
    assert response.json()["detail"]["code"] == "taxi_offer_changed"
    assert _state(oid) == before and effects == []
