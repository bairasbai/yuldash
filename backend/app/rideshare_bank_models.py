"""Separate accounting for amounts held by the rideshare bank provider."""

from datetime import datetime
from uuid import uuid4

from sqlalchemy import BigInteger, Boolean, CheckConstraint, Column, Index, LargeBinary, UniqueConstraint, false, text
from sqlmodel import Field, SQLModel

from .timeutil import utcnow


BANK_ACTIVE_STATES = ("queued", "dispatched", "pending", "needs_reconciliation")
_ACTIVE = "state IN ('queued', 'dispatched', 'pending', 'needs_reconciliation')"
_FINAL = "resource IN ('deals', 'payments', 'payouts') AND state = 'succeeded'"
_OPEN = "closed_at IS NULL"


class RideshareBankRecipient(SQLModel, table=True):
    user_id: int = Field(primary_key=True, foreign_key="user.id", ondelete="CASCADE")
    version: str = Field(default_factory=lambda: uuid4().hex, max_length=32)
    token_ciphertext: bytes | None = Field(default=None, sa_column=Column(LargeBinary, nullable=True))
    updated_at: datetime = Field(default_factory=utcnow)


class RideshareBankRecipientSetup(SQLModel, table=True):
    id: str = Field(default_factory=lambda: uuid4().hex, primary_key=True, max_length=32)
    user_id: int = Field(foreign_key="user.id", ondelete="CASCADE", index=True)
    session_fingerprint: str = Field(max_length=64)
    test_mode: bool
    provider_account_id: str | None = Field(default=None, max_length=64)
    expected_version: str | None = Field(default=None, max_length=32)
    paid_revision: int = Field(sa_column=Column(BigInteger, nullable=False))
    card_revision: int = Field(sa_column=Column(BigInteger, nullable=False))
    expires_at: datetime
    confirmed_at: datetime | None = None
    command_digest: str | None = Field(default=None, max_length=64)
    result_version: str | None = Field(default=None, max_length=32)


class RideshareBankCheckout(SQLModel, table=True):
    __table_args__ = (
        CheckConstraint("amount_kop > 0 AND payout_kop > 0 AND payout_kop <= amount_kop",
                        name="ck_rideshare_bank_checkout_amount"),
        Index("uq_rideshare_bank_open_checkout", "booking_id", unique=True,
              postgresql_where=text(_OPEN), sqlite_where=text(_OPEN)),
    )

    id: int | None = Field(default=None, primary_key=True)
    booking_id: int | None = Field(default=None, foreign_key="booking.id", ondelete="SET NULL", index=True)
    amount_kop: int = Field(sa_column=Column(BigInteger, nullable=False))
    payout_kop: int = Field(sa_column=Column(BigInteger, nullable=False))
    test_mode: bool
    provider_account_id: str | None = Field(default=None, max_length=64)
    created_at: datetime = Field(default_factory=utcnow)
    closed_at: datetime | None = None
    paid_revision: int | None = Field(default=None, sa_column=Column(BigInteger, nullable=True))
    card_revision: int | None = Field(default=None, sa_column=Column(BigInteger, nullable=True))
    recipient_version: str | None = Field(default=None, max_length=32)
    driver_consent_at: datetime | None = None
    next_bank_check_at: datetime | None = Field(default=None, index=True)
    receipt_conflict: bool = Field(default=False, sa_column=Column(Boolean, nullable=False, server_default=false()))


class RideshareBankOperation(SQLModel, table=True):
    __table_args__ = (
        UniqueConstraint("resource", "provider_id", name="uq_rideshare_bank_provider_object"),
        CheckConstraint("resource IN ('deals', 'payments', 'refunds', 'payouts')",
                        name="ck_rideshare_bank_resource"),
        CheckConstraint("state IN ('queued', 'dispatched', 'pending', 'needs_reconciliation', "
                        "'succeeded', 'canceled', 'rejected')", name="ck_rideshare_bank_state"),
        CheckConstraint(
            "(resource = 'deals' AND amount_kop IS NULL AND settlement_kop IS NULL) OR "
            "(resource <> 'deals' AND amount_kop IS NOT NULL AND settlement_kop IS NOT NULL "
            "AND amount_kop > 0 AND settlement_kop > 0 AND settlement_kop <= amount_kop)",
            name="ck_rideshare_bank_operation_amount",
        ),
        Index("uq_rideshare_bank_active_operation", "checkout_id", unique=True,
              postgresql_where=text(_ACTIVE), sqlite_where=text(_ACTIVE)),
        Index("uq_rideshare_bank_final_operation", "checkout_id", "resource", unique=True,
              postgresql_where=text(_FINAL), sqlite_where=text(_FINAL)),
    )

    # The opaque local identifier is also the stable key and provider metadata reference.
    id: str = Field(default_factory=lambda: uuid4().hex, primary_key=True, max_length=32)
    checkout_id: int = Field(foreign_key="ridesharebankcheckout.id", index=True)
    resource: str = Field(max_length=10)
    state: str = Field(default="queued", max_length=24, index=True)
    amount_kop: int | None = Field(default=None, sa_column=Column(BigInteger, nullable=True))
    settlement_kop: int | None = Field(default=None, sa_column=Column(BigInteger, nullable=True))
    provider_id: str | None = Field(default=None, max_length=128)
    dispatched_at: datetime | None = None
    created_at: datetime = Field(default_factory=utcnow)
    request_ciphertext: bytes | None = Field(default=None, sa_column=Column(LargeBinary, nullable=True))
    confirmation_ciphertext: bytes | None = Field(default=None, sa_column=Column(LargeBinary, nullable=True))
    lease_token: str | None = Field(default=None, max_length=32)
    lease_until: datetime | None = None


class RideshareBankApproval(SQLModel, table=True):
    __table_args__ = (UniqueConstraint("command_id", name="uq_rideshare_bank_owner_command"),)

    operation_id: str = Field(primary_key=True, foreign_key="ridesharebankoperation.id", max_length=32)
    command_id: str = Field(max_length=32)
    approved_by: int | None = Field(default=None, foreign_key="user.id", ondelete="SET NULL")
    approved_at: datetime = Field(default_factory=utcnow)
    paid_revision: int | None = Field(default=None, sa_column=Column(BigInteger, nullable=True))
    card_revision: int | None = Field(default=None, sa_column=Column(BigInteger, nullable=True))


class RideshareBankPosting(SQLModel, table=True):
    __table_args__ = (
        CheckConstraint("amount_kop > 0 AND settlement_kop > 0 AND settlement_kop <= amount_kop",
                        name="ck_rideshare_bank_posting_amount"),
    )

    operation_id: str = Field(primary_key=True, foreign_key="ridesharebankoperation.id", max_length=32)
    amount_kop: int = Field(sa_column=Column(BigInteger, nullable=False))
    settlement_kop: int = Field(sa_column=Column(BigInteger, nullable=False))
    recorded_at: datetime = Field(default_factory=utcnow)
