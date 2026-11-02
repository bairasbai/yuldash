"""Свести RS-02/RS-03 и прежние гарантии отказа/исправления посадки без изменения данных."""

revision = "cf_merge_legacy_safety_20261102"
down_revision = ("ce_booking_meeting_points_20261101", "cd_boarding_undo_consent_20261024")
branch_labels = None
depends_on = None


def upgrade() -> None:
    pass


def downgrade() -> None:
    pass
