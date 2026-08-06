"""Такси: «только женщина за рулём».

В попутках этот выбор был с самого начала, в такси его не было — хотя ночью в машину
к незнакомому человеку садятся именно здесь (аудит 2026-08-06).

Колонка добавляется идемпотентно: миграция переживает повторный прогон и БД, где кто-то
уже накатил её руками.
"""
from alembic import op
import sqlalchemy as sa

revision = "ab_taxi_women_only"
down_revision = "aa_winter_all"
branch_labels = None
depends_on = None

_TABLE = "instantorder"
_COL = "women_only"


def _has_column() -> bool:
    bind = op.get_bind()
    cols = {c["name"] for c in sa.inspect(bind).get_columns(_TABLE)}
    return _COL in cols


def upgrade() -> None:
    if _has_column():
        return
    op.add_column(
        _TABLE,
        sa.Column(_COL, sa.Boolean(), nullable=False, server_default=sa.false()),
    )


def downgrade() -> None:
    if not _has_column():
        return
    op.drop_column(_TABLE, _COL)
