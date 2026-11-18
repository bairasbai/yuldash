"""Nullable durable taxi search revision foundation; no writer enforcement or backfill."""
import sqlalchemy as sa
from alembic import op

revision = "cl_instant_search_revision"
down_revision = "ck_instant_offer_token"
branch_labels = None
depends_on = None


def upgrade() -> None:
    if op.get_context().as_sql or "search_revision" not in {
        column["name"] for column in sa.inspect(op.get_bind()).get_columns("instantorder")
    }:
        op.add_column("instantorder", sa.Column("search_revision", sa.BigInteger(), nullable=True))


def downgrade() -> None:
    op.drop_column("instantorder", "search_revision")
