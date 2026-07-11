"""F10: Booking.pay_method + pay_amount — фиксация ДОГОВОРЁННОСТИ об оплате.

Это ЗАПИСЬ договорённости («как решили платить»), а НЕ платёж и не движение денег —
юр-модель проекта не меняется. Поля видны обеим сторонам брони и полезны в споре.

Колонки добавлены в модель `Booking`, но на свежей БД их создаёт `create_all`, а на проде
(таблица создана раньше) их нет — `create_all` существующую таблицу не трогает. Закрываем миграцией.

ИДЕМПОТЕНТНО (baseline-через-create_all):
- свежая БД: create_all уже создал booking С колонками → no-op;
- прод (колонок нет): добавляем `pay_method varchar NOT NULL DEFAULT 'negotiate'` + `pay_amount integer NULL`.

⚠️ Ревизия НАМЕРЕННО названа уникально (revision id "f10_payment_agreement") и висит на общей
базе `0004_booking_boarding_code`. Параллельные ветки других фич добавят свои ревизии на ту же
базу → несколько heads. Единую линейную цепочку миграций сведёт лид при мердже.

Прод: `alembic upgrade head`.

Revision ID: f10_payment_agreement
Revises: 0004_booking_boarding_code
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "f10_payment_agreement"
down_revision = "0004_booking_boarding_code"
branch_labels = None
depends_on = None


def _cols(bind, table: str) -> set:
    return {c["name"] for c in inspect(bind).get_columns(table)}


def upgrade() -> None:
    bind = op.get_bind()
    cols = _cols(bind, "booking")
    with op.batch_alter_table("booking") as batch:
        if "pay_method" not in cols:
            batch.add_column(sa.Column("pay_method", sa.String(), nullable=False, server_default="negotiate"))
        if "pay_amount" not in cols:
            batch.add_column(sa.Column("pay_amount", sa.Integer(), nullable=True))


def downgrade() -> None:
    bind = op.get_bind()
    cols = _cols(bind, "booking")
    with op.batch_alter_table("booking") as batch:
        if "pay_amount" in cols:
            batch.drop_column("pay_amount")
        if "pay_method" in cols:
            batch.drop_column("pay_method")
