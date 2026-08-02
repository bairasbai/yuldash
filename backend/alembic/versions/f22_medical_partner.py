"""F22 — B2B «медцентр-партнёр»: таблица medicalpartner + ride.partner_id + сид примеров.

Справочник клиник (партнёров-медцентров) как точек назначения поездок «в больницу».
ДЕЛИКАТНО: только логистика (доехать до клиники). Никаких мед.данных пациента —
клиника это публичная организация и обычный пункт назначения маршрута.

ИДЕМПОТЕНТНО (baseline-через-create_all, как соседние ревизии):
- свежая/dev БД: create_all уже создал `medicalpartner` и колонку `ride.partner_id` → эти шаги no-op;
- прод (создан раньше, без F22): создаём таблицу и добавляем колонку;
- сид: заполняем справочник примерами, ТОЛЬКО если он пуст.

Прод: `alembic upgrade head`.

Revision ID: f22_medical_partner
Revises: 0004_booking_boarding_code
"""
from datetime import datetime, timezone

import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "f22_medical_partner"
down_revision = "f19_invite_drivers"
branch_labels = None
depends_on = None


def _tables(bind) -> set:
    return set(inspect(bind).get_table_names())


def _cols(bind, table: str) -> set:
    return {c["name"] for c in inspect(bind).get_columns(table)}


# Пара реальных примеров: республиканская клиника в Уфе + районные ЦРБ.
# Держим в синхроне с app/routers/medical.py::_SEED_PARTNERS.
_SEED = [
    ("РКБ им. Г. Г. Куватова", "Уфа", "ул. Достоевского, 132", 54.7261, 55.9475,
     "Республиканская клиническая больница. Как доехать: центр Уфы, рядом остановка."),
    ("Баймакская ЦРБ", "Баймак", "ул. М. Горького, 5", 52.5913, 58.3170,
     "Центральная районная больница Баймакского района."),
    ("Сибайская ЦГБ", "Сибай", "ул. Заки Валиди, 27", 52.7160, 58.6640,
     "Центральная городская больница Сибая."),
]


def upgrade() -> None:
    bind = op.get_bind()

    # 1) Таблица справочника клиник (если ещё нет).
    if "medicalpartner" not in _tables(bind):
        op.create_table(
            "medicalpartner",
            sa.Column("id", sa.Integer(), primary_key=True, nullable=False),
            sa.Column("name", sa.String(), nullable=False),
            sa.Column("city", sa.String(), nullable=False),
            sa.Column("address", sa.String(), nullable=False, server_default=""),
            sa.Column("lat", sa.Float(), nullable=True),
            sa.Column("lng", sa.Float(), nullable=True),
            sa.Column("description", sa.String(), nullable=False, server_default=""),
            sa.Column("active", sa.Boolean(), nullable=False, server_default=sa.true()),
            sa.Column("created_at", sa.DateTime(), nullable=False, server_default=sa.func.now()),
        )
        op.create_index("ix_medicalpartner_name", "medicalpartner", ["name"])
        op.create_index("ix_medicalpartner_city", "medicalpartner", ["city"])
        op.create_index("ix_medicalpartner_active", "medicalpartner", ["active"])

    # 2) Ride.partner_id — клиника-назначение (опц.). Без DB-FK, чтобы ALTER был лёгким и
    #    не спорил с порядком таблиц на проде; связь на уровне приложения.
    if "partner_id" not in _cols(bind, "ride"):
        with op.batch_alter_table("ride") as batch:
            batch.add_column(sa.Column("partner_id", sa.Integer(), nullable=True))
        op.create_index("ix_ride_partner_id", "ride", ["partner_id"])

    # 3) Сид примеров — только если справочник пуст (идемпотентно).
    mp = sa.table(
        "medicalpartner",
        sa.column("name", sa.String), sa.column("city", sa.String),
        sa.column("address", sa.String), sa.column("lat", sa.Float),
        sa.column("lng", sa.Float), sa.column("description", sa.String),
        sa.column("active", sa.Boolean), sa.column("created_at", sa.DateTime),
    )
    count = bind.execute(sa.text("SELECT COUNT(*) FROM medicalpartner")).scalar()
    if not count:
        now = datetime.now(timezone.utc).replace(tzinfo=None)
        op.bulk_insert(mp, [
            {"name": n, "city": c, "address": a, "lat": la, "lng": ln,
             "description": d, "active": True, "created_at": now}
            for (n, c, a, la, ln, d) in _SEED
        ])


def downgrade() -> None:
    bind = op.get_bind()
    if "partner_id" in _cols(bind, "ride"):
        try:
            op.drop_index("ix_ride_partner_id", table_name="ride")
        except Exception:  # noqa: BLE001 — индекса могло не быть
            pass
        with op.batch_alter_table("ride") as batch:
            batch.drop_column("partner_id")
    if "medicalpartner" in _tables(bind):
        op.drop_table("medicalpartner")
