"""Водитель видел смену способа расчёта (2026-08-28).

ЗАЧЕМ. Пассажир может сменить способ прямо в поездке — про наличные вспоминают, уже сидя
в машине. Водителю уходит пуш, и на этом всё: за рулём уведомление пропускают, а посмотреть
глазами было негде. Приезжают — один достаёт телефон, другой ждёт наличные.

Это ровно та же история, что со сменой адреса, и решается тем же способом: помним, когда
способ поменяли и видел ли это водитель. Не видел дольше минуты — пассажиру честно говорим
«водитель ещё не в курсе, позвони», как мы уже делаем с адресом.

ЧТО ДОБАВЛЯЕТ.
* `payment_changed_at` — когда пассажир последний раз менял способ по ходу поездки.
* `payment_ack_at` — когда водитель нажал «Понял». Пусто при непросмотренной смене.

Обе колонки nullable: у старых заказов смены не было, и пустота здесь — правда, а не пробел.

ИДЕМПОТЕНТНО: колонки проверяются перед добавлением.
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "bi_payment_seen"
down_revision = "bh_winter_road"
branch_labels = None
depends_on = None

_TABLE = "instantorder"
_COLUMNS = (
    ("payment_changed_at", sa.DateTime(), None),
    ("payment_ack_at", sa.DateTime(), None),
)


def _columns(bind) -> set:
    insp = inspect(bind)
    if _TABLE not in set(insp.get_table_names()):
        return set()
    return {c["name"] for c in insp.get_columns(_TABLE)}


def upgrade() -> None:
    bind = op.get_bind()
    have = _columns(bind)
    if not have:
        return
    for name, type_, _default in _COLUMNS:
        if name not in have:
            op.add_column(_TABLE, sa.Column(name, type_, nullable=True))


def downgrade() -> None:
    for name, _type, _default in reversed(_COLUMNS):
        try:
            op.drop_column(_TABLE, name)
        except Exception:  # noqa: BLE001 — колонки может не быть; откат не должен падать
            pass
