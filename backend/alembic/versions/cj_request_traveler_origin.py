"""Record how a ride request's passenger was selected."""

import sqlalchemy as sa
from alembic import op


revision = "cj_request_traveler_origin"
down_revision = "ci_booking_traveler_name"
branch_labels = None
depends_on = None


COLUMNS = (
    ("riderequest", "traveler_origin", sa.String(8)),
    ("riderequest", "traveler_revision", sa.Integer()),
    ("requestresponse", "traveler_driver_consent", sa.String(64)),
    ("requestresponse", "traveler_owner_waiting", sa.String(64)),
    ("requestresponse", "traveler_accept_key", sa.String(128)),
    ("requestresponse", "traveler_accept_hash", sa.String(64)),
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
