"""Persist commission recovery for newly auto-completed taxi orders."""
import sqlalchemy as sa
from alembic import op

revision = "de_taxi_auto_commission_retry"
down_revision = "dd_taxi_assessed_commission"
branch_labels = None
depends_on = None
_INDEX = "ix_instantorder_auto_commission_retry_at"


def upgrade():
    if op.get_context().as_sql:
        column_exists = index_exists = False
    else:
        meta = sa.inspect(op.get_bind())
        column_exists = any(c["name"] == "auto_commission_retry_at" for c in meta.get_columns("instantorder"))
        index_exists = any(i["name"] == _INDEX for i in meta.get_indexes("instantorder"))
    if not column_exists:
        op.add_column("instantorder", sa.Column("auto_commission_retry_at", sa.DateTime(), nullable=True))
    if not index_exists:
        op.create_index(_INDEX, "instantorder", ["auto_commission_retry_at"])


def downgrade():
    op.drop_index(_INDEX, table_name="instantorder")
    op.drop_column("instantorder", "auto_commission_retry_at")
