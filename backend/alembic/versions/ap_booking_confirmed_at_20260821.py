"""Отметка «водитель принял пассажира»: booking.confirmed_at (аудит 2026-08-08, волна 158).

Зачем. Тяжёлая жалоба («опасное вождение», «домогательство») снимает водителя с линии сразу,
до разбора у админа — и правильно, тут нельзя ждать. Доказательством того, что люди реально
встречались, служила привязка к поездке: есть номер брони — значит ехали вместе.

Проверено пробой: номер брони получает КТО УГОДНО в один тап. Кнопка «Забронировать» открыта
всем, спрашивать водителя не надо. Конкурент из соседнего села бронирует чужую поездку, тут же
жалуется на опасное вождение, отменяет бронь — и водитель снят с линии до ручного разбора
(в пробе: пауза до 2036 года). Никуда ехать не требуется, десять водителей за час.

Настоящее доказательство встречи — это подтверждение ВОДИТЕЛЕМ: односторонний тап постороннего
им не является. Отличить подтверждённую бронь от неподтверждённой по статусу нельзя: отменённая
после подтверждения и отменённая из ожидания выглядят одинаково. Поэтому нужна отметка времени.

ИДЕМПОТЕНТНО: на свежей БД колонку уже создаёт create_all из моделей → no-op.

Старым броням (до этого деплоя) отметку проставляем по факту: confirmed/onboard/done — это
брони, которые водитель уже принял, иначе они бы там не оказались. Без этого честные жалобы
по вчерашним поездкам перестали бы снимать нарушителя с линии.

Прод: `alembic upgrade head`.

Revision ID: ap_booking_confirmed_at
Revises: ao_lost_item_calls
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "ap_booking_confirmed_at"
down_revision = "ao_lost_item_calls"
branch_labels = None
depends_on = None

_TABLE = "booking"


def _columns(bind) -> set:
    insp = inspect(bind)
    if _TABLE not in set(insp.get_table_names()):
        return set()
    return {c["name"] for c in insp.get_columns(_TABLE)}


def upgrade() -> None:
    bind = op.get_bind()
    есть = _columns(bind)
    if not есть:
        return
    if "confirmed_at" not in есть:
        op.add_column(_TABLE, sa.Column("confirmed_at", sa.DateTime(), nullable=True))
    # Задним числом: то, что уже прошло через руки водителя, отмечаем принятым.
    op.execute(
        "UPDATE booking SET confirmed_at = COALESCE(cancelled_at, created_at) "
        "WHERE confirmed_at IS NULL AND status IN ('confirmed', 'onboard', 'done')"
    )


def downgrade() -> None:
    bind = op.get_bind()
    if "confirmed_at" in _columns(bind):
        op.drop_column(_TABLE, "confirmed_at")
