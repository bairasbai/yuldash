"""Напоминание «оцени поездку» и после такси, и после доставки (аудит 2026-08-08, волна 197).

Рейтинг в Юлдаше — это доверие между своими, и человек часто закрывает приложение, не поставив
звёзды. Ровно для этого есть фоновое напоминание. Но искало оно только брони попутки: после
заказа такси и после доставки посылки не напоминали никому, а поставить оценку можно лишь
60 дней — дальше звёзды потеряны навсегда.

Для таксиста это прямо деньги: с волны 194 его рабочий рейтинг (по нему matcher решает, кому
дать заказ) считается только по поездкам, где за рулём был он. Меньше оценок — тоньше балл
и дольше бейдж «Новичок».

Поле хранит факт «по этой поездке напоминание уже уходило» — чтобы оно ушло один раз, а не
каждый проход таймера. Такое же поле уже есть у брони попутки (`booking.rate_reminded`).

Revision ID: as_rate_reminded_all
Revises: ar_declare_reminded
"""
from alembic import op
import sqlalchemy as sa

revision = "as_rate_reminded_all"
down_revision = "ar_declare_reminded"
branch_labels = None
depends_on = None

# Таблица → имя индекса под скан «завершённые, ещё не напомненные».
ТАБЛИЦЫ = {
    "instantorder": "ix_instantorder_rate_reminded",
    "parceldelivery": "ix_parceldelivery_rate_reminded",
}


def _есть_колонка(таблица: str, колонка: str) -> bool:
    bind = op.get_bind()
    return колонка in {c["name"] for c in sa.inspect(bind).get_columns(таблица)}


def _есть_индекс(таблица: str, индекс: str) -> bool:
    bind = op.get_bind()
    return индекс in {i["name"] for i in sa.inspect(bind).get_indexes(таблица)}


def upgrade() -> None:
    # Идемпотентно: на свежей базе таблицы создаёт create_all, и колонка уже на месте.
    for таблица, индекс in ТАБЛИЦЫ.items():
        if not _есть_колонка(таблица, "rate_reminded"):
            op.add_column(
                таблица,
                sa.Column("rate_reminded", sa.Boolean(), nullable=False,
                          server_default=sa.false()),
            )
        if not _есть_индекс(таблица, индекс):
            op.create_index(индекс, таблица, ["rate_reminded"])


def downgrade() -> None:
    for таблица, индекс in ТАБЛИЦЫ.items():
        if _есть_индекс(таблица, индекс):
            op.drop_index(индекс, table_name=таблица)
        if _есть_колонка(таблица, "rate_reminded"):
            op.drop_column(таблица, "rate_reminded")
