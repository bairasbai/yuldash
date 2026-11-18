"""Pre-accept route changes must be committed before any replacement offer."""
import pytest
from sqlmodel import Session

from app import instant_service as isv
from app.db import engine
from app.models import InstantOrder, InstantOrderStatus as S
from test_instant import _offered_order
from test_taxi_offer_token import fake_redis  # noqa: F401


@pytest.mark.parametrize("kind", ["destination", "waypoints"])
@pytest.mark.usefixtures("fake_redis")
def test_replacement_offer_contains_committed_new_route(client, user_factory, monkeypatch, kind):
    driver, passenger, payload = _offered_order(client, user_factory, isv._redis(), "RouteOrderDriver", "RouteOrderPax")
    oid = payload["id"]
    old_token = payload["offer_token"]
    seen = []
    clamped = []
    real_reclamp = isv.promo_ride.reclamp
    def reclamp_before_offer(session, order, **kwargs):
        result = real_reclamp(session, order, **kwargs)
        clamped.append(True)
        return result
    monkeypatch.setattr(isv.promo_ride, "reclamp", reclamp_before_offer)
    with Session(engine) as session:
        current = session.get(InstantOrder, oid)
        current.search_round = 3
        if kind == "waypoints":
            current.waypoints_json = isv.dump_waypoints([{"lat": 52.592, "lng": 58.318, "text": "Пройденная", "done": True}])
        session.add(current)
        session.commit()
    monkeypatch.setattr(isv, "candidates", lambda *args, **kwargs: [driver["id"]])

    def observe_push(session, order):
        assert clamped, "replacement must follow reclamp"
        with Session(engine) as observer:
            fresh = observer.get(InstantOrder, oid)
            seen.append((fresh.to_lat, fresh.to_lng, fresh.to_text,
                         isv.parse_waypoints(fresh.waypoints_json), fresh.offer_token,
                         fresh.price_estimate, fresh.search_round))

    monkeypatch.setattr(isv, "_push_offer", observe_push)
    if kind == "destination":
        body = {"to_lat": 52.60, "to_lng": 58.33, "to_text": "Новое место"}
    else:
        body = {"waypoints": [{"lat": 52.595, "lng": 58.32, "text": "Новая остановка"}]}
    response = client.post(f"/instant/orders/{oid}/{kind}", headers=passenger["auth"], json=body)
    assert response.status_code == 200, response.text
    assert len(seen) == 1, "route update must publish one replacement, not retain the old offer"
    if kind == "destination":
        assert seen[0][:3] == (body["to_lat"], body["to_lng"], body["to_text"]), "push preceded destination commit"
    else:
        assert seen[0][3][-1]["text"] == "Новая остановка", "push preceded waypoint commit"
    if kind == "waypoints":
        assert seen[0][3][0]["done"] is True
        assert seen[0][3][0]["text"] == "Пройденная"
    assert seen[0][6] == 4, "route change must not reset search round"
    assert seen[0][4] != old_token
    assert seen[0][5] == response.json()["order"]["price_estimate"]
    with Session(engine) as session:
        order = session.get(InstantOrder, oid)
        assert order.status == S.offered
        assert order.offer_token == seen[0][4]


NEW_TO = (52.60, 58.33)
POINTS = [{"lat": 52.595, "lng": 58.32, "text": "Новая остановка", "done": False}]


def _quote(session, order, kind):
    return isv.destination_quote(session, order, NEW_TO if kind == "destination" else (order.to_lat, order.to_lng),
                                 waypoints_override=POINTS if kind == "waypoints" else None)


def _apply(session, order, kind, quote=None):
    quote = quote or _quote(session, order, kind)
    assert quote["ok"]
    if kind == "destination":
        return isv.apply_destination(session, order, NEW_TO, "Новое место", quote)
    return isv.apply_waypoints(session, order, POINTS, quote)


@pytest.mark.parametrize("kind", ["destination", "waypoints"])
@pytest.mark.parametrize("has_candidate", [True, False])
@pytest.mark.usefixtures("fake_redis")
def test_changed_search_owns_continuation_and_old_lookup_loses(client, user_factory, monkeypatch, kind, has_candidate):
    from test_taxi_status_never_resurrects import _order
    from app.models import UserRole
    from app.timeutil import utcnow
    passenger = user_factory("RouteSearchPax")
    driver = user_factory("RouteSearchDriver", role=UserRole.driver)
    fixed = utcnow()
    monkeypatch.setattr(isv, "utcnow", lambda: fixed)
    oid = _order(passenger["id"], S.searching, searching_at=fixed, search_round=2)
    writer = isv.try_offer_next
    effects = []
    monkeypatch.setattr(isv, "_push_offer", lambda *args: effects.append("offer"))
    monkeypatch.setattr(isv, "push_bilingual", lambda *args, **kwargs: effects.append("expired"))
    monkeypatch.setattr(isv, "_cleanup_tried", lambda *args: effects.append("cleanup"))

    def update_during_lookup(*args, **kwargs):
        with Session(engine) as newer:
            with monkeypatch.context() as pause:
                pause.setattr(isv, "try_offer_next", lambda session, order: order)
                changed = _apply(newer, newer.get(InstantOrder, oid), kind)
                assert changed.status == S.searching
                assert changed.search_round == 2
                assert changed.offer_token is not None
        return [driver["id"]] if has_candidate else []

    monkeypatch.setattr(isv, "candidates", update_during_lookup)
    with Session(engine) as old:
        result = writer(old, old.get(InstantOrder, oid))
        assert result.status == S.searching
        assert result.search_round == 2
    assert not effects
    assert not isv._redis().sismember(isv._tried_key(oid), driver["id"])


@pytest.mark.parametrize("kind", ["destination", "waypoints"])
@pytest.mark.parametrize("target", [S.accepted, S.cancelled])
@pytest.mark.usefixtures("fake_redis")
def test_stale_helper_does_not_apply_quote_after_state_transition(client, user_factory, monkeypatch, kind, target):
    from fastapi import HTTPException
    driver, passenger, payload = _offered_order(client, user_factory, isv._redis(), "StaleRouteDriver", "StaleRoutePax")
    oid = payload["id"]
    with Session(engine) as old:
        stale = old.get(InstantOrder, oid)
        quote = _quote(old, stale, kind)
        with Session(engine) as other:
            if target == S.accepted:
                isv.transition(other, oid, isv.Actor.driver, target, driver["id"])
            else:
                isv.cancel_order(other, oid, isv.Actor.passenger, passenger["id"], "changed_mind")
        with Session(engine) as observer:
            saved = observer.get(InstantOrder, oid)
            before = (saved.status, saved.to_lat, saved.to_lng, saved.waypoints_json, saved.price_estimate)
        with pytest.raises(HTTPException) as error:
            _apply(old, stale, kind, quote)
        assert error.value.status_code == 409
    with Session(engine) as observer:
        saved = observer.get(InstantOrder, oid)
        assert (saved.status, saved.to_lat, saved.to_lng, saved.waypoints_json, saved.price_estimate) == before


@pytest.mark.parametrize("kind", ["destination", "waypoints"])
@pytest.mark.usefixtures("fake_redis")
def test_superseded_continuation_does_not_adopt_new_owner(client, user_factory, monkeypatch, kind):
    _, _, payload = _offered_order(client, user_factory, isv._redis(), "OwnerRouteDriver", "OwnerRoutePax")
    oid = payload["id"]
    changed_token = "c" * 32
    monkeypatch.setattr(isv, "try_offer_next", lambda *args: pytest.fail("old continuation adopted newer owner"))
    with Session(engine) as session:
        real_commit = session.commit
        def replace_owner_after_commit():
            real_commit()
            with Session(engine) as other:
                current = other.get(InstantOrder, oid)
                assert current.status == S.searching
                current.offer_token = changed_token
                other.add(current)
                other.commit()
        monkeypatch.setattr(session, "commit", replace_owner_after_commit)
        result = _apply(session, session.get(InstantOrder, oid), kind)
        assert result.status == S.searching
        assert result.offer_token == changed_token


@pytest.mark.parametrize("kind", ["destination", "waypoints"])
@pytest.mark.usefixtures("fake_redis")
def test_failed_commit_does_not_publish_or_change_route(client, user_factory, monkeypatch, kind):
    _, _, payload = _offered_order(client, user_factory, isv._redis(), "RollbackRouteDriver", "RollbackRoutePax")
    oid = payload["id"]
    monkeypatch.setattr(isv, "try_offer_next", lambda *args: pytest.fail("matching after failed commit"))
    with Session(engine) as session:
        order = session.get(InstantOrder, oid)
        before = (order.status, order.offer_token, order.to_lat, order.to_lng, order.waypoints_json, order.price_estimate)
        with monkeypatch.context() as failure:
            failure.setattr(session, "commit", lambda: (_ for _ in ()).throw(RuntimeError("fixture commit failure")))
            with pytest.raises(RuntimeError, match="fixture commit failure"):
                _apply(session, order, kind)
        session.rollback()
    with Session(engine) as observer:
        order = observer.get(InstantOrder, oid)
        assert (order.status, order.offer_token, order.to_lat, order.to_lng, order.waypoints_json, order.price_estimate) == before


@pytest.mark.parametrize("kind", ["destination", "waypoints"])
@pytest.mark.usefixtures("fake_redis")
def test_created_route_update_does_not_start_unrequested_matching(client, user_factory, monkeypatch, kind):
    from test_taxi_status_never_resurrects import _order
    passenger = user_factory("CreatedRoutePax")
    oid = _order(passenger["id"], S.created)
    monkeypatch.setattr(isv, "try_offer_next", lambda *args: pytest.fail("created route started matching"))
    with Session(engine) as session:
        result = _apply(session, session.get(InstantOrder, oid), kind)
        assert result.status == S.created
        assert result.offer_token is None
        assert result.search_round == 0


@pytest.mark.parametrize("kind", ["destination", "waypoints"])
@pytest.mark.usefixtures("fake_redis")
def test_route_search_is_not_published_before_reclamp(client, user_factory, monkeypatch, kind):
    _, _, payload = _offered_order(client, user_factory, isv._redis(), "AtomicRouteDriver", "AtomicRoutePax")
    oid = payload["id"]
    with Session(engine) as observer:
        saved = observer.get(InstantOrder, oid)
        before = (saved.status, saved.to_lat, saved.to_lng, saved.waypoints_json, saved.price_estimate, saved.promo_discount_kop)
    real_reclamp = isv.promo_ride.reclamp

    def observe_before_reclamp(session, order, **kwargs):
        with Session(engine) as observer:
            saved = observer.get(InstantOrder, oid)
            visible = (saved.status, saved.to_lat, saved.to_lng, saved.waypoints_json, saved.price_estimate, saved.promo_discount_kop)
            assert visible == before, "a new matcher can observe changed route before discount reclamp"
        return real_reclamp(session, order, **kwargs)

    monkeypatch.setattr(isv.promo_ride, "reclamp", observe_before_reclamp)
    monkeypatch.setattr(isv, "candidates", lambda *args, **kwargs: [])
    with Session(engine) as session:
        _apply(session, session.get(InstantOrder, oid), kind)


@pytest.mark.parametrize("finish", ["commit", "rollback", "default"])
@pytest.mark.usefixtures("fake_redis")
def test_reclamp_can_join_route_transaction_without_changing_default(client, user_factory, finish):
    _, _, payload = _offered_order(client, user_factory, isv._redis(), "ClampTransactionDriver", "ClampTransactionPax")
    oid = payload["id"]
    with Session(engine) as setup:
        order = setup.get(InstantOrder, oid)
        order.promo_discount_kop = 100000
        isv.set_ride_price(order, 100, 100)
        setup.add(order)
        setup.commit()
    with Session(engine) as session:
        order = session.get(InstantOrder, oid)
        if finish == "default":
            capped = isv.promo_ride.reclamp(session, order)
        else:
            capped = isv.promo_ride.reclamp(session, order, commit=False)
        assert capped < 100000
        with Session(engine) as observer:
            assert observer.get(InstantOrder, oid).promo_discount_kop == (capped if finish == "default" else 100000)
        if finish == "commit":
            session.commit()
        elif finish == "rollback":
            session.rollback()
    with Session(engine) as observer:
        assert observer.get(InstantOrder, oid).promo_discount_kop == (100000 if finish == "rollback" else capped)


@pytest.mark.usefixtures("fake_redis")
def test_stale_pending_destination_cannot_replace_new_pending_request(client, user_factory):
    from fastapi import HTTPException
    driver, _, payload = _offered_order(client, user_factory, isv._redis(), "PendingRouteDriver", "PendingRoutePax")
    oid = payload["id"]
    with Session(engine) as session:
        isv.transition(session, oid, isv.Actor.driver, S.accepted, driver["id"])
        order = session.get(InstantOrder, oid)
        order.pending_to_lat, order.pending_to_lng = NEW_TO
        order.pending_to_text = "Первое предложение"
        session.add(order)
        session.commit()
    with Session(engine) as stale_session:
        stale = stale_session.get(InstantOrder, oid)
        quote = _quote(stale_session, stale, "destination")
        with Session(engine) as other:
            newer = other.get(InstantOrder, oid)
            newer.pending_to_text = "Новое предложение"
            other.add(newer)
            other.commit()
        with pytest.raises(HTTPException) as error:
            _apply(stale_session, stale, "destination", quote)
        assert error.value.status_code == 409
    with Session(engine) as observer:
        assert observer.get(InstantOrder, oid).pending_to_text == "Новое предложение"
