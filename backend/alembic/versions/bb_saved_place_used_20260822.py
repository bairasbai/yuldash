"""Сохранённые места: когда ими пользовались в последний раз (2026-08-22).

В форме заказа показываем не все сохранённые адреса, а Дом, Работу и три своих. Выбирать
эти три по дате добавления неправильно: наверх лезет случайный адрес «на один раз», а тот,
куда ездят каждую неделю, уходит вниз. Нужна отметка использования — она и добавляется.

Существующим строкам ставим used_at = created_at: другой честной точки отсчёта у нас нет,
а NULL сломал бы сортировку (в SQLite он меньше любой даты и уехал бы в самый низ вместе
с адресами, которыми как раз пользуются).

ИДЕМПОТЕНТНО: колонка проверяется перед добавлением. На свежей БД её уже создал create_all
из моделей — тогда ревизия только заполняет значения.
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "bb_saved_place_used"
down_revision = "ba_taxi_route_edit"
branch_labels = None
depends_on = None

_TABLE = "savedplace"
_COLUMN = "used_at"


def _columns(bind) -> set:
    insp = inspect(bind)
    if _TABLE not in set(insp.get_table_names()):
        return set()
    return {c["name"] for c in insp.get_columns(_TABLE)}


def upgrade() -> None:
    bind = op.get_bind()
    есть = _columns(bind)
    if not есть:
        return          # таблицы нет — её создаст create_all из моделей
    if _COLUMN not in есть:
        # nullable=True: на живых строках колонку иначе не добавить. Значения проставим ниже.
        op.add_column(_TABLE, sa.Column(_COLUMN, sa.DateTime(), nullable=True))
    # Пустым — дату создания. Гоняем и когда колонка уже была: на свежей БД create_all
    # мог оставить NULL у строк, добавленных до этой ревизии.
    op.execute(f"UPDATE {_TABLE} SET {_COLUMN} = created_at WHERE {_COLUMN} IS NULL")


def downgrade() -> None:
    bind = op.get_bind()
    if _COLUMN in _columns(bind):
        op.drop_column(_TABLE, _COLUMN)
