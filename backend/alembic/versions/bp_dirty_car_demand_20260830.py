"""Жалоба «грязная машина» разбирается фотографией + светлый салон в Бизнес (2026-08-30).

ЗАЧЕМ. Жалоба на грязь — слово против слова: пассажиру могло показаться, водитель мог
только что кого-то довезти. Разбирать это без фото невозможно, а наказывать по одному
сообщению значит дать любому кнопку «испортить соседу неделю». Теперь жалоба открывает
требование: пришли фото салона за сутки. Прислал чистое — вопрос закрыт без последствий;
не прислал или подтвердилась грязь — обычная лестница качества.

ЧТО ДОБАВЛЯЕТ:
  `carphotocheck.kind`       — плановый обход (`periodic`) или требование по жалобе
      (`complaint`). Разделены намеренно: плановый двигает лестницу и может поставить
      паузу линии, требование по жалобе работу НЕ ограничивает вообще.
  `carphotocheck.report_id`  — какая жалоба его открыла. По нему решение по фото закрывает
      саму жалобу: чисто → отклонена, грязно → подтверждена.
  `driverprofile.car_light_salon` — светлый салон, РАВНОЦЕННАЯ коже дорога в Бизнес.
      Кожа в райцентре редкость, а светлый ухоженный салон читается пассажиром как «дорого»
      ничуть не хуже; требовать именно кожу значило бы закрыть Бизнес почти всем, кто его
      заслужил.

ИДЕМПОТЕНТНО: на свежей БД колонки приходят из `create_all` → ревизия no-op; на проде
добавляются со значениями по умолчанию, существующие строки не переписываются (все
прежние контроли — плановые, светлого салона ни у кого не заявлено).

Прод: `alembic upgrade head`.

Revision ID: bp_dirty_car_demand
Revises: bo_car_photo_check
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "bp_dirty_car_demand"
down_revision = "bo_car_photo_check"
branch_labels = None
depends_on = None

# (таблица, колонка, тип, значение для существующих строк)
_COLUMNS = (
    ("carphotocheck", "kind", sa.String(length=10), "periodic"),
    ("carphotocheck", "report_id", sa.Integer(), None),
    ("driverprofile", "car_light_salon", sa.Boolean(), "false"),
)


def _columns(bind, table: str) -> set:
    insp = inspect(bind)
    if table not in set(insp.get_table_names()):
        return set()
    return {c["name"] for c in insp.get_columns(table)}


def upgrade() -> None:
    bind = op.get_bind()
    for table, name, type_, default in _COLUMNS:
        have = _columns(bind, table)
        if not have or name in have:
            continue
        op.add_column(table, sa.Column(name, type_, nullable=True, server_default=default))
    # «Открытое требование по жалобе у этого человека» и «чем кончилась жалоба» — оба
    # запроса идут по этим колонкам, и без индексов это полный проход по всем контролям.
    indexes = ({index["name"] for index in inspect(bind).get_indexes("carphotocheck")}
               if _columns(bind, "carphotocheck") else None)
    for name, cols in (("ix_carphotocheck_kind", ["kind"]),
                       ("ix_carphotocheck_report_id", ["report_id"])):
        if indexes is not None and name not in indexes:
            op.create_index(name, "carphotocheck", cols)


def downgrade() -> None:
    bind = op.get_bind()
    indexes = ({index["name"] for index in inspect(bind).get_indexes("carphotocheck")}
               if _columns(bind, "carphotocheck") else set())
    for name in ("ix_carphotocheck_report_id", "ix_carphotocheck_kind"):
        if name in indexes:
            op.drop_index(name, table_name="carphotocheck")
    for table, name, _type, _default in reversed(_COLUMNS):
        if name in _columns(bind, table):
            if bind.dialect.name == "sqlite":
                convention = {"fk": "fk_%(table_name)s_%(column_0_name)s_%(referred_table_name)s"}
                with op.batch_alter_table(table, naming_convention=convention) as batch:
                    for fk in inspect(bind).get_foreign_keys(table):
                        if name in fk["constrained_columns"]:
                            fk_name = fk["name"] or f"fk_{table}_{name}_{fk['referred_table']}"
                            batch.drop_constraint(fk_name, type_="foreignkey")
                    batch.drop_column(name)
            else:
                op.drop_column(table, name)
