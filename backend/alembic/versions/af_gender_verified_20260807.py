"""Пол водителя подтверждает модератор: колонка driverprofile.gender_verified.

ИДЕМПОТЕНТНО, оба пути:
- свежая БД: baseline create_all уже создаёт колонку из модели → тут no-op;
- прод: driverprofile есть без колонки → добавляем (false = не подтверждён).

ВНИМАНИЕ ПРИ ВЫКАТКЕ: значение по умолчанию false осознанно. У водителей, которые
раньше сами отметили «женщина», бейдж «женщина за рулём» пропадёт, пока модератор не
подтвердит его в админке. Это правильное поведение для фичи безопасности — лучше не
показать настоящую женщину-водителя, чем показать мужчину как женщину. Список тех, кого
надо пересмотреть: SELECT user_id FROM driverprofile WHERE gender <> ''.

Revision ID: af_gender_verified
Revises: ae_arrival_verified
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "af_gender_verified"
down_revision = "ae_arrival_verified"
branch_labels = None
depends_on = None


def _columns(bind, table: str) -> set:
    try:
        return {c["name"] for c in inspect(bind).get_columns(table)}
    except Exception:  # noqa: BLE001 — таблицы ещё нет (до baseline) → считаем пустой
        return set()


def upgrade() -> None:
    bind = op.get_bind()
    cols = _columns(bind, "driverprofile")
    if cols and "gender_verified" not in cols:
        op.add_column("driverprofile", sa.Column("gender_verified", sa.Boolean(),
                                                 nullable=False, server_default=sa.false()))


def downgrade() -> None:
    bind = op.get_bind()
    if "gender_verified" in _columns(bind, "driverprofile"):
        with op.batch_alter_table("driverprofile") as batch:
            batch.drop_column("gender_verified")
