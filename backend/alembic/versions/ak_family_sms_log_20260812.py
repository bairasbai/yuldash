"""Журнал SMS близким: таблица familysmslog (аудит 2026-08-12, волна 48).

Зачем. «Поделиться поездкой с близким» шлёт SMS за счёт платформы на номер, который человек
ввёл сам и который никто не подтверждал. Потолок стоял на ЧИСЛЕ близких (10), а не на числе
сообщений: контакт удаляется — слот освобождается, следующий номер получает новую SMS.
Проба: 60 сообщений на 60 разных номеров подряд, ни одного отказа.

Почему отдельная таблица, а не счёт по строкам шаринга. Удаление контакта уносит и его
`tripshare`, то есть счётчик обнулялся бы вместе с уликой. Журнал живёт у ОТПРАВИТЕЛЯ и
переживает удаление контактов — тот же приём, что с пожизненным счётчиком рефералов (волна 25).

Приватности не добавляет: номер получателя сюда НЕ пишется (он и так лежит в своей таблице),
здесь только «кто, какого рода сообщение и когда». Чистится через 30 дней (`cleanup.py`),
удаляется вместе с аккаунтом (`account.py`).

ИДЕМПОТЕНТНО: на свежей БД таблицу уже создаёт create_all из моделей → no-op.

Прод: `alembic upgrade head`.

Revision ID: ak_family_sms_log
Revises: aj_payment_settled_at
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "ak_family_sms_log"
down_revision = "aj_payment_settled_at"
branch_labels = None
depends_on = None

_TABLE = "familysmslog"


def _tables(bind) -> set:
    return set(inspect(bind).get_table_names())


def upgrade() -> None:
    bind = op.get_bind()
    if _TABLE in _tables(bind):
        return
    op.create_table(
        _TABLE,
        sa.Column("id", sa.Integer(), primary_key=True, nullable=False),
        # Без ForeignKey в ALTER-ветке: на SQLite он отдельным ALTER не навешивается.
        # На свежей БД связь ставит create_all из моделей.
        sa.Column("user_id", sa.Integer(), nullable=False),
        sa.Column("kind", sa.String(length=24), nullable=False, server_default=""),
        sa.Column("created_at", sa.DateTime(), nullable=False),
    )
    op.create_index("ix_familysmslog_user_id", _TABLE, ["user_id"])
    op.create_index("ix_familysmslog_created_at", _TABLE, ["created_at"])


def downgrade() -> None:
    bind = op.get_bind()
    if _TABLE not in _tables(bind):
        return
    for idx in ("ix_familysmslog_created_at", "ix_familysmslog_user_id"):
        try:
            op.drop_index(idx, table_name=_TABLE)
        except Exception:  # noqa: BLE001 — индекса может не быть (создан create_all под другим именем)
            pass
    op.drop_table(_TABLE)
