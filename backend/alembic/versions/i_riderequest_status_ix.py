"""Индекс riderequest.status (горячий фильтр авто-подбора и ленты заявок).

RideRequest.status фильтруется в /requests и авто-подборе (WHERE status='active') — на проде
это seq-scan по всей таблице каждую минуту. Ride.status уже индексирован, riderequest — нет.

Аддитивно, ИДЕМПОТЕНТНО (паттерн g_tips), оба пути:
- свежая БД: create_all уже создал ix_riderequest_status из модели (index=True) → no-op;
- прод (Postgres): создаём недостающий индекс.

Прод: `alembic upgrade head`.

Revision ID: i_riderequest_status_ix
Revises: h_rate_reminded
"""
from alembic import op
from sqlalchemy import inspect

revision = "i_riderequest_status_ix"
down_revision = "h_rate_reminded"
branch_labels = None
depends_on = None


def _indexes(bind, table: str) -> set:
    try:
        return {ix["name"] for ix in inspect(bind).get_indexes(table)}
    except Exception:  # noqa: BLE001
        return set()


def upgrade() -> None:
    bind = op.get_bind()
    if "ix_riderequest_status" not in _indexes(bind, "riderequest"):
        op.create_index("ix_riderequest_status", "riderequest", ["status"])


def downgrade() -> None:
    bind = op.get_bind()
    if "ix_riderequest_status" in _indexes(bind, "riderequest"):
        op.drop_index("ix_riderequest_status", table_name="riderequest")
