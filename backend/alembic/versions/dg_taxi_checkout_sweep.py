"""Persist the taxi checkout sweep lease and index just the pending taxi lane."""

import sqlalchemy as sa
from alembic import op

revision = "dg_taxi_checkout_sweep"
down_revision = "df_taxi_manual_commission_intent"
branch_labels = None
depends_on = None
_NAME = "ix_payment_taxi_sweep_pending"
_PREDICATE = sa.text("status = 'pending' AND purpose = 'ride' AND order_id IS NOT NULL AND method = 'yookassa'")


def upgrade():
    names, indexes = set(), set()
    if not op.get_context().as_sql:
        db = sa.inspect(op.get_bind())
        names = {c["name"] for c in db.get_columns("payment")}
        indexes = {i["name"] for i in db.get_indexes("payment")}
    if "taxi_reconcile_after" not in names:
        op.add_column("payment", sa.Column("taxi_reconcile_after", sa.DateTime(), nullable=True))
    if "taxi_provider_canceled_at" not in names:
        op.add_column("payment", sa.Column("taxi_provider_canceled_at", sa.DateTime(), nullable=True))
    if _NAME not in indexes:
        op.create_index(_NAME, "payment", ["created_at", "id"],
                        postgresql_where=_PREDICATE, sqlite_where=_PREDICATE)


def downgrade():
    op.drop_index(_NAME, table_name="payment")
    op.drop_column("payment", "taxi_provider_canceled_at")
    op.drop_column("payment", "taxi_reconcile_after")
