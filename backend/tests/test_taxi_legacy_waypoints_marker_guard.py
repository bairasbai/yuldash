"""Ordinary waypoint mutation only; do not enable capped route edits."""
from types import SimpleNamespace

import pytest
from sqlalchemy import delete, event, select, update

from app import instant_service as isv, promo_ride, taxi_route_resume as resume
from app.compensation import COMPENSATION_FIELDS
from app.db import engine
from app.models import InstantOrder, InstantOrderStatus as S, UserRole
from test_instant import _offered_order, fake_redis  # noqa: F401 - actual fixture
from test_taxi_route_write_races import POINTS, _price, _route
from test_waypoints import _make_order

_TABLE = InstantOrder.__table__
_C = _TABLE.c
_CHANGED = "taxi_route_changed"


def _state(oid):
    with engine.connect() as c:
        return dict(c.execute(select(_TABLE).where(_C.id == oid)).one()._mapping)


def _install(oid, **values):
    with engine.begin() as c:
        assert c.execute(update(_TABLE).where(_C.id == oid).values(**values)).rowcount == 1


@pytest.fixture
def ordinary(request, client, user_factory):
    if request.param == "offered":
        redis = request.getfixturevalue("fake_redis")
        driver, passenger, response = _offered_order(client, user_factory, redis, "LegacyWpDriver", "LegacyWpOwner")
        oid = response["id"]
    else:
        passenger = user_factory("LegacyWpAssignedOwner")
        driver = user_factory("LegacyWpAssignedDriver", role=UserRole.driver)
        oid = _make_order(passenger["id"], driver["id"], ride_price=225,
                          pickup_fee_kop=7000, promo_discount_kop=10000)
    try:
        yield driver, passenger, oid
    finally:
        with engine.begin() as c:
            c.execute(delete(_TABLE).where(_C.id == oid))


def _post(client, by, oid):
    return client.post(_route(oid, "waypoints"), headers=by["auth"], json={"waypoints": POINTS})


@pytest.mark.parametrize("ordinary", ("offered",), indirect=True)
@pytest.mark.parametrize("field", (*resume._MARKERS, "complete"))
def test_visible_partial_or_full_marker_refuses_before_route_and_keeps_owner_privacy(client, ordinary, field, monkeypatch):
    driver, passenger, oid = ordinary
    at = _state(oid)["offered_at"]
    fields = {"route_resume_id": "a"*32, "route_resume_claim_id": "b"*32,
              "route_resume_previous_driver_id": driver["id"], resume._SEAL: b"x"*32,
              "route_resume_retired_at": at, "route_resume_ready_at": at, "route_resume_deadline_at": at}
    _install(oid, **(fields if field == "complete" else {field: fields[field]}))
    before = _state(oid)
    quotes, notices = [], []

    def price(session, order, destination, **kwargs):
        quotes.append(True)
        return _price(130, order)

    monkeypatch.setattr(isv, "destination_quote", price)
    monkeypatch.setattr(isv, "notify_driver_destination", lambda *a, **kw: notices.append(True))
    outsider = _post(client, driver, oid)
    assert outsider.status_code == 404 and "code" not in outsider.json()["detail"]
    forbidden = _post(client, passenger, oid)
    assert forbidden.status_code == 409 and forbidden.json()["detail"]["code"] == _CHANGED
    assert "route_resume" not in forbidden.text and "a"*32 not in forbidden.text
    assert _state(oid) == before and quotes == notices == []


@pytest.mark.skipif(engine.dialect.name != "sqlite", reason="actual independent SQLite rival at final DML")
@pytest.mark.parametrize("ordinary", ("offered", "onboard"), indirect=True)
def test_new_seal_at_actual_waypoint_dml_wins_preserves_all_state_and_sends_no_notice(client, ordinary, monkeypatch):
    _, passenger, oid = ordinary
    before = _state(oid)
    quotes, notices, witness = [], [], []

    def price(session, order, destination, **kwargs):
        quotes.append(True)
        return _price(130, order)

    def competitor(conn, cursor, statement, parameters, context, executemany):
        if statement.startswith("UPDATE instantorder SET") and not witness:
            witness.append(True)
            assert quotes == [True]
            with engine.begin() as rival:
                assert rival.connection.driver_connection is not conn.connection.driver_connection
                count = rival.execute(update(_TABLE).where(_C.id == oid).values({resume._SEAL: b"x"*32})).rowcount
                assert count == 1

    monkeypatch.setattr(isv, "destination_quote", price)
    monkeypatch.setattr(isv, "notify_driver_destination", lambda *a, **kw: notices.append(True))
    event.listen(engine, "before_cursor_execute", competitor)
    try:
        response = _post(client, passenger, oid)
    finally:
        event.remove(engine, "before_cursor_execute", competitor)
    assert response.status_code == 409 and response.json()["detail"]["code"] == _CHANGED
    assert witness == [True] and notices == []
    assert _state(oid) == before | {resume._SEAL: b"x"*32}


@pytest.mark.parametrize("ordinary", ("offered", "onboard"), indirect=True)
def test_unmarked_ordinary_waypoints_keeps_legacy_status_driver_economics_and_notice(client, ordinary, monkeypatch):
    driver, passenger, oid = ordinary
    before = _state(oid)
    notices = []
    monkeypatch.setattr(isv, "destination_quote", lambda s, o, d, **kw: _price(130, o))

    def notice(session, order, quote):
        assert _state(oid)["ride_price"] == quote["price"] == 130
        notices.append(order.driver_id)

    monkeypatch.setattr(isv, "notify_driver_destination", notice)
    response = _post(client, passenger, oid)
    assert response.status_code == 200 and response.json()["applied"]
    after = _state(oid)
    fields = ("status", "driver_id", "current_offer_driver_id", "offer_expires_at", "offered_at",
              "search_round", "from_lat", "from_lng", "to_lat", "to_lng", "paid", *COMPENSATION_FIELDS)
    assert {f: after[f] for f in fields} == {f: before[f] for f in fields}
    assert isv.parse_waypoints(after["waypoints_json"]) == [dict(POINTS[0], done=False)]
    assert after["ride_price"] == 130
    assert after["price_estimate"] == 130 + isv.order_compensation_rub(SimpleNamespace(**before))
    assert after["promo_discount_kop"] == promo_ride.cap_for_price(before["promo_discount_kop"], 130)
    assert notices == ([driver["id"]] if before["status"] is S.onboard else [])
