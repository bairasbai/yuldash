"""Волна 2, батч B6 «Запуск: ранний доступ + „Скоро в городе"» (§11).

Новая таблица `waitlistentry` — лист ожидания: phone (уникален), city, role
(passenger|driver), created_at, invited_at (отметка «позван в волне»).

ИДЕМПОТЕНТНО (как p2/p3/w2_*), оба пути:
- свежая БД: `alembic upgrade head` идёт через 0001_baseline (create_all из моделей) →
  таблица уже есть → эта ревизия no-op;
- прод: создаём таблицу + индексы, только если их нет.

Данные не сеет, ничего не удаляет. Попутка/такси не затрагиваются.

Прод: `alembic upgrade head`.

Revision ID: w2_waitlist
Revises: w2_quality
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "w2_waitlist"
down_revision = "w2_quality"
branch_labels = None
depends_on = None

_TABLE = "waitlistentry"


def _tables(bind) -> set:
    try:
        return set(inspect(bind).get_table_names())
    except Exception:  # noqa: BLE001
        return set()


def _indexes(bind, table: str) -> set:
    try:
        return {i["name"] for i in inspect(bind).get_indexes(table)}
    except Exception:  # noqa: BLE001
        return set()


def upgrade() -> None:
    bind = op.get_bind()
    if _TABLE not in _tables(bind):
        op.create_table(
            _TABLE,
            sa.Column("id", sa.Integer(), primary_key=True),
            sa.Column("phone", sa.String(length=32), nullable=False, unique=True),
            sa.Column("city", sa.String(), nullable=True),
            sa.Column("role", sa.String(), nullable=False, server_default="passenger"),
            sa.Column("created_at", sa.DateTime(), nullable=False),
            sa.Column("invited_at", sa.DateTime(), nullable=True),
        )
    # Индексы — как у модели (index=True); на свежей БД baseline уже создал — досоздаём в проде.
    ix = _indexes(bind, _TABLE)
    if "ix_waitlistentry_phone" not in ix:
        op.create_index("ix_waitlistentry_phone", _TABLE, ["phone"])
    if "ix_waitlistentry_city" not in ix:
        op.create_index("ix_waitlistentry_city", _TABLE, ["city"])
    if "ix_waitlistentry_role" not in ix:
        op.create_index("ix_waitlistentry_role", _TABLE, ["role"])


def downgrade() -> None:
    bind = op.get_bind()
    if _TABLE in _tables(bind):
        op.drop_table(_TABLE)   # индексы уходят вместе с таблицей
