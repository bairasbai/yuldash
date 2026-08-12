"""Момент применения платежа: payment.settled_at.

Сверка ledger↔оплаты сравнивала начисления с платежами по `created_at` — а это момент, когда
человек НАЖАЛ «оплатить», а не когда деньги дошли. Обычная ночная оплата (нажал в 23:58,
деньги в 00:03) давала −сумму вчера и +сумму сегодня при полном порядке с деньгами. Прибор,
который краснеет сам по себе, перестают читать — и настоящую поломку он уже не покажет
(аудит 2026-08-12, волна 27).

Старым строкам ставим NULL: сверка берёт `COALESCE(settled_at, created_at)`, то есть для
истории поведение прежнее — задним числом её не переписываем.

ИДЕМПОТЕНТНО: свежая БД получает колонку из create_all (baseline) → тут no-op.

Revision ID: aj_payment_settled_at
Revises: merge_20260812b
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "aj_payment_settled_at"
down_revision = "merge_20260812b"
branch_labels = None
depends_on = None


def _columns(bind, table: str) -> set:
    try:
        return {c["name"] for c in inspect(bind).get_columns(table)}
    except Exception:  # noqa: BLE001
        return set()


def upgrade() -> None:
    bind = op.get_bind()
    if "settled_at" in _columns(bind, "payment"):
        return
    op.add_column("payment", sa.Column("settled_at", sa.DateTime(), nullable=True))
    op.create_index("ix_payment_settled_at", "payment", ["settled_at"])


def downgrade() -> None:
    bind = op.get_bind()
    if "settled_at" not in _columns(bind, "payment"):
        return
    try:
        op.drop_index("ix_payment_settled_at", table_name="payment")
    except Exception:  # noqa: BLE001 — индекса может не быть (создан вместе с колонкой)
        pass
    op.drop_column("payment", "settled_at")
