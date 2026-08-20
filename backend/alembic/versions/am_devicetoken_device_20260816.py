"""Приём уведомлений привязан к телефону: devicetoken.device_id (аудит 2026-08-08, волна 147).

Зачем. Перепривязать запись устройства мог КТО УГОДНО, зная строку токена: сервер молча
переписывал владельца. Жертва оставалась без единого устройства и переставала получать всё —
сообщения, «водитель подъехал», напоминание по сигналу SOS. Тихо: приложение выглядит рабочим,
просто уведомления «почему-то не приходят». А пуши захватчика прилетали на телефон жертвы.

При этом законная перепривязка нужна: общий телефон в семье, отец вышел — зашёл сын, и
уведомления должны идти тому, кто сейчас в приложении. Отличаем по телефону: забрать чужую
запись можно только с того самого устройства, на котором она живёт.

Пустое значение — старый клиент без отметки устройства; для него поведение прежнее, иначе
смена человека на общем телефоне сломалась бы у тех, кто не обновился.

ИДЕМПОТЕНТНО: на свежей БД колонку уже создаёт create_all из моделей → no-op.

Прод: `alembic upgrade head`.

Revision ID: am_devicetoken_device
Revises: al_user_last_seen
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "am_devicetoken_device"
down_revision = "al_user_last_seen"
branch_labels = None
depends_on = None

_TABLE = "devicetoken"


def _columns(bind) -> set:
    insp = inspect(bind)
    if _TABLE not in set(insp.get_table_names()):
        return set()
    return {c["name"] for c in insp.get_columns(_TABLE)}


def upgrade() -> None:
    bind = op.get_bind()
    есть = _columns(bind)
    if not есть:                     # таблицы нет — её создаст create_all из моделей
        return
    if "device_id" not in есть:
        op.add_column(_TABLE, sa.Column("device_id", sa.String(), nullable=False,
                                        server_default=""))
        op.create_index("ix_devicetoken_device_id", _TABLE, ["device_id"])


def downgrade() -> None:
    bind = op.get_bind()
    if "device_id" in _columns(bind):
        op.drop_index("ix_devicetoken_device_id", table_name=_TABLE)
        op.drop_column(_TABLE, "device_id")
