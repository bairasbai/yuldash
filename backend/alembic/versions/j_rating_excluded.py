"""«Щит рейтинга» (Справедливость): флаг rating.excluded.

Админ снимает спорную/накрученную оценку из среднего рейтинга (защита оболганного).
Агрегат (services.user_rating / drivers_bundle) фильтрует excluded=False.

Аддитивно, ИДЕМПОТЕНТНО (паттерн g_tips), оба пути:
- свежая БД: create_all уже создал колонку из модели → no-op;
- прод (Postgres): добавляем недостающую колонку с дефолтом false (старые оценки — учитываются).

Прод: `alembic upgrade head`.

Revision ID: j_rating_excluded
Revises: i_riderequest_status_ix
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "j_rating_excluded"
down_revision = "i_riderequest_status_ix"
branch_labels = None
depends_on = None


def _columns(bind, table: str) -> set:
    try:
        return {c["name"] for c in inspect(bind).get_columns(table)}
    except Exception:  # noqa: BLE001
        return set()


def upgrade() -> None:
    bind = op.get_bind()
    if "excluded" not in _columns(bind, "rating"):
        op.add_column("rating",
                      sa.Column("excluded", sa.Boolean(), nullable=False, server_default=sa.text("false")))


def downgrade() -> None:
    bind = op.get_bind()
    if "excluded" in _columns(bind, "rating"):
        with op.batch_alter_table("rating") as b:
            b.drop_column("excluded")
