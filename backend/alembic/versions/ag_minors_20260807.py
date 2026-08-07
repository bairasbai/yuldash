"""Несовершеннолетний пассажир: booking.minor_* + ride.no_minors.

Зачем: возраст не спрашивался нигде. Подросток регистрировался и садился к незнакомому
человеку, водитель об этом не знал, а отвечать в случае чего пришлось бы ему. Запретить
нельзя — сайт прямо обещает «школьник доберётся», и в районе это реальная нужда. Поэтому
согласие взрослого (имя + телефон на броне) и выбор водителя (не беру без сопровождения).

ИДЕМПОТЕНТНО, оба пути:
- свежая БД: baseline create_all уже создал колонки из моделей → no-op;
- прод: таблицы есть без колонок → добавляем. Дефолты = прежнее поведение (обычная бронь,
  водитель берёт всех), поэтому существующие поездки и брони не меняются.

Revision ID: ag_minors
Revises: af_gender_verified
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "ag_minors"
down_revision = "af_gender_verified"
branch_labels = None
depends_on = None


def _columns(bind, table: str) -> set:
    try:
        return {c["name"] for c in inspect(bind).get_columns(table)}
    except Exception:  # noqa: BLE001 — таблицы ещё нет (до baseline) → считаем пустой
        return set()


def upgrade() -> None:
    bind = op.get_bind()
    booking = _columns(bind, "booking")
    if booking:
        if "minor_passenger" not in booking:
            op.add_column("booking", sa.Column("minor_passenger", sa.Boolean(),
                                               nullable=False, server_default=sa.false()))
        if "minor_guardian_name" not in booking:
            op.add_column("booking", sa.Column("minor_guardian_name", sa.String(length=120),
                                               nullable=False, server_default=""))
        if "minor_guardian_phone" not in booking:
            op.add_column("booking", sa.Column("minor_guardian_phone", sa.String(length=32),
                                               nullable=False, server_default=""))
    ride = _columns(bind, "ride")
    if ride and "no_minors" not in ride:
        op.add_column("ride", sa.Column("no_minors", sa.Boolean(),
                                        nullable=False, server_default=sa.false()))


def downgrade() -> None:
    bind = op.get_bind()
    if "no_minors" in _columns(bind, "ride"):
        with op.batch_alter_table("ride") as batch:
            batch.drop_column("no_minors")
    booking = _columns(bind, "booking")
    if booking:
        with op.batch_alter_table("booking") as batch:
            for col in ("minor_guardian_phone", "minor_guardian_name", "minor_passenger"):
                if col in booking:
                    batch.drop_column(col)
