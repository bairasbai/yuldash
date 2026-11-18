"""Owned frozen offer dispatch: final guard is a checkpoint, not delivery atomicity."""
from dataclasses import FrozenInstanceError, fields
from datetime import timedelta

import pytest
from sqlmodel import Session

from app import instant_service as isv, debt, compensation
from app.db import engine
from app.models import InstantOrder, InstantOrderStatus as S, UserRole
from app.timeutil import utcnow
from test_taxi_status_never_resurrects import _order, fake_redis  # noqa: F401
from test_taxi_accept_cleanup_generation import _cleanup_accept_orders  # noqa: F401


def _prepare_offered_snapshot(session, order, driver_id):
    """Adapt old helper fixtures to a real committed offered row, not an ORM bypass."""
    order.status = S.offered
    order.current_offer_driver_id = driver_id
    session.add(order)
    session.commit()
    snapshot = isv._capture_offer_snapshot(session, order.id, driver_id)
    assert snapshot is not None
    return snapshot


@pytest.fixture
def frozen_offer(user_factory):
    passenger = user_factory("FrozenOfferPassenger")
    driver = user_factory("FrozenOfferDriver", role=UserRole.driver)
    oid = _order(passenger["id"], S.offered, search_revision=10,
                 current_offer_driver_id=driver["id"], offer_token="a" * 32,
                 offer_expires_at=utcnow() + timedelta(minutes=10),
                 from_text="ул. 8 Марта, 12", to_text="ул. Гагарина, 5, кв. 9",
                 price_estimate=500, price_final=490,
                 pickup_fee_kop=1000, options_fee_kop=2000, weather_fee_kop=3000)
    with Session(engine) as session:
        snapshot = isv._capture_offer_snapshot(session, oid, driver["id"])
        assert snapshot is not None
    return oid, passenger, driver, snapshot


def _transport(monkeypatch):
    sent = []
    monkeypatch.setattr(isv, "send_push", lambda session, uid, title, body, **kwargs:
                        sent.append((uid, title, body, dict(kwargs["data"]), kwargs["data_only"])))
    return sent


def test_snapshot_covers_compensation_fields_and_uses_actual_money_formula(client, frozen_offer, monkeypatch):
    oid, passenger, driver, snapshot = frozen_offer
    assert set(compensation.COMPENSATION_FIELDS) <= {field.name for field in fields(snapshot)}
    with pytest.raises(FrozenInstanceError):
        snapshot.price_estimate = 999
    sent = _transport(monkeypatch)
    with Session(engine) as session:
        order = session.get(InstantOrder, oid)
        for field in compensation.COMPENSATION_FIELDS:
            assert getattr(snapshot, field) == getattr(order, field) != 0
        percent = debt.driver_fee_percent(session, driver["id"], order.created_at)
        expected_fee = debt.order_commission_kop(order, percent)
        isv._push_offer(session, snapshot)
    assert len(sent) == 1
    recipient, title, body, data, data_only = sent[0]
    assert recipient == driver["id"] and data_only is True
    assert data["price"] == "500" and data["gross_kop"] == "50000"
    assert data["fee_kop"] == str(expected_fee)
    assert data["net_kop"] == str(50000 - expected_fee)
    assert data["from"] == "ул. 8 Марта" and data["to"] == "ул. Гагарина"
    assert data["offer_token"] == "a" * 32
    assert all(isinstance(value, str) for value in data.values())


@pytest.mark.parametrize("change", ["recipient", "token", "expiry", "revision", "lifecycle", "cancel", "missing"])
def test_final_guard_after_real_stats_suppresses_changed_assignment(client, frozen_offer, user_factory, monkeypatch, change):
    oid, passenger, driver, snapshot = frozen_offer
    another = user_factory("FrozenOfferOtherDriver", role=UserRole.driver)
    sent = _transport(monkeypatch)
    real_stats = isv.passenger_stats
    def changing_stats(*args, **kwargs):
        result = real_stats(*args, **kwargs)
        with Session(engine) as newer:
            order = newer.get(InstantOrder, oid)
            if change == "recipient":
                order.current_offer_driver_id = another["id"]
            elif change == "token":
                order.offer_token = "b" * 32
            elif change == "expiry":
                order.offer_expires_at += timedelta(seconds=1)
            elif change == "revision":
                order.search_revision += 1
            elif change == "lifecycle":
                order.tried_lifecycle = "c" * 32
            elif change == "cancel":
                order.status = S.cancelled
            if change == "missing":
                newer.delete(order)
            else:
                newer.add(order)
            newer.commit()
        return result
    monkeypatch.setattr(isv, "passenger_stats", changing_stats)
    with Session(engine) as session:
        isv._push_offer(session, snapshot)
    assert sent == []


def test_guard_read_failure_fails_closed(client, frozen_offer, monkeypatch):
    oid, passenger, driver, snapshot = frozen_offer
    sent = _transport(monkeypatch)
    def unavailable(*args, **kwargs):
        raise RuntimeError("synthetic guard database unavailable")
    monkeypatch.setattr(isv, "Session", unavailable)
    with Session(engine) as session:
        isv._push_offer(session, snapshot)
    assert sent == []


def test_guard_does_not_flush_rollback_or_refresh_caller_identity_map(client, frozen_offer):
    oid, passenger, driver, snapshot = frozen_offer
    with Session(engine) as caller:
        order = caller.get(InstantOrder, oid)
        order.from_text = "caller pending edit, not committed"
        assert order in caller.dirty
        assert isv._offer_snapshot_is_current(caller, snapshot) is True
        assert caller.get(InstantOrder, oid) is order
        assert order.from_text == "caller pending edit, not committed" and order in caller.dirty
        with Session(engine) as observer:
            assert observer.get(InstantOrder, oid).from_text == "ул. 8 Марта, 12"
        caller.rollback()


def test_guard_session_is_closed_before_transport(client, frozen_offer, monkeypatch):
    oid, passenger, driver, snapshot = frozen_offer
    guard_states, delivered = [], []
    class TrackedSession(Session):
        def __enter__(self):
            guard_states.append("open")
            return super().__enter__()
        def __exit__(self, *args):
            result = super().__exit__(*args)
            guard_states.append("closed")
            return result
    monkeypatch.setattr(isv, "Session", TrackedSession)
    def transport(*args, **kwargs):
        assert guard_states == ["open", "closed"]
        delivered.append(True)
    monkeypatch.setattr(isv, "send_push", transport)
    with Session(engine) as caller:
        isv._push_offer(caller, snapshot)
    assert delivered == [True]


def test_delayed_transport_after_guard_retains_old_complete_snapshot(client, frozen_offer, monkeypatch):
    oid, passenger, driver, snapshot = frozen_offer
    delivered = []
    def transport(session, uid, title, body, **kwargs):
        with Session(engine) as newer:
            order = newer.get(InstantOrder, oid)
            order.status = S.cancelled
            order.offer_token = "f" * 32
            order.price_estimate = 999
            order.to_text = "Более поздний адрес"
            newer.add(order)
            newer.commit()
        delivered.append((uid, dict(kwargs["data"])))
    monkeypatch.setattr(isv, "send_push", transport)
    with Session(engine) as caller:
        isv._push_offer(caller, snapshot)
    assert delivered == [(driver["id"], delivered[0][1])]
    assert delivered[0][1]["offer_token"] == "a" * 32
    assert delivered[0][1]["price"] == "500" and delivered[0][1]["to"] == "ул. Гагарина"
    # This is the documented post-checkpoint delay, not client-side suppression.


@pytest.mark.usefixtures("fake_redis")
def test_real_decline_new_matcher_dispatches_only_next_recipient(client, user_factory, monkeypatch):
    passenger = user_factory("DispatchDeclinePassenger")
    first = user_factory("DispatchDeclineFirst", role=UserRole.driver)
    second = user_factory("DispatchDeclineSecond", role=UserRole.driver)
    oid = _order(passenger["id"], S.searching, search_revision=1)
    sent = _transport(monkeypatch)
    monkeypatch.setattr(isv, "candidates", lambda redis, session, order, **kwargs:
                        [first["id"] if order.search_revision == 1 else second["id"]])
    try:
        with Session(engine) as session:
            commit = session.commit
            previous = []
            def decline_after_commit():
                commit()
                with Session(engine) as other:
                    current = other.get(InstantOrder, oid)
                    previous.append(current.offer_token)
                    assert current.current_offer_driver_id == first["id"]
                    replacement = isv.decline_offer(other, oid, first["id"], expected_offer_token=current.offer_token)
                    assert replacement.status == S.offered and replacement.current_offer_driver_id == second["id"]
            monkeypatch.setattr(session, "commit", decline_after_commit)
            result = isv.try_offer_next(session, session.get(InstantOrder, oid))
            assert result.current_offer_driver_id == second["id"]
        assert len(sent) == 1 and sent[0][0] == second["id"]
        assert sent[0][3]["offer_token"] != previous[0]
    finally:
        isv._redis().delete(isv._tried_key(oid))


def test_helper_mutation_does_not_mix_new_money_or_address_into_snapshot(client, frozen_offer, monkeypatch):
    oid, passenger, driver, snapshot = frozen_offer
    sent = _transport(monkeypatch)
    real_stats = isv.passenger_stats
    with Session(engine) as session:
        percent = debt.driver_fee_percent(session, driver["id"], snapshot.created_at)
        expected_fee = debt.order_commission_kop(snapshot, percent)
    def changing_stats(*args, **kwargs):
        result = real_stats(*args, **kwargs)
        with Session(engine) as other:
            order = other.get(InstantOrder, oid)
            order.price_estimate = 999
            order.price_final = 998
            order.from_text = "Новое место, 123"
            order.to_text = "Другой конец, 456"
            for field in compensation.COMPENSATION_FIELDS:
                setattr(order, field, 0)
            other.add(order)
            other.commit()
        return result
    monkeypatch.setattr(isv, "passenger_stats", changing_stats)
    with Session(engine) as session:
        isv._push_offer(session, snapshot)
    assert len(sent) == 1
    data = sent[0][3]
    assert data["price"] == "500" and data["fee_kop"] == str(expected_fee)
    assert data["from"] == "ул. 8 Марта" and data["to"] == "ул. Гагарина"


def test_past_offer_deadline_suppresses_dispatch(client, frozen_offer, monkeypatch):
    oid, passenger, driver, snapshot = frozen_offer
    sent = _transport(monkeypatch)
    monkeypatch.setattr(isv, "utcnow", lambda: snapshot.offer_expires_at + timedelta(seconds=1))
    with Session(engine) as session:
        isv._push_offer(session, snapshot)
    assert sent == []


def test_orm_order_cannot_bypass_frozen_snapshot_contract(client, frozen_offer, monkeypatch):
    oid, passenger, driver, snapshot = frozen_offer
    sent = _transport(monkeypatch)
    with Session(engine) as session:
        with pytest.raises(TypeError):
            isv._push_offer(session, session.get(InstantOrder, oid))
    assert sent == []


@pytest.mark.parametrize("pool_name", ["StaticPool", "SingletonThreadPool"])
@pytest.mark.parametrize("flushed", [False, True])
def test_guard_shared_connection_pools_preserve_caller_writes(client, frozen_offer, monkeypatch, pool_name, flushed):
    from sqlalchemy import create_engine, pool, Column, Integer, String
    from sqlalchemy.orm import declarative_base
    oid, passenger, driver, snapshot = frozen_offer
    isolated_engine = create_engine("sqlite://", poolclass=getattr(pool, pool_name))
    Base = declarative_base()
    class Probe(Base):
        __tablename__ = "guard_pending_probe"
        id = Column(Integer, primary_key=True)
        value = Column(String)
    Base.metadata.create_all(isolated_engine)
    with Session(isolated_engine) as setup:
        setup.add(Probe(id=1, value="original"))
        setup.commit()
    def forbidden(*args, **kwargs):
        pytest.fail("shared connection guard must not open a second session")
    monkeypatch.setattr(isv, "Session", forbidden)
    try:
        with Session(isolated_engine) as caller:
            row = caller.get(Probe, 1)
            row.value = "caller write"
            if flushed:
                caller.flush()
            assert isv._offer_snapshot_is_current(caller, snapshot) is False
            assert caller.get(Probe, 1) is row and row.value == "caller write"
            if not flushed:
                assert row in caller.dirty
            caller.commit()
        with Session(isolated_engine) as observer:
            assert observer.get(Probe, 1).value == "caller write"
    finally:
        isolated_engine.dispose()


def test_guard_connection_bind_does_not_touch_existing_transaction(client, frozen_offer, monkeypatch):
    from sqlalchemy import text
    oid, passenger, driver, snapshot = frozen_offer
    def forbidden(*args, **kwargs):
        pytest.fail("connection-bound guard must not open a second session")
    monkeypatch.setattr(isv, "Session", forbidden)
    with engine.connect() as connection:
        transaction = connection.begin()
        connection.execute(text("UPDATE instantorder SET price_estimate=777 WHERE id=:id"), {"id": oid})
        with Session(bind=connection) as caller:
            assert isv._offer_snapshot_is_current(caller, snapshot) is False
            assert transaction.is_active and connection.in_transaction()
            assert connection.execute(text("SELECT price_estimate FROM instantorder WHERE id=:id"), {"id": oid}).scalar_one() == 777
        transaction.commit()
    with Session(engine) as observer:
        assert observer.get(InstantOrder, oid).price_estimate == 777


def test_capture_uses_database_scalars_not_stale_identity_map(client, frozen_offer):
    oid, passenger, driver, snapshot = frozen_offer
    with Session(engine) as caller:
        stale = caller.get(InstantOrder, oid)
        assert stale.price_estimate == 500
        with Session(engine) as newer:
            current = newer.get(InstantOrder, oid)
            current.price_estimate = 700
            newer.add(current)
            newer.commit()
        fresh_snapshot = isv._capture_offer_snapshot(caller, oid, driver["id"])
        assert fresh_snapshot.price_estimate == 700 and stale.price_estimate == 500
        assert isv._capture_offer_snapshot(caller, oid, driver["id"] + 10000) is None
        assert isv._capture_offer_snapshot(caller, oid + 1000000, driver["id"]) is None


def test_factory_unit_null_created_at_fallback_is_captured_once(frozen_offer, monkeypatch):
    """Factory row stub for defensive NULL path; normal schema is NOT NULL."""
    oid, passenger, driver, existing = frozen_offer
    names = [field.name for field in fields(existing)]
    row = tuple(None if name == "created_at" else getattr(existing, name) for name in names)
    class Result:
        def first(self):
            return row
    class FactorySession:
        def exec(self, statement):
            return Result()
    calls = []
    fixed = utcnow()
    def once():
        calls.append(True)
        return fixed
    monkeypatch.setattr(isv, "utcnow", once)
    snapshot = isv._capture_offer_snapshot(FactorySession(), oid, driver["id"])
    assert snapshot.created_at == fixed and calls == [True]
    assert snapshot.created_at == snapshot.created_at and calls == [True]
