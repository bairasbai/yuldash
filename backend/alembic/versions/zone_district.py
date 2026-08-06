"""Зона работы по районам: work_district + тумблеры «загород» и «соседние регионы».

Зачем: раньше водитель/курьер выбирал только «мой город», «межгород» или «регион».
Района не было — житель Абзелиловского района не мог сказать «вожу по своему району»,
а с приходом 6600 сёл это стало главным сценарием.

ИДЕМПОТЕНТНО (как villages_district), оба пути:
- свежая БД: baseline create_all уже создаёт колонки из модели → тут no-op;
- прод: добавляем колонки со значениями по умолчанию (поведение не меняется, пока
  человек сам не переключит зону).

ПЕРЕНОС СТАРЫХ ЗНАЧЕНИЙ (данные, не схема — поэтому в этой же ревизии):
- zone='intercity' → база остаётся прежней (city), включается тумблер «загород»;
- zone='region'    → то же + тумблер «соседние регионы».
Так у тех, кто уже выбрал зону, ничего не пропадает: они как получали межгород,
так и получают.

Revision ID: zone_district
Revises: villages_district
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "zone_district"
down_revision = "villages_district"
branch_labels = None
depends_on = None

# (таблица, колонка, тип, значение по умолчанию для существующих строк)
_NEW_COLUMNS = [
    ("driverprofile", "work_district", sa.String(), None),
    ("driverprofile", "work_intercity", sa.Boolean(), False),
    ("driverprofile", "work_regions", sa.Boolean(), False),
    ("courierprofile", "work_district", sa.String(), None),
    ("courierprofile", "work_intercity", sa.Boolean(), False),
    ("courierprofile", "work_regions", sa.Boolean(), False),
]


def _columns(bind, table: str) -> set:
    try:
        return {c["name"] for c in inspect(bind).get_columns(table)}
    except Exception:  # noqa: BLE001 — таблицы ещё нет (до baseline) → считаем пустой
        return set()


def upgrade() -> None:
    bind = op.get_bind()
    for table, column, type_, default in _NEW_COLUMNS:
        cols = _columns(bind, table)
        if not cols or column in cols:
            continue
        op.add_column(table, sa.Column(column, type_, nullable=True))
        if default is not None:
            op.execute(sa.text(f"UPDATE {table} SET {column} = :v WHERE {column} IS NULL")
                       .bindparams(v=default))

    # Перенос старых зон в тумблеры. Идемпотентно: гоняем только по строкам, где зона
    # ещё в старом значении, и её же после переноса приводим к базовой.
    for table, zone_col in (("driverprofile", "work_zone"), ("courierprofile", "zone")):
        if zone_col not in _columns(bind, table):
            continue
        op.execute(sa.text(
            f"UPDATE {table} SET work_intercity = TRUE WHERE {zone_col} IN ('intercity', 'region')"))
        op.execute(sa.text(
            f"UPDATE {table} SET work_regions = TRUE WHERE {zone_col} = 'region'"))
        op.execute(sa.text(
            f"UPDATE {table} SET {zone_col} = 'city' WHERE {zone_col} IN ('intercity', 'region')"))


def downgrade() -> None:
    bind = op.get_bind()
    # Возврат: у кого стоял «загород» — снова intercity/region (данные не теряем).
    for table, zone_col in (("driverprofile", "work_zone"), ("courierprofile", "zone")):
        cols = _columns(bind, table)
        if zone_col not in cols or "work_intercity" not in cols:
            continue
        op.execute(sa.text(
            f"UPDATE {table} SET {zone_col} = 'intercity' WHERE work_intercity = TRUE"))
        op.execute(sa.text(
            f"UPDATE {table} SET {zone_col} = 'region' WHERE work_regions = TRUE"))
    for table, column, _type, _default in _NEW_COLUMNS:
        if column in _columns(bind, table):
            op.drop_column(table, column)
