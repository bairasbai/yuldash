"""Фотоконтроль машины: раз в две недели человек показывает, на чём он возит (580-ФЗ, 2026-08-30).

ЗАЧЕМ. Машину мы видели ОДИН раз — на фото при регистрации. Дальше о ней известно ровно то,
что человек сказал сам: перед выездом он ставит галочку «машина исправна». Закон требует от
службы заказа контроля состояния транспорта, а галочка ничего не показывает — разбитый
бампер и салон, куда стыдно посадить ребёнка, выглядят в базе как новая машина.

ЧТО ДОБАВЛЯЕТ. Одну таблицу `carphotocheck` — по строке на каждый контроль:
  `mode`        такси или курьер: у такси спрашиваем салон, у курьера — багажник;
  `seq`         номер контроля. Первые два идут через три дня, дальше раз в две недели;
  `status`      waiting (ждём кадры) → review (смотрит человек) → passed | failed;
  `due_at`      до какого момента прислать. По нему считается лестница просрочки:
                1–3 дня напоминание, 4–7 приоритет вниз, дальше пауза до фото;
  `photos_json` ссылки на кадры, `checks_json` — что сказал автомат, `hashes_json` —
                отпечатки кадров: по ним видно, что прислали ту же фотографию второй раз;
  `manual`      контроль смотрит человек (новичок, жалоба, выборка), а не только автомат;
  `winter`      зимний контроль: чистоту кузова не требуем, целостность и салон — да.

САМИ ФАЙЛЫ ЖИВУТ 90 ДНЕЙ (`cleanup._clean_carphoto`), строка остаётся навсегда: факт
«контроль пройден такого-то числа» — наш след для проверки, а снимок чужой машины у
подъезда хранить дольше незачем (152-ФЗ, ст. 5 п. 7).

ВЫКЛЮЧЕНО ПО УМОЛЧАНИЮ. `car_photo_taxi_enabled` и `car_photo_courier_enabled` — раздельные
флаги, оба False: таблица появляется заранее, контроль включается решением Александра.

ИДЕМПОТЕНТНО: на свежей БД таблица приходит из `create_all` → ревизия no-op; на проде
создаётся. Существующие водители при включении получают неделю на первый контроль и
работают без ограничений (см. `carphoto.ensure`).

Прод: `alembic upgrade head`.

Revision ID: bo_car_photo_check
Revises: bn_fgis_permit_check
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "bo_car_photo_check"
down_revision = "bn_fgis_permit_check"
branch_labels = None
depends_on = None

_TABLE = "carphotocheck"


def _exists(bind) -> bool:
    return _TABLE in set(inspect(bind).get_table_names())


def upgrade() -> None:
    bind = op.get_bind()
    if _exists(bind):
        return
    op.create_table(
        _TABLE,
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("user_id", sa.Integer(), nullable=False),
        sa.Column("mode", sa.String(length=8), nullable=False, server_default="taxi"),
        sa.Column("seq", sa.Integer(), nullable=False, server_default="1"),
        sa.Column("status", sa.String(length=8), nullable=False, server_default="waiting"),
        sa.Column("due_at", sa.DateTime(), nullable=False),
        sa.Column("submitted_at", sa.DateTime(), nullable=True),
        sa.Column("reviewed_at", sa.DateTime(), nullable=True),
        sa.Column("reviewed_by", sa.Integer(), nullable=True),
        sa.Column("photos_json", sa.Text(), nullable=False, server_default=""),
        sa.Column("checks_json", sa.Text(), nullable=False, server_default=""),
        sa.Column("hashes_json", sa.Text(), nullable=False, server_default=""),
        sa.Column("manual", sa.Boolean(), nullable=False, server_default="false"),
        sa.Column("manual_reason", sa.String(length=16), nullable=False, server_default=""),
        sa.Column("reject_reason", sa.String(length=200), nullable=False, server_default=""),
        sa.Column("reminded_at", sa.DateTime(), nullable=True),
        sa.Column("winter", sa.Boolean(), nullable=False, server_default="false"),
        sa.Column("created_at", sa.DateTime(), nullable=False),
    )
    # «Мой открытый контроль» — запрос каждого экрана водителя.
    op.create_index(f"ix_{_TABLE}_user_id", _TABLE, ["user_id"])
    op.create_index(f"ix_{_TABLE}_mode", _TABLE, ["mode"])
    op.create_index(f"ix_{_TABLE}_status", _TABLE, ["status"])
    op.create_index(f"ix_{_TABLE}_due_at", _TABLE, ["due_at"])
    # «Кому пора прислать фото» — ночной обход по состоянию и сроку разом.
    op.create_index("ix_carphotocheck_status_due", _TABLE, ["status", "due_at"])


def downgrade() -> None:
    bind = op.get_bind()
    if not _exists(bind):
        return
    for name in ("ix_carphotocheck_status_due", f"ix_{_TABLE}_due_at", f"ix_{_TABLE}_status",
                 f"ix_{_TABLE}_mode", f"ix_{_TABLE}_user_id"):
        try:
            op.drop_index(name, table_name=_TABLE)
        except Exception:  # noqa: BLE001 — индекса может не быть, откат не должен падать
            pass
    op.drop_table(_TABLE)
