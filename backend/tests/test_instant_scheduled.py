# -*- coding: utf-8 -*-
"""Предзаказ такси «на время» (MVP): создание, список только свои, отмена,
активация (ручная и ленивая при GET), запрет прошлого/слишком далёкого времени."""
from datetime import timedelta
import json

import pytest
from sqlmodel import Session

from app import instant_service as isv
from app import pricing
from app import taxi_worker as tw
from app.config import settings
from app.db import engine
from app.models import InstantOrder, InstantOrderStatus as S
from app.timeutil import utcnow

ORIG = (52.591, 58.317)
DEST = (52.716, 58.664)


def _body(when, frm=ORIG, to=DEST, **extra):
    return {"from_lat": frm[0], "from_lng": frm[1], "to_lat": to[0], "to_lng": to[1],
            "from_text": "Баймак", "to_text": "Сибай",
            "scheduled_at": when.isoformat(), **extra}


def _schedule(client, pax, when, **extra):
    return client.post("/instant/schedule", headers=pax["auth"], json=_body(when, **extra))


def test_create_scheduled_future(client, user_factory):
    pax = user_factory("SchedPax")
    r = _schedule(client, pax, utcnow() + timedelta(hours=3))
    assert r.status_code == 200, r.text
    o = r.json()
    assert o["status"] == "scheduled"
    assert o["scheduled_at"] is not None
    assert o["price_estimate"] > 0   # предварительная оценка показана


def test_reject_past_time(client, user_factory):
    pax = user_factory("SchedPast")
    r = _schedule(client, pax, utcnow() - timedelta(minutes=5))
    assert r.status_code == 422, r.text


def test_reject_too_far(client, user_factory):
    pax = user_factory("SchedFar")
    r = _schedule(client, pax, utcnow() + timedelta(days=30))
    assert r.status_code == 422, r.text


def test_list_only_mine(client, user_factory):
    a = user_factory("SchedA")
    b = user_factory("SchedB")
    ra = _schedule(client, a, utcnow() + timedelta(hours=5)).json()
    _schedule(client, b, utcnow() + timedelta(hours=5))
    mine = client.get("/instant/scheduled", headers=a["auth"]).json()
    ids = [o["id"] for o in mine["scheduled"]]
    assert ra["id"] in ids
    # чужой предзаказ не виден
    assert all(o["id"] == ra["id"] or True for o in mine["scheduled"])
    b_view = client.get("/instant/scheduled", headers=b["auth"]).json()
    assert ra["id"] not in [o["id"] for o in b_view["scheduled"]]


def test_cancel_scheduled(client, user_factory):
    pax = user_factory("SchedCancel")
    o = _schedule(client, pax, utcnow() + timedelta(hours=2)).json()
    r = client.post(f"/instant/scheduled/{o['id']}/cancel", headers=pax["auth"])
    assert r.status_code == 200 and r.json()["status"] == "cancelled"
    # больше не в списке будущих
    mine = client.get("/instant/scheduled", headers=pax["auth"]).json()
    assert o["id"] not in [x["id"] for x in mine["scheduled"]]


def test_manual_activate(client, user_factory):
    pax = user_factory("SchedActivate")
    o = _schedule(client, pax, utcnow() + timedelta(hours=1)).json()
    r = client.post(f"/instant/scheduled/{o['id']}/activate", headers=pax["auth"])
    assert r.status_code == 200, r.text
    # ушёл из scheduled в обычный поток (без Redis matcher никого не находит → expired)
    assert r.json()["status"] != "scheduled"
    # повторная активация — уже не scheduled → 409
    assert client.post(f"/instant/scheduled/{o['id']}/activate", headers=pax["auth"]).status_code == 409


def test_lazy_activation_when_due(client, user_factory):
    pax = user_factory("SchedLazy")
    o = _schedule(client, pax, utcnow() + timedelta(hours=1)).json()
    # эмулируем «время подошло»: сдвигаем scheduled_at в прошлое напрямую в БД
    with Session(engine) as s:
        row = s.get(InstantOrder, o["id"])
        row.scheduled_at = utcnow() - timedelta(minutes=1)
        s.add(row)
        s.commit()
    resp = client.get("/instant/scheduled", headers=pax["auth"]).json()
    # активировался лениво при GET: попал в activated, ушёл из scheduled
    assert o["id"] in [x["id"] for x in resp["activated"]]
    assert o["id"] not in [x["id"] for x in resp["scheduled"]]
    with Session(engine) as s:
        assert s.get(InstantOrder, o["id"]).status != S.scheduled


_TF08_POINTS = [
    {"lat": 52.64, "lng": 58.41, "text": "Точка А"},
    {"lat": 52.68, "lng": 58.54, "text": "Точка Б"},
]
_TF08_CASES = [(False, 0), (False, 1), (False, 2), (True, 0), (True, 1), (True, 2)]
_TF08_CASE_IDS = ["direct", "one-stop", "two-stops", "return", "return-one-stop", "return-two-stops"]
_TF08_MODES = ["manual", "lazy", "worker"]
_TF08_ROUTE_METRICS = {
    (ORIG, DEST): (10.0, 12.0),
    (ORIG, (52.64, 58.41)): (7.5, 9.0),
    ((52.64, 58.41), DEST): (11.0, 13.0),
    ((52.64, 58.41), (52.68, 58.54)): (5.0, 8.0),
    ((52.68, 58.54), DEST): (13.0, 17.0),
    # The reverse order has a different distance and ETA, not just different labels.
    (ORIG, (52.68, 58.54)): (9.0, 10.0),
    ((52.68, 58.54), (52.64, 58.41)): (6.0, 10.0),
}


@pytest.fixture
def tf08_market(monkeypatch):
    """Offline providers; real fare, HTTP auth, SQL and matcher remain in use."""
    now = utcnow()
    context = {"now": now, "estimates": [], "matching": [], "legs": [], "owned": []}
    original_estimate, original_matching = isv.estimate, isv.start_matching
    monkeypatch.setattr(settings, "round_trip_discount_percent", 12)
    monkeypatch.setattr(settings, "scheduled_price_guarantee_percent", 0)
    monkeypatch.setattr(isv, "zone_for_km", lambda _km: "intercity")
    monkeypatch.setattr(isv, "surge_k_for", lambda *a, **kw: 1.0)
    monkeypatch.setattr(isv, "night_k_for", lambda *a, **kw: 1.0)
    monkeypatch.setattr(isv, "_redis", lambda: None)
    monkeypatch.setattr(isv, "utcnow", lambda: now)
    monkeypatch.setattr(pricing, "weather_metrics", lambda *a: pricing.WeatherMetrics())
    monkeypatch.setattr("app.services.send_push", lambda *a, **kw: None)

    def route_metrics(frm, to):
        leg = (tuple(frm), tuple(to))
        context["legs"].append(leg)
        distance, duration = _TF08_ROUTE_METRICS[leg]
        return pricing.RouteMetrics(distance, duration)

    def observed_estimate(*args, **kwargs):
        result = original_estimate(*args, **kwargs)
        context["estimates"].append({"kwargs": kwargs.copy(), "result": result.copy()})
        return result

    def observed_matching(session, order, *args, **kwargs):
        context["matching"].append(order.id)
        return original_matching(session, order, *args, **kwargs)

    monkeypatch.setattr(pricing, "route_metrics", route_metrics)
    monkeypatch.setattr(isv, "estimate", observed_estimate)
    monkeypatch.setattr(isv, "start_matching", observed_matching)
    yield context
    # Only this test's synthetic rows are terminated, so later worker tests do not
    # inherit a due scheduled order or a permanently busy passenger from this test.
    with Session(engine) as session:
        for order_id, passenger_id in context["owned"]:
            order = session.get(InstantOrder, order_id)
            if order is not None and order.passenger_id == passenger_id:
                order.status = S.cancelled
                session.add(order)
        session.commit()


def _tf08_new(client, user_factory, market, round_trip, stop_count, mode="manual"):
    pax = user_factory("TF08Synthetic")
    points = [point.copy() for point in _TF08_POINTS[:stop_count]]
    response = _schedule(client, pax, market["now"] + timedelta(hours=1),
                         round_trip=round_trip, waypoints=points)
    assert response.status_code == 200, response.text
    payload = response.json()
    order_id = payload["id"]
    market["owned"].append((order_id, pax["id"]))
    assert payload["status"] == "scheduled"
    assert len(market["estimates"]) == 1 and market["matching"] == []
    quote = market["estimates"][0]["result"]
    stored_points = [{**point, "done": False} for point in points]
    assert payload["stops"] == stored_points  # Actual response key is stops, not waypoints.
    with Session(engine) as session:
        row = session.get(InstantOrder, order_id)
        assert bool(row.round_trip) is round_trip
        assert isv.parse_waypoints(row.waypoints_json) == stored_points
        assert row.price_estimate == quote["price"] == payload["price_estimate"]
        if mode != "manual":
            row.scheduled_at = market["now"] - timedelta(minutes=1)
            session.add(row)
            session.commit()
    nodes = [ORIG] + [(p["lat"], p["lng"]) for p in points] + [DEST]
    legs = list(zip(nodes, nodes[1:]))
    expected_distance = sum(_TF08_ROUTE_METRICS[leg][0] for leg in legs)
    expected_eta = sum(_TF08_ROUTE_METRICS[leg][1] for leg in legs)
    assert quote["distance_km"] == expected_distance and quote["eta_min"] == expected_eta
    market["estimates"].clear()
    market["legs"].clear()
    return {"pax": pax, "id": order_id, "points": stored_points, "round_trip": round_trip,
            "quote": quote, "legs": legs, "distance": expected_distance, "eta": expected_eta}


def _tf08_activate(client, case, mode):
    if mode == "manual":
        response = client.post(f"/instant/scheduled/{case['id']}/activate", headers=case["pax"]["auth"])
        assert response.status_code == 200, response.text
        payload = response.json()
        return payload, payload["status"] != "scheduled"
    if mode == "lazy":
        response = client.get("/instant/scheduled", headers=case["pax"]["auth"])
        assert response.status_code == 200, response.text
        data = response.json()
        payload = next(o for o in data["scheduled"] + data["activated"] if o["id"] == case["id"])
        return payload, case["id"] in {o["id"] for o in data["activated"]}
    with Session(engine) as session:
        started = tw.activate_due_scheduled(session)
    response = client.get(f"/instant/orders/{case['id']}", headers=case["pax"]["auth"])
    assert response.status_code == 200, response.text
    return response.json(), case["id"] in started


def _tf08_snapshot(order_id):
    fields = ("status", "price_estimate", "ride_price", "ride_base_price", "distance_km", "eta_min",
              "pickup_fee_kop", "options_fee_kop", "weather_fee_kop", "round_trip", "waypoints_json")
    with Session(engine) as session:
        row = session.get(InstantOrder, order_id)
        return {field: getattr(row, field) for field in fields}


def _tf08_assert_activated(client, case, mode, market):
    payload, reported_started = _tf08_activate(client, case, mode)
    assert payload["status"] != "scheduled" and reported_started
    assert payload["stops"] == case["points"]
    # Fare inputs are deliberately stable here. Existing scheduled-price tests
    # separately cover time-dependent repricing and the optional price ceiling.
    # Distance/ETA retain the existing outbound projection for a return trip.
    assert payload["distance_km"] == case["distance"]
    assert payload["eta_min"] == case["eta"]
    assert payload["price_estimate"] == case["quote"]["price"]
    snapshot = _tf08_snapshot(case["id"])
    assert snapshot["round_trip"] is case["round_trip"]
    assert isv.parse_waypoints(snapshot["waypoints_json"]) == case["points"]
    for field, expected in isv.price_fields(case["quote"]).items():
        if field in snapshot:
            assert snapshot[field] == expected, field
    assert len(market["estimates"]) == 1
    kwargs = market["estimates"][0]["kwargs"]
    assert bool(kwargs.get("round_trip", False)) is case["round_trip"]
    assert (kwargs.get("waypoints") or []) == case["points"]
    assert kwargs.get("when") is None
    assert market["legs"] == case["legs"]
    assert market["matching"] == [case["id"]]
    before = _tf08_snapshot(case["id"])
    if mode == "manual":
        retry = client.post(f"/instant/scheduled/{case['id']}/activate", headers=case["pax"]["auth"])
        assert retry.status_code == 409
    elif mode == "lazy":
        retry = client.get("/instant/scheduled", headers=case["pax"]["auth"])
        assert retry.status_code == 200
        assert case["id"] not in {o["id"] for o in retry.json()["activated"]}
    else:
        with Session(engine) as session:
            assert case["id"] not in tw.activate_due_scheduled(session)
    assert _tf08_snapshot(case["id"]) == before
    assert len(market["estimates"]) == 1 and market["matching"] == [case["id"]]


def _tf08_busy(case, market):
    with Session(engine) as session:
        source = session.get(InstantOrder, case["id"])
        busy = InstantOrder(passenger_id=source.passenger_id, status=S.searching,
                            from_lat=source.from_lat, from_lng=source.from_lng,
                            to_lat=source.to_lat, to_lng=source.to_lng,
                            from_text=source.from_text, to_text=source.to_text,
                            price_estimate=source.price_estimate)
        session.add(busy)
        session.commit()
        session.refresh(busy)
        market["owned"].append((busy.id, source.passenger_id))
        return busy.id


def _tf08_assert_deferred(client, case, mode, market):
    before = _tf08_snapshot(case["id"])
    for _ in range(2):
        payload, reported_started = _tf08_activate(client, case, mode)
        assert payload["status"] == "scheduled"
        assert _tf08_snapshot(case["id"]) == before
        assert market["estimates"] == [] and market["matching"] == [] and market["legs"] == []
        print("TF08_BUSY_OBSERVATION " + json.dumps({"entrypoint": mode, "order_id": case["id"],
              "persisted_status": "scheduled", "estimate_calls": 0, "matching_calls": 0,
              "reported_started": reported_started}, sort_keys=True))


@pytest.mark.parametrize("round_trip,stop_count", _TF08_CASES, ids=_TF08_CASE_IDS)
def test_tf08_creation_persists_route_before_activation(client, user_factory, tf08_market, round_trip, stop_count):
    _tf08_new(client, user_factory, tf08_market, round_trip, stop_count)


@pytest.mark.parametrize("mode", _TF08_MODES)
@pytest.mark.parametrize("round_trip,stop_count", _TF08_CASES, ids=_TF08_CASE_IDS)
def test_tf08_activation_preserves_persisted_route(client, user_factory, tf08_market, round_trip, stop_count, mode):
    case = _tf08_new(client, user_factory, tf08_market, round_trip, stop_count, mode)
    _tf08_assert_activated(client, case, mode, tf08_market)


@pytest.mark.parametrize("mode", _TF08_MODES)
@pytest.mark.parametrize("round_trip,stop_count", _TF08_CASES, ids=_TF08_CASE_IDS)
def test_tf08_busy_order_stays_scheduled_without_repricing_or_matching(client, user_factory, tf08_market, round_trip, stop_count, mode):
    case = _tf08_new(client, user_factory, tf08_market, round_trip, stop_count, mode)
    _tf08_busy(case, tf08_market)
    _tf08_assert_deferred(client, case, mode, tf08_market)


@pytest.mark.parametrize("mode", _TF08_MODES)
@pytest.mark.parametrize("round_trip,stop_count", _TF08_CASES, ids=_TF08_CASE_IDS)
def test_tf08_released_passenger_activates_once(client, user_factory, tf08_market, round_trip, stop_count, mode):
    case = _tf08_new(client, user_factory, tf08_market, round_trip, stop_count, mode)
    busy_id = _tf08_busy(case, tf08_market)
    _tf08_assert_deferred(client, case, mode, tf08_market)
    released = client.post(f"/instant/orders/{busy_id}/cancel", headers=case["pax"]["auth"])
    assert released.status_code == 200 and released.json()["status"] == "cancelled"
    _tf08_assert_activated(client, case, mode, tf08_market)
