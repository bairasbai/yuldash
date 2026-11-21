"""Schedule bounded bank queue passes without editing monetary records."""

import sqlalchemy as sa
from alembic import op


revision = "cx_rideshare_bank_queue"
down_revision = "cw_rideshare_bank_settlement"
branch_labels = None
depends_on = None

_TABLE = "ridesharebankcheckout"
_COLUMN = "next_bank_check_at"
_INDEX = "ix_ridesharebankcheckout_next_bank_check_at"


def upgrade() -> None:
    offline = op.get_context().as_sql
    inspector = None if offline else sa.inspect(op.get_bind())
    if offline or all(col["name"] != _COLUMN for col in inspector.get_columns(_TABLE)):
        op.add_column(_TABLE, sa.Column(_COLUMN, sa.DateTime(), nullable=True))
    if offline or all(index["name"] != _INDEX for index in inspector.get_indexes(_TABLE)):
        op.create_index(_INDEX, _TABLE, [_COLUMN])


def downgrade() -> None:
    # Only the next inspection time is lost. Monetary admissions and history survive.
    op.drop_index(_INDEX, table_name=_TABLE)
    op.drop_column(_TABLE, _COLUMN)
