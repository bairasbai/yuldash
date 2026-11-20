"""Store the disabled rideshare card switch and reserved driver consent fields."""

import sqlalchemy as sa
from alembic import op


revision = "cs_card_payment_switch"
down_revision = "cr_driver_pay_settings"
branch_labels = None
depends_on = None


def upgrade() -> None:
    columns = set()
    if not op.get_context().as_sql:
        columns = {column["name"] for column in sa.inspect(op.get_bind()).get_columns("driverpaysettings")}
    if "accepts_card" not in columns:
        op.add_column("driverpaysettings", sa.Column("accepts_card", sa.Boolean(), nullable=False, server_default=sa.false()))
    if "accepts_card_at" not in columns:
        op.add_column("driverpaysettings", sa.Column("accepts_card_at", sa.DateTime(), nullable=True))
    op.execute(
        "INSERT INTO appsetting (key, value) VALUES ('rideshare_card_payments', FALSE) ON CONFLICT (key) DO NOTHING"
    )


def downgrade() -> None:
    op.execute(
        "INSERT INTO appsettingchange (key, old_value, new_value, created_at) "
        "SELECT key, value, FALSE, CURRENT_TIMESTAMP FROM appsetting WHERE key = 'rideshare_card_payments'"
    )
    # The history references the setting by foreign key on both databases.
    # Earlier runtimes ignore this dormant key.
    op.execute(
        "UPDATE appsetting SET value = FALSE, updated_at = NULL, updated_by = NULL "
        "WHERE key = 'rideshare_card_payments'"
    )
    op.drop_column("driverpaysettings", "accepts_card_at")
    op.drop_column("driverpaysettings", "accepts_card")
