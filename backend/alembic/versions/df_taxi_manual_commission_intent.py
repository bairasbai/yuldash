"""Persist commission intent when the passenger or driver closes a taxi order."""

import sqlalchemy as sa
from alembic import op

revision = "df_taxi_manual_commission_intent"
down_revision = "de_taxi_auto_commission_retry"
branch_labels = None
depends_on = None


def upgrade():
    names = set()
    if not op.get_context().as_sql:
        names = {c["name"] for c in sa.inspect(op.get_bind()).get_columns("instantorder")}
    if "commission_close_source" not in names:
        op.add_column("instantorder", sa.Column("commission_close_source", sa.String(16), nullable=True))
    if "commission_close_percent" not in names:
        op.add_column("instantorder", sa.Column("commission_close_percent", sa.Float(), nullable=True))


def downgrade():
    op.drop_column("instantorder", "commission_close_percent")
    op.drop_column("instantorder", "commission_close_source")
