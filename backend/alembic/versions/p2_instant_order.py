"""Фаза 2 «Быстрый заказ»: таблицы tariff + instant_order.

Presence — только в Redis (эфемерно), в БД НЕ пишется. Здесь только тариф и заказ.

ИДЕМПОТЕНТНО (baseline-через-create_all):
- свежая БД: `alembic upgrade head` идёт через 0001_baseline (create_all из моделей) →
  таблицы уже созданы → эта ревизия видит их и делает no-op;
- прод (таблиц ещё нет): создаём tariff и instant_order с индексами.

НЕ создаёт таблицу Notification (лента уведомлений — отдельная фича F5): офферы/статусы
идут через send_push, не через БД-уведомления.

Прод: `alembic upgrade head`.

Revision ID: p2_instant_order
Revises: 0004_booking_boarding_code
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "p2_instant_order"
down_revision = "0004_booking_boarding_code"
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
    tables = _tables(bind)

    if "tariff" not in tables:
        op.create_table(
            "tariff",
            sa.Column("id", sa.Integer(), primary_key=True, nullable=False),
            sa.Column("zone", sa.String(), nullable=False, server_default="city"),
            sa.Column("category", sa.String(), nullable=False, server_default="standard"),
            sa.Column("base", sa.Integer(), nullable=False, server_default="0"),
            sa.Column("per_km", sa.Float(), nullable=False, server_default="0"),
            sa.Column("per_min", sa.Float(), nullable=False, server_default="0"),
            sa.Column("min_price", sa.Integer(), nullable=False, server_default="0"),
            sa.Column("k", sa.Float(), nullable=False, server_default="1"),
            sa.Column("active", sa.Boolean(), nullable=False, server_default=sa.true()),
            sa.Column("created_at", sa.DateTime(), nullable=False),
        )
        ix = _indexes(bind, "tariff")
        if "ix_tariff_zone" not in ix:
            op.create_index("ix_tariff_zone", "tariff", ["zone"])
        if "ix_tariff_category" not in ix:
            op.create_index("ix_tariff_category", "tariff", ["category"])
        if "ix_tariff_active" not in ix:
            op.create_index("ix_tariff_active", "tariff", ["active"])

    if "instantorder" not in tables:
        op.create_table(
            "instantorder",
            sa.Column("id", sa.Integer(), primary_key=True, nullable=False),
            sa.Column("passenger_id", sa.Integer(), sa.ForeignKey("user.id"), nullable=False),
            sa.Column("from_lat", sa.Float(), nullable=False, server_default="0"),
            sa.Column("from_lng", sa.Float(), nullable=False, server_default="0"),
            sa.Column("to_lat", sa.Float(), nullable=False, server_default="0"),
            sa.Column("to_lng", sa.Float(), nullable=False, server_default="0"),
            sa.Column("from_text", sa.String(), nullable=False, server_default=""),
            sa.Column("to_text", sa.String(), nullable=False, server_default=""),
            sa.Column("category", sa.String(), nullable=False, server_default="standard"),
            sa.Column("status", sa.String(), nullable=False, server_default="created"),
            sa.Column("price_estimate", sa.Integer(), nullable=False, server_default="0"),
            sa.Column("price_final", sa.Integer(), nullable=True),
            sa.Column("tariff_id", sa.Integer(), sa.ForeignKey("tariff.id"), nullable=True),
            sa.Column("distance_km", sa.Float(), nullable=False, server_default="0"),
            sa.Column("eta_min", sa.Float(), nullable=False, server_default="0"),
            sa.Column("driver_id", sa.Integer(), sa.ForeignKey("user.id"), nullable=True),
            sa.Column("current_offer_driver_id", sa.Integer(), sa.ForeignKey("user.id"), nullable=True),
            sa.Column("offer_expires_at", sa.DateTime(), nullable=True),
            sa.Column("search_round", sa.Integer(), nullable=False, server_default="0"),
            sa.Column("cancel_by", sa.String(), nullable=False, server_default=""),
            sa.Column("cancel_reason", sa.String(), nullable=False, server_default=""),
            sa.Column("created_at", sa.DateTime(), nullable=False),
            sa.Column("searching_at", sa.DateTime(), nullable=True),
            sa.Column("offered_at", sa.DateTime(), nullable=True),
            sa.Column("accepted_at", sa.DateTime(), nullable=True),
            sa.Column("arriving_at", sa.DateTime(), nullable=True),
            sa.Column("onboard_at", sa.DateTime(), nullable=True),
            sa.Column("done_at", sa.DateTime(), nullable=True),
            sa.Column("cancelled_at", sa.DateTime(), nullable=True),
            sa.Column("expired_at", sa.DateTime(), nullable=True),
        )
        ix = _indexes(bind, "instantorder")
        if "ix_instantorder_passenger_id" not in ix:
            op.create_index("ix_instantorder_passenger_id", "instantorder", ["passenger_id"])
        if "ix_instantorder_status" not in ix:
            op.create_index("ix_instantorder_status", "instantorder", ["status"])
        if "ix_instantorder_driver_id" not in ix:
            op.create_index("ix_instantorder_driver_id", "instantorder", ["driver_id"])


def downgrade() -> None:
    bind = op.get_bind()
    tables = _tables(bind)
    if "instantorder" in tables:
        op.drop_table("instantorder")
    if "tariff" in tables:
        op.drop_table("tariff")
