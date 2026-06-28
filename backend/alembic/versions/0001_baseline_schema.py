"""Базовая схема Юлдаша из моделей SQLModel.

Один чистый baseline вместо трёх прежних миграций (они использовали ALTER CONSTRAINT —
не работает на SQLite, и на проде никогда не запускались: схема там собиралась через
SQLModel.create_all + ручные migrate_*.sql). Создаём все таблицы прямо из моделей —
это работает на любом диалекте (SQLite/PostgreSQL) и автоматически совпадает с моделями.

Прод-катовер (один раз, т.к. таблицы уже существуют): `alembic stamp head` — пометить
baseline применённым, НЕ создавая заново. Дальше — обычные `alembic revision`.

Revision ID: 0001_baseline
Revises:
"""
from alembic import op  # noqa: F401
from sqlmodel import SQLModel

import app.models  # noqa: F401 — импорт регистрирует все таблицы в SQLModel.metadata

revision = "0001_baseline"
down_revision = None
branch_labels = None
depends_on = None


def upgrade() -> None:
    SQLModel.metadata.create_all(op.get_bind())


def downgrade() -> None:
    SQLModel.metadata.drop_all(op.get_bind())
