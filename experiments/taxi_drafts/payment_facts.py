"""Synthetic receipt projection. NOT a ledger, proof verifier or money operation.

Each operation has one authoritative snapshot. Pending->confirmed reconciliation
belongs to a future adapter, not to last-write-wins ordering of these fixtures.
"""
from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum

from .contracts import InvalidDraft, identifier, instant, integer


def _reference(value: str) -> None:
    identifier(value)
    if not value.strip():
        raise InvalidDraft("blank_reference")


@dataclass(frozen=True)
class PaymentScope:
    """Original collection direction. REFUND reverses it, retaining this scope."""
    order_ref: str = field(repr=False)
    payer_ref: str = field(repr=False)
    payee_ref: str = field(repr=False)
    currency: str

    def __post_init__(self):
        for value in (self.order_ref, self.payer_ref, self.payee_ref):
            _reference(value)
        if self.currency != "RUB" or self.payer_ref == self.payee_ref:
            raise InvalidDraft("invalid_payment_scope")


class MoneyKind(str, Enum):
    COLLECTION = "collection"
    REFUND = "refund"


class Confirmation(str, Enum):
    PENDING = "pending"
    UNKNOWN = "unknown"
    CONFIRMED = "confirmed"
    FAILED = "failed"


class MoneyMethod(str, Enum):
    CASH = "cash"
    DIRECT_SBP = "direct_sbp"
    PROVIDER = "provider"


@dataclass(frozen=True)
class OperationFact:
    """Trusted fixture inputs, NOT client fields. evidence_ref alone proves nothing."""
    operation_ref: str = field(repr=False)
    scope: PaymentScope
    kind: MoneyKind
    amount_kop: int
    status: Confirmation
    method: MoneyMethod
    occurred_at: datetime
    original_collection_ref: str | None = field(default=None, repr=False)
    evidence_ref: str | None = field(default=None, repr=False)

    def __post_init__(self):
        _reference(self.operation_ref)
        if (type(self.scope) is not PaymentScope or type(self.kind) is not MoneyKind
                or type(self.status) is not Confirmation or type(self.method) is not MoneyMethod):
            raise InvalidDraft("invalid_fact_type")
        integer(self.amount_kop, minimum=1)
        instant(self.occurred_at)
        if self.kind is MoneyKind.REFUND:
            _reference(self.original_collection_ref)
        elif self.original_collection_ref is not None:
            raise InvalidDraft("collection_cannot_reference_collection")
        if self.evidence_ref is not None:
            _reference(self.evidence_ref)
        if self.status is Confirmation.CONFIRMED and self.evidence_ref is None:
            raise InvalidDraft("confirmation_evidence_missing")


@dataclass(frozen=True)
class PaymentProjection:
    issues: tuple[str, ...]
    confirmed_received_kop: int | None
    confirmed_returned_kop: int | None
    unresolved_refund_reserve_kop: int | None
    remaining_confirmed_return_capacity_kop: int | None
    unconfirmed_operation_count: int | None
    limitation: str = "Synthetic only: NOT_IMPLEMENTED provenance, authorization, ledger execution."


def project_payment_facts(facts: tuple[OperationFact, ...], *, expected_scope: PaymentScope,
                          as_of: datetime) -> PaymentProjection:
    """No writes. No paid/order-done/debt/amount-due fields or refund authorization.

    Cash/SBP return is counted only from an explicitly confirmed fixture receipt;
    neither a client claim nor completed trip is an accepted input. UNKNOWN refund
    reserves capacity because a timeout may conceal success. Separate compensation,
    coupons, disputes/chargebacks, rent, driver liability and payouts are absent.
    """
    instant(as_of)
    if type(expected_scope) is not PaymentScope or type(facts) is not tuple:
        raise InvalidDraft("invalid_projection_input")
    operations, evidence_owners, issues = {}, {}, set()
    for fact in facts:
        if type(fact) is not OperationFact:
            raise InvalidDraft("invalid_operation_fact")
        if fact.scope != expected_scope:
            issues.add("scope_mismatch")
        if fact.occurred_at > as_of:
            issues.add("future_fact")
        if fact.status is Confirmation.CONFIRMED:
            owner = evidence_owners.get(fact.evidence_ref)
            if owner is not None and owner != fact.operation_ref:
                issues.add("confirmation_evidence_reused")
            evidence_owners[fact.evidence_ref] = fact.operation_ref
        previous = operations.get(fact.operation_ref)
        if previous is not None and previous != fact:
            issues.add("operation_identity_conflict")
        operations[fact.operation_ref] = fact
    # Conflicting duplicates have no arbitrary winner or dependent calculations.
    if issues:
        return PaymentProjection(tuple(sorted(issues)), None, None, None, None, None)
    collections = {ref: fact for ref, fact in operations.items() if fact.kind is MoneyKind.COLLECTION}
    consumed, confirmed_returned, reserve = {}, 0, 0
    for fact in operations.values():
        if fact.kind is not MoneyKind.REFUND:
            continue
        original = collections.get(fact.original_collection_ref)
        if original is None or original.status is not Confirmation.CONFIRMED:
            issues.add("refund_original_not_confirmed")
            continue
        if fact.method is not original.method:
            issues.add("refund_method_mismatch")
        if fact.occurred_at < original.occurred_at:
            issues.add("refund_precedes_collection")
        if fact.status is Confirmation.FAILED:
            continue
        ref = fact.original_collection_ref
        consumed[ref] = consumed.get(ref, 0) + fact.amount_kop
        if fact.status is Confirmation.CONFIRMED:
            confirmed_returned += fact.amount_kop
        else:  # pending and unknown both reserve; neither claims a return happened.
            reserve += fact.amount_kop
    for ref, amount in consumed.items():
        if amount > collections[ref].amount_kop:
            issues.add("refund_capacity_exceeded")
    if issues:
        return PaymentProjection(tuple(sorted(issues)), None, None, None, None, None)
    received = sum(f.amount_kop for f in collections.values() if f.status is Confirmation.CONFIRMED)
    unknown = sum(f.status in (Confirmation.PENDING, Confirmation.UNKNOWN) for f in operations.values())
    return PaymentProjection((), received, confirmed_returned, reserve,
                             received-confirmed_returned-reserve, unknown)
