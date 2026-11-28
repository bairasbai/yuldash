"""Operator-invoked, resumable reconciliation of one interrupted taxi checkout."""

import argparse
from datetime import datetime, timedelta

from sqlmodel import Session, select

from .config import settings
from .db import engine
from .models import Payment, TaxiCheckoutScan
from .payments import _yookassa_live, read_taxi_payment_page
from .routers.payments import _sync_provider_status
from .timeutil import utcnow


def _lock_taxi_payment(session: Session, payment_id: int) -> Payment | None:
    payment = session.get(Payment, payment_id, with_for_update=True, populate_existing=True)
    if payment and payment.purpose == "ride" and payment.order_id is not None and payment.method == "yookassa":
        return payment
    return None


def _sync_linked(session: Session, payment: Payment) -> str:
    session.commit()
    payment, _ = _sync_provider_status(session, payment)
    return payment.status


def reconcile_taxi_checkout_once(db, payment_id: int, *, now: datetime | None = None) -> str:
    """Read at most one merchant page and checkpoint it before handing off to the next pass."""
    if not _yookassa_live():
        return "disabled"
    now = now or utcnow()
    with Session(db) as session:
        payment = _lock_taxi_payment(session, payment_id)
        if payment is None:
            return "not_found"
        if payment.status != "pending":
            return payment.status
        scan = session.get(TaxiCheckoutScan, payment_id)
        if scan is not None and scan.shop_id != settings.yookassa_shop_id:
            return "shop_changed"
        if payment.provider_id:
            return _sync_linked(session, payment) if scan and scan.state == "linked" else "already_linked"
        if scan is None:
            if payment.created_at > now - timedelta(minutes=5):
                return "too_recent"
            scan = TaxiCheckoutScan(payment_id=payment_id, cutoff=now, shop_id=settings.yookassa_shop_id)
            session.add(scan)
        if scan.state != "scanning":
            return scan.state
        cutoff, shop, cursor, match = scan.cutoff, scan.shop_id, scan.cursor, scan.candidate
        created_at, amount = payment.created_at, payment.amount_kop
        session.commit()

    try:
        page = read_taxi_payment_page(payment_id, amount, created_at, cursor=cursor, cutoff=cutoff, match=match)
    except Exception:  # noqa: BLE001 — не двигаем курсор при неизвестном исходе
        return "retry_later"

    with Session(db) as session:
        payment = _lock_taxi_payment(session, payment_id)
        scan = session.get(TaxiCheckoutScan, payment_id)
        if payment is None or scan is None or payment.status != "pending" or payment.provider_id:
            return "changed"
        if (
            (scan.state, scan.cutoff, scan.shop_id, scan.cursor, scan.candidate)
            != ("scanning", cutoff, shop, cursor, match)
            or payment.amount_kop != amount or payment.created_at != created_at
            or settings.yookassa_shop_id != shop
        ):
            return "changed"
        if page.ambiguous:
            scan.state = "ambiguous"
        elif page.cursor is not None:
            scan.cursor, scan.candidate = page.cursor, page.match
        elif page.match is None:
            scan.state = "not_seen"  # отсутствие платежа не является подтверждённой отменой
        elif session.exec(select(Payment.id).where(Payment.provider_id == page.match).limit(1)).first() is not None:
            scan.state = "ambiguous"
        else:
            scan.state = "linked"
            payment.provider_id = page.match
            session.add(payment)
        session.add(scan)
        result = scan.state
        if result == "linked":
            return _sync_linked(session, payment)
        session.commit()
        return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--payment-id", required=True, type=int)
    args = parser.parse_args()
    print(reconcile_taxi_checkout_once(engine, args.payment_id))


if __name__ == "__main__":
    main()
