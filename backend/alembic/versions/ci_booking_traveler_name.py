"""Private name of the person traveling on another user's booking."""

import sqlalchemy as sa
from alembic import op


revision = "ci_booking_traveler_name"
down_revision = "ch_auth_session_20261112"
branch_labels = None
depends_on = None


COLUMNS = (
    ("booking", "traveler_name", sa.String(120)),
    ("commandreceipt", "conflict_booking_id", sa.Integer()),
    ("commandreceipt", "conflict_booking_created_at", sa.DateTime()),
)


def upgrade() -> None:
    for table, name, column_type in COLUMNS:
        if op.get_context().as_sql or name not in {
            column["name"] for column in sa.inspect(op.get_bind()).get_columns(table)
        }:
            op.add_column(table, sa.Column(name, column_type, nullable=True))


def downgrade() -> None:
    for table, name, _ in reversed(COLUMNS):
        op.drop_column(table, name)
