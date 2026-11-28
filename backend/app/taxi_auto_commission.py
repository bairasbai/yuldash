"""Recover commission for taxi orders auto-completed by retention cleanup."""

from datetime import timedelta

from sqlalchemy import update
from sqlalchemy.engine import Engine
from sqlmodel import Session, select

from . import debt as debt_mod
from .db import engine
from .models import InstantOrder, InstantOrderStatus
from .timeutil import utcnow

_AUTO_COMMISSION_BATCH = 50
_AUTO_COMMISSION_BACKOFF = timedelta(minutes=5)


def _accrue_auto_done(order_ids: list[int], now, db_engine: Engine) -> int:
    """Process persisted auto-completion work in independent transactions."""
    completed = 0
    for oid in order_ids:
        expected = None
        try:
            with Session(db_engine) as session:
                order = session.get(InstantOrder, oid)
                if order is None or order.status != InstantOrderStatus.done:
                    continue
                expected = order.auto_commission_retry_at
                if expected is None or expected > now:
                    continue
                # The lock inside accrual serializes this with manual retry and card settlement.
                # The debt and assessed commission are idempotent across a restart before ack.
                if order.driver_id is not None:
                    posted_debt = debt_mod.accrue_for_order(session, order, pay_now_allowed=False) is not None
                    # The wallet sweep can leave the user lock held on its read-only path.
                    # Release it before the acknowledgement takes the order lock again.
                    session.rollback()
                    session.refresh(order)
                    if not posted_debt and order.commission_assessed_kop is None:
                        raise RuntimeError("auto-completion accrual is not yet committed")
                ack = session.execute(update(InstantOrder).where(
                    InstantOrder.id == oid,
                    InstantOrder.auto_commission_retry_at == expected,
                ).values(auto_commission_retry_at=None))
                session.commit()
                completed += ack.rowcount
        except Exception as ex:  # noqa: BLE001 — a failed order cannot block the rest
            print(f"  комиссия за авто-закрытый заказ {oid}: ОШИБКА {type(ex).__name__}")
            if expected is not None:
                try:
                    with Session(db_engine) as session:
                        session.execute(update(InstantOrder).where(
                            InstantOrder.id == oid,
                            InstantOrder.auto_commission_retry_at == expected,
                        ).values(auto_commission_retry_at=now + _AUTO_COMMISSION_BACKOFF))
                        session.commit()
                except Exception as retry_ex:  # noqa: BLE001 — retain the durable marker
                    print(f"  перенос комиссии заказа {oid}: ОШИБКА {type(retry_ex).__name__}")
    return completed


def retry_auto_commissions(now=None, *, db_engine: Engine = engine) -> int:
    """Recover already completed orders; never infer intent from historical done rows."""
    now = now or utcnow()
    with Session(db_engine) as session:
        due = session.exec(select(InstantOrder.id).where(
            InstantOrder.status == InstantOrderStatus.done,
            InstantOrder.auto_commission_retry_at <= now,
        ).order_by(InstantOrder.auto_commission_retry_at, InstantOrder.id).limit(_AUTO_COMMISSION_BATCH)).all()
    return _accrue_auto_done(due, now, db_engine)
