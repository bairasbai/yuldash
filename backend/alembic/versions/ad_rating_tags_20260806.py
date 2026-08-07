"""Быстрые метки к оценке: колонка rating.tags (CSV из закрытого списка).

ИДЕМПОТЕНТНО, оба пути:
- свежая БД: baseline create_all уже создаёт rating.tags из модели → тут no-op;
- прод: rating уже есть без tags → добавляем колонку (пустая строка = меток нет,
  прежнее поведение не меняется).

Revision ID: ad_rating_tags
Revises: zone_district
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "ad_rating_tags"
down_revision = "zone_district"
branch_labels = None
depends_on = None


def _columns(bind, table: str) -> set:
    try:
        return {c["name"] for c in inspect(bind).get_columns(table)}
    except Exception:  # noqa: BLE001 — таблицы ещё нет (до baseline) → считаем пустой
        return set()


def upgrade() -> None:
    bind = op.get_bind()
    cols = _columns(bind, "rating")
    if cols and "tags" not in cols:
        op.add_column("rating", sa.Column("tags", sa.String(length=200),
                                          nullable=False, server_default=""))


def downgrade() -> None:
    bind = op.get_bind()
    if "tags" in _columns(bind, "rating"):
        op.drop_column("rating", "tags")
