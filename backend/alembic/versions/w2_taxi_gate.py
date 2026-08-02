"""Волна 2: гейт такси + онбординг таксиста (580-ФЗ).

ИДЕМПОТЕНТНО (как p2/p3_*), оба пути:
- свежая БД: `alembic upgrade head` идёт через 0001_baseline (create_all из текущих моделей)
  → таблицы taxicity/taxiapplication уже созданы → эта ревизия видит их и делает no-op;
- прод (таблиц ещё нет): создаём обе таблицы с индексами.

Добавляет:
- taxicity — города, где включено такси (city, enabled); логика в app/taxi.py;
- taxiapplication — заявка «Стать таксистом» (user_id unique, inn, permit_number,
  фото разрешения/ОСАГО, birth_date, license_since_year, status[pending|approved|rejected],
  comment, created_at, reviewed_at).
Ничего не удаляет. ПОПУТКА не затрагивается.

Прод: `alembic upgrade head`.

Revision ID: w2_taxi_gate
Revises: p3_debt
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "w2_taxi_gate"
down_revision = "p3_debt"
branch_labels = None
depends_on = None


def _tables(bind) -> set:
    return set(inspect(bind).get_table_names())


def _indexes(bind, table: str) -> set:
    try:
        return {ix["name"] for ix in inspect(bind).get_indexes(table)}
    except Exception:  # noqa: BLE001
        return set()


def upgrade() -> None:
    bind = op.get_bind()
    tables = _tables(bind)
    if "taxicity" not in tables:
        op.create_table(
            "taxicity",
            sa.Column("id", sa.Integer(), primary_key=True, nullable=False),
            sa.Column("city", sa.String(), nullable=False),
            sa.Column("enabled", sa.Boolean(), nullable=False, server_default=sa.true()),
            sa.Column("created_at", sa.DateTime(), nullable=False),
        )
        if "ix_taxicity_city" not in _indexes(bind, "taxicity"):
            op.create_index("ix_taxicity_city", "taxicity", ["city"])
    if "taxiapplication" not in tables:
        op.create_table(
            "taxiapplication",
            sa.Column("id", sa.Integer(), primary_key=True, nullable=False),
            sa.Column("user_id", sa.Integer(), sa.ForeignKey("user.id"), nullable=False),
            sa.Column("inn", sa.String(), nullable=False, server_default=""),
            sa.Column("permit_number", sa.String(), nullable=False, server_default=""),
            sa.Column("permit_photo_url", sa.String(), nullable=True),
            sa.Column("osago_url", sa.String(), nullable=True),
            sa.Column("birth_date", sa.Date(), nullable=False),
            sa.Column("license_since_year", sa.Integer(), nullable=False, server_default="0"),
            sa.Column("status", sa.String(), nullable=False, server_default="pending"),
            sa.Column("comment", sa.String(), nullable=True),
            sa.Column("created_at", sa.DateTime(), nullable=False),
            sa.Column("reviewed_at", sa.DateTime(), nullable=True),
        )
        ix = _indexes(bind, "taxiapplication")
        if "ix_taxiapplication_user_id" not in ix:
            op.create_index("ix_taxiapplication_user_id", "taxiapplication", ["user_id"], unique=True)
        if "ix_taxiapplication_status" not in ix:
            op.create_index("ix_taxiapplication_status", "taxiapplication", ["status"])


def downgrade() -> None:
    bind = op.get_bind()
    tables = _tables(bind)
    if "taxiapplication" in tables:
        op.drop_table("taxiapplication")
    if "taxicity" in tables:
        op.drop_table("taxicity")
