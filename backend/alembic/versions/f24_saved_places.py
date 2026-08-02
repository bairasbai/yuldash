"""F24 — таблицы `savedplace` и `recentplace` (сохранённые и недавние адреса пользователя).

Быстрый выбор точки в форме заказа: дом/работа/произвольные сохранённые места + недавние
точки (куда/откуда заказывали). Данные ПРИВАТНЫЕ (адрес/координаты) — доступны только
владельцу через роутер places.py.

ИДЕМПОТЕНТНО (как f14/0004): на свежей БД `create_all` уже создал таблицы из моделей →
no-op; на проде (таблиц нет) — создаём таблицы + индексы. Индексы тоже под проверкой
inspector (таблица есть, а индекса нет).

Прод: `alembic upgrade head`.

Revision ID: f24_saved_places
Revises: w5_ride_composite_index
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "f24_saved_places"
down_revision = "w5_ride_composite_index"
branch_labels = None
depends_on = None

_SAVED = "savedplace"
_RECENT = "recentplace"


def _tables(bind) -> set:
    return set(inspect(bind).get_table_names())


def _indexes(bind, table: str) -> set:
    return {ix["name"] for ix in inspect(bind).get_indexes(table)}


def upgrade() -> None:
    bind = op.get_bind()
    tables = _tables(bind)

    if _SAVED not in tables:
        op.create_table(
            _SAVED,
            sa.Column("id", sa.Integer(), primary_key=True, nullable=False),
            sa.Column("user_id", sa.Integer(), sa.ForeignKey("user.id"), nullable=False),
            sa.Column("label", sa.String(length=120), nullable=False, server_default=""),
            sa.Column("kind", sa.String(length=16), nullable=False, server_default="custom"),
            sa.Column("address", sa.String(length=500), nullable=False, server_default=""),
            sa.Column("lat", sa.Float(), nullable=True),
            sa.Column("lng", sa.Float(), nullable=True),
            sa.Column("created_at", sa.DateTime(), nullable=False),
        )
    saved_ix = _indexes(bind, _SAVED)
    if "ix_savedplace_user_id" not in saved_ix:
        op.create_index("ix_savedplace_user_id", _SAVED, ["user_id"])
    if "ix_savedplace_kind" not in saved_ix:
        op.create_index("ix_savedplace_kind", _SAVED, ["kind"])

    if _RECENT not in tables:
        op.create_table(
            _RECENT,
            sa.Column("id", sa.Integer(), primary_key=True, nullable=False),
            sa.Column("user_id", sa.Integer(), sa.ForeignKey("user.id"), nullable=False),
            sa.Column("address", sa.String(length=500), nullable=False, server_default=""),
            sa.Column("lat", sa.Float(), nullable=True),
            sa.Column("lng", sa.Float(), nullable=True),
            sa.Column("used_at", sa.DateTime(), nullable=False),
        )
    recent_ix = _indexes(bind, _RECENT)
    if "ix_recentplace_user_id" not in recent_ix:
        op.create_index("ix_recentplace_user_id", _RECENT, ["user_id"])
    if "ix_recentplace_used_at" not in recent_ix:
        op.create_index("ix_recentplace_used_at", _RECENT, ["used_at"])


def downgrade() -> None:
    bind = op.get_bind()
    tables = _tables(bind)
    if _RECENT in tables:
        op.drop_table(_RECENT)
    if _SAVED in tables:
        op.drop_table(_SAVED)
