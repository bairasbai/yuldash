"""След «этот номер уже брал промокод»: таблица promoclaimlog (аудит 2026-08-08, волна 149).

Зачем. «Один код на всю жизнь аккаунта» держал уникальный индекс по человеку. Но удаление
аккаунта уносило запись вместе с ним, а номер оставался тем же. Проверено пробой: вошёл по SMS,
применил код на 300 ₽, съездил, удалил аккаунт, вошёл снова с ТЕМ ЖЕ номером и с того же
телефона — код принялся заново. Три круга подряд.

Каждый круг — прямые деньги: скидку по промокоду оплачивает платформа, водитель получает своё
полностью. С водителем-сообщником это готовый канал обналички с одного номера.

Почему отдельная таблица. Тот же приём, что у пожизненного счётчика рефералов (волна 25) и
журнала SMS близким (волна 48): след должен пережить удаление аккаунта, иначе он исчезает
вместе с уликой.

Приватности не добавляет: самого номера здесь нет — только его ключ (цифры) и отметка
устройства. По ключу человека не найти, если не знать номер заранее.

ИДЕМПОТЕНТНО: на свежей БД таблицу уже создаёт create_all из моделей → no-op.

Прод: `alembic upgrade head`.

Revision ID: an_promo_claim_log
Revises: am_devicetoken_device
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "an_promo_claim_log"
down_revision = "am_devicetoken_device"
branch_labels = None
depends_on = None

_TABLE = "promoclaimlog"


def _tables(bind) -> set:
    return set(inspect(bind).get_table_names())


def upgrade() -> None:
    bind = op.get_bind()
    if _TABLE in _tables(bind):
        return
    op.create_table(
        _TABLE,
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("promo_id", sa.Integer(), sa.ForeignKey("promocode.id"), nullable=False),
        sa.Column("phone_key", sa.String(), nullable=False, server_default=""),
        sa.Column("device_id", sa.String(), nullable=False, server_default=""),
        sa.Column("created_at", sa.DateTime(), nullable=False),
    )
    op.create_index("ix_promoclaimlog_promo_id", _TABLE, ["promo_id"])
    op.create_index("ix_promoclaimlog_phone_key", _TABLE, ["phone_key"])
    op.create_index("ix_promoclaimlog_device_id", _TABLE, ["device_id"])
    op.create_index("ix_promoclaimlog_created_at", _TABLE, ["created_at"])


def downgrade() -> None:
    bind = op.get_bind()
    if _TABLE in _tables(bind):
        op.drop_table(_TABLE)
