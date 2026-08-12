"""Классы машин Эконом/Комфорт/Бизнес/Минивэн, опции салона, ОСГОП.

Что появилось (спека — docs/taxi-classes-2026-08.md):
  • driverprofile: характеристики машины (год, кондиционер, седан, кожа, чистота, кузов),
    насчитанные классификатором классы и включённые водителем, опции салона;
  • instantorder: запрошенные опции и классы, которые пассажир САМ согласился добавить
    к поиску, когда в выбранном никого не было;
  • taxiapplication: ОСГОП (обязателен для всех перевозчиков с 01.09.2024).

ИДЕМПОТЕНТНО: колонку добавляем, только если её нет — свежая БД получает всё из create_all
(baseline), прод дополняем. Значения по умолчанию подобраны так, чтобы прежнее поведение
не менялось: пустые классы читаются через car_class.available_or_legacy() и дают ровно то
распределение заказов, что было до выката.

ПОБОЧНО ЧИНИТ РАСХОЖДЕНИЕ ИСТОРИИ. До этой ревизии у alembic было ДВЕ головы —
`ah_text_flags` (ветка ag_minors) и `money_holes_20260807` (ветка ad_rating_tags), — и
`alembic upgrade head` падал с «Multiple head revisions are present». На прод накатывали
по одной ветке вручную, а свежая БД не поднималась вообще. Здесь ветки сведены: down_revision
указывает на обе, дальше история снова линейная.

Revision ID: ai_car_classes
Revises: ah_text_flags, money_holes_20260807
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "ai_car_classes"
down_revision = ("ah_text_flags", "money_holes_20260807")
branch_labels = None
depends_on = None

# (таблица, колонка, тип, значение по умолчанию на сервере)
_COLUMNS = (
    ("driverprofile", "car_year", sa.Integer(), None),
    ("driverprofile", "car_ac", sa.Boolean(), sa.false()),
    ("driverprofile", "car_sedan", sa.Boolean(), sa.false()),
    ("driverprofile", "car_leather", sa.Boolean(), sa.false()),
    ("driverprofile", "car_premium_verified", sa.Boolean(), sa.false()),
    # Чистота салона и целость кузова — по умолчанию TRUE: человеку верим, модератор снимает
    # по фото. Иначе выкат разом уронил бы всех действующих водителей до Эконома.
    ("driverprofile", "car_clean", sa.Boolean(), sa.true()),
    ("driverprofile", "car_body_ok", sa.Boolean(), sa.true()),
    ("driverprofile", "car_classes_available", sa.String(length=64), ""),
    ("driverprofile", "car_classes_enabled", sa.String(length=64), ""),
    ("driverprofile", "car_options", sa.String(length=200), ""),
    ("instantorder", "options", sa.String(length=200), ""),
    ("instantorder", "fallback_categories", sa.String(length=64), ""),
    ("taxiapplication", "osgop_url", sa.String(length=500), None),
    ("taxiapplication", "osgop_until", sa.Date(), None),
)


def _cols(bind, table: str) -> set:
    try:
        return {c["name"] for c in inspect(bind).get_columns(table)}
    except Exception:  # noqa: BLE001 — таблицы может не быть (частичная БД)
        return set()


def _tables(bind) -> set:
    try:
        return set(inspect(bind).get_table_names())
    except Exception:  # noqa: BLE001
        return set()


def upgrade() -> None:
    bind = op.get_bind()
    tables = _tables(bind)
    for table, name, type_, default in _COLUMNS:
        if table not in tables or name in _cols(bind, table):
            continue
        kw = {}
        if default is not None:
            kw["server_default"] = default if not isinstance(default, str) else sa.text(f"'{default}'")
        op.add_column(table, sa.Column(name, type_, nullable=True, **kw))
    # Индекс под фоновую проверку сроков (app/doc_check.py ходит по каждому сроку отдельно).
    if "taxiapplication" in tables and "osgop_until" in _cols(bind, "taxiapplication"):
        try:
            op.create_index("ix_taxiapplication_osgop_until", "taxiapplication", ["osgop_until"])
        except Exception:  # noqa: BLE001 — индекс уже мог приехать из create_all
            pass


def downgrade() -> None:
    bind = op.get_bind()
    tables = _tables(bind)
    try:
        op.drop_index("ix_taxiapplication_osgop_until", table_name="taxiapplication")
    except Exception:  # noqa: BLE001
        pass
    for table, name, _type, _default in reversed(_COLUMNS):
        if table not in tables or name not in _cols(bind, table):
            continue
        try:
            op.drop_column(table, name)
        except Exception:  # noqa: BLE001 — SQLite до 3.35 не умеет DROP COLUMN
            pass
