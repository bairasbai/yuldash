"""Индексы created_at на растущих таблицах: instantorder, parceldelivery.

Обе таблицы растут с каждым такси-заказом/доставкой, а created_at используется для сортировки,
дневной сводки (digest) и будущей ретеншен-чистки. Без индекса — seq-scan по мере роста.

ИДЕМПОТЕНТНО (baseline-через-create_all): свежая БД уже имеет индексы из модели → no-op;
прод — создаём недостающие.

Прод: `alembic upgrade head`.

Revision ID: w4_growth_indexes
Revises: w3_release_hardening
"""
from alembic import op
from sqlalchemy import inspect

revision = "w4_growth_indexes"
down_revision = "w3_release_hardening"
branch_labels = None
depends_on = None

_INDEXES = [
    ("ix_instantorder_created_at", "instantorder", "created_at"),
    ("ix_parceldelivery_created_at", "parceldelivery", "created_at"),
]


def upgrade() -> None:
    insp = inspect(op.get_bind())
    tables = set(insp.get_table_names())
    for ix_name, table, col in _INDEXES:
        if table not in tables:
            continue
        if ix_name not in {ix["name"] for ix in insp.get_indexes(table)}:
            op.create_index(ix_name, table, [col])


def downgrade() -> None:
    insp = inspect(op.get_bind())
    tables = set(insp.get_table_names())
    for ix_name, table, _col in _INDEXES:
        if table not in tables:
            continue
        if ix_name in {ix["name"] for ix in insp.get_indexes(table)}:
            op.drop_index(ix_name, table_name=table)
