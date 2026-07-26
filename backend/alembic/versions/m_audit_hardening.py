"""Аудит 2026-07-26: учёт применённых наказаний + индекс rate-reminder.

1) incident.applied_warning / applied_strike / applied_suspended_until — что РЕАЛЬНО наложил
   спор на профиль обвинённого. Нужно для честного пере-решения после апелляции: «оставить
   в силе» не наказывает второй раз, «отменить» снимает ровно свой вклад.
2) Индекс booking(status, rate_reminded) — скан rate-reminder'а перестаёт ходить heap'ом
   по всем done-броням всех времён.

Аддитивно, ИДЕМПОТЕНТНО (паттерн g_tips): свежая БД (create_all) → no-op; прод → add_column.

Прод: `alembic upgrade head`.

Revision ID: m_audit_hardening
Revises: l_booking_cancelled_by
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "m_audit_hardening"
down_revision = "l_booking_cancelled_by"
branch_labels = None
depends_on = None

_IX = "ix_booking_status_rate_reminded"


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


def upgrade() -> None:
    bind = op.get_bind()
    cols = _columns(bind, "incident")
    if "applied_warning" not in cols:
        op.add_column("incident", sa.Column("applied_warning", sa.Boolean(), nullable=False,
                                            server_default=sa.false()))
    if "applied_strike" not in cols:
        op.add_column("incident", sa.Column("applied_strike", sa.Boolean(), nullable=False,
                                            server_default=sa.false()))
    if "applied_suspended_until" not in cols:
        op.add_column("incident", sa.Column("applied_suspended_until", sa.DateTime(), nullable=True))
    if _IX not in _indexes(bind, "booking"):
        op.create_index(_IX, "booking", ["status", "rate_reminded"])


def downgrade() -> None:
    bind = op.get_bind()
    if _IX in _indexes(bind, "booking"):
        op.drop_index(_IX, table_name="booking")
    cols = _columns(bind, "incident")
    with op.batch_alter_table("incident") as b:
        if "applied_suspended_until" in cols:
            b.drop_column("applied_suspended_until")
        if "applied_strike" in cols:
            b.drop_column("applied_strike")
        if "applied_warning" in cols:
            b.drop_column("applied_warning")
