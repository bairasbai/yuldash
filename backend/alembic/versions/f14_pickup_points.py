"""F14 — таблица `pickuppoint` (точки сбора по ориентирам города/села).

РБ-фишка: в сёлах адресов нет, встречаются «у мечети» / «у Магнита» / «автовокзал».
Публичный двуязычный справочник ориентиров с координатами; наполняется сидом
популярных точек крупных городов и пополняется из реально выбранных водителями точек.

ИДЕМПОТЕНТНО (baseline-через-create_all, как 0004):
- свежая БД: `create_all` уже создал `pickuppoint` из модели → no-op;
- прод (таблицы нет): создаём таблицу + индексы.

Прод: `alembic upgrade head`.

ВНИМАНИЕ (для лида): это ветка `feat/village-pickup-points`. down_revision указывает на
head ветки feat-base (`0004_booking_boarding_code`). При сведении нескольких фича-веток
цепочку ревизий сведёт лид (возможен merge-revision / переустановка down_revision).

Revision ID: f14_pickup_points
Revises: 0004_booking_boarding_code
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "f14_pickup_points"
down_revision = "f12_winter_safety"
branch_labels = None
depends_on = None

_TABLE = "pickuppoint"


def _tables(bind) -> set:
    return set(inspect(bind).get_table_names())


def _indexes(bind, table: str) -> set:
    return {ix["name"] for ix in inspect(bind).get_indexes(table)}


def upgrade() -> None:
    bind = op.get_bind()
    if _TABLE not in _tables(bind):
        op.create_table(
            _TABLE,
            sa.Column("id", sa.Integer(), primary_key=True, nullable=False),
            sa.Column("city", sa.String(), nullable=False, server_default=""),
            sa.Column("title_ru", sa.String(), nullable=False, server_default=""),
            sa.Column("title_ba", sa.String(), nullable=False, server_default=""),
            sa.Column("lat", sa.Float(), nullable=True),
            sa.Column("lng", sa.Float(), nullable=True),
            sa.Column("usage_count", sa.Integer(), nullable=False, server_default="0"),
            sa.Column("is_seed", sa.Boolean(), nullable=False, server_default=sa.false()),
            sa.Column("created_at", sa.DateTime(), nullable=False),
        )
    # Индексы — тоже идемпотентно (на случай, если таблица есть, а индекса нет).
    existing = _indexes(bind, _TABLE)
    if "ix_pickuppoint_city" not in existing:
        op.create_index("ix_pickuppoint_city", _TABLE, ["city"])
    if "ix_pickuppoint_usage_count" not in existing:
        op.create_index("ix_pickuppoint_usage_count", _TABLE, ["usage_count"])


def downgrade() -> None:
    bind = op.get_bind()
    if _TABLE in _tables(bind):
        op.drop_table(_TABLE)
