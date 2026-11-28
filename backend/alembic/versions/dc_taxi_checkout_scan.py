"""Persist progress through a fixed YooKassa merchant window for taxi checkout recovery."""
import sqlalchemy as sa
from alembic import op

revision = "dc_taxi_checkout_scan"
down_revision = "db_chat_message_receipt"
branch_labels = None
depends_on = None


def upgrade():
    if not op.get_context().as_sql and sa.inspect(op.get_bind()).has_table("taxicheckoutscan"):
        return
    op.create_table(
        "taxicheckoutscan",
        sa.Column("payment_id", sa.Integer, sa.ForeignKey("payment.id", ondelete="CASCADE"), primary_key=True),
        sa.Column("cutoff", sa.DateTime, nullable=False),
        sa.Column("shop_id", sa.String(128), nullable=False),
        sa.Column("cursor", sa.String(512)),
        sa.Column("candidate", sa.String(36)),
        sa.Column("state", sa.String(16), nullable=False),
    )


def downgrade():
    op.drop_table("taxicheckoutscan")
