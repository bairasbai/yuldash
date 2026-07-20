"""Причина отмены брони + флаг неявки (no-show) — сигнал доверия «между своими» и аргумент в споре.

ИДЕМПОТЕНТНО (как analytics_events), оба пути:
- свежая БД: create_all из текущих моделей уже создаёт колонки → ревизия видит их и no-op;
- прод (колонок ещё нет): добавляем booking.cancel_reason + booking.no_show.
Ничего не удаляет из существующих таблиц.

Прод: `alembic upgrade head`.

Revision ID: booking_cancel_reason
Revises: analytics_events
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "booking_cancel_reason"
down_revision = "analytics_events"
branch_labels = None
depends_on = None


def _columns(bind, table: str) -> set:
    try:
        return {c["name"] for c in inspect(bind).get_columns(table)}
    except Exception:  # noqa: BLE001 — таблицы может не быть
        return set()


def upgrade() -> None:
    bind = op.get_bind()
    cols = _columns(bind, "booking")
    if "cancel_reason" not in cols:
        op.add_column("booking", sa.Column("cancel_reason", sa.String(), nullable=True))
    if "no_show" not in cols:
        op.add_column("booking", sa.Column("no_show", sa.Boolean(), nullable=False, server_default=sa.false()))


def downgrade() -> None:
    bind = op.get_bind()
    cols = _columns(bind, "booking")
    if "no_show" in cols:
        op.drop_column("booking", "no_show")
    if "cancel_reason" in cols:
        op.drop_column("booking", "cancel_reason")
