"""Напоминание оценить поездку: флаг booking.rate_reminded.

Аддитивно (паттерн g_tips), ИДЕМПОТЕНТНО, оба пути:
- свежая БД: 0001_baseline (create_all из моделей) уже создал колонку → ревизия no-op;
- прод (Postgres): добавляем недостающую колонку с дефолтом false.

Уже завершённые брони помечаем rate_reminded=true → фоновая задача (app/rate_reminder.py)
НЕ шлёт ретро-спам «оцените поездку» по старым поездкам после первого запуска.

Прод: `alembic upgrade head`.

Revision ID: h_rate_reminded
Revises: g_tips
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "h_rate_reminded"
down_revision = "g_tips"
branch_labels = None
depends_on = None


def _columns(bind, table: str) -> set:
    try:
        return {c["name"] for c in inspect(bind).get_columns(table)}
    except Exception:  # noqa: BLE001
        return set()


def upgrade() -> None:
    bind = op.get_bind()
    if "rate_reminded" not in _columns(bind, "booking"):
        op.add_column("booking",
                      sa.Column("rate_reminded", sa.Boolean(), nullable=False, server_default=sa.text("false")))
        # Уже завершённые брони: «напоминание не нужно» → без ретро-спама по старым поездкам.
        op.execute("UPDATE booking SET rate_reminded = true WHERE status = 'done'")


def downgrade() -> None:
    bind = op.get_bind()
    if "rate_reminded" in _columns(bind, "booking"):
        with op.batch_alter_table("booking") as b:
            b.drop_column("rate_reminded")
