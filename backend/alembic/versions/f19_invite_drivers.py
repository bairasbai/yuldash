"""F19 «Позови водителя»: User.driver_referral_rewarded — водительский трек реферала.

Приглашённый стал водителем и опубликовал первый рейс → пригласивший получает бонус
(бесплатный Boost). Флаг гарантирует начисление РОВНО один раз на приглашённого.

Колонка добавлена в модель `User`. На свежей БД её создаёт `create_all`; на проде
(таблица `user` создана раньше) `create_all` существующую таблицу не трогает → нужна
эта ревизия, иначе обращение к `user.driver_referral_rewarded` на проде → 500.

ИДЕМПОТЕНТНО (baseline-через-create_all):
- свежая БД: create_all уже создал user С колонкой → no-op;
- прод (колонки нет): добавляем `driver_referral_rewarded boolean NOT NULL DEFAULT false`.

Прод: `alembic upgrade head`.

ПРИМЕЧАНИЕ ДЛЯ ЛИДА: миграции нескольких F-веток сведёт лид — ревизия ветвится от
0004_booking_boarding_code; при сведении цепочки поправьте down_revision, если нужно.

Revision ID: f19_invite_drivers
Revises: 0004_booking_boarding_code
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "f19_invite_drivers"
down_revision = "f17_driver_schedule"
branch_labels = None
depends_on = None


def _cols(bind, table: str) -> set:
    return {c["name"] for c in inspect(bind).get_columns(table)}


def upgrade() -> None:
    bind = op.get_bind()
    if "driver_referral_rewarded" not in _cols(bind, "user"):
        with op.batch_alter_table("user") as batch:
            batch.add_column(sa.Column("driver_referral_rewarded", sa.Boolean(), nullable=False, server_default=sa.false()))


def downgrade() -> None:
    bind = op.get_bind()
    if "driver_referral_rewarded" in _cols(bind, "user"):
        with op.batch_alter_table("user") as batch:
            batch.drop_column("driver_referral_rewarded")
