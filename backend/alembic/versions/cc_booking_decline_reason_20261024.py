"""Причина отказа водителя отдельным полем брони (приёмка PR210, 24.10.2026).

Отказ хранился как `cancel_reason="driver_declined"`, а выбранная водителем причина (например,
«безопасность») жила только в тексте пуша — для разборов и статистики её не было. Аддитивно и
идемпотентно; старые отказы не угадываем — у них NULL.

Прод: `alembic upgrade head`.
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "cc_booking_decline_reason_20261024"
down_revision = "cb_sos_retry_intent_20261021"
branch_labels = None
depends_on = None


def upgrade() -> None:
    bind = op.get_bind()
    if "booking" not in inspect(bind).get_table_names():
        return
    existing = {column["name"] for column in inspect(bind).get_columns("booking")}
    if "decline_reason" not in existing:
        op.add_column("booking", sa.Column("decline_reason", sa.String(), nullable=True))


def downgrade() -> None:
    bind = op.get_bind()
    if "booking" not in inspect(bind).get_table_names():
        return
    existing = {column["name"] for column in inspect(bind).get_columns("booking")}
    if "decline_reason" in existing:
        with op.batch_alter_table("booking") as batch:
            batch.drop_column("decline_reason")
