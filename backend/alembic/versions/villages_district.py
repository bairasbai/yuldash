"""Деревни РБ: колонка settlement.district (различать тёзок) + опора для kind='village'.

ИДЕМПОТЕНТНО (как w2_geo и пр.), оба пути:
- свежая БД: baseline create_all уже создаёт settlement.district из модели → тут no-op;
- прод: settlement уже есть без district → добавляем колонку (NULL — прежнее поведение).

Данные НЕ сеет — деревни сеются идемпотентно в lifespan (app/geo.py: seed_villages,
как seed_settlements). Ничего не удаляет. kind='village' — просто значение, схему не меняет.

Revision ID: villages_district
Revises: analytics_events
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "villages_district"
down_revision = "analytics_events"
branch_labels = None
depends_on = None


def _columns(bind, table: str) -> set:
    try:
        return {c["name"] for c in inspect(bind).get_columns(table)}
    except Exception:  # noqa: BLE001 — таблицы ещё нет (до baseline) → считаем пустой
        return set()


def upgrade() -> None:
    bind = op.get_bind()
    cols = _columns(bind, "settlement")
    if cols and "district" not in cols:
        op.add_column("settlement", sa.Column("district", sa.String(), nullable=True))


def downgrade() -> None:
    bind = op.get_bind()
    if "district" in _columns(bind, "settlement"):
        op.drop_column("settlement", "district")
