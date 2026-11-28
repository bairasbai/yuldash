"""Opt-in recovery of missed taxi payment outcomes, at most two bank reads per tick."""
from datetime import datetime, timedelta

from sqlalchemy import and_, func, or_, update
from sqlalchemy.exc import SQLAlchemyError
from sqlmodel import Session, select

from .config import settings
from .db import engine
from .logs import log
from .models import Payment, TaxiCheckoutScan
from .payments import _yookassa_live, fetch_taxi_payment_status
from .routers.payments import _sync_provider_status
from .taxi_checkout_worker import reconcile_taxi_checkout_once
from .timeutil import utcnow

_MIN_AGE = timedelta(minutes=5)
_STATUS_DELAY = timedelta(minutes=5)
_SCAN_DELAY = timedelta(minutes=10)


def _eligible(now: datetime):
    return (
        Payment.status == "pending", Payment.purpose == "ride",
        Payment.order_id.is_not(None), Payment.method == "yookassa",
        Payment.created_at <= now - _MIN_AGE,
        or_(Payment.taxi_reconcile_after.is_(None), Payment.taxi_reconcile_after <= now),
    )


def _claim(db, *, now: datetime, known: bool, shop: str) -> int | None:
    """Give status lookups and paginated discovery separate budgets; oldest eligible goes first."""
    scan = TaxiCheckoutScan
    if known:
        lane = and_(Payment.provider_id != "", or_(
            scan.payment_id.is_(None), and_(scan.shop_id == shop, scan.state == "linked"),
        ))
    else:
        lane = or_(
            and_(Payment.provider_id == "", scan.payment_id.is_(None)),
            and_(scan.shop_id == shop, or_(
                scan.state == "scanning",
                and_(scan.state == "not_seen", scan.cutoff <= now - _SCAN_DELAY),
            )),
        )
    with Session(db) as session:
        pid = session.exec(select(Payment.id).outerjoin(scan, scan.payment_id == Payment.id)
                           .where(*_eligible(now), lane)
                           .order_by(func.coalesce(Payment.taxi_reconcile_after, Payment.created_at), Payment.id)
                           .limit(1)).first()
        if pid is None:
            return None
        # A crash after claiming loses only the short lease. Do not hold DB locks during HTTP.
        result = session.execute(update(Payment).where(Payment.id == pid, *_eligible(now))
                                 .values(taxi_reconcile_after=now + _STATUS_DELAY))
        session.commit()
        return pid if result.rowcount == 1 else None


def _poll_known(db, payment_id: int, shop: str) -> None:
    with Session(db) as session:
        row = session.get(Payment, payment_id)
        if (not row or row.status != "pending" or not row.provider_id or row.purpose != "ride"
                or row.order_id is None or row.method != "yookassa"):
            return
        scan = session.get(TaxiCheckoutScan, payment_id)
        if scan is not None and (scan.shop_id != shop or scan.state != "linked"):
            return
        session.commit()
        _sync_provider_status(
            session, row, verified_fetch=lambda provider_id, pid, amount: (
                fetch_taxi_payment_status(provider_id, pid, amount, shop)
            ),
        )


def sweep_taxi_checkouts(*, db=engine, now: datetime | None = None) -> int:
    """Poll without a client request. Never create provider charges or clear unverified holds."""
    if not settings.taxi_checkout_sweep_enabled or not _yookassa_live():
        return 0
    now = now or utcnow()
    shop = settings.yookassa_shop_id
    attempted = 0
    for known in (True, False):
        try:
            pid = _claim(db, now=now, known=known, shop=shop)
            if pid is None:
                continue
            attempted += 1
            if known:
                _poll_known(db, pid, shop)
            else:
                reconcile_taxi_checkout_once(db, pid, now=now, defer_status=True)
        except SQLAlchemyError as exc:
            # No raw database exception: parameters can contain private payment data.
            log.warning("[TAXI-CHECKOUT] reconciliation DB failure: %s", type(exc).__name__)
    return attempted
