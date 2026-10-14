"""Опции мест в машине: «сзади максимум двое», «переднее свободно», «хочу переднее» (2026-10-14).

Зачем. Александр попросил простые опции мест, как у BlaBlaCar, без схемы кресел:
* водитель обещает, что сзади поедут не больше двоих (`ride.back_max_two`) — тогда мест
  не больше 3; и говорит, свободно ли переднее (`ride.front_seat_available`) — выключает,
  когда спереди едет свой человек;
* пассажир при брони просит переднее место (`booking.front_seat`).

Барьер «одно переднее на поездку» — частичный уникальный индекс `uq_booking_front_seat_live`:
среди ЖИВЫХ броней поездки (pending / confirmed / onboard) только одна с front_seat. Две
одновременные заявки разводит сама база — вторая получает отказ «Переднее место уже занято».
Отмена, отказ водителя и неявка переводят бронь в cancelled, и место освобождается само.

Существующие поездки и брони НЕ меняем: новые колонки получают значения «как было» —
сзади без ограничения, переднее свободно, никто переднее не просил. Число мест по умолчанию
(4 вместо 3) живёт только в коде и касается новых поездок.

Индекс строим обычным `CREATE UNIQUE INDEX` в той же транзакции, что и колонки: колонка
`front_seat` новая и у всех строк false, так что под индекс не попадает ни одна строка, а
таблица броней небольшая. Зато нет состояния «колонки есть, индекс недостроен» (INVALID),
которое бывает у CONCURRENTLY при оборванном деплое.

ИДЕМПОТЕНТНО: на свежей БД колонки и индекс уже создал baseline (create_all из моделей) → no-op.

Прод: `alembic upgrade head`.

Revision ID: bw_seat_options_20261014
Revises: bd_merge_heads_20261002
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "bw_seat_options_20261014"
down_revision = "bd_merge_heads_20261002"
branch_labels = None
depends_on = None

INDEX = "uq_booking_front_seat_live"
# Та же строка, что FRONT_SEAT_LIVE_WHERE в app/models.py (сторож — test_seat_options.py).
WHERE = "front_seat AND status IN ('pending', 'confirmed', 'onboard')"

# (таблица, колонка, значение по умолчанию для уже существующих строк)
_COLUMNS = (
    ("ride", "back_max_two", sa.false()),
    ("ride", "front_seat_available", sa.true()),
    ("booking", "front_seat", sa.false()),
)


def _columns(bind, table: str) -> set:
    insp = inspect(bind)
    if table not in set(insp.get_table_names()):
        return set()
    return {c["name"] for c in insp.get_columns(table)}


def _has_index(bind) -> bool:
    return INDEX in {ix["name"] for ix in inspect(bind).get_indexes("booking")}


def upgrade() -> None:
    bind = op.get_bind()
    for table, column, default in _COLUMNS:
        есть = _columns(bind, table)
        if есть and column not in есть:
            op.add_column(table, sa.Column(column, sa.Boolean(), nullable=False, server_default=default))
    if _columns(bind, "booking") and not _has_index(bind):
        op.execute(f"CREATE UNIQUE INDEX IF NOT EXISTS {INDEX} ON booking (ride_id) WHERE {WHERE}")


def downgrade() -> None:
    bind = op.get_bind()
    if _columns(bind, "booking"):
        op.execute(f"DROP INDEX IF EXISTS {INDEX}")
    for table, column, _ in reversed(_COLUMNS):
        if column in _columns(bind, table):
            op.drop_column(table, column)
