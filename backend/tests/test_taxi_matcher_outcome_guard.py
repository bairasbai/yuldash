"""A search outcome that lost ownership must not change the order or announce it."""
from concurrent.futures import ThreadPoolExecutor
from threading import Event

import pytest
from sqlmodel import Session

from app import instant_service as isv
from app.db import engine
from app.models import InstantOrder, InstantOrderStatus as S
import test_instant
from test_instant import ORIG, _driver_online, _heartbeat, _offered_order

fake_redis = test_instant.fake_redis


def _row(oid):
    with Session(engine) as session:
        o = session.get(InstantOrder, oid)
        return (o.status, o.driver_id, o.current_offer_driver_id, o.offer_expires_at,
                o.cancelled_at, o.expired_at, o.search_round, o.offered_at)


@pytest.mark.parametrize("next_driver_exists", (True, False))
def test_real_decline_matcher_does_not_overwrite_cancellation_during_geo(
        client, user_factory, fake_redis, monkeypatch, next_driver_exists):
    old_driver, passenger, offered = _offered_order(
        client, user_factory, fake_redis, "MatcherGuardOld", "MatcherGuardPassenger")
    oid = offered["id"]
    url = f"/instant/orders/{oid}"
    tried_key = isv._tried_key(oid)
    if next_driver_exists:
        new_driver = _driver_online(client, user_factory, "MatcherGuardNext")
        _heartbeat(client, new_driver, ORIG)
    # Model a prior offer which committed before its already-tried Redis write.
    # Existing filtering must still exclude the old driver without mutating
    # Redis ahead of the outcome CAS.
    fake_redis.delete(tried_key)
    entered, proceed = Event(), Event()
    real_candidates = isv.candidates
    observations = []
    effects = []
    original_push = isv._push_offer
    original_cleanup = isv._cleanup_tried
    original_bilingual = isv.push_bilingual

    def pause(r, session, order, exclude):
        found = real_candidates(r, session, order, exclude=exclude)
        assert old_driver["id"] in exclude
        assert found == ([new_driver["id"]] if next_driver_exists else [])
        observations.append(fake_redis.smembers(tried_key))
        entered.set()
        assert proceed.wait(10)
        return found

    def push(*args, **kwargs):
        effects.append("offer")
        return original_push(*args, **kwargs)

    def clean(*args, **kwargs):
        effects.append("cleanup")
        return original_cleanup(*args, **kwargs)

    def bilingual(*args, **kwargs):
        if (kwargs.get("data") or {}).get("status") == "expired":
            effects.append("expired")
        return original_bilingual(*args, **kwargs)

    monkeypatch.setattr(isv, "candidates", pause)
    monkeypatch.setattr(isv, "_push_offer", push)
    monkeypatch.setattr(isv, "_cleanup_tried", clean)
    monkeypatch.setattr(isv, "push_bilingual", bilingual)
    with ThreadPoolExecutor(max_workers=1) as pool:
        decline = pool.submit(client.post, url + "/decline", headers=old_driver["auth"])
        try:
            assert entered.wait(10)
            assert _row(oid)[0] == S.searching
            cancelled = client.post(url + "/cancel", headers=passenger["auth"], json={})
            assert cancelled.status_code == 200, cancelled.text
            committed = _row(oid)
            assert committed[0] == S.cancelled
            assert effects == ["cleanup"]
        finally:
            proceed.set()
        result = decline.result(timeout=10)
    assert result.status_code == 200, result.text
    assert _row(oid) == committed
    assert result.json()["status"] == "cancelled"
    assert observations == [set()]
    assert not fake_redis.exists(tried_key)
    assert effects == ["cleanup"]


@pytest.mark.parametrize("next_driver_exists", (True, False))
def test_real_decline_matcher_emits_only_committed_offer_or_expiration(
        client, user_factory, fake_redis, monkeypatch, next_driver_exists):
    old_driver, passenger, offered = _offered_order(
        client, user_factory, fake_redis, "MatcherWinOld", "MatcherWinPassenger")
    oid = offered["id"]
    tried_key = isv._tried_key(oid)
    fake_redis.delete(tried_key)
    if next_driver_exists:
        new_driver = _driver_online(client, user_factory, "MatcherWinNext")
        _heartbeat(client, new_driver, ORIG)
    real_push, real_bilingual = isv._push_offer, isv.push_bilingual
    effects = []

    def push(session, order, driver_id):
        observed = _row(oid)
        assert observed[:3] == (S.offered, None, driver_id)
        effects.append(("offer", driver_id))
        return real_push(session, order, driver_id)

    def bilingual(*args, **kwargs):
        if (kwargs.get("data") or {}).get("status") == "expired":
            assert _row(oid)[0] == S.expired
            assert args[1] == passenger["id"]
            effects.append(("expired", args[1]))
        return real_bilingual(*args, **kwargs)

    monkeypatch.setattr(isv, "_push_offer", push)
    monkeypatch.setattr(isv, "push_bilingual", bilingual)
    response = client.post(f"/instant/orders/{oid}/decline", headers=old_driver["auth"])
    assert response.status_code == 200, response.text
    if next_driver_exists:
        assert response.json()["status"] == "offered"
        assert effects == [("offer", new_driver["id"])]
        assert fake_redis.smembers(tried_key) == {str(old_driver["id"]), str(new_driver["id"])}
        assert fake_redis.ttl(tried_key) > 0
    else:
        assert response.json()["status"] == "expired"
        assert effects == [("expired", passenger["id"])]
        assert not fake_redis.exists(tried_key)
