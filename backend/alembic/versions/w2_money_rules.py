"""Волна 2, батч B3 «Деньги-тонкости»: сурж + ожидание/отмены/no-show + классы машин.

Новые колонки:
- instantorder: surge_k (фикс применённого суржа), waiting_started_at («Я на месте»),
  waiting_fee_kop (платное ожидание), cancel_fee_kop (штраф-подача, Модель А — только
  фиксируем), no_show («пассажир не вышел»);
- driverprofile: car_class (economy|comfort, NULL = economy).

ИДЕМПОТЕНТНО (как p2/p3/w2_*), оба пути:
- свежая БД: `alembic upgrade head` идёт через 0001_baseline (create_all из моделей) →
  колонки уже есть → эта ревизия no-op;
- прод: добавляем недостающие колонки с server_default (существующие строки получают
  нейтральные значения: surge_k=1, суммы 0, no_show=false, car_class NULL).

Данные НЕ сеет — комфорт-тарифы досеивает идемпотентный seed_tariffs в lifespan.
Ничего не удаляет. ПОПУТКА не затрагивается.

Прод: `alembic upgrade head`.

Revision ID: w2_money_rules
Revises: w2_geo
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "w2_money_rules"
down_revision = "w2_geo"
branch_labels = None
depends_on = None


def _columns(bind, table: str) -> set:
    try:
        return {c["name"] for c in inspect(bind).get_columns(table)}
    except Exception:  # noqa: BLE001
        return set()


def upgrade() -> None:
    bind = op.get_bind()
    cols = _columns(bind, "instantorder")
    if "surge_k" not in cols:
        op.add_column("instantorder", sa.Column("surge_k", sa.Float(), nullable=False, server_default="1"))
    if "waiting_started_at" not in cols:
        op.add_column("instantorder", sa.Column("waiting_started_at", sa.DateTime(), nullable=True))
    if "waiting_fee_kop" not in cols:
        op.add_column("instantorder", sa.Column("waiting_fee_kop", sa.Integer(), nullable=False, server_default="0"))
    if "cancel_fee_kop" not in cols:
        op.add_column("instantorder", sa.Column("cancel_fee_kop", sa.Integer(), nullable=False, server_default="0"))
    if "no_show" not in cols:
        op.add_column("instantorder", sa.Column("no_show", sa.Boolean(), nullable=False, server_default=sa.false()))
    if "car_class" not in _columns(bind, "driverprofile"):
        op.add_column("driverprofile", sa.Column("car_class", sa.String(), nullable=True))


def downgrade() -> None:
    bind = op.get_bind()
    io_cols = _columns(bind, "instantorder")
    drop_io = [c for c in ("no_show", "cancel_fee_kop", "waiting_fee_kop",
                           "waiting_started_at", "surge_k") if c in io_cols]
    if drop_io:
        # batch: на SQLite простым ALTER колонки не снять — batch пересобирает таблицу;
        # на Postgres сводится к обычным ALTER DROP COLUMN.
        with op.batch_alter_table("instantorder") as batch:
            for col in drop_io:
                batch.drop_column(col)
    if "car_class" in _columns(bind, "driverprofile"):
        with op.batch_alter_table("driverprofile") as batch:
            batch.drop_column("car_class")
