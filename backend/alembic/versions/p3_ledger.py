"""Фаза 3 «Деньги v1»: ledger (кошелёк водителя) + колонки оплаты поездки.

ИДЕМПОТЕНТНО (как p2), оба пути:
- свежая БД: `alembic upgrade head` идёт через 0001_baseline (create_all из текущих моделей)
  → таблица ledgerentry и новые колонки уже созданы → эта ревизия видит их и делает no-op;
- прод (таблицы уже есть без новых колонок): создаём ledgerentry с индексами и добавляем
  недостающие колонки в payment / instantorder / booking.

Добавляет:
- таблицу ledgerentry (append-only кошелёк: earn|fee|payout|adj, amount_kop, order/booking);
- payment: order_id, booking_id, method + индекс на created_at (для сверки за период);
- instantorder: paid, payment_method;
- booking: paid, payment_method.

НЕ создаёт Notification и ничего не удаляет. Деньги — только целые копейки (int).

Прод: `alembic upgrade head`.

Revision ID: p3_ledger
Revises: p2_instant_order
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "p3_ledger"
down_revision = "p2_instant_order"
branch_labels = None
depends_on = None


def _tables(bind) -> set:
    return set(inspect(bind).get_table_names())


def _columns(bind, table: str) -> set:
    try:
        return {c["name"] for c in inspect(bind).get_columns(table)}
    except Exception:  # noqa: BLE001 — таблицы может не быть
        return set()


def _indexes(bind, table: str) -> set:
    try:
        return {ix["name"] for ix in inspect(bind).get_indexes(table)}
    except Exception:  # noqa: BLE001
        return set()


def _add_col(bind, table: str, column: sa.Column) -> None:
    """Добавить колонку, только если её ещё нет (идемпотентно)."""
    if table in _tables(bind) and column.name not in _columns(bind, table):
        op.add_column(table, column)


def upgrade() -> None:
    bind = op.get_bind()
    tables = _tables(bind)

    # --- Кошелёк-ledger водителя (append-only) ---
    if "ledgerentry" not in tables:
        op.create_table(
            "ledgerentry",
            sa.Column("id", sa.Integer(), primary_key=True, nullable=False),
            sa.Column("driver_id", sa.Integer(), sa.ForeignKey("user.id"), nullable=False),
            sa.Column("order_id", sa.Integer(), sa.ForeignKey("instantorder.id"), nullable=True),
            sa.Column("booking_id", sa.Integer(), sa.ForeignKey("booking.id"), nullable=True),
            sa.Column("kind", sa.String(), nullable=False),
            sa.Column("amount_kop", sa.Integer(), nullable=False, server_default="0"),
            sa.Column("created_at", sa.DateTime(), nullable=False),
            sa.Column("note", sa.String(), nullable=False, server_default=""),
        )
        ix = _indexes(bind, "ledgerentry")
        for name, col in (
            ("ix_ledgerentry_driver_id", "driver_id"),
            ("ix_ledgerentry_order_id", "order_id"),
            ("ix_ledgerentry_booking_id", "booking_id"),
            ("ix_ledgerentry_kind", "kind"),
            ("ix_ledgerentry_created_at", "created_at"),
        ):
            if name not in ix:
                op.create_index(name, "ledgerentry", [col])

    # --- Колонки оплаты поездки на существующих таблицах (прод-путь) ---
    _add_col(bind, "payment", sa.Column("order_id", sa.Integer(), sa.ForeignKey("instantorder.id"), nullable=True))
    _add_col(bind, "payment", sa.Column("booking_id", sa.Integer(), sa.ForeignKey("booking.id"), nullable=True))
    _add_col(bind, "payment", sa.Column("method", sa.String(), nullable=False, server_default=""))
    if "payment" in tables and "ix_payment_created_at" not in _indexes(bind, "payment"):
        op.create_index("ix_payment_created_at", "payment", ["created_at"])

    _add_col(bind, "instantorder", sa.Column("paid", sa.Boolean(), nullable=False, server_default=sa.false()))
    _add_col(bind, "instantorder", sa.Column("payment_method", sa.String(), nullable=False, server_default=""))

    _add_col(bind, "booking", sa.Column("paid", sa.Boolean(), nullable=False, server_default=sa.false()))
    _add_col(bind, "booking", sa.Column("payment_method", sa.String(), nullable=False, server_default=""))


def downgrade() -> None:
    bind = op.get_bind()
    tables = _tables(bind)
    for table, col in (
        ("booking", "payment_method"), ("booking", "paid"),
        ("instantorder", "payment_method"), ("instantorder", "paid"),
        ("payment", "method"), ("payment", "booking_id"), ("payment", "order_id"),
    ):
        if table in tables and col in _columns(bind, table):
            # batch_alter_table, а не голый drop_column: payment.order_id/booking_id участвуют
            # в FOREIGN KEY, и SQLite отказывается снимать такую колонку через ALTER TABLE
            # ("unknown column ... in foreign key definition"). batch пересоздаёт таблицу без
            # колонки и без её внешнего ключа; на PostgreSQL это тот же ALTER TABLE DROP COLUMN.
            with op.batch_alter_table(table) as b:
                b.drop_column(col)
    if "ledgerentry" in _tables(bind):
        op.drop_table("ledgerentry")
