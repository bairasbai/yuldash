"""Брошенный водителем заказ возвращается в поиск, а не умирает (2026-08-29).

ЗАЧЕМ. Водитель принимал заказ и отменял — заказ уходил в `cancelled` навсегда. Пассажиру
при этом уходил бодрый пуш «Водитель отменил заказ. Ищем другого?», а экран приложения
отвечал honestly-но-иначе: «Попробуй заказать снова». Никто ничего не искал. Женщина с
ребёнком у подъезда в мороз вбивала адреса заново, теряя и зафиксированную цену, и очередь.

Теперь заказ сам возвращается в поиск: те же адреса, тот же класс, та же цена, тот же
промокод. Бросивший водитель попадает в список «уже предлагали», чтобы круг подбора не
вернул ему заказ через минуту.

ЧТО ДОБАВЛЯЕТ:
  `instantorder.reassigns` — сколько раз заказ возвращали в поиск. Предохранитель
      (`taxi_reassign_limit`, по умолчанию 2): заказ не должен скакать по кругу вечно.

  таблица `drivercancel` — ФАКТ «водитель бросил принятый заказ», отдельным событием.
      Без неё починка ломала наказание за брошенные заказы: пауза офферов читалась прямо
      с заказа (`status == cancelled AND cancel_by == 'driver'`), а у переназначенного
      заказа `driver_id` и `cancelled_at` перезаписываются новым водителем — след первого
      исчезал бы, и бросать заказы можно было бы бесконечно. Событие описывает поступок
      человека, а не судьбу заказа, и переживает и переназначение, и завершение поездки.

СОВМЕСТИМОСТЬ: старые отмены (до этой ревизии) остаются на заказах и по-прежнему читаются
как страйки — иначе у живых водителей история обнулилась бы в день выката.

ИДЕМПОТЕНТНО: на свежей БД таблица и колонка приходят из `create_all` → ревизия no-op;
на проде добавляются, существующие заказы не переписываются.

Прод: `alembic upgrade head`.

Revision ID: bl_brosennyi_zakaz_ishchet_dalshe
Revises: bk_second_try_before_return
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "bl_brosennyi_zakaz_ishchet_dalshe"
down_revision = "bk_second_try_before_return"
branch_labels = None
depends_on = None

_ORDERS = "instantorder"
_EVENTS = "drivercancel"


def _tables(bind) -> set:
    return set(inspect(bind).get_table_names())


def _columns(bind, table: str) -> set:
    insp = inspect(bind)
    if table not in set(insp.get_table_names()):
        return set()
    return {c["name"] for c in insp.get_columns(table)}


def upgrade() -> None:
    bind = op.get_bind()

    have = _columns(bind, _ORDERS)
    if have and "reassigns" not in have:
        op.add_column(_ORDERS, sa.Column("reassigns", sa.Integer(), nullable=True,
                                         server_default="0"))

    if _EVENTS not in _tables(bind):
        op.create_table(
            _EVENTS,
            sa.Column("id", sa.Integer(), primary_key=True),
            sa.Column("driver_id", sa.Integer(), sa.ForeignKey("user.id"), nullable=False),
            sa.Column("order_id", sa.Integer(), sa.ForeignKey("instantorder.id"), nullable=False),
            sa.Column("at", sa.DateTime(), nullable=False),
            sa.Column("no_show", sa.Boolean(), nullable=False, server_default=sa.false()),
            sa.Column("reason", sa.String(length=200), nullable=False, server_default=""),
        )
        # Читаем всегда одинаково: «отмены этого водителя за последние N дней».
        op.create_index("ix_drivercancel_driver_id", _EVENTS, ["driver_id"])
        op.create_index("ix_drivercancel_order_id", _EVENTS, ["order_id"])
        op.create_index("ix_drivercancel_at", _EVENTS, ["at"])


def downgrade() -> None:
    bind = op.get_bind()
    if _EVENTS in _tables(bind):
        for name in ("ix_drivercancel_at", "ix_drivercancel_order_id", "ix_drivercancel_driver_id"):
            try:
                op.drop_index(name, table_name=_EVENTS)
            except Exception:  # noqa: BLE001 — индекса может не быть, откат не должен падать
                pass
        op.drop_table(_EVENTS)
    try:
        op.drop_column(_ORDERS, "reassigns")
    except Exception:  # noqa: BLE001 — колонки может не быть, откат не должен падать
        pass
