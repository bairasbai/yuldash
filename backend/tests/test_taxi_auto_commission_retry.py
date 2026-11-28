from datetime import timedelta

import pytest
from sqlmodel import Session, select

from app import cleanup, debt, taxi_auto_commission, taxi_worker
from app.config import settings
from app.db import engine
from app.models import CommissionDebt, InstantOrder, InstantOrderStatus as S, UserRole
from app.timeutil import utcnow
from test_money_holes_audit import _debt_row, _order


class ProcessStopped(BaseException):
    """Simulate a lost process, not an exception the worker retries inline."""


def _stale_order(user_factory, key, now):
    driver = user_factory(f"AutoDebtDriver{key}", role=UserRole.driver)
    passenger = user_factory(f"AutoDebtPassenger{key}")
    old = now - timedelta(hours=settings.taxi_stale_hours + 1)
    return driver, _order(passenger["id"], driver["id"], status=S.onboard, price=400, created_at=old)


def _state(order_id):
    with Session(engine) as session:
        order = session.get(InstantOrder, order_id)
        return order.status, order.auto_commission_retry_at, order.commission_assessed_kop


@pytest.mark.parametrize("stop_after_accrual", [False, True])
def test_restart_recovers_auto_commission_once_without_touching_legacy(
    client, monkeypatch, user_factory, stop_after_accrual,
):
    now = utcnow().replace(microsecond=0)
    monkeypatch.setattr(taxi_auto_commission, "utcnow", lambda: now)
    monkeypatch.setattr(settings, "fee_tier1_percent", 3.0)
    monkeypatch.setattr(settings, "debt_now_threshold_kop", 1)
    driver, oid = _stale_order(user_factory, "Restart", now)
    passenger = user_factory("LegacyDebtPassenger")
    legacy = _order(passenger["id"], driver["id"], price=400)
    original = debt.accrue_for_order

    def stop(session, order, **kwargs):
        if stop_after_accrual:
            original(session, order, **kwargs)
        raise ProcessStopped

    with monkeypatch.context() as patch:
        patch.setattr(debt, "accrue_for_order", stop)
        with pytest.raises(ProcessStopped):
            cleanup.close_stale_orders(now)
    assert _state(oid) == (S.done, now, 1200 if stop_after_accrual else None)
    assert (_debt_row(oid) is not None) is stop_after_accrual
    with Session(engine) as session:
        assert taxi_worker.run_once(session, dry_run=True)["auto_commissions_recovered"] == 0
    assert _state(oid)[1] == now
    assert cleanup.close_stale_orders(now) == 0  # No second status change or close notification.
    assert _state(oid) == (S.done, None, 1200)
    assert taxi_auto_commission.retry_auto_commissions(now) == 0
    assert _state(legacy) == (S.done, None, None)
    assert _debt_row(legacy) is None
    with Session(engine) as session:
        rows = session.exec(select(CommissionDebt).where(CommissionDebt.order_id == oid)).all()
        assert len(rows) == 1
        assert rows[0].amount_kop == 1200
        assert "автомат" in rows[0].note
        assert not debt.is_pay_now(rows[0])


def test_failed_order_backs_off_and_manual_retry_does_not_make_debt_urgent(
    client, monkeypatch, user_factory,
):
    now = utcnow().replace(microsecond=0)
    monkeypatch.setattr(taxi_auto_commission, "utcnow", lambda: now)
    monkeypatch.setattr(settings, "fee_tier1_percent", 3.0)
    monkeypatch.setattr(settings, "debt_now_threshold_kop", 1)
    driver, first = _stale_order(user_factory, "First", now)
    _, second = _stale_order(user_factory, "Second", now)
    original = debt.accrue_for_order

    def fail_first(session, order, **kwargs):
        if order.id == first:
            raise RuntimeError("fixture lock unavailable")
        return original(session, order, **kwargs)

    with monkeypatch.context() as patch:
        patch.setattr(debt, "accrue_for_order", fail_first)
        assert cleanup.close_stale_orders(now) == 2
        assert _state(first) == (S.done, now + timedelta(minutes=5), None)
        assert _state(second) == (S.done, None, 1200)
        assert taxi_auto_commission.retry_auto_commissions(now) == 0

    response = client.post(f"/instant/orders/{first}/done", headers=driver["auth"])
    assert response.status_code == 200, response.text
    assert not debt.is_pay_now(_debt_row(first))
    now += timedelta(minutes=5)
    with Session(engine) as session:
        assert taxi_worker.run_once(session)["auto_commissions_recovered"] >= 1
    assert _state(first) == (S.done, None, 1200)


@pytest.mark.parametrize("fee_percent,card_first", [(0.0, False), (3.0, True)])
def test_recovery_respects_zero_or_an_already_settled_card_payment(
    client, monkeypatch, user_factory, fee_percent, card_first,
):
    from app import ledger
    from app.models import LedgerEntry, LedgerKind

    now = utcnow().replace(microsecond=0)
    monkeypatch.setattr(settings, "fee_tier1_percent", fee_percent)
    _, oid = _stale_order(user_factory, f"ZeroOrCard{card_first}", now)

    def stop(*_args):
        raise ProcessStopped

    with monkeypatch.context() as patch:
        patch.setattr(taxi_auto_commission, "_accrue_auto_done", stop)
        with pytest.raises(ProcessStopped):
            cleanup.close_stale_orders(now)
    if card_first:
        with Session(engine) as session:
            assert ledger.settle_instant_order(session, oid, "card", 40000) == "settled"
        monkeypatch.setattr(settings, "fee_tier1_percent", 12.0)
    assert taxi_auto_commission.retry_auto_commissions(now) == 1
    assert taxi_auto_commission.retry_auto_commissions(now) == 0
    assert _debt_row(oid) is None
    assert _state(oid) == (S.done, None, 1200 if card_first else 0)
    with Session(engine) as session:
        fees = session.exec(select(LedgerEntry.amount_kop).where(
            LedgerEntry.order_id == oid, LedgerEntry.kind == LedgerKind.fee,
        )).all()
    assert fees == ([-1200] if card_first else [])
