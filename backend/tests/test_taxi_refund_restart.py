"""A confirmed extra taxi payment must survive restart with its support thread."""

import pytest
from sqlalchemy import event
from sqlmodel import Session, select

from app.db import engine
from app.models import Payment, SupportMessage, SupportTicket, UserRole
from app.routers import payments

from test_money_holes_audit import _order


class _Stopped(BaseException):
    """A stopped process cannot execute the remaining best-effort steps."""


@pytest.mark.parametrize("when", ["before_commit", "after_commit"])
def test_refund_support_thread_survives_restart(client, user_factory, monkeypatch, when):
    del client  # initializes the isolated application database
    driver = user_factory("RefundRestartDriver", role=UserRole.driver)
    passenger = user_factory("RefundRestartPassenger")
    notifications = []
    monkeypatch.setattr(payments, "notify_admin_telegram", notifications.append)
    monkeypatch.setattr("app.services.push_notification", lambda *args, **kwargs: None)
    oid = _order(passenger["id"], driver["id"], price=600, paid=True, payment_method="cash")
    with Session(engine) as s:
        payment = Payment(
            user_id=passenger["id"], order_id=oid, purpose="ride", method="yookassa",
            provider_id="taxi-refund-restart-control", amount_kop=60000,
        )
        s.add(payment)
        s.commit()
        pid = payment.id

    with Session(engine) as s:
        payment = s.get(Payment, pid)

        def stop(session):
            raise _Stopped

        event.listen(s, when, stop, once=True)
        with pytest.raises(_Stopped):
            payments._activate_payment(s, payment)

    with Session(engine) as s:
        payment = s.get(Payment, pid)
        tickets = s.exec(select(SupportTicket).where(SupportTicket.user_id == passenger["id"])).all()
        if when == "after_commit":
            assert payment.status == payments.REFUND_DUE
            assert len(tickets) == 1
            assert len(s.exec(select(SupportMessage).where(SupportMessage.ticket_id == tickets[0].id)).all()) == 1
        else:
            assert payment.status == "pending"
            assert not tickets

    with Session(engine) as s:
        payment = s.get(Payment, pid)
        payments._activate_payment(s, payment)
        payments._activate_payment(s, payment)
        assert payment.status == payments.REFUND_DUE
        ticket = s.exec(select(SupportTicket).where(SupportTicket.user_id == passenger["id"])).one()
        message = s.exec(select(SupportMessage).where(SupportMessage.ticket_id == ticket.id)).one()
        assert "600 ₽" in message.body
    assert len(notifications) == (1 if when == "before_commit" else 0)
