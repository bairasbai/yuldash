"""Фаза 3 «Деньги v2»: выплаты водителям (Модель Б, готовность, ВЫКЛ по умолчанию).

ИДЕМПОТЕНТНО (как p3_ledger), оба пути:
- свежая БД: create_all из текущих моделей уже создаёт новые колонки → ревизия видит их и no-op;
- прод (таблицы есть без новых колонок): добавляем недостающие колонки.

Добавляет:
- driverprofile: payout_card_last4, payout_token, payout_card_at (реквизиты выплат; PAN НЕ храним);
- ledgerentry: ext_id + индекс (ключ идемпотентности выплаты / id выплаты у провайдера).

Ничего не удаляет. Деньги — только целые копейки (int).

Прод: `alembic upgrade head`.

Revision ID: p3_payout
Revises: p3_ledger
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "p3_payout"
down_revision = "p3_ledger"
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

    # --- Реквизиты выплат водителя (полный номер карты НЕ храним — только последние 4 + токен) ---
    _add_col(bind, "driverprofile", sa.Column("payout_card_last4", sa.String(), nullable=False, server_default=""))
    _add_col(bind, "driverprofile", sa.Column("payout_token", sa.String(), nullable=False, server_default=""))
    _add_col(bind, "driverprofile", sa.Column("payout_card_at", sa.DateTime(), nullable=True))

    # --- ext_id для ledger (ключ идемпотентности выплаты / id выплаты у провайдера) ---
    _add_col(bind, "ledgerentry", sa.Column("ext_id", sa.String(), nullable=False, server_default=""))
    if "ledgerentry" in _tables(bind) and "ix_ledgerentry_ext_id" not in _indexes(bind, "ledgerentry"):
        op.create_index("ix_ledgerentry_ext_id", "ledgerentry", ["ext_id"])


def downgrade() -> None:
    bind = op.get_bind()
    if "ledgerentry" in _tables(bind):
        if "ix_ledgerentry_ext_id" in _indexes(bind, "ledgerentry"):
            op.drop_index("ix_ledgerentry_ext_id", table_name="ledgerentry")
        if "ext_id" in _columns(bind, "ledgerentry"):
            op.drop_column("ledgerentry", "ext_id")
    for col in ("payout_card_at", "payout_token", "payout_card_last4"):
        if "driverprofile" in _tables(bind) and col in _columns(bind, "driverprofile"):
            op.drop_column("driverprofile", col)
