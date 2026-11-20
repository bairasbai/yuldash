"""Add safe defaults for paid integrations and SMS notices."""

from alembic import op


revision = "cq_paid_external_switches"
down_revision = "cp_smsru_receipts"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute(
        "INSERT INTO appsetting (key) VALUES ('external.paid'), ('external.sms_notices') "
        "ON CONFLICT (key) DO NOTHING"
    )


def downgrade() -> None:
    # Record even an unset value to invalidate every request issued before the rollback.
    op.execute(
        "INSERT INTO appsettingchange (key, old_value, new_value, created_at) "
        "SELECT key, value, FALSE, CURRENT_TIMESTAMP FROM appsetting "
        "WHERE key IN ('external.paid', 'external.sms_notices')"
    )
    op.execute(
        "UPDATE appsetting SET value = NULL, updated_at = NULL, updated_by = NULL "
        "WHERE key IN ('external.paid', 'external.sms_notices')"
    )
