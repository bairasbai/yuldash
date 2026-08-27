"""Честный чек: цена поездки без наценки + жалоба на цену (2026-08-23).

ЗАЧЕМ.

1. `instantorder.ride_base_price` — цена поездки БЕЗ наценки, ₽. Нужна ровно для одной
   строки чека: «наценка за спрос +40 ₽». Восстановить её задним числом нельзя: цена
   округляется до 10 ₽, и делением на коэффициент обратно не получается. Без этой колонки
   честного чека не бывает — есть только итог, в который просят поверить.

2. Таблица `pricecomplaint` — «что-то не так с ценой». Это НЕ жалоба на человека: второй
   стороны здесь нет, человек спорит с нашим расчётом. Сводить это в разбор с водителем
   значит обвинять того, кто ни при чём.

   Принимается и ДО заказа: обычно жалоба рождается именно там — человек увидел 450 ₽ и
   закрыл приложение. Поэтому order_id необязательный.

ПРИВАТНОСТЬ. В жалобе НЕТ координат. Только числа расчёта (то же, что человек видел на
экране) и выбранная причина: адрес, откуда он собирался ехать, для разбора цены не нужен.

ИДЕМПОТЕНТНО: колонка и таблица проверяются перед созданием. На свежей БД их уже создал
create_all из моделей — тогда ревизия ничего не делает.
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "bg_honest_receipt"
down_revision = "bf_options_fee"
branch_labels = None
depends_on = None

_ORDER_TABLE = "instantorder"
_ORDER_COLUMN = "ride_base_price"
_COMPLAINT_TABLE = "pricecomplaint"


def _tables(bind) -> set:
    return set(inspect(bind).get_table_names())


def _columns(bind, table: str) -> set:
    insp = inspect(bind)
    if table not in set(insp.get_table_names()):
        return set()
    return {c["name"] for c in insp.get_columns(table)}


def upgrade() -> None:
    bind = op.get_bind()

    have = _columns(bind, _ORDER_TABLE)
    if have and _ORDER_COLUMN not in have:
        op.add_column(_ORDER_TABLE, sa.Column(_ORDER_COLUMN, sa.Integer(), nullable=False,
                                              server_default="0"))
        # Старым заказам ставим цену поездки: наценки в них либо не было, либо её уже
        # не восстановить. Ноль означал бы «вся цена — наценка», а это неправда.
        cols = _columns(bind, _ORDER_TABLE)
        if "ride_price" in cols:
            bind.execute(sa.text(
                "UPDATE instantorder SET ride_base_price = ride_price "
                "WHERE ride_base_price = 0 AND ride_price > 0"
            ))

    if _COMPLAINT_TABLE not in _tables(bind):
        op.create_table(
            _COMPLAINT_TABLE,
            sa.Column("id", sa.Integer(), primary_key=True),
            sa.Column("user_id", sa.Integer(), sa.ForeignKey("user.id"), nullable=False),
            sa.Column("order_id", sa.Integer(), sa.ForeignKey("instantorder.id"), nullable=True),
            sa.Column("price", sa.Integer(), nullable=False, server_default="0"),
            sa.Column("reason", sa.String(length=32), nullable=False, server_default="other"),
            sa.Column("comment", sa.String(length=500), nullable=False, server_default=""),
            sa.Column("breakdown_json", sa.Text(), nullable=False, server_default=""),
            sa.Column("created_at", sa.DateTime(), nullable=False,
                      server_default=sa.func.now()),
            sa.Column("handled_at", sa.DateTime(), nullable=True),
        )
        op.create_index("ix_pricecomplaint_user_id", _COMPLAINT_TABLE, ["user_id"])
        op.create_index("ix_pricecomplaint_order_id", _COMPLAINT_TABLE, ["order_id"])
        op.create_index("ix_pricecomplaint_created_at", _COMPLAINT_TABLE, ["created_at"])


def downgrade() -> None:
    try:
        op.drop_table(_COMPLAINT_TABLE)
    except Exception:  # noqa: BLE001 — таблицы может не быть; откат не должен падать
        pass
    try:
        op.drop_column(_ORDER_TABLE, _ORDER_COLUMN)
    except Exception:  # noqa: BLE001
        pass
