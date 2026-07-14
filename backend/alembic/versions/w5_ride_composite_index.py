"""Композитный индекс ride(status, depart_at) под горячую выдачу /rides.

Фильтр `status='active'` + сортировка по `depart_at` — главный массовый запрос. Композитный
индекс даёт точечный план вместо bitmap-AND двух отдельных индексов на большом объёме.

ИДЕМПОТЕНТНО (baseline-через-create_all): свежая БД уже имеет индекс из модели → no-op.

Прод: `alembic upgrade head`.

Revision ID: w5_ride_composite_index
Revises: w4_growth_indexes
"""
from alembic import op
from sqlalchemy import inspect

revision = "w5_ride_composite_index"
down_revision = "w4_growth_indexes"
branch_labels = None
depends_on = None

_IX = "ix_ride_status_depart"


def upgrade() -> None:
    insp = inspect(op.get_bind())
    if "ride" in set(insp.get_table_names()):
        if _IX not in {ix["name"] for ix in insp.get_indexes("ride")}:
            op.create_index(_IX, "ride", ["status", "depart_at"])


def downgrade() -> None:
    insp = inspect(op.get_bind())
    if "ride" in set(insp.get_table_names()):
        if _IX in {ix["name"] for ix in insp.get_indexes("ride")}:
            op.drop_index(_IX, table_name="ride")
