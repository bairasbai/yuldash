"""Журнал причин отказа водителя от оффера (разбор №2, 2026-08-03).

Отказ был безмолвным: платформа видела только «заказ не берут» и продолжала слать такие же
заказы тем же людям. Диагностировать «почему в Баймаке никто не едет» было нечем — далеко
подавать? мало денег? направление неудобное? Теперь водитель может назвать причину одним
тапом, и это становится статистикой, по которой видно, что чинить: радиус, тариф, расписание.

Наказания за отказ нет и не планируется: иначе водитель перестанет отказываться честно и
просто уйдёт в офлайн, а это хуже для всех — машина есть, но её не видно.

Персональных данных таблица не содержит: заказ, водитель, слово-причина, время. Живёт 90 дней
(`cleanup.py`), удаляется вместе с аккаунтом (`account.py`).

Аддитивно и идемпотентно: на свежей БД таблицу уже создал `create_all` из моделей → no-op.

Прод: `alembic upgrade head`.

Revision ID: z_decline_reason
Revises: y_utc_depart
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "z_decline_reason"
down_revision = "y_utc_depart"
branch_labels = None
depends_on = None

_TABLE = "offerdecline"


def _tables(bind) -> set:
    return set(inspect(bind).get_table_names())


def upgrade() -> None:
    bind = op.get_bind()
    if _TABLE in _tables(bind):
        return
    op.create_table(
        _TABLE,
        sa.Column("id", sa.Integer(), primary_key=True, nullable=False),
        # Без ForeignKey в ALTER-ветке: на SQLite он отдельным ALTER не навешивается, а
        # целостность здесь не критична — это журнал, а не деньги. На свежей БД FK ставит
        # create_all из моделей.
        sa.Column("order_id", sa.Integer(), nullable=False),
        sa.Column("driver_id", sa.Integer(), nullable=False),
        sa.Column("reason", sa.String(length=32), nullable=False, server_default=""),
        sa.Column("created_at", sa.DateTime(), nullable=False),
    )
    op.create_index("ix_offerdecline_order_id", _TABLE, ["order_id"])
    op.create_index("ix_offerdecline_driver_id", _TABLE, ["driver_id"])
    op.create_index("ix_offerdecline_created_at", _TABLE, ["created_at"])


def downgrade() -> None:
    bind = op.get_bind()
    if _TABLE not in _tables(bind):
        return
    for idx in ("ix_offerdecline_created_at", "ix_offerdecline_driver_id", "ix_offerdecline_order_id"):
        try:
            op.drop_index(idx, table_name=_TABLE)
        except Exception:  # noqa: BLE001 — индекса может не быть (создан create_all под другим именем)
            pass
    op.drop_table(_TABLE)
