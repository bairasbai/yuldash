"""Опции салона стали платными: сумма на заказе (2026-08-23).

ЗАЧЕМ. Детское кресло, бустер, перевозка животного и большой багаж были бесплатны. Кресло
стоит 3–8 тысяч, живёт три-четыре года и занимает багажник постоянно — за ноль рублей
водитель просто не включает галочку «у меня есть кресло», и заказ мамы с ребёнком не находит
машину вообще. Функция была, а работать не могла.

Теперь: кресло/люлька/бустер — 150 ₽, животное и большой багаж — по 100 ₽. Деньги уходят
водителю целиком, комиссия с них не берётся (это компенсация расходов, а не наша выручка).

⚠️ Инвалидная коляска и собака-проводник — всегда 0 ₽, и это зашито в коде, а не в конфиге.

ЧТО ДОБАВЛЯЕТ. `instantorder.options_fee_kop` — сколько опции стоили на конкретном заказе,
копейки. Храним на заказе, а не считаем на лету: цена опции может измениться завтра, а чек
за вчерашнюю поездку меняться не должен.

ИДЕМПОТЕНТНО: колонка проверяется перед добавлением. На свежей БД её уже создал create_all
из моделей — тогда ревизия не делает ничего.
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "bf_options_fee"
down_revision = "be_pickup_enroute"
branch_labels = None
depends_on = None

_TABLE = "instantorder"
_COLUMN = "options_fee_kop"


def _columns(bind) -> set:
    insp = inspect(bind)
    if _TABLE not in set(insp.get_table_names()):
        return set()
    return {c["name"] for c in insp.get_columns(_TABLE)}


def upgrade() -> None:
    bind = op.get_bind()
    have = _columns(bind)
    if have and _COLUMN not in have:
        op.add_column(_TABLE, sa.Column(_COLUMN, sa.BigInteger(), nullable=False,
                                        server_default="0"))


def downgrade() -> None:
    try:
        op.drop_column(_TABLE, _COLUMN)
    except Exception:  # noqa: BLE001 — колонки может не быть; откат не должен падать
        pass
