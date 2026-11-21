"""Initial taxi consent: actual HTTP/auth/estimator/SQL, synthetic market only."""
import json
import time
import threading
from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta
from types import SimpleNamespace
from uuid import uuid4

import fakeredis
import fakeredis._socket._base as fake_socket
import pytest
from sqlalchemy import text
from sqlmodel import Session, select

from app import instant_service as isv, pricing, taxi_quote_consent as consent, taxi_worker
from app.config import settings
from app.db import engine
from app.models import InstantOrder, InstantOrderStatus as S, LedgerEntry, PromoCode, Tariff, UserRole
from app.timeutil import utcnow
from test_money_rules import _driver_online, _heartbeat, _order_body
from test_promo_taxi import _pax_with_discount

QUOTE = "/instant/quotes/v1"
CONFIRM = "/instant/orders/confirmed/v1"


@pytest.fixture
def consent_market(client, monkeypatch):
    class Clock:
        offset = 0

        def time(self):
            return time.time() + self.offset

        def __getattr__(self, key):
            return getattr(time, key)
    clock = Clock()
    redis = fakeredis.FakeStrictRedis(decode_responses=True)
    state = SimpleNamespace(redis=redis, clock=clock, surge=1.0, unavailable=False)
    monkeypatch.setattr(fake_socket, "time", clock)
    monkeypatch.setattr(isv, "_redis", lambda: None if state.unavailable else redis)
    monkeypatch.setattr(isv, "surge_k_for", lambda *a, **k: state.surge)
    monkeypatch.setattr(isv, "night_k_for", lambda *a, **k: 1)
    monkeypatch.setattr(pricing, "route_metrics", lambda *a: pricing.RouteMetrics(12.0, 18.0))
    monkeypatch.setattr(pricing, "weather_metrics", lambda *a: pricing.WeatherMetrics())
    monkeypatch.setattr("app.services.send_push", lambda *a, **k: None)
    monkeypatch.setattr(settings, "price_freeze_sec", 120)
    monkeypatch.setattr(settings, "promo_ride_max_discount_rub", 1000)
    monkeypatch.setattr(settings, "promo_ride_max_price_share", 0.5)
    with Session(engine) as session:
        originals = {t.id: t.model_dump() for t in session.exec(select(Tariff)).all()}
    try:
        with Session(engine) as session:
            for tariff in session.exec(select(Tariff)).all():
                tariff.base = tariff.min_price = 450
                tariff.per_km = tariff.per_min = tariff.pickup_per_km = 0
                tariff.k = tariff.night_k = 1
                session.add(tariff)
            session.commit()
        yield state
    finally:
        # set_base() and pickup cases also edit free/max pickup and other fields.
        # Restore the entire real rows, not just the common base/minimum fields.
        with Session(engine) as session:
            for tariff_id, values in originals.items():
                tariff = session.get(Tariff, tariff_id)
                assert tariff is not None, "synthetic test removed an existing tariff"
                for name, value in values.items():
                    setattr(tariff, name, value)
                session.add(tariff)
            session.commit()
        with Session(engine) as observer:
            restored = {t.id: t.model_dump() for t in observer.exec(
                select(Tariff).where(Tariff.id.in_(originals))).all()}
            assert restored == originals, "consent fixture leaked tariff state"


def quote(client, user, body=None):
    response = client.post(QUOTE, headers=user["auth"], json=body or _order_body())
    assert response.status_code == 200, response.text
    data = response.json()
    assert sum(data["breakdown"].values()) == data["price_kop"]
    assert data["price_kop"] - data["promo_discount_kop"] == data["payable_kop"]
    return data


def body_for(data, **extra):
    return _order_body(quote_id=data["quote_id"], request_key=data["request_key"], **extra)


def confirm(client, user, data, **extra):
    return client.post(CONFIRM, headers=user["auth"], json=body_for(data, **extra))


def orders(owner):
    with Session(engine) as session:
        return session.exec(select(InstantOrder).where(InstantOrder.passenger_id == owner)).all()


def set_base(value, **fields):
    with Session(engine) as session:
        for tariff in session.exec(select(Tariff)).all():
            tariff.base = tariff.min_price = value
            for name, val in fields.items():
                setattr(tariff, name, val)
            session.add(tariff)
        session.commit()


def live_driver(client, user_factory):
    driver = _driver_online(client, user_factory, "SyntheticConsentDriver")
    _heartbeat(client, driver)
    return driver


def promo_user(client, user_factory, rub=150):
    admin = user_factory("SyntheticConsentAdmin", role=UserRole.admin)
    return _pax_with_discount(client, user_factory, admin, "SC" + uuid4().hex[:12].upper(),
                              rub=rub, name="SyntheticConsentPromo")


def test_two_tabs_cannot_replace_specific_fare(client, user_factory, consent_market):
    user = user_factory("SyntheticConsentTabs")
    a = quote(client, user)
    consent_market.clock.offset = 30
    consent_market.surge = 4 / 3
    b = quote(client, user)
    assert (a["payable_kop"], b["payable_kop"]) == (45000, 60000)
    assert a["quote_id"] != b["quote_id"] and a["request_key"] != b["request_key"]
    result = confirm(client, user, a)
    assert result.status_code == 200, result.text
    assert result.json()["consent"]["accepted"]["payable_kop"] == 45000
    assert result.json()["order"]["price_estimate"] == 450


def test_cheaper_whole_calculation_and_breakdown(client, user_factory, consent_market):
    user = user_factory("SyntheticConsentCheaper")
    set_base(600)
    data = quote(client, user)
    set_base(400)
    result = confirm(client, user, data)
    assert result.status_code == 200, result.text
    accepted = result.json()["consent"]["accepted"]
    assert accepted["price_kop"] == accepted["payable_kop"] == 40000
    assert accepted["breakdown"]["ride_price_kop"] == 40000
    assert sum(accepted["breakdown"].values()) == 40000
    assert result.json()["consent"]["quote"]["payable_kop"] == 60000


@pytest.mark.parametrize("failure", ["expiry", "deadline", "lost", "unavailable", "tamper"])
def test_invalid_quote_never_creates_or_clears_wait(client, user_factory, consent_market, monkeypatch, failure):
    user = user_factory("SyntheticConsentInvalid")
    old_wait = utcnow() + timedelta(minutes=20)
    with Session(engine) as session:
        old = InstantOrder(passenger_id=user["id"], status=S.expired, wait_until=old_wait)
        session.add(old)
        session.commit()
        old_id = old.id
    data = quote(client, user)
    if failure == "expiry":
        consent_market.clock.offset = 121
    elif failure == "deadline":
        now = utcnow()
        monkeypatch.setattr(consent, "utcnow", lambda: now + timedelta(seconds=121))
    elif failure == "lost":
        consent_market.redis.flushall()
    elif failure == "unavailable":
        consent_market.unavailable = True
    else:
        key = consent_market.redis.keys("taxi:initial-quote:v1:*")[0]
        raw = json.loads(consent_market.redis.get(key))
        raw["estimate"]["price"] = 1
        consent_market.redis.set(key, json.dumps(raw))
    response = confirm(client, user, data)
    assert response.status_code == 409, response.text
    assert response.json()["detail"]["code"] == "taxi_quote_refresh_required"
    assert len(orders(user["id"])) == 1
    with Session(engine) as session:
        assert session.get(InstantOrder, old_id).wait_until == old_wait


def test_foreign_confirmation_does_not_consume_owners_quote(client, user_factory, consent_market):
    owner, stranger = user_factory("SyntheticOwner"), user_factory("SyntheticStranger")
    data = quote(client, owner)
    denied = confirm(client, stranger, data)
    assert denied.status_code == 409 and not orders(stranger["id"])
    assert confirm(client, owner, data).status_code == 200


@pytest.mark.parametrize("change", [
    {"to_lat": 52.8}, {"from_lng": 58.31}, {"round_trip": True}, {"return_wait_min": 10},
    {"category": "comfort"}, {"options": ["wheelchair"]},
    {"waypoints": [{"lat": 52.7, "lng": 58.4}]},
])
def test_route_mismatch_rejected_before_effects(client, user_factory, consent_market, change):
    user = user_factory("SyntheticConsentRoute")
    data = quote(client, user)
    payload = body_for(data)
    payload.update(change)
    response = client.post(CONFIRM, headers=user["auth"], json=payload)
    assert response.status_code == 409, response.text
    assert not orders(user["id"])
    assert confirm(client, user, data).status_code == 200


def test_ordered_stops_and_labels_are_separate_from_key_content(client, user_factory, consent_market):
    user = user_factory("SyntheticConsentStops")
    a, b = {"lat": 52.7, "lng": 58.4, "text": "Synthetic A"}, {"lat": 52.8, "lng": 58.5, "text": "Synthetic B"}
    data = quote(client, user, _order_body(waypoints=[a, b]))
    changed = confirm(client, user, data, waypoints=[b, a])
    assert changed.status_code == 409 and not orders(user["id"])
    result = confirm(client, user, data, waypoints=[dict(a, text="Renamed"), b])
    assert result.status_code == 200, result.text
    # Labels do not change quote pricing identity; they ARE material delivery instructions.
    replay_changed_label = confirm(client, user, data, waypoints=[a, b])
    assert replay_changed_label.status_code == 409
    assert replay_changed_label.json()["detail"]["code"] == "taxi_consent_key_conflict"


@pytest.mark.parametrize("extra", [
    {"comment": "Synthetic instruction"}, {"entrance": "Synthetic entrance"},
    {"for_name": "Synthetic receiver"}, {"for_phone": "+70000000000"},
    {"women_only": True}, {"payment_method": "cash"},
    {"from_text": "Synthetic other pickup"}, {"to_text": "Synthetic other destination"},
])
def test_material_request_key_conflict_after_terminal(client, user_factory, consent_market, extra):
    user = user_factory("SyntheticConsentConflict")
    data = quote(client, user)
    first = confirm(client, user, data)
    assert first.status_code == 200, first.text
    consent_market.clock.offset = 121
    changed = confirm(client, user, data, **extra)
    assert changed.status_code == 409, changed.text
    assert changed.json()["detail"]["code"] == "taxi_consent_key_conflict"
    assert len(orders(user["id"])) == 1


def test_lost_response_replay_after_done_expiry_and_disabled_feature(client, user_factory, consent_market, monkeypatch):
    driver = live_driver(client, user_factory)
    user = user_factory("SyntheticConsentReplay")
    data = quote(client, user)
    original = isv.start_matching
    dispatches = []

    def observed(session, order, *args, **kwargs):
        with Session(engine) as observer:
            committed = observer.get(InstantOrder, order.id)
            assert committed and committed.consent_snapshot_json
        dispatches.append(order.id)
        return original(session, order, *args, **kwargs)
    monkeypatch.setattr(isv, "start_matching", observed)
    first = confirm(client, user, data)
    assert first.status_code == 200, first.text
    oid = first.json()["order"]["id"]
    for action in ["accept", "arrived", "onboard", "done"]:
        response = client.post(f"/instant/orders/{oid}/{action}", headers=driver["auth"])
        assert response.status_code == 200, response.text
    consent_market.clock.offset = 121
    consent_market.unavailable = True
    monkeypatch.setattr(settings, "taxi_enabled", False)
    replay = confirm(client, user, data)
    assert replay.status_code == 200, replay.text
    assert replay.json()["order"]["id"] == oid and replay.json()["order"]["status"] == "done"
    assert replay.json()["consent"] == first.json()["consent"]
    assert len(orders(user["id"])) == 1 and dispatches == [oid]


def test_key_cannot_be_rebound_after_order_deletion(client, user_factory, consent_market):
    user = user_factory("SyntheticConsentDeletion")
    data = quote(client, user)
    result = confirm(client, user, data)
    assert result.status_code == 200, result.text
    with Session(engine) as session:
        row = session.get(InstantOrder, result.json()["order"]["id"])
        assert row.status == S.expired
        session.delete(row)
        session.commit()
    assert confirm(client, user, data).status_code == 409
    fresh = quote(client, user)
    rebound = dict(fresh, request_key=data["request_key"])
    assert confirm(client, user, rebound).status_code == 409
    assert not orders(user["id"])
    assert confirm(client, user, fresh).status_code == 200


def test_quote_cache_has_no_route_or_recipient_copy(client, user_factory, consent_market):
    user = user_factory("SyntheticConsentPrivacy")
    body = _order_body(from_text="PRIVATE SYNTHETIC PICKUP", to_text="PRIVATE SYNTHETIC DESTINATION",
                       waypoints=[{"lat": 52.712345, "lng": 58.412345, "text": "PRIVATE SYNTHETIC STOP"}])
    quote(client, user, body)
    raw = consent_market.redis.get(consent_market.redis.keys("taxi:initial-quote:v1:*")[0])
    for sensitive in ["PRIVATE SYNTHETIC", "52.712345", "58.412345", "from_lat", "from_text", "for_phone"]:
        assert sensitive not in raw


def test_cache_watch_replacement_fails_closed(client, user_factory, consent_market, monkeypatch):
    user = user_factory("SyntheticConsentWatch")
    data = quote(client, user)
    redis = consent_market.redis
    key = redis.keys("taxi:initial-quote:v1:*")[0]
    pipeline = redis.pipeline

    def competing_pipeline(*args, **kwargs):
        pipe = pipeline(*args, **kwargs)
        execute = pipe.execute

        def compete(*a, **kw):
            redis.set(key, '{"synthetic":"replacement"}')
            return execute(*a, **kw)
        pipe.execute = compete
        return pipe
    monkeypatch.setattr(redis, "pipeline", competing_pipeline)
    response = confirm(client, user, data)
    assert response.status_code == 409 and not orders(user["id"])
    assert redis.get(key) == '{"synthetic":"replacement"}'


@pytest.mark.parametrize("mutation", ["disabled", "consumed", "new-promo", "lower-cap"])
def test_promo_change_cannot_create_or_change_previous_wait(client, user_factory, consent_market, mutation):
    set_base(600)
    user = user_factory("SyntheticNoPromo") if mutation == "new-promo" else promo_user(client, user_factory)
    data = quote(client, user)
    assert data["payable_kop"] == (60000 if mutation == "new-promo" else 45000)
    old_wait = utcnow() + timedelta(minutes=15)
    with Session(engine) as session:
        old = InstantOrder(passenger_id=user["id"], status=S.expired, wait_until=old_wait, price_estimate=600)
        session.add(old)
        session.commit()
        old_id = old.id
        ledger_count = len(session.exec(select(LedgerEntry)).all())
    if mutation == "disabled":
        from app.models import PromoRedemption
        with Session(engine) as session:
            red = session.exec(select(PromoRedemption).where(PromoRedemption.user_id == user["id"])).one()
            promo = session.get(PromoCode, red.promo_id)
            promo.active = False
            session.add(promo)
            session.commit()
    elif mutation == "consumed":
        from app import promo_ride
        with Session(engine) as session:
            other = InstantOrder(passenger_id=user["id"], status=S.done, price_estimate=600, ride_price=600)
            session.add(other)
            session.commit()
            assert promo_ride.consume(session, user["id"], other) == 15000
    elif mutation == "new-promo":
        from test_promo_taxi import _create_promo
        admin = user_factory("SyntheticNewPromoAdmin", role=UserRole.admin)
        code = "SC" + uuid4().hex[:12].upper()
        _create_promo(client, admin, code, perk_value=150)
        assert client.post("/promo/apply", headers=user["auth"], json={"code": code}).status_code == 200
    else:
        set_base(400)
        # Same resource, but cap fell below shown150. Require explicit new consent.
        monkey = pytest.MonkeyPatch()
        monkey.setattr(settings, "promo_ride_max_price_share", 0.25)
    try:
        before = len(orders(user["id"]))
        result = confirm(client, user, data)
        assert result.status_code == 409, result.text
        assert len(orders(user["id"])) == before
        with Session(engine) as session:
            assert session.get(InstantOrder, old_id).wait_until == old_wait
            assert len(session.exec(select(LedgerEntry)).all()) == ledger_count
    finally:
        if mutation == "lower-cap":
            monkey.undo()


def test_positive_promo_full_initial_amount(client, user_factory, consent_market):
    set_base(600)
    live_driver(client, user_factory)
    user = promo_user(client, user_factory)
    data = quote(client, user)
    assert (data["price_kop"], data["promo_discount_kop"], data["payable_kop"]) == (60000, 15000, 45000)
    result = confirm(client, user, data)
    assert result.status_code == 200, result.text
    order, accepted = result.json()["order"], result.json()["consent"]["accepted"]
    assert order["status"] == "offered"
    assert order["promo_discount_kop"] == 15000 and accepted["payable_kop"] == 45000
    with Session(engine) as observer:
        saved = observer.get(InstantOrder, order["id"])
        assert saved.price_estimate * 100 - saved.promo_discount_kop == 45000


def test_unknown_pickup_not_misrepresented_as_fixed_price(client, user_factory, consent_market):
    set_base(450, pickup_per_km=10, pickup_free_km=0, pickup_max_rub=1000)
    user = user_factory("SyntheticPendingPickup")
    response = client.post(QUOTE, headers=user["auth"], json=_order_body())
    assert response.status_code == 409, response.text
    assert response.json()["detail"]["code"] == "taxi_quote_price_pending"
    assert not consent_market.redis.keys("taxi:initial-quote:v1:*") and not orders(user["id"])
    # The old mode continues to return its existing pending estimate.
    legacy = client.post("/instant/estimate", headers=user["auth"], json=_order_body())
    assert legacy.status_code == 200 and legacy.json()["pickup_pending"] is True


def test_known_quote_keeps_complete_pickup_when_fresh_is_pending(client, user_factory, consent_market):
    set_base(450, pickup_per_km=10, pickup_free_km=0, pickup_max_rub=1000)
    driver = _driver_online(client, user_factory, "SyntheticKnownPickup")
    _heartbeat(client, driver, coord=(52.64, 58.317))
    user = user_factory("SyntheticPickupDisappeared")
    data = quote(client, user)
    assert data["breakdown"]["pickup_fee_kop"] > 0
    assert client.post("/driver/online", headers=driver["auth"], json={"online": False}).status_code == 200
    result = confirm(client, user, data)
    assert result.status_code == 200, result.text
    accepted = result.json()["consent"]["accepted"]
    assert accepted["breakdown"] == data["breakdown"]
    assert accepted["payable_kop"] == data["payable_kop"]
    assert result.json()["order"]["pickup_pending"] is False


def test_accept_does_not_add_a_new_pickup_charge_to_confirmed_initial_fare(client, user_factory, consent_market):
    set_base(450, pickup_per_km=10, pickup_free_km=0, pickup_max_rub=1000)
    driver = _driver_online(client, user_factory, "SyntheticFixedPickup")
    _heartbeat(client, driver, coord=(52.64, 58.317))
    user = user_factory("SyntheticFixedPickupPassenger")
    data = quote(client, user)
    created = confirm(client, user, data)
    assert created.status_code == 200, created.text
    oid = created.json()["order"]["id"]
    set_base(450, pickup_per_km=1000, pickup_free_km=0, pickup_max_rub=10000)
    accepted = client.post(f"/instant/orders/{oid}/accept", headers=driver["auth"])
    assert accepted.status_code == 200, accepted.text
    assert accepted.json()["price_estimate"] * 100 == data["price_kop"]
    assert accepted.json()["pickup_fee_kop"] == data["breakdown"]["pickup_fee_kop"]


def test_legacy_and_scheduled_inputs_remain_separate(client, user_factory, consent_market):
    user = user_factory("SyntheticConsentCompatibility")
    future = _order_body(scheduled_at=(utcnow() + timedelta(hours=12)).isoformat() + "Z")
    assert client.post(QUOTE, headers=user["auth"], json=future).status_code == 422
    assert client.post(QUOTE, headers=user["auth"], json=_order_body(request_key=str(uuid4()))).status_code == 422
    data = quote(client, user)
    assert confirm(client, user, data, price=1).status_code == 422
    assert not orders(user["id"])
    assert client.post("/instant/estimate", headers=user["auth"], json=future).status_code == 200
    legacy = client.post("/instant/orders", headers=user["auth"], json=_order_body(quote_id="unknown"))
    assert legacy.status_code == 200  # Explicitly NOT the protected mode.


def test_old_binary_insert_can_omit_all_new_columns(client, user_factory, consent_market):
    user = user_factory("SyntheticOldBinary")
    response = client.post("/instant/orders", headers=user["auth"], json=_order_body())
    assert response.status_code == 200, response.text
    columns = [c.name for c in InstantOrder.__table__.columns if c.name != "id" and not c.name.startswith("consent_")]
    names = ",".join('"' + name + '"' for name in columns)
    # Real SQL deliberately omits every new column, as a rolling old server does.
    with Session(engine) as session:
        new_id = session.exec(text("INSERT INTO instantorder (" + names + ") SELECT " + names
                                  + " FROM instantorder WHERE id=:id RETURNING id")
                              .bindparams(id=response.json()["id"])).scalar_one()
        session.commit()
        saved = session.get(InstantOrder, new_id)
        assert saved.consent_request_key is None and saved.consent_snapshot_json is None


@pytest.mark.parametrize("same_key", [True, False])
def test_concurrent_admission_serializes_on_each_supported_database(
    client, user_factory, consent_market, monkeypatch, same_key,
):
    live_driver(client, user_factory)
    user = user_factory("SyntheticConsentConcurrent")
    a = quote(client, user)
    b = a if same_key else quote(client, user)
    barrier = threading.Barrier(2)
    original = isv.start_matching
    dispatches = []

    def observed(*args, **kwargs):
        dispatches.append(args[1].id)
        return original(*args, **kwargs)
    monkeypatch.setattr(isv, "start_matching", observed)

    def send(data):
        barrier.wait(10)
        return confirm(client, user, data)

    with ThreadPoolExecutor(max_workers=2) as pool:
        results = [f.result(20) for f in [pool.submit(send, a), pool.submit(send, b)]]
    assert sorted(r.status_code for r in results) == ([200, 200] if same_key else [200, 409]), [r.text for r in results]
    successful = [r.json()["order"]["id"] for r in results if r.status_code == 200]
    assert len(set(successful)) == len(dispatches) == len(orders(user["id"])) == 1


def test_stale_wait_worker_cannot_dispatch_replaced_order(client,user_factory,consent_market,monkeypatch):
 user=user_factory('SyntheticQueueReplacement')
 with Session(engine) as s:
  old=InstantOrder(passenger_id=user['id'],status=S.expired,wait_until=utcnow()+timedelta(minutes=20),searching_at=utcnow()-timedelta(hours=1),price_estimate=450,ride_price=450,from_lat=52.591,from_lng=58.317,to_lat=52.716,to_lng=58.664)
  s.add(old);s.commit();old_id=old.id
 live_driver(client,user_factory);live_driver(client,user_factory)
 data=quote(client,user);selected,release=threading.Event(),threading.Event();original=isv.start_matching
 def blocked(session,order,*args,**kwargs):
  if order.id==old_id:
   selected.set();assert release.wait(15),'replacement not completed'
  return original(session,order,*args,**kwargs)
 monkeypatch.setattr(isv,'start_matching',blocked)
 def worker():
  with Session(engine) as s:return taxi_worker.retry_waiting_orders(s)
 with ThreadPoolExecutor(max_workers=1) as pool:
  future=pool.submit(worker)
  try:
   assert selected.wait(10),'worker did not select prior wait'
   response=confirm(client,user,data);assert response.status_code==200,response.text
  finally:release.set()
  future.result(20)
 with Session(engine) as observer:
  active=observer.exec(select(InstantOrder).where(InstantOrder.passenger_id==user['id'],InstantOrder.status.in_(isv.LIVE_ORDER_STATUSES))).all()
  assert len(active)==1,'STALE_WAIT_DISPATCHED_AFTER_REPLACEMENT: '+str(len(active))
  assert observer.get(InstantOrder,old_id).status==S.cancelled
  assert observer.get(InstantOrder,old_id).wait_until is None
