"""C2 — «купи и привези»: расчёт с получателем + объявленная ценность (спор).

ИДЕМПОТЕНТНО (baseline-через-create_all, как соседние ревизии courier_c1/mon_m3/w2_*):
- свежая/dev БД: create_all уже создал колонки → все шаги no-op;
- прод (создан раньше, без C2): добавляем колонки к parceldelivery и report.

Добавляем:
- parceldelivery.delivery_price_kop — цена доставки для получателя (без комиссии), фикс при создании;
- parceldelivery.goods_actual_kop  — фактически потрачено курьером на товар (buy_bring);
- parceldelivery.settled           — получатель рассчитался (товар + доставка);
- parceldelivery.settled_at        — когда рассчитался;
- report.parcel_id                 — привязка спора к доставке (category=parcel_dispute).

Прод: `alembic upgrade head`.

Revision ID: courier_c2
Revises: courier_c1
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "courier_c2"
down_revision = "courier_c1"
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

    if "parceldelivery" in tables:
        cols = _columns(bind, "parceldelivery")
        if "delivery_price_kop" not in cols:
            op.add_column("parceldelivery",
                          sa.Column("delivery_price_kop", sa.Integer(), nullable=False, server_default="0"))
        if "goods_actual_kop" not in cols:
            op.add_column("parceldelivery",
                          sa.Column("goods_actual_kop", sa.Integer(), nullable=False, server_default="0"))
        if "settled" not in cols:
            op.add_column("parceldelivery",
                          sa.Column("settled", sa.Boolean(), nullable=False, server_default=sa.false()))
        if "settled_at" not in cols:
            op.add_column("parceldelivery",
                          sa.Column("settled_at", sa.DateTime(), nullable=True))

    if "report" in tables:
        rcols = _columns(bind, "report")
        if "parcel_id" not in rcols:
            op.add_column("report",
                          sa.Column("parcel_id", sa.Integer(), nullable=True))


def downgrade() -> None:
    bind = op.get_bind()
    tables = _tables(bind)

    # report.parcel_id создаётся как FK (модель) → на SQLite обычный DROP COLUMN падает
    # («unknown column in foreign key definition»). batch_alter_table пересоздаёт таблицу чисто.
    if "report" in tables and "parcel_id" in _columns(bind, "report"):
        with op.batch_alter_table("report") as batch:
            batch.drop_column("parcel_id")

    if "parceldelivery" in tables:
        cols = _columns(bind, "parceldelivery")
        with op.batch_alter_table("parceldelivery") as batch:
            for name in ("settled_at", "settled", "goods_actual_kop", "delivery_price_kop"):
                if name in cols:
                    batch.drop_column(name)
