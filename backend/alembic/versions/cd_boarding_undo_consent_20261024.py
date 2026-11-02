"""Согласие пассажира на исправление посадки; старые отметки сохраняют защиту."""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "cd_boarding_undo_consent_20261024"
down_revision = "cc_booking_decline_reason_20261024"
branch_labels = None
depends_on = None

_COLUMNS = (
    ("boarding_undo_request_id", sa.String()),
    ("boarding_undo_requested_at", sa.DateTime()),
    ("boarding_undo_answer", sa.Boolean()),
)


def upgrade() -> None:
    bind = op.get_bind()
    if "booking" not in inspect(bind).get_table_names():
        return
    existing = {column["name"] for column in inspect(bind).get_columns("booking")}
    for name, kind in _COLUMNS:
        if name not in existing:
            op.add_column("booking", sa.Column(name, kind, nullable=True))


def downgrade() -> None:
    bind = op.get_bind()
    if "booking" not in inspect(bind).get_table_names():
        return
    existing = {column["name"] for column in inspect(bind).get_columns("booking")}
    with op.batch_alter_table("booking") as batch:
        for name, _kind in reversed(_COLUMNS):
            if name in existing:
                batch.drop_column(name)
