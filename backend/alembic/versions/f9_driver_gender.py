"""DriverProfile.gender — пол водителя (F9 «Женщинам — водитель-женщина»).

Опциональное opt-in поле: "" (не указан, по умолчанию) / female / male.
Добавлено в модель `DriverProfile`; на свежей БД его создаёт `create_all`,
на проде (таблица создана раньше) колонки НЕТ — нужен ALTER TABLE.

ИДЕМПОТЕНТНО (как соседние ревизии 0003/0004):
- свежая БД: create_all уже создал driverprofile С колонкой → no-op;
- прод (колонки нет): добавляем `gender varchar NOT NULL DEFAULT ''`.

Прод: `alembic upgrade head`.

⚠️ Ветка фичи: down_revision = текущий head feat-base (0004). Несколько
фича-веток ответвляются от 0004 → возникнут параллельные heads; цепочку
миграций сведёт лид при merge (перецепит down_revision по порядку слияния).

Revision ID: f9_driver_gender
Revises: 0004_booking_boarding_code
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "f9_driver_gender"
down_revision = "0004_booking_boarding_code"
branch_labels = None
depends_on = None


def _cols(bind, table: str) -> set:
    return {c["name"] for c in inspect(bind).get_columns(table)}


def upgrade() -> None:
    bind = op.get_bind()
    if "gender" not in _cols(bind, "driverprofile"):
        with op.batch_alter_table("driverprofile") as batch:
            batch.add_column(sa.Column("gender", sa.String(), nullable=False, server_default=""))


def downgrade() -> None:
    bind = op.get_bind()
    if "gender" in _cols(bind, "driverprofile"):
        with op.batch_alter_table("driverprofile") as batch:
            batch.drop_column("gender")
