"""Минимальный журнал сохранённых намерений попутки."""

import sqlalchemy as sa
from alembic import op


revision = "cg_command_receipt_20261111"
down_revision = "cf_merge_legacy_safety_20261102"
branch_labels = None
depends_on = None


def upgrade():
    # Базовая ревизия на новой базе уже использует актуальные SQLModel.
    if not op.get_context().as_sql and sa.inspect(op.get_bind()).has_table("commandreceipt"):
        return
    op.create_table(
        "commandreceipt",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("user_id", sa.Integer(), sa.ForeignKey("user.id", ondelete="CASCADE"), nullable=False),
        sa.Column("request_key", sa.String(128), nullable=False),
        sa.Column("scope", sa.String(40), nullable=False),
        sa.Column("target_id", sa.Integer(), nullable=True),
        sa.Column("payload_hash", sa.String(64), nullable=False),
        sa.Column("state", sa.String(24), nullable=False),
        sa.Column("ref_kind", sa.String(16), nullable=True),
        sa.Column("ref_id", sa.Integer(), nullable=True),
        sa.Column("ref_created_at", sa.DateTime(), nullable=True),
        sa.Column("result_code", sa.String(64), nullable=True),
        sa.Column("created_at", sa.DateTime(), nullable=False),
        sa.UniqueConstraint("user_id", "request_key", name="uq_command_receipt_owner_key"),
        sa.CheckConstraint(
            "(state = 'applied' AND ref_kind IS NOT NULL AND ref_kind IN ('ride', 'booking') "
            "AND ref_id IS NOT NULL AND ref_created_at IS NOT NULL AND result_code IS NULL) OR "
            "(state IN ('rejected', 'closed_no_effect') AND ref_kind IS NULL "
            "AND ref_id IS NULL AND ref_created_at IS NULL AND result_code IS NOT NULL)",
            name="ck_command_receipt_result",
        ),
    )
    op.create_index("ix_commandreceipt_user_id", "commandreceipt", ["user_id"])
    op.create_index("ix_commandreceipt_created_at", "commandreceipt", ["created_at"])


def downgrade():
    op.drop_table("commandreceipt")
