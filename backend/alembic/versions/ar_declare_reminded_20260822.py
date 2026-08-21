"""Предупреждение водителю до того, как «на доверии» кончится (аудит 2026-08-08, волна 176).

Водитель перевёл деньги по СБП и нажал «Я оплатил» — такси работает три дня на доверии.
Не подтвердили за три дня, такси закрывается. Срок нужен: без него кнопку жали бы каждую
неделю вместо оплаты. Но Александр один и половину недели в командировках, и честный водитель
узнавал об отключении по факту отключения — в кабинете ему при этом писали «оплати долг».

Поле хранит момент, когда мы предупредили: за сутки до конца доверия. Нужно, чтобы
предупреждение ушло один раз, а не каждую ночь — иначе его перестают читать ровно
к тому дню, когда оно важно.

Revision ID: ar_declare_reminded
Revises: aq_docs_photo_due
"""
from alembic import op
import sqlalchemy as sa

revision = "ar_declare_reminded"
down_revision = "aq_docs_photo_due"
branch_labels = None
depends_on = None


def _есть_колонка(таблица: str, колонка: str) -> bool:
    bind = op.get_bind()
    return колонка in {c["name"] for c in sa.inspect(bind).get_columns(таблица)}


def upgrade() -> None:
    # Идемпотентно: на свежей базе таблицы создаёт create_all, и колонка уже на месте.
    if not _есть_колонка("commissiondebt", "declare_reminded_at"):
        op.add_column("commissiondebt",
                      sa.Column("declare_reminded_at", sa.DateTime(), nullable=True))


def downgrade() -> None:
    if _есть_колонка("commissiondebt", "declare_reminded_at"):
        op.drop_column("commissiondebt", "declare_reminded_at")
