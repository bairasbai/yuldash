"""Кто и на чём везёт: ФИО, госномер и согласие с правилами в заявке курьера.

Раньше «стать курьером Юлдаша» = селфи + выбор «легковой/грузовой». Ни ФИО, ни номера машины,
ни галочки согласия с правилами доставки (аудит 2026-07-26). Человеку доверяли чужую посылку,
зная о нём меньше, чем о попутчике: у таксиста собирались ИНН, разрешение, ОСАГО и стаж,
а у курьера — одно фото.

Аддитивно, ИДЕМПОТЕНТНО: свежая БД (create_all) → no-op; прод → add_column.

Прод: `alembic upgrade head`.

Revision ID: q_courier_identity
Revises: p_taxi_docs_pretrip
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "q_courier_identity"
down_revision = "p_taxi_docs_pretrip"
branch_labels = None
depends_on = None

_COLUMNS = [
    ("courierapplication", "full_name", sa.String(length=120), {"nullable": False, "server_default": ""}),
    ("courierapplication", "car_plate", sa.String(length=16), {"nullable": False, "server_default": ""}),
    ("courierapplication", "rules_accepted", sa.Boolean(), {"nullable": False, "server_default": sa.false()}),
    ("courierapplication", "rules_accepted_at", sa.DateTime(), {"nullable": True}),
]


def _columns(bind, table: str) -> set:
    try:
        return {c["name"] for c in inspect(bind).get_columns(table)}
    except Exception:  # noqa: BLE001
        return set()


def upgrade() -> None:
    bind = op.get_bind()
    existing = _columns(bind, "courierapplication")
    for table, name, type_, kwargs in _COLUMNS:
        if name not in existing:
            op.add_column(table, sa.Column(name, type_, **kwargs))


def downgrade() -> None:
    bind = op.get_bind()
    existing = _columns(bind, "courierapplication")
    names = [name for _t, name, _ty, _k in _COLUMNS if name in existing]
    if names:
        with op.batch_alter_table("courierapplication") as b:
            for name in names:
                b.drop_column(name)
