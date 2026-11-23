"""A committed price-confirmed taxi must reach the driver even if its HTTP worker stops."""
from datetime import timedelta

import pytest
from sqlalchemy import event
from sqlmodel import Session, select

from app import instant_service as isv, taxi_worker
from app.config import settings
from app.db import engine
from app.models import InstantOrder, InstantOrderStatus as S, LedgerEntry, PromoRedemption, TaxiCity
from app.routers import instant
from app.timeutil import utcnow
from test_taxi_quote_consent import consent_market as consent_market
from test_taxi_quote_consent import confirm, live_driver, orders, promo_user, quote, set_base
from test_taxi_quote_result import lookup


def _stop_after_commit(monkeypatch, client, passenger, price):
    def interrupted(*args, **kwargs):
        raise RuntimeError("synthetic stop after the order commit")

    with monkeypatch.context() as patch:
        patch.setattr(instant, "moderate_open_text", interrupted)
        with pytest.raises(RuntimeError, match="synthetic stop"):
            confirm(client, passenger, price)
    order, = orders(passenger["id"])
    assert order.status == S.created
    return order


def _worker_tick(dry_run=False):
    with Session(engine) as session:
        return taxi_worker.run_once(session, dry_run=dry_run)


@pytest.fixture
def recovery_worker(monkeypatch):
    # The suite shares a database. Other worker jobs may dispatch previous tests'
    # waiting/scheduled orders to this test's synthetic driver. Exercise the real
    # registered task through run_once without advancing those unrelated workflows.
    tasks = tuple(task for task in taxi_worker.ЗАДАЧИ if task[0] == "consent_dispatches")
    monkeypatch.setattr(taxi_worker, "ЗАДАЧИ", tasks)
    return _worker_tick


@pytest.fixture
def driver_offers(monkeypatch):
    notices = []

    def capture(session, driver_id, title, body, *, data=None, **kwargs):
        if data and data.get("type") == "instant_offer":
            notices.append((driver_id, int(data["order_id"])))

    monkeypatch.setattr(isv, "send_push", capture)
    return notices


def test_worker_recovers_confirmed_fare_after_http_process_stops(
    client, user_factory, consent_market, monkeypatch, driver_offers, recovery_worker,
):
    driver = live_driver(client, user_factory)
    passenger = promo_user(client, user_factory)
    set_base(600)
    price = quote(client, passenger)
    order = _stop_after_commit(monkeypatch, client, passenger, price)
    found = lookup(client, passenger, price["request_key"]).json()
    assert (found["state"], found["order_id"], found["initial_payable_kop"]) == ("committed", order.id, 45000)

    # Close new admission before repair; the committed order and replay still drain.
    monkeypatch.setattr(settings, "taxi_initial_consent_admission_enabled", False)
    # A market change between the crash and the worker must not reprice the existing order.
    set_base(900)
    assert order.id in recovery_worker(dry_run=True)["consent_dispatches"]
    assert orders(passenger["id"])[0].status == S.created
    assert driver_offers == []
    assert order.id in recovery_worker()["consent_dispatches"]
    assert order.id not in recovery_worker()["consent_dispatches"]
    recovered, = orders(passenger["id"])
    assert recovered.status == S.offered
    assert (recovered.id, recovered.current_offer_driver_id, recovered.search_round) == (order.id, driver["id"], 1)
    assert (recovered.price_estimate, recovered.promo_discount_kop) == (600, 15000)
    assert recovered.consent_snapshot_json == order.consent_snapshot_json
    assert driver_offers == [(driver["id"], order.id)]
    with Session(engine) as observer:
        promo = observer.exec(select(PromoRedemption).where(PromoRedemption.user_id == passenger["id"])).one()
        assert promo.used_order_id == order.id
        assert observer.exec(select(LedgerEntry).where(LedgerEntry.order_id == order.id)).all() == []
    response = client.get("/instant/driver/offer", headers=driver["auth"])
    assert response.status_code == 200 and response.json()["offer"]["id"] == order.id
    replay = confirm(client, passenger, price)
    assert replay.status_code == 200 and replay.json()["order"]["id"] == order.id
    assert replay.headers["cache-control"] == "no-store"
    conflict = confirm(client, passenger, price, comment="different synthetic intent")
    assert conflict.status_code == 409 and conflict.json()["detail"]["code"] == "taxi_consent_key_conflict"
    assert len(orders(passenger["id"])) == 1
    assert driver_offers == [(driver["id"], order.id)]


def test_worker_leaves_unconfirmed_and_cancelled_orders_alone(
    client, user_factory, consent_market, monkeypatch, driver_offers, recovery_worker,
):
    live_driver(client, user_factory)
    passenger = user_factory("SyntheticCancelled")
    price = quote(client, passenger)
    order = _stop_after_commit(monkeypatch, client, passenger, price)
    cancelled = client.post(f"/instant/orders/{order.id}/cancel", headers=passenger["auth"], json={})
    assert cancelled.status_code == 200, cancelled.text
    legacy_owner = user_factory("SyntheticLegacy")
    with Session(engine) as session:
        legacy = InstantOrder(passenger_id=legacy_owner["id"], status=S.created)
        session.add(legacy)
        session.commit()
    recovery_worker()
    assert orders(passenger["id"])[0].status == S.cancelled
    assert orders(legacy_owner["id"])[0].status == S.created
    assert driver_offers == []


@pytest.mark.parametrize("boundary", ["choose_driver", "before_offer_commit"])
def test_worker_recovers_after_first_search_transaction_fails(
    client, user_factory, consent_market, monkeypatch, driver_offers, recovery_worker, boundary,
):
    driver = live_driver(client, user_factory)
    passenger = user_factory("SyntheticDispatchRestart")
    price = quote(client, passenger)
    order = _stop_after_commit(monkeypatch, client, passenger, price)

    def interrupt(*args, **kwargs):
        raise RuntimeError("synthetic candidate selection interruption")

    def stop_after_offer_update(conn, cursor, statement, params, context, many):
        sql = statement.lower()
        if sql.startswith("update instantorder set") and "offer_expires_at=" in sql and "search_round=" in sql:
            raise RuntimeError("synthetic failure before offer commit")

    with monkeypatch.context() as patch:
        if boundary == "choose_driver":
            patch.setattr(isv, "candidates", interrupt)
        else:
            event.listen(engine, "after_cursor_execute", stop_after_offer_update)
        try:
            assert order.id not in recovery_worker()["consent_dispatches"]
        finally:
            if boundary == "before_offer_commit":
                event.remove(engine, "after_cursor_execute", stop_after_offer_update)
    assert driver_offers == []
    assert orders(passenger["id"])[0].search_round == 0
    assert not consent_market.redis.sismember(isv._tried_key(order.id), driver["id"])
    assert order.id in recovery_worker()["consent_dispatches"]
    assert driver_offers == [(driver["id"], order.id)]


def test_deadline_crossed_during_candidate_selection_expires_without_offer(
    client, user_factory, consent_market, monkeypatch, driver_offers, recovery_worker,
):
    driver = live_driver(client, user_factory)
    passenger = user_factory("SyntheticSlowSelection")
    price = quote(client, passenger)
    order = _stop_after_commit(monkeypatch, client, passenger, price)
    candidates = isv.candidates
    selected = []
    deadline = order.created_at + timedelta(seconds=settings.instant_max_offers * settings.instant_offer_ttl_sec)

    def slow_selection(*args, **kwargs):
        result = candidates(*args, **kwargs)
        selected.extend(result)
        monkeypatch.setattr(isv, "utcnow", lambda: deadline + timedelta(seconds=1))
        return result

    monkeypatch.setattr(isv, "candidates", slow_selection)
    recovery_worker()
    assert selected == [driver["id"]]
    saved, = orders(passenger["id"])
    assert saved.status == S.expired and saved.current_offer_driver_id is None
    assert saved.offered_at is None and saved.search_round == 0
    assert driver_offers == []


def test_driver_can_poll_committed_offer_when_push_is_interrupted(
    client, user_factory, consent_market, monkeypatch, driver_offers, recovery_worker,
):
    driver = live_driver(client, user_factory)
    passenger = user_factory("SyntheticOfferPushStop")
    price = quote(client, passenger)
    order = _stop_after_commit(monkeypatch, client, passenger, price)
    interrupted = []

    def lost_push(session, driver_id, title, body, *, data, **kwargs):
        interrupted.append((driver_id, int(data["order_id"])))
        raise RuntimeError("synthetic stop after offer commit, before push delivery")

    with monkeypatch.context() as patch:
        patch.setattr(isv, "send_push", lost_push)
        recovery_worker()
    assert interrupted == [(driver["id"], order.id)]
    assert order.id not in recovery_worker()["consent_dispatches"]
    assert driver_offers == []
    card = client.get("/instant/driver/offer", headers=driver["auth"])
    assert card.status_code == 200 and card.json()["offer"]["id"] == order.id
    assert orders(passenger["id"])[0].search_round == 1


def test_historical_first_search_is_recoverable_but_old_or_previous_rounds_are_not(
    client, user_factory, consent_market, monkeypatch, driver_offers, recovery_worker,
):
    driver = live_driver(client, user_factory)
    now = utcnow()
    cases = [
        ("First", {}),
        ("Offered", {"offered_at": now - timedelta(minutes=2)}),
        ("Retrying", {"retry_count": 1}),
        ("Waiting", {"wait_until": now + timedelta(minutes=5)}),
    ]
    ids = []
    for name, fields in cases:
        passenger = user_factory("SyntheticSearch" + name)
        price = quote(client, passenger)
        order = _stop_after_commit(monkeypatch, client, passenger, price)
        with Session(engine) as session:
            row = session.get(InstantOrder, order.id)
            row.status = S.searching
            row.searching_at = fields.get("created_at", now)
            for field, value in fields.items():
                setattr(row, field, value)
            session.commit()
        ids.append(order.id)
    # The old process could mark the candidate in Redis before its offer transaction.
    consent_market.redis.sadd(isv._tried_key(ids[0]), driver["id"])
    selected = recovery_worker(dry_run=True)["consent_dispatches"]
    assert set(selected).intersection(ids) == {ids[0]}
    recovered = recovery_worker()["consent_dispatches"]
    assert set(recovered).intersection(ids) == {ids[0]}
    assert driver_offers == [(driver["id"], ids[0])]
    with Session(engine) as observer:
        assert all(observer.get(InstantOrder, oid).status == S.searching for oid in ids[1:])
        # The session-scoped test database is shared with the waiting-queue suite.
        # Retire these synthetic orders after checking that recovery skipped them.
        for oid in ids[1:]:
            row = observer.get(InstantOrder, oid)
            row.status = S.cancelled
            row.wait_until = None
        observer.commit()


@pytest.mark.parametrize("reason", ["elapsed", "global", "city"])
def test_worker_cannot_send_a_late_or_disabled_ride(
    client, user_factory, consent_market, monkeypatch, driver_offers, recovery_worker, reason,
):
    live_driver(client, user_factory)
    passenger = promo_user(client, user_factory)
    set_base(600)
    price = quote(client, passenger)
    order = _stop_after_commit(monkeypatch, client, passenger, price)
    city_id = None
    with Session(engine) as session:
        if reason == "elapsed":
            row = session.get(InstantOrder, order.id)
            row.created_at = utcnow() - timedelta(minutes=30)
        elif reason == "global":
            monkeypatch.setattr(settings, "taxi_enabled", False)
        else:
            city = TaxiCity(city="Баймак", enabled=False)
            session.add(city)
            session.flush()
            city_id = city.id
        session.commit()
    try:
        recovery_worker()
        final = orders(passenger["id"])[0]
        assert final.status == S.expired and final.current_offer_driver_id is None
        assert driver_offers == []
    finally:
        if reason == "global":
            monkeypatch.setattr(settings, "taxi_enabled", True)
        if city_id is not None:
            with Session(engine) as session:
                session.delete(session.get(TaxiCity, city_id))
                session.commit()
    # An expired order's discount is available for the next explicitly confirmed ride.
    next_price = quote(client, passenger)
    assert next_price["promo_discount_kop"] == 15000
    response = confirm(client, passenger, next_price)
    assert response.status_code == 200, response.text
    replacement = response.json()["order"]
    assert replacement["id"] != order.id and replacement["promo_discount_kop"] == 15000
    with Session(engine) as session:
        promo = session.exec(select(PromoRedemption).where(PromoRedemption.user_id == passenger["id"])).one()
        assert promo.used_order_id == replacement["id"]
    assert lookup(client, passenger, price["request_key"]).json()["initial_payable_kop"] == 45000
