"""Store SMS quota, dispatch outcomes and hashed code verification."""

from datetime import datetime, timezone

import sqlalchemy as sa
from alembic import op


revision = "co_sms_budget"
down_revision = "cn_admin_telegram_cutover"
branch_labels = None
depends_on = None


def upgrade() -> None:
    offline = op.get_context().as_sql
    inspector = None if offline else sa.inspect(op.get_bind())
    if offline or not inspector.has_table("otpbudgetbucket"):
        op.create_table(
            "otpbudgetbucket",
            sa.Column("scope", sa.String(24), primary_key=True),
            sa.Column("subject", sa.String(64), primary_key=True),
            sa.Column("window_start", sa.BigInteger(), primary_key=True),
            sa.Column("used", sa.Integer(), nullable=False),
            sa.Column("cap", sa.Integer(), nullable=False),
            sa.Column("version", sa.Integer(), nullable=False),
            sa.Column("updated_at", sa.DateTime(), nullable=False),
            sa.Column("next_allowed_at", sa.DateTime()),
        )
        op.create_index("ix_otpbudgetbucket_window_start", "otpbudgetbucket", ["window_start"])
    if offline or not inspector.has_table("otpsendattempt"):
        op.create_table(
            "otpsendattempt",
            sa.Column("id", sa.String(36), primary_key=True),
            sa.Column("provider_idempotency_key", sa.String(48), nullable=False, unique=True),
            sa.Column("state", sa.String(8), nullable=False),
            sa.Column("bucket_keys", sa.JSON(), nullable=False),
            sa.Column("created_at", sa.DateTime(), nullable=False),
            sa.Column("updated_at", sa.DateTime(), nullable=False),
        )
        op.create_index("ix_otpsendattempt_created_at", "otpsendattempt", ["created_at"])
    if offline or not inspector.has_table("otpbudgetalert"):
        op.create_table(
            "otpbudgetalert",
            sa.Column("service_day", sa.BigInteger(), primary_key=True),
            sa.Column("kind", sa.String(24), primary_key=True),
            sa.Column("created_at", sa.DateTime(), nullable=False),
        )

    existing = set() if offline else {column["name"] for column in inspector.get_columns("otpcode")}
    columns = (
        sa.Column("channel", sa.String(), server_default="sms", nullable=False),
        sa.Column("ip_hash", sa.String(64)),
        sa.Column("device_hash", sa.String(64)),
        sa.Column("attempt_id", sa.String(36)),
    )
    for column in columns:
        if column.name not in existing:
            op.add_column("otpcode", column)
    for name in ("ip_hash", "device_hash"):
        if name not in existing:
            op.create_index(f"ix_otpcode_{name}", "otpcode", [name])
    if "attempt_id" not in existing:
        op.create_index("ix_otpcode_attempt_id", "otpcode", ["attempt_id"], unique=True)

    otp = sa.table("otpcode", sa.column("attempt_id"), sa.column("code"), sa.column("expires_at"))
    cutoff = op.inline_literal(datetime.now(timezone.utc).replace(tzinfo=None), type_=sa.DateTime())
    op.execute(otp.update().where(otp.c.attempt_id.is_(None), otp.c.code != op.inline_literal(""))
               .values(code=op.inline_literal(""), expires_at=cutoff))


def downgrade() -> None:
    # Old code understands plaintext OTP only. Expire these rather than revive old proofs.
    otp = sa.table("otpcode", sa.column("attempt_id"), sa.column("code"), sa.column("expires_at"))
    cutoff = op.inline_literal(datetime.now(timezone.utc).replace(tzinfo=None), type_=sa.DateTime())
    op.execute(otp.update().where(otp.c.attempt_id.is_not(None))
               .values(code=op.inline_literal(""), expires_at=cutoff))
    for name in ("attempt_id", "device_hash", "ip_hash"):
        op.drop_index(f"ix_otpcode_{name}", table_name="otpcode")
        op.drop_column("otpcode", name)
    op.drop_column("otpcode", "channel")
    for name in ("otpbudgetalert", "otpsendattempt", "otpbudgetbucket"):
        op.drop_table(name)
