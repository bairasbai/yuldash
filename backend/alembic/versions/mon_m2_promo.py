"""M2 — промокоды и кампании (рычаг роста: именные коды блогеров/партнёров/акций).

Таблицы: promocode (кампания/код) и promoredemption (факт применения кодом пользователем).

ИДЕМПОТЕНТНО (baseline-через-create_all, как соседние ревизии mon_m1/f22/w2_*):
- свежая/dev БД: create_all уже создал таблицы → все шаги no-op;
- прод (создан раньше, без M2): создаём таблицы и индексы.

Прод: `alembic upgrade head`.

Revision ID: mon_m2_promo
Revises: mon_m1_coupons
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "mon_m2_promo"
down_revision = "mon_m1_coupons"
branch_labels = None
depends_on = None


def _tables(bind) -> set:
    return set(inspect(bind).get_table_names())


def upgrade() -> None:
    bind = op.get_bind()
    tables = _tables(bind)

    # 1) Промокод / кампания.
    if "promocode" not in tables:
        op.create_table(
            "promocode",
            sa.Column("id", sa.Integer(), primary_key=True, nullable=False),
            sa.Column("code", sa.String(length=32), nullable=False),
            sa.Column("title", sa.String(), nullable=False, server_default=""),
            sa.Column("description", sa.String(), nullable=False, server_default=""),
            sa.Column("owner_id", sa.Integer(), nullable=True),
            sa.Column("campaign", sa.String(length=80), nullable=False, server_default=""),
            sa.Column("kind", sa.String(length=16), nullable=False, server_default="welcome"),
            sa.Column("perk_value", sa.Integer(), nullable=False, server_default="0"),
            sa.Column("limit_total", sa.Integer(), nullable=False, server_default="0"),
            sa.Column("limit_per_user", sa.Integer(), nullable=False, server_default="1"),
            sa.Column("redeemed_count", sa.Integer(), nullable=False, server_default="0"),
            sa.Column("valid_from", sa.DateTime(), nullable=True),
            sa.Column("valid_until", sa.DateTime(), nullable=True),
            sa.Column("active", sa.Boolean(), nullable=False, server_default=sa.true()),
            sa.Column("created_at", sa.DateTime(), nullable=False, server_default=sa.func.now()),
        )
        op.create_index("ix_promocode_code", "promocode", ["code"], unique=True)
        op.create_index("ix_promocode_owner_id", "promocode", ["owner_id"])
        op.create_index("ix_promocode_active", "promocode", ["active"])

    # 2) Факт применения промокода пользователем.
    if "promoredemption" not in tables:
        op.create_table(
            "promoredemption",
            sa.Column("id", sa.Integer(), primary_key=True, nullable=False),
            sa.Column("promo_id", sa.Integer(), nullable=False),
            sa.Column("user_id", sa.Integer(), nullable=False),
            sa.Column("redeemed_at", sa.DateTime(), nullable=False, server_default=sa.func.now()),
        )
        op.create_index("ix_promoredemption_promo_id", "promoredemption", ["promo_id"])
        op.create_index("ix_promoredemption_user_id", "promoredemption", ["user_id"])


def downgrade() -> None:
    bind = op.get_bind()
    tables = _tables(bind)
    if "promoredemption" in tables:
        op.drop_table("promoredemption")
    if "promocode" in tables:
        op.drop_table("promocode")
