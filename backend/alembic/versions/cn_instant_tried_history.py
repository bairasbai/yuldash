"""Inactive normalized taxi tried history; no initialization or runtime activation."""
import sqlalchemy as sa
from alembic import op

revision = "cn_instant_tried_history"
down_revision = "cm_instant_tried_lifecycle"
branch_labels = None
depends_on = None


def upgrade() -> None:
    offline = op.get_context().as_sql
    inspector = None if offline else sa.inspect(op.get_bind())
    columns = set() if offline else {column["name"] for column in inspector.get_columns("instantorder")}
    # Skip only supports create_all bootstrap; existing schema drift is not repaired.
    if "tried_history_version" not in columns:
        op.add_column("instantorder", sa.Column("tried_history_version", sa.Integer(), nullable=True))
    if "tried_history_expires_at" not in columns:
        op.add_column("instantorder", sa.Column("tried_history_expires_at", sa.DateTime(timezone=False), nullable=True))
    if offline or not inspector.has_table("instanttriedexclusion"):
        op.create_table(
            "instanttriedexclusion",
            sa.Column("order_id", sa.Integer(), nullable=False),
            sa.Column("lifecycle", sa.String(32), nullable=False),
            sa.Column("driver_id", sa.Integer(), nullable=False),
            sa.PrimaryKeyConstraint("order_id", "lifecycle", "driver_id"),
            sa.ForeignKeyConstraint(["order_id"], ["instantorder.id"], ondelete="CASCADE"),
        )


def downgrade() -> None:
    op.drop_table("instanttriedexclusion")
    op.drop_column("instantorder", "tried_history_expires_at")
    op.drop_column("instantorder", "tried_history_version")
