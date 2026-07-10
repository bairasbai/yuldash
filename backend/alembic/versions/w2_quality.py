"""Волна 2, батч B5 «Качество: жалобы + лестница наказаний» (§9).

Новые колонки:
- report: category (перечень §9, default other — совместимость), order_id/booking_id
  (привязка к поездке), status (new|reviewing|resolved|rejected), resolution, resolved_at;
- rating: order_id (взаимные оценки быстрых заказов), booking_id становится NULLABLE
  (оценка привязана к брони ИЛИ заказу);
- driverprofile: taxi_paused_until/taxi_pause_reason (пауза ТАКСИ — авто по жалобам или
  админом; попутка работает), low_rating_advice_at (дедуп мягкого пуш-совета 🟡).

ИДЕМПОТЕНТНО (как p2/p3/w2_*), оба пути:
- свежая БД: `alembic upgrade head` идёт через 0001_baseline (create_all из моделей) →
  колонки уже есть → эта ревизия no-op;
- прод: добавляем недостающие колонки с server_default (существующие строки получают
  нейтральные значения: category=other, status=new, остальное NULL).

Данные не сеет, ничего не удаляет. ПОПУТКА не затрагивается.

Прод: `alembic upgrade head`.

Revision ID: w2_quality
Revises: w2_work_hours
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "w2_quality"
down_revision = "w2_work_hours"
branch_labels = None
depends_on = None


def _columns(bind, table: str) -> dict:
    try:
        return {c["name"]: c for c in inspect(bind).get_columns(table)}
    except Exception:  # noqa: BLE001
        return {}


def _indexes(bind, table: str) -> set:
    try:
        return {i["name"] for i in inspect(bind).get_indexes(table)}
    except Exception:  # noqa: BLE001
        return set()


def _fk_col(bind, name: str, ref: str) -> sa.Column:
    """Nullable FK-колонка. SQLite не умеет ALTER ADD CONSTRAINT (alembic кидает
    NotImplementedError) → там добавляем без констрейнта; прод (Postgres) — с честным FK."""
    if bind.dialect.name == "sqlite":
        return sa.Column(name, sa.Integer(), nullable=True)
    return sa.Column(name, sa.Integer(), sa.ForeignKey(ref), nullable=True)


def upgrade() -> None:
    bind = op.get_bind()
    # --- report: категория/привязка/статус разбора ---
    rep = _columns(bind, "report")
    if "category" not in rep:
        op.add_column("report", sa.Column("category", sa.String(), nullable=False, server_default="other"))
    if "order_id" not in rep:
        op.add_column("report", _fk_col(bind, "order_id", "instantorder.id"))
    if "booking_id" not in rep:
        op.add_column("report", _fk_col(bind, "booking_id", "booking.id"))
    if "status" not in rep:
        op.add_column("report", sa.Column("status", sa.String(), nullable=False, server_default="new"))
    if "resolution" not in rep:
        op.add_column("report", sa.Column("resolution", sa.String(), nullable=True))
    if "resolved_at" not in rep:
        op.add_column("report", sa.Column("resolved_at", sa.DateTime(), nullable=True))
    # --- rating: оценки быстрых заказов ---
    rat = _columns(bind, "rating")
    if "order_id" not in rat:
        op.add_column("rating", _fk_col(bind, "order_id", "instantorder.id"))
    # booking_id: NOT NULL → NULLABLE (оценка теперь к брони ИЛИ заказу). batch — ради SQLite.
    if rat.get("booking_id") is not None and rat["booking_id"].get("nullable") is False:
        with op.batch_alter_table("rating") as batch:
            batch.alter_column("booking_id", existing_type=sa.Integer(), nullable=True)
    # --- driverprofile: пауза такси + дедуп совета ---
    dp = _columns(bind, "driverprofile")
    if "taxi_paused_until" not in dp:
        op.add_column("driverprofile", sa.Column("taxi_paused_until", sa.DateTime(), nullable=True))
    if "taxi_pause_reason" not in dp:
        op.add_column("driverprofile", sa.Column("taxi_pause_reason", sa.String(), nullable=True))
    if "low_rating_advice_at" not in dp:
        op.add_column("driverprofile", sa.Column("low_rating_advice_at", sa.DateTime(), nullable=True))
    # Индексы (модели держат index=True; на свежей БД baseline уже создал — досоздаём в проде).
    rep_ix = _indexes(bind, "report")
    if "ix_report_category" not in rep_ix:
        op.create_index("ix_report_category", "report", ["category"])
    if "ix_report_status" not in rep_ix:
        op.create_index("ix_report_status", "report", ["status"])
    if "ix_report_target_user_id" not in rep_ix:
        op.create_index("ix_report_target_user_id", "report", ["target_user_id"])
    if "ix_rating_order_id" not in _indexes(bind, "rating"):
        op.create_index("ix_rating_order_id", "rating", ["order_id"])


def downgrade() -> None:
    bind = op.get_bind()
    dp = _columns(bind, "driverprofile")
    drop_dp = [c for c in ("low_rating_advice_at", "taxi_pause_reason", "taxi_paused_until") if c in dp]
    if drop_dp:
        with op.batch_alter_table("driverprofile") as batch:
            for col in drop_dp:
                batch.drop_column(col)
    rat = _columns(bind, "rating")
    if "order_id" in rat:
        if "ix_rating_order_id" in _indexes(bind, "rating"):
            op.drop_index("ix_rating_order_id", table_name="rating")
        with op.batch_alter_table("rating") as batch:
            batch.drop_column("order_id")
    # booking_id обратно NOT NULL не делаем: в проде могут остаться оценки заказов
    # с booking_id=NULL — жёсткий откат сломал бы данные (безопасный даунгрейд).
    rep = _columns(bind, "report")
    rep_ix = _indexes(bind, "report")
    for ix in ("ix_report_category", "ix_report_status", "ix_report_target_user_id"):
        if ix in rep_ix:
            op.drop_index(ix, table_name="report")
    drop_rep = [c for c in ("resolved_at", "resolution", "status", "booking_id", "order_id", "category")
                if c in rep]
    if drop_rep:
        with op.batch_alter_table("report") as batch:
            for col in drop_rep:
                batch.drop_column(col)
