"""F17 — таблица driverschedule (постоянные маршруты водителя).

Регулярный маршрут водителя: from_city/to_city + дни недели (CSV ISO 1–7) + время +
active. Показывается в профиле водителя и в поиске. Подписку пассажира (route-watch, F13)
эта таблица НЕ хранит.

ИДЕМПОТЕНТНО (baseline-через-create_all):
- свежая БД: create_all уже создал `driverschedule` → пропускаем (no-op);
- прод (таблицы нет): создаём таблицу + индексы.

Прод: `alembic upgrade head`. Уникальный revision id, чтобы миграции разных фич не
конфликтовали — сведение веток лид делает вручную.

Revision ID: f17_driver_schedule
Revises: 0004_booking_boarding_code
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "f17_driver_schedule"
down_revision = "f14_pickup_points"
branch_labels = None
depends_on = None

_TABLE = "driverschedule"


def _has_table(bind, name: str) -> bool:
    return name in inspect(bind).get_table_names()


def upgrade() -> None:
    bind = op.get_bind()
    if _has_table(bind, _TABLE):
        return
    op.create_table(
        _TABLE,
        sa.Column("id", sa.Integer(), primary_key=True, nullable=False),
        sa.Column("driver_id", sa.Integer(), sa.ForeignKey("user.id"), nullable=False),
        sa.Column("from_city", sa.String(), nullable=False),
        sa.Column("to_city", sa.String(), nullable=False),
        sa.Column("weekdays", sa.String(), nullable=False, server_default=""),
        sa.Column("time", sa.String(), nullable=False, server_default=""),
        sa.Column("comment", sa.String(), nullable=False, server_default=""),
        sa.Column("active", sa.Boolean(), nullable=False, server_default=sa.true()),
        sa.Column("created_at", sa.DateTime(), nullable=False),
    )
    op.create_index("ix_driverschedule_driver_id", _TABLE, ["driver_id"])
    op.create_index("ix_driverschedule_from_city", _TABLE, ["from_city"])
    op.create_index("ix_driverschedule_to_city", _TABLE, ["to_city"])
    op.create_index("ix_driverschedule_active", _TABLE, ["active"])


def downgrade() -> None:
    bind = op.get_bind()
    if not _has_table(bind, _TABLE):
        return
    for ix in (
        "ix_driverschedule_active",
        "ix_driverschedule_to_city",
        "ix_driverschedule_from_city",
        "ix_driverschedule_driver_id",
    ):
        op.drop_index(ix, table_name=_TABLE)
    op.drop_table(_TABLE)
