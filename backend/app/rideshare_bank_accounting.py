"""Calculate and reserve funds outside the ordinary driver wallet."""

from sqlalchemy import update
from sqlmodel import Session, select

from .rideshare_bank_models import (
    BANK_ACTIVE_STATES,
    RideshareBankCheckout,
    RideshareBankOperation,
    RideshareBankPosting,
)
from .rideshare_bank_provider import BankObservation, BankResource


class BankSettlementUnavailable(ValueError):
    """The verified bank balance or a concurrent operation blocks this request."""


def _lock_checkout(session: Session, checkout_id: int) -> RideshareBankCheckout:
    if session.get_bind().dialect.name == "sqlite":
        session.execute(update(RideshareBankCheckout).where(RideshareBankCheckout.id == checkout_id)
                        .values(id=RideshareBankCheckout.id))
    checkout = session.get(RideshareBankCheckout, checkout_id, with_for_update=True, populate_existing=True)
    if checkout is None:
        raise BankSettlementUnavailable("Bank checkout is unavailable")
    return checkout


def reserve_settlement(session: Session, checkout_id: int, resource: BankResource, *, amount_kop: int,
                       settlement_kop: int) -> RideshareBankOperation:
    """Reserve one refund or payout. The caller owns authorization and the transaction."""
    if not isinstance(resource, BankResource) or resource not in (BankResource.refund, BankResource.payout):
        raise ValueError("Expected a refund or payout")
    if type(amount_kop) is not int or type(settlement_kop) is not int or not 0 < settlement_kop <= amount_kop:
        raise ValueError("Invalid settlement amount in kopecks")

    checkout = _lock_checkout(session, checkout_id)
    if checkout.closed_at is not None or (resource == BankResource.payout and checkout.booking_id is None):
        raise BankSettlementUnavailable("Bank checkout is unavailable")

    live = session.exec(select(RideshareBankOperation.id).where(
        RideshareBankOperation.checkout_id == checkout_id,
        RideshareBankOperation.state.in_(BANK_ACTIVE_STATES),
    ).limit(1)).first()
    if live is not None:
        raise BankSettlementUnavailable("Another bank operation still reserves this checkout")

    posted = session.exec(
        select(RideshareBankOperation.resource, RideshareBankPosting)
        .join(RideshareBankOperation, RideshareBankPosting.operation_id == RideshareBankOperation.id)
        .where(RideshareBankOperation.checkout_id == checkout_id, RideshareBankOperation.state == "succeeded")
    ).all()
    payment = next((posting for kind, posting in posted if kind == BankResource.payment.value), None)
    if (payment is None or payment.amount_kop != checkout.amount_kop or payment.settlement_kop != checkout.payout_kop
            or any(kind == BankResource.payout.value for kind, _ in posted)):
        raise BankSettlementUnavailable("Bank funds are unavailable for settlement")
    refunds = [posting for kind, posting in posted if kind == BankResource.refund.value]
    refundable = payment.amount_kop - sum(refund.amount_kop for refund in refunds)
    payable = payment.settlement_kop - sum(refund.settlement_kop for refund in refunds)
    if resource == BankResource.payout:
        if amount_kop != payable or settlement_kop != amount_kop:
            raise BankSettlementUnavailable("Payout does not match the remaining verified balance")
    elif (amount_kop > refundable or settlement_kop > payable
          or (amount_kop == refundable) != (settlement_kop == payable)):
        raise BankSettlementUnavailable("Refund does not match the remaining verified balance")

    operation = RideshareBankOperation(checkout_id=checkout_id, resource=resource.value,
                                      amount_kop=amount_kop, settlement_kop=settlement_kop)
    session.add(operation)
    session.flush()
    return operation


def _successful_provider_id(session: Session, checkout_id: int, resource: BankResource) -> str | None:
    return session.exec(select(RideshareBankOperation.provider_id).where(
        RideshareBankOperation.checkout_id == checkout_id,
        RideshareBankOperation.resource == resource.value,
        RideshareBankOperation.state == "succeeded",
    )).one_or_none()


def record_bank_observation(session: Session, checkout_id: int, operation_id: str,
                            observed: BankObservation) -> RideshareBankOperation:
    """Apply a verified provider result. The caller commits receipt and accounting together."""
    checkout = _lock_checkout(session, checkout_id)
    operation = session.get(RideshareBankOperation, operation_id, with_for_update=True, populate_existing=True)
    if (operation is None or operation.checkout_id != checkout_id or operation.dispatched_at is None or
            operation.state == "queued" or
            operation.resource != observed.resource.value or observed.operation_id != operation_id or
            (operation.provider_id is not None and operation.provider_id != observed.provider_id)):
        raise BankSettlementUnavailable("Bank observation does not identify the admitted operation")
    resource = observed.resource
    if observed.test is not checkout.test_mode and not (resource == BankResource.refund and observed.test is None):
        raise BankSettlementUnavailable("Bank observation has the wrong environment")

    if resource == BankResource.deal:
        if observed.status != "opened":
            raise BankSettlementUnavailable("The created bank deal is not open")
        state = "succeeded"
    else:
        deal_id = _successful_provider_id(session, checkout_id, BankResource.deal)
        settlement = observed.amount_kop if resource == BankResource.payout else observed.settlement_kop
        if (deal_id is None or deal_id != observed.deal_id or observed.amount_kop != operation.amount_kop or
                settlement != operation.settlement_kop):
            raise BankSettlementUnavailable("Bank amount or deal differs from the admitted operation")
        if resource == BankResource.refund:
            payment_id = _successful_provider_id(session, checkout_id, BankResource.payment)
            if payment_id is None or payment_id != observed.payment_id:
                raise BankSettlementUnavailable("Bank refund refers to another payment")
        state = "pending" if observed.status == "waiting_for_capture" else observed.status
        if state not in {"pending", "succeeded", "canceled"}:
            raise BankSettlementUnavailable("Bank observation has an unexpected status")

    if operation.state in {"succeeded", "canceled", "rejected"}:
        if operation.state == state or (state == "pending" and operation.provider_id == observed.provider_id):
            return operation
        raise BankSettlementUnavailable("Bank observation contradicts a terminal result")

    operation.state = state
    operation.provider_id = observed.provider_id
    if state == "succeeded" and resource != BankResource.deal:
        session.add(RideshareBankPosting(operation_id=operation.id, amount_kop=operation.amount_kop,
                                        settlement_kop=operation.settlement_kop))
    session.flush()
    return operation
