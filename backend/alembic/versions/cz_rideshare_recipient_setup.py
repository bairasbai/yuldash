"""Record a short-lived driver consent and the result of its confirmation."""

import sqlalchemy as sa
from alembic import op


revision = "cz_rideshare_recipient_setup"
down_revision = "cy_rideshare_bank_account_binding"
branch_labels = None
depends_on = None

_TABLE = "ridesharebankrecipientsetup"


def upgrade() -> None:
    if not op.get_context().as_sql and sa.inspect(op.get_bind()).has_table(_TABLE):
        return
    op.create_table(
        _TABLE,
        sa.Column("id", sa.String(32), primary_key=True),
        sa.Column("user_id", sa.Integer(), sa.ForeignKey("user.id", ondelete="CASCADE"), nullable=False),
        sa.Column("session_fingerprint", sa.String(64), nullable=False),
        sa.Column("test_mode", sa.Boolean(), nullable=False),
        sa.Column("provider_account_id", sa.String(64)),
        sa.Column("expected_version", sa.String(32)),
        sa.Column("paid_revision", sa.BigInteger(), nullable=False),
        sa.Column("card_revision", sa.BigInteger(), nullable=False),
        sa.Column("expires_at", sa.DateTime(), nullable=False),
        sa.Column("confirmed_at", sa.DateTime()),
        sa.Column("command_digest", sa.String(64)),
        sa.Column("result_version", sa.String(32)),
    )
    op.create_index("ix_ridesharebankrecipientsetup_user_id", _TABLE, ["user_id"])


def downgrade() -> None:
    if op.get_context().as_sql or op.get_bind().scalar(
        sa.text("SELECT 1 FROM ridesharebankrecipientsetup LIMIT 1")
    ) is not None:
        raise RuntimeError("Payout recipient consent and confirmation must be preserved")
    op.drop_table(_TABLE)
