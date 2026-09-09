"""Real PostgreSQL row locks: run only with an isolated test DATABASE_URL."""
import asyncio
from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta
from threading import Barrier, Event
from uuid import uuid4

import pytest
from sqlalchemy import text
from sqlmodel import Session, select

from app.config import settings
from app.db import engine
from app.models import InstantOrder, LedgerEntry, Payment, ParcelDelivery, Ride, UserRole
from app.routers import payments
from app.routers.courier import _courier_snapshot_tier
from app.timeutil import utcnow
from test_ledger import _make_done_order
from test_the_wallet_pays_for_what_is_already_paid import _доставка

pytestmark = pytest.mark.skipif(engine.dialect.name != "postgresql", reason="requires isolated PostgreSQL")


@pytest.fixture(autouse=True)
def quiet_provider(monkeypatch):
    monkeypatch.setattr(settings, "payments_provider", "yookassa")
    monkeypatch.setattr(payments, "fetch_payment", lambda _: {"status": "succeeded"})
    monkeypatch.setattr(payments, "_tell_about_payment", lambda *a, **k: None)
    monkeypatch.setattr(payments, "notify_admin_telegram", lambda *a, **k: None)


def save_payment(**values):
    with Session(engine) as session:
        payment = Payment(provider_id=f"pg_{uuid4().hex}", method="yookassa", **values)
        session.add(payment)
        session.commit()
        session.refresh(payment)
        return payment.id


def race(payment_id, actions):
    """Preload the same pending row on distinct PG connections before either action."""
    barrier = Barrier(len(actions))

    def worker(action):
        with Session(engine) as session:
            session.execute(text("SET lock_timeout = '4s'"))
            session.execute(text("SET statement_timeout = '8s'"))
            pid = session.execute(text("SELECT pg_backend_pid()")).scalar_one()
            payment = session.get(Payment, payment_id)
            assert payment.status == "pending"
            barrier.wait(timeout=10)
            action(session, payment)
            return pid

    with ThreadPoolExecutor(max_workers=len(actions)) as executor:
        futures = [executor.submit(worker, action) for action in actions]
        pids = [future.result(timeout=20) for future in futures]
    assert len(set(pids)) == len(actions)


def webhook(session, payment):
    class Request:
        async def json(self):
            return {"object": {"id": payment.provider_id}}
    assert asyncio.run(payments.yookassa_webhook(Request(), session)) == {"ok": True}


def test_poll_webhook_and_activation_credit_ride_once(user_factory, monkeypatch):
    monkeypatch.setattr("app.debt.driver_fee_percent", lambda *a, **k: 15.0)
    driver = user_factory("PgRaceDriver", role=UserRole.driver)
    passenger = user_factory("PgRacePassenger")
    order_id = _make_done_order(driver["id"], passenger["id"], price_rub=200)
    payment_id = save_payment(user_id=passenger["id"], purpose="ride", order_id=order_id, amount_kop=20000)
    race(payment_id, [payments._sync_provider_status, webhook, payments._activate_payment])
    with Session(engine) as session:
        assert session.get(Payment, payment_id).status == "succeeded"
        assert session.get(InstantOrder, order_id).paid
        entries = session.exec(select(LedgerEntry).where(LedgerEntry.order_id == order_id)).all()
        assert sorted(row.amount_kop for row in entries) == [-3000, 20000]


def test_stale_concurrent_activation_extends_boost_only_once(user_factory, monkeypatch):
    driver = user_factory("PgBoost", role=UserRole.driver)
    now = utcnow()
    monkeypatch.setattr(payments, "utcnow", lambda: now)
    tier, plan = next(iter(payments.BOOST_PLANS.items()))
    with Session(engine) as session:
        ride = Ride(driver_id=driver["id"], from_city="A", to_city="B", depart_at=now, price=200)
        session.add(ride)
        session.commit()
        session.refresh(ride)
        ride_id = ride.id
    payment_id = save_payment(user_id=driver["id"], purpose="boost", ride_id=ride_id, tier=tier, amount_kop=plan[1])
    race(payment_id, [payments._activate_payment, payments._activate_payment])
    with Session(engine) as session:
        assert session.get(Ride, ride_id).boosted_until == now + timedelta(hours=plan[2])


@pytest.mark.parametrize("first_status", ["succeeded", "canceled"])
def test_stale_opposite_provider_result_keeps_terminal_outcome(user_factory, monkeypatch, first_status):
    payer = user_factory("PgTerminal")
    payment_id = save_payment(user_id=payer["id"], purpose="donate", amount_kop=10000)
    first_done = Event()
    from threading import local
    state = local()
    monkeypatch.setattr(payments, "fetch_payment", lambda _: {"status": state.status})

    def first(session, payment):
        state.status = first_status
        payments._sync_provider_status(session, payment)
        first_done.set()

    def second(session, payment):
        assert first_done.wait(timeout=10)
        state.status = "canceled" if first_status == "succeeded" else "succeeded"
        payments._sync_provider_status(session, payment)

    race(payment_id, [first, second])
    with Session(engine) as session:
        expected = "succeeded" if first_status == "succeeded" else payments.REFUND_DUE
        assert session.get(Payment, payment_id).status == expected


def test_concurrent_courier_confirmation_pays_exact_snapshot(user_factory):
    courier = user_factory("PgCourier", role=UserRole.driver)
    sender = user_factory("PgSender")
    old = _доставка(courier["id"], sender["id"], 5000)
    payment_id = save_payment(user_id=courier["id"], purpose="courier_commission",
                              amount_kop=5000, tier=_courier_snapshot_tier((old,)))
    new = _доставка(courier["id"], sender["id"], 9000)
    # Even identical timestamps cannot widen the paid snapshot.
    with Session(engine) as session:
        session.get(ParcelDelivery, new).delivered_at = session.get(ParcelDelivery, old).delivered_at
        session.commit()
    race(payment_id, [payments._sync_provider_status, payments._activate_payment])
    with Session(engine) as session:
        assert session.get(Payment, payment_id).status == "succeeded"
        assert session.get(ParcelDelivery, old).commission_paid
        assert not session.get(ParcelDelivery, new).commission_paid
