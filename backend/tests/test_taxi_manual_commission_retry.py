"""Manual completion commits enough information to recover after process loss."""
from datetime import timedelta

import pytest
from sqlmodel import Session, select

from app import debt, ledger, taxi_auto_commission
from app.config import settings
from app.db import engine
from app.models import CommissionDebt, InstantOrder, InstantOrderStatus as S, LedgerEntry, LedgerKind, UserRole
from app.timeutil import utcnow
from test_money_holes_audit import _debt_row, _order


def _recover_only(order_id):
    # Keep these per-order assertions independent of other journeys sharing the test DB.
    # The public worker's query and dispatch are covered by test_taxi_auto_commission_retry.
    return taxi_auto_commission._accrue_auto_done([order_id], utcnow(), engine)


@pytest.mark.parametrize("path", ["done", "passenger-done", "destination/decline"])
@pytest.mark.parametrize("first_effect", ["recovery", "card"])
def test_manual_completion_survives_process_loss_and_rate_change(
    client, monkeypatch, user_factory, path, first_effect,
):
    monkeypatch.setattr(settings, "fee_tier1_percent", 3.0)
    monkeypatch.setattr(settings, "debt_now_threshold_kop", 1)
    monkeypatch.setattr("app.instant_service.active_tariff", lambda *_: object())
    monkeypatch.setattr("app.instant_service._tariff_price", lambda *_: 300)
    driver = user_factory("RestartDriver", role=UserRole.driver)
    passenger = user_factory("RestartPassenger")
    now = utcnow()
    oid = _order(passenger["id"], driver["id"], status=S.onboard, price=400,
                 onboard_at=now - timedelta(hours=2))
    legacy = _order(passenger["id"], driver["id"], price=400)

    def stop(*args, **kwargs):
        raise RuntimeError("test process stopped")

    actor = passenger if path == "passenger-done" else driver
    with monkeypatch.context() as patch:
        patch.setattr(debt, "accrue_for_order", stop)
        with pytest.raises(RuntimeError, match="test process stopped"):
            client.post(f"/instant/orders/{oid}/{path}", headers=actor["auth"])
    rub = 300 if path == "destination/decline" else 400
    with Session(engine) as session:
        assert session.get(InstantOrder, oid).status == S.done
        assert session.get(InstantOrder, oid).price_final == rub
    assert _debt_row(oid) is None
    monkeypatch.setattr(settings, "fee_tier1_percent", 12.0)
    expected_fee = rub * 3
    if first_effect == "card":
        with Session(engine) as session:
            assert ledger.settle_instant_order(session, oid, "card", rub * 100) == "settled"
    assert _recover_only(oid) == 1
    assert _recover_only(oid) == 0
    assert _debt_row(legacy) is None
    assert client.post(f"/instant/orders/{oid}/done", headers=driver["auth"]).status_code == 200
    with Session(engine) as session:
        row = session.get(InstantOrder, oid)
        assert row.commission_assessed_kop == expected_fee
        if first_effect == "card":
            entries = session.exec(select(LedgerEntry).where(
                LedgerEntry.order_id == oid, LedgerEntry.kind == LedgerKind.fee,
            )).all()
            assert [entry.amount_kop for entry in entries] == [-expected_fee]
            assert _debt_row(oid) is None
        else:
            rows = session.exec(select(CommissionDebt).where(CommissionDebt.order_id == oid)).all()
            assert len(rows) == 1
            assert rows[0].amount_kop == expected_fee
            assert not debt.is_pay_now(rows[0])
            if path == "passenger-done":
                assert "пассажир" in rows[0].note


@pytest.mark.parametrize("path", ["done", "passenger-done", "destination/decline"])
def test_driver_replay_preserves_the_winning_close_policy(client, monkeypatch, user_factory, path):
    monkeypatch.setattr(settings, "quiet_hours_to", 0)
    monkeypatch.setattr(settings, "debt_now_threshold_kop", 1)
    monkeypatch.setattr(settings, "fee_tier1_percent", 3.0)
    driver = user_factory("ReplayDriver", role=UserRole.driver)
    passenger = user_factory("ReplayPassenger")
    oid = _order(passenger["id"], driver["id"], status=S.onboard, price=400,
                 onboard_at=utcnow() - timedelta(hours=2))

    def stop(*args, **kwargs):
        raise RuntimeError("test process stopped")

    actor = passenger if path == "passenger-done" else driver
    with monkeypatch.context() as patch:
        patch.setattr(debt, "accrue_for_order", stop)
        with pytest.raises(RuntimeError, match="test process stopped"):
            client.post(f"/instant/orders/{oid}/{path}", headers=actor["auth"])

    response = client.post(f"/instant/orders/{oid}/done", headers=driver["auth"])
    assert response.status_code == 200, response.text
    posted = _debt_row(oid)
    assert posted.amount_kop > 0
    assert debt.is_pay_now(posted) == (path != "passenger-done")
    assert ("пассажир" in posted.note) == (path == "passenger-done")
    assert _recover_only(oid) == 1
    assert _debt_row(oid).id == posted.id


@pytest.mark.parametrize("card_first", [False, True])
def test_zero_rate_stays_zero_before_the_first_accrual(client, monkeypatch, user_factory, card_first):
    monkeypatch.setattr(settings, "fee_tier1_percent", 0.0)
    driver = user_factory("ZeroRestartDriver", role=UserRole.driver)
    passenger = user_factory("ZeroRestartPassenger")
    oid = _order(passenger["id"], driver["id"], status=S.onboard, price=400)

    def stop(*args, **kwargs):
        raise RuntimeError("test process stopped")

    with monkeypatch.context() as patch:
        patch.setattr(debt, "accrue_for_order", stop)
        with pytest.raises(RuntimeError, match="test process stopped"):
            client.post(f"/instant/orders/{oid}/done", headers=driver["auth"])
    monkeypatch.setattr(settings, "fee_tier1_percent", 12.0)
    if card_first:
        with Session(engine) as session:
            assert ledger.settle_instant_order(session, oid, "card", 40000) == "settled"
    assert _recover_only(oid) == 1
    assert _debt_row(oid) is None
    with Session(engine) as session:
        assert session.get(InstantOrder, oid).commission_assessed_kop == 0
        assert session.exec(select(LedgerEntry.id).where(
            LedgerEntry.order_id == oid, LedgerEntry.kind == LedgerKind.fee,
        )).first() is None
