"""Give JWT and refresh credentials an explicit generation."""

import sqlalchemy as sa
from alembic import op


revision = "cm_auth_epoch"
down_revision = "cl_refresh_auth_proof"
branch_labels = None
depends_on = None

TABLES = ("user", "refreshtoken")


def upgrade() -> None:
    offline = op.get_context().as_sql
    for table in TABLES:
        if offline or "auth_epoch" not in {
            column["name"] for column in sa.inspect(op.get_bind()).get_columns(table)
        }:
            op.add_column(table, sa.Column("auth_epoch", sa.BigInteger(), server_default="0", nullable=False))


def downgrade() -> None:
    for table in reversed(TABLES):
        op.drop_column(table, "auth_epoch")
