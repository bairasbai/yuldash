"""Keep the outcome of a driver cancellation command across lost responses."""
from alembic import op
import sqlalchemy as sa


revision = "dc_taxi_driver_cancel_receipt"
down_revision = "db_chat_message_receipt"
branch_labels = None
depends_on = None


def upgrade():
    if not op.get_context().as_sql and sa.inspect(op.get_bind()).has_table("taxidrivercancelreceipt"):
        return  # Исторический bootstrap может создать таблицу из актуальной metadata.
    op.create_table(
        "taxidrivercancelreceipt",
        sa.Column("order_id", sa.Integer(), sa.ForeignKey("instantorder.id", ondelete="CASCADE"), primary_key=True),
        sa.Column("driver_id", sa.Integer(), sa.ForeignKey("user.id", ondelete="CASCADE"), primary_key=True),
        sa.Column("command_id", sa.String(32), primary_key=True),
        sa.Column("assignment_revision", sa.String(64), nullable=False),
        sa.Column("reason", sa.String(16), nullable=False),
        sa.Column("outcome", sa.String(12), nullable=False),
        sa.Column("cancel_fee_kop", sa.Integer(), nullable=False),
        sa.CheckConstraint(
            "outcome IN ('released', 'cancelled', 'no_show') AND cancel_fee_kop >= 0 "
            "AND (outcome = 'no_show' OR cancel_fee_kop = 0)",
            name="ck_taxi_driver_cancel_receipt_outcome",
        ),
    )
    op.create_index("ix_taxidrivercancelreceipt_driver_id", "taxidrivercancelreceipt", ["driver_id"])


def downgrade():
    op.drop_table("taxidrivercancelreceipt")
