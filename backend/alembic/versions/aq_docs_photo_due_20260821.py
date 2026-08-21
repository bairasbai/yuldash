"""Срок «принеси фото документа» у заявки таксиста (аудит 2026-08-08, волна 170).

Водитель, у которого кончился документ, может вписать новую дату сам и работать сразу —
ждать модератора ради продлённого полиса никто не должен. Но если фото так и не пришло,
обещание остаётся словом: ночной робот смотрит на даты, а даты водитель поправил своей рукой.

Поле хранит момент, до которого он работает «на честном слове». Пришло фото — гасим;
не пришло — ночной обход возвращает снятие допуска.

Revision ID: aq_docs_photo_due
Revises: ap_booking_confirmed_at
"""
from alembic import op
import sqlalchemy as sa

revision = "aq_docs_photo_due"
down_revision = "ap_booking_confirmed_at"
branch_labels = None
depends_on = None


def _есть_колонка(таблица: str, колонка: str) -> bool:
    bind = op.get_bind()
    return колонка in {c["name"] for c in sa.inspect(bind).get_columns(таблица)}


def upgrade() -> None:
    # Идемпотентно: на свежей базе таблицы создаёт create_all, и колонка уже на месте.
    if not _есть_колонка("taxiapplication", "docs_photo_due_at"):
        op.add_column("taxiapplication",
                      sa.Column("docs_photo_due_at", sa.DateTime(), nullable=True))
        op.create_index("ix_taxiapplication_docs_photo_due_at", "taxiapplication",
                        ["docs_photo_due_at"])


def downgrade() -> None:
    if _есть_колонка("taxiapplication", "docs_photo_due_at"):
        op.drop_index("ix_taxiapplication_docs_photo_due_at", table_name="taxiapplication")
        op.drop_column("taxiapplication", "docs_photo_due_at")
