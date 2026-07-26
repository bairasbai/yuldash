"""580-ФЗ, честный минимум: срок диагностической карты + предрейсовое подтверждение.

Что добавляем и зачем (решение Александра 2026-07-26, «честный минимум»):
- taxiapplication.inspection_until — дата диагностической карты (техосмотра). Раньше техосмотра
  в коде не было вообще: одобрили в июле — человек возит на непроверенной машине в декабре,
  а мы называемся «проверенной службой». Контроль срока — та же фоновая задача app/doc_check.py,
  что и для ОСАГО/разрешения.
- таблица pretripcheck — одна строка на водителя в день: перед первым выходом на линию он ЯВНО
  подтверждает самочувствие, исправность машины и отсутствие алкоголя. Это самодекларация,
  а не медосмотр (медцентра у нас нет), но это след: при разборе ДТП видно, что человек заявил
  в этот день. UNIQUE(driver_id, day) — повторное подтверждение не плодит строк.

Аддитивно, ИДЕМПОТЕНТНО (паттерн w2_extras): свежая БД (create_all) → no-op; прод → добавляем.

Прод: `alembic upgrade head`.

Revision ID: p_taxi_docs_pretrip
Revises: o_gaps_taxi_courier
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "p_taxi_docs_pretrip"
down_revision = "o_gaps_taxi_courier"
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


def _indexes(bind, table: str) -> set:
    try:
        return {i["name"] for i in inspect(bind).get_indexes(table)}
    except Exception:  # noqa: BLE001
        return set()


def upgrade() -> None:
    bind = op.get_bind()

    # --- срок диагностической карты (техосмотр) ---
    if "inspection_until" not in _columns(bind, "taxiapplication"):
        op.add_column("taxiapplication", sa.Column("inspection_until", sa.Date(), nullable=True))
    if ("ix_taxiapplication_inspection_until" not in _indexes(bind, "taxiapplication")
            and _columns(bind, "taxiapplication")):
        op.create_index("ix_taxiapplication_inspection_until", "taxiapplication", ["inspection_until"])

    # --- предрейсовое подтверждение (один день = одна строка) ---
    if "pretripcheck" not in _tables(bind):
        op.create_table(
            "pretripcheck",
            sa.Column("id", sa.Integer(), primary_key=True),
            sa.Column("driver_id", sa.Integer(), sa.ForeignKey("user.id"), nullable=False),
            sa.Column("day", sa.Date(), nullable=False),
            sa.Column("health_ok", sa.Boolean(), nullable=False, server_default=sa.false()),
            sa.Column("car_ok", sa.Boolean(), nullable=False, server_default=sa.false()),
            sa.Column("no_alcohol", sa.Boolean(), nullable=False, server_default=sa.false()),
            sa.Column("note", sa.String(length=300), nullable=False, server_default=""),
            sa.Column("created_at", sa.DateTime(), nullable=False),
            sa.UniqueConstraint("driver_id", "day", name="uq_pretripcheck_driver_day"),
        )
        op.create_index("ix_pretripcheck_driver_id", "pretripcheck", ["driver_id"])
        op.create_index("ix_pretripcheck_day", "pretripcheck", ["day"])


def downgrade() -> None:
    bind = op.get_bind()
    if "pretripcheck" in _tables(bind):
        op.drop_table("pretripcheck")
    if "ix_taxiapplication_inspection_until" in _indexes(bind, "taxiapplication"):
        op.drop_index("ix_taxiapplication_inspection_until", table_name="taxiapplication")
    if "inspection_until" in _columns(bind, "taxiapplication"):
        with op.batch_alter_table("taxiapplication") as b:
            b.drop_column("inspection_until")
