"""A courier wallet guard must not block the notification's foreign-key check."""
from contextlib import contextmanager

import pytest
from sqlalchemy import text
from sqlmodel import Session, select

from app.db import engine
from app.models import LedgerEntry, LedgerKind, Notification, ParcelDelivery
from app.routers.parcels import _notify_couriers_new_parcel
from test_the_delivery_call_knows_the_whole_door import (
    _курьер_на_линии,
    режим_курьера,  # noqa: F401
)


@pytest.fixture(autouse=True)
def bounded_notification_session(monkeypatch):
    """A regression must fail promptly even on a server without a default lock timeout."""
    if engine.dialect.name != "postgresql":
        return

    @contextmanager
    def session_with_timeout(*args, **kwargs):
        with Session(*args, **kwargs) as session:
            session.execute(text("SET LOCAL lock_timeout = '1s'"))
            session.execute(text("SET LOCAL statement_timeout = '5s'"))
            yield session

    monkeypatch.setattr("app.services.Session", session_with_timeout)


@pytest.mark.skipif(engine.dialect.name != "postgresql", reason="Requires PostgreSQL row locks")
@pytest.mark.parametrize("kind", ["courier", "taxi"])
def test_wallet_guard_allows_notification_foreign_key(user_factory, monkeypatch, kind):
    from app.debt import settle_debt_from_wallet
    from app.routers.courier import settle_courier_commission_from_wallet
    from app.services import push_notification
    user = user_factory("PgGuard" + kind)
    monkeypatch.setattr("app.services.send_push", lambda *a, **kw: None)
    settle = settle_courier_commission_from_wallet if kind == "courier" else settle_debt_from_wallet
    with Session(engine) as session:
        assert settle(session, user["id"]) == 0
        # NO KEY UPDATE must still exclude another money writer, while permitting FK reads.
        from sqlalchemy.exc import OperationalError
        from app.models import User
        with Session(engine) as competitor:
            with pytest.raises(OperationalError) as locked:
                competitor.exec(select(User).where(User.id == user["id"]).with_for_update(
                    key_share=True, nowait=True,
                )).one()
            assert locked.value.orig.pgcode == "55P03"
        push_notification(session, user["id"], "parcel", "Test", "Test", "Body", "Body", push=False)
    with Session(engine) as verify:
        assert len(verify.exec(select(Notification).where(Notification.user_id == user["id"])).all()) == 1


@pytest.mark.skipif(engine.dialect.name != "postgresql", reason="Requires PostgreSQL row locks")
@pytest.mark.parametrize("balance", [0, 1000])
def test_courier_notification_survives_wallet_guard(user_factory, режим_курьера, monkeypatch, caplog, balance):
    courier = _курьер_на_линии(user_factory, "PgNotifyCourier")
    sender = user_factory("PgNotifySender")
    monkeypatch.setattr("app.services.send_push", lambda *a, **kw: None)
    with Session(engine) as session:
        if balance:
            session.add(LedgerEntry(driver_id=courier["id"], kind=LedgerKind.adj,
                                    amount_kop=balance, note="test credit"))
        parcel = ParcelDelivery(
            sender_id=sender["id"], from_city="Баймак", to_city="Сибай",
            size="small", description="medicine", receiver_name="Receiver",
            receiver_phone="+79995550001", status="created",
            delivery_type="courier", rules_accepted=True,
        )
        session.add(parcel)
        session.commit()
        session.refresh(parcel)
        parcel_id = parcel.id
        # A rollback used merely to release the guard lock must not erase caller work.
        parcel.description = "caller change must survive"
        session.add(parcel)
        sent = _notify_couriers_new_parcel(session, parcel)
        session.commit()
    with Session(engine) as verify:
        notes = verify.exec(select(Notification).where(
            Notification.user_id == courier["id"], Notification.ref_kind == "parcel",
            Notification.ref_id == parcel_id,
        )).all()
        assert len(notes) == 1, "Notification insert was swallowed (check PostgreSQL lock timeout)"
        assert sent >= 1  # PostgreSQL fixture retains couriers from previous test cases.
        assert verify.get(ParcelDelivery, parcel_id).description == "caller change must survive"
    assert "[NOTIFY] db error" not in caplog.text
