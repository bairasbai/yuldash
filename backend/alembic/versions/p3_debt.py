"""Фаза 3: долг водителя по комиссии за такси (Модель А «на доверии»).

ИДЕМПОТЕНТНО (как p2/p3_ledger), оба пути:
- свежая БД: `alembic upgrade head` идёт через 0001_baseline (create_all из текущих моделей)
  → таблица commissiondebt уже создана → эта ревизия видит её и делает no-op;
- прод (таблиц ещё нет): создаём commissiondebt с индексами.

Добавляет таблицу commissiondebt (одна строка = комиссия одного завершённого такси-заказа:
driver_id, order_id, amount_kop, week, status[unpaid|pending|paid], сроки).
Ничего не удаляет. Деньги — только целые копейки (int).

Прод: `alembic upgrade head`.

Revision ID: p3_debt
Revises: p3_ledger
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "p3_debt"
down_revision = "p3_ledger"
branch_labels = None
depends_on = None


def _tables(bind) -> set:
    return set(inspect(bind).get_table_names())


def _indexes(bind, table: str) -> set:
    try:
        return {ix["name"] for ix in inspect(bind).get_indexes(table)}
    except Exception:  # noqa: BLE001
        return set()


def upgrade() -> None:
    bind = op.get_bind()
    if "commissiondebt" not in _tables(bind):
        op.create_table(
            "commissiondebt",
            sa.Column("id", sa.Integer(), primary_key=True, nullable=False),
            sa.Column("driver_id", sa.Integer(), sa.ForeignKey("user.id"), nullable=False),
            sa.Column("order_id", sa.Integer(), sa.ForeignKey("instantorder.id"), nullable=True),
            sa.Column("amount_kop", sa.Integer(), nullable=False, server_default="0"),
            sa.Column("week", sa.String(), nullable=False, server_default=""),
            sa.Column("status", sa.String(), nullable=False, server_default="unpaid"),
            sa.Column("created_at", sa.DateTime(), nullable=False),
            sa.Column("due_at", sa.DateTime(), nullable=True),
            sa.Column("paid_declared_at", sa.DateTime(), nullable=True),
            sa.Column("confirmed_at", sa.DateTime(), nullable=True),
        )
        ix = _indexes(bind, "commissiondebt")
        for name, col in (
            ("ix_commissiondebt_driver_id", "driver_id"),
            ("ix_commissiondebt_order_id", "order_id"),
            ("ix_commissiondebt_week", "week"),
            ("ix_commissiondebt_status", "status"),
            ("ix_commissiondebt_created_at", "created_at"),
        ):
            if name not in ix:
                op.create_index(name, "commissiondebt", [col])


def downgrade() -> None:
    bind = op.get_bind()
    if "commissiondebt" in _tables(bind):
        op.drop_table("commissiondebt")
