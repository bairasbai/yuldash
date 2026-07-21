"""G1 «Трекинг-ссылка посылки получателю».

Аддитивно (данные не трогаем):
- tripshare.parcel_id (NULL, INTEGER, index) — привязка публичной ссылки /t/{token} к доставке
  (раньше только booking_id / order_id). Получатель следит за курьером без приложения.
- tripshare.contact_id → NULLABLE: у брони/такси контакт есть (доверенный близкий), у посылки
  контакта нет (ссылку отдаёт отправитель / SMS на телефон получателя).

ИДЕМПОТЕНТНО (паттерн w2_livelink), оба пути:
- свежая БД: 0001_baseline (create_all из моделей) уже создал колонку/nullable → ревизия no-op;
- прод (Postgres): добавляем недостающую колонку, индекс и снимаем NOT NULL с contact_id.

Прод: `alembic upgrade head`.

Revision ID: g1_parcel_track
Revises: p2_promo_unique
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "g1_parcel_track"
down_revision = "p2_promo_unique"
branch_labels = None
depends_on = None

_IX = "ix_tripshare_parcel_id"


def _cols(bind, table: str) -> dict:
    """{имя_колонки: nullable(bool)} — или пусто, если таблицы ещё нет."""
    try:
        return {c["name"]: c["nullable"] for c in inspect(bind).get_columns(table)}
    except Exception:  # noqa: BLE001
        return {}


def _indexes(bind, table: str) -> set:
    try:
        return {i["name"] for i in inspect(bind).get_indexes(table)}
    except Exception:  # noqa: BLE001
        return set()


def upgrade() -> None:
    bind = op.get_bind()
    cols = _cols(bind, "tripshare")
    if "parcel_id" not in cols:
        op.add_column("tripshare", sa.Column("parcel_id", sa.Integer(), nullable=True))
    if _IX not in _indexes(bind, "tripshare"):
        op.create_index(_IX, "tripshare", ["parcel_id"], unique=False)
    # contact_id стал опциональным. На Postgres снимаем NOT NULL, если ещё стоит;
    # на SQLite dev-БД пересоздаётся из моделей (там уже nullable) — ALTER не трогаем.
    if bind.dialect.name != "sqlite" and cols.get("contact_id") is False:
        op.alter_column("tripshare", "contact_id", existing_type=sa.Integer(), nullable=True)


def downgrade() -> None:
    bind = op.get_bind()
    if _IX in _indexes(bind, "tripshare"):
        op.drop_index(_IX, table_name="tripshare")
    if "parcel_id" in _cols(bind, "tripshare"):
        with op.batch_alter_table("tripshare") as b:
            b.drop_column("parcel_id")
