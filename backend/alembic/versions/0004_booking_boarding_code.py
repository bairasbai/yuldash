"""Booking.boarding_code — посадочный код брони.

Колонка добавлена в модель `Booking`, но не имела ни migrate_*.sql, ни alembic-ревизии:
на свежей БД её создаёт `create_all`, а на проде (таблица создана раньше) колонки НЕТ —
`create_all` существующую таблицу не трогает. Обращение к `booking.boarding_code` на проде
без этой миграции → 500. Закрываем.

ИДЕМПОТЕНТНО (baseline-через-create_all):
- свежая БД: create_all уже создал booking С колонкой → no-op;
- прод (колонки нет): добавляем `boarding_code varchar NOT NULL DEFAULT ''`.

Прод: `alembic upgrade head`.

Revision ID: 0004_booking_boarding_code
Revises: 0003_partner_ads_columns
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "0004_booking_boarding_code"
down_revision = "0003_partner_ads_columns"
branch_labels = None
depends_on = None


def _cols(bind, table: str) -> set:
    return {c["name"] for c in inspect(bind).get_columns(table)}


def upgrade() -> None:
    bind = op.get_bind()
    if "boarding_code" not in _cols(bind, "booking"):
        with op.batch_alter_table("booking") as batch:
            batch.add_column(sa.Column("boarding_code", sa.String(), nullable=False, server_default=""))


def downgrade() -> None:
    bind = op.get_bind()
    if "boarding_code" in _cols(bind, "booking"):
        with op.batch_alter_table("booking") as batch:
            batch.drop_column("boarding_code")
