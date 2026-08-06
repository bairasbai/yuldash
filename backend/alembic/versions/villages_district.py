"""Деревни РБ: колонка settlement.district (различать тёзок) + опора для kind='village'.

ИДЕМПОТЕНТНО (как w2_geo и пр.), оба пути:
- свежая БД: baseline create_all уже создаёт settlement.district из модели → тут no-op;
- прод: settlement уже есть без district → добавляем колонку (NULL — прежнее поведение).

Данные НЕ сеет — деревни сеются идемпотентно в lifespan (app/geo.py: seed_villages,
как seed_settlements). Ничего не удаляет. kind='village' — просто значение, схему не меняет.

Revision ID: villages_district
Revises: ac_booking_lost_item

ПОЧЕМУ ac_booking_lost_item, а не analytics_events (как было в ветке). Ветка про районы
отошла от `main` в тот момент, когда хвостом цепочки был `analytics_events`. За месяц `main`
дописал туда ещё десяток миграций и закончил на `ac_booking_lost_item`. При сборке релизной
ветки обе цепочки встретились — и у alembic стало ДВЕ головы вместо одной.

Чем это плохо: `alembic upgrade head` при двух головах не знает, какую катить, а сторож в CI
на этом честно краснеет. На проде это значило бы «колонки района не создались, а никто не
заметил». Цепочку выпрямили: миграция идемпотентная (сама проверяет, есть ли колонка),
поэтому её позиция в очереди роли не играет — важно лишь, что очередь одна.
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "villages_district"
down_revision = "ac_booking_lost_item"
branch_labels = None
depends_on = None


def _columns(bind, table: str) -> set:
    try:
        return {c["name"] for c in inspect(bind).get_columns(table)}
    except Exception:  # noqa: BLE001 — таблицы ещё нет (до baseline) → считаем пустой
        return set()


def upgrade() -> None:
    bind = op.get_bind()
    cols = _columns(bind, "settlement")
    if cols and "district" not in cols:
        op.add_column("settlement", sa.Column("district", sa.String(), nullable=True))


def downgrade() -> None:
    bind = op.get_bind()
    if "district" in _columns(bind, "settlement"):
        op.drop_column("settlement", "district")
