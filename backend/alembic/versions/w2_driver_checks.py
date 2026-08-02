"""Проверки водителя, Уровень 1: TaxiApplication.selfie_url + criminal_record_url.

Селфи с правами в руках (сверка лица с документом) + справка о несудимости (опц.).
Обе — приватные защищённые URL (как permit_photo_url/osago_url). Проверяет админ вручную.

Колонки добавлены в модель `TaxiApplication`. На свежей БД их создаёт `create_all`; на
проде (таблица создана раньше) `create_all` существующую таблицу не трогает → нужна эта
ревизия, иначе обращение к новым полям на проде → 500.

ИДЕМПОТЕНТНО (baseline-через-create_all):
- свежая БД: create_all уже создал таблицу С колонками → no-op;
- прод (колонок нет): добавляем `selfie_url` / `criminal_record_url` (nullable VARCHAR).

Прод: `alembic upgrade head`.

Revision ID: w2_driver_checks
Revises: w2_extras
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "w2_driver_checks"
down_revision = "w2_extras"
branch_labels = None
depends_on = None


def _cols(bind, table: str) -> set:
    return {c["name"] for c in inspect(bind).get_columns(table)}


def upgrade() -> None:
    bind = op.get_bind()
    cols = _cols(bind, "taxiapplication")
    with op.batch_alter_table("taxiapplication") as batch:
        if "selfie_url" not in cols:
            batch.add_column(sa.Column("selfie_url", sa.String(), nullable=True))
        if "criminal_record_url" not in cols:
            batch.add_column(sa.Column("criminal_record_url", sa.String(), nullable=True))


def downgrade() -> None:
    bind = op.get_bind()
    cols = _cols(bind, "taxiapplication")
    with op.batch_alter_table("taxiapplication") as batch:
        if "criminal_record_url" in cols:
            batch.drop_column("criminal_record_url")
        if "selfie_url" in cols:
            batch.drop_column("selfie_url")
