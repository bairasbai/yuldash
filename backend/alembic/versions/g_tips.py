"""«Сказать рәхмәт» (чаевые водителю).

Аддитивно (данные не трогаем):
- driverprofile.tips_sbp (TEXT, default '') — СБП-реквизит водителя для денежных чаевых (opt-in).
- booking.thanked (BOOLEAN, default false) — пассажир сказал «рәхмәт» за поездку (дедуп).

ИДЕМПОТЕНТНО (паттерн g3_request_watch), оба пути:
- свежая БД: 0001_baseline (create_all из моделей) уже создал колонки → ревизия no-op;
- прод (Postgres): добавляем недостающие колонки с дефолтами.

Прод: `alembic upgrade head`.

Revision ID: g_tips
Revises: g3_request_watch
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "g_tips"
down_revision = "g3_request_watch"
branch_labels = None
depends_on = None


def _columns(bind, table: str) -> set:
    try:
        return {c["name"] for c in inspect(bind).get_columns(table)}
    except Exception:  # noqa: BLE001
        return set()


def upgrade() -> None:
    bind = op.get_bind()
    if "tips_sbp" not in _columns(bind, "driverprofile"):
        op.add_column("driverprofile",
                      sa.Column("tips_sbp", sa.String(), nullable=False, server_default=""))
    if "thanked" not in _columns(bind, "booking"):
        op.add_column("booking",
                      sa.Column("thanked", sa.Boolean(), nullable=False, server_default=sa.text("false")))


def downgrade() -> None:
    bind = op.get_bind()
    if "thanked" in _columns(bind, "booking"):
        with op.batch_alter_table("booking") as b:
            b.drop_column("thanked")
    if "tips_sbp" in _columns(bind, "driverprofile"):
        with op.batch_alter_table("driverprofile") as b:
            b.drop_column("tips_sbp")
