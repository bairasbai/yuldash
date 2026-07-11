"""RouteWatch — подписка на маршрут «карауль поездку» (F13).

Одна новая таблица:
- routewatch: подписка пользователя на маршрут (from_city→to_city, опц. дата/направление,
  анти-спам last_notified_at, протухание expires_at 14 дней).

Таблицу `notification` НЕ создаёт: хозяин уведомлений — F5 (0005_notification_table),
F13 пишет в неё через хелпер services.push_notification (узел §2 merge-guide).

ИДЕМПОТЕНТНО (baseline-через-create_all):
- свежая БД: create_all уже создал таблицу → no-op (проверяем has_table);
- прод (таблицы нет): создаём.

Прод: `alembic upgrade head`.

Revision ID: 0005_route_watch
Revises: 0004_booking_boarding_code
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "0005_route_watch"
down_revision = "0004_booking_boarding_code"
branch_labels = None
depends_on = None


def upgrade() -> None:
    bind = op.get_bind()
    insp = inspect(bind)
    tables = set(insp.get_table_names())

    if "routewatch" not in tables:
        op.create_table(
            "routewatch",
            sa.Column("id", sa.Integer(), primary_key=True, nullable=False),
            sa.Column("user_id", sa.Integer(), sa.ForeignKey("user.id"), nullable=False),
            sa.Column("from_city", sa.String(), nullable=False),
            sa.Column("to_city", sa.String(), nullable=False),
            sa.Column("watch_date", sa.DateTime(), nullable=True),
            sa.Column("direction", sa.String(), nullable=False, server_default="forward"),
            sa.Column("last_notified_at", sa.DateTime(), nullable=True),
            sa.Column("created_at", sa.DateTime(), nullable=False),
            sa.Column("expires_at", sa.DateTime(), nullable=False),
        )
        op.create_index("ix_routewatch_user_id", "routewatch", ["user_id"])
        op.create_index("ix_routewatch_from_city", "routewatch", ["from_city"])
        op.create_index("ix_routewatch_to_city", "routewatch", ["to_city"])
        op.create_index("ix_routewatch_expires_at", "routewatch", ["expires_at"])


def downgrade() -> None:
    bind = op.get_bind()
    insp = inspect(bind)
    tables = set(insp.get_table_names())
    if "routewatch" in tables:
        op.drop_table("routewatch")
