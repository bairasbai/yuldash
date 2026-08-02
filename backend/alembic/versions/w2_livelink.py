"""Волна 2, батч B7c «Live-ссылка поездки для близких».

Аддитивно (данные не трогаем):
- tripshare.token (NULL, unique) — capability-токен публичной страницы /t/{token}.
  Старые строки остаются с NULL — токен догенерируется при следующем share
  (см. family.py::_ensure_share_token); unique-индекс NULL'ы не ограничивает.

ИДЕМПОТЕНТНО (паттерн w2_polish2), оба пути:
- свежая БД: 0001_baseline (create_all из моделей) уже создал колонку → ревизия no-op;
- прод (Postgres): добавляем недостающую колонку и unique-индекс.

Прод: `alembic upgrade head`.

Revision ID: w2_livelink
Revises: w2_polish2
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "w2_livelink"
down_revision = "w2_polish2"
branch_labels = None
depends_on = None

_IX = "ix_tripshare_token"


def _columns(bind, table: str) -> set:
    try:
        return {c["name"] for c in inspect(bind).get_columns(table)}
    except Exception:  # noqa: BLE001
        return set()


def _indexes(bind, table: str) -> set:
    try:
        return {i["name"] for i in inspect(bind).get_indexes(table)}
    except Exception:  # noqa: BLE001
        return set()


def upgrade() -> None:
    bind = op.get_bind()
    if "token" not in _columns(bind, "tripshare"):
        op.add_column("tripshare", sa.Column("token", sa.String(), nullable=True))
    if _IX not in _indexes(bind, "tripshare"):
        op.create_index(_IX, "tripshare", ["token"], unique=True)


def downgrade() -> None:
    bind = op.get_bind()
    if _IX in _indexes(bind, "tripshare"):
        op.drop_index(_IX, table_name="tripshare")
    if "token" in _columns(bind, "tripshare"):
        with op.batch_alter_table("tripshare") as b:
            b.drop_column("token")
