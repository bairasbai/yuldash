"""Record each unique owner authorization alongside its reserved operation."""

import sqlalchemy as sa
from alembic import op


revision = "cw_rideshare_bank_settlement"
down_revision = "cv_rideshare_bank_dispatch"
branch_labels = None
depends_on = None


_TABLE = "ridesharebankapproval"


def upgrade() -> None:
    if not op.get_context().as_sql and sa.inspect(op.get_bind()).has_table(_TABLE):
        return
    op.create_table(
        _TABLE,
        sa.Column("operation_id", sa.String(32), sa.ForeignKey("ridesharebankoperation.id"), primary_key=True),
        sa.Column("command_id", sa.String(32), nullable=False),
        sa.Column("approved_by", sa.Integer(), sa.ForeignKey("user.id", ondelete="SET NULL")),
        sa.Column("approved_at", sa.DateTime(), nullable=False),
        sa.Column("paid_revision", sa.BigInteger()),
        sa.Column("card_revision", sa.BigInteger()),
        sa.UniqueConstraint("command_id", name="uq_rideshare_bank_owner_command"),
    )


def downgrade() -> None:
    if op.get_context().as_sql:
        raise RuntimeError("Bank settlement downgrade requires a history check")
    if op.get_bind().execute(sa.text("SELECT 1 FROM ridesharebankapproval LIMIT 1")).first() is not None:
        raise RuntimeError("Bank owner authorizations must be preserved")
    op.drop_table(_TABLE)
