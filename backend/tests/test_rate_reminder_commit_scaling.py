"""Bound reminder ORM expiry work while preserving durable per-item checkpoints."""
from dataclasses import replace

import pytest
from sqlalchemy import inspect
from sqlalchemy.orm.state import InstanceState
from sqlmodel import Session, select

from app import rate_reminder as rr
from app.db import engine
from app.models import Booking, BookingStatus, Ride
from app.timeutil import utcnow


def _service_with_bookings(user_factory, count):
    driver, passenger = user_factory(), user_factory()
    with Session(engine) as session:
        ride = Ride(driver_id=driver["id"], from_city="A", to_city="B", depart_at=utcnow())
        session.add(ride)
        session.flush()
        rows = [Booking(ride_id=ride.id, passenger_id=passenger["id"], status=BookingStatus.done)
                for _ in range(count)]
        session.add_all(rows)
        session.flush()
        ids = [row.id for row in rows]
        session.commit()
    service = replace(rr.СЕРВИСЫ[0], найти=lambda session: session.exec(
        select(Booking).where(Booking.id.in_(ids), Booking.rate_reminded == False)
        .order_by(Booking.id)
    ).all())
    return service, ids


@pytest.mark.parametrize("count", [8, 16])
def test_commit_expiry_work_is_linear(user_factory, monkeypatch, count):
    service, ids = _service_with_bookings(user_factory, count)
    monkeypatch.setattr(rr, "push_notification", lambda *args, **kwargs: None)
    expire = InstanceState._expire
    expirations = 0

    def counted_expire(state, *args, **kwargs):
        nonlocal expirations
        expirations += 1
        return expire(state, *args, **kwargs)

    monkeypatch.setattr(InstanceState, "_expire", counted_expire)
    with Session(engine) as session:
        assert len(rr._по_сервису(session, service, False)) == 2 * count
        assert session.expire_on_commit is True
    assert expirations <= 4 * count + 10, f"{expirations} expiry visits for {count} items"
    with Session(engine) as verification:
        assert all(verification.get(Booking, bid).rate_reminded for bid in ids)


@pytest.mark.parametrize("original_expire", [False, True])
def test_commit_policy_restored_after_later_item_failure(user_factory, monkeypatch, original_expire):
    service, ids = _service_with_bookings(user_factory, 2)
    notifications = 0

    def fail_on_second_item(*args, **kwargs):
        nonlocal notifications
        notifications += 1
        if notifications == 3:
            with Session(engine) as verification:
                assert verification.get(Booking, ids[0]).rate_reminded is True
                assert verification.get(Booking, ids[1]).rate_reminded is False
            raise RuntimeError("second-item failure")

    monkeypatch.setattr(rr, "push_notification", fail_on_second_item)
    with Session(engine, expire_on_commit=original_expire) as session:
        earlier = session.get(Booking, ids[0])
        with pytest.raises(RuntimeError, match="second-item failure"):
            rr._по_сервису(session, service, False)
        assert session.expire_on_commit is original_expire
        assert inspect(earlier).expired is original_expire


def test_session_proxy_keeps_its_own_commit_contract(user_factory, monkeypatch):
    service, _ = _service_with_bookings(user_factory, 2)
    monkeypatch.setattr(rr, "push_notification", lambda *args, **kwargs: None)

    class Proxy:
        def __init__(self, underlying):
            self.underlying = underlying
            self.commits = 0

        def __getattr__(self, name):
            return getattr(self.underlying, name)

        def commit(self):
            self.commits += 1
            return self.underlying.commit()

    with Session(engine) as session:
        proxy = Proxy(session)
        assert len(rr._по_сервису(proxy, service, False)) == 4
        assert proxy.commits == 2
        assert session.expire_on_commit is True
        assert "expire_on_commit" not in vars(proxy)


def test_dry_run_does_not_expire_callers_objects(user_factory):
    service, ids = _service_with_bookings(user_factory, 2)
    with Session(engine) as session:
        existing = session.get(Booking, ids[0])
        assert len(rr._по_сервису(session, service, True)) == 4
        assert not inspect(existing).expired
        assert existing.rate_reminded is False
        assert session.expire_on_commit is True
