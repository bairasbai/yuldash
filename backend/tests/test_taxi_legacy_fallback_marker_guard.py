"""Real class fallback legacy route and authoritative mutation race witnesses."""
from datetime import timedelta

import pytest
from sqlalchemy import delete, event, select, update
from sqlmodel import Session

from app import instant_service as isv, promo_ride, taxi_route_resume as resume
from app.config import settings
from app.db import engine
from app.models import InstantOrder, InstantOrderStatus as S, Tariff, UserRole
from app.timeutil import utcnow
from test_taxi_route_write_races import _route

_TABLE = InstantOrder.__table__
_C = _TABLE.c
_TARGET = {"category": "standard"}


def _state(oid):
    with engine.connect() as c:
        return dict(c.execute(select(_TABLE).where(_C.id == oid)).one()._mapping)


@pytest.fixture
def ordinary(user_factory, monkeypatch):
    monkeypatch.setattr(settings, "car_class_min_drivers", 0)
    owner, other = user_factory("LegacyFallbackOwner"), user_factory("LegacyFallbackOther")
    driver = user_factory("LegacyFallbackDriver", role=UserRole.driver)
    now = utcnow()
    with Session(engine) as s:
        row = InstantOrder(
            passenger_id=owner["id"], current_offer_driver_id=driver["id"], status=S.offered,
            category="comfort", from_lat=54.76, from_lng=55.99, to_lat=54.77, to_lng=55.98,
            distance_km=5.0, eta_min=15.0, ride_price=900, ride_base_price=900, price_estimate=970,
            pickup_fee_kop=7000, promo_discount_kop=8000, search_round=2,
            searching_at=now - timedelta(seconds=5), offered_at=now - timedelta(seconds=1),
            offer_expires_at=now + timedelta(seconds=90),
        )
        s.add(row)
        s.flush()
        oid = row.id
        s.commit()
    try:
        yield owner, driver, other, oid
    finally:
        with engine.begin() as c:
            c.execute(delete(_TABLE).where(_C.id == oid))


def _call(client, method, user, oid):
    kw = {"json": _TARGET} if method == "POST" else {}
    return client.request(method, _route(oid, "alternatives"), headers=user["auth"], **kw)


@pytest.mark.parametrize("method", ("GET", "POST"))
@pytest.mark.parametrize("marked", ("seal_only", "full"))
def test_existing_full_or_partial_marker_denied_after_owner_before_class_price(client, ordinary, monkeypatch, method, marked):
    owner, driver, other, oid = ordinary
    at = _state(oid)["offered_at"]
    marks = {resume._SEAL: b"x"*32}
    if marked == "full":
        marks |= {"route_resume_id": "b"*32, "route_resume_previous_driver_id": driver["id"],
                  "route_resume_retired_at": at, "route_resume_deadline_at": at+timedelta(seconds=120),
                  "route_resume_ready_at": at, "route_resume_claim_id": "c"*32}
    with engine.begin() as conn:
        conn.execute(update(_TABLE).where(_C.id == oid).values(**marks))
    before = _state(oid)
    calls = []
    canonical = isv.class_rollout.place_at

    def class_check(*args, **kwargs):
        calls.append("availability")
        return canonical(*args, **kwargs)

    monkeypatch.setattr(isv.class_rollout, "place_at", class_check)
    for outsider in (driver, other):
        hidden = _call(client, method, outsider, oid)
        assert hidden.status_code == 403 and "code" not in hidden.json()["detail"]
        assert calls == []
    response = _call(client, method, owner, oid)
    assert response.status_code == 409 and response.json()["detail"]["code"] == "taxi_route_changed"
    assert "route_resume" not in response.text and "b"*32 not in response.text
    assert calls == [] and _state(oid) == before


@pytest.mark.skipif(engine.dialect.name != "sqlite", reason="real independent SQLite writer at authoritative DML")
@pytest.mark.parametrize("rival", ("mark", "phase", "owner", "offer", "tariff"))
def test_actual_dml_loser_never_alters_fare_class_or_discount(client, ordinary, monkeypatch, rival):
    owner, _, other, oid = ordinary
    before = _state(oid)
    mutations = {
        "mark": {resume._SEAL: b"x"*32}, "phase": {"status": S.cancelled, "cancelled_at": utcnow()},
        "owner": {"passenger_id": other["id"]}, "offer": {"offer_expires_at": before["offer_expires_at"]+timedelta(seconds=30)},
        "tariff": {},
    }
    saw, reclamps, notices, tariff_change = [], [], [], []
    original_tariff = isv.active_tariff
    chosen_id = []

    def chosen(*args):
        tariff = original_tariff(*args)
        if tariff is not None:
            chosen_id.append(tariff.id)
        return tariff

    def intervene(conn, cursor, statement, parameters, context, executemany):
        if statement.startswith("UPDATE instantorder SET") and not saw:
            saw.append(True)
            with engine.begin() as writer:
                assert writer.connection.driver_connection is not conn.connection.driver_connection
                if rival == "tariff":
                    rates = Tariff.__table__.c
                    tid = chosen_id[0]
                    old = writer.scalar(select(rates.per_km).where(rates.id == tid))
                    tariff_change.append((tid, old))
                    writer.execute(update(Tariff).where(rates.id == tid).values(per_km=old+2))
                else:
                    writer.execute(update(_TABLE).where(_C.id == oid).values(**mutations[rival]))

    def clamp(*args, **kwargs):
        reclamps.append(True)
        return original_reclamp(*args, **kwargs)

    original_reclamp = promo_ride.reclamp
    monkeypatch.setattr(isv, "active_tariff", chosen)
    monkeypatch.setattr(promo_ride, "reclamp", clamp)
    monkeypatch.setattr(isv, "try_offer_next", lambda *a, **kw: notices.append("matching"))
    monkeypatch.setattr(isv, "notify_driver_destination", lambda *a, **kw: notices.append("notification"))
    event.listen(engine, "before_cursor_execute", intervene)
    try:
        response = _call(client, "POST", owner, oid)
    finally:
        event.remove(engine, "before_cursor_execute", intervene)
        if tariff_change:
            tid, old = tariff_change[0]
            with engine.begin() as conn:
                conn.execute(update(Tariff).where(Tariff.id == tid).values(per_km=old))
    assert response.status_code == 409, response.text
    assert response.json()["detail"]["code"] == "taxi_route_changed"
    assert saw == [True] and reclamps == notices == []
    assert _state(oid) == before | mutations[rival]


@pytest.mark.parametrize("discounted", (True, False))
def test_ordinary_price_cap_and_offer_invalidation_are_unchanged(client, ordinary, discounted):
    owner, _, _, oid = ordinary
    if not discounted:
        with engine.begin() as conn:
            conn.execute(update(_TABLE).where(_C.id == oid).values(ride_price=1, price_estimate=71))
    before = _state(oid)
    get = _call(client, "GET", owner, oid)
    assert get.status_code == 200 and get.json()["options"][0]["category"] == "standard"
    after_get = _state(oid)
    assert after_get == before
    response = _call(client, "POST", owner, oid)
    result = _state(oid)
    assert response.status_code == 200 and sorted(response.json()["categories"]) == ["comfort", "standard"]
    assert result["status"] is S.offered  # no new dispatch/lifecycle rule
    assert result["price_estimate"] == response.json()["price"] == (
        get.json()["options"][0]["price"] if discounted else before["price_estimate"]
    )
    assert result["promo_discount_kop"] == promo_ride.cap_for_price(before["promo_discount_kop"], result["ride_price"])
    for name in ("search_round", "searching_at", "offered_at", "destination_changed_at", "pickup_fee_kop", "tariff_id"):
        assert result[name] == before[name]
    for name in ("current_offer_driver_id", "offer_expires_at"):
        assert result[name] == (None if discounted else before[name])


@pytest.mark.skipif(engine.dialect.name != "sqlite", reason="SQLite RETURNING does not see AFTER triggers")
def test_after_update_marker_rolls_back_class_fare_and_discount(client, ordinary):
    owner, _, _, oid = ordinary
    before = _state(oid)
    with engine.begin() as c:
        c.exec_driver_sql(
            "CREATE TRIGGER staged_fallback_interference AFTER UPDATE OF fallback_categories ON instantorder "
            "BEGIN UPDATE instantorder SET route_resume_agreement_seal=zeroblob(32) WHERE id=NEW.id; END",
        )
    try:
        response = _call(client, "POST", owner, oid)
    finally:
        with engine.begin() as c:
            c.exec_driver_sql("DROP TRIGGER staged_fallback_interference")
    assert response.status_code == 409 and response.json()["detail"]["code"] == "taxi_route_changed"
    assert _state(oid) == before
