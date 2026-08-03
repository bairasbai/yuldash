"""У посылки появляется точка «куда именно», а не только город.

parceldelivery.from_address / to_address — свободный текст ≤200 (дом, квартира, ориентир).
Раньше в заявке были только from_city/to_city: курьер брал заказ и ехал «в Баймак» — ни дома,
ни квартиры, ни ориентира. У заказа ТАКСИ это давно решено полями comment/entrance; здесь та же
идиома для посылок. Свободный текст, а не улица/дом/квартира: в селе адрес чаще ориентир
(«у мечети», «синие ворота»), чем табличка с номером.

Приватность (CLAUDE.md §8): колонки хранят персональные данные, поэтому в открытом списке
заявок их нет вообще — отдаём принявшему курьеру, отправителю и админу, а получателю по
трекинг-ссылке только его собственный to_address (см. routers/parcels._addresses).

Аддитивно и ИДЕМПОТЕНТНО (паттерн s_audit_20260803 / courier_c1):
- свежая БД: 0001_baseline (create_all из моделей) уже создал колонки → ревизия no-op;
- прод: add_column с server_default="" (у старых заявок адреса просто пустые).

Прод: `alembic upgrade head`.

Revision ID: t_parcel_address
Revises: s_audit_20260803
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "t_parcel_address"
down_revision = "s_audit_20260803"
branch_labels = None
depends_on = None

_TABLE = "parceldelivery"
# NOT NULL со server_default="": в модели это обычный str с default="" — старые строки не ломаем.
_COLUMNS = [
    ("from_address", sa.String(length=200)),   # где забрать (дом, квартира, ориентир)
    ("to_address", sa.String(length=200)),     # где вручить
]


def _columns(bind, table: str) -> set:
    try:
        return {c["name"] for c in inspect(bind).get_columns(table)}
    except Exception:  # noqa: BLE001 — таблицы ещё нет (свежая БД до create_all)
        return set()


def upgrade() -> None:
    bind = op.get_bind()
    existing = _columns(bind, _TABLE)
    if not existing:   # таблицы нет → её создаст baseline/create_all уже с этими колонками
        return
    for name, type_ in _COLUMNS:
        if name not in existing:
            op.add_column(_TABLE, sa.Column(name, type_, nullable=False, server_default=""))


def downgrade() -> None:
    bind = op.get_bind()
    existing = _columns(bind, _TABLE)
    for name, _type in _COLUMNS:
        if name in existing:
            with op.batch_alter_table(_TABLE) as b:
                b.drop_column(name)
