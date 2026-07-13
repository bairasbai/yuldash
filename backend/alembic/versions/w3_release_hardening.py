"""Прод-доводка: UNIQUE(commissiondebt.order_id) + tripshare.expires_at.

1) UNIQUE на commissiondebt.order_id — DB-барьер против гонки двойного «done» (двойной тап/ретрай
   мог создать две записи долга на один заказ → двойная комиссия таксисту).
2) tripshare.expires_at (nullable) — TTL live-ссылки: «зависшая» поездка не отдаёт гео бессрочно.

ИДЕМПОТЕНТНО (baseline-через-create_all):
- свежая БД: create_all уже создал ограничение/колонку из модели → проверяем и no-op;
- прод: добавляем недостающее.

Прод: `alembic upgrade head`.

Revision ID: w3_release_hardening
Revises: w2_status_indexes
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "w3_release_hardening"
down_revision = "w2_status_indexes"
branch_labels = None
depends_on = None

_UQ = "uq_commissiondebt_order_id"


def upgrade() -> None:
    bind = op.get_bind()
    insp = inspect(bind)
    tables = set(insp.get_table_names())

    if "commissiondebt" in tables:
        uniques = {c["name"] for c in insp.get_unique_constraints("commissiondebt")}
        if _UQ not in uniques:
            op.create_unique_constraint(_UQ, "commissiondebt", ["order_id"])

    if "tripshare" in tables:
        cols = {c["name"] for c in insp.get_columns("tripshare")}
        if "expires_at" not in cols:
            op.add_column("tripshare", sa.Column("expires_at", sa.DateTime(), nullable=True))


def downgrade() -> None:
    bind = op.get_bind()
    insp = inspect(bind)
    tables = set(insp.get_table_names())

    if "tripshare" in tables:
        cols = {c["name"] for c in insp.get_columns("tripshare")}
        if "expires_at" in cols:
            op.drop_column("tripshare", "expires_at")

    if "commissiondebt" in tables:
        uniques = {c["name"] for c in insp.get_unique_constraints("commissiondebt")}
        if _UQ in uniques:
            op.drop_constraint(_UQ, "commissiondebt", type_="unique")
