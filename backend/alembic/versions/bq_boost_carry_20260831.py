"""Остаток оплаченного поднятия переезжает на следующую поездку (2026-08-31).

Водитель платил за Boost, отменял поездку — и деньги оставались у нас, а услуга не оказывалась.
Возврат через ЮKassa тянет комиссию платёжной системы и ручную работу, поэтому решили иначе:
остаток времени поднятия ложится на следующую опубликованную поездку этого же водителя,
автоматически, без единого нажатия. За рулём не до выбора экранов.

Срок жизни остатка — 30 дней. Бесконечный долг перед водителем это бухгалтерия, которой
у нас нет; месяца хватает на любой нормальный сценарий, и в приложении видно, сколько осталось.

Два поля на пользователе:
  boost_credit_sec   — сколько секунд поднятия за нами;
  boost_credit_until — до какого момента остаток действителен.

Аддитивно и идемпотентно: на свежей БД колонки уже создал `create_all` из моделей → no-op.

Прод: `alembic upgrade head`.

Revision ID: bq_boost_carry
Revises: bp_dirty_car_demand
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "bq_boost_carry"
down_revision = "bp_dirty_car_demand"
branch_labels = None
depends_on = None

_TABLE = "user"


def _columns(bind) -> set:
    if _TABLE not in set(inspect(bind).get_table_names()):
        return set()
    return {c["name"] for c in inspect(bind).get_columns(_TABLE)}


def upgrade() -> None:
    bind = op.get_bind()
    have = _columns(bind)
    if not have:                      # таблицы нет — её создаст create_all из моделей
        return
    if "boost_credit_sec" not in have:
        op.add_column(_TABLE, sa.Column("boost_credit_sec", sa.Integer(), nullable=False, server_default="0"))
    if "boost_credit_until" not in have:
        op.add_column(_TABLE, sa.Column("boost_credit_until", sa.DateTime(), nullable=True))


def downgrade() -> None:
    bind = op.get_bind()
    have = _columns(bind)
    if "boost_credit_until" in have:
        op.drop_column(_TABLE, "boost_credit_until")
    if "boost_credit_sec" in have:
        op.drop_column(_TABLE, "boost_credit_sec")
