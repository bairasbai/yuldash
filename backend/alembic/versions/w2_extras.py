"""Волна 2, батч B9b «Предзапусковые мелочи».

Аддитивно (данные не трогаем):
- таблица dailydigestlog — замок дневной сводки админу (UNIQUE(day) решает гонку воркеров, B9b-3);
- user.is_reviewer — тестовый аккаунт модерации сторов (обычный пассажир, без прав, B9b-4).

ИДЕМПОТЕНТНО (паттерн w2_antifraud), оба пути:
- свежая БД: 0001_baseline (create_all из моделей) уже создал всё → ревизия no-op;
- прод (Postgres): добавляем недостающие таблицу/колонку.

Прод: `alembic upgrade head`.

Revision ID: w2_extras
Revises: w2_antifraud
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "w2_extras"
down_revision = "w2_antifraud"
branch_labels = None
depends_on = None


def _tables(bind) -> set:
    try:
        return set(inspect(bind).get_table_names())
    except Exception:  # noqa: BLE001
        return set()


def _columns(bind, table: str) -> set:
    try:
        return {c["name"] for c in inspect(bind).get_columns(table)}
    except Exception:  # noqa: BLE001
        return set()


def _add_col(bind, table: str, col: sa.Column) -> None:
    if col.name not in _columns(bind, table):
        op.add_column(table, col)


def _drop_col(bind, table: str, name: str) -> None:
    if name in _columns(bind, table):
        with op.batch_alter_table(table) as b:
            b.drop_column(name)


def upgrade() -> None:
    bind = op.get_bind()

    # --- B9b-3: замок дневной сводки (одна строка = день отправлен) ---
    if "dailydigestlog" not in _tables(bind):
        op.create_table(
            "dailydigestlog",
            sa.Column("id", sa.Integer(), primary_key=True),
            sa.Column("day", sa.Date(), nullable=False, unique=True),
            sa.Column("sent_at", sa.DateTime(), nullable=False),
        )

    # --- B9b-4: тестовый аккаунт для модерации сторов ---
    _add_col(bind, "user", sa.Column("is_reviewer", sa.Boolean(), nullable=False,
                                     server_default=sa.false()))


def downgrade() -> None:
    bind = op.get_bind()
    _drop_col(bind, "user", "is_reviewer")
    if "dailydigestlog" in _tables(bind):
        op.drop_table("dailydigestlog")
