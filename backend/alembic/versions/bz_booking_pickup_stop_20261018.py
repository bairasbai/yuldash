"""Место посадки в брони: booking.pickup_stop — где садится пассажир (2026-10-18).

Зачем. Решение Александра 18.10.2026: на поездке с остановками пассажир выбирает, где садится, —
в начале маршрута или на остановке по пути. От этого зависят честные «Подъезжаю» (GPS сверяется
с его остановкой, а не с пином начала) и «Не пришёл» (не раньше расчётного приезда на остановку),
а водитель видит, где забирать. Значения: "" — начало маршрута, «Акъяр» — остановка (написание
как в `ride.waypoints`), NULL — неизвестно.

Старым броням НЕ проставляем ничего: где садился человек, в базе не записано, а выдумать
(например, «начало») значило бы включить старой брони правила «начала» и открыть «Не пришёл»
пассажиру на остановке раньше времени. NULL = правила PR184, как и до этой ревизии.

Координат не храним — только название (точку каждый раз берём из справочника сёл в памяти).

ИДЕМПОТЕНТНО: на свежей БД колонку уже создал baseline (create_all из моделей) → no-op.

Прод: `alembic upgrade head` — только при выкладке, по отдельному «да» Александра.

Revision ID: bz_booking_pickup_stop_20261018
Revises: by_booking_arriving_at_20261018
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "bz_booking_pickup_stop_20261018"
down_revision = "by_booking_arriving_at_20261018"
branch_labels = None
depends_on = None

_TABLE = "booking"
_COLUMN = "pickup_stop"


def _columns(bind) -> set:
    insp = inspect(bind)
    if _TABLE not in set(insp.get_table_names()):
        return set()
    return {c["name"] for c in insp.get_columns(_TABLE)}


def upgrade() -> None:
    есть = _columns(op.get_bind())
    if not есть:
        return
    if _COLUMN not in есть:
        op.add_column(_TABLE, sa.Column(_COLUMN, sa.String(), nullable=True))


def downgrade() -> None:
    if _COLUMN in _columns(op.get_bind()):
        op.drop_column(_TABLE, _COLUMN)
