"""An expired generation must not clean up or notify over its committed restart."""
import pytest
from sqlmodel import Session, select

from app import instant_service as isv
from app.db import engine
from app.models import InstantOrder, InstantOrderStatus as S, UserRole
from app.timeutil import utcnow
from test_taxi_status_never_resurrects import _order, fake_redis  # noqa: F401


@pytest.fixture(autouse=True)
def _cleanup_expiry_orders(client):
    """Leave no synthetic active order for unrelated worker tests in FULL."""
    with Session(engine) as session:
        existing = set(session.exec(select(InstantOrder.id)).all())
    yield
    with Session(engine) as session:
        for order in session.exec(select(InstantOrder)).all():
            if order.id not in existing:
                session.delete(order)
        session.commit()


@pytest.mark.parametrize("effect", ["tried", "expired_notification"])
@pytest.mark.usefixtures("fake_redis")
def test_expiry_postcommit_effects_cannot_touch_new_offered_generation(client, user_factory, monkeypatch, effect):
    passenger = user_factory("ExpiryGenerationPassenger")
    driver = user_factory("ExpiryGenerationDriver", role=UserRole.driver)
    oid = _order(passenger["id"], S.searching, searching_at=utcnow(), search_revision=10)
    offers, notifications, restarted = [], [], []
    monkeypatch.setattr(isv, "_push_offer", lambda session, snapshot: offers.append((snapshot.id, snapshot.search_revision, snapshot.current_offer_driver_id)))
    monkeypatch.setattr(isv, "push_bilingual", lambda *args, **kwargs: notifications.append(kwargs.get("data")))
    # Both generations use the real matcher and real UPDATE/SADD paths. Only
    # external candidate discovery is controlled: old empty, restarted has driver.
    monkeypatch.setattr(isv, "candidates", lambda redis, session, order, **kwargs:
                        [driver["id"]] if order.search_revision == 11 else [])
    redis = isv._redis()
    key = isv._tried_key(oid)
    try:
        with Session(engine) as old:
            real_commit = old.commit
            def restart_after_expiry_commit():
                real_commit()
                assert not restarted, "old expiry must commit exactly once"
                with Session(engine) as newer:
                    expired = newer.get(InstantOrder, oid)
                    assert expired.status == S.expired and expired.search_revision == 10
                    result = isv.start_matching(newer, expired)
                    assert result.status == S.offered and result.search_revision == 11
                    assert result.current_offer_driver_id == driver["id"]
                    assert redis.sismember(key, driver["id"]), "new matching must have actually recorded tried"
                    restarted.append(result.offer_token)
            monkeypatch.setattr(old, "commit", restart_after_expiry_commit)
            isv.try_offer_next(old, old.get(InstantOrder, oid))
        with Session(engine) as observer:
            saved = observer.get(InstantOrder, oid)
            assert saved.status == S.offered and saved.search_revision == 11
            assert saved.offer_token == restarted[0]
        assert offers == [(oid, 11, driver["id"])]
        if effect == "tried":
            assert redis.sismember(key, driver["id"]), "old expiry deleted the restarted generation's tried driver"
        else:
            assert notifications == [], "old expiry sent no-driver notification after new offer committed"
    finally:
        redis.delete(key)  # Only this test's order, never another test's Redis keys.


@pytest.mark.parametrize("notify", [True, False])
@pytest.mark.usefixtures("fake_redis")
def test_current_expired_generation_cleans_tried_and_honors_notify(client, user_factory, monkeypatch, notify):
    passenger = user_factory("CurrentExpiryPassenger")
    driver = user_factory("CurrentExpiryDriver", role=UserRole.driver)
    oid = _order(passenger["id"], S.searching, search_revision=20)
    redis = isv._redis()
    key = isv._tried_key(oid)
    redis.sadd(key, driver["id"])
    notifications = []
    monkeypatch.setattr(isv, "candidates", lambda *args, **kwargs: [])
    monkeypatch.setattr(isv, "push_bilingual", lambda *args, **kwargs: notifications.append(kwargs["data"]))
    try:
        with Session(engine) as session:
            result = isv.try_offer_next(session, session.get(InstantOrder, oid), notify=notify)
            assert result.status == S.expired and result.search_revision == 20
            assert result.expired_at is not None
            assert not redis.exists(key)
            assert notifications == ([{"type": "instant_status", "order_id": str(oid), "status": "expired"}] if notify else [])
    finally:
        redis.delete(key)


@pytest.mark.usefixtures("fake_redis")
def test_redis_cleanup_error_keeps_expiry_committed_and_session_usable(client, user_factory, monkeypatch):
    passenger = user_factory("ExpiryRedisFailurePassenger")
    driver = user_factory("ExpiryRedisFailureDriver", role=UserRole.driver)
    first = _order(passenger["id"], S.searching, search_revision=30)
    following = _order(passenger["id"], S.searching, search_revision=40)
    redis = isv._redis()
    keys = [isv._tried_key(first), isv._tried_key(following)]
    for key in keys:
        redis.sadd(key, driver["id"])
    real_delete = redis.delete
    deleted = []
    def fail_first_delete(key, *args):
        deleted.append(key)
        if key == keys[0]:
            raise RuntimeError("synthetic Redis cleanup failure")
        return real_delete(key, *args)
    monkeypatch.setattr(redis, "delete", fail_first_delete)
    monkeypatch.setattr(isv, "candidates", lambda *args, **kwargs: [])
    monkeypatch.setattr(isv, "push_bilingual", lambda *args, **kwargs: None)
    try:
        with Session(engine) as session:
            result = isv.try_offer_next(session, session.get(InstantOrder, first))
            # Worker-style consumption of returned ORM must remain valid after
            # cleanup rollback, and the same session must handle its next order.
            assert result.id == first and result.status == S.expired and result.search_revision == 30
            with Session(engine) as observer:
                assert observer.get(InstantOrder, first).status == S.expired
            next_result = isv.try_offer_next(session, session.get(InstantOrder, following))
            assert next_result.id == following and next_result.status == S.expired
            assert session.get(InstantOrder, first).status == S.expired
        assert deleted == keys
        assert redis.exists(keys[0]) and not redis.exists(keys[1])
    finally:
        real_delete(*keys)


@pytest.mark.usefixtures("fake_redis")
def test_losing_expiry_cas_has_no_cleanup_or_notification(client, user_factory, monkeypatch):
    passenger = user_factory("LosingExpiryPassenger")
    driver = user_factory("LosingExpiryDriver", role=UserRole.driver)
    oid = _order(passenger["id"], S.searching, search_revision=50)
    redis = isv._redis()
    key = isv._tried_key(oid)
    redis.sadd(key, driver["id"])
    effects = []
    monkeypatch.setattr(isv, "_cleanup_tried", lambda *args: effects.append("cleanup"))
    monkeypatch.setattr(isv, "push_bilingual", lambda *args, **kwargs: effects.append("notify"))
    def replace_during_lookup(*args, **kwargs):
        with Session(engine) as newer:
            current = newer.get(InstantOrder, oid)
            current.search_revision = 51
            newer.add(current)
            newer.commit()
        return []
    monkeypatch.setattr(isv, "candidates", replace_during_lookup)
    try:
        with Session(engine) as session:
            result = isv.try_offer_next(session, session.get(InstantOrder, oid))
            assert result.status == S.searching and result.search_revision == 51
        assert effects == []
        assert redis.sismember(key, driver["id"])
    finally:
        redis.delete(key)


@pytest.mark.usefixtures("fake_redis")
def test_missing_order_after_expiry_commit_has_no_cleanup_or_notification(client, user_factory, monkeypatch):
    passenger = user_factory("MissingExpiryPassenger")
    oid = _order(passenger["id"], S.searching, search_revision=60)
    effects = []
    monkeypatch.setattr(isv, "_cleanup_tried", lambda *args: effects.append("cleanup"))
    monkeypatch.setattr(isv, "push_bilingual", lambda *args, **kwargs: effects.append("notify"))
    with Session(engine) as session:
        order = session.get(InstantOrder, oid)
        expected = (order.searching_at, order.search_round, order.offer_token)
        real_commit = session.commit
        def delete_after_commit():
            real_commit()
            with Session(engine) as other:
                current = other.get(InstantOrder, oid)
                assert current.status == S.expired
                other.delete(current)
                other.commit()
        monkeypatch.setattr(session, "commit", delete_after_commit)
        result = isv._expire_no_drivers(session, order, expected_search=expected, expected_revision=60, expected_lifecycle=None)
        assert result is None
        assert session.get(InstantOrder, oid) is None
    assert effects == []


@pytest.mark.usefixtures("fake_redis")
def test_retry_worker_continues_next_order_after_expiry_cleanup_orm_expiration(client, user_factory, monkeypatch):
    from datetime import timedelta
    from app import taxi_worker
    passenger = user_factory("ExpiryWorkerPassenger")
    now = utcnow()
    first = _order(passenger["id"], S.expired, search_revision=70,
                   wait_until=now + timedelta(minutes=10), searching_at=now - timedelta(days=1))
    following = _order(passenger["id"], S.expired, search_revision=80,
                       wait_until=now + timedelta(minutes=10), searching_at=now - timedelta(days=1))
    redis = isv._redis()
    real_delete = redis.delete
    deleted = []
    def delete_with_first_failure(key, *args):
        deleted.append(key)
        if key == isv._tried_key(first):
            raise RuntimeError("synthetic Redis cleanup failure in worker")
        return real_delete(key, *args)
    monkeypatch.setattr(redis, "delete", delete_with_first_failure)
    monkeypatch.setattr(isv, "candidates", lambda *args, **kwargs: [])
    monkeypatch.setattr(isv, "push_bilingual", lambda *args, **kwargs: pytest.fail("notify=False worker emitted expiry push"))
    with Session(engine) as session:
        retried = taxi_worker.retry_waiting_orders(session)
        assert first in retried and following in retried
        for oid, revision in [(first, 71), (following, 81)]:
            saved = session.get(InstantOrder, oid)
            assert saved.status == S.expired and saved.search_revision == revision
            assert saved.retry_count == 1
    assert isv._tried_key(first) in deleted and isv._tried_key(following) in deleted
