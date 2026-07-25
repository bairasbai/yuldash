"""«Надёжность» (Справедливость, фаза 4): booking.cancelled_by.

Кто выполнил отмену — для расчёта Надёжности (поздняя отмена бьёт по инициатору).
Аддитивно, ИДЕМПОТЕНТНО (паттерн g_tips): свежая БД (create_all) → no-op; прод → add_column.

Прод: `alembic upgrade head`.

Revision ID: l_booking_cancelled_by
Revises: k_incidents
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "l_booking_cancelled_by"
down_revision = "k_incidents"
branch_labels = None
depends_on = None


def _columns(bind, table: str) -> set:
    try:
        return {c["name"] for c in inspect(bind).get_columns(table)}
    except Exception:  # noqa: BLE001
        return set()


def upgrade() -> None:
    bind = op.get_bind()
    if "cancelled_by" not in _columns(bind, "booking"):
        op.add_column("booking", sa.Column("cancelled_by", sa.Integer(), nullable=True))


def downgrade() -> None:
    bind = op.get_bind()
    if "cancelled_by" in _columns(bind, "booking"):
        with op.batch_alter_table("booking") as b:
            b.drop_column("cancelled_by")
