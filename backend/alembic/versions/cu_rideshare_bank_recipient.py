"""Store only ciphertext and a revision for the driver's payout recipient."""

import sqlalchemy as sa
from alembic import op


revision = "cu_rideshare_bank_recipient"
down_revision = "ct_rideshare_bank_accounting"
branch_labels = None
depends_on = None

_TABLE = "ridesharebankrecipient"


def upgrade() -> None:
    if not op.get_context().as_sql and sa.inspect(op.get_bind()).has_table(_TABLE):
        return
    op.create_table(
        _TABLE,
        sa.Column("user_id", sa.Integer(), sa.ForeignKey("user.id", ondelete="CASCADE"), primary_key=True),
        sa.Column("version", sa.String(32), nullable=False),
        sa.Column("token_ciphertext", sa.LargeBinary(), nullable=True),
        sa.Column("updated_at", sa.DateTime(), nullable=False),
    )


def downgrade() -> None:
    if op.get_context().as_sql:
        raise RuntimeError("Bank payout recipient downgrade requires an empty database check")
    if op.get_bind().execute(sa.text("SELECT 1 FROM ridesharebankrecipient LIMIT 1")).first() is not None:
        raise RuntimeError("Bank payout recipient versions must be preserved")
    op.drop_table(_TABLE)
