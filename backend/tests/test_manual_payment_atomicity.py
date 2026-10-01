"""QA-B07-004: actual failed SQL must roll back Payment and all its effects.

Only inject an actual DB error before the real Payment status UPDATE. Product
handlers, settlement, commit/rollback and notifications are never replaced.
Legacy money inputs below are seeded explicitly, not presented as UI journeys.
"""
from datetime import timedelta
import json
from uuid import uuid4

import pytest
from sqlalchemy import event
from sqlalchemy.exc import DBAPIError
from sqlmodel import Session, select

from app.config import settings
from app.db import engine
from app.models import (CommissionDebt, DebtStatus, InstantOrder, LedgerEntry,
                        Notification, ParcelDelivery, Payment, UserRole)
from app.timeutil import utcnow
from test_ledger import _make_done_order
from test_manual_payment_terminal_confirmation import (
    _action, _input, _snapshot, isolated_manual_configuration,
)


def _atomic_input(client, user_factory, monkeypatch, kind):
    if kind in ("legacy_booking", "boost"):
        context = _input(client, user_factory, kind)
        context.update(order_id=None, debt_ids=[], parcel_ids=[])
        return context
    # Real nonzero configured tier; neither calculation nor settlement is mocked.
    monkeypatch.setattr(settings, "launch_promo_until", "")
    monkeypatch.setattr(settings, "fee_tier1_percent", 3.0)
    admin = user_factory("QA_ATOMIC_ADMIN", role=UserRole.admin)
    driver = user_factory("QA_ATOMIC_DRIVER", role=UserRole.driver)
    passenger = user_factory("QA_ATOMIC_PASSENGER")
    owner = passenger if kind.startswith("ride_") else driver
    boundary = utcnow()
    context = dict(kind=kind, admin=admin, owner=owner, driver=driver,
                   ride_id=None, booking_id=None, order_id=None, debt_ids=[], parcel_ids=[])
    with Session(engine) as session:
        if kind.startswith("ride_"):
            order_id = _make_done_order(driver["id"], passenger["id"], price_rub=200)
            context["order_id"] = order_id
            debt = CommissionDebt(driver_id=driver["id"], order_id=order_id, amount_kop=600,
                                  status=DebtStatus.paid if kind == "ride_paid_refund" else DebtStatus.unpaid,
                                  created_at=boundary-timedelta(minutes=5),
                                  confirmed_at=boundary if kind == "ride_paid_refund" else None,
                                  note="QA confirmed transfer" if kind == "ride_paid_refund" else "")
            session.add(debt)
            session.flush()
            context["debt_ids"] = [debt.id]
            payment = Payment(user_id=owner["id"], purpose="ride", order_id=order_id,
                              amount_kop=20000, method="sbp", created_at=boundary)
        elif kind == "taxi_debt":
            for status, minutes, amount in ((DebtStatus.unpaid, -5, 500), (DebtStatus.pending, -1, 700),
                                            (DebtStatus.unpaid, 5, 900)):
                debt = CommissionDebt(driver_id=driver["id"], amount_kop=amount,
                                      status=status, created_at=boundary+timedelta(minutes=minutes))
                session.add(debt)
                session.flush()
                context["debt_ids"].append(debt.id)
            payment = Payment(user_id=owner["id"], purpose="taxi_debt", amount_kop=1200,
                              method="sbp", created_at=boundary)
        else:
            assert kind == "courier_commission"
            for minutes, amount in ((-5, 500), (5, 900)):
                parcel = ParcelDelivery(sender_id=passenger["id"], courier_id=driver["id"],
                                        delivery_type="courier", status="delivered", commission_kop=amount,
                                        commission_paid=False, delivered_at=boundary+timedelta(minutes=minutes))
                session.add(parcel)
                session.flush()
                context["parcel_ids"].append(parcel.id)
            # Historical timestamp snapshot (no causal-ID marker); real fallback logic.
            payment = Payment(user_id=owner["id"], purpose="courier_commission", amount_kop=500,
                              method="sbp", created_at=boundary)
        session.add(payment)
        session.commit()
        session.refresh(payment)
        context["payment_id"] = payment.id
        context["amount_kop"] = payment.amount_kop
    return context


def _atomic_snapshot(context):
    base = _snapshot(context)
    with Session(engine) as session:
        order = session.get(InstantOrder, context["order_id"]) if context["order_id"] else None
        debts = [session.get(CommissionDebt, key) for key in context["debt_ids"]]
        parcels = [session.get(ParcelDelivery, key) for key in context["parcel_ids"]]
        base["order"] = {"paid": order.paid, "payment_method": order.payment_method} if order else None
        base["debts"] = [{"id": row.id, "status": row.status.value, "amount_kop": row.amount_kop,
                          "note": row.note, "confirmed_at": row.confirmed_at.isoformat() if row.confirmed_at else None}
                         for row in debts]
        base["parcels"] = [{"id": row.id, "paid": row.commission_paid, "amount_kop": row.commission_kop}
                           for row in parcels]
        base["ledger"] = [{"id": row.id, "kind": row.kind.value, "amount_kop": row.amount_kop,
                          "order_id": row.order_id, "booking_id": row.booking_id, "ext_id": row.ext_id}
                         for row in session.exec(select(LedgerEntry).where(LedgerEntry.driver_id == context["driver"]["id"])
                                                 .order_by(LedgerEntry.id)).all()]
    return base


@pytest.mark.parametrize("kind", ["legacy_booking", "ride_unpaid_debt", "ride_paid_refund",
                                 "taxi_debt", "courier_commission", "boost"])
def test_failed_status_sql_rolls_back_effect_and_retry_applies_once(client, user_factory, monkeypatch, kind):
    context = _atomic_input(client, user_factory, monkeypatch, kind)
    before = _atomic_snapshot(context)
    seen = []
    missing = "qa_payment_missing_" + uuid4().hex

    def fail_actual_sql(conn, cursor, statement, parameters, execution_context, executemany):
        sql = statement.lower().strip().replace('"', '')
        if sql.startswith("update payment ") and "status" in sql and not seen:
            seen.append({"statement": statement, "dialect": conn.dialect.name})
            # This is executed by the actual SQLite/PG connection, causing its real
            # DBAPI error/transaction abort. It does not fake successful settlement.
            conn.exec_driver_sql("SELECT * FROM " + missing)

    event.listen(engine, "before_cursor_execute", fail_actual_sql)
    try:
        with pytest.raises(DBAPIError):
            _action(client, context["admin"], context["payment_id"], "confirm")
    finally:
        event.remove(engine, "before_cursor_execute", fail_actual_sql)
    failed = _atomic_snapshot(context)
    print("QA_MANUAL_ATOMIC " + json.dumps(dict(kind=kind, dialect=engine.dialect.name,
                                               before=before, failed=failed, actual_sql_fault=seen),
                                           ensure_ascii=False, sort_keys=True))
    assert len(seen) == 1, "calibration: reached actual Payment UPDATE, not a setup failure"
    assert failed == before, "failed Payment status write must not leave money/paid/refund/debt/boost changes"

    response = _action(client, context["admin"], context["payment_id"], "confirm")
    assert response.status_code == 200 and response.json()["status"] == "succeeded", response.text
    succeeded = _atomic_snapshot(context)
    assert succeeded["payment"]["status"] == "succeeded"
    assert succeeded["payment"]["settled_at"] is not None
    repeated = _action(client, context["admin"], context["payment_id"], "confirm")
    assert repeated.status_code == 200 and _atomic_snapshot(context) == succeeded
    if kind == "legacy_booking":
        assert succeeded["booking"]["paid"] is True
        assert [(e["kind"], e["amount_kop"]) for e in succeeded["ledger"]] == [("earn", 40000)]
    elif kind.startswith("ride_"):
        assert succeeded["order"]["paid"] is True and succeeded["order"]["payment_method"] == "sbp"
        amounts = {(e["kind"], e["amount_kop"]) for e in succeeded["ledger"]}
        expected = {("earn", 20000), ("fee", -600)}
        if kind == "ride_paid_refund":
            expected.add(("adj", 600))
        assert amounts == expected and len(succeeded["ledger"]) == len(expected)
        assert succeeded["debts"][0]["status"] == "paid"
    elif kind == "taxi_debt":
        assert [d["status"] for d in succeeded["debts"]] == ["paid", "paid", "unpaid"]
        assert succeeded["ledger"] == []
    elif kind == "courier_commission":
        assert [p["paid"] for p in succeeded["parcels"]] == [True, False]
        assert succeeded["ledger"] == []
    else:
        assert succeeded["ride"]["boosted_until"] is not None and succeeded["ride"]["boost_tier"] == "quick"
