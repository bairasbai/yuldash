"""Удаление мёртвых колонок User.vk_id и User.whatsapp_verified.

Колонки никогда не читались/писались (вход через Telegram-код, не VK/WhatsApp). Убраны из модели.

ИДЕМПОТЕНТНО (важно из-за baseline-через-create_all):
- свежая БД: baseline создаёт `user` уже БЕЗ этих колонок (модель их не содержит) → дропать нечего → no-op;
- прод (колонки есть): дропаем.
Так одна и та же миграция работает и на чистой SQLite-в-тестах, и на проде-Postgres.

Прод: `alembic upgrade head` (низкий приоритет — колонки безвредны, если оставить).

Revision ID: 0002_drop_dead_user_columns
Revises: 0001_baseline
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "0002_drop_dead_user_columns"
down_revision = "0001_baseline"
branch_labels = None
depends_on = None

_DEAD = ("vk_id", "whatsapp_verified")


def _user_columns(bind) -> set:
    return {c["name"] for c in inspect(bind).get_columns("user")}


def upgrade() -> None:
    bind = op.get_bind()
    cols = _user_columns(bind)
    to_drop = [c for c in _DEAD if c in cols]
    if not to_drop:
        return  # свежая БД (baseline уже без них) — ничего не делаем
    with op.batch_alter_table("user") as batch:  # batch → корректный DROP COLUMN и на SQLite
        for c in to_drop:
            batch.drop_column(c)


def downgrade() -> None:
    bind = op.get_bind()
    cols = _user_columns(bind)
    with op.batch_alter_table("user") as batch:
        if "vk_id" not in cols:
            batch.add_column(sa.Column("vk_id", sa.String(), nullable=True))
        if "whatsapp_verified" not in cols:
            batch.add_column(sa.Column("whatsapp_verified", sa.Boolean(), nullable=False, server_default=sa.false()))
