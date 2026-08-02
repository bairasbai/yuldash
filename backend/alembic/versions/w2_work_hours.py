"""Волна 2, батч B4 «8-часовой лимит + отдых»: таблица taxiworkday.

Учёт такси-времени водителя за местный день (unique driver_id+day): seconds_online копится
на presence-heartbeat; лимит (taxi_shift_limit_hours) → limit_reached_at и гейт такси до
разблокировки; return_ride_used — «один попутчик домой»; флаги warned_60/warned_15/
winter_push_sent — дедуп вежливых пушей.

ИДЕМПОТЕНТНО (как p2/p3/w2_*), оба пути:
- свежая БД: `alembic upgrade head` идёт через 0001_baseline (create_all из моделей) →
  таблица уже есть → эта ревизия no-op;
- прод: создаём таблицу целиком (данных в ней ещё нет — терять нечего).

Данные не сеет, ничего не удаляет. ПОПУТКА не затрагивается.

Прод: `alembic upgrade head`.

Revision ID: w2_work_hours
Revises: w2_money_rules
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "w2_work_hours"
down_revision = "w2_money_rules"
branch_labels = None
depends_on = None


def _has_table(bind, table: str) -> bool:
    try:
        return table in inspect(bind).get_table_names()
    except Exception:  # noqa: BLE001
        return False


def upgrade() -> None:
    bind = op.get_bind()
    if _has_table(bind, "taxiworkday"):
        return
    op.create_table(
        "taxiworkday",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("driver_id", sa.Integer(), sa.ForeignKey("user.id"), nullable=False),
        sa.Column("day", sa.Date(), nullable=False),
        sa.Column("seconds_online", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("limit_reached_at", sa.DateTime(), nullable=True),
        sa.Column("return_ride_used", sa.Boolean(), nullable=False, server_default=sa.false()),
        sa.Column("last_heartbeat_at", sa.DateTime(), nullable=True),
        sa.Column("warned_60", sa.Boolean(), nullable=False, server_default=sa.false()),
        sa.Column("warned_15", sa.Boolean(), nullable=False, server_default=sa.false()),
        sa.Column("winter_push_sent", sa.Boolean(), nullable=False, server_default=sa.false()),
        sa.UniqueConstraint("driver_id", "day", name="uq_taxiworkday_driver_day"),
    )
    op.create_index("ix_taxiworkday_driver_id", "taxiworkday", ["driver_id"])


def downgrade() -> None:
    bind = op.get_bind()
    if _has_table(bind, "taxiworkday"):
        op.drop_table("taxiworkday")
