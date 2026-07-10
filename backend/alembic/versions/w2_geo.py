"""Волна 2: география — справочник НП (Settlement) + зона работы таксиста.

ИДЕМПОТЕНТНО (как p2/p3/w2_taxi_gate), оба пути:
- свежая БД: `alembic upgrade head` идёт через 0001_baseline (create_all из текущих
  моделей) → таблица settlement и колонки зоны уже есть → эта ревизия no-op;
- прод (ещё нет): создаём settlement с индексом по name_ru и добавляем в driverprofile
  колонки work_zone / work_city / work_direction_id (все NULL — прежнее поведение matcher'а).

Данные НЕ сеет — сид идемпотентный в lifespan (app/geo.py: seed_settlements, как seed_tariffs).
Ничего не удаляет. ПОПУТКА не затрагивается.

Прод: `alembic upgrade head`.

Revision ID: w2_geo
Revises: w2_taxi_gate
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "w2_geo"
down_revision = "w2_taxi_gate"
branch_labels = None
depends_on = None


def _tables(bind) -> set:
    return set(inspect(bind).get_table_names())


def _columns(bind, table: str) -> set:
    try:
        return {c["name"] for c in inspect(bind).get_columns(table)}
    except Exception:  # noqa: BLE001
        return set()


def _indexes(bind, table: str) -> set:
    try:
        return {ix["name"] for ix in inspect(bind).get_indexes(table)}
    except Exception:  # noqa: BLE001
        return set()


def upgrade() -> None:
    bind = op.get_bind()
    if "settlement" not in _tables(bind):
        op.create_table(
            "settlement",
            sa.Column("id", sa.Integer(), primary_key=True, nullable=False),
            sa.Column("name_ru", sa.String(), nullable=False),
            sa.Column("name_ba", sa.String(), nullable=True),
            sa.Column("region", sa.String(), nullable=False, server_default=""),
            sa.Column("kind", sa.String(), nullable=False, server_default="city"),
            sa.Column("lat", sa.Float(), nullable=False, server_default="0"),
            sa.Column("lng", sa.Float(), nullable=False, server_default="0"),
            sa.Column("active", sa.Boolean(), nullable=False, server_default=sa.true()),
        )
    if "ix_settlement_name_ru" not in _indexes(bind, "settlement"):
        op.create_index("ix_settlement_name_ru", "settlement", ["name_ru"])
    cols = _columns(bind, "driverprofile")
    # Колонки зоны — без FK-констрейнта на add_column (SQLite не умеет ALTER ADD FK;
    # свежая БД получает FK через create_all из моделей). Целостность держит приложение.
    if "work_zone" not in cols:
        op.add_column("driverprofile", sa.Column("work_zone", sa.String(), nullable=True))
    if "work_city" not in cols:
        op.add_column("driverprofile", sa.Column("work_city", sa.String(), nullable=True))
    if "work_direction_id" not in cols:
        op.add_column("driverprofile", sa.Column("work_direction_id", sa.Integer(), nullable=True))


def downgrade() -> None:
    bind = op.get_bind()
    cols = _columns(bind, "driverprofile")
    drop = [c for c in ("work_direction_id", "work_city", "work_zone") if c in cols]
    if drop:
        # batch: на SQLite колонку с FK (свежая БД через create_all) простым ALTER не снять —
        # batch пересобирает таблицу; на Postgres сводится к обычным ALTER.
        with op.batch_alter_table("driverprofile") as batch:
            for col in drop:
                batch.drop_column(col)
    if "settlement" in _tables(bind):
        op.drop_table("settlement")
