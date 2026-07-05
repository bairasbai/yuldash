"""Таблица `notification` — Центр уведомлений (типизированная лента RU+BA, read_at).

Модель `Notification` добавлена в app/models.py. На свежей БД её создаёт `create_all`,
но на проде (таблицы созданы раньше) её нет — эта ревизия добивает её.

ИДЕМПОТЕНТНО (dialect-safe, SQLite + PostgreSQL):
- таблица уже есть (create_all успел / повторный прогон) → no-op (checkfirst=True);
- таблицы нет → создаём из метаданных модели (совпадает с моделью на любом диалекте).

Прод: `alembic upgrade head`.

Revision ID: 0005_notification_table
Revises: 0004_booking_boarding_code
"""
from alembic import op
from sqlalchemy import inspect

import app.models  # noqa: F401 — регистрирует таблицы в SQLModel.metadata
from app.models import Notification

revision = "0005_notification_table"
down_revision = "0004_booking_boarding_code"
branch_labels = None
depends_on = None


def upgrade() -> None:
    bind = op.get_bind()
    if "notification" not in set(inspect(bind).get_table_names()):
        Notification.__table__.create(bind, checkfirst=True)


def downgrade() -> None:
    bind = op.get_bind()
    if "notification" in set(inspect(bind).get_table_names()):
        Notification.__table__.drop(bind, checkfirst=True)
