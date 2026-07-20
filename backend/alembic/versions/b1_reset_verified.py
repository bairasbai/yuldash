"""B1 (аудит #73): сбросить фиктивный verified у непрошедших модерацию.

Регистрация раньше ставила `User.verified=True` ВСЕМ, а слой доверия трактует `verified`
как L2 «Проверен» (документы проверены модерацией). Итог: любой новорег становился L2 →
проходил гейт инвайтов → за 2 клика делался «своим» (видел поездки «только для своих»),
а бейдж «Проверен» горел у каждого водителя без модерации; плюс допуск в такси-матчинг.

Код уже не ставит verified при входе (только модерация). Эта миграция чинит УЖЕ накопленные
данные: снимает verified у всех, КРОМЕ реально промодерированных водителей
(driverprofile.docs_status='verified'). Идемпотентно (повторный прогон ничего не меняет).

Revision ID: b1_reset_verified
Revises: merge_20260720
"""
import sqlalchemy as sa
from alembic import op

revision = "b1_reset_verified"
down_revision = "merge_20260720"
branch_labels = None
depends_on = None


def upgrade() -> None:
    bind = op.get_bind()
    # "user" — зарезервированное слово в Postgres → в кавычках. true/false vs 1/0 по диалекту.
    if bind.dialect.name == "postgresql":
        op.execute(sa.text(
            'UPDATE "user" SET verified = false '
            'WHERE verified = true '
            "AND id NOT IN (SELECT user_id FROM driverprofile WHERE docs_status = 'verified')"
        ))
    else:  # sqlite (тест/дев)
        op.execute(sa.text(
            'UPDATE "user" SET verified = 0 '
            'WHERE verified = 1 '
            "AND id NOT IN (SELECT user_id FROM driverprofile WHERE docs_status = 'verified')"
        ))


def downgrade() -> None:
    # Необратимо: какие verified были «фиктивными», а какие настоящими на момент сброса — не хранится.
    # Повторно verified=True всем ставить НЕЛЬЗЯ (вернём дыру). No-op.
    pass
