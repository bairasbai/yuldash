"""Анонимная продуктовая аналитика веб-версии: таблица analyticsevent.

ИДЕМПОТЕНТНО (как p3_payout), оба пути:
- свежая БД: create_all из текущих моделей уже создаёт таблицу → ревизия видит её и no-op;
- прод (таблицы ещё нет): создаём таблицу + индексы.

АНОНИМНО: ни user_id, ни телефона, ни имени, ни точных координат — только event, анонимный
client_id, клиентское ts и context_json (безопасные props после серверной чистки).
Ничего не удаляет из существующих таблиц.

Прод: `alembic upgrade head`.

Revision ID: analytics_events
Revises: p3_payout
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "analytics_events"
down_revision = "p3_payout"
branch_labels = None
depends_on = None


def _tables(bind) -> set:
    return set(inspect(bind).get_table_names())


def _indexes(bind, table: str) -> set:
    try:
        return {ix["name"] for ix in inspect(bind).get_indexes(table)}
    except Exception:  # noqa: BLE001 — таблицы может не быть
        return set()


def upgrade() -> None:
    bind = op.get_bind()
    if "analyticsevent" in _tables(bind):
        return   # create_all уже создал (свежая БД) — no-op

    op.create_table(
        "analyticsevent",
        sa.Column("id", sa.Integer(), primary_key=True, nullable=False),
        sa.Column("event", sa.String(length=64), nullable=False, server_default=""),
        sa.Column("client_id", sa.String(length=64), nullable=False, server_default=""),
        sa.Column("ts", sa.BigInteger(), nullable=True),
        sa.Column("context_json", sa.Text(), nullable=False, server_default=""),
        sa.Column("created_at", sa.DateTime(), nullable=False),
    )
    idx = _indexes(bind, "analyticsevent")
    if "ix_analyticsevent_client_id" not in idx:
        op.create_index("ix_analyticsevent_client_id", "analyticsevent", ["client_id"])
    if "ix_analyticsevent_created_at" not in idx:
        op.create_index("ix_analyticsevent_created_at", "analyticsevent", ["created_at"])
    if "ix_analyticsevent_created_event" not in idx:
        op.create_index("ix_analyticsevent_created_event", "analyticsevent", ["created_at", "event"])


def downgrade() -> None:
    bind = op.get_bind()
    if "analyticsevent" not in _tables(bind):
        return
    idx = _indexes(bind, "analyticsevent")
    for name in ("ix_analyticsevent_created_event", "ix_analyticsevent_created_at",
                 "ix_analyticsevent_client_id"):
        if name in idx:
            op.drop_index(name, table_name="analyticsevent")
    op.drop_table("analyticsevent")
