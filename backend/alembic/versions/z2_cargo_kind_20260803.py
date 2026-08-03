"""Что именно везём: вес, тип груза, «хрупкое» (разбор №2, 2026-08-03).

Было только «размер» — small/medium/large. Он отвечает на вопрос «влезет ли», но курьер решает
по трём другим:
  * унесу ли — коробка 40×40 может весить 3 кг (подушка) и 40 кг (картошка);
  * возьмусь ли — лекарство, документы и рассада ехали одной строкой свободного описания;
  * как положить — «хрупкое» не было отмечено нигде, и банка мёда честно ехала рядом с домкратом,
    после чего в споре обе стороны оказывались правы.

Все три поля — УСЛОВИЯ заказа, а не персональные данные: курьер видит их ДО принятия, в том
числе в открытом списке (в отличие от телефона и адреса, которые открываются только принявшему).

Потолок веса 100 кг задан в схеме: выше — это уже не «посылка между своими», а грузоперевозка
с другим транспортом и другой ответственностью.

Аддитивно и идемпотентно: свежая БД получает колонки из `create_all` → ревизия no-op; на проде
add_column со server_default, у старых заявок вес 0 («не указан»), тип пустой, «хрупкое» = нет.

Прод: `alembic upgrade head`.

Revision ID: z2_cargo_kind
Revises: z_decline_reason
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "z2_cargo_kind"
down_revision = "z_decline_reason"
branch_labels = None
depends_on = None

_TABLE = "parceldelivery"
_COLUMNS = (
    ("weight_kg", lambda: sa.Column("weight_kg", sa.Float(), nullable=False, server_default="0")),
    ("cargo_type", lambda: sa.Column("cargo_type", sa.String(length=16), nullable=False, server_default="")),
    ("fragile", lambda: sa.Column("fragile", sa.Boolean(), nullable=False, server_default=sa.false())),
)


def _columns(bind) -> set:
    try:
        return {c["name"] for c in inspect(bind).get_columns(_TABLE)}
    except Exception:  # noqa: BLE001 — таблицы ещё нет (свежая БД до create_all)
        return set()


def upgrade() -> None:
    have = _columns(op.get_bind())
    if not have:
        return
    for name, make in _COLUMNS:
        if name not in have:
            op.add_column(_TABLE, make())


def downgrade() -> None:
    have = _columns(op.get_bind())
    drop = [name for name, _ in _COLUMNS if name in have]
    if drop:
        with op.batch_alter_table(_TABLE) as batch:
            for name in drop:
                batch.drop_column(name)
