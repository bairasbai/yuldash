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
    for name, cols in (("ix_carphotocheck_kind", ["kind"]),
                       ("ix_carphotocheck_report_id", ["report_id"])):
        try:
            op.create_index(name, "carphotocheck", cols)
        except Exception:  # noqa: BLE001 — индекс мог остаться от прошлого прогона
            pass


def downgrade() -> None:
    for name in ("ix_carphotocheck_report_id", "ix_carphotocheck_kind"):
        try:
            op.drop_index(name, table_name="carphotocheck")
        except Exception:  # noqa: BLE001 — индекса может не быть, откат не должен падать
            pass
    for table, name, _type, _default in reversed(_COLUMNS):
        try:
            op.drop_column(table, name)
        except Exception:  # noqa: BLE001 — колонки может не быть, откат не должен падать
            pass
