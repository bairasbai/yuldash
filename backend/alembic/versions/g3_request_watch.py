"""G3 «Пуш водителю: заявка по твоему направлению».

Аддитивно (данные не трогаем):
- routewatch.watch_kind (TEXT, default 'rides') — что караулит подписка: rides (поездки
  водителей, прежнее поведение) / requests (заявки пассажиров, G3) / both.

ИДЕМПОТЕНТНО (паттерн w2_livelink/g1_parcel_track), оба пути:
- свежая БД: 0001_baseline (create_all из моделей) уже создал колонку → ревизия no-op;
- прод (Postgres): добавляем недостающую колонку с дефолтом 'rides' (старые подписки —
  это «жду поездки», как и было).

Прод: `alembic upgrade head`.

Revision ID: g3_request_watch
Revises: g1_parcel_track
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "g3_request_watch"
down_revision = "g1_parcel_track"
branch_labels = None
depends_on = None


def _columns(bind, table: str) -> set:
    try:
        return {c["name"] for c in inspect(bind).get_columns(table)}
    except Exception:  # noqa: BLE001
        return set()


def upgrade() -> None:
    bind = op.get_bind()
    if "watch_kind" not in _columns(bind, "routewatch"):
        op.add_column("routewatch",
                      sa.Column("watch_kind", sa.String(), nullable=False, server_default="rides"))


def downgrade() -> None:
    bind = op.get_bind()
    if "watch_kind" in _columns(bind, "routewatch"):
        with op.batch_alter_table("routewatch") as b:
            b.drop_column("watch_kind")
