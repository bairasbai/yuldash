"""Волна 2, батч B7b «Такси-полировка: связь и контроль».

Аддитивные колонки (данные не трогаем):
- message.order_id (NULL, FK instantorder) — чат такси-заказа; message.booking_id → nullable
  (ровно одна привязка: бронь ИЛИ заказ; старые строки — все с booking_id).
- tripshare.order_id (NULL, FK instantorder) — «поделиться поездкой» из такси;
  tripshare.booking_id → nullable (та же логика «одна привязка»).
- sosevent.order_id (NULL, FK instantorder) — SOS с контекстом такси-заказа.
- driverprofile.receipt_reminder_at (NULL) — дедуп напоминания о чеке «Мой налог» (1/сутки).

ИДЕМПОТЕНТНО (как p2/p3/w2_*), оба пути:
- свежая БД: 0001_baseline (create_all из моделей) уже создал всё → ревизия no-op;
- прод (Postgres): добавляем недостающие колонки/индексы, снимаем NOT NULL.

Попутка/такси не затрагиваются, ничего не удаляем. Прод: `alembic upgrade head`.

Revision ID: w2_polish2
Revises: w2_waitlist
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "w2_polish2"
down_revision = "w2_waitlist"
branch_labels = None
depends_on = None


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


def _booking_id_not_null(bind, table: str) -> bool:
    try:
        for c in inspect(bind).get_columns(table):
            if c["name"] == "booking_id":
                return not c.get("nullable", True)
    except Exception:  # noqa: BLE001
        pass
    return False


_ADD = (
    # (таблица, колонка, индекс|None)
    ("message", "order_id", "ix_message_order_id"),
    ("tripshare", "order_id", "ix_tripshare_order_id"),
    ("sosevent", "order_id", None),
)


def _fk_int_column(bind, name: str, ref: str) -> sa.Column:
    """FK-колонка. SQLite не умеет ALTER ADD с констрейнтом → без FK (паттерн w2_quality);
    прод-Postgres получает честный FOREIGN KEY."""
    if bind.dialect.name == "sqlite":
        return sa.Column(name, sa.Integer(), nullable=True)
    return sa.Column(name, sa.Integer(), sa.ForeignKey(ref), nullable=True)


def upgrade() -> None:
    bind = op.get_bind()
    for table, col, ix in _ADD:
        if col not in _columns(bind, table):
            op.add_column(table, _fk_int_column(bind, col, "instantorder.id"))
        if ix and ix not in _indexes(bind, table):
            op.create_index(ix, table, [col])
    if "receipt_reminder_at" not in _columns(bind, "driverprofile"):
        op.add_column("driverprofile", sa.Column("receipt_reminder_at", sa.DateTime(), nullable=True))
    # booking_id → nullable (сообщение/шаринг такси-заказа живёт без брони).
    # batch_alter_table корректен и на Postgres (обычный ALTER), и на SQLite (пересборка таблицы).
    for table in ("message", "tripshare"):
        if _booking_id_not_null(bind, table):
            with op.batch_alter_table(table) as b:
                b.alter_column("booking_id", existing_type=sa.Integer(), nullable=True)


def downgrade() -> None:
    bind = op.get_bind()
    # Возврат NOT NULL: строки без брони (чаты/шаринги заказов) чистим — иначе ALTER упадёт.
    for table in ("message", "tripshare"):
        if "order_id" in _columns(bind, table):
            op.execute(sa.text(f"DELETE FROM {table} WHERE booking_id IS NULL"))  # noqa: S608 — имена из белого списка
        if not _booking_id_not_null(bind, table):
            with op.batch_alter_table(table) as b:
                b.alter_column("booking_id", existing_type=sa.Integer(), nullable=False)
    for table, col, ix in _ADD:
        if ix and ix in _indexes(bind, table):
            op.drop_index(ix, table_name=table)
        if col in _columns(bind, table):
            with op.batch_alter_table(table) as b:
                b.drop_column(col)
    if "receipt_reminder_at" in _columns(bind, "driverprofile"):
        with op.batch_alter_table("driverprofile") as b:
            b.drop_column("receipt_reminder_at")
