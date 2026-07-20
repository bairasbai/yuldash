"""Свести две головы миграций после консолидации release-2026-07.

Ветки #85 (booking_cancel_reason) и #89 (villages_district) ответвились от одной
ревизии (analytics_events) → при слиянии обеих в накопитель появились ДВЕ головы.
Alembic не умеет `upgrade head` при неоднозначности — эта пустая merge-ревизия
линеаризует историю в одну голову. Схему НЕ меняет (обе миграции идемпотентны сами).

Revision ID: merge_20260720
Revises: booking_cancel_reason, villages_district
"""

revision = "merge_20260720"
down_revision = ("booking_cancel_reason", "villages_district")
branch_labels = None
depends_on = None


def upgrade() -> None:
    pass


def downgrade() -> None:
    pass
