"""Join the published taxi price-consent and rideshare receipt histories."""
revision = "dc_merge_taxi_consent_receipts"
down_revision = ("db_chat_message_receipt", "da_taxi_consent_attempt")
branch_labels = None
depends_on = None


def upgrade() -> None:
    pass


def downgrade() -> None:
    pass
