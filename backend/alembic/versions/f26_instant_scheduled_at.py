"""InstantOrder.scheduled_at — предзаказ такси «на время».

Колонка добавлена в модель `InstantOrder`. На свежей БД её создаёт create_all; на проде
(таблица создана раньше) колонки нет — create_all существующую таблицу не трогает. Без
миграции обращение к `order.scheduled_at` на проде → 500. Закрываем.

Новое значение статуса `scheduled` не требует DDL: статус хранится строкой (VARCHAR),
enum-типа в БД нет. Добавляем только nullable-колонку + индекс (под выборку «мои будущие
предзаказы» и будущий фоновый диспетчер).

ИДЕМПОТЕНТНО (dialect-safe, SQLite + PostgreSQL): проверяем колонку/индекс через inspector.

Прод: `alembic upgrade head`.

Revision ID: f26_instant_scheduled_at
Revises: f25_support_tickets
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "f26_instant_scheduled_at"
down_revision = "f25_support_tickets"
branch_labels = None
depends_on = None

_IDX = "ix_instantorder_scheduled_at"


def _cols(bind, table: str) -> set:
    return {c["name"] for c in inspect(bind).get_columns(table)}


def _indexes(bind, table: str) -> set:
    return {i["name"] for i in inspect(bind).get_indexes(table)}


def upgrade() -> None:
    bind = op.get_bind()
    if "scheduled_at" not in _cols(bind, "instantorder"):
        with op.batch_alter_table("instantorder") as batch:
            batch.add_column(sa.Column("scheduled_at", sa.DateTime(), nullable=True))
    if _IDX not in _indexes(bind, "instantorder"):
        op.create_index(_IDX, "instantorder", ["scheduled_at"])


def downgrade() -> None:
    bind = op.get_bind()
    if _IDX in _indexes(bind, "instantorder"):
        op.drop_index(_IDX, table_name="instantorder")
    if "scheduled_at" in _cols(bind, "instantorder"):
        with op.batch_alter_table("instantorder") as batch:
            batch.drop_column("scheduled_at")
