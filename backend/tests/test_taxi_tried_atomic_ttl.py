"""Tried TTL regressions plus an explicitly retained SQL/Redis boundary diagnostic.

The diagnostic PASS below means precommit Redis effects survived SQL rollback;
that is an observed limitation, not a distributed-atomicity guarantee.
"""
import pytest
from redis.exceptions import TimeoutError as RedisTimeoutError
from sqlmodel import Session

from app import instant_service as isv
from app.db import engine
from app.models import InstantOrder, InstantOrderStatus as S, UserRole
from test_taxi_status_never_resurrects import _order, fake_redis  # noqa: F401
from test_taxi_accept_cleanup_generation import _cleanup_accept_orders  # noqa: F401


@pytest.mark.parametrize("entry", ["matcher", "reassign"])
@pytest.mark.parametrize("lifecycle", [None, "a" * 32])
@pytest.mark.usefixtures("fake_redis")
def test_tried_membership_never_loses_ttl_when_standalone_expire_times_out(client, user_factory, monkeypatch, entry, lifecycle):
    passenger = user_factory("AtomicTTLPassenger")
    driver = user_factory("AtomicTTLDriver", role=UserRole.driver)
    oid = _order(passenger["id"], S.searching if entry == "matcher" else S.cancelled,
                 search_revision=10, tried_lifecycle=lifecycle)
    redis = isv._redis()
    expired_commands, keys = [], []
    def expire_transport_timeout(*args, **kwargs):
        expired_commands.append(args)
        raise RedisTimeoutError("synthetic standalone EXPIRE transport failure")
    monkeypatch.setattr(redis, "expire", expire_transport_timeout)
    monkeypatch.setattr(isv, "_push_offer", lambda *args: None)
    monkeypatch.setattr(isv, "_notify_reassign", lambda *args: None)
    monkeypatch.setattr(isv, "candidates", lambda *args, **kwargs: [driver["id"]])
    try:
        with Session(engine) as session:
            if entry == "matcher":
                result = isv.try_offer_next(session, session.get(InstantOrder, oid))
                assert result.status == S.offered and result.current_offer_driver_id == driver["id"]
            else:
                # Isolate the real reassign SADD/EXPIRE from a subsequent matcher.
                monkeypatch.setattr(isv, "try_offer_next", lambda session, order, *args, **kwargs: order)
                result = isv._reassign_after_driver_cancel(session, oid, driver["id"])
                assert result.status == S.searching and result.reassigns == 1
            key = isv._tried_key(oid, result.tried_lifecycle)
            keys.append(key)
            assert redis.sismember(key, driver["id"]), "actual writer must have recorded the driver"
        with Session(engine) as observer:
            committed = observer.get(InstantOrder, oid)
            assert committed.status == (S.offered if entry == "matcher" else S.searching)
        assert redis.ttl(key) >= 0, f"{entry}: SADD committed without TTL after EXPIRE failure; standalone calls={expired_commands}"
    finally:
        if keys:
            redis.delete(*keys)


@pytest.mark.parametrize("lifecycle", [None, "b" * 32])
@pytest.mark.usefixtures("fake_redis")
def test_known_boundary_precommit_tried_survives_real_sql_rollback(client, user_factory, monkeypatch, lifecycle):
    """Diagnostic: retained Redis member after DB rollback is NOT fixed by atomic TTL."""
    passenger = user_factory("AtomicTTLDBFailurePassenger")
    driver = user_factory("AtomicTTLDBFailureDriver", role=UserRole.driver)
    oid = _order(passenger["id"], S.searching, search_revision=20, tried_lifecycle=lifecycle)
    redis = isv._redis()
    key = isv._tried_key(oid, lifecycle)
    offered_notifications = []
    monkeypatch.setattr(isv, "candidates", lambda *args, **kwargs: [driver["id"]])
    monkeypatch.setattr(isv, "_push_offer", lambda *args: offered_notifications.append(True))
    try:
        with Session(engine) as session:
            def commit_failure():
                assert redis.sismember(key, driver["id"]), "failure must occur after actual Redis write"
                raise RuntimeError("synthetic SQL commit failure after tried write")
            monkeypatch.setattr(session, "commit", commit_failure)
            with pytest.raises(RuntimeError, match="synthetic SQL commit failure after tried write"):
                isv.try_offer_next(session, session.get(InstantOrder, oid))
            session.rollback()  # Real rollback of the executed offered UPDATE.
        with Session(engine) as observer:
            saved = observer.get(InstantOrder, oid)
            assert saved.status == S.searching and saved.search_revision == 20
            assert saved.current_offer_driver_id is None and saved.offer_token is None
        assert redis.sismember(key, driver["id"]), "diagnostic must observe the retained precommit Redis effect"
        assert 0 < redis.ttl(key) <= 3600
        assert not offered_notifications
        print("KNOWN LIMITATION observed: tried member survives SQL rollback despite having TTL")
    finally:
        redis.delete(key)


class _ObservedPipeline:
    """Delegate to fakeredis's real MULTI/EXEC while recording transport boundaries."""
    def __init__(self, inner, events, failure=None):
        self.inner, self.events, self.failure = inner, events, failure

    def __enter__(self):
        self.inner.__enter__()
        return self

    def __exit__(self, *args):
        return self.inner.__exit__(*args)

    def sadd(self, *args):
        self.events.append(("sadd", args))
        self.inner.sadd(*args)
        return self

    def expire(self, *args):
        self.events.append(("expire", args))
        self.inner.expire(*args)
        return self

    def execute(self, *args, **kwargs):
        self.events.append(("execute",))
        if self.failure == "before":
            raise RedisTimeoutError("synthetic failure before EXEC reached Redis")
        result = self.inner.execute(*args, **kwargs)
        if self.failure == "after":
            raise RedisTimeoutError("synthetic timeout after actual EXEC committed")
        return result


def _observe_pipeline(redis, monkeypatch, failure=None):
    events = []
    real_pipeline = redis.pipeline
    def pipeline(*args, **kwargs):
        assert not args and kwargs == {"transaction": True}
        events.append(("pipeline", True))
        return _ObservedPipeline(real_pipeline(*args, **kwargs), events, failure)
    monkeypatch.setattr(redis, "pipeline", pipeline)
    return events


@pytest.mark.parametrize("lifecycle", [None, "c" * 32])
@pytest.mark.usefixtures("fake_redis")
def test_remember_tried_queues_same_key_sadd_and_ttl_in_one_real_transaction(monkeypatch, lifecycle):
    redis = isv._redis()
    key = isv._tried_key(987001, lifecycle)
    events = _observe_pipeline(redis, monkeypatch)
    try:
        isv._remember_tried(redis, 987001, 123, lifecycle)
        assert events == [("pipeline", True), ("sadd", (key, 123)), ("expire", (key, 3600)), ("execute",)]
        assert redis.smembers(key) == {"123"}
        assert 3590 <= redis.ttl(key) <= 3600
    finally:
        redis.delete(key)


@pytest.mark.parametrize("invalid", ["", "A" * 32, "g" * 32, "a" * 31, "a" * 33])
@pytest.mark.usefixtures("fake_redis")
def test_remember_tried_invalid_lifecycle_never_opens_pipeline(monkeypatch, invalid):
    redis = isv._redis()
    monkeypatch.setattr(redis, "pipeline", lambda *args, **kwargs: pytest.fail("invalid lifecycle opened Redis pipeline"))
    isv._remember_tried(redis, 987002, 123, invalid)
    assert not redis.exists(isv._tried_key(987002)), "invalid lifecycle cannot fall back to legacy"


def test_remember_tried_none_client_is_best_effort_noop():
    isv._remember_tried(None, 987003, 123, None)


@pytest.mark.parametrize("failure", ["before", "after"])
@pytest.mark.parametrize("lifecycle", [None, "d" * 32])
@pytest.mark.usefixtures("fake_redis")
def test_remember_tried_exec_transport_failure_boundary(monkeypatch, failure, lifecycle):
    redis = isv._redis()
    key = isv._tried_key(987004, lifecycle)
    events = _observe_pipeline(redis, monkeypatch, failure)
    try:
        isv._remember_tried(redis, 987004, 123, lifecycle)
        assert [event[0] for event in events] == ["pipeline", "sadd", "expire", "execute"]
        if failure == "before":
            assert not redis.exists(key), "commands queued locally must not execute before EXEC"
        else:
            assert redis.smembers(key) == {"123"}
            assert 3590 <= redis.ttl(key) <= 3600, "lost ACK after actual EXEC must leave both membership and TTL"
    finally:
        redis.delete(key)


@pytest.mark.parametrize("lifecycle", [None, "e" * 32])
@pytest.mark.usefixtures("fake_redis")
def test_remember_tried_duplicate_member_refreshes_ttl(lifecycle):
    redis = isv._redis()
    key = isv._tried_key(987005, lifecycle)
    try:
        redis.sadd(key, 123)
        redis.expire(key, 30)
        assert redis.ttl(key) <= 30
        isv._remember_tried(redis, 987005, 123, lifecycle)
        assert redis.smembers(key) == {"123"}
        assert 3590 <= redis.ttl(key) <= 3600
    finally:
        redis.delete(key)


@pytest.mark.usefixtures("fake_redis")
def test_known_boundary_wrongtype_sadd_error_does_not_rollback_expire():
    """Diagnostic PASS observes Redis execution errors are not SQL-style rollback."""
    redis = isv._redis()
    key = isv._tried_key(987006, "f" * 32)
    try:
        redis.set(key, "synthetic-wrong-type")
        assert redis.ttl(key) == -1
        # Real fakeredis transaction raises a SADD WRONGTYPE response error;
        # best-effort helper swallows it, but Redis still executes EXPIRE.
        isv._remember_tried(redis, 987006, 123, "f" * 32)
        assert redis.type(key) == "string" and redis.get(key) == "synthetic-wrong-type"
        assert 3590 <= redis.ttl(key) <= 3600
        print("KNOWN LIMITATION observed: WRONGTYPE SADD failed but EXEC still applied EXPIRE")
    finally:
        redis.delete(key)
