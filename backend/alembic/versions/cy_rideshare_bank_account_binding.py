"""Preserve the assigned provider account for new bank checkouts."""

import sqlalchemy as sa
from alembic import op


revision = "cy_rideshare_bank_account_binding"
down_revision = "cx_rideshare_bank_queue"
branch_labels = None
depends_on = None

_TABLE = "ridesharebankcheckout"
_COLUMN = "provider_account_id"


def upgrade() -> None:
    offline = op.get_context().as_sql
    if offline or not any(column["name"] == _COLUMN for column in sa.inspect(op.get_bind()).get_columns(_TABLE)):
        op.add_column(_TABLE, sa.Column(_COLUMN, sa.String(64), nullable=True))


def downgrade() -> None:
    checkout = sa.table(_TABLE, sa.column(_COLUMN, sa.String(64)))
    if op.get_context().as_sql or op.get_bind().scalar(
        sa.select(sa.literal(1)).where(checkout.c.provider_account_id.is_not(None)).limit(1)
    ) is not None:
        raise RuntimeError("Cannot drop the account binding without verifying that no checkouts use it")
    op.drop_column(_TABLE, _COLUMN)
