"""Store rideshare bank checkouts, reservations, and confirmed postings."""

import sqlalchemy as sa
from alembic import op


revision = "ct_rideshare_bank_accounting"
down_revision = "cs_card_payment_switch"
branch_labels = None
depends_on = None


_CHECKOUT = "ridesharebankcheckout"
_OPERATION = "ridesharebankoperation"
_POSTING = "ridesharebankposting"
_ACTIVE = "state IN ('queued', 'dispatched', 'pending', 'needs_reconciliation')"
_FINAL = "resource IN ('deals', 'payments', 'payouts') AND state = 'succeeded'"
_OPEN = "closed_at IS NULL"


def _index(table: str, name: str, *columns: str, unique: bool = False, where: str | None = None) -> None:
    options = {}
    if where is not None:
        options = {"sqlite_where": sa.text(where), "postgresql_where": sa.text(where)}
    if not op.get_context().as_sql and name in {index["name"] for index in sa.inspect(op.get_bind()).get_indexes(table)}:
        return
    op.create_index(name, table, list(columns), unique=unique, **options)


def upgrade() -> None:
    existing = set() if op.get_context().as_sql else set(sa.inspect(op.get_bind()).get_table_names())
    if _CHECKOUT not in existing:
        op.create_table(
            _CHECKOUT,
            sa.Column("id", sa.Integer(), primary_key=True),
            sa.Column("booking_id", sa.Integer(), sa.ForeignKey("booking.id", ondelete="SET NULL"), nullable=True),
            sa.Column("amount_kop", sa.BigInteger(), nullable=False),
            sa.Column("payout_kop", sa.BigInteger(), nullable=False),
            sa.Column("test_mode", sa.Boolean(), nullable=False),
            sa.Column("created_at", sa.DateTime(), nullable=False),
            sa.Column("closed_at", sa.DateTime(), nullable=True),
            sa.CheckConstraint("amount_kop > 0 AND payout_kop > 0 AND payout_kop <= amount_kop",
                               name="ck_rideshare_bank_checkout_amount"),
        )
    _index(_CHECKOUT, "ix_ridesharebankcheckout_booking_id", "booking_id")
    _index(_CHECKOUT, "uq_rideshare_bank_open_checkout", "booking_id", unique=True, where=_OPEN)

    if _OPERATION not in existing:
        op.create_table(
            _OPERATION,
            sa.Column("id", sa.String(32), primary_key=True),
            sa.Column("checkout_id", sa.Integer(), sa.ForeignKey(f"{_CHECKOUT}.id"), nullable=False),
            sa.Column("resource", sa.String(10), nullable=False),
            sa.Column("state", sa.String(24), nullable=False),
            sa.Column("amount_kop", sa.BigInteger(), nullable=True),
            sa.Column("settlement_kop", sa.BigInteger(), nullable=True),
            sa.Column("provider_id", sa.String(128), nullable=True),
            sa.Column("dispatched_at", sa.DateTime(), nullable=True),
            sa.Column("created_at", sa.DateTime(), nullable=False),
            sa.UniqueConstraint("resource", "provider_id", name="uq_rideshare_bank_provider_object"),
            sa.CheckConstraint("resource IN ('deals', 'payments', 'refunds', 'payouts')",
                               name="ck_rideshare_bank_resource"),
            sa.CheckConstraint(
                "state IN ('queued', 'dispatched', 'pending', 'needs_reconciliation', "
                "'succeeded', 'canceled', 'rejected')", name="ck_rideshare_bank_state",
            ),
            sa.CheckConstraint(
                "(resource = 'deals' AND amount_kop IS NULL AND settlement_kop IS NULL) OR "
                "(resource <> 'deals' AND amount_kop IS NOT NULL AND settlement_kop IS NOT NULL "
                "AND amount_kop > 0 AND settlement_kop > 0 AND settlement_kop <= amount_kop)",
                name="ck_rideshare_bank_operation_amount",
            ),
        )
    _index(_OPERATION, "ix_ridesharebankoperation_checkout_id", "checkout_id")
    _index(_OPERATION, "ix_ridesharebankoperation_state", "state")
    _index(_OPERATION, "uq_rideshare_bank_active_operation", "checkout_id", unique=True, where=_ACTIVE)
    _index(_OPERATION, "uq_rideshare_bank_final_operation", "checkout_id", "resource", unique=True, where=_FINAL)

    if _POSTING not in existing:
        op.create_table(
            _POSTING,
            sa.Column("operation_id", sa.String(32), sa.ForeignKey(f"{_OPERATION}.id"), primary_key=True),
            sa.Column("amount_kop", sa.BigInteger(), nullable=False),
            sa.Column("settlement_kop", sa.BigInteger(), nullable=False),
            sa.Column("recorded_at", sa.DateTime(), nullable=False),
            sa.CheckConstraint("amount_kop > 0 AND settlement_kop > 0 AND settlement_kop <= amount_kop",
                               name="ck_rideshare_bank_posting_amount"),
        )


def downgrade() -> None:
    if op.get_context().as_sql:
        raise RuntimeError("Bank accounting downgrade requires an empty database check")
    connection = op.get_bind()
    for table in (_CHECKOUT, _OPERATION, _POSTING):
        if connection.execute(sa.text(f"SELECT 1 FROM {table} LIMIT 1")).first() is not None:
            raise RuntimeError("Bank accounting history must be preserved")
    for table in (_POSTING, _OPERATION, _CHECKOUT):
        op.drop_table(table)
