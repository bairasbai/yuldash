"""Real route handlers and database transactions, with deterministic route quotes."""
from concurrent.futures import ThreadPoolExecutor
from threading import Event

import pytest
from sqlmodel import Session

from app import instant_service as isv, promo_ride
from app.db import engine
from app.models import InstantOrder, UserRole
from app.timeutil import utcnow
from test_waypoints import STOP_A, _make_order

DEST2 = (54.765, 55.995)
POINTS = [{"lat": STOP_A[0], "lng": STOP_A[1], "text": "Аптека"}]


@pytest.fixture
def ride(client, user_factory, monkeypatch):
    pax = user_factory("Tf04CasOwner")
    drv = user_factory("Tf04CasDriver", role=UserRole.driver)
    oid = _make_order(pax["id"], drv["id"], ride_price=225,
                      pickup_fee_kop=7500, promo_discount_kop=18000)
    events = []
    monkeypatch.setattr("app.services.push_notification", lambda *a, **kw: events.append(a[1]))
    return pax, drv, oid, events


def _route(oid, suffix):
    return f"/instant/orders/{oid}/{suffix}"


def _destination():
    return {"to_lat": DEST2[0], "to_lng": DEST2[1], "to_text": "Новый адрес"}


def _price(price, order, driver_required=False):
    return {"ok": True, "price": price, "base_price": price,
            "old_price": isv.order_ride_price(order), "distance_km": 7.1,
            "eta_min": 16.0, "tariff_id": None, "needs_driver_ok": driver_required}


@pytest.mark.parametrize("loser", ["destination", "waypoints", "accept", "offer"])
def test_stale_repricing_never_overwrites_a_concurrently_committed_route(
        client, monkeypatch, ride, loser):
    pax, drv, oid, events = ride
    if loser == "accept":
        with Session(engine) as s:
            o = s.get(InstantOrder, oid)
            o.pending_to_lat, o.pending_to_lng = DEST2
            o.pending_to_text, o.pending_price = "Новый адрес", 400
            o.pending_asked_at = utcnow()
            s.commit()
    admitted, release = Event(), Event()
    observed = []

    def price(session, order, destination, now=None, waypoints_override=None):
        is_destination = tuple(destination) == DEST2
        stale = is_destination if loser != "waypoints" else not is_destination
        if stale:
            observed.append(isv.parse_waypoints(order.waypoints_json))
            admitted.set()
            assert release.wait(8), "race fixture could not release the first request"
        return _price(400 if is_destination else 130, order,
                      driver_required=(is_destination and loser == "offer"))

    monkeypatch.setattr(isv, "destination_quote", price)
    destination_request = (_route(oid, "destination"), pax["auth"], _destination())
    waypoints_request = (_route(oid, "waypoints"), pax["auth"], {"waypoints": POINTS})
    accept_request = (_route(oid, "destination/accept"), drv["auth"], {})
    first = {"destination": destination_request, "waypoints": waypoints_request,
             "accept": accept_request, "offer": destination_request}[loser]
    second = destination_request if loser == "waypoints" else waypoints_request
    with ThreadPoolExecutor(max_workers=1) as pool:
        early = pool.submit(client.post, first[0], headers=first[1], json=first[2])
        try:
            assert admitted.wait(8), "first route did not capture its stale source"
            winner = client.post(second[0], headers=second[1], json=second[2])
            assert winner.status_code == 200, winner.text
        finally:
            release.set()
        result = early.result(timeout=8)
    assert observed == [[]]
    assert result.status_code == 409, result.text
    assert result.json()["detail"]["code"] == "taxi_route_changed"
    assert events == [drv["id"]], "stale request sent a notification"

    with Session(engine) as s:
        o = s.get(InstantOrder, oid)
        assert (o.to_lat, o.to_lng) == (DEST2 if loser == "waypoints" else (54.76, 55.99))
        assert o.ride_price == (400 if loser == "waypoints" else 130)
        assert o.price_estimate == o.ride_price + 75
        assert o.promo_discount_kop == promo_ride.cap_for_price(18000, o.ride_price)
        assert isv.parse_waypoints(o.waypoints_json) == (
            [] if loser == "waypoints" else [{**POINTS[0], "done": False}])
        assert o.destination_ack_at is None
        assert o.pending_to_lat == (DEST2[0] if loser == "accept" else None)


def test_pending_accept_changes_price_and_ack_together(client, monkeypatch, ride):
    pax, drv, oid, events = ride
    monkeypatch.setattr(isv, "destination_quote",
                        lambda session, order, destination: _price(130, order, driver_required=True))
    proposal = client.post(_route(oid, "destination"), headers=pax["auth"], json=_destination())
    assert proposal.status_code == 200 and proposal.json()["waiting_driver"] is True
    result = client.post(_route(oid, "destination/accept"), headers=drv["auth"])
    assert result.status_code == 200, result.text
    with Session(engine) as s:
        o = s.get(InstantOrder, oid)
        assert (o.to_lat, o.to_lng) == DEST2 and o.destination_ack_at is not None
        assert o.pending_to_lat is None and o.pending_price is None
        assert o.price_estimate == 205 and o.promo_discount_kop == promo_ride.cap_for_price(18000, 130)
    assert events == [drv["id"]]


def test_route_price_and_promo_rollback_together_before_commit(client, monkeypatch, ride):
    from sqlalchemy import event
    from sqlmodel import select

    pax, _, oid, pushes = ride
    monkeypatch.setattr(isv, "destination_quote",
                        lambda *args, **kwargs: _price(130, args[1]))
    observed = []
    fields = select(InstantOrder.waypoints_json, InstantOrder.price_estimate,
                    InstantOrder.promo_discount_kop).where(InstantOrder.id == oid)

    class AbortedWrite(RuntimeError):
        pass

    def stop_commit(session):
        pending = tuple(session.exec(fields).one())
        if pending[1] != 205:
            return
        with Session(engine) as outsider:
            persisted = tuple(outsider.exec(fields).one())
        observed.append((pending, persisted))
        raise AbortedWrite("synthetic rollback after route UPDATE")

    event.listen(Session, "before_commit", stop_commit)
    try:
        with pytest.raises(AbortedWrite, match="synthetic rollback"):
            client.post(_route(oid, "waypoints"), headers=pax["auth"], json={"waypoints": POINTS})
    finally:
        event.remove(Session, "before_commit", stop_commit)
    assert len(observed) == 1
    pending, persisted = observed[0]
    assert isv.parse_waypoints(pending[0]) == [{**POINTS[0], "done": False}]
    assert pending[1:] == (205, promo_ride.cap_for_price(18000, 130))
    assert persisted == ("", 300, 18000)
    with Session(engine) as outsider:
        assert tuple(outsider.exec(fields).one()) == persisted
    assert pushes == []


def test_waypoint_write_matches_latest_offer_recipient(client, monkeypatch, ride):
    from app.models import InstantOrderStatus as S

    pax, drv, oid, pushes = ride
    with Session(engine) as s:
        o = s.get(InstantOrder, oid)
        o.status = S.offered
        o.driver_id = None
        o.current_offer_driver_id = drv["id"]
        s.commit()

    def reassign_in_price(session, order, destination, *, waypoints_override):
        with Session(engine) as writer:
            o = writer.get(InstantOrder, oid)
            o.current_offer_driver_id = None
            writer.commit()
        return _price(170, order)

    monkeypatch.setattr(isv, "destination_quote", reassign_in_price)
    result = client.post(_route(oid, "waypoints"), headers=pax["auth"], json={"waypoints": POINTS})
    assert result.status_code == 409
    assert result.json()["detail"]["code"] == "taxi_route_changed"
    with Session(engine) as s:
        o = s.get(InstantOrder, oid)
        assert o.status == S.offered
        assert o.current_offer_driver_id is None
        assert o.waypoints_json == "" and o.price_estimate == 300
    assert pushes == []
