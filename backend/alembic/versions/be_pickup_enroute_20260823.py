"""Подача дешевле, когда водителю по пути: отметка на заказе (2026-08-23).

ЗАЧЕМ. Компенсация за подачу платит за бензин, который водитель сожжёт РАДИ этого заказа.
Если он и так возвращается в своё село (или уже едет в эту сторону), топливо тратится в любом
случае — брать с пассажира полную цену нечестно. Такая подача стоит вдвое дешевле
(`settings.pickup_enroute_discount_percent`).

Одного числа в `pickup_fee_kop` мало: в чеке нельзя объяснить, почему у соседа за ту же
дорогу вышло дороже. Поэтому храним сам ФАКТ скидки.

ИДЕМПОТЕНТНО: колонка проверяется перед добавлением. На свежей БД её уже создал create_all
из моделей — тогда ревизия не делает ничего.
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "be_pickup_enroute"
down_revision = "bd_pickup_fee"
branch_labels = None
depends_on = None

_TABLE = "instantorder"
_COLUMN = "pickup_enroute"


def _columns(bind) -> set:
    insp = inspect(bind)
    if _TABLE not in set(insp.get_table_names()):
        return set()
    return {c["name"] for c in insp.get_columns(_TABLE)}


def upgrade() -> None:
    bind = op.get_bind()
    have = _columns(bind)
    if have and _COLUMN not in have:
        op.add_column(_TABLE, sa.Column(_COLUMN, sa.Boolean(), nullable=False,
                                        server_default=sa.false()))


def downgrade() -> None:
    try:
        op.drop_column(_TABLE, _COLUMN)
    except Exception:  # noqa: BLE001 — колонки может не быть; откат не должен падать
        pass
