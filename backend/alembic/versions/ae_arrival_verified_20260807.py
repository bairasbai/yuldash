"""Честное «подъезжаю»: колонка booking.arrival_verified (GPS подтвердил близость водителя).

ИДЕМПОТЕНТНО, оба пути:
- свежая БД: baseline create_all уже создаёт booking.arrival_verified из модели → тут no-op;
- прод: booking уже есть без колонки → добавляем (false = не подтверждено, прежнее поведение).

Revision ID: ae_arrival_verified
Revises: ad_rating_tags
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "ae_arrival_verified"
down_revision = "ad_rating_tags"
branch_labels = None
depends_on = None


def _columns(bind, table: str) -> set:
    try:
        return {c["name"] for c in inspect(bind).get_columns(table)}
    except Exception:  # noqa: BLE001 — таблицы ещё нет (до baseline) → считаем пустой
        return set()


def upgrade() -> None:
    bind = op.get_bind()
    cols = _columns(bind, "booking")
    if cols and "arrival_verified" not in cols:
        op.add_column("booking", sa.Column("arrival_verified", sa.Boolean(),
                                           nullable=False, server_default=sa.false()))


def downgrade() -> None:
    bind = op.get_bind()
    if "arrival_verified" in _columns(bind, "booking"):
        with op.batch_alter_table("booking") as batch:
            batch.drop_column("arrival_verified")
