"""M3 — доставка посылок между сёлами/городами (символический сбор за вещь-доставку).

Таблица: parceldelivery (заявка на доставку посылки попутным курьером).

ИДЕМПОТЕНТНО (baseline-через-create_all, как соседние ревизии mon_m1/mon_m2/f22/w2_*):
- свежая/dev БД: create_all уже создал таблицу → все шаги no-op;
- прод (создан раньше, без M3): создаём таблицу и индексы.

Прод: `alembic upgrade head`.

Revision ID: mon_m3_parcels
Revises: mon_m2_promo
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "mon_m3_parcels"
down_revision = "mon_m2_promo"
branch_labels = None
depends_on = None


def _tables(bind) -> set:
    return set(inspect(bind).get_table_names())


def upgrade() -> None:
    bind = op.get_bind()
    tables = _tables(bind)

    if "parceldelivery" not in tables:
        op.create_table(
            "parceldelivery",
            sa.Column("id", sa.Integer(), primary_key=True, nullable=False),
            sa.Column("sender_id", sa.Integer(), nullable=False),
            sa.Column("courier_id", sa.Integer(), nullable=True),
            sa.Column("from_city", sa.String(length=80), nullable=False, server_default=""),
            sa.Column("to_city", sa.String(length=80), nullable=False, server_default=""),
            sa.Column("from_lat", sa.Float(), nullable=True),
            sa.Column("from_lng", sa.Float(), nullable=True),
            sa.Column("to_lat", sa.Float(), nullable=True),
            sa.Column("to_lng", sa.Float(), nullable=True),
            sa.Column("size", sa.String(length=16), nullable=False, server_default="small"),
            sa.Column("description", sa.String(), nullable=False, server_default=""),
            sa.Column("receiver_name", sa.String(), nullable=False, server_default=""),
            sa.Column("receiver_phone", sa.String(), nullable=False, server_default=""),
            sa.Column("fee_kop", sa.Integer(), nullable=False, server_default="0"),
            sa.Column("status", sa.String(length=16), nullable=False, server_default="created"),
            sa.Column("confirm_code", sa.String(length=12), nullable=False, server_default=""),
            sa.Column("created_at", sa.DateTime(), nullable=False, server_default=sa.func.now()),
            sa.Column("accepted_at", sa.DateTime(), nullable=True),
            sa.Column("delivered_at", sa.DateTime(), nullable=True),
        )
        op.create_index("ix_parceldelivery_sender_id", "parceldelivery", ["sender_id"])
        op.create_index("ix_parceldelivery_courier_id", "parceldelivery", ["courier_id"])
        op.create_index("ix_parceldelivery_from_city", "parceldelivery", ["from_city"])
        op.create_index("ix_parceldelivery_to_city", "parceldelivery", ["to_city"])
        op.create_index("ix_parceldelivery_status", "parceldelivery", ["status"])
        op.create_index("ix_parceldelivery_confirm_code", "parceldelivery", ["confirm_code"])


def downgrade() -> None:
    bind = op.get_bind()
    if "parceldelivery" in _tables(bind):
        op.drop_table("parceldelivery")
