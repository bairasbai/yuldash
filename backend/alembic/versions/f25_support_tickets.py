"""Таблицы поддержки внутри приложения: `supportticket` + `supportmessage`.

Модели `SupportTicket` / `SupportMessage` добавлены в app/models.py. На свежей БД их
создаёт create_all; на проде (таблицы созданы раньше) — эта ревизия добивает их.

ИДЕМПОТЕНТНО (dialect-safe, SQLite + PostgreSQL): проверяем наличие таблиц через inspector,
создаём из метаданных модели (checkfirst=True). Порядок: ticket → message (FK message→ticket).

Прод: `alembic upgrade head`.

Revision ID: f25_support_tickets
Revises: f24_saved_places
"""
from alembic import op
from sqlalchemy import inspect

import app.models  # noqa: F401 — регистрирует таблицы в SQLModel.metadata
from app.models import SupportMessage, SupportTicket

revision = "f25_support_tickets"
down_revision = "f24_saved_places"
branch_labels = None
depends_on = None


def upgrade() -> None:
    bind = op.get_bind()
    existing = set(inspect(bind).get_table_names())
    if "supportticket" not in existing:
        SupportTicket.__table__.create(bind, checkfirst=True)
    if "supportmessage" not in existing:
        SupportMessage.__table__.create(bind, checkfirst=True)


def downgrade() -> None:
    bind = op.get_bind()
    existing = set(inspect(bind).get_table_names())
    if "supportmessage" in existing:
        SupportMessage.__table__.drop(bind, checkfirst=True)
    if "supportticket" in existing:
        SupportTicket.__table__.drop(bind, checkfirst=True)
