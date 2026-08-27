"""Зимняя дорога: компенсация водителю отдельной строкой (2026-08-23).

ЗАЧЕМ. Погода в цене была множителем (до +10%) и сидела ВНУТРИ общего потолка ×1,5 вместе
со спросом: в метель, когда заказов много, спрос съедал потолок, и надбавка за погоду
обнулялась ровно тогда, когда ехать тяжелее всего. Вдобавок множитель питался от Яндекс.Погоды,
ключа к которой у нас нет, — то есть погода в цене «была» и не работала ни дня.

Теперь это строка счёта: 1,5 ₽ за километр тяжёлой дороги (гололёд, метель, сильный снег,
мороз), потолок — 15% от цены поездки. Источник — Open-Meteo, тот же, что в предупреждениях
о погоде: бесплатно и без ключа. Деньги идут водителю целиком, комиссия не берётся.

ЧТО ДОБАВЛЯЕТ. `weather_fee_kop` — сколько вышло на этом заказе; `weather_kind` — какая
именно погода. Второе нужно чеку: «Гололёд +45 ₽» объясняет, а «погода +45 ₽» — нет.

ИДЕМПОТЕНТНО: колонки проверяются перед добавлением.
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "bh_winter_road"
down_revision = "bg_honest_receipt"
branch_labels = None
depends_on = None

_TABLE = "instantorder"
_COLUMNS = (
    ("weather_fee_kop", sa.BigInteger(), "0"),
    ("weather_kind", sa.String(length=16), "''"),
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
    for name, type_, default in _COLUMNS:
        if name not in have:
            op.add_column(_TABLE, sa.Column(name, type_, nullable=False, server_default=default))


def downgrade() -> None:
    for name, _type, _default in reversed(_COLUMNS):
        try:
            op.drop_column(_TABLE, name)
        except Exception:  # noqa: BLE001 — колонки может не быть; откат не должен падать
            pass
