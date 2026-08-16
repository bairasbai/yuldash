"""Отметка активности и отвязка номера: user.last_seen_at, user.phone_released_at
(аудит 2026-08-08, волна 139).

Зачем. Вход в Юлдаш — по номеру телефона, а номер человеку не принадлежит: оператор забирает
неиспользуемый номер и через полгода-год продаёт другому. Проверено пробой: новый владелец
номера ставит приложение, входит по SMS и получает ЧУЖОЙ аккаунт целиком — имя, историю
поездок, переписку с водителями, доверенные контакты. Его сигнал SOS ушёл бы маме прежней
хозяйки номера.

Отличить «человек просто сменил телефон» от «номер перешёл к другому» было нечем: отметки
последней активности у нас не существовало вовсе.

`last_seen_at` — когда человек последний раз пользовался (пишется при выдаче и обновлении
ключей входа, у активного это минимум раз в 12 часов).

`phone_released_at` — номер отвязан от аккаунта, потому что перешёл к другому человеку.
Данные при этом НЕ удаляются: если это всё-таки был прежний владелец (уехал на год, сменил
телефон), доступ возвращает поддержка.

ИДЕМПОТЕНТНО: на свежей БД колонки уже создаёт create_all из моделей → no-op.

Прод: `alembic upgrade head`.

Revision ID: al_user_last_seen
Revises: ak_family_sms_log
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "al_user_last_seen"
down_revision = "ak_family_sms_log"
branch_labels = None
depends_on = None

_TABLE = "user"


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
    if "last_seen_at" not in есть:
        op.add_column(_TABLE, sa.Column("last_seen_at", sa.DateTime(), nullable=True))
        op.create_index("ix_user_last_seen_at", _TABLE, ["last_seen_at"])
    if "phone_released_at" not in есть:
        op.add_column(_TABLE, sa.Column("phone_released_at", sa.DateTime(), nullable=True))


def downgrade() -> None:
    bind = op.get_bind()
    есть = _columns(bind)
    if "phone_released_at" in есть:
        op.drop_column(_TABLE, "phone_released_at")
    if "last_seen_at" in есть:
        op.drop_index("ix_user_last_seen_at", table_name=_TABLE)
        op.drop_column(_TABLE, "last_seen_at")
