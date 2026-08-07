"""Rating.text + Rating.text_published — текстовый отзыв о поездке с модерацией.

Колонки добавлены в модель `Rating`, но на проде таблица создана раньше — `create_all`
существующую таблицу не трогает. Обращение к `rating.text`/`rating.text_published` без
этой миграции → 500. Закрываем.

ИДЕМПОТЕНТНО (baseline-через-create_all):
- свежая БД: create_all уже создал rating С колонками → no-op;
- прод (колонок нет): добавляем `text varchar NOT NULL DEFAULT ''`
  + `text_published boolean NOT NULL DEFAULT false`.

Прод: `alembic upgrade head`.

Revision ID: 0005_rating_text_review
Revises: 0004_booking_boarding_code
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "0005_rating_text_review"
down_revision = "0005_notification_table"
branch_labels = None
depends_on = None


def _cols(bind, table: str) -> set:
    return {c["name"] for c in inspect(bind).get_columns(table)}


def upgrade() -> None:
    bind = op.get_bind()
    cols = _cols(bind, "rating")
    with op.batch_alter_table("rating") as batch:
        if "text" not in cols:
            batch.add_column(sa.Column("text", sa.String(), nullable=False, server_default=""))
        if "text_published" not in cols:
            batch.add_column(sa.Column(
                "text_published", sa.Boolean(), nullable=False, server_default=sa.false()
            ))


def downgrade() -> None:
    bind = op.get_bind()
    cols = _cols(bind, "rating")
    doomed = {c for c in ("text_published", "text") if c in cols}
    if not doomed:
        return
    # Индексы по удаляемым колонкам сносим ДО batch_alter_table. На SQLite batch пересоздаёт
    # таблицу и восстанавливает ВСЕ отражённые индексы — включая индекс по колонке, которой
    # в новой таблице уже нет ("no such column: text_published"). Имя индекса не хардкодим:
    # ix_rating_text_published объявлен в модели (Field(index=True)) и на свежей БД создан
    # baseline-ом через create_all, а не этой ревизией. duplicates_constraint пропускаем —
    # такой индекс на PostgreSQL держит UNIQUE и уходит вместе с колонкой.
    for ix in inspect(bind).get_indexes("rating"):
        if (ix.get("name") and not ix.get("duplicates_constraint")
                and doomed.intersection(ix.get("column_names") or ())):
            op.drop_index(ix["name"], table_name="rating")
    with op.batch_alter_table("rating") as batch:
        for name in ("text_published", "text"):
            if name in doomed:
                batch.drop_column(name)
