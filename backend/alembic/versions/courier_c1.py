"""C1 — профиль «Курьер»: courierapplication, courierprofile + новые поля parceldelivery.

ИДЕМПОТЕНТНО (baseline-через-create_all, как соседние ревизии mon_m3/mon_m2/w2_*):
- свежая/dev БД: create_all уже создал таблицы/колонки → все шаги no-op;
- прод (создан раньше, без C1): создаём таблицы, добавляем колонки к parceldelivery.

Прод: `alembic upgrade head`.

Revision ID: courier_c1
Revises: mon_m3_parcels
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "courier_c1"
down_revision = "mon_m3_parcels"
branch_labels = None
depends_on = None


def _tables(bind) -> set:
    return set(inspect(bind).get_table_names())


def _columns(bind, table) -> set:
    if table not in _tables(bind):
        return set()
    return {c["name"] for c in inspect(bind).get_columns(table)}


def upgrade() -> None:
    bind = op.get_bind()
    tables = _tables(bind)

    if "courierapplication" not in tables:
        op.create_table(
            "courierapplication",
            sa.Column("id", sa.Integer(), primary_key=True, nullable=False),
            sa.Column("user_id", sa.Integer(), nullable=False),
            sa.Column("transport", sa.String(length=16), nullable=False, server_default="car"),
            sa.Column("status", sa.String(length=16), nullable=False, server_default="pending"),
            sa.Column("selfie_url", sa.String(), nullable=False, server_default=""),
            sa.Column("invited_by", sa.Integer(), nullable=True),
            sa.Column("reject_reason", sa.String(), nullable=False, server_default=""),
            sa.Column("created_at", sa.DateTime(), nullable=False, server_default=sa.func.now()),
            sa.Column("reviewed_at", sa.DateTime(), nullable=True),
        )
        op.create_index("ix_courierapplication_user_id", "courierapplication", ["user_id"])
        op.create_index("ix_courierapplication_status", "courierapplication", ["status"])

    if "courierprofile" not in tables:
        op.create_table(
            "courierprofile",
            sa.Column("id", sa.Integer(), primary_key=True, nullable=False),
            sa.Column("user_id", sa.Integer(), nullable=False),
            sa.Column("online", sa.Boolean(), nullable=False, server_default=sa.false()),
            sa.Column("car_class", sa.String(length=16), nullable=False, server_default="car"),
            sa.Column("zone", sa.String(length=16), nullable=False, server_default="city"),
            sa.Column("work_city", sa.String(length=80), nullable=False, server_default=""),
            sa.Column("work_direction_id", sa.Integer(), nullable=True),
            sa.Column("updated_at", sa.DateTime(), nullable=False, server_default=sa.func.now()),
        )
        op.create_index("ix_courierprofile_user_id", "courierprofile", ["user_id"], unique=True)
        op.create_index("ix_courierprofile_online", "courierprofile", ["online"])

    # Новые поля курьерского режима на parceldelivery (аддитивно, со server_default).
    cols = _columns(bind, "parceldelivery")
    if "parceldelivery" in tables:
        if "delivery_type" not in cols:
            op.add_column("parceldelivery",
                          sa.Column("delivery_type", sa.String(length=16), nullable=False, server_default="poputka"))
        if "declared_value_kop" not in cols:
            op.add_column("parceldelivery",
                          sa.Column("declared_value_kop", sa.Integer(), nullable=False, server_default="0"))
        if "cod_amount_kop" not in cols:
            op.add_column("parceldelivery",
                          sa.Column("cod_amount_kop", sa.Integer(), nullable=False, server_default="0"))
        if "commission_kop" not in cols:
            op.add_column("parceldelivery",
                          sa.Column("commission_kop", sa.Integer(), nullable=False, server_default="0"))
        if "urgency" not in cols:
            op.add_column("parceldelivery",
                          sa.Column("urgency", sa.String(length=16), nullable=False, server_default="bypath"))


def downgrade() -> None:
    bind = op.get_bind()
    tables = _tables(bind)
    cols = _columns(bind, "parceldelivery")
    if "parceldelivery" in tables:
        for name in ("urgency", "commission_kop", "cod_amount_kop", "declared_value_kop", "delivery_type"):
            if name in cols:
                op.drop_column("parceldelivery", name)
    if "courierprofile" in tables:
        op.drop_table("courierprofile")
    if "courierapplication" in tables:
        op.drop_table("courierapplication")
