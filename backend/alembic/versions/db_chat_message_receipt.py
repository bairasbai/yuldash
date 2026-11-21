"""Store individual delivery and read confirmations for rideshare messages."""
from alembic import op
import sqlalchemy as sa


revision = "db_chat_message_receipt"
down_revision = "da_route_watch_dispatch"
branch_labels = None
depends_on = None


def upgrade():
    if not op.get_context().as_sql and sa.inspect(op.get_bind()).has_table("chatmessagereceipt"):
        return
    op.create_table(
        "chatmessagereceipt",
        sa.Column("message_id", sa.Integer(), sa.ForeignKey("message.id", ondelete="CASCADE"), primary_key=True),
        sa.Column("user_id", sa.Integer(), sa.ForeignKey("user.id", ondelete="CASCADE"), primary_key=True),
        sa.Column("delivered", sa.Boolean(), nullable=False, server_default=sa.false()),
        sa.Column("read", sa.Boolean(), nullable=False, server_default=sa.false()),
        sa.Column("updated_at", sa.DateTime(), nullable=False),
        sa.CheckConstraint("NOT read OR delivered", name="ck_chat_message_receipt_read_requires_delivery"),
    )
    op.create_index("ix_chatmessagereceipt_user_id", "chatmessagereceipt", ["user_id"])


def downgrade():
    op.drop_table("chatmessagereceipt")
