"""Чат по посылке: отправитель ↔ назначенный курьер.

message.parcel_id (NULL, INTEGER, index, FK parceldelivery.id) — третья привязка сообщения
(раньше были только booking_id — чат брони попутки — и order_id — чат такси-заказа).

Зачем: до этого по доставке можно было только ПОЗВОНИТЬ. Половина вопросов — одна фраза
(«оставь у соседей», «я на работе до шести», «звони, домофон не работает»): звонок для этого
тяжёлый и не оставляет следа, если потом спор. Чат — зеркало чата такси-заказа, ничего своего.

Новая связь вплетена в каскад удаления аккаунта (app/account.py, шаг 3.1 — иначе `/me/delete`
падал бы по внешнему ключу на Postgres) и в ретеншен-чистку (app/cleanup.py — NOT EXISTS-гард
на message.parcel_id перед удалением старой доставки).

Аддитивно и ИДЕМПОТЕНТНО (паттерн w2_polish2 — там так же появился message.order_id):
- свежая БД: 0001_baseline (create_all из моделей) уже создал колонку → ревизия no-op;
- прод (Postgres): добавляем недостающую колонку + индекс, честный FOREIGN KEY.
SQLite не умеет ALTER ADD с констрейнтом → там колонка без FK (как в w2_polish2/w2_quality).

Прод: `alembic upgrade head`.

Revision ID: u_parcel_chat
Revises: t_parcel_address
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "u_parcel_chat"
down_revision = "t_parcel_address"
branch_labels = None
depends_on = None

_TABLE = "message"
_COL = "parcel_id"
_IX = "ix_message_parcel_id"


def _columns(bind, table: str) -> set:
    try:
        return {c["name"] for c in inspect(bind).get_columns(table)}
    except Exception:  # noqa: BLE001 — таблицы ещё нет (свежая БД до create_all)
        return set()


def _indexes(bind, table: str) -> set:
    try:
        return {i["name"] for i in inspect(bind).get_indexes(table)}
    except Exception:  # noqa: BLE001
        return set()


def _fk_int_column(bind) -> sa.Column:
    """FK-колонка. SQLite не умеет ALTER ADD с констрейнтом → без FK (паттерн w2_polish2);
    прод-Postgres получает честный FOREIGN KEY."""
    if bind.dialect.name == "sqlite":
        return sa.Column(_COL, sa.Integer(), nullable=True)
    return sa.Column(_COL, sa.Integer(), sa.ForeignKey("parceldelivery.id"), nullable=True)


def upgrade() -> None:
    bind = op.get_bind()
    existing = _columns(bind, _TABLE)
    if not existing:   # таблицы нет → её создаст baseline/create_all уже с этой колонкой
        return
    if _COL not in existing:
        op.add_column(_TABLE, _fk_int_column(bind))
    if _IX not in _indexes(bind, _TABLE):
        op.create_index(_IX, _TABLE, [_COL], unique=False)


def downgrade() -> None:
    bind = op.get_bind()
    if _IX in _indexes(bind, _TABLE):
        op.drop_index(_IX, table_name=_TABLE)
    # На SQLite колонка с FK дропается только через batch (пересборка таблицы) — как в courier_c3.
    if _COL in _columns(bind, _TABLE):
        with op.batch_alter_table(_TABLE) as b:
            b.drop_column(_COL)
