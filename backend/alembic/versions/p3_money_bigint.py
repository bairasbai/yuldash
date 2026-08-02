"""P3 (аудит #73): денежные *_kop колонки INTEGER → BigInteger (потолок int4 ≈ 21,47 млн ₽).

На PostgreSQL INTEGER = int4: значение > 2 147 483 647 коп (≈ 21,47 млн ₽) не влезает в строку —
уязвимы дорогая посылка (declared_value_kop/cod_amount_kop) и крупный платёж (amount_kop).
Расширяем до BIGINT. На SQLite INTEGER уже 64-битный (динамический) → там миграция не нужна,
поэтому на sqlite это no-op. Идемпотентно: пропускаем отсутствующие таблицы/колонки.

Revision ID: p3_money_bigint
Revises: p2_promo_unique
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "p3_money_bigint"
down_revision = "p2_promo_unique"
branch_labels = None
depends_on = None

_MONEY_COLS = {
    "instantorder": ["waiting_fee_kop", "cancel_fee_kop"],
    "payment": ["amount_kop"],
    "ledgerentry": ["amount_kop"],
    "commissiondebt": ["amount_kop"],
    "ad": ["budget_kop"],
    "parceldelivery": ["fee_kop", "declared_value_kop", "cod_amount_kop",
                       "commission_kop", "delivery_price_kop", "goods_actual_kop"],
}


def _retype(target_type, from_type) -> None:
    bind = op.get_bind()
    if bind.dialect.name != "postgresql":
        return  # sqlite INTEGER уже 64-битный — виджить нечего; на проде тип меняем только в PG
    insp = inspect(bind)
    tables = set(insp.get_table_names())
    for table, cols in _MONEY_COLS.items():
        if table not in tables:
            continue
        have = {c["name"] for c in insp.get_columns(table)}
        for col in cols:
            if col in have:
                # lock_timeout на миграционном соединении (env.py, B6) не даёт ALTER повиснуть
                # на горячей таблице — на маленьких (ранний прод) переписывание типа мгновенно.
                op.alter_column(table, col, type_=target_type, existing_type=from_type)


def upgrade() -> None:
    _retype(sa.BigInteger(), sa.Integer())


def downgrade() -> None:
    _retype(sa.Integer(), sa.BigInteger())
