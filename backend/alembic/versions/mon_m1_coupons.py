"""M1 — партнёрский слой + купонный маркетплейс «Скидки по пути».

Таблицы: partner (бизнес), coupon (купон-скидка), couponredemption (бронь/погашение по коду) +
колонка payment.partner_id (для purpose=partner_sub — подписка бизнеса).

ИДЕМПОТЕНТНО (baseline-через-create_all, как соседние ревизии f22/w2_*):
- свежая/dev БД: create_all уже создал таблицы и колонку → все шаги no-op;
- прод (создан раньше, без M1): создаём таблицы и добавляем колонку.

Прод: `alembic upgrade head`.

Revision ID: mon_m1_coupons
Revises: w2_driver_checks
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "mon_m1_coupons"
down_revision = "w2_driver_checks"
branch_labels = None
depends_on = None


def _tables(bind) -> set:
    return set(inspect(bind).get_table_names())


def _cols(bind, table: str) -> set:
    return {c["name"] for c in inspect(bind).get_columns(table)}


def upgrade() -> None:
    bind = op.get_bind()
    tables = _tables(bind)

    # 1) Бизнес-партнёр.
    if "partner" not in tables:
        op.create_table(
            "partner",
            sa.Column("id", sa.Integer(), primary_key=True, nullable=False),
            sa.Column("owner_id", sa.Integer(), nullable=False),
            sa.Column("name", sa.String(), nullable=False),
            sa.Column("category", sa.String(length=20), nullable=False, server_default="other"),
            sa.Column("city", sa.String(), nullable=False, server_default=""),
            sa.Column("address", sa.String(), nullable=False, server_default=""),
            sa.Column("lat", sa.Float(), nullable=True),
            sa.Column("lng", sa.Float(), nullable=True),
            sa.Column("phone", sa.String(), nullable=False, server_default=""),
            sa.Column("description", sa.String(), nullable=False, server_default=""),
            sa.Column("status", sa.String(length=16), nullable=False, server_default="pending"),
            sa.Column("subscription_until", sa.DateTime(), nullable=True),
            sa.Column("subscription_plan", sa.String(length=20), nullable=False, server_default=""),
            sa.Column("reject_reason", sa.String(), nullable=False, server_default=""),
            sa.Column("created_at", sa.DateTime(), nullable=False, server_default=sa.func.now()),
            sa.Column("reviewed_at", sa.DateTime(), nullable=True),
        )
        op.create_index("ix_partner_owner_id", "partner", ["owner_id"])
        op.create_index("ix_partner_name", "partner", ["name"])
        op.create_index("ix_partner_city", "partner", ["city"])

    # 2) Купон.
    if "coupon" not in tables:
        op.create_table(
            "coupon",
            sa.Column("id", sa.Integer(), primary_key=True, nullable=False),
            sa.Column("partner_id", sa.Integer(), nullable=False),
            sa.Column("title", sa.String(), nullable=False),
            sa.Column("description", sa.String(), nullable=False, server_default=""),
            sa.Column("discount_text", sa.String(length=80), nullable=False, server_default=""),
            sa.Column("city", sa.String(), nullable=False, server_default=""),
            sa.Column("route_hint", sa.String(), nullable=False, server_default=""),
            sa.Column("valid_from", sa.DateTime(), nullable=True),
            sa.Column("valid_until", sa.DateTime(), nullable=True),
            sa.Column("limit_total", sa.Integer(), nullable=False, server_default="0"),
            sa.Column("limit_per_user", sa.Integer(), nullable=False, server_default="1"),
            sa.Column("redeemed_count", sa.Integer(), nullable=False, server_default="0"),
            sa.Column("premium", sa.Boolean(), nullable=False, server_default=sa.false()),
            sa.Column("status", sa.String(length=16), nullable=False, server_default="draft"),
            sa.Column("created_at", sa.DateTime(), nullable=False, server_default=sa.func.now()),
        )
        op.create_index("ix_coupon_partner_id", "coupon", ["partner_id"])
        op.create_index("ix_coupon_title", "coupon", ["title"])
        op.create_index("ix_coupon_city", "coupon", ["city"])
        op.create_index("ix_coupon_status", "coupon", ["status"])

    # 3) Бронь/погашение купона.
    if "couponredemption" not in tables:
        op.create_table(
            "couponredemption",
            sa.Column("id", sa.Integer(), primary_key=True, nullable=False),
            sa.Column("coupon_id", sa.Integer(), nullable=False),
            sa.Column("user_id", sa.Integer(), nullable=False),
            sa.Column("code", sa.String(length=12), nullable=False),
            sa.Column("status", sa.String(length=16), nullable=False, server_default="reserved"),
            sa.Column("reserved_at", sa.DateTime(), nullable=False, server_default=sa.func.now()),
            sa.Column("redeemed_at", sa.DateTime(), nullable=True),
            sa.Column("redeemed_by", sa.Integer(), nullable=True),
        )
        op.create_index("ix_couponredemption_coupon_id", "couponredemption", ["coupon_id"])
        op.create_index("ix_couponredemption_user_id", "couponredemption", ["user_id"])
        op.create_index("ix_couponredemption_code", "couponredemption", ["code"])
        op.create_index("ix_couponredemption_status", "couponredemption", ["status"])

    # 4) Payment.partner_id — для подписки бизнеса (purpose=partner_sub). Без DB-FK (лёгкий ALTER).
    if "payment" in _tables(bind) and "partner_id" not in _cols(bind, "payment"):
        with op.batch_alter_table("payment") as batch:
            batch.add_column(sa.Column("partner_id", sa.Integer(), nullable=True))


def downgrade() -> None:
    bind = op.get_bind()
    if "payment" in _tables(bind) and "partner_id" in _cols(bind, "payment"):
        with op.batch_alter_table("payment") as batch:
            batch.drop_column("partner_id")
    tables = _tables(bind)
    if "couponredemption" in tables:
        op.drop_table("couponredemption")
    if "coupon" in tables:
        op.drop_table("coupon")
    if "partner" in tables:
        op.drop_table("partner")
