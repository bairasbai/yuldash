"""Закрытие пробелов такси/курьера (аудит 2026-07-26): все новые поля одной ревизией.

Что добавляем и зачем (подробности — docs/gaps-taxi-courier-2026-07-26.md):
- instantorder: comment/entrance (как найти пассажира в селе), for_name/for_phone (заказ для
  другого человека), wait_until/retry_count (очередь «рядом никого»), lost_item_until (забытые вещи);
- parceldelivery: возврат (return_reason/returned_at/delivery_attempts), фото на границах
  ответственности (pickup_photo_url/delivery_photo_url), компенсация курьеру (cancel_fee_kop);
- incident: parcel_id/order_id — спор по доставке и по такси-заказу (типы были заведены,
  но недостижимы: код требовал booking_id);
- taxiapplication: сроки документов (osago_until/permit_until) + допуск (docs_expired);
- commissiondebt: declare_count — лимит «Я оплатил» (иначе комиссию можно не платить вообще);
- tariff: ночной коэффициент (night_k/night_from_hour/night_to_hour);
- sosevent: handled_at/handled_by/handled_note/escalated_at + индексы (у админа не было
  ни списка сигналов, ни отметки «принял»).

Аддитивно, ИДЕМПОТЕНТНО (паттерн g_tips): свежая БД (create_all) → no-op; прод → add_column.

Прод: `alembic upgrade head`.

Revision ID: o_gaps_taxi_courier
Revises: n_incident_evidence
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "o_gaps_taxi_courier"
down_revision = "n_incident_evidence"
branch_labels = None
depends_on = None

# (таблица, колонка, тип, kwargs) — порядок не важен, каждая проверяется отдельно.
_COLUMNS = [
    ("instantorder", "comment", sa.String(), {"nullable": False, "server_default": ""}),
    ("instantorder", "entrance", sa.String(), {"nullable": False, "server_default": ""}),
    ("instantorder", "for_name", sa.String(), {"nullable": False, "server_default": ""}),
    ("instantorder", "for_phone", sa.String(), {"nullable": False, "server_default": ""}),
    ("instantorder", "wait_until", sa.DateTime(), {"nullable": True}),
    ("instantorder", "retry_count", sa.Integer(), {"nullable": False, "server_default": "0"}),
    ("instantorder", "lost_item_until", sa.DateTime(), {"nullable": True}),
    ("instantorder", "thanked", sa.Boolean(), {"nullable": False, "server_default": sa.false()}),
    ("parceldelivery", "return_reason", sa.String(), {"nullable": False, "server_default": ""}),
    ("parceldelivery", "returned_at", sa.DateTime(), {"nullable": True}),
    ("parceldelivery", "delivery_attempts", sa.Integer(), {"nullable": False, "server_default": "0"}),
    ("parceldelivery", "pickup_photo_url", sa.String(), {"nullable": False, "server_default": ""}),
    ("parceldelivery", "delivery_photo_url", sa.String(), {"nullable": False, "server_default": ""}),
    ("parceldelivery", "cancel_fee_kop", sa.BigInteger(), {"nullable": False, "server_default": "0"}),
    ("incident", "parcel_id", sa.Integer(), {"nullable": True}),
    ("incident", "order_id", sa.Integer(), {"nullable": True}),
    ("taxiapplication", "osago_until", sa.Date(), {"nullable": True}),
    ("taxiapplication", "permit_until", sa.Date(), {"nullable": True}),
    ("taxiapplication", "docs_expired", sa.Boolean(), {"nullable": False, "server_default": sa.false()}),
    ("taxiapplication", "docs_warned_at", sa.DateTime(), {"nullable": True}),
    ("commissiondebt", "declare_count", sa.Integer(), {"nullable": False, "server_default": "0"}),
    ("tariff", "night_k", sa.Float(), {"nullable": False, "server_default": "1.0"}),
    ("tariff", "night_from_hour", sa.Integer(), {"nullable": False, "server_default": "22"}),
    ("tariff", "night_to_hour", sa.Integer(), {"nullable": False, "server_default": "6"}),
    ("sosevent", "handled_at", sa.DateTime(), {"nullable": True}),
    ("sosevent", "handled_by", sa.Integer(), {"nullable": True}),
    ("sosevent", "handled_note", sa.String(), {"nullable": False, "server_default": ""}),
    ("sosevent", "escalated_at", sa.DateTime(), {"nullable": True}),
]

# (имя индекса, таблица, колонки) — горячие фильтры новых фоновых задач и админ-списков.
_INDEXES = [
    ("ix_instantorder_wait_until", "instantorder", ["wait_until"]),
    ("ix_incident_parcel_id", "incident", ["parcel_id"]),
    ("ix_incident_order_id", "incident", ["order_id"]),
    ("ix_taxiapplication_docs_expired", "taxiapplication", ["docs_expired"]),
    ("ix_sosevent_status", "sosevent", ["status"]),
    ("ix_sosevent_created_at", "sosevent", ["created_at"]),
]


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
    cache: dict = {}
    for table, name, type_, kwargs in _COLUMNS:
        cols = cache.setdefault(table, _columns(bind, table))
        if name not in cols:
            op.add_column(table, sa.Column(name, type_, **kwargs))
    for ix_name, table, cols in _INDEXES:
        if ix_name not in _indexes(bind, table) and _columns(bind, table):
            op.create_index(ix_name, table, cols)


def downgrade() -> None:
    bind = op.get_bind()
    for ix_name, table, _cols in _INDEXES:
        if ix_name in _indexes(bind, table):
            op.drop_index(ix_name, table_name=table)
    by_table: dict = {}
    for table, name, _t, _k in _COLUMNS:
        by_table.setdefault(table, []).append(name)
    for table, names in by_table.items():
        existing = _columns(bind, table)
        with op.batch_alter_table(table) as b:
            for name in names:
                if name in existing:
                    b.drop_column(name)
