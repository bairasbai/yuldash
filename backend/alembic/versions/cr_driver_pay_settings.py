"""Store drivers' cash and manual SBP preferences."""

import sqlalchemy as sa
from alembic import op


revision = "cr_driver_pay_settings"
down_revision = "cq_paid_external_switches"
branch_labels = None
depends_on = None


def upgrade() -> None:
    if not op.get_context().as_sql and sa.inspect(op.get_bind()).has_table("driverpaysettings"):
        return
    op.create_table(
        "driverpaysettings",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("user_id", sa.Integer(), sa.ForeignKey("user.id"), nullable=False, unique=True),
        sa.Column("methods", sa.String(8), nullable=False),
        sa.Column("updated_at", sa.DateTime(), nullable=False),
    )


def downgrade() -> None:
    op.drop_table("driverpaysettings")
