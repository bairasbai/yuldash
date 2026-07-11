"""Волна 2, батч B8 «Анти-фрод»: защита от мошенников с обеих сторон.

Аддитивно (данные не трогаем):
- таблица deviceban — баны устройств (обход бана новым номером, B8-1);
- user.last_device_id — последнее устройство входа (X-Device-Id), сигнал нового устройства.

ИДЕМПОТЕНТНО (паттерн w2_livelink), оба пути:
- свежая БД: 0001_baseline (create_all из моделей) уже создал всё → ревизия no-op;
- прод (Postgres): добавляем недостающие таблицы/колонки/индексы.

Прод: `alembic upgrade head`.

Revision ID: w2_antifraud
Revises: w2_livelink
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "w2_antifraud"
down_revision = "w2_livelink"
branch_labels = None
depends_on = None


def _tables(bind) -> set:
    try:
        return set(inspect(bind).get_table_names())
    except Exception:  # noqa: BLE001
        return set()


def _columns(bind, table: str) -> set:
    try:
        return {c["name"] for c in inspect(bind).get_columns(table)}
    except Exception:  # noqa: BLE001
        return set()


def _indexes(bind, table: str) -> set:
    try:
        return {i["name"] for i in inspect(bind).get_indexes(table)}
    except Exception:  # noqa: BLE001
        return set()


def _add_col(bind, table: str, col: sa.Column) -> None:
    if col.name not in _columns(bind, table):
        op.add_column(table, col)


def _drop_col(bind, table: str, name: str) -> None:
    if name in _columns(bind, table):
        with op.batch_alter_table(table) as b:
            b.drop_column(name)


def upgrade() -> None:
    bind = op.get_bind()

    # --- B8-1: баны устройств ---
    if "deviceban" not in _tables(bind):
        op.create_table(
            "deviceban",
            sa.Column("id", sa.Integer(), primary_key=True),
            sa.Column("device_id", sa.String(length=64), nullable=False),
            sa.Column("user_id", sa.Integer(), sa.ForeignKey("user.id"), nullable=True),
            sa.Column("reason", sa.String(), nullable=False, server_default=""),
            sa.Column("created_at", sa.DateTime(), nullable=False),
        )
    if "ix_deviceban_device_id" not in _indexes(bind, "deviceban"):
        op.create_index("ix_deviceban_device_id", "deviceban", ["device_id"], unique=True)

    # --- B8-1/2: последнее устройство входа ---
    _add_col(bind, "user", sa.Column("last_device_id", sa.String(), nullable=True))
    if "ix_user_last_device_id" not in _indexes(bind, "user"):
        op.create_index("ix_user_last_device_id", "user", ["last_device_id"])

    # --- B8-6/9: чат — флаг анти-фишинга + бейдж официальности ---
    _add_col(bind, "message", sa.Column("flag", sa.String(), nullable=False, server_default=""))
    _add_col(bind, "message", sa.Column("from_admin", sa.Boolean(), nullable=False,
                                        server_default=sa.false()))

    # --- B8-4: выданные водительские реферальные бонусы (анти-накрутка) ---
    if "referralbonus" not in _tables(bind):
        op.create_table(
            "referralbonus",
            sa.Column("id", sa.Integer(), primary_key=True),
            sa.Column("referrer_id", sa.Integer(), sa.ForeignKey("user.id"), nullable=False),
            sa.Column("invited_user_id", sa.Integer(), sa.ForeignKey("user.id"), nullable=False),
            sa.Column("kind", sa.String(), nullable=False, server_default="driver"),
            sa.Column("created_at", sa.DateTime(), nullable=False),
        )
    for ix, cols, uniq in (
        ("ix_referralbonus_referrer_id", ["referrer_id"], False),
        ("ix_referralbonus_invited_user_id", ["invited_user_id"], True),
        ("ix_referralbonus_created_at", ["created_at"], False),
    ):
        if ix not in _indexes(bind, "referralbonus"):
            op.create_index(ix, "referralbonus", cols, unique=uniq)


def downgrade() -> None:
    bind = op.get_bind()
    _drop_col(bind, "message", "from_admin")
    _drop_col(bind, "message", "flag")
    if "referralbonus" in _tables(bind):
        op.drop_table("referralbonus")
    if "ix_user_last_device_id" in _indexes(bind, "user"):
        op.drop_index("ix_user_last_device_id", table_name="user")
    _drop_col(bind, "user", "last_device_id")
    if "deviceban" in _tables(bind):
        op.drop_table("deviceban")
