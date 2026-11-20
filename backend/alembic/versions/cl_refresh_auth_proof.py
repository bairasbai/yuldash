"""Preserve verified login method and time through refresh rotation."""

import sqlalchemy as sa
from alembic import op


revision = "cl_refresh_auth_proof"
down_revision = "ck_owner_switches"
branch_labels = None
depends_on = None


def upgrade() -> None:
    existing = (
        set() if op.get_context().as_sql else
        {item["name"] for item in sa.inspect(op.get_bind()).get_columns("refreshtoken")}
    )
    if "auth_method" not in existing:
        op.add_column("refreshtoken", sa.Column("auth_method", sa.String(12), server_default="legacy", nullable=False))
    if "auth_time" not in existing:
        op.add_column("refreshtoken", sa.Column("auth_time", sa.DateTime(), nullable=True))


def downgrade() -> None:
    op.drop_column("refreshtoken", "auth_time")
    op.drop_column("refreshtoken", "auth_method")
