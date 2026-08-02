"""Индексы на «горячие» status-колонки (M4): parceldelivery.status, partner.status, coupon.status.

Курьерские ленты/выписки и админ-списки фильтруют/агрегируют по этим status — без индекса
это seq-scan по мере роста объёма. Добавляем btree-индексы.

ИДЕМПОТЕНТНО (baseline-через-create_all):
- свежая БД: create_all уже создал индексы из модели (index=True) → проверяем и no-op;
- прод (индекса нет): создаём.

Прод: `alembic upgrade head`.

Revision ID: w2_status_indexes
Revises: ride_waypoints
"""
from alembic import op
from sqlalchemy import inspect

revision = "w2_status_indexes"
down_revision = "ride_waypoints"
branch_labels = None
depends_on = None

_INDEXES = [
    ("ix_parceldelivery_status", "parceldelivery", "status"),
    ("ix_partner_status", "partner", "status"),
    ("ix_coupon_status", "coupon", "status"),
]


def upgrade() -> None:
    bind = op.get_bind()
    insp = inspect(bind)
    tables = set(insp.get_table_names())
    for ix_name, table, col in _INDEXES:
        if table not in tables:
            continue
        existing = {ix["name"] for ix in insp.get_indexes(table)}
        if ix_name not in existing:
            op.create_index(ix_name, table, [col])


def downgrade() -> None:
    bind = op.get_bind()
    insp = inspect(bind)
    tables = set(insp.get_table_names())
    for ix_name, table, _col in _INDEXES:
        if table not in tables:
            continue
        existing = {ix["name"] for ix in insp.get_indexes(table)}
        if ix_name in existing:
            op.drop_index(ix_name, table_name=table)
