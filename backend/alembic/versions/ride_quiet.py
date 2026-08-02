"""Тихая поездка — ride.quiet (удобство: без лишних разговоров/громкой музыки).

ИДЕМПОТЕНТНО (baseline-через-create_all, как соседние ревизии): свежая/dev БД —
create_all уже создал колонку → no-op; прод (создан раньше) — добавляем ride.quiet.

Прод: `alembic upgrade head`.

Revision ID: ride_quiet
Revises: city_profile
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "ride_quiet"
down_revision = "city_profile"
branch_labels = None
depends_on = None


def _columns(bind, table) -> set:
    if table not in set(inspect(bind).get_table_names()):
        return set()
    return {c["name"] for c in inspect(bind).get_columns(table)}


def upgrade() -> None:
    bind = op.get_bind()
    if "ride" in set(inspect(bind).get_table_names()):
        if "quiet" not in _columns(bind, "ride"):
            op.add_column("ride", sa.Column("quiet", sa.Boolean(), nullable=False,
                                            server_default=sa.false()))


def downgrade() -> None:
    bind = op.get_bind()
    if "quiet" in _columns(bind, "ride"):
        with op.batch_alter_table("ride") as batch:
            batch.drop_column("quiet")
