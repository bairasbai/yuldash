"""Opt-in tried lifecycle isolation: executable regression before implementation."""
import pytest
from redis.exceptions import TimeoutError as RedisTimeoutError
from sqlmodel import Session

from app import instant_service as isv
from app.db import engine
from app.models import InstantOrder, InstantOrderStatus as S, UserRole
from test_taxi_status_never_resurrects import _order, fake_redis  # noqa: F401
from test_taxi_accept_cleanup_generation import _cleanup_accept_orders  # noqa: F401


def _captured_key(oid, lifecycle):
    # Preserve executable RED against the pre-protocol one-argument helper.
    return isv._tried_key(oid) if lifecycle is None else isv._tried_key(oid, lifecycle)


@pytest.mark.usefixtures("fake_redis")
def test_enabled_lifecycle_late_accept_delete_preserves_reassigned_tried(client, user_factory, monkeypatch):
    # Settings forbids unknown setattr before the config field exists. The test's
    # temporary instance dict entry works both before and after adding the field.
    monkeypatch.setitem(isv.settings.__dict__, "taxi_tried_lifecycle_enabled", True)
    passenger = user_factory("LifecycleWriterPassenger")
    first_driver = user_factory("LifecycleWriterFirst", role=UserRole.driver)
    next_driver = user_factory("LifecycleWriterNext", role=UserRole.driver)
    oid = _order(passenger["id"], S.created)
    redis = isv._redis()
    real_delete, queued, keys = redis.delete, [], []
    monkeypatch.setattr(isv, "_push_offer", lambda *args, **kwargs: None)
    monkeypatch.setattr(isv, "push_bilingual", lambda *args, **kwargs: None)
    monkeypatch.setattr("app.services.push_notification", lambda *args, **kwargs: None)
    monkeypatch.setattr(isv, "candidates", lambda redis, session, order, **kwargs:
                        [next_driver["id"] if order.reassigns else first_driver["id"]])
    try:
        with Session(engine) as session:
            offered = isv.start_matching(session, session.get(InstantOrder, oid))
            assert offered.status == S.offered and offered.current_offer_driver_id == first_driver["id"]
            first_lifecycle = offered.tried_lifecycle
            first_key = _captured_key(oid, first_lifecycle)
            keys.append(first_key)
            assert redis.sismember(first_key, first_driver["id"])
        def delayed_delete(*key_args):
            assert key_args == (first_key,)
            queued.append(key_args)
            raise RedisTimeoutError("synthetic delayed accept cleanup")
        monkeypatch.setattr(redis, "delete", delayed_delete)
        with Session(engine) as session:
            accepted = isv.transition(session, oid, isv.Actor.driver, S.accepted, first_driver["id"], idempotent=False)
            assert accepted.status == S.accepted
        assert queued == [(first_key,)]
        with Session(engine) as session:
            reassigned = isv.cancel_order(session, oid, isv.Actor.driver, first_driver["id"], "car_problem")
            assert reassigned.status == S.offered and reassigned.current_offer_driver_id == next_driver["id"]
            assert reassigned.reassigns == 1 and reassigned.search_revision == 2
            second_lifecycle = reassigned.tried_lifecycle
            second_key = _captured_key(oid, second_lifecycle)
            keys.append(second_key)
            assert redis.sismember(second_key, first_driver["id"])
            assert redis.sismember(second_key, next_driver["id"])
        # Complete precisely the old timed-out command after the new SADDs.
        real_delete(*queued.pop())
        assert redis.sismember(second_key, first_driver["id"]) and redis.sismember(second_key, next_driver["id"]), "old lifecycle DELETE erased reassigned tried history"
        assert first_lifecycle is not None and second_lifecycle is not None
        assert first_lifecycle != second_lifecycle and first_key != second_key
    finally:
        if keys:
            real_delete(*set(keys))


def _flag(monkeypatch, enabled):
    monkeypatch.setitem(isv.settings.__dict__, "taxi_tried_lifecycle_enabled", enabled)


def _valid(value):
    return isinstance(value, str) and len(value) == 32 and all(c in "0123456789abcdef" for c in value)


@pytest.mark.parametrize("enabled", [False, True])
@pytest.mark.parametrize("initial", [None, "a" * 32])
@pytest.mark.parametrize("status", [S.created, S.scheduled, S.expired, S.searching])
@pytest.mark.usefixtures("fake_redis")
def test_start_boundary_lifecycle_matrix(client, user_factory, monkeypatch, enabled, initial, status):
    _flag(monkeypatch, enabled)
    passenger = user_factory("LifecycleMatrixPassenger")
    oid = _order(passenger["id"], status, search_revision=10, tried_lifecycle=initial)
    monkeypatch.setattr(isv, "try_offer_next", lambda session, order, *args, **kwargs: order)
    with Session(engine) as session:
        result = isv.start_matching(session, session.get(InstantOrder, oid))
        if status == S.searching or (initial is None and not enabled):
            assert result.tried_lifecycle == initial
        else:
            assert _valid(result.tried_lifecycle) and result.tried_lifecycle != initial
        assert result.search_revision == 11 and result.status == S.searching


@pytest.mark.parametrize("enabled", [False, True])
@pytest.mark.parametrize("stale,actual", [(None, "b" * 32), ("a" * 32, None)])
@pytest.mark.usefixtures("fake_redis")
def test_start_lifecycle_decision_reads_database_not_stale_orm(client, user_factory, monkeypatch, enabled, stale, actual):
    _flag(monkeypatch, enabled)
    passenger = user_factory("LifecycleStalePassenger")
    oid = _order(passenger["id"], S.expired, search_revision=10, tried_lifecycle=stale)
    monkeypatch.setattr(isv, "try_offer_next", lambda session, order, *args, **kwargs: order)
    with Session(engine) as session:
        order = session.get(InstantOrder, oid)
        with Session(engine) as newer:
            current = newer.get(InstantOrder, oid)
            current.tried_lifecycle = actual
            newer.add(current)
            newer.commit()
        result = isv.start_matching(session, order)
        if actual is None and not enabled:
            assert result.tried_lifecycle is None
        else:
            assert _valid(result.tried_lifecycle) and result.tried_lifecycle != actual


@pytest.mark.parametrize("initial", [None, "a" * 32])
@pytest.mark.parametrize("kind", ["advance", "destination", "waypoints", "fallback"])
@pytest.mark.usefixtures("fake_redis")
def test_continuations_preserve_lifecycle_and_tried_history(client, user_factory, monkeypatch, initial, kind):
    from test_taxi_route_offer_ordering import _apply
    _flag(monkeypatch, True)
    passenger = user_factory("LifecycleContinuePassenger")
    driver = user_factory("LifecycleTriedDriver", role=UserRole.driver)
    oid = _order(passenger["id"], S.offered if kind == "advance" else S.searching,
                 category="comfort", search_revision=10, tried_lifecycle=initial)
    redis, key = isv._redis(), _captured_key(oid, initial)
    redis.sadd(key, driver["id"])
    monkeypatch.setattr(isv, "try_offer_next", lambda session, order, *args, **kwargs: order)
    monkeypatch.setattr(isv.settings, "car_class_min_drivers", 0)
    monkeypatch.setattr(isv, "_category_price", lambda *args: 100)
    try:
        with Session(engine) as session:
            order = session.get(InstantOrder, oid)
            if kind == "advance":
                isv.advance_after_no_accept(session, order)
            elif kind == "fallback":
                isv.add_fallback_category(session, order, "standard")
            else:
                _apply(session, order, kind)
            session.refresh(order)
            assert order.tried_lifecycle == initial and order.search_revision == 11
        assert redis.sismember(key, driver["id"])
    finally:
        redis.delete(key)


@pytest.mark.parametrize("revision", [None, 8])
@pytest.mark.usefixtures("fake_redis")
def test_active_null_matcher_retains_legacy_history_without_namespace_init(client, user_factory, monkeypatch, revision):
    _flag(monkeypatch, True)
    passenger = user_factory("LifecycleLegacyPassenger")
    prior = user_factory("LifecycleLegacyPrior", role=UserRole.driver)
    next_driver = user_factory("LifecycleLegacyNext", role=UserRole.driver)
    oid = _order(passenger["id"], S.searching, search_revision=revision, tried_lifecycle=None)
    redis, key = isv._redis(), isv._tried_key(oid)
    redis.sadd(key, prior["id"])
    def candidates(redis, session, order, exclude):
        assert prior["id"] in exclude and order.tried_lifecycle is None
        return [next_driver["id"]]
    monkeypatch.setattr(isv, "candidates", candidates)
    monkeypatch.setattr(isv, "_push_offer", lambda *args: None)
    try:
        with Session(engine) as session:
            result = isv.try_offer_next(session, session.get(InstantOrder, oid))
            assert result.status == S.offered and result.tried_lifecycle is None
        assert redis.sismember(key, prior["id"]) and redis.sismember(key, next_driver["id"])
    finally:
        redis.delete(key)


@pytest.mark.parametrize("initial,replacement", [(None, "a" * 32), ("a" * 32, None), ("a" * 32, "b" * 32)])
@pytest.mark.parametrize("has_candidate", [True, False])
@pytest.mark.usefixtures("fake_redis")
def test_exact_lifecycle_cas_loser_has_no_effects(client, user_factory, monkeypatch, initial, replacement, has_candidate):
    _flag(monkeypatch, True)
    passenger = user_factory("LifecycleCASPassenger")
    driver = user_factory("LifecycleCASDriver", role=UserRole.driver)
    oid = _order(passenger["id"], S.searching, search_revision=15, tried_lifecycle=initial)
    effects = []
    monkeypatch.setattr(isv._redis(), "sadd", lambda *args: effects.append("sadd"))
    monkeypatch.setattr(isv._redis(), "expire", lambda *args: effects.append("ttl"))
    monkeypatch.setattr(isv, "_cleanup_tried", lambda *args, **kwargs: effects.append("cleanup"))
    monkeypatch.setattr(isv, "_push_offer", lambda *args: effects.append("offer"))
    monkeypatch.setattr(isv, "push_bilingual", lambda *args, **kwargs: effects.append("push"))
    def candidates(*args, **kwargs):
        with Session(engine) as newer:
            current = newer.get(InstantOrder, oid)
            current.tried_lifecycle = replacement
            newer.add(current)
            newer.commit()
        return [driver["id"]] if has_candidate else []
    monkeypatch.setattr(isv, "candidates", candidates)
    with Session(engine) as session:
        result = isv.try_offer_next(session, session.get(InstantOrder, oid))
        assert result.status == S.searching and result.search_revision == 15
        assert result.tried_lifecycle == replacement
    assert effects == []


@pytest.mark.parametrize("invalid", ["", "A" * 32, "g" * 32, "a" * 31, "a" * 33, "../legacy"])
@pytest.mark.parametrize("entry,status", [("start", S.created), ("matcher", S.searching), ("reassign", S.cancelled)])
@pytest.mark.usefixtures("fake_redis")
def test_invalid_lifecycle_never_repaired_or_sent_to_redis(client, user_factory, monkeypatch, invalid, entry, status):
    from fastapi import HTTPException
    _flag(monkeypatch, True)
    passenger = user_factory("LifecycleInvalidPassenger")
    oid = _order(passenger["id"], status, search_revision=5, tried_lifecycle=invalid)
    effects = []
    redis = isv._redis()
    for method in ("smembers", "sadd", "expire", "delete"):
        monkeypatch.setattr(redis, method, lambda *args, **kwargs: effects.append("redis"))
    monkeypatch.setattr(isv, "candidates", lambda *args, **kwargs: effects.append("lookup") or [])
    monkeypatch.setattr(isv, "_notify_reassign", lambda *args: effects.append("notify"))
    with Session(engine) as session:
        try:
            order = session.get(InstantOrder, oid)
            if entry == "start":
                isv.start_matching(session, order)
            elif entry == "matcher":
                isv.try_offer_next(session, order)
            else:
                isv._reassign_after_driver_cancel(session, oid, None)
        except (ValueError, HTTPException):
            session.rollback()
    with Session(engine) as observer:
        saved = observer.get(InstantOrder, oid)
        assert (saved.status, saved.search_revision, saved.tried_lifecycle) == (status, 5, invalid)
    assert effects == []


@pytest.mark.parametrize("entry", ["accept", "cancel"])
@pytest.mark.usefixtures("fake_redis")
def test_invalid_lifecycle_does_not_undo_business_transition_or_delete_legacy_key(client, user_factory, monkeypatch, entry):
    _flag(monkeypatch, True)
    passenger = user_factory("InvalidBusinessPassenger")
    driver = user_factory("InvalidBusinessDriver", role=UserRole.driver)
    oid = _order(passenger["id"], S.offered, search_revision=5, tried_lifecycle="",
                 current_offer_driver_id=driver["id"], offer_token="a" * 32)
    effects = []
    monkeypatch.setattr(isv._redis(), "delete", lambda *args: effects.append("delete"))
    monkeypatch.setattr(isv, "push_bilingual", lambda *args, **kwargs: None)
    monkeypatch.setattr("app.services.push_notification", lambda *args, **kwargs: None)
    with Session(engine) as session:
        if entry == "accept":
            result = isv.transition(session, oid, isv.Actor.driver, S.accepted, driver["id"], idempotent=False)
        else:
            result = isv.cancel_order(session, oid, isv.Actor.passenger, passenger["id"], "changed_mind")
        assert result.status == (S.accepted if entry == "accept" else S.cancelled)
        assert result.tried_lifecycle == ""
    assert effects == []


@pytest.mark.usefixtures("fake_redis")
def test_captured_late_sadd_and_expire_only_address_old_namespace(client, user_factory, monkeypatch):
    _flag(monkeypatch, True)
    passenger = user_factory("LateWritePassenger")
    first_driver = user_factory("LateWriteFirst", role=UserRole.driver)
    next_driver = user_factory("LateWriteNext", role=UserRole.driver)
    oid = _order(passenger["id"], S.created)
    redis = isv._redis()
    real_sadd, real_expire = redis.sadd, redis.expire
    queued, capturing, keys = [], [True], []
    def delayed_sadd(*args):
        if capturing[0]:
            queued.append(("sadd", args))
            return 0
        return real_sadd(*args)
    def delayed_expire(*args):
        if capturing[0]:
            queued.append(("expire", args))
            return False
        return real_expire(*args)
    monkeypatch.setattr(redis, "sadd", delayed_sadd)
    monkeypatch.setattr(redis, "expire", delayed_expire)
    monkeypatch.setattr(isv, "candidates", lambda redis, session, order, **kwargs:
                        [next_driver["id"] if order.reassigns else first_driver["id"]])
    monkeypatch.setattr(isv, "_push_offer", lambda *args: None)
    monkeypatch.setattr(isv, "push_bilingual", lambda *args, **kwargs: None)
    monkeypatch.setattr("app.services.push_notification", lambda *args, **kwargs: None)
    try:
        with Session(engine) as session:
            offered = isv.start_matching(session, session.get(InstantOrder, oid))
            old_key = _captured_key(oid, offered.tried_lifecycle)
            keys.append(old_key)
        assert [kind for kind, args in queued] == ["sadd", "expire"]
        assert all(args[0] == old_key for kind, args in queued)
        capturing[0] = False
        with Session(engine) as session:
            isv.transition(session, oid, isv.Actor.driver, S.accepted, first_driver["id"], idempotent=False)
        with Session(engine) as session:
            offered = isv.cancel_order(session, oid, isv.Actor.driver, first_driver["id"], "car_problem")
            new_key = _captured_key(oid, offered.tried_lifecycle)
            keys.append(new_key)
        assert new_key != old_key
        members = redis.smembers(new_key)
        assert members == {str(first_driver["id"]), str(next_driver["id"])}
        real_expire(new_key, 90)  # A distinct observation sentinel, not a runtime TTL change.
        for kind, args in queued:
            (real_sadd if kind == "sadd" else real_expire)(*args)
        assert redis.smembers(new_key) == members
        assert 0 < redis.ttl(new_key) <= 90, "old EXPIRE must not reset current namespace TTL"
        assert redis.sismember(old_key, first_driver["id"])
        assert redis.ttl(old_key) > 3000
    finally:
        if keys:
            redis.delete(*set(keys))


@pytest.mark.parametrize("enabled", [False, True])
@pytest.mark.parametrize("initial", [None, "d" * 32])
@pytest.mark.usefixtures("fake_redis")
def test_reassign_rotates_at_new_cycle_and_adds_cancelled_driver_only_to_new_key(client, user_factory, monkeypatch, enabled, initial):
    _flag(monkeypatch, enabled)
    passenger = user_factory("ReassignLifecyclePassenger")
    driver = user_factory("ReassignLifecycleDriver", role=UserRole.driver)
    prior = user_factory("ReassignOldHistory", role=UserRole.driver)
    oid = _order(passenger["id"], S.cancelled, search_revision=15, tried_lifecycle=initial)
    redis = isv._redis()
    old_key = _captured_key(oid, initial)
    redis.sadd(old_key, prior["id"])
    keys = [old_key]
    monkeypatch.setattr(isv, "_notify_reassign", lambda *args: None)
    monkeypatch.setattr(isv, "try_offer_next", lambda session, order, *args, **kwargs: order)
    try:
        with Session(engine) as session:
            result = isv._reassign_after_driver_cancel(session, oid, driver["id"])
            if enabled or initial is not None:
                assert _valid(result.tried_lifecycle) and result.tried_lifecycle != initial
            else:
                assert result.tried_lifecycle is None
            key = _captured_key(oid, result.tried_lifecycle)
            keys.append(key)
            assert result.search_revision == 16
        assert redis.sismember(key, driver["id"])
        if key != old_key:
            assert not redis.sismember(key, prior["id"]), "new cycles must not copy old leftover history"
            assert redis.sismember(old_key, prior["id"])
    finally:
        redis.delete(*set(keys))


@pytest.mark.parametrize("initial", [None, "e" * 32])
@pytest.mark.parametrize("entry", ["expiry", "terminal_cancel"])
@pytest.mark.usefixtures("fake_redis")
def test_cleanup_uses_captured_namespace_not_legacy_fallback(client, user_factory, monkeypatch, initial, entry):
    passenger = user_factory("CleanupLifecyclePassenger")
    driver = user_factory("CleanupLifecycleDriver", role=UserRole.driver)
    oid = _order(passenger["id"], S.searching, search_revision=3, tried_lifecycle=initial)
    redis = isv._redis()
    target = _captured_key(oid, initial)
    other = _captured_key(oid, "f" * 32 if initial is None else None)
    redis.sadd(target, driver["id"])
    redis.sadd(other, driver["id"])
    monkeypatch.setattr(isv, "candidates", lambda *args, **kwargs: [])
    monkeypatch.setattr(isv, "push_bilingual", lambda *args, **kwargs: None)
    monkeypatch.setattr("app.services.push_notification", lambda *args, **kwargs: None)
    try:
        with Session(engine) as session:
            if entry == "expiry":
                result = isv.try_offer_next(session, session.get(InstantOrder, oid))
            else:
                result = isv.cancel_order(session, oid, isv.Actor.passenger, passenger["id"], "changed_mind")
            assert result.status == (S.expired if entry == "expiry" else S.cancelled)
        assert not redis.exists(target)
        assert redis.sismember(other, driver["id"])
    finally:
        redis.delete(target, other)


def test_lifecycle_feature_flag_schema_defaults_off():
    assert type(isv.settings).model_fields["taxi_tried_lifecycle_enabled"].default is False
