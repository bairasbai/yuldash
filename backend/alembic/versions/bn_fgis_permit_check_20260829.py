"""Проверка разрешения такси в государственном реестре ФГИС (580-ФЗ, 2026-08-29).

ЗАЧЕМ. Закон обязывает службу заказа передавать заказы ТОЛЬКО тем, кто есть в реестре
легковых такси, и проверять это. До сих пор водитель вписывал номер разрешения руками,
а мы верили на слово. Цена доверия: солидарная ответственность за вред пассажиру, если
заказ ушёл нелегалу, штраф до 50 000 ₽ для ИП и — при систематических нарушениях —
исключение нас самих из реестра служб заказа.

ЧТО ДОБАВЛЯЕТ (в `taxiapplication`):
  `fgis_checked_at`    — когда реестр отвечал последний раз. По нему решаем, пора ли
      перепроверять (`fgis_recheck_hours`) и не устарел ли последний ответ (`fgis_stale_days`).
  `fgis_permit_ok`     — что ответил реестр: разрешение действует или нет.
  `fgis_permit_until`  — срок разрешения ИЗ РЕЕСТРА. Отдельно от `permit_until`, который
      водитель вписал сам: расхождение между ними — сигнал, и терять его нельзя.

ЧЕГО СОЗНАТЕЛЬНО НЕТ. Реестр отдаёт ещё VIN, ИНН и ОГРН перевозчика — мы их не храним.
Искать в ФГИС можно по госномеру, который у нас и так есть, а лишний идентификатор в базе
это то, что придётся защищать и что можно потерять.

ВЫКЛЮЧЕНО ПО УМОЛЧАНИЮ. `fgis_check_enabled=False` и пустой токен: колонки появляются
заранее, проверка включится в день, когда будет оплачен доступ к API.

ИДЕМПОТЕНТНО: на свежей БД колонки приходят из `create_all` → ревизия no-op; на проде
добавляются, существующие заявки не переписываются.

Прод: `alembic upgrade head`.

Revision ID: bn_fgis_permit_check
Revises: bm_merge_heads_20260829
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "bn_fgis_permit_check"
down_revision = "bm_merge_heads_20260829"
branch_labels = None
depends_on = None

_TABLE = "taxiapplication"
# (имя, тип, значение по умолчанию для существующих строк)
_COLUMNS = (
    ("fgis_checked_at", sa.DateTime(), None),
    ("fgis_permit_ok", sa.Boolean(), "false"),
    ("fgis_permit_until", sa.Date(), None),
)


def _columns(bind) -> set:
    insp = inspect(bind)
    if _TABLE not in set(insp.get_table_names()):
        return set()
    return {c["name"] for c in insp.get_columns(_TABLE)}


def upgrade() -> None:
    have = _columns(op.get_bind())
    if not have:
        return
    for name, type_, default in _COLUMNS:
        if name in have:
            continue
        op.add_column(_TABLE, sa.Column(name, type_, nullable=True, server_default=default))
    # Ищем «кого пора перепроверить» по дате последней проверки — без индекса это полный
    # проход по всем заявкам каждые сутки.
    try:
        op.create_index("ix_taxiapplication_fgis_permit_until", _TABLE, ["fgis_permit_until"])
    except Exception:  # noqa: BLE001 — индекс мог остаться от прошлого прогона
        pass


def downgrade() -> None:
    try:
        op.drop_index("ix_taxiapplication_fgis_permit_until", table_name=_TABLE)
    except Exception:  # noqa: BLE001 — индекса может не быть, откат не должен падать
        pass
    for name, _type, _default in reversed(_COLUMNS):
        try:
            op.drop_column(_TABLE, name)
        except Exception:  # noqa: BLE001 — колонки может не быть, откат не должен падать
            pass
