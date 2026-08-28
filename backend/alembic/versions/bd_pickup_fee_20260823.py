"""Дальняя подача — отдельной строкой в рублях (2026-08-23).

ЗАЧЕМ. Дорога водителя К пассажиру оплачивалась множителем ко всей цене, максимум ×1,12.
На поездке за 170 ₽ это +20 ₽ — за 20 километров порожняка. Водитель на такой заказ
не поедет, и он умрёт в «рядом никого». Хуже: если рядом действительно никого не было,
множитель равнялся 1,0 — надбавка отсутствовала ровно там, где она нужнее всего.
В Башкирии между сёлами 20–40 км, это не исключение, а обычный заказ.

ЧТО ДОБАВЛЯЕТ.

`tariff` — правила строки (правятся в базе, без пересборки):
    pickup_free_km   сколько километров подачи входит в цену
    pickup_per_km    ₽ за километр сверх бесплатных (0 = строка выключена)
    pickup_max_rub   потолок строки, ₽

`instantorder` — что зафиксировано на конкретном заказе:
    ride_price       сама поездка, ₽ (без компенсаций) — по нему идут все пересчёты
    pickup_fee_kop   компенсация за подачу, копейки
    pickup_km        сколько километров подачи учтено
    pickup_pending   рядом никого; сумма будет зафиксирована при accept

ЗАПОЛНЕНИЕ СУЩЕСТВУЮЩИХ СТРОК.

Тарифы: ставку проставляем ТОЛЬКО там, где её ещё нет (pickup_per_km = 0 или NULL).
Отличается — значит цену уже правил человек, и наше «улучшение» затёрло бы его решение.
Город: 3 км бесплатно, 11,5 ₽/км, потолок 400 ₽. Межгород: 5 км, 12 ₽/км, потолок 1 200 ₽.
Ставка ≈ себестоимость километра (бензин 65 ₽/л × 7 л/100 км + износ ≈ 3,5 ₽/км).

Заказы: ride_price = price_estimate. У старых заказов компенсаций не было, вся сумма —
это поездка; NULL или ноль тут сломал бы сравнение «цена выросла втрое» при смене адреса.

ИДЕМПОТЕНТНО: каждая колонка проверяется перед добавлением. На свежей БД их уже создал
create_all из моделей — тогда ревизия только заполняет значения.
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "bd_pickup_fee"
down_revision = "bc_merge_heads_20260823"
branch_labels = None
depends_on = None

# (таблица, колонка, тип, значение по умолчанию в SQL)
_COLUMNS = (
    ("tariff", "pickup_free_km", sa.Float(), "3.0"),
    ("tariff", "pickup_per_km", sa.Float(), "0"),
    ("tariff", "pickup_max_rub", sa.Integer(), "0"),
    ("instantorder", "ride_price", sa.Integer(), "0"),
    ("instantorder", "pickup_fee_kop", sa.BigInteger(), "0"),
    ("instantorder", "pickup_km", sa.Float(), "0"),
    ("instantorder", "pickup_pending", sa.Boolean(), sa.false()),
)

# Стартовые правила строки по зонам. Ставим только нетронутым тарифам.
_ZONE_DEFAULTS = (
    ("city", 3.0, 11.5, 400),
    ("intercity", 5.0, 12.0, 1200),
)


def _columns(bind, table: str) -> set:
    insp = inspect(bind)
    if table not in set(insp.get_table_names()):
        return set()
    return {c["name"] for c in insp.get_columns(table)}


def upgrade() -> None:
    bind = op.get_bind()
    have: dict[str, set] = {}
    for table, column, type_, default in _COLUMNS:
        if table not in have:
            have[table] = _columns(bind, table)
        if not have[table] or column in have[table]:
            continue
        op.add_column(table, sa.Column(column, type_, nullable=False, server_default=default))

    tariff_cols = _columns(bind, "tariff")
    if {"pickup_free_km", "pickup_per_km", "pickup_max_rub", "zone"} <= tariff_cols:
        for zone, free_km, per_km, max_rub in _ZONE_DEFAULTS:
            bind.execute(
                sa.text(
                    "UPDATE tariff SET pickup_free_km = :free_km, pickup_per_km = :per_km, "
                    "pickup_max_rub = :max_rub "
                    "WHERE zone = :zone AND (pickup_per_km IS NULL OR pickup_per_km = 0)"
                ),
                {"free_km": free_km, "per_km": per_km, "max_rub": max_rub, "zone": zone},
            )

    order_cols = _columns(bind, "instantorder")
    if {"ride_price", "price_estimate"} <= order_cols:
        bind.execute(
            sa.text(
                "UPDATE instantorder SET ride_price = price_estimate "
                "WHERE ride_price IS NULL OR ride_price = 0"
            )
        )


def downgrade() -> None:
    for table, column, _type, _default in reversed(_COLUMNS):
        try:
            op.drop_column(table, column)
        except Exception:  # noqa: BLE001 — колонки может не быть; откат не должен падать
            pass
