"""C3 — рейтинг курьера (взаимные оценки доставки) + биллинг комиссии «на доверии».

ИДЕМПОТЕНТНО (baseline-через-create_all, как соседние ревизии courier_c1/c2/mon_m3/w2_*):
- свежая/dev БД: create_all уже создал колонки/индексы → все шаги no-op;
- прод (создан раньше, без C3): добавляем колонки и индексы.

Добавляем:
- rating.parcel_id                 — привязка оценки к доставке (FK parceldelivery.id, index);
- parceldelivery.commission_paid   — комиссия по доставке оплачена курьером (index, биллинг);
- courierprofile.low_rating_advice_at — дедуп мягкого пуш-совета по качеству;
- courierprofile.paused_until      — мягкая короткая пауза курьера по качеству.

Прод: `alembic upgrade head`.

Revision ID: courier_c3
Revises: courier_c2
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "courier_c3"
down_revision = "courier_c2"
branch_labels = None
depends_on = None


def _tables(bind) -> set:
    return set(inspect(bind).get_table_names())


def _columns(bind, table) -> set:
    if table not in _tables(bind):
        return set()
    return {c["name"] for c in inspect(bind).get_columns(table)}


def _indexes(bind, table) -> set:
    if table not in _tables(bind):
        return set()
    return {i["name"] for i in inspect(bind).get_indexes(table)}


def upgrade() -> None:
    bind = op.get_bind()
    tables = _tables(bind)

    # rating.parcel_id — взаимные оценки доставки (как booking_id/order_id).
    if "rating" in tables:
        if "parcel_id" not in _columns(bind, "rating"):
            op.add_column("rating", sa.Column("parcel_id", sa.Integer(), nullable=True))
        if "ix_rating_parcel_id" not in _indexes(bind, "rating"):
            op.create_index("ix_rating_parcel_id", "rating", ["parcel_id"])

    # parceldelivery.commission_paid — биллинг комиссии платформы «на доверии».
    if "parceldelivery" in tables:
        if "commission_paid" not in _columns(bind, "parceldelivery"):
            op.add_column("parceldelivery",
                          sa.Column("commission_paid", sa.Boolean(), nullable=False,
                                    server_default=sa.false()))
        if "ix_parceldelivery_commission_paid" not in _indexes(bind, "parceldelivery"):
            op.create_index("ix_parceldelivery_commission_paid", "parceldelivery", ["commission_paid"])

    # courierprofile — мягкая лестница качества (совет + пауза).
    if "courierprofile" in tables:
        cols = _columns(bind, "courierprofile")
        if "low_rating_advice_at" not in cols:
            op.add_column("courierprofile", sa.Column("low_rating_advice_at", sa.DateTime(), nullable=True))
        if "paused_until" not in cols:
            op.add_column("courierprofile", sa.Column("paused_until", sa.DateTime(), nullable=True))


def downgrade() -> None:
    bind = op.get_bind()
    tables = _tables(bind)

    if "courierprofile" in tables:
        cols = _columns(bind, "courierprofile")
        with op.batch_alter_table("courierprofile") as batch:
            for name in ("paused_until", "low_rating_advice_at"):
                if name in cols:
                    batch.drop_column(name)

    if "parceldelivery" in tables:
        if "ix_parceldelivery_commission_paid" in _indexes(bind, "parceldelivery"):
            op.drop_index("ix_parceldelivery_commission_paid", table_name="parceldelivery")
        if "commission_paid" in _columns(bind, "parceldelivery"):
            with op.batch_alter_table("parceldelivery") as batch:
                batch.drop_column("commission_paid")

    if "rating" in tables:
        if "ix_rating_parcel_id" in _indexes(bind, "rating"):
            op.drop_index("ix_rating_parcel_id", table_name="rating")
        # rating.parcel_id — FK на parceldelivery → на SQLite чистим через batch (как c2 с report).
        if "parcel_id" in _columns(bind, "rating"):
            with op.batch_alter_table("rating") as batch:
                batch.drop_column("parcel_id")
