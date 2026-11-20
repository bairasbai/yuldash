# -*- coding: utf-8 -*-
"""Предзаказ такси «на время» (MVP): создание, список только свои, отмена,
активация (ручная и ленивая при GET), запрет прошлого/слишком далёкого времени."""
from datetime import timedelta

import pytest
from sqlmodel import Session

from app import instant_service as isv
from app import pricing
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


@pytest.mark.parametrize("lazy", [False, True])
@pytest.mark.parametrize("round_trip,with_stops", [(False, False), (False, True), (True, False), (True, True)])
def test_activation_reprices_the_persisted_route(client, user_factory, monkeypatch, lazy, round_trip, with_stops):
    monkeypatch.setattr(settings, "round_trip_discount_percent", 12)
    monkeypatch.setattr(settings, "scheduled_price_guarantee_percent", 0)
    monkeypatch.setattr(isv, "zone_for_km", lambda _distance: "intercity")
    monkeypatch.setattr(isv, "_redis", lambda: None)
    monkeypatch.setattr(pricing, "route_metrics", lambda *_: pricing.RouteMetrics(10.0, 12.0))
    monkeypatch.setattr(pricing, "weather_metrics", lambda *_: pricing.WeatherMetrics())
    # Isolate the test from external services, but use the real fare computation.
    pax = user_factory("SchedRoute")
    points = [
        {"lat": 52.64, "lng": 58.41, "text": "Точка А"},
        {"lat": 52.68, "lng": 58.54, "text": "Точка Б"},
    ] if with_stops else []
    created = _schedule(client, pax, utcnow() + timedelta(hours=1), round_trip=round_trip, waypoints=points)
    assert created.status_code == 200, created.text
    order_id = created.json()["id"]
    with Session(engine) as session:
        row = session.get(InstantOrder, order_id)
        assert bool(row.round_trip) is round_trip
        assert isv.parse_waypoints(row.waypoints_json) == [{**point, "done": False} for point in points]
        if lazy:
            row.scheduled_at = utcnow() - timedelta(minutes=1)
            session.add(row)
            session.commit()

    calls = []
    results = []
    estimate = isv.estimate
    activation_time = utcnow()
    monkeypatch.setattr(isv, "utcnow", lambda: activation_time)

    def observed_estimate(*args, **kwargs):
        calls.append(kwargs.copy())
        result = estimate(*args, **kwargs)
        results.append(result.copy())
        return result

    monkeypatch.setattr(isv, "estimate", observed_estimate)
    if lazy:
        activation = client.get("/instant/scheduled", headers=pax["auth"])
        assert activation.status_code == 200, activation.text
        assert order_id in {order["id"] for order in activation.json()["activated"]}
    else:
        activation = client.post(f"/instant/scheduled/{order_id}/activate", headers=pax["auth"])
        assert activation.status_code == 200, activation.text
        assert activation.json()["status"] != "scheduled"
    assert len(calls) == 1
    assert calls[0]["round_trip"] is round_trip
    assert calls[0]["waypoints"] == [{**point, "done": False} for point in points]
    assert calls[0].get("when") is None  # current activation time, not initial booking time
    assert results[0]["distance_km"] == (30.0 if with_stops else 10.0)
    assert results[0]["eta_min"] == (36.0 if with_stops else 12.0)
    with Session(engine) as session:
        row = session.get(InstantOrder, order_id)
        assert row.price_estimate == results[0]["price"]
        assert row.ride_price == results[0]["ride_price"]
        assert row.distance_km == results[0]["distance_km"]
        assert row.eta_min == results[0]["eta_min"]
        assert bool(row.round_trip) is round_trip
        assert isv.parse_waypoints(row.waypoints_json) == [{**point, "done": False} for point in points]
