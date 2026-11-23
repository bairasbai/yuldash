"""Real legacy offered path, SQL fences and same existing tariff calculation."""
from concurrent.futures import ThreadPoolExecutor
from threading import Event

import pytest
from sqlalchemy import delete, event, select, update
from sqlmodel import Session

from app import instant_service as isv, pricing, taxi_route_resume as resume
from app.db import engine
from app.models import InstantOrder, InstantOrderStatus as S, Tariff
from test_instant import ORIG, _driver_online, _heartbeat, _offered_order, fake_redis  # noqa: F401
from test_taxi_route_write_races import _destination, _route

_ORDER = InstantOrder.__table__


def _state(oid):
    with engine.connect() as conn:
        return conn.execute(select(_ORDER).where(_ORDER.c.id == oid)).mappings().one()


@pytest.fixture
def offer(request, client, user_factory, monkeypatch):
    redis = request.getfixturevalue("fake_redis")
    driver, passenger, resp = _offered_order(client, user_factory, redis, "LegacyDestDriver", "LegacyDestOwner")
    monkeypatch.setattr(pricing, "route_metrics", lambda *a, **k: pricing.RouteMetrics(3.0, 4.0, "synthetic"))
    try:
        yield driver, passenger, resp["id"]
    finally:
        with engine.begin() as c:
            c.execute(delete(_ORDER).where(_ORDER.c.id == resp["id"]))


def _post(client, owner, oid, **extra):
    return client.post(_route(oid, "destination"), headers=owner["auth"], json=_destination() | extra)


@pytest.mark.parametrize("field", resume._MARKERS)
def test_visible_marker_stops_before_quote_without_exposing_it(client, offer, monkeypatch, field):
    driver, passenger, oid = offer
    with engine.begin() as c:
        val = c.scalar(select(_ORDER.c.offered_at).where(_ORDER.c.id == oid))
        flags = {"route_resume_id": "a"*32, "route_resume_claim_id": "b"*32,
                 "route_resume_previous_driver_id": driver["id"], resume._SEAL: b"x"*32}
        c.execute(update(_ORDER).where(_ORDER.c.id == oid).values({field: flags.get(field, val)}))
    seen = []
    real = isv.destination_quote

    def pricing_spy(*a, **kw):
        seen.append(True)
        return real(*a, **kw)

    monkeypatch.setattr(isv, "destination_quote", pricing_spy)
    before = _state(oid)
    assert _post(client, driver, oid).status_code == 404 and seen == []
    blocked = _post(client, passenger, oid)
    assert blocked.status_code == 409 and blocked.json()["detail"]["code"] == "taxi_route_changed"
    assert seen == [] and _state(oid) == before


@pytest.mark.skipif(engine.dialect.name != "sqlite", reason="actual separately owned SQLite write")
@pytest.mark.parametrize("rival", ("seal", "tariff", "accept"))
def test_real_rival_wins_before_final_offered_destination_sql(client, offer, monkeypatch, rival):
    driver, passenger, oid = offer
    first = _state(oid)
    entered, release = Event(), Event()
    quote_tariff = []
    effects = []
    original_quote = isv.destination_quote

    def capture(session, order, destination, **kwargs):
        quote = original_quote(session, order, destination, **kwargs)
        quote_tariff.append(quote["tariff_id"])
        entered.set()
        assert release.wait(6)
        return quote

    monkeypatch.setattr(isv, "destination_quote", capture)
    monkeypatch.setattr(isv, "try_offer_next", lambda *a, **k: effects.append("matcher"))
    monkeypatch.setattr(isv, "notify_driver_destination", lambda *a, **k: effects.append("destination"))
    with ThreadPoolExecutor(max_workers=1) as workers:
        pending = workers.submit(_post, client, passenger, oid)
        old_rate = None
        try:
            assert entered.wait(4)
            if rival == "seal":
                with engine.begin() as c:
                    c.execute(update(_ORDER).where(_ORDER.c.id == oid).values({resume._SEAL: b"x"*32}))
            elif rival == "tariff":
                with Session(engine) as s:
                    rate = s.get(Tariff, quote_tariff[0])
                    old_rate = rate.per_km
                    rate.per_km += 2
                    s.commit()
            else:
                accepted = client.post(_route(oid, "accept"), headers=driver["auth"])
                assert accepted.status_code == 200, accepted.text
            winner = _state(oid)
        finally:
            release.set()
        try:
            result = pending.result(timeout=8)
        finally:
            if old_rate is not None:
                with Session(engine) as s:
                    rate = s.get(Tariff, quote_tariff[0])
                    rate.per_km = old_rate
                    s.commit()
    assert result.status_code == 409, result.text
    assert result.json()["detail"]["code"] == "taxi_route_changed"
    assert _state(oid) == winner and effects == []
    assert winner["to_text"] == first["to_text"] and winner["ride_price"] == first["ride_price"]


def test_first_real_successor_offer_only_sees_new_committed_route_and_fare(client, user_factory, offer, monkeypatch):
    previous, passenger, oid = offer
    second = _driver_online(client, user_factory, "LegacyDestNewDriver")
    _heartbeat(client, second, ORIG)
    observed = []

    def push(session, order, driver):
        row = _state(oid)
        assert row["status"] is S.offered
        observed.append((driver, row, order.to_text))

    monkeypatch.setattr(isv, "_push_offer", push)
    monkeypatch.setattr(isv, "notify_driver_destination", lambda *a, **k: pytest.fail("old route notification"))
    started = _state(oid)
    preview = _post(client, passenger, oid, preview=True)
    assert preview.status_code == 200 and not preview.json()["applied"] and _state(oid) == started
    result = _post(client, passenger, oid)
    assert result.status_code == 200 and result.json()["applied"], result.text
    saved = _state(oid)
    assert observed == [(second["id"], saved, _destination()["to_text"])]
    assert saved["current_offer_driver_id"] == second["id"] != previous["id"]
    assert saved["ride_price"] == result.json()["price"]
    assert saved["search_round"] == started["search_round"] + 1
    assert saved["searching_at"] == started["searching_at"]


@pytest.mark.skipif(engine.dialect.name != "sqlite", reason="actual separately owned SQLite write")
def test_mark_arriving_at_actual_destination_update_stops_not_just_pricing(client, offer, monkeypatch):
    _, passenger, oid = offer
    prior = _state(oid)
    hit, effects = [], []

    def mark(conn, cursor, sql, parameters, context, executemany):
        if sql.startswith("UPDATE instantorder SET") and not hit:
            hit.append(True)
            with engine.begin() as rival:
                assert rival.connection.driver_connection is not conn.connection.driver_connection
                rival.execute(update(_ORDER).where(_ORDER.c.id == oid).values({resume._SEAL: b"x"*32}))

    monkeypatch.setattr(isv, "try_offer_next", lambda *a, **kw: effects.append("matcher"))
    monkeypatch.setattr(isv, "notify_driver_destination", lambda *a, **kw: effects.append("notify"))
    event.listen(engine, "before_cursor_execute", mark)
    try:
        actual = _post(client, passenger, oid)
    finally:
        event.remove(engine, "before_cursor_execute", mark)
    assert hit == [True] and effects == []
    assert actual.status_code == 409, actual.text
    assert _state(oid) == dict(prior) | {resume._SEAL: b"x"*32}


@pytest.mark.skipif(engine.dialect.name != "sqlite", reason="actual separately owned SQLite write")
def test_postcommit_legacy_matcher_cannot_issue_offer_for_superseded_terms(client, user_factory, offer, monkeypatch):
    _, passenger, oid = offer
    new = _driver_online(client, user_factory, "LegacyDestRouteRival")
    _heartbeat(client, new, ORIG)
    called, pushes = [], []
    real = isv.candidates

    def race(redis, session, order, exclude):
        result = real(redis, session, order, exclude)
        assert new["id"] in result
        owned = _state(oid)
        assert owned["status"] is S.searching and owned["to_text"] == _destination()["to_text"]
        with engine.begin() as rival:
            rival.execute(update(_ORDER).where(_ORDER.c.id == oid).values(to_text="SYNTHETIC MORE RECENT ROUTE"))
        called.append(True)
        return result

    monkeypatch.setattr(isv, "candidates", race)
    monkeypatch.setattr(isv, "_push_offer", lambda *a, **kw: pushes.append(True))
    response = _post(client, passenger, oid)
    assert response.status_code == 409 and called == [True] and pushes == []
    persisted = _state(oid)
    assert persisted["status"] is S.searching and persisted["current_offer_driver_id"] is None
    assert persisted["to_text"] == "SYNTHETIC MORE RECENT ROUTE"
