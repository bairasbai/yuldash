"""Попутка: «я забыл вещь в машине» открывает чат заново.

У такси этот выход был. У попутки чат раньше не закрывался вовсе, поэтому выход был не нужен;
2026-08-06 чат закрыли через сутки после поездки — и телефон, забытый на заднем сиденье, стало
не вернуть. Дыру создало само закрытие, поэтому колонка появляется вместе с ним.

Идемпотентно в обе стороны.
"""
from alembic import op
import sqlalchemy as sa

revision = "ac_booking_lost_item"
down_revision = "ab_taxi_women_only"
branch_labels = None
depends_on = None

_TABLE = "booking"
_COL = "lost_item_until"


def _has_column() -> bool:
    bind = op.get_bind()
    cols = {c["name"] for c in sa.inspect(bind).get_columns(_TABLE)}
    return _COL in cols


def upgrade() -> None:
    if _has_column():
        return
    op.add_column(_TABLE, sa.Column(_COL, sa.DateTime(), nullable=True))


def downgrade() -> None:
    if not _has_column():
        return
    op.drop_column(_TABLE, _COL)
