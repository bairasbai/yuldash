"""Остановки по пути — ride.waypoints (промежуточные точки маршрута A→точки→B).

Названия НП через " | " (разделитель, чтобы не конфликтовать с запятыми в именах).
ИДЕМПОТЕНТНО (baseline-через-create_all): свежая/dev БД — no-op; прод — добавляем колонку.

Прод: `alembic upgrade head`.

Revision ID: ride_waypoints
Revises: ride_quiet
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "ride_waypoints"
down_revision = "ride_quiet"
branch_labels = None
depends_on = None


def _columns(bind, table) -> set:
    if table not in set(inspect(bind).get_table_names()):
        return set()
    return {c["name"] for c in inspect(bind).get_columns(table)}


def upgrade() -> None:
    bind = op.get_bind()
    if "ride" in set(inspect(bind).get_table_names()):
        if "waypoints" not in _columns(bind, "ride"):
            op.add_column("ride", sa.Column("waypoints", sa.String(), nullable=False,
                                            server_default=""))


def downgrade() -> None:
    bind = op.get_bind()
    if "waypoints" in _columns(bind, "ride"):
        with op.batch_alter_table("ride") as batch:
            batch.drop_column("waypoints")
