"""Посадку отмечает водитель: booking.onboard_at — когда он нажал «Сел», booking.done_at — когда
поездку закрыл человек (2026-10-17).

Зачем. Решение Александра 17.10.2026: посадку отмечает ВОДИТЕЛЬ, а не пассажир. Пассажирский
«Я сел» можно было нажать из дома — и водитель уже не мог отметить «Не пришёл»; нажатие по
ошибке было не вернуть. Теперь «Сел» жмёт водитель на карточке подтверждённого пассажира, а
промахнувшись карточкой, отменяет свою отметку в первые минуты. Чтобы считать эти минуты,
нужна отметка времени: по статусу брони её не восстановить.

done_at (повторная приёмка PR174): «Сообщить о проблеме» у пассажира после поездки живёт 6 часов
от позднего из «выезд», «Сел» и «поездку закрыли» — долгая поездка, закрытая через восемь часов
после посадки, должна оставлять окно. Ночная уборка done_at не ставит: автозакрытие — не завершение.

Старым броням отметки НЕ проставляем: «в пути» без onboard_at — это посадка от прежнего
пассажирского «Я сел», и отменять её водитель не должен (это не его отметка). Колонка пустая —
ровно этот смысл, бэкфилл его бы стёр.

ИДЕМПОТЕНТНО: на свежей БД колонки уже создал baseline (create_all из моделей) → no-op.

Прод: `alembic upgrade head`.

Revision ID: bx_booking_onboard_at_20261017
Revises: bw_seat_options_20261014
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "bx_booking_onboard_at_20261017"
down_revision = "bw_seat_options_20261014"
branch_labels = None
depends_on = None

_TABLE = "booking"
_COLUMNS = ("onboard_at", "done_at")


def _columns(bind) -> set:
    insp = inspect(bind)
    if _TABLE not in set(insp.get_table_names()):
        return set()
    return {c["name"] for c in insp.get_columns(_TABLE)}


def upgrade() -> None:
    есть = _columns(op.get_bind())
    if not есть:
        return
    for column in _COLUMNS:
        if column not in есть:
            op.add_column(_TABLE, sa.Column(column, sa.DateTime(), nullable=True))


def downgrade() -> None:
    есть = _columns(op.get_bind())
    for column in reversed(_COLUMNS):
        if column in есть:
            op.drop_column(_TABLE, column)
