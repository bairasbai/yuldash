"""A delayed accept must not erase tried or announce acceptance after reassign."""
from datetime import timedelta

import pytest
from sqlmodel import Session, select

from app import instant_service as isv
from app.db import engine
from app.models import DriverCancel, InstantOrder, InstantOrderStatus as S, UserRole, Tariff
from app.timeutil import utcnow
from test_taxi_status_never_resurrects import _order, fake_redis  # noqa: F401


@pytest.fixture(autouse=True)
def _cleanup_accept_orders(client):
    with Session(engine) as session:
        existing = set(session.exec(select(InstantOrder.id)).all())
        old_tariffs = set(session.exec(select(Tariff.id)).all())
    yield
    with Session(engine) as session:
        for order in session.exec(select(InstantOrder)).all():
            if order.id not in existing:
                for event in session.exec(select(DriverCancel).where(DriverCancel.order_id == order.id)).all():
                    session.delete(event)
                session.flush()
                session.delete(order)
        session.flush()
        for tariff in session.exec(select(Tariff)).all():
            if tariff.id not in old_tariffs:
                session.delete(tariff)
        session.commit()


@pytest.mark.parametrize("effect", ["tried", "accept_notification"])
@pytest.mark.usefixtures("fake_redis")
def test_old_accept_effects_do_not_touch_reassigned_offer(client, user_factory, monkeypatch, effect):
    passenger = user_factory("AcceptGenerationPassenger")
    old_driver = user_factory("AcceptGenerationOldDriver", role=UserRole.driver)
    new_driver = user_factory("AcceptGenerationNewDriver", role=UserRole.driver)
    oid = _order(passenger["id"], S.offered, search_revision=10,
                 current_offer_driver_id=old_driver["id"], offer_token="a" * 32,
                 offer_expires_at=utcnow() + timedelta(minutes=1))
    redis = isv._redis()
    key = isv._tried_key(oid)
    redis.sadd(key, old_driver["id"])
    offers, acceptance_notices, reassign_notices, pickup_notices, restarted = [], [], [], [], []
    monkeypatch.setattr(isv, "candidates", lambda *args, **kwargs: [new_driver["id"]])
    monkeypatch.setattr(isv, "_push_offer", lambda session, order, did: offers.append((order.search_revision, did)))
    monkeypatch.setattr(isv, "_notify_transition", lambda session, order, target: acceptance_notices.append(target))
    monkeypatch.setattr(isv, "_notify_reassign", lambda *args: reassign_notices.append(True))
    monkeypatch.setattr(isv, "_notify_pickup_fee", lambda *args: pickup_notices.append(True))
    try:
        with Session(engine) as old:
            real_commit = old.commit
            def cancel_and_reassign_after_commit():
                real_commit()
                assert not restarted
                with Session(engine) as newer:
                    accepted = newer.get(InstantOrder, oid)
                    assert accepted.status == S.accepted and accepted.driver_id == old_driver["id"]
                    result = isv.cancel_order(newer, oid, isv.Actor.driver, old_driver["id"], "car_problem")
                    assert result.status == S.offered and result.search_revision == 11
                    assert result.reassigns == 1 and result.current_offer_driver_id == new_driver["id"]
                    assert redis.sismember(key, old_driver["id"])
                    assert redis.sismember(key, new_driver["id"])
                    assert len(newer.exec(select(DriverCancel).where(DriverCancel.order_id == oid)).all()) == 1
                    restarted.append(result.offer_token)
            monkeypatch.setattr(old, "commit", cancel_and_reassign_after_commit)
            isv.transition(old, oid, isv.Actor.driver, S.accepted, old_driver["id"], idempotent=False)
        with Session(engine) as observer:
            saved = observer.get(InstantOrder, oid)
            assert saved.status == S.offered and saved.search_revision == 11
            assert saved.offer_token == restarted[0]
        assert offers == [(11, new_driver["id"])] and reassign_notices == [True]
        if effect == "tried":
            assert redis.sismember(key, old_driver["id"]) and redis.sismember(key, new_driver["id"]), "old accept erased reassign tried history"
        else:
            assert acceptance_notices == [] and pickup_notices == [], "old accept announced acceptance after reassignment"
    finally:
        redis.delete(key)


def _offer_fixture(user_factory):
    passenger = user_factory("AcceptCompatibilityPassenger")
    driver = user_factory("AcceptCompatibilityDriver", role=UserRole.driver)
    oid = _order(passenger["id"], S.offered, search_revision=20,
                 current_offer_driver_id=driver["id"], offer_token="b" * 32,
                 offer_expires_at=utcnow() + timedelta(minutes=1))
    isv._redis().sadd(isv._tried_key(oid), driver["id"])
    return oid, driver


def _record_accept_notices(monkeypatch):
    notices = []
    monkeypatch.setattr(isv, "_notify_transition", lambda session, order, target: notices.append(("transition", target)))
    monkeypatch.setattr(isv, "_notify_pickup_fee", lambda *args: notices.append(("pickup", None)))
    return notices


@pytest.mark.usefixtures("fake_redis")
@pytest.mark.parametrize("legacy", [False, True])
def test_normal_accept_clears_tried_and_notifies(client, user_factory, monkeypatch, legacy):
    oid, driver = _offer_fixture(user_factory)
    notices = []
    monkeypatch.setattr(isv, "push_bilingual", lambda *args, **kwargs: notices.append(kwargs["data"]))
    monkeypatch.setattr("app.services.push_notification", lambda *args, **kwargs: pytest.fail("unchanged pickup produced charge notice"))
    with Session(engine) as session:
        if legacy:
            order = session.get(InstantOrder, oid)
            order.search_revision = None
            order.offer_token = None
            session.add(order)
            session.commit()
        result = isv.transition(session, oid, isv.Actor.driver, S.accepted, driver["id"], idempotent=False)
        assert result.status == S.accepted and result.driver_id == driver["id"]
    assert not isv._redis().exists(isv._tried_key(oid))
    assert notices == [{"type": "instant_status", "order_id": str(oid), "status": "accepted"}]


@pytest.mark.parametrize("advanced", [S.arriving, S.onboard, S.done])
@pytest.mark.usefixtures("fake_redis")
def test_same_assignment_advanced_still_cleans_without_old_accept_notice(client, user_factory, monkeypatch, advanced):
    oid, driver = _offer_fixture(user_factory)
    notices = _record_accept_notices(monkeypatch)
    with Session(engine) as session:
        real_commit = session.commit
        def advance_after_commit():
            real_commit()
            with Session(engine) as other:
                order = other.get(InstantOrder, oid)
                assert order.status == S.accepted
                order.status = advanced
                setattr(order, f"{advanced.value}_at", utcnow())
                other.add(order)
                other.commit()
        monkeypatch.setattr(session, "commit", advance_after_commit)
        result = isv.transition(session, oid, isv.Actor.driver, S.accepted, driver["id"], idempotent=False)
        assert result.status == advanced and result.driver_id == driver["id"]
    assert not isv._redis().exists(isv._tried_key(oid))
    assert notices == []


@pytest.mark.parametrize("change", ["revision", "driver", "missing"])
@pytest.mark.usefixtures("fake_redis")
def test_changed_or_missing_assignment_skips_accept_effects(client, user_factory, monkeypatch, change):
    oid, driver = _offer_fixture(user_factory)
    another_driver = user_factory("ChangedAcceptDriver", role=UserRole.driver)
    notices = _record_accept_notices(monkeypatch)
    with Session(engine) as session:
        real_commit = session.commit
        def change_after_commit():
            real_commit()
            with Session(engine) as other:
                order = other.get(InstantOrder, oid)
                assert order.status == S.accepted
                if change == "missing":
                    other.delete(order)
                else:
                    if change == "revision":
                        order.search_revision += 1
                    else:
                        order.driver_id = another_driver["id"]
                    other.add(order)
                other.commit()
        monkeypatch.setattr(session, "commit", change_after_commit)
        result = isv.transition(session, oid, isv.Actor.driver, S.accepted, driver["id"], idempotent=False)
        if change == "missing":
            assert result is None
    assert isv._redis().sismember(isv._tried_key(oid), driver["id"])
    assert notices == []
    isv._redis().delete(isv._tried_key(oid))


@pytest.mark.usefixtures("fake_redis")
def test_redis_error_does_not_undo_accept_or_poison_session(client, user_factory, monkeypatch):
    oid, driver = _offer_fixture(user_factory)
    notices = _record_accept_notices(monkeypatch)
    redis = isv._redis()
    real_delete = redis.delete
    def redis_failure(*args):
        raise RuntimeError("synthetic Redis delete failure")
    monkeypatch.setattr(redis, "delete", redis_failure)
    try:
        with Session(engine) as session:
            result = isv.transition(session, oid, isv.Actor.driver, S.accepted, driver["id"], idempotent=False)
            assert result.status == S.accepted and result.driver_id == driver["id"]
            assert session.get(InstantOrder, oid).search_revision == 20
            with Session(engine) as observer:
                assert observer.get(InstantOrder, oid).status == S.accepted
        assert notices == [("transition", S.accepted), ("pickup", None)]
    finally:
        real_delete(isv._tried_key(oid))


@pytest.mark.usefixtures("fake_redis")
def test_accept_notifications_use_values_captured_before_read_lock_release(client, user_factory, monkeypatch):
    oid, driver = _offer_fixture(user_factory)
    observed, before, released, status_pushes = [], [], [], []
    with Session(engine) as setup:
        tariff = Tariff(zone="accept_snapshot_fixture", category="standard", base=70,
                        per_km=11.5, per_min=5.0, min_price=100,
                        pickup_free_km=3.0, pickup_per_km=11.5, pickup_max_rub=400)
        setup.add(tariff)
        setup.flush()
        order = setup.get(InstantOrder, oid)
        order.tariff_id = tariff.id
        order.pickup_pending = True
        setup.add(order)
        setup.commit()
    # Synthetic driver position; actual pickup formula and settlement run unchanged.
    isv._redis().geoadd(isv.PRESENCE_KEY, (58.317, 52.791, f"driver:{driver['id']}"))
    def values(order):
        return (order.id, order.passenger_id, order.from_text, order.to_text,
                order.pickup_fee_kop, order.pickup_km, order.price_estimate)
    monkeypatch.setattr(isv, "push_bilingual", lambda *args, **kwargs: status_pushes.append(kwargs["data"]))
    monkeypatch.setattr("app.services.push_notification", lambda *args, **kwargs: observed.append((args, kwargs)))
    real_cleanup = isv._cleanup_tried
    def record_accepted_values(order_id, lifecycle=None):
        with Session(engine) as observer:
            order = observer.get(InstantOrder, order_id)
            assert order.status == S.accepted
            before.append(values(order))
        real_cleanup(order_id, lifecycle)
    monkeypatch.setattr(isv, "_cleanup_tried", record_accepted_values)
    with Session(engine) as session:
        real_rollback = session.rollback
        def change_price_after_release():
            real_rollback()
            if released:
                return
            released.append(True)
            with Session(engine) as other:
                order = other.get(InstantOrder, oid)
                order.price_estimate += 1000
                order.pickup_fee_kop += 1000
                order.pickup_km = 999
                order.to_text = "Более позднее место"
                other.add(order)
                other.commit()
        monkeypatch.setattr(session, "rollback", change_price_after_release)
        isv.transition(session, oid, isv.Actor.driver, S.accepted, driver["id"], idempotent=False)
    assert released == [True], "guard must release its read transaction before notification"
    assert len(before) == 1 and before[0][4] > 0
    assert status_pushes == [{"type": "instant_status", "order_id": str(oid), "status": "accepted"}]
    assert len(observed) == 1
    args, kwargs = observed[0]
    assert args[1] == before[0][1] and kwargs["ref_id"] == oid
    assert f"Итого {before[0][-1]} ₽" in args[5]
    assert f"примерно {int(round(before[0][5]))} км" in args[5]
    assert f"добавилось {before[0][4] // 100} ₽" in args[5]
    assert "999" not in args[5]
    with Session(engine) as observer:
        assert observer.get(InstantOrder, oid).price_estimate == before[0][-1] + 1000
