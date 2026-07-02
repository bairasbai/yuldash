"""Партнёрский кабинет рекламы: колонки владения / модерации / тарифа.

Добавляет:
- User.is_advertiser (флаг «рекламодатель»);
- Ad.owner_id (партнёр-владелец), reject_reason (причина отказа), package/budget_kop/period_days
  (тариф), submitted_at/reviewed_at (метки модерации).
Новые статусы объявления (pending_review / rejected) — это просто значения строки `status`,
схему не меняют, миграции не требуют.

ИДЕМПОТЕНТНО (важно из-за baseline-через-create_all):
- свежая БД: create_all уже создаёт таблицы С этими колонками (модель их содержит) → добавлять
  нечего → no-op;
- прод (колонок ещё нет): добавляем.
Одна миграция работает и на чистой SQLite-в-тестах, и на проде-Postgres.

Прод: `alembic upgrade head`.

Revision ID: 0003_partner_ads_columns
Revises: 0002_drop_dead_user_columns
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "0003_partner_ads_columns"
down_revision = "0002_drop_dead_user_columns"
branch_labels = None
depends_on = None

# (имя, тип, kwargs Column) — колонки, добавляемые в таблицу ad
_AD_COLS = (
    ("reject_reason", sa.String(), dict(nullable=False, server_default="")),
    ("package", sa.String(), dict(nullable=False, server_default="")),
    ("budget_kop", sa.Integer(), dict(nullable=False, server_default="0")),
    ("period_days", sa.Integer(), dict(nullable=False, server_default="0")),
    ("owner_id", sa.Integer(), dict(nullable=True)),
    ("submitted_at", sa.DateTime(), dict(nullable=True)),
    ("reviewed_at", sa.DateTime(), dict(nullable=True)),
)


def _cols(bind, table: str) -> set:
    return {c["name"] for c in inspect(bind).get_columns(table)}


def _indexes(bind, table: str) -> set:
    return {i["name"] for i in inspect(bind).get_indexes(table)}


def upgrade() -> None:
    bind = op.get_bind()

    # User.is_advertiser
    if "is_advertiser" not in _cols(bind, "user"):
        with op.batch_alter_table("user") as batch:
            batch.add_column(sa.Column("is_advertiser", sa.Boolean(), nullable=False, server_default=sa.false()))

    # Ad-колонки — добавляем только недостающие
    to_add = [(n, t, kw) for (n, t, kw) in _AD_COLS if n not in _cols(bind, "ad")]
    if to_add:
        with op.batch_alter_table("ad") as batch:
            for name, type_, kw in to_add:
                batch.add_column(sa.Column(name, type_, **kw))

    # индекс на Ad.owner_id (имя = как у SQLModel index=True)
    if "owner_id" in _cols(bind, "ad") and "ix_ad_owner_id" not in _indexes(bind, "ad"):
        op.create_index("ix_ad_owner_id", "ad", ["owner_id"])


def downgrade() -> None:
    bind = op.get_bind()

    if "ix_ad_owner_id" in _indexes(bind, "ad"):
        op.drop_index("ix_ad_owner_id", table_name="ad")

    ad_have = _cols(bind, "ad")
    to_drop = [n for (n, _, _) in _AD_COLS if n in ad_have]
    if to_drop:
        with op.batch_alter_table("ad") as batch:
            for name in to_drop:
                batch.drop_column(name)

    if "is_advertiser" in _cols(bind, "user"):
        with op.batch_alter_table("user") as batch:
            batch.drop_column("is_advertiser")
