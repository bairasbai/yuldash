"""«Не пришёл» на остановках: booking.arriving_at — когда водитель первый раз нажал «Подъезжаю»
по этой брони (2026-10-18).

Зачем. Решение Александра 18.10.2026 (PR182): водитель отмечает неявку не раньше чем через 15 минут
после времени выезда. Но пассажира на промежуточной остановке (`ride.waypoints`) сажают позже
выезда — его 15 минут считались от начальной точки и не защищали ничего: водитель доезжал до
остановки через час, и кнопка уже горела. Теперь на поездке с остановками «Не пришёл» — только
после «Подъезжаю» по этой брони, срок — от позднего из «выезд» и «Подъезжаю». Чтобы его считать,
нужна отметка времени первого «Подъезжаю»: по `driver_phase` её не восстановить.

Старым броням отметку НЕ проставляем: момента нажатия в базе нет, а выдумывать его (например,
«выезд») значило бы открыть «Не пришёл» на остановке раньше времени. Пусто — «ещё не жал».

ИДЕМПОТЕНТНО: на свежей БД колонку уже создал baseline (create_all из моделей) → no-op.

Прод: `alembic upgrade head` — только при выкладке, по отдельному «да» Александра.

Revision ID: by_booking_arriving_at_20261018
Revises: bx_booking_onboard_at_20261017
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "by_booking_arriving_at_20261018"
down_revision = "bx_booking_onboard_at_20261017"
branch_labels = None
depends_on = None

_TABLE = "booking"
_COLUMN = "arriving_at"


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
        op.add_column(_TABLE, sa.Column(_COLUMN, sa.DateTime(), nullable=True))


def downgrade() -> None:
    if _COLUMN in _columns(op.get_bind()):
        op.drop_column(_TABLE, _COLUMN)
