"""F12 «Зимний протокол»: Booking.winter_check_sent_at / winter_check_ack_at.

Авто-проверка «доехал?»: sent_at — когда обеим сторонам ушёл пуш «всё в порядке?»,
ack_at — когда участник подтвердил, что всё хорошо (гасит эскалацию доверенным).

Колонки добавлены в модель `Booking`. На свежей БД их создаёт `create_all`, на проде
(таблица создана раньше) — нет: `create_all` существующую таблицу не трогает. Без миграции
обращение к `booking.winter_check_*` на проде → 500. Закрываем.

ИДЕМПОТЕНТНО (baseline-через-create_all):
- свежая БД: create_all уже создал booking С колонками → no-op;
- прод (колонок нет): добавляем nullable-колонки (без server_default — по умолчанию NULL).

Прод: `alembic upgrade head`.  (Миграции F-веток сведёт лид — см. PR.)

Revision ID: f12_winter_safety
Revises: 0004_booking_boarding_code
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "f12_winter_safety"
down_revision = "f10_payment_agreement"
branch_labels = None
depends_on = None


def _cols(bind, table: str) -> set:
    return {c["name"] for c in inspect(bind).get_columns(table)}


def upgrade() -> None:
    bind = op.get_bind()
    existing = _cols(bind, "booking")
    with op.batch_alter_table("booking") as batch:
        if "winter_check_sent_at" not in existing:
            batch.add_column(sa.Column("winter_check_sent_at", sa.DateTime(), nullable=True))
        if "winter_check_ack_at" not in existing:
            batch.add_column(sa.Column("winter_check_ack_at", sa.DateTime(), nullable=True))


def downgrade() -> None:
    bind = op.get_bind()
    existing = _cols(bind, "booking")
    with op.batch_alter_table("booking") as batch:
        if "winter_check_ack_at" in existing:
            batch.drop_column("winter_check_ack_at")
        if "winter_check_sent_at" in existing:
            batch.drop_column("winter_check_sent_at")
